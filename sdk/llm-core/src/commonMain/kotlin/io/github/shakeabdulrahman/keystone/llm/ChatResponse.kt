package io.github.shakeabdulrahman.keystone.llm

import kotlin.time.Duration

/** Why the model stopped. Unknown provider values are kept in [Other]. */
public sealed interface FinishReason {
    /** Natural end of the answer, or a stop sequence. */
    public data object Stop : FinishReason

    /** Hit the output-token limit: text may be cut off and JSON incomplete. */
    public data object Length : FinishReason

    /** The model asked for tool calls. */
    public data object ToolCalls : FinishReason

    /** The provider's safety system stopped or blocked the answer. */
    public data object ContentFilter : FinishReason

    /** The provider reported no reason. */
    public data object Unknown : FinishReason

    public data class Other(val raw: String) : FinishReason
}

/** Where a token count came from. */
public enum class UsageSource {
    /** Counted by the provider. */
    Reported,

    /** Computed locally by a [TokenEstimator]: approximate. */
    Estimated,

    /** Not available. */
    Unknown,
}

/**
 * Token usage of one response. A `null` count means unknown: the SDK never invents one.
 *
 * @property cachedInputTokens the part of [inputTokens] served from the provider's prompt cache, if reported.
 */
public data class Usage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val source: UsageSource = if (inputTokens == null && outputTokens == null) UsageSource.Unknown else UsageSource.Reported,
) {
    public val totalTokens: Long?
        get() = if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null

    public companion object {
        public val Unknown: Usage = Usage()
    }
}

/** How a structured-output request was enforced for the target that answered. */
public enum class StructuredEnforcement {
    /** The provider constrained output to the schema. Also validated client-side. */
    ProviderSchema,

    /** The provider guaranteed JSON, not the schema. Schema validated client-side only. */
    ProviderJsonMode,

    /** Only instructions in the prompt. Validated client-side only. */
    PromptOnly,
}

/** The outcome of one try against one model. Contains no prompt text or keys. */
public data class Attempt(
    val target: ModelRef,
    /** 1-based attempt number against this target. */
    val number: Int,
    val duration: Duration,
    /** `null` for the attempt that succeeded. */
    val error: LlmErrorKind?,
    val httpStatus: Int? = null,
    val requestId: String? = null,
)

/**
 * @property requestId the provider's request id (from response headers), for support tickets.
 * @property responseId the provider's id of the generated response, if any.
 * @property attempts every attempt made, in order; the last one is the one that answered.
 */
public data class ResponseMetadata(
    val target: ModelRef,
    val model: String?,
    val requestId: String?,
    val responseId: String?,
    val attempts: List<Attempt>,
    val structuredEnforcement: StructuredEnforcement? = null,
)

/** A complete model answer. */
public data class ChatResponse(
    val message: Message.Assistant,
    val finishReason: FinishReason,
    val usage: Usage,
    /** `null` unless the client was configured with a [PriceTable]. */
    val cost: CostReport?,
    val metadata: ResponseMetadata,
) {
    public val text: String get() = message.text
    public val toolCalls: List<ToolCall> get() = message.toolCalls
}
