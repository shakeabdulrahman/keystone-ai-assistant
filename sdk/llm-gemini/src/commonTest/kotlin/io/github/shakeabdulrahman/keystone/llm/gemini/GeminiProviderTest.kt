package io.github.shakeabdulrahman.keystone.llm.gemini

import io.github.shakeabdulrahman.keystone.llm.Auth
import io.github.shakeabdulrahman.keystone.llm.ChatRequest
import io.github.shakeabdulrahman.keystone.llm.FinishReason
import io.github.shakeabdulrahman.keystone.llm.LlmClient
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ModelRef
import io.github.shakeabdulrahman.keystone.llm.ResponseFormat
import io.github.shakeabdulrahman.keystone.llm.RetryPolicy
import io.github.shakeabdulrahman.keystone.llm.Route
import io.github.shakeabdulrahman.keystone.llm.Tool
import io.github.shakeabdulrahman.keystone.llm.ToolChoice
import io.github.shakeabdulrahman.keystone.llm.Usage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Fixtures are hand-written from the documented `streamGenerateContent?alt=sse` format and the
 * payloads Keystone's backend handles; they are not captured from live traffic.
 */
class GeminiProviderTest {
    private val model = ModelRef("gemini", "gemini-test-flash")

    private fun client(transport: FakeHttpTransport) = LlmClient {
        provider(GeminiProvider(Auth.ApiKey("test-key-not-real"), transport = transport))
        retry = RetryPolicy.None
    }

    private val weather = Tool(
        "get_weather", "Weather for a city",
        Json.parseToJsonElement(
            """{"type":"object","properties":{"city":{"type":"string"},"unit":{"anyOf":[{"type":"string","enum":["C","F"]},{"type":"null"}],"default":null}},
               "required":["city"],"additionalProperties":false}""",
        ).jsonObject,
    )

