package io.github.shakeabdulrahman.keystone.llm.gemini

import io.github.shakeabdulrahman.keystone.llm.Auth
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.LlmProvider
import io.github.shakeabdulrahman.keystone.llm.ProviderCapabilities
import io.github.shakeabdulrahman.keystone.llm.ProviderEvent
import io.github.shakeabdulrahman.keystone.llm.ProviderRequest
import io.github.shakeabdulrahman.keystone.llm.StructuredOutputSupport
import io.github.shakeabdulrahman.keystone.llm.Support
import io.github.shakeabdulrahman.keystone.llm.http.HttpRequest
import io.github.shakeabdulrahman.keystone.llm.http.HttpTransport
import io.github.shakeabdulrahman.keystone.llm.http.forEachSseEvent
import io.github.shakeabdulrahman.keystone.llm.http.toHeaders
import io.github.shakeabdulrahman.keystone.llm.http.toLlmException
import io.github.shakeabdulrahman.keystone.llm.ktor.KtorHttpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Google's Gemini API (`models/{model}:streamGenerateContent?alt=sse`).
 *
 * - [Auth.ApiKey] goes in the `x-goog-api-key` header, never in the URL, so it can't leak
 *   into logs of URLs.
 * - Tool and response schemas are converted to the OpenAPI subset Gemini accepts (see
 *   [GeminiWire.geminiSchema]): unsupported keywords are dropped, so Gemini enforces less
 *   than the full schema. The SDK still validates the result client-side.
 * - Gemini's `thoughtSignature` on function calls is kept in
 *   [io.github.shakeabdulrahman.keystone.llm.ToolCall.providerMetadata] and sent back with
 *   the call, which Gemini requires for multi-turn tool use with thinking models.
 */
public class GeminiProvider(
    private val auth: Auth,
    override val id: String = "gemini",
    baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val capabilities: (model: String) -> ProviderCapabilities = { DefaultCapabilities },
    transport: HttpTransport? = null,
) : LlmProvider {
    private val baseUrl = baseUrl.trimEnd('/')
    private val transport: HttpTransport = transport ?: KtorHttpTransport()
    private val ownsTransport = transport == null

    override fun capabilities(model: String): ProviderCapabilities = capabilities.invoke(model)

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
        if (!MODEL.matches(request.model)) {
            throw LlmException(LlmErrorKind.InvalidRequest, "Invalid Gemini model name", provider = id)
        }
        val body = GeminiWire.requestBody(request)
        val headers = auth.toHeaders { key -> "x-goog-api-key" to key } + ("Accept" to "text/event-stream")
        val url = "$baseUrl/models/${request.model}:streamGenerateContent?alt=sse"
        val http = HttpRequest("POST", url, headers, body.toString().encodeToByteArray(), "application/json")
        transport.execute(http) { response ->
            if (response.status !in 200..299) throw response.toLlmException(id, requestIdHeader = null, GeminiWire::parseError)
            val mapper = GeminiStreamMapper(id)
            response.forEachSseEvent { event ->
                for (e in mapper.map(event.data)) emit(e)
                true
            }
            mapper.end()?.let { emit(it) }
        }
    }

    override fun close() {
        if (ownsTransport) transport.close()
    }

    public companion object {
        public val DefaultCapabilities: ProviderCapabilities = ProviderCapabilities(
            tools = Support.Native,
            toolChoiceRequired = Support.Native,
            structuredOutput = StructuredOutputSupport.JsonSchema,
        )

        // Model names go into the URL path: allow only what model ids contain.
        private val MODEL = Regex("^[A-Za-z0-9._-]+$")
    }
}
