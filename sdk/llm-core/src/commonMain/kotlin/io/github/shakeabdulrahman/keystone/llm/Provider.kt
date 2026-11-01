package io.github.shakeabdulrahman.keystone.llm

import kotlinx.coroutines.flow.Flow

/** Whether a provider handles a feature itself. */
public enum class Support { Native, Unsupported }

/** How far a provider can enforce structured output. */
public enum class StructuredOutputSupport { None, JsonMode, JsonSchema }

/**
 * What a provider can do for a model. Declared, not guessed: the client checks a request
 * against these before sending it and fails with [LlmErrorKind.Unsupported] (which allows
 * failover to a capable target) instead of letting the provider silently ignore a feature.
 */
public data class ProviderCapabilities(
    val tools: Support,
    val toolChoiceRequired: Support,
    val structuredOutput: StructuredOutputSupport,
    val systemMessages: Support = Support.Native,
) {
    /** What [request] needs that this provider lacks, or `null`. Downgradable formats don't count. */
    public fun missingFor(request: ChatRequest): String? {
        if (request.tools.isNotEmpty() && tools == Support.Unsupported) return "tool calling"
        val forcing = request.toolChoice is ToolChoice.Required || request.toolChoice is ToolChoice.Specific
        if (request.tools.isNotEmpty() && forcing && toolChoiceRequired == Support.Unsupported) return "forced tool choice"
        when (val format = request.responseFormat) {
            ResponseFormat.Text -> Unit
            ResponseFormat.JsonMode ->
                if (structuredOutput == StructuredOutputSupport.None) return "JSON mode"
            is ResponseFormat.JsonSchema ->
                if (structuredOutput != StructuredOutputSupport.JsonSchema && !format.allowDowngrade) return "JSON schema output"
        }
        return null
    }
}

/**
 * A normalized event from a provider. Providers translate their wire format into these;
 * the client turns them into [StreamEvent]s, assembles tool calls, and validates them.
 */
public sealed interface ProviderEvent {
    /** Response metadata. Not user-visible, so it doesn't block a retry. */
    public data class Metadata(
        val requestId: String? = null,
        val responseId: String? = null,
        val model: String? = null,
    ) : ProviderEvent

    public data class TextDelta(val text: String) : ProviderEvent

    /**
     * A piece of tool call number [index]. [id] and [name] may come only in the first piece;
     * [argumentsDelta] pieces are concatenated. Providers that send whole calls send one piece.
     */
    public data class ToolCallChunk(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String = "",
        val providerMetadata: Map<String, String> = emptyMap(),
    ) : ProviderEvent

    /** Usage so far. The last one wins. */
    public data class UsageUpdate(val usage: Usage) : ProviderEvent

    /** The model finished. Must be sent once, at the end; a stream without it is treated as cut off. */
    public data class Finish(val reason: FinishReason) : ProviderEvent
}

/** A request resolved to one model of one provider. */
public data class ProviderRequest(
    val model: String,
    val request: ChatRequest,
    /** `request.providerOptions[provider.id]`, if any. */
    val options: kotlinx.serialization.json.JsonObject?,
)

/**
 * A model backend: a cloud API, a proxy, or an on-device runtime.
 *
 * This is the extension point for new providers. Like the rest of the API it may still
 * change in 0.x minor releases; changes will be listed in the changelog.
 *
 * Contract for [stream]:
 * - cold: nothing happens until collected; cancelling the collector cancels the request
 *   and must release the connection or model session promptly;
 * - ends with exactly one [ProviderEvent.Finish];
 * - fails only with [LlmException] (or `CancellationException`), already classified;
 * - does not retry or fail over by itself: [LlmClient] does that, under its policies.
 */
public interface LlmProvider : AutoCloseable {
    /** The id used in [ModelRef.provider]. */
    public val id: String

    public fun capabilities(model: String): ProviderCapabilities

    public fun stream(request: ProviderRequest): Flow<ProviderEvent>

    override fun close() {}
}

/** Credentials for a provider. [toString] never shows a secret. */
public sealed interface Auth {
    /** A static API key. The provider decides the header (Bearer, `x-goog-api-key`, ...). */
    public class ApiKey(public val value: String) : Auth {
        init {
            require(value.isNotBlank()) { "API key is blank" }
        }

        override fun toString(): String = "ApiKey(***)"
    }

    /**
     * Headers computed per request, e.g. a short-lived token from your backend, or headers
     * for your own proxy. Called once per attempt.
     */
    public class Headers(public val provide: suspend () -> Map<String, String>) : Auth {
        override fun toString(): String = "Headers(***)"
    }

    /** No credentials, e.g. a local server or a proxy that authenticates by other means. */
    public data object None : Auth
}
