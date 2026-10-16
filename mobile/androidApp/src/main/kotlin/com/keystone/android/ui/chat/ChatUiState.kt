package com.keystone.android.ui.chat

import com.keystone.android.voice.SpeechError
import com.keystone.shared.core.AppError
import com.keystone.shared.domain.model.Citation
import com.keystone.shared.domain.model.PendingAction
import com.keystone.shared.domain.model.ToolCard

data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val input: String = "",
    val isResponding: Boolean = false,
    /** Opening a past conversation: true until its history has been shown. */
    val isLoadingHistory: Boolean = false,
    val voice: VoiceUi = VoiceUi(),
) {
    val canSend: Boolean get() = input.isNotBlank() && !isResponding
}

data class ChatMessageUi(
    val id: String,
    val role: Role,
    val text: String,
    val status: Status = Status.Complete,
    val error: AppError? = null,
    /** HR lookups the assistant made for this answer, shown above its text in call order. */
    val tools: List<ToolActivityUi> = emptyList(),
    /** Policy sources the answer cited, shown as chips under it. */
    val citations: List<Citation> = emptyList(),
    /** Changes the answer prepared, shown as confirmation cards. */
    val actions: List<ActionUi> = emptyList(),
    /** A question asked by voice (its retry is spoken again too). */
    val spoken: Boolean = false,
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

/** One tool call: a progress row while [state] is Running, then a card (or a short note). */
data class ToolActivityUi(
    val callId: String,
    val label: String,
    val state: State,
    val card: ToolCard? = null,
) {
    enum class State { Running, Done, Failed }
}

/** Microphone and read-aloud state for the composer and the top bar. */
data class VoiceUi(
    /** Speech recognition exists on this device; otherwise the mic button is hidden. */
    val micAvailable: Boolean = false,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    /** Read every answer aloud, also typed ones (the speaker button). */
    val readAloud: Boolean = false,
    val error: SpeechError? = null,
)

/** A confirmation card. The server's [action] status decides which buttons are shown. */
data class ActionUi(
    val action: PendingAction,
    /** A confirm/cancel request is in flight: buttons disabled, spinner shown. */
    val busy: Boolean = false,
    /** The last decision didn't reach the server; it's safe to try again. */
    val error: AppError? = null,
)

const val MAX_MESSAGE_CHARS = 4000 // matches the backend limit
