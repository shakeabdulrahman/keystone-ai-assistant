package com.keystone.shared.domain.repository

import com.keystone.shared.domain.model.ChatEvent
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    /**
     * Sends a message and streams the reply. Cold: nothing happens until collected.
     * Cancelling the collector closes the connection, which also stops generation on the server.
     *
     * @param conversationId null starts a new conversation (its ID arrives in [ChatEvent.Started]).
     * @param clientMessageId unique per message; resending the same ID is rejected (idempotency).
     */
    fun sendMessage(conversationId: String?, clientMessageId: String, content: String): Flow<ChatEvent>
}
