package com.keystone.shared.data.remote

import com.keystone.shared.core.AppError
import com.keystone.shared.data.remote.dto.MessageCompletedDto
import com.keystone.shared.data.remote.dto.MessageStartedDto
import com.keystone.shared.data.remote.dto.StreamErrorDto
import com.keystone.shared.data.remote.dto.TextDeltaDto
import com.keystone.shared.data.remote.sse.SseEvent
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.TokenUsage

/**
 * Turns wire events into domain events. Unknown event types return null and are skipped:
 * that is what lets the server add new events (tool calls, citations) without breaking
 * app versions already installed on users' phones.
 */
internal object ChatStreamMapper {
    fun map(event: SseEvent): ChatEvent? = when (event.event) {
        "message.started" -> decode<MessageStartedDto>(event).let {
            ChatEvent.Started(it.conversationId, it.messageId, it.userMessageId)
        }
        "text.delta" -> ChatEvent.Delta(decode<TextDeltaDto>(event).text)
        "message.completed" -> decode<MessageCompletedDto>(event).let {
            ChatEvent.Completed(
                messageId = it.messageId,
                truncated = it.finishReason == "length",
                usage = TokenUsage(it.usage.inputTokens, it.usage.outputTokens),
            )
        }
        "error" -> decode<StreamErrorDto>(event).let {
            ChatEvent.Failed(AppError.Assistant(it.code, it.retryable))
        }
        else -> null
    }

    private inline fun <reified T> decode(event: SseEvent): T =
        KeystoneJson.decodeFromString<T>(event.data)
}
