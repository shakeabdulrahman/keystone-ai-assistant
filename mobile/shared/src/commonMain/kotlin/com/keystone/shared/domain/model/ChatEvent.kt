package com.keystone.shared.domain.model

import com.keystone.shared.core.AppError

/**
 * What happens while the assistant answers one message, in order:
 * [Started] → ([Delta] | [ToolStarted] / [ToolResult] | [ConfirmationRequired])* → [Citations]?
 * → ([Completed] | [Failed]).
 * Every stream ends with exactly one terminal event, even when the network fails.
 */
sealed interface ChatEvent {
    data class Started(
        val conversationId: String,
        val messageId: String,
        val userMessageId: String,
    ) : ChatEvent

    data class Delta(val text: String) : ChatEvent

    /** The assistant started looking something up; [label] is user-facing ("Checking your leave balance"). */
    data class ToolStarted(val callId: String, val tool: String, val label: String) : ChatEvent

    /** A lookup finished. [card] is null when it failed or the card type is unknown to this app version. */
    data class ToolResult(val callId: String, val tool: String, val ok: Boolean, val card: ToolCard?) : ChatEvent

    /** The assistant prepared a change; nothing happens until the employee confirms it. */
    data class ConfirmationRequired(val action: PendingAction) : ChatEvent

    /** The policy passages the answer cited, in the order the text first mentions them. */
    data class Citations(val items: List<Citation>) : ChatEvent

    data class Completed(
        val messageId: String,
        val truncated: Boolean,
        val usage: TokenUsage,
    ) : ChatEvent

    data class Failed(val error: AppError) : ChatEvent
}

data class TokenUsage(val inputTokens: Int, val outputTokens: Int)
