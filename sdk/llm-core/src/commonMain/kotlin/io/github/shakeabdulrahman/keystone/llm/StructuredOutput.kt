package io.github.shakeabdulrahman.keystone.llm

import io.github.shakeabdulrahman.keystone.llm.schema.JsonSchemaGenerator
import io.github.shakeabdulrahman.keystone.llm.schema.JsonSchemaValidator
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** A typed structured answer, plus the full response it came from. */
public data class StructuredResponse<T>(
    val value: T,
    val response: ChatResponse,
) {
    /** How the schema was enforced. Client-side validation always happens as well. */
    public val enforcement: StructuredEnforcement? get() = response.metadata.structuredEnforcement
}

/**
 * Asks for JSON matching [schema] and decodes it with [deserializer].
 *
 * Validation: the answer is always parsed and checked against [schema] on the client (the
 * subset in [JsonSchemaValidator]) before decoding. Whether the *provider* also enforced the
 * schema is in [StructuredResponse.enforcement]. With [allowDowngrade] = false, a target that
 * can't enforce schemas fails with [LlmErrorKind.Unsupported] (and may fail over).
 *
 * A malformed answer is not retried automatically: it throws [StructuredOutputException]
 * so you can decide (retry, repair, or show an error).
 *
 * Existing [ChatRequest.tools] are kept; if the model answers with tool calls instead of
 * JSON, the reason is [StructuredOutputException.Reason.NoContent].
 */
public suspend fun <T> LlmClient.chatStructured(
    request: ChatRequest,
    deserializer: DeserializationStrategy<T>,
    schema: JsonObject,
    name: String = "response",
    strict: Boolean = false,
    allowDowngrade: Boolean = false,
    json: Json = LenientDecoding,
): StructuredResponse<T> {
    val format = ResponseFormat.JsonSchema(name, schema, strict, allowDowngrade)
    val response = chat(request.copy(responseFormat = format))
    return StructuredResponse(decodeStructured(response, schema, deserializer, json), response)
}

/** [chatStructured] with the schema derived from [T]'s serializer (see [JsonSchemaGenerator]). */
public suspend inline fun <reified T> LlmClient.chatStructured(
    request: ChatRequest,
    name: String = "response",
    strict: Boolean = false,
    allowDowngrade: Boolean = false,
): StructuredResponse<T> {
    val serializer: KSerializer<T> = serializer()
    return chatStructured(request, serializer, JsonSchemaGenerator.from(serializer.descriptor), name, strict, allowDowngrade)
}

/** Schema validation runs first (and rejects extra keys where the schema forbids them), so decoding can tolerate them. */
@PublishedApi
internal val LenientDecoding: Json = Json { ignoreUnknownKeys = true }

internal fun <T> decodeStructured(
    response: ChatResponse,
    schema: JsonObject,
    deserializer: DeserializationStrategy<T>,
    json: Json,
): T {
    fun fail(reason: StructuredOutputException.Reason, message: String, cause: Throwable? = null): Nothing =
        throw StructuredOutputException(reason, message, response.text, response, cause)

    if (response.finishReason == FinishReason.Length) {
        fail(StructuredOutputException.Reason.Truncated, "Answer hit the token limit; JSON is incomplete")
    }
    val text = stripFences(response.text, response.metadata.structuredEnforcement)
    if (text.isBlank()) fail(StructuredOutputException.Reason.NoContent, "The model returned no JSON")
    val element: JsonElement = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        fail(StructuredOutputException.Reason.MalformedJson(e.message?.take(200) ?: "invalid JSON"), "The answer is not valid JSON", e)
    }
    val violations = JsonSchemaValidator.validate(element, schema)
    if (violations.isNotEmpty()) {
        fail(StructuredOutputException.Reason.SchemaMismatch(violations), "The answer doesn't match the schema (${violations.size} problem(s))")
    }
    return try {
        json.decodeFromJsonElement(deserializer, element)
    } catch (e: SerializationException) {
        fail(StructuredOutputException.Reason.DecodingFailed(e.message?.take(200) ?: "decoding failed"), "The answer couldn't be decoded", e)
    } catch (e: IllegalArgumentException) {
        fail(StructuredOutputException.Reason.DecodingFailed(e.message?.take(200) ?: "decoding failed"), "The answer couldn't be decoded", e)
    }
}

/** Models prompted (not constrained) for JSON often wrap it in a Markdown fence. */
private fun stripFences(text: String, enforcement: StructuredEnforcement?): String {
    val t = text.trim()
    if (enforcement != StructuredEnforcement.PromptOnly || !t.startsWith("```")) return t
    return t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
}
