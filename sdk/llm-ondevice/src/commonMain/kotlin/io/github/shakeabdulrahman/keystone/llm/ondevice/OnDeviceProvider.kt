package io.github.shakeabdulrahman.keystone.llm.ondevice

import io.github.shakeabdulrahman.keystone.llm.FinishReason
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.LlmProvider
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ProviderCapabilities
import io.github.shakeabdulrahman.keystone.llm.ProviderEvent
import io.github.shakeabdulrahman.keystone.llm.ProviderRequest
import io.github.shakeabdulrahman.keystone.llm.StructuredOutputSupport
import io.github.shakeabdulrahman.keystone.llm.Support
import io.github.shakeabdulrahman.keystone.llm.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The boundary for on-device models. Implement [OnDeviceEngine] over a runtime (an Android
 * system model, Apple's on-device model, llama.cpp, MediaPipe, ...) in your app or in a
 * separate adapter module; this module depends on no runtime.
 *
 * The SDK ships no engine yet: platform runtimes and their APIs change quickly, and an
 * adapter is only worth shipping once it is tested on real devices.
 */
public interface OnDeviceEngine : AutoCloseable {
    /** Whether [model] can run now. Called before every request; keep it cheap. */
    public suspend fun availability(model: String): OnDeviceAvailability

    /**
     * Generates text for [prompt]. Cold and cancellable: cancelling collection must stop
     * generation. Must end with exactly one [OnDeviceOutput.Done].
     */
    public fun generate(model: String, prompt: OnDevicePrompt): Flow<OnDeviceOutput>

    override fun close() {}
}

public sealed interface OnDeviceAvailability {
    public data object Available : OnDeviceAvailability

    /** Supported, but not ready now (e.g. still downloading). */
    public data class NotReady(val reason: String) : OnDeviceAvailability

    /** This device or OS can't run the model. */
    public data class Unsupported(val reason: String) : OnDeviceAvailability
}

/** A text-only conversation, already flattened for small local models. */
public data class OnDevicePrompt(
    val system: String?,
    val turns: List<Turn>,
    val maxOutputTokens: Int?,
    val temperature: Double?,
    val stopSequences: List<String>,
) {
    public data class Turn(val fromUser: Boolean, val text: String)
}

public sealed interface OnDeviceOutput {
    public data class Text(val text: String) : OnDeviceOutput

    /** Token counts if the runtime knows them; `null` stays unknown. */
    public data class Done(
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val hitTokenLimit: Boolean = false,
    ) : OnDeviceOutput
}

/**
 * Adapts an [OnDeviceEngine] to [LlmProvider].
 *
 * Capabilities are text only: requests with tools or structured output are rejected before
 * reaching the engine, with [LlmErrorKind.Unsupported]. An unavailable model also fails with
 * [LlmErrorKind.Unsupported], so a route like `Route(onDevice, cloud)` falls back to the cloud
 * under the default [io.github.shakeabdulrahman.keystone.llm.FailoverPolicy].
 */
public class OnDeviceProvider(
    private val engine: OnDeviceEngine,
    override val id: String = "on-device",
) : LlmProvider {
    override fun capabilities(model: String): ProviderCapabilities = TextOnly

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
        when (val availability = engine.availability(request.model)) {
            OnDeviceAvailability.Available -> Unit
            is OnDeviceAvailability.NotReady ->
                throw LlmException(LlmErrorKind.Unsupported, "On-device model not ready: ${availability.reason}", provider = id)
            is OnDeviceAvailability.Unsupported ->
                throw LlmException(LlmErrorKind.Unsupported, "On-device model unsupported: ${availability.reason}", provider = id)
        }
        var done = false
        engine.generate(request.model, toPrompt(request)).collect { output ->
            if (done) return@collect
            when (output) {
                is OnDeviceOutput.Text -> emit(ProviderEvent.TextDelta(output.text))
                is OnDeviceOutput.Done -> {
                    done = true
                    emit(ProviderEvent.UsageUpdate(Usage(output.inputTokens, output.outputTokens)))
                    emit(ProviderEvent.Finish(if (output.hitTokenLimit) FinishReason.Length else FinishReason.Stop))
                }
            }
        }
    }

    override fun close() {
        engine.close()
    }

    private fun toPrompt(request: ProviderRequest): OnDevicePrompt {
        val r = request.request
        val turns = r.messages.mapNotNull { m ->
            when (m) {
                is Message.System -> null
                is Message.User -> OnDevicePrompt.Turn(fromUser = true, text = m.text)
                is Message.Assistant -> {
                    if (m.toolCalls.isNotEmpty()) unsupported("tool calls in history")
                    OnDevicePrompt.Turn(fromUser = false, text = m.text)
                }
                is Message.ToolResult -> unsupported("tool results in history")
            }
        }
        val system = r.messages.filterIsInstance<Message.System>().joinToString("\n\n") { it.text }.ifEmpty { null }
        return OnDevicePrompt(system, turns, r.maxOutputTokens, r.temperature, r.stopSequences)
    }

    private fun unsupported(what: String): Nothing =
        throw LlmException(LlmErrorKind.Unsupported, "On-device provider doesn't support $what", provider = id)

    public companion object {
        public val TextOnly: ProviderCapabilities = ProviderCapabilities(
            tools = Support.Unsupported,
            toolChoiceRequired = Support.Unsupported,
            structuredOutput = StructuredOutputSupport.None,
        )
    }
}
