package com.keystone.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.android.ui.chat.ChatMessageUi.Role
import com.keystone.android.ui.chat.ChatMessageUi.Status
import com.keystone.android.voice.SpeechError
import com.keystone.android.voice.SpeechEvent
import com.keystone.android.voice.SpeechInput
import com.keystone.android.voice.SpeechOutput
import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.InputMode
import com.keystone.shared.domain.model.PendingAction
import com.keystone.shared.domain.model.StoredMessage
import com.keystone.shared.domain.repository.ActionRepository
import com.keystone.shared.domain.repository.ChatRepository
import com.keystone.shared.domain.repository.ConversationRepository
import com.keystone.shared.voice.SentenceChunker
import com.keystone.shared.voice.speakable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Drives one chat conversation.
 *
 * Streaming design:
 * - The repository returns a cold Flow per message; collecting it opens the stream.
 * - Cancelling the collecting Job (Stop button, or leaving the screen) closes the HTTP
 *   connection, and the backend stops generating.
 * - Tool events add a progress row ("Checking your leave balance") that becomes a native
 *   card when the result arrives. Rows still running when the answer ends are marked failed.
 * - Text deltas are buffered and flushed to UI state at most every [FLUSH_INTERVAL], so a
 *   fast model doesn't trigger a recomposition for every token.
 *
 * Voice: the mic streams a live transcript into the input field and sends it when the
 * person stops talking, marked as spoken. Answers to spoken questions (or all answers,
 * with read-aloud on) are spoken sentence by sentence while they stream in. Tapping the
 * mic while it speaks interrupts it ("barge-in"). Confirmation cards still need a tap.
 *
 * Opening a past conversation shows the cached history at once, then the server's copy.
 * After each answer the cache is refreshed in the background, so history works offline.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val conversationRepository: ConversationRepository,
    private val actionRepository: ActionRepository,
    private val speechInput: SpeechInput,
    private val speechOutput: SpeechOutput,
    savedStateHandle: SavedStateHandle,
    private val timeSource: TimeSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(voice = VoiceUi(micAvailable = speechInput.isAvailable)))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Assigned by the server on the first reply; reused for follow-ups so the model has context. */
    private var conversationId: String? = savedStateHandle.get<String>(CONVERSATION_ID_ARG)
    private var replyJob: Job? = null
    private var listenJob: Job? = null

    /** Messages typed in this session; history never overwrites them. */
    private var sentThisSession = 0

    init {
        conversationId?.let(::loadHistory)
        // A question handed over from the home screen is asked once (not again on rotation).
        val prompt = savedStateHandle.get<String>(PROMPT_ARG)
        if (!prompt.isNullOrBlank() && savedStateHandle.get<Boolean>(PROMPT_SENT_KEY) != true) {
            savedStateHandle[PROMPT_SENT_KEY] = true
            send(prompt, InputMode.Text)
        }
        viewModelScope.launch {
            speechOutput.speaking.collect { speaking -> updateVoice { it.copy(speaking = speaking) } }
        }
    }

    // --- Voice -----------------------------------------------------------------------

    /**
     * The mic button. Idle: start listening (interrupting speech or an answer in progress).
     * Listening: "I'm done", so finish and send what was heard.
     */
    fun onMicPressed() {
        if (_uiState.value.voice.listening) {
            speechInput.stop()
            return
        }
        speechOutput.stop() // barge-in: stop talking as soon as the person wants to speak
        if (_uiState.value.isResponding) onStop()
        updateVoice { it.copy(listening = true, error = null) }
        // Subscribe now, not inside the coroutine: nothing the microphone hears in between
        // can be missed (the flow is cold, so the mic still opens only when collected).
        val heard = speechInput.listen()
        listenJob = viewModelScope.launch {
            heard.collect { event ->
                when (event) {
                    SpeechEvent.Ready -> Unit
                    is SpeechEvent.Partial -> _uiState.update { it.copy(input = event.text.take(MAX_MESSAGE_CHARS)) }
                    is SpeechEvent.Final -> {
                        updateVoice { it.copy(listening = false) }
                        send(event.text, InputMode.Voice)
                    }
                    is SpeechEvent.Error -> updateVoice { it.copy(listening = false, error = event.reason) }
                }
            }
            updateVoice { it.copy(listening = false) } // ended without a result (e.g. cancelled)
        }
    }

    /** The person refused the microphone permission. */
    fun onMicPermissionDenied() = updateVoice { it.copy(listening = false, error = SpeechError.NoPermission) }

    fun onDismissVoiceError() = updateVoice { it.copy(error = null) }

    /** The speaker button: read typed answers aloud too, or stop reading. */
    fun onToggleReadAloud() {
        val on = !_uiState.value.voice.readAloud
        if (!on) speechOutput.stop()
        updateVoice { it.copy(readAloud = on) }
    }

    private fun shouldSpeak(mode: InputMode): Boolean =
        speechOutput.autoSpeakAllowed && (mode == InputMode.Voice || _uiState.value.voice.readAloud)

    private fun updateVoice(transform: (VoiceUi) -> VoiceUi) {
        _uiState.update { it.copy(voice = transform(it.voice)) }
    }

    override fun onCleared() {
        listenJob?.cancel()
        speechOutput.stop() // leaving the chat must not leave it talking
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

    fun onSend() = send(_uiState.value.input, InputMode.Text)

    fun onSuggestion(text: String) = send(text, InputMode.Text)

    /** Stop generating. The partial answer stays on screen, marked as stopped. */
    fun onStop() {
        speechOutput.stop()
        replyJob?.cancel()
        replyJob = null
        _uiState.update { state ->
            state.copy(
                isResponding = false,
                messages = state.messages.map {
                    if (it.status == Status.Streaming) it.copy(status = Status.Stopped, tools = it.tools.settled()) else it
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
        streamReply(question.text, if (question.spoken) InputMode.Voice else InputMode.Text)
    }

    private fun send(raw: String, mode: InputMode) {
        val content = raw.trim()
        if (content.isEmpty() || _uiState.value.isResponding) return
        sentThisSession++
        val question = ChatMessageUi(newId(), Role.User, content, spoken = mode == InputMode.Voice)
        _uiState.update { it.copy(input = "", messages = it.messages + question) }
        streamReply(content, mode)
    }

    private fun streamReply(question: String, mode: InputMode) {
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
            // Speak sentence by sentence as the answer streams in, not after it ends.
            val sentences = if (shouldSpeak(mode)) SentenceChunker() else null

            fun say(text: String?) {
                val words = text?.let(::speakable).orEmpty()
                if (words.isNotEmpty()) speechOutput.speak(words)
            }

            fun flush(status: Status = Status.Streaming, failure: ChatEvent.Failed? = null) {
                val chunk = pending.toString()
                pending.clear()
                lastFlush = timeSource.markNow()
                updateMessage(replyId) {
                    val tools = if (status == Status.Streaming) it.tools else it.tools.settled()
                    it.copy(text = it.text + chunk, status = status, error = failure?.error, tools = tools)
                }
            }

            try {
                // A new client message ID per attempt: retries are new messages to the server.
                chatRepository.sendMessage(conversationId, newId(), question, mode).collect { event ->
                    when (event) {
                        is ChatEvent.Started -> conversationId = event.conversationId
                        is ChatEvent.Delta -> {
                            sentences?.add(event.text)?.forEach(::say)
                            pending.append(event.text)
                            if (lastFlush.elapsedNow() >= FLUSH_INTERVAL) flush()
                        }
                        is ChatEvent.ToolStarted -> {
                            flush()
                            val running = ToolActivityUi(event.callId, event.label, ToolActivityUi.State.Running)
                            updateMessage(replyId) { it.copy(tools = it.tools + running) }
                        }
                        is ChatEvent.ToolResult -> {
                            flush()
                            updateMessage(replyId) { message ->
                                val state = if (event.ok) ToolActivityUi.State.Done else ToolActivityUi.State.Failed
                                val known = message.tools.any { it.callId == event.callId }
                                message.copy(
                                    tools = if (known) {
                                        message.tools.map {
                                            if (it.callId == event.callId) it.copy(state = state, card = event.card) else it
                                        }
                                    } else {
                                        message.tools + ToolActivityUi(event.callId, "", state, event.card)
                                    },
                                )
                            }
                        }
                        is ChatEvent.ConfirmationRequired -> {
                            flush()
                            updateMessage(replyId) { it.copy(actions = it.actions + ActionUi(event.action)) }
                        }
                        is ChatEvent.Citations -> {
                            flush()
                            updateMessage(replyId) { it.copy(citations = event.items) }
                        }
                        is ChatEvent.Completed -> {
                            sentences?.let { say(it.finish()) }
                            flush(Status.Complete)
                        }
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

    /** The employee tapped the card's confirm button. Only this ever runs the change. */
    fun onConfirmAction(actionId: String) = decide(actionId) { actionRepository.confirm(it) }

    /** The employee tapped Cancel on the card. */
    fun onCancelAction(actionId: String) = decide(actionId) { actionRepository.cancel(it) }

    private fun decide(actionId: String, call: suspend (String) -> AppResult<PendingAction>) {
        val current = findAction(actionId) ?: return
        if (current.busy || !current.action.status.isOpen) return // ignore double taps
        updateAction(actionId) { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = call(actionId)) {
                is AppResult.Success -> {
                    updateAction(actionId) { ActionUi(result.value) }
                    if (result.value.status == PendingAction.Status.Executing) awaitOutcome(actionId)
                }
                // Not decided (e.g. offline). Confirming is idempotent, so retrying is safe.
                is AppResult.Failure -> updateAction(actionId) { it.copy(busy = false, error = result.error) }
            }
            syncCache() // history shows the new state, also offline
        }
    }

    /** Another request is running this action right now: check back until it settles. */
    private suspend fun awaitOutcome(actionId: String) {
        repeat(OUTCOME_POLLS) {
            delay(OUTCOME_POLL_INTERVAL)
            val latest = actionRepository.get(actionId)
            if (latest is AppResult.Success) {
                updateAction(actionId) { ActionUi(latest.value) }
                if (latest.value.status != PendingAction.Status.Executing) return
            }
        }
    }

    private fun findAction(actionId: String): ActionUi? =
        _uiState.value.messages.firstNotNullOfOrNull { m -> m.actions.firstOrNull { it.action.id == actionId } }

    private fun updateAction(actionId: String, transform: (ActionUi) -> ActionUi) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.actions.none { it.action.id == actionId }) {
                        message
                    } else {
                        message.copy(actions = message.actions.map { if (it.action.id == actionId) transform(it) else it })
                    }
                },
            )
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
        val OUTCOME_POLL_INTERVAL = 2.seconds
        const val OUTCOME_POLLS = 5

        /** Matches the property name in ChatDestination (type-safe navigation argument). */
        const val CONVERSATION_ID_ARG = "conversationId"
        const val PROMPT_ARG = "prompt"
        private const val PROMPT_SENT_KEY = "promptSent"
    }
}

/** No row can keep spinning once its answer has ended. */
private fun List<ToolActivityUi>.settled() = map {
    if (it.state == ToolActivityUi.State.Running) it.copy(state = ToolActivityUi.State.Failed) else it
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
    tools = cards.mapIndexed { index, card -> ToolActivityUi("$id-$index", "", ToolActivityUi.State.Done, card) },
    citations = citations,
    actions = actions.map { ActionUi(it) },
)
