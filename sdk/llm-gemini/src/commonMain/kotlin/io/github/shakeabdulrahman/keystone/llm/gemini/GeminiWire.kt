package io.github.shakeabdulrahman.keystone.llm.gemini

import io.github.shakeabdulrahman.keystone.llm.FinishReason
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ProviderEvent
import io.github.shakeabdulrahman.keystone.llm.ProviderRequest
import io.github.shakeabdulrahman.keystone.llm.ResponseFormat
import io.github.shakeabdulrahman.keystone.llm.ToolArguments
import io.github.shakeabdulrahman.keystone.llm.ToolCall
import io.github.shakeabdulrahman.keystone.llm.ToolChoice
import io.github.shakeabdulrahman.keystone.llm.Usage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal object GeminiWire {
    /** Key of Gemini's thought signature in [ToolCall.providerMetadata]. */
    const val THOUGHT_SIGNATURE = "gemini.thoughtSignature"

    fun requestBody(request: ProviderRequest): JsonObject {
        val r = request.request
        val system = r.messages.filterIsInstance<Message.System>().joinToString("\n\n") { it.text }
        val base = buildJsonObject {
            if (system.isNotEmpty()) putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            put("contents", contents(r.messages))
            if (r.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            for (tool in r.tools) addJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                val schema = geminiSchema(tool.parameters)
                                // Gemini rejects an object schema without properties.
                                if ((schema["properties"] as? JsonObject)?.isNotEmpty() == true) put("parameters", schema)
                            }
                        }
                    }
                }
                putJsonObject("toolConfig") {
                    putJsonObject("functionCallingConfig") {
                        when (val choice = r.toolChoice) {
                            ToolChoice.Auto -> put("mode", "AUTO")
                            ToolChoice.None -> put("mode", "NONE")
                            ToolChoice.Required -> put("mode", "ANY")
                            is ToolChoice.Specific -> {
                                put("mode", "ANY")
                                putJsonArray("allowedFunctionNames") { add(JsonPrimitive(choice.toolName)) }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                r.maxOutputTokens?.let { put("maxOutputTokens", it) }
                r.temperature?.let { put("temperature", it) }
                r.topP?.let { put("topP", it) }
                if (r.stopSequences.isNotEmpty()) put("stopSequences", JsonArray(r.stopSequences.map(::JsonPrimitive)))
                when (val format = r.responseFormat) {
                    ResponseFormat.Text -> Unit
                    ResponseFormat.JsonMode -> put("responseMimeType", "application/json")
                    is ResponseFormat.JsonSchema -> {
                        put("responseMimeType", "application/json")
                        put("responseSchema", geminiSchema(format.schema))
                    }
                }
            }
        }
        val extra = request.options ?: return base
        return JsonObject(base + extra)
    }

    /**
     * Roles are `user` and `model`. Tool results become `functionResponse` parts, and
     * consecutive results are merged into one user turn, as Gemini expects. System messages
     * go to `systemInstruction` instead.
     */
    private fun contents(messages: List<Message>): JsonArray {
        val turns = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (m in messages) {
            when (m) {
                is Message.System -> Unit
                is Message.User -> turns += "user" to mutableListOf(textPart(m.text))
                is Message.Assistant -> {
                    val parts = mutableListOf<JsonObject>()
                    if (m.text.isNotEmpty()) parts += textPart(m.text)
                    m.toolCalls.forEach { parts += functionCallPart(it) }
                    if (parts.isNotEmpty()) turns += "model" to parts
                }
                is Message.ToolResult -> {
                    val part = buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", m.toolName)
                            put("response", toolResponse(m))
                        }
                    }
                    val last = turns.lastOrNull()
                    if (last != null && last.first == "user" && last.second.all { "functionResponse" in it }) {
                        last.second += part
                    } else {
                        turns += "user" to mutableListOf(part)
                    }
                }
            }
        }
        return JsonArray(
            turns.map { (role, parts) ->
                buildJsonObject {
                    put("role", role)
                    put("parts", JsonArray(parts))
                }
            },
        )
    }

    private fun textPart(text: String) = buildJsonObject { put("text", text) }

    private fun functionCallPart(call: ToolCall) = buildJsonObject {
        putJsonObject("functionCall") {
            put("name", call.name)
            val args = (call.arguments as? ToolArguments.Valid)?.json
                ?: runCatching { Json.parseToJsonElement(call.rawArguments) as? JsonObject }.getOrNull()
                ?: JsonObject(emptyMap())
            put("args", args)
        }
        call.providerMetadata[THOUGHT_SIGNATURE]?.let { put("thoughtSignature", it) }
    }

    /** Gemini wants an object: JSON objects pass through, anything else is wrapped. */
    private fun toolResponse(m: Message.ToolResult): JsonObject {
        val parsed = runCatching { Json.parseToJsonElement(m.content) }.getOrNull()
        val value: JsonElement = parsed ?: JsonPrimitive(m.content)
        return when {
            m.isError -> buildJsonObject { put("error", value) }
            value is JsonObject -> value
            else -> buildJsonObject { put("result", value) }
        }
    }

    private val KEEP = setOf("type", "description", "enum", "properties", "required", "items", "nullable")

    /**
     * JSON Schema -> the OpenAPI subset Gemini accepts. `anyOf [X, null]` and
     * `type: [X, "null"]` become X + `nullable`; unknown keywords (`additionalProperties`, `format`,
     * `default`, `minLength`, ...) are dropped; type names are upper-cased (`STRING`, ...).
     * Ported from Keystone's backend, where it runs in production.
     */
    fun geminiSchema(schema: JsonObject): JsonObject {
        var node: Map<String, JsonElement> = schema
        var nullable = false
        (node["anyOf"] as? JsonArray)?.let { anyOf ->
            val options = anyOf.filterIsInstance<JsonObject>().filter { (it["type"] as? JsonPrimitive)?.contentOrNull != "null" }
            nullable = options.size < anyOf.size
            node = (options.firstOrNull() ?: JsonObject(emptyMap())) + node.filterKeys { it != "anyOf" && it != "default" }
        }
        (node["type"] as? JsonArray)?.let { types ->
            val names = types.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            nullable = nullable || "null" in names
            node = node + ("type" to JsonPrimitive(names.firstOrNull { it != "null" } ?: "string"))
        }
        return buildJsonObject {
            for ((key, value) in node) {
                if (key !in KEEP) continue
                when (key) {
                    "properties" -> putJsonObject("properties") {
                        (value as? JsonObject)?.forEach { (name, child) -> if (child is JsonObject) put(name, geminiSchema(child)) }
                    }
                    "items" -> if (value is JsonObject) put("items", geminiSchema(value))
                    "type" -> put("type", (value as JsonPrimitive).content.uppercase())
                    else -> put(key, value)
                }
            }
            if (nullable) put("nullable", true)
        }
    }

    /** `{"error":{"code":429,"message":"...","status":"RESOURCE_EXHAUSTED"}}` -> (status, message). */
    fun parseError(body: String): Pair<String?, String?> {
        val error = (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject ?: return null to null
        return error.str("status") to error.str("message")
    }

    fun finishReason(raw: String): FinishReason = when (raw) {
        "STOP" -> FinishReason.Stop
        "MAX_TOKENS" -> FinishReason.Length
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" -> FinishReason.ContentFilter
        "FINISH_REASON_UNSPECIFIED" -> FinishReason.Unknown
        else -> FinishReason.Other(raw)
    }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}

