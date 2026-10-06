package com.keystone.shared.domain.model

import com.keystone.shared.core.AppError

/**
 * What happens while the assistant answers one message, in order:
 * [Started] → [Delta]* → ([Completed] | [Failed]).
 * Every stream ends with exactly one terminal event, even when the network fails.
 */
sealed interface ChatEvent {
    data class Started(
        val conversationId: String,
        val messageId: String,
        val userMessageId: String,
    ) : ChatEvent

    data class Delta(val text: String) : ChatEvent

    data class Completed(
        val messageId: String,
        val truncated: Boolean,
        val usage: TokenUsage,
    ) : ChatEvent

    data class Failed(val error: AppError) : ChatEvent
}

data class TokenUsage(val inputTokens: Int, val outputTokens: Int)
