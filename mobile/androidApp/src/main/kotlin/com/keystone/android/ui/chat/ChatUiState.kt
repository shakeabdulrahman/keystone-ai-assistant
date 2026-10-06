package com.keystone.android.ui.chat

import com.keystone.shared.core.AppError

data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val input: String = "",
    val isResponding: Boolean = false,
) {
    val canSend: Boolean get() = input.isNotBlank() && !isResponding
}

data class ChatMessageUi(
    val id: String,
    val role: Role,
    val text: String,
    val status: Status = Status.Complete,
    val error: AppError? = null,
) {
    enum class Role { User, Assistant }

    enum class Status {
        /** Text is still arriving. */
        Streaming,
        Complete,

        /** The user pressed Stop; the partial text is kept. */
        Stopped,
        Failed,
    }
}

const val MAX_MESSAGE_CHARS = 4000 // matches the backend limit