/**
 * Maps `streamGenerateContent` SSE payloads. Each payload is a whole `GenerateContentResponse`
 * with new parts only; function calls arrive complete. The stream has no end marker: it is
 * complete when a candidate reported a `finishReason`.
 */
internal class GeminiStreamMapper(private val provider: String) {
    private var finish: FinishReason? = null
    private var nextCallIndex = 0
    private var sentMetadata = false

    fun map(data: String): List<ProviderEvent> = with(GeminiWire) {
        val chunk = try {
            Json.parseToJsonElement(data) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw LlmException(LlmErrorKind.MalformedResponse, "$provider sent a stream chunk that isn't a JSON object", provider = provider)

        chunk.obj("error")?.let { error ->
            throw LlmException(
                LlmErrorKind.ServerError, "$provider reported an error mid-stream", provider = provider,
                providerCode = error.str("status"), providerMessage = error.str("message")?.take(300),
            )
        }
        val out = ArrayList<ProviderEvent>(2)
        if (!sentMetadata && (chunk.str("responseId") != null || chunk.str("modelVersion") != null)) {
            sentMetadata = true
            out += ProviderEvent.Metadata(responseId = chunk.str("responseId"), model = chunk.str("modelVersion"))
        }
        chunk.obj("usageMetadata")?.let { u ->
            val candidates = u.long("candidatesTokenCount")
            val thoughts = u.long("thoughtsTokenCount")
            // Thinking tokens are billed as output, so they count as output here.
            val output = if (candidates == null && thoughts == null) null else (candidates ?: 0) + (thoughts ?: 0)
            out += ProviderEvent.UsageUpdate(Usage(u.long("promptTokenCount"), output, u.long("cachedContentTokenCount")))
        }
        val candidate = (chunk["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (candidate == null) {
            chunk.obj("promptFeedback")?.str("blockReason")?.let { reason ->
                throw LlmException(LlmErrorKind.ContentFiltered, "$provider blocked the prompt ($reason)", provider = provider, providerCode = reason)
            }
            return out
        }
        val parts = (candidate.obj("content")?.get("parts") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        for (part in parts) {
            if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) continue // private reasoning summary
            val call = part.obj("functionCall")
            val text = part.str("text")
            when {
                call != null -> out += ProviderEvent.ToolCallChunk(
                    index = nextCallIndex++,
                    id = call.str("id"),
                    name = call.str("name"),
                    argumentsDelta = (call["args"] as? JsonObject ?: JsonObject(emptyMap())).toString(),
                    providerMetadata = part.str("thoughtSignature")?.let { mapOf(GeminiWire.THOUGHT_SIGNATURE to it) } ?: emptyMap(),
                )
                !text.isNullOrEmpty() -> out += ProviderEvent.TextDelta(text)
            }
        }
        candidate.str("finishReason")?.let { finish = GeminiWire.finishReason(it) }
        out
    }

    /** The Finish event, or `null` if no finish reason arrived (the stream was cut off). */
    fun end(): ProviderEvent.Finish? = finish?.let { ProviderEvent.Finish(it) }
}