    @Test
    fun textStreamWithUsageAndThoughtsSkipped() = runTest {
        val stream = sse(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"thinking...","thought":true}]}}],"modelVersion":"gemini-test-flash-001","responseId":"r-1"}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Namaste, "}]}}],"responseId":"r-1"}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Chennai ☀"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":4,"thoughtsTokenCount":6,"totalTokenCount":19}}""",
        ).replace("\n\n", "\r\n\r\n") // Gemini terminates events with CRLF
        for (size in listOf(1, 5, 4096)) {
            val transport = FakeHttpTransport(chunkSize = size) { FakeHttpTransport.Canned(200, stream) }
            val response = client(transport).chat(ChatRequest.of(model, Message.user("hi")))
            assertEquals("Namaste, Chennai ☀", response.text)
            assertEquals(FinishReason.Stop, response.finishReason)
            assertEquals(Usage(9, 10), response.usage) // output = candidates + thoughts
            assertEquals("gemini-test-flash-001", response.metadata.model)
            assertEquals("r-1", response.metadata.responseId)
        }
    }

    @Test
    fun requestUsesHeaderKeySystemInstructionAndConvertedSchemas() = runTest {
        val stream = sse("""{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""")
        val transport = FakeHttpTransport { FakeHttpTransport.Canned(200, stream) }
        val request = ChatRequest(
            messages = listOf(Message.system("Be brief."), Message.user("Weather?")),
            route = Route(model),
            tools = listOf(weather),
            toolChoice = ToolChoice.Specific("get_weather"),
            maxOutputTokens = 50,
            temperature = 0.1,
        )
        client(transport).chat(request)
        val sent = transport.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-test-flash:streamGenerateContent?alt=sse", sent.url)
        assertEquals("test-key-not-real", sent.headers["x-goog-api-key"])
        assertTrue("key" !in sent.url)
        val expected = Json.parseToJsonElement(
            """
            {"systemInstruction":{"parts":[{"text":"Be brief."}]},
             "contents":[{"role":"user","parts":[{"text":"Weather?"}]}],
             "tools":[{"functionDeclarations":[{"name":"get_weather","description":"Weather for a city",
               "parameters":{"type":"OBJECT","properties":{"city":{"type":"STRING"},"unit":{"type":"STRING","enum":["C","F"],"nullable":true}},"required":["city"]}}]}],
             "toolConfig":{"functionCallingConfig":{"mode":"ANY","allowedFunctionNames":["get_weather"]}},
             "generationConfig":{"maxOutputTokens":50,"temperature":0.1}}
            """,
        )
        assertEquals(expected, Json.parseToJsonElement(sent.body!!.decodeToString()))
    }

    @Test
    fun functionCallsKeepTheirThoughtSignatureAndRoundTrip() = runTest {
        val first = sse(
            """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_weather","args":{"city":"Chennai"}},"thoughtSignature":"sig-123"}]},"finishReason":"STOP"}]}""",
        )
        val transport = FakeHttpTransport { FakeHttpTransport.Canned(200, first) }
        val llm = client(transport)
        val response = llm.chat(ChatRequest(listOf(Message.user("Weather?")), route = Route(model), tools = listOf(weather)))
        assertEquals(FinishReason.ToolCalls, response.finishReason) // Gemini says STOP
        val call = response.toolCalls.single()
        assertEquals("call_0", call.id)
        assertEquals("sig-123", call.providerMetadata[GeminiWire.THOUGHT_SIGNATURE])
        assertEquals(JsonPrimitive("Chennai"), call.argumentsOrNull!!["city"])

        // Second turn: the call goes back with its signature; results are merged in one user turn.
        llm.chat(
            ChatRequest(
                messages = listOf(
                    Message.user("Weather?"),
                    response.message,
                    Message.ToolResult(call.id, "get_weather", """{"tempC":31}"""),
                    Message.ToolResult("call_1", "get_weather", "service down", isError = true),
                ),
                route = Route(model),
                tools = listOf(weather),
            ),
        )
        val contents = Json.parseToJsonElement(transport.requests[1].body!!.decodeToString()).jsonObject["contents"]
        val expected = Json.parseToJsonElement(
            """
            [{"role":"user","parts":[{"text":"Weather?"}]},
             {"role":"model","parts":[{"functionCall":{"name":"get_weather","args":{"city":"Chennai"}},"thoughtSignature":"sig-123"}]},
             {"role":"user","parts":[
               {"functionResponse":{"name":"get_weather","response":{"tempC":31}}},
               {"functionResponse":{"name":"get_weather","response":{"error":"service down"}}}]}]
            """,
        )
        assertEquals(expected, contents)
    }

    @Test
    fun jsonSchemaOutputUsesResponseSchema() = runTest {
        val stream = sse("""{"candidates":[{"content":{"parts":[{"text":"{}"}]},"finishReason":"STOP"}]}""")
        val transport = FakeHttpTransport { FakeHttpTransport.Canned(200, stream) }
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"n":{"type":["integer","null"]}},"additionalProperties":false}""").jsonObject
        client(transport).chat(ChatRequest.of(model, Message.user("x")).copy(responseFormat = ResponseFormat.JsonSchema("r", schema)))
        val config = Json.parseToJsonElement(transport.requests.single().body!!.decodeToString()).jsonObject["generationConfig"]
        assertEquals(
            Json.parseToJsonElement("""{"responseMimeType":"application/json","responseSchema":{"type":"OBJECT","properties":{"n":{"type":"INTEGER","nullable":true}}}}"""),
            config,
        )
    }

    @Test
    fun blockedPromptMaxTokensAndCutOffStreams() = runTest {
        val blocked = sse("""{"promptFeedback":{"blockReason":"SAFETY"},"usageMetadata":{"promptTokenCount":5}}""")
        val e = assertFailsWith<LlmException> { client(FakeHttpTransport { FakeHttpTransport.Canned(200, blocked) }).chat(ChatRequest.of(model, Message.user("x"))) }
        assertEquals(LlmErrorKind.ContentFiltered, e.kind)

        val long = sse("""{"candidates":[{"content":{"parts":[{"text":"abc"}]},"finishReason":"MAX_TOKENS"}]}""")
        assertEquals(FinishReason.Length, client(FakeHttpTransport { FakeHttpTransport.Canned(200, long) }).chat(ChatRequest.of(model, Message.user("x"))).finishReason)

        val cut = sse("""{"candidates":[{"content":{"parts":[{"text":"abc"}]}}]}""")
        val e2 = assertFailsWith<LlmException> { client(FakeHttpTransport { FakeHttpTransport.Canned(200, cut) }).chat(ChatRequest.of(model, Message.user("x"))) }
        assertEquals(LlmErrorKind.Network, e2.kind)
    }

    @Test
    fun httpErrorsUseGoogleStatus() = runTest {
        val transport = FakeHttpTransport {
            FakeHttpTransport.Canned(503, """{"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}""", mapOf("Retry-After" to "4"))
        }
        val e = assertFailsWith<LlmException> { client(transport).chat(ChatRequest.of(model, Message.user("x"))) }
        assertEquals(LlmErrorKind.ServerError, e.kind)
        assertEquals("UNAVAILABLE", e.providerCode)
        assertEquals(4.seconds, e.retryAfter)

        val notFound = FakeHttpTransport { FakeHttpTransport.Canned(404, """{"error":{"code":404,"message":"models/x is not found","status":"NOT_FOUND"}}""") }
        assertEquals(LlmErrorKind.NotFound, assertFailsWith<LlmException> { client(notFound).chat(ChatRequest.of(model, Message.user("x"))) }.kind)
    }

    @Test
    fun modelNamesThatWouldAlterTheUrlAreRejected() = runTest {
        val transport = FakeHttpTransport { error("must not be called") }
        val e = assertFailsWith<LlmException> { client(transport).chat(ChatRequest.of(ModelRef("gemini", "../x?key=1"), Message.user("x"))) }
        assertEquals(LlmErrorKind.InvalidRequest, e.kind)
        assertTrue(transport.requests.isEmpty())
    }
}
