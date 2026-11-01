package io.github.shakeabdulrahman.keystone.llm

import kotlin.time.Duration

/** What kind of failure happened. Drives the default retry and failover decisions. */
public enum class LlmErrorKind(
    /** Worth retrying the same target (after a delay). */
    public val isTransient: Boolean,
) {
    /** Couldn't connect, or the connection dropped (including a stream cut off mid-way). */
    Network(true),

    /** No response, or no stream event, within the configured time. */
    Timeout(true),

    /** HTTP 429 or the provider's equivalent. See [LlmException.retryAfter]. */
    RateLimited(true),

    /** HTTP 5xx / overloaded. */
    ServerError(true),

    /** Out of credit or quota: waiting won't help (e.g. OpenAI `insufficient_quota`). */
    QuotaExceeded(false),

    /** HTTP 401: missing or wrong key. */
    Authentication(false),

    /** HTTP 403. */
    PermissionDenied(false),

    /** HTTP 404, often a retired or misspelled model. */
    NotFound(false),

    /** HTTP 400/413/422: the request itself is wrong for this provider. */
    InvalidRequest(false),

    /** The target lacks a capability the request needs (tools, JSON schema, ...). */
    Unsupported(false),

    /** The provider refused for safety reasons. */
    ContentFiltered(false),

    /** The provider sent something the SDK couldn't parse. */
    MalformedResponse(false),

    /** Structured output was malformed, cut off, or didn't match the schema. */
    StructuredOutput(false),

    /** Anything else, e.g. a bug in a custom provider. */
    Unknown(false),
}

/**
 * The one exception type the SDK throws for model failures. Coroutine cancellation is never
 * wrapped: it propagates as `CancellationException`.
 *
 * The [message] never contains prompts, model output or keys. [providerMessage] is the
 * provider's own error text (truncated); it is kept out of [message] because a provider
 * might echo request content in it, so think before logging it.
 */
public open class LlmException(
    public val kind: LlmErrorKind,
    message: String,
    public val provider: String? = null,
    public val httpStatus: Int? = null,
    public val providerCode: String? = null,
    public val providerMessage: String? = null,
    public val requestId: String? = null,
    /** How long the provider asked us to wait, if it said. */
    public val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public val isTransient: Boolean get() = kind.isTransient

    /** Every attempt made before giving up, in order. Filled in by [LlmClient]. */
    public var attempts: List<Attempt> = emptyList()
        internal set
}

/** Structured output couldn't be turned into the requested type. */
public class StructuredOutputException(
    public val reason: Reason,
    message: String,
    /** The model's raw answer. Model output: don't log it carelessly. */
    public val rawText: String,
    public val response: ChatResponse,
    cause: Throwable? = null,
) : LlmException(LlmErrorKind.StructuredOutput, message, provider = response.metadata.target.provider, cause = cause) {
    public sealed interface Reason {
        /** The answer stopped at the token limit, so the JSON is incomplete. */
        public data object Truncated : Reason

        /** The model answered with tool calls or nothing, not JSON. */
        public data object NoContent : Reason
        public data class MalformedJson(val detail: String) : Reason
        public data class SchemaMismatch(val violations: List<SchemaViolation>) : Reason

        /** Matched the schema, but the deserializer rejected it. */
        public data class DecodingFailed(val detail: String) : Reason
    }
}
