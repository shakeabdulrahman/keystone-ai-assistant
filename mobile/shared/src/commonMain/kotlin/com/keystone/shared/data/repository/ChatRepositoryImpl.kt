package com.keystone.shared.data.repository

import com.keystone.shared.core.AppError
import com.keystone.shared.data.remote.ChatStreamMapper
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.dto.ChatRequestDto
import com.keystone.shared.data.remote.toAppError
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach

class ChatRepositoryImpl(private val api: KeystoneApi) : ChatRepository {

    override fun sendMessage(
        conversationId: String?,
        clientMessageId: String,
        content: String,
    ): Flow<ChatEvent> = flow {
        var finished = false
        val events = api.streamChat(ChatRequestDto(conversationId, clientMessageId, content))
            .mapNotNull(ChatStreamMapper::map) // unknown event types are skipped
            .onEach { if (it is ChatEvent.Completed || it is ChatEvent.Failed) finished = true }
            // `catch` only sees upstream failures (network, HTTP status, bad JSON) and lets
            // cancellation through, so pressing Stop is never reported as an error.
            .catch { e ->
                finished = true
                emit(ChatEvent.Failed(e.toAppError()))
            }
        emitAll(events)
        // The connection closed without a final event (e.g. the network or a proxy cut it).
        if (!finished) emit(ChatEvent.Failed(AppError.Network))
    }
}
