package io.github.shakeabdulrahman.keystone.llm

/**
 * One turn of a conversation. Text only in 0.1: images and audio are not supported yet.
 */
public sealed interface Message {
    /** Instructions for the model. Providers without a system role get it in their own way. */
    public data class System(val text: String) : Message

    public data class User(val text: String) : Message

    /**
     * A model turn. To continue after tool calls, send back the [ToolCall]s exactly as you
     * received them (including [ToolCall.providerMetadata]), followed by one [ToolResult] each.
     */
    public data class Assistant(
        val text: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
    ) : Message

    /**
     * The result of running a tool your app executed. [content] is usually JSON text.
     * Set [isError] when the tool failed, so the model can recover.
     */
    public data class ToolResult(
        val callId: String,
        val toolName: String,
        val content: String,
        val isError: Boolean = false,
    ) : Message

    public companion object {
        public fun system(text: String): Message = System(text)
        public fun user(text: String): Message = User(text)
        public fun assistant(text: String): Message = Assistant(text)
    }
}
