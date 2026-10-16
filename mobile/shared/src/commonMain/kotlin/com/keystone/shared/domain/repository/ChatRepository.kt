package com.keystone.shared.domain.repository

import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.InputMode
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    /**
     * Sends a message and streams the reply. Cold: nothing happens until collected.
     * Cancelling the collector closes the connection, which also stops generation on the server.
     *
     * @param conversationId null starts a new conversation (its ID arrives in [ChatEvent.Started]).
     * @param clientMessageId unique per message; resending the same ID is rejected (idempotency).
     * @param inputMode [InputMode.Voice] when the question was spoken: the answer comes back
     *   in short sentences meant to be read aloud.
     */
    fun sendMessage(
        conversationId: String?,
        clientMessageId: String,
        content: String,
        inputMode: InputMode = InputMode.Text,
    ): Flow<ChatEvent>
}
