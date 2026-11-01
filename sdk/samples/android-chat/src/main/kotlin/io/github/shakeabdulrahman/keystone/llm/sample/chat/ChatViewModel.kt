package io.github.shakeabdulrahman.keystone.llm.sample.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shakeabdulrahman.keystone.llm.Attempt
import io.github.shakeabdulrahman.keystone.llm.Auth
import io.github.shakeabdulrahman.keystone.llm.ChatRequest
import io.github.shakeabdulrahman.keystone.llm.LlmClient
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.LlmListener
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ModelRef
import io.github.shakeabdulrahman.keystone.llm.Route
import io.github.shakeabdulrahman.keystone.llm.StreamEvent
import io.github.shakeabdulrahman.keystone.llm.gemini.GeminiProvider
import io.github.shakeabdulrahman.keystone.llm.openai.OpenAiCompatibleProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    /** Which provider/model answered, e.g. "groq/openai/gpt-oss-120b". */
    val answeredBy: String? = null,
    /** Retries and failover that happened before the answer, one line each. */
    val attemptLog: List<String> = emptyList(),
    /** Finish reason and token usage, once complete. */
    val footer: String? = null,
    val streaming: Boolean = false,
    val failed: Boolean = false,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    /** Providers that have a key, in failover order. */
    val providers: List<String> = emptyList(),
    /** Demo switch: send the primary a model that doesn't exist, so failover is visible. */
    val breakPrimary: Boolean = false,
)

/**
 * Chat on the SDK: Groq first, Gemini as fallback (the Keystone setup).
 * Keys come from BuildConfig, filled from sdk/local.properties for debug builds only.
 */
class ChatViewModel : ViewModel() {
    private val groq = BuildConfig.GROQ_API_KEY.takeIf { it.isNotBlank() && BuildConfig.GROQ_MODEL.isNotBlank() }
    private val gemini = BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() && BuildConfig.GEMINI_MODEL.isNotBlank() }

    private val _state = MutableStateFlow(ChatUiState(providers = listOfNotNull(groq?.let { "groq" }, gemini?.let { "gemini" })))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var nextId = 0L
    private var job: Job? = null

    /** Id of the assistant message currently being written, for the listener below. */
    @Volatile private var currentAnswerId: Long? = null

    private val client: LlmClient? = if (groq == null && gemini == null) {
        null
    } else {
        LlmClient {
            groq?.let { provider(OpenAiCompatibleProvider("groq", "https://api.groq.com/openai/v1", Auth.ApiKey(it))) }
            gemini?.let { provider(GeminiProvider(Auth.ApiKey(it))) }
            listener = object : LlmListener {
                override fun onAttemptFailed(attempt: Attempt, error: LlmException, next: LlmListener.NextStep) {
                    val step = when (next) {
                        is LlmListener.NextStep.Retry -> "retrying in ${next.delay}"
                        is LlmListener.NextStep.Failover -> "failing over to ${next.to.provider}"
                        LlmListener.NextStep.GiveUp -> "giving up"
                    }
                    val line = "${attempt.target.provider} #${attempt.number}: ${error.kind}, $step"
                    currentAnswerId?.let { id -> updateMessage(id) { it.copy(attemptLog = it.attemptLog + line) } }
                }
            }
        }
    }

    fun onInputChange(text: String) = _state.update { it.copy(input = text) }

    fun onBreakPrimaryChange(enabled: Boolean) = _state.update { it.copy(breakPrimary = enabled) }

    fun send() {
        val llm = client ?: return
        val question = _state.value.input.trim()
        if (question.isEmpty() || _state.value.isStreaming) return

        val history = _state.value.messages
            .filter { !it.failed && it.text.isNotBlank() }
            .map { if (it.fromUser) Message.user(it.text) else Message.assistant(it.text) }
        val userMessage = ChatMessage(nextId++, fromUser = true, text = question)
        val answerId = nextId++
        currentAnswerId = answerId
        _state.update {
            it.copy(
                input = "",
                isStreaming = true,
                messages = it.messages + userMessage + ChatMessage(answerId, fromUser = false, text = "", streaming = true),
            )
        }

        val request = ChatRequest(
            messages = listOf(Message.system("You are a concise, friendly assistant.")) + history + Message.user(question),
            route = route(),
            maxOutputTokens = 800,
        )
        job = viewModelScope.launch {
            try {
                llm.stream(request).collect { event ->
                    when (event) {
                        is StreamEvent.Started -> updateMessage(answerId) {
                            it.copy(answeredBy = event.target.toString(), text = "")
                        }
                        is StreamEvent.TextDelta -> updateMessage(answerId) { it.copy(text = it.text + event.text) }
                        is StreamEvent.Restarted -> updateMessage(answerId) { it.copy(text = "") }
                        is StreamEvent.Completed -> {
                            val r = event.response
                            val usage = r.usage.let { u ->
                                if (u.inputTokens == null && u.outputTokens == null) "usage unknown"
                                else "in ${u.inputTokens ?: "?"} · out ${u.outputTokens ?: "?"} tokens"
                            }
                            updateMessage(answerId) { it.copy(footer = "${r.finishReason::class.simpleName} · $usage", streaming = false) }
                        }
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                updateMessage(answerId) { it.copy(streaming = false, footer = "Stopped") }
                throw e
            } catch (e: LlmException) {
                updateMessage(answerId) {
                    it.copy(streaming = false, failed = true, text = it.text.ifEmpty { "Couldn't get an answer: ${e.kind}" })
                }
            } finally {
                currentAnswerId = null
                _state.update { it.copy(isStreaming = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun route(): Route {
        val targets = buildList {
            if (groq != null) {
                val model = if (_state.value.breakPrimary) "model-that-does-not-exist" else BuildConfig.GROQ_MODEL
                add(ModelRef("groq", model))
            }
            if (gemini != null) add(ModelRef("gemini", BuildConfig.GEMINI_MODEL))
        }
        return Route(targets)
    }

    private fun updateMessage(id: Long, change: (ChatMessage) -> ChatMessage) =
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) change(it) else it }) }

    override fun onCleared() {
        client?.close()
    }
}
