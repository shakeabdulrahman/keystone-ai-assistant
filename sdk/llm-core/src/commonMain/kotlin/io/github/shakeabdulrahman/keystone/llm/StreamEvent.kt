package io.github.shakeabdulrahman.keystone.llm

/**
 * An event of [LlmClient.stream]. A successful stream is:
 *
 * ```
 * Started, (TextDelta | ToolCallStarted | ToolCallDelta)*, ToolCallCompleted*, Completed
 * ```
 *
 * Failures end the flow with an [LlmException]. With [StreamRestartPolicy.EmitRestarted],
 * a [Restarted] event may appear after output; then a new `Started` follows.
 */
public sealed interface StreamEvent {
    /**
     * The first event of an answer: which model is answering. Emitted just before the
     * first output, so a retry or failover that happens before any output stays invisible.
     */
    public data class Started(val target: ModelRef, val requestId: String?) : StreamEvent

    public data class TextDelta(val text: String) : StreamEvent

    /** A tool call began. [index] identifies it in later deltas. */
    public data class ToolCallStarted(val index: Int, val id: String, val name: String) : StreamEvent

    /** A fragment of a tool call's JSON arguments. Not valid JSON on its own. */
    public data class ToolCallDelta(val index: Int, val argumentsDelta: String) : StreamEvent

    /** A tool call with its complete, validated arguments. Emitted when the answer ends. */
    public data class ToolCallCompleted(val index: Int, val call: ToolCall) : StreamEvent

    /**
     * Everything emitted since the last [Started] is void: the attempt failed and another
     * starts now. Only with [StreamRestartPolicy.EmitRestarted].
     */
    public data class Restarted(val failed: Attempt, val next: ModelRef) : StreamEvent

    /** The last event: the whole answer, as [LlmClient.chat] would return it. */
    public data class Completed(val response: ChatResponse) : StreamEvent
}
