package io.github.shakeabdulrahman.keystone.llm.testing

import io.github.shakeabdulrahman.keystone.llm.Attempt
import io.github.shakeabdulrahman.keystone.llm.FinishReason
import io.github.shakeabdulrahman.keystone.llm.LlmClient
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.LlmListener
import io.github.shakeabdulrahman.keystone.llm.LlmProvider
import io.github.shakeabdulrahman.keystone.llm.ModelRef
import io.github.shakeabdulrahman.keystone.llm.ProviderCapabilities
import io.github.shakeabdulrahman.keystone.llm.ProviderEvent
import io.github.shakeabdulrahman.keystone.llm.ProviderRequest
import io.github.shakeabdulrahman.keystone.llm.StructuredOutputSupport
import io.github.shakeabdulrahman.keystone.llm.Support
import io.github.shakeabdulrahman.keystone.llm.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

val FullCapabilities = ProviderCapabilities(
    tools = Support.Native,
    toolChoiceRequired = Support.Native,
    structuredOutput = StructuredOutputSupport.JsonSchema,
)

/**
 * A scripted provider: the n-th call to [stream] (1-based) runs `script(n, request)`.
 */
class FakeProvider(
    override val id: String,
    private val capabilities: ProviderCapabilities = FullCapabilities,
    private val script: suspend kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.(call: Int, request: ProviderRequest) -> Unit,
) : LlmProvider {
    var calls = 0
        private set
    val requests = mutableListOf<ProviderRequest>()
    var closed = false

    override fun capabilities(model: String): ProviderCapabilities = capabilities

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
        calls++
        requests += request
        script(calls, request)
    }

    override fun close() {
        closed = true
    }
}

/** Emits a complete text answer. */
suspend fun kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.answer(
    vararg parts: String,
    usage: Usage = Usage(10, parts.sumOf { it.length }.toLong()),
    finish: FinishReason = FinishReason.Stop,
) {
    emit(ProviderEvent.Metadata(requestId = "req-1", model = "m-1"))
    parts.forEach { emit(ProviderEvent.TextDelta(it)) }
    emit(ProviderEvent.UsageUpdate(usage))
    emit(ProviderEvent.Finish(finish))
}

fun failure(kind: LlmErrorKind, retryAfter: Duration? = null, status: Int? = null): LlmException =
    LlmException(kind, "fake $kind", provider = "fake", httpStatus = status, retryAfter = retryAfter)

/** Records listener calls as readable strings. */
class RecordingListener : LlmListener {
    val log = mutableListOf<String>()
    override fun onAttemptStarted(target: ModelRef, attempt: Int) {
        log += "start $target #$attempt"
    }

    override fun onAttemptFailed(attempt: Attempt, error: LlmException, next: LlmListener.NextStep) {
        val step = when (next) {
            is LlmListener.NextStep.Retry -> "retry in ${next.delay}"
            is LlmListener.NextStep.Failover -> "failover to ${next.to}"
            LlmListener.NextStep.GiveUp -> "give up"
        }
        log += "fail ${attempt.target} #${attempt.number} ${error.kind}: $step"
    }

    override fun onSucceeded(attempt: Attempt) {
        log += "ok ${attempt.target} #${attempt.number}"
    }
}

/** Client with recorded (not real) sleeps and no jitter, so tests are deterministic and fast. */
fun testClient(
    vararg providers: LlmProvider,
    configure: LlmClient.Builder.() -> Unit = {},
): Pair<LlmClient, MutableList<Duration>> {
    val sleeps = mutableListOf<Duration>()
    val client = LlmClient {
        providers.forEach { provider(it) }
        retry = io.github.shakeabdulrahman.keystone.llm.RetryPolicy(jitter = 0.0)
        sleeper = { sleeps += it }
        configure()
    }
    return client to sleeps
}
