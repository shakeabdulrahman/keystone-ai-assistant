package io.github.shakeabdulrahman.keystone.llm

import io.github.shakeabdulrahman.keystone.llm.schema.JsonSchemaGenerator
import io.github.shakeabdulrahman.keystone.llm.schema.JsonSchemaValidator
import io.github.shakeabdulrahman.keystone.llm.testing.FakeProvider
import io.github.shakeabdulrahman.keystone.llm.testing.answer
import io.github.shakeabdulrahman.keystone.llm.testing.testClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Structured output with built-in serializers (no compiler plugin needed). See `serializable/` for data classes. */
class StructuredOutputTest {
    private val model = ModelRef("a", "m")
    private val listOfStrings = ListSerializer(String.serializer())
    private val schema = JsonSchemaGenerator.from(listOfStrings.descriptor)
    private fun request() = ChatRequest.of(model, Message.user("Three fruits"))

    private fun client(text: String, caps: ProviderCapabilities = io.github.shakeabdulrahman.keystone.llm.testing.FullCapabilities, finish: FinishReason = FinishReason.Stop): Pair<LlmClient, FakeProvider> {
        val provider = FakeProvider("a", caps) { _, _ -> answer(text, finish = finish) }
        return testClient(provider).first to provider
    }

    @Test
    fun validJsonIsDecodedAndEnforcementIsReported() = runTest {
        val (llm, provider) = client("[\"apple\",\"mango\",\"fig\"]")
        val result = llm.chatStructured(request(), listOfStrings, schema)
        assertEquals(listOf("apple", "mango", "fig"), result.value)
        assertEquals(StructuredEnforcement.ProviderSchema, result.enforcement)
        assertIs<ResponseFormat.JsonSchema>(provider.requests.single().request.responseFormat)
    }

    @Test
    fun malformedJsonIsATypedError() = runTest {
        val (llm, _) = client("[\"apple\",")
        val e = assertFailsWith<StructuredOutputException> { llm.chatStructured(request(), listOfStrings, schema) }
        assertIs<StructuredOutputException.Reason.MalformedJson>(e.reason)
        assertEquals("[\"apple\",", e.rawText)
        assertEquals(LlmErrorKind.StructuredOutput, e.kind)
    }

    @Test
    fun schemaMismatchListsViolations() = runTest {
        val (llm, _) = client("[\"apple\", 3]")
        val e = assertFailsWith<StructuredOutputException> { llm.chatStructured(request(), listOfStrings, schema) }
        val reason = assertIs<StructuredOutputException.Reason.SchemaMismatch>(e.reason)
        assertEquals("$[1]", reason.violations.single().path)
    }

    @Test
    fun truncatedAnswerIsReportedAsTruncated() = runTest {
        val (llm, _) = client("[\"apple\"]", finish = FinishReason.Length)
        val e = assertFailsWith<StructuredOutputException> { llm.chatStructured(request(), listOfStrings, schema) }
        assertEquals(StructuredOutputException.Reason.Truncated, e.reason)
    }

    @Test
    fun withoutDowngradeAJsonModeOnlyProviderIsUnsupported() = runTest {
        val jsonModeOnly = ProviderCapabilities(Support.Native, Support.Native, StructuredOutputSupport.JsonMode)
        val (llm, provider) = client("[]", jsonModeOnly)
        val e = assertFailsWith<LlmException> { llm.chatStructured(request(), listOfStrings, schema) }
        assertEquals(LlmErrorKind.Unsupported, e.kind)
        assertEquals(0, provider.calls)
    }

    @Test
    fun downgradeToJsonModeAddsSchemaInstructionAndStillValidates() = runTest {
        val jsonModeOnly = ProviderCapabilities(Support.Native, Support.Native, StructuredOutputSupport.JsonMode)
        val (llm, provider) = client("[\"kiwi\"]", jsonModeOnly)
        val result = llm.chatStructured(request(), listOfStrings, schema, allowDowngrade = true)
        assertEquals(listOf("kiwi"), result.value)
        assertEquals(StructuredEnforcement.ProviderJsonMode, result.enforcement)
        val sent = provider.requests.single().request
        assertEquals(ResponseFormat.JsonMode, sent.responseFormat)
        assertTrue((sent.messages.first() as Message.System).text.contains("JSON Schema"))
    }

    @Test
    fun promptOnlyDowngradeStripsCodeFences() = runTest {
        val none = ProviderCapabilities(Support.Native, Support.Native, StructuredOutputSupport.None)
        val (llm, _) = client("```json\n[\"kiwi\"]\n```", none)
        val result = llm.chatStructured(request(), listOfStrings, schema, allowDowngrade = true)
        assertEquals(listOf("kiwi"), result.value)
        assertEquals(StructuredEnforcement.PromptOnly, result.enforcement)
    }

    @Test
    fun generatorHandlesNullableAndMaps() {
        val s = JsonSchemaGenerator.from(MapSerializer(String.serializer(), Int.serializer().nullable).descriptor)
        assertEquals("object", (s["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        val values = s["additionalProperties"]!!.jsonObject
        assertEquals(Json.parseToJsonElement("[\"integer\",\"null\"]"), values["type"])
        assertTrue(JsonSchemaValidator.validate(Json.parseToJsonElement("{\"a\":1,\"b\":null}"), s).isEmpty())
        assertEquals(1, JsonSchemaValidator.validate(Json.parseToJsonElement("{\"a\":\"x\"}"), s).size)
    }

    @Test
    fun validatorChecksRequiredEnumAdditionalPropertiesAndIntegers() {
        val s: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"n":{"type":"integer"},"c":{"type":"string","enum":["a","b"]}},
               "required":["n"],"additionalProperties":false}""",
        ).jsonObject
        fun v(json: String) = JsonSchemaValidator.validate(Json.parseToJsonElement(json), s).map { "${it.path}: ${it.message}" }
        assertEquals(emptyList(), v("""{"n": 2, "c": "a"}"""))
        assertEquals(emptyList(), v("""{"n": 2.0}"""))
        assertEquals(listOf("$: missing required property 'n'"), v("""{"c": "a"}"""))
        assertEquals(listOf("$.n: expected integer, got number"), v("""{"n": 2.5}"""))
        assertEquals(listOf("$.c: value not in enum"), v("""{"n": 1, "c": "z"}"""))
        assertEquals(listOf("$.x: property not allowed"), v("""{"n": 1, "x": true}"""))
        assertEquals(listOf("$.n: expected integer, got string"), v("""{"n": "1"}"""))
    }

    @Test
    fun nullableKeywordFromOpenApiIsHonoured() {
        val s = buildJsonObject {
            put("type", "string")
            put("nullable", true)
        }
        assertTrue(JsonSchemaValidator.validate(kotlinx.serialization.json.JsonNull, s).isEmpty())
    }
}
