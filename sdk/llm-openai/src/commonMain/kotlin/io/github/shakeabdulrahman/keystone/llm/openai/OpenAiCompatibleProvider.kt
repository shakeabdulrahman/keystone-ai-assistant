package io.github.shakeabdulrahman.keystone.llm.openai

import io.github.shakeabdulrahman.keystone.llm.Auth
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
 * Any API that implements OpenAI's Chat Completions (`POST {baseUrl}/chat/completions` with
 * SSE streaming): OpenAI, Groq, OpenRouter, Mistral, Together, a local Ollama or vLLM, or
 * your own proxy. A new vendor is configuration, not code.
 *
 * Capabilities differ between these servers, so they're declared, not assumed: the default
 * [GenericCapabilities] claims tools and JSON mode but not JSON-schema output. Pass
 * [OpenAiCapabilities] (or your own) when the server supports more.
 *
 * Key handling: [Auth.ApiKey] is sent as `Authorization: Bearer`. Shipping a vendor key inside
 * a mobile app exposes it to anyone who unpacks the app; prefer [baseUrl] pointing at your own
 * backend with [Auth.Headers] carrying a short-lived user token.
 *
 * @param id the provider id used in [io.github.shakeabdulrahman.keystone.llm.ModelRef].
 * @param capabilities per model, e.g. `{ model -> if (model.startsWith("gpt-4o")) OpenAiCapabilities else GenericCapabilities }`.
 * @param transport HTTP stack. Defaults to a [KtorHttpTransport] owned (and closed) by this provider.
 */
public class OpenAiCompatibleProvider(
    override val id: String,
    baseUrl: String,
    private val auth: Auth,
    private val capabilities: (model: String) -> ProviderCapabilities = { GenericCapabilities },
    private val options: OpenAiCompatibleOptions = OpenAiCompatibleOptions(),
    transport: HttpTransport? = null,
) : LlmProvider {
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
    private val transport: HttpTransport = transport ?: KtorHttpTransport()
    private val ownsTransport = transport == null

    init {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "baseUrl must be an http(s) URL" }
    }

    override fun capabilities(model: String): ProviderCapabilities = capabilities.invoke(model)

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
        val body = OpenAiWire.requestBody(request, options)
        val headers = buildMap {
            putAll(options.extraHeaders)
            putAll(auth.toHeaders { key -> "Authorization" to "Bearer $key" })
            put("Accept", "text/event-stream")
        }
        val http = HttpRequest("POST", endpoint, headers, body.toString().encodeToByteArray(), "application/json")
        transport.execute(http) { response ->
            if (response.status !in 200..299) {
                throw response.toLlmException(id, options.requestIdHeader, OpenAiWire::parseError)
            }
            val mapper = OpenAiStreamMapper(id)
            emit(ProviderEvent.Metadata(requestId = options.requestIdHeader?.let { response.header(it) }))
            response.forEachSseEvent { event ->
                for (e in mapper.map(event.data)) emit(e)
                !mapper.done
            }
            mapper.end()?.let { emit(it) }
        }
    }

    override fun close() {
        if (ownsTransport) transport.close()
    }

    public companion object {
        /** Tools and JSON mode; no JSON-schema output. A safe default for unknown servers. */
        public val GenericCapabilities: ProviderCapabilities = ProviderCapabilities(
            tools = Support.Native,
            toolChoiceRequired = Support.Native,
            structuredOutput = StructuredOutputSupport.JsonMode,
        )

        /** For servers with `response_format: json_schema` (OpenAI's current chat models). */
        public val OpenAiCapabilities: ProviderCapabilities = GenericCapabilities.copy(
            structuredOutput = StructuredOutputSupport.JsonSchema,
        )

        /** A text-only model or server: no tools, no JSON mode. */
        public val TextOnlyCapabilities: ProviderCapabilities = ProviderCapabilities(
            tools = Support.Unsupported,
            toolChoiceRequired = Support.Unsupported,
            structuredOutput = StructuredOutputSupport.None,
        )
    }
}

/**
 * Wire-level switches for servers that differ from OpenAI.
 *
 * @property maxTokensField `max_tokens` (widely accepted) or `max_completion_tokens` (required
 * by some newer OpenAI models).
 * @property includeStreamUsage send `stream_options.include_usage` so the stream ends with
 * token usage. Turn off for servers that reject unknown fields; usage is then unknown.
 * @property extraHeaders sent with every request (e.g. OpenRouter's `HTTP-Referer`). Auth
 * headers take precedence.
 * @property requestIdHeader the response header with the server's request id, or `null`.
 */
public data class OpenAiCompatibleOptions(
    val maxTokensField: String = "max_tokens",
    val includeStreamUsage: Boolean = true,
    val extraHeaders: Map<String, String> = emptyMap(),
    val requestIdHeader: String? = "x-request-id",
)
