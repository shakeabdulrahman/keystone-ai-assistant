package com.keystone.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.android.ui.chat.ChatMessageUi.Role
import com.keystone.android.ui.chat.ChatMessageUi.Status
import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.StoredMessage
import com.keystone.shared.domain.repository.ChatRepository
import com.keystone.shared.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Drives one chat conversation.
 *
 * Streaming design:
 * - The repository returns a cold Flow per message; collecting it opens the stream.
 * - Cancelling the collecting Job (Stop button, or leaving the screen) closes the HTTP
 *   connection, and the backend stops generating.
 * - Text deltas are buffered and flushed to UI state at most every [FLUSH_INTERVAL], so a
 *   fast model doesn't trigger a recomposition for every token.
 *
 * Opening a past conversation shows the cached history at once, then the server's copy.
 * After each answer the cache is refreshed in the background, so history works offline.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val conversationRepository: ConversationRepository,
    savedStateHandle: SavedStateHandle,
    private val timeSource: TimeSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Assigned by the server on the first reply; reused for follow-ups so the model has context. */
    private var conversationId: String? = savedStateHandle.get<String>(CONVERSATION_ID_ARG)
    private var replyJob: Job? = null

    /** Messages typed in this session; history never overwrites them. */
    private var sentThisSession = 0

    init {
        conversationId?.let(::loadHistory)
    }

    private fun loadHistory(id: String) {
        _uiState.update { it.copy(isLoadingHistory = true) }
        viewModelScope.launch {
            showHistory(conversationRepository.observeMessages(id).first()) // cache: instant, offline
            if (conversationRepository.refreshMessages(id) is AppResult.Success) {
                showHistory(conversationRepository.observeMessages(id).first()) // server: authoritative
            }
            _uiState.update { it.copy(isLoadingHistory = false) }
        }
    }

    private fun showHistory(stored: List<StoredMessage>) {
        if (sentThisSession > 0 || stored.isEmpty()) return
        _uiState.update { it.copy(messages = stored.map { m -> m.toUi() }) }
    }

    /** Keeps the offline copy of this conversation current. Failures don't matter here. */
    private fun syncCache() {
        val id = conversationId ?: return
        viewModelScope.launch { conversationRepository.refreshMessages(id) }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text.take(MAX_MESSAGE_CHARS)) }
    }

    fun onSend() = send(_uiState.value.input)

    fun onSuggestion(text: String) = send(text)

    /** Stop generating. The partial answer stays on screen, marked as stopped. */
    fun onStop() {
        replyJob?.cancel()
        replyJob = null
        _uiState.update { state ->
            state.copy(
                isResponding = false,
                messages = state.messages.map {
                    if (it.status == Status.Streaming) it.copy(status = Status.Stopped) else it
                },
            )
        }
    }

    /** Retry a failed answer: drop it and ask the same question again. */
    fun onRetry(failedMessageId: String) {
        val messages = _uiState.value.messages
        val index = messages.indexOfFirst { it.id == failedMessageId }
        if (index <= 0 || _uiState.value.isResponding) return
        val question = messages.subList(0, index).lastOrNull { it.role == Role.User } ?: return
        _uiState.update { it.copy(messages = it.messages.filterNot { m -> m.id == failedMessageId }) }
        streamReply(question.text)
    }

    private fun send(raw: String) {
        val content = raw.trim()
        if (content.isEmpty() || _uiState.value.isResponding) return
        sentThisSession++
        _uiState.update {
            it.copy(
                input = "",
                messages = it.messages + ChatMessageUi(newId(), Role.User, content),
            )
        }
        streamReply(content)
    }

    private fun streamReply(question: String) {
        val replyId = newId()
        _uiState.update {
            it.copy(
                isResponding = true,
                messages = it.messages + ChatMessageUi(replyId, Role.Assistant, "", Status.Streaming),
            )
        }

        replyJob = viewModelScope.launch {
            val pending = StringBuilder()
            var lastFlush = timeSource.markNow()

            fun flush(status: Status = Status.Streaming, failure: ChatEvent.Failed? = null) {
                val chunk = pending.toString()
                pending.clear()
                lastFlush = timeSource.markNow()
                updateMessage(replyId) {
                    it.copy(text = it.text + chunk, status = status, error = failure?.error)
                }
            }

            try {
                // A new client message ID per attempt: retries are new messages to the server.
                chatRepository.sendMessage(conversationId, newId(), question).collect { event ->
                    when (event) {
                        is ChatEvent.Started -> conversationId = event.conversationId
                        is ChatEvent.Delta -> {
                            pending.append(event.text)
                            if (lastFlush.elapsedNow() >= FLUSH_INTERVAL) flush()
                        }
                        is ChatEvent.Completed -> flush(Status.Complete)
                        is ChatEvent.Failed -> flush(Status.Failed, event)
                    }
                }
                _uiState.update { it.copy(isResponding = false) }
                syncCache()
            } finally {
                // Stopped mid-stream: keep text that arrived but wasn't flushed yet.
                if (pending.isNotEmpty()) {
                    val chunk = pending.toString()
                    pending.clear()
                    updateMessage(replyId) { it.copy(text = it.text + chunk) }
                }
            }
        }
    }

    private fun updateMessage(id: String, transform: (ChatMessageUi) -> ChatMessageUi) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        val FLUSH_INTERVAL = 40.milliseconds

        /** Matches the property name in ChatDestination (type-safe navigation argument). */
        const val CONVERSATION_ID_ARG = "conversationId"
    }
}

private fun StoredMessage.toUi() = ChatMessageUi(
    id = id,
    role = if (role == StoredMessage.Role.User) Role.User else Role.Assistant,
    text = content,
    status = when (status) {
        StoredMessage.Status.Complete -> Status.Complete
        StoredMessage.Status.Interrupted -> Status.Stopped
        StoredMessage.Status.Failed -> Status.Failed
    },
    // A failed answer from an earlier session can still be retried.
    error = if (status == StoredMessage.Status.Failed) AppError.Assistant("ai_unavailable", retryable = true) else null,
)
