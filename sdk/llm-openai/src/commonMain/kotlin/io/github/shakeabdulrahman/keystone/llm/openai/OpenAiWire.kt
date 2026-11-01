package io.github.shakeabdulrahman.keystone.llm.openai

import io.github.shakeabdulrahman.keystone.llm.FinishReason
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ProviderEvent
import io.github.shakeabdulrahman.keystone.llm.ProviderRequest
import io.github.shakeabdulrahman.keystone.llm.ResponseFormat
import io.github.shakeabdulrahman.keystone.llm.ToolArguments
import io.github.shakeabdulrahman.keystone.llm.ToolChoice
import io.github.shakeabdulrahman.keystone.llm.Usage
import io.github.shakeabdulrahman.keystone.llm.http.HttpErrors
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Chat Completions wire format. Built and read as JSON trees rather than generated DTOs:
 * these servers add and rename fields often, and a tree tolerates unknown fields and partial
 * objects without a schema change.
 */
internal object OpenAiWire {

    fun requestBody(request: ProviderRequest, options: OpenAiCompatibleOptions): JsonObject {
        val r = request.request
        val base = buildJsonObject {
            put("model", request.model)
            put("messages", JsonArray(r.messages.map(::message)))
            if (r.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in r.tools) addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        }
                    }
                }
                put("tool_choice", toolChoice(r.toolChoice))
            }
            when (val format = r.responseFormat) {
                ResponseFormat.Text -> Unit
                ResponseFormat.JsonMode -> putJsonObject("response_format") { put("type", "json_object") }
                is ResponseFormat.JsonSchema -> putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", format.name)
                        put("schema", format.schema)
                        put("strict", format.strict)
                    }
                }
            }
            r.maxOutputTokens?.let { put(options.maxTokensField, it) }
            r.temperature?.let { put("temperature", it) }
            r.topP?.let { put("top_p", it) }
            if (r.stopSequences.isNotEmpty()) put("stop", JsonArray(r.stopSequences.map(::JsonPrimitive)))
            put("stream", true)
            if (options.includeStreamUsage) putJsonObject("stream_options") { put("include_usage", true) }
        }
        // The escape hatch goes last, so it can override anything above.
        val extra = request.options ?: return base
        return JsonObject(base + extra)
    }

    private fun message(m: Message): JsonObject = when (m) {
        is Message.System -> buildJsonObject {
            put("role", "system")
            put("content", m.text)
        }
        is Message.User -> buildJsonObject {
            put("role", "user")
            put("content", m.text)
        }
        is Message.Assistant -> buildJsonObject {
            put("role", "assistant")
            // OpenAI expects null content (not "") on a turn that only calls tools.
            put("content", if (m.text.isEmpty() && m.toolCalls.isNotEmpty()) JsonNull else JsonPrimitive(m.text))
            if (m.toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    for (call in m.toolCalls) addJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", call.name)
                            put("arguments", (call.arguments as? ToolArguments.Valid)?.json?.toString() ?: call.rawArguments)
                        }
                    }
                }
            }
        }
        is Message.ToolResult -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", m.callId)
            put("content", m.content)
        }
    }

    private fun toolChoice(choice: ToolChoice): JsonElement = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Specific -> buildJsonObject {
            put("type", "function")
            putJsonObject("function") { put("name", choice.toolName) }
        }
    }

    /** `{"error": {"code"|"type": ..., "message": ...}}` -> (code, message). */
    fun parseError(body: String): Pair<String?, String?> {
        val error = (Json.parseToJsonElement(body) as? JsonObject)?.get("error") ?: return null to null
        if (error !is JsonObject) return null to (error as? JsonPrimitive)?.contentOrNull
        val code = error.string("code") ?: error.string("type")
        return code to error.string("message")
    }

    fun finishReason(raw: String): FinishReason = when (raw) {
        "stop" -> FinishReason.Stop
        "length" -> FinishReason.Length
        "tool_calls", "function_call" -> FinishReason.ToolCalls
        "content_filter" -> FinishReason.ContentFilter
        else -> FinishReason.Other(raw)
    }

    internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

/**
 * Maps the `data:` payloads of one stream to [ProviderEvent]s.
 *
 * - text: `choices[0].delta.content`; only the first choice is read (`n > 1` is unsupported);
 * - tool calls: `choices[0].delta.tool_calls[]` pieces, keyed by `index`;
 * - usage: `usage` (OpenAI, with `stream_options.include_usage`) or `x_groq.usage` (Groq);
 * - finish: `finish_reason` is remembered and emitted at the end, because the usage chunk
 *   arrives after it;
 * - `[DONE]` ends the stream. Without it, the stream counts as complete only if a
 *   `finish_reason` was seen; otherwise no Finish is emitted and the client treats the
 *   stream as cut off.
 */
internal class OpenAiStreamMapper(private val provider: String) {
    private var finish: FinishReason? = null
    private var sentMetadata = false
    var done: Boolean = false
        private set

    fun map(data: String): List<ProviderEvent> {
        if (data.trim() == "[DONE]") {
            done = true
            return emptyList()
        }
        val chunk = try {
            Json.parseToJsonElement(data) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw LlmException(LlmErrorKind.MalformedResponse, "$provider sent a stream chunk that isn't a JSON object", provider = provider)

        with(OpenAiWire) {
            chunk.obj("error")?.let { error ->
                val code = error.string("code") ?: error.string("type")
                throw LlmException(
                    kind = if (code == "rate_limit_exceeded") LlmErrorKind.RateLimited else LlmErrorKind.ServerError,
                    message = "$provider reported an error mid-stream" + (code?.let { " ($it)" } ?: ""),
                    provider = provider,
                    providerCode = code,
                    providerMessage = error.string("message")?.take(HttpErrors.MAX_PROVIDER_MESSAGE),
                )
            }
            val out = ArrayList<ProviderEvent>(2)
            if (!sentMetadata && (chunk.string("id") != null || chunk.string("model") != null)) {
                sentMetadata = true
                out += ProviderEvent.Metadata(responseId = chunk.string("id"), model = chunk.string("model"))
            }
            (chunk.obj("usage") ?: chunk.obj("x_groq")?.obj("usage"))?.let { u ->
                out += ProviderEvent.UsageUpdate(
                    Usage(
                        inputTokens = u.long("prompt_tokens"),
                        outputTokens = u.long("completion_tokens"),
                        cachedInputTokens = u.obj("prompt_tokens_details")?.long("cached_tokens"),
                    ),
                )
            }
            val choice = (chunk["choices"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { (it.int("index") ?: 0) == 0 }
                ?: return out
            choice.obj("delta")?.let { delta ->
                delta.string("content")?.let { if (it.isNotEmpty()) out += ProviderEvent.TextDelta(it) }
                (delta["tool_calls"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEachIndexed { position, piece ->
                    val function = piece.obj("function")
                    out += ProviderEvent.ToolCallChunk(
                        index = piece.int("index") ?: position,
                        id = piece.string("id"),
                        name = function?.string("name"),
                        argumentsDelta = function?.string("arguments") ?: "",
                    )
                }
            }
            choice.string("finish_reason")?.let { finish = OpenAiWire.finishReason(it) }
            return out
        }
    }

    /** The Finish event at end of stream, or `null` if the stream looks cut off. */
    fun end(): ProviderEvent.Finish? = when {
        done -> ProviderEvent.Finish(finish ?: FinishReason.Unknown)
        finish != null -> ProviderEvent.Finish(finish!!)
        else -> null
    }
}
