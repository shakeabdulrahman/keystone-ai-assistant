package io.github.shakeabdulrahman.keystone.llm.internal

import io.github.shakeabdulrahman.keystone.llm.Attempt
import io.github.shakeabdulrahman.keystone.llm.ChatRequest
import io.github.shakeabdulrahman.keystone.llm.ChatResponse
import io.github.shakeabdulrahman.keystone.llm.FailoverPolicy
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.LlmListener
import io.github.shakeabdulrahman.keystone.llm.LlmListener.NextStep
import io.github.shakeabdulrahman.keystone.llm.LlmProvider
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ModelRef
import io.github.shakeabdulrahman.keystone.llm.PriceTable
import io.github.shakeabdulrahman.keystone.llm.ProviderCapabilities
import io.github.shakeabdulrahman.keystone.llm.ProviderEvent
import io.github.shakeabdulrahman.keystone.llm.ProviderRequest
import io.github.shakeabdulrahman.keystone.llm.ResponseFormat
import io.github.shakeabdulrahman.keystone.llm.RetryPolicy
import io.github.shakeabdulrahman.keystone.llm.Route
import io.github.shakeabdulrahman.keystone.llm.StreamEvent
import io.github.shakeabdulrahman.keystone.llm.StreamRestartPolicy
import io.github.shakeabdulrahman.keystone.llm.StructuredEnforcement
import io.github.shakeabdulrahman.keystone.llm.StructuredOutputSupport
import io.github.shakeabdulrahman.keystone.llm.Timeouts
import io.github.shakeabdulrahman.keystone.llm.TokenEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.TimeSource

internal class EngineConfig(
    val providers: Map<String, LlmProvider>,
    val defaultRoute: Route?,
    val retry: RetryPolicy,
    val failover: FailoverPolicy,
    val streamRestart: StreamRestartPolicy,
    val timeouts: Timeouts,
    val listener: LlmListener?,
    val pricing: PriceTable?,
    val estimator: TokenEstimator?,
    val sleeper: suspend (Duration) -> Unit,
    val timeSource: TimeSource,
)

/**
 * Runs a request against its route under the retry, failover and timeout policies.
 *
 * The rule that keeps streamed output coherent: once an attempt has emitted a visible event
 * (text or a tool call) in live mode, a later failure of that attempt is final, unless the
 * caller chose [StreamRestartPolicy.EmitRestarted]. In buffered mode ([LlmClient.chat])
 * nothing is visible until the end, so every failure may be retried or failed over.
 */
internal class Engine(private val config: EngineConfig) {

    /** [live]: forward events as they happen. Otherwise only [StreamEvent.Completed] is sent. */
    suspend fun run(request: ChatRequest, live: Boolean, sink: suspend (StreamEvent) -> Unit) {
        val route = request.route ?: config.defaultRoute
            ?: throw IllegalArgumentException("The request has no route and the client has no default route")
        for (target in route.targets) {
            require(target.provider in config.providers) { "No provider registered with id '${target.provider}'" }
        }
        val attempts = mutableListOf<Attempt>()
        var lastError: LlmException? = null
        var visible = false // something since the last Started/Restarted was shown to the caller

        suspend fun emit(event: StreamEvent) {
            try {
                sink(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw DownstreamFailure(e) // the collector's exception: never retried
            }
        }

        try {
            for ((targetIndex, target) in route.targets.withIndex()) {
                val provider = config.providers.getValue(target.provider)
                val next = route.targets.getOrNull(targetIndex + 1)
                val capabilities = provider.capabilities(target.model)

                val missing = capabilities.missingFor(request)
                if (missing != null) {
                    val error = LlmException(LlmErrorKind.Unsupported, "$target does not support $missing", provider = target.provider)
                    val attempt = Attempt(target, 1, Duration.ZERO, error.kind)
                    attempts += attempt
                    val failover = next != null && config.failover.failoverOn(error)
                    notify { onAttemptFailed(attempt, error, if (failover) NextStep.Failover(next!!) else NextStep.GiveUp) }
                    if (!failover) throw error.withAttempts(attempts)
                    lastError = error
                    continue
                }
                val (adapted, enforcement) = adaptResponseFormat(request, capabilities)
                val providerRequest = ProviderRequest(target.model, adapted, adapted.providerOptions[provider.id])

                var number = 0
                while (true) {
                    number++
                    notify { onAttemptStarted(target, number) }
                    val mark = config.timeSource.markNow()
                    val aggregator = AttemptAggregator(target, adapted, config.pricing, config.estimator)
                    var startedThisAttempt = false

                    val failure: LlmException? = try {
                        collectAttempt(provider, providerRequest) { event ->
                            val visibleEvents = aggregator.onEvent(event)
                            if (live && visibleEvents.isNotEmpty()) {
                                if (!startedThisAttempt) {
                                    startedThisAttempt = true
                                    visible = true
                                    emit(StreamEvent.Started(target, aggregator.requestId))
                                }
                                for (e in visibleEvents) emit(e)
                            }
                        }
                        if (!aggregator.finished) {
                            throw LlmException(LlmErrorKind.Network, "${target.provider} stream ended before the answer was complete", provider = target.provider)
                        }
                        null
                    } catch (e: DownstreamFailure) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: LlmException) {
                        e
                    } catch (e: Throwable) {
                        LlmException(LlmErrorKind.Unknown, "Provider '${provider.id}' failed with ${e::class.simpleName}", provider = provider.id, cause = e)
                    }

                    if (failure == null) {
                        val attempt = Attempt(target, number, mark.elapsedNow(), null, requestId = aggregator.requestId)
                        attempts += attempt
                        val (toolEvents, response) = aggregator.complete(attempts.toList(), enforcement)
                        notify { onSucceeded(attempt) }
                        if (live) {
                            if (!startedThisAttempt) emit(StreamEvent.Started(target, aggregator.requestId))
                            toolEvents.forEach { emit(it) }
                        }
                        emit(StreamEvent.Completed(response))
                        return
                    }
                    val error: LlmException = failure

                    val attempt = Attempt(
                        target, number, mark.elapsedNow(), error.kind, error.httpStatus,
                        error.requestId ?: aggregator.requestId,
                    )
                    attempts += attempt
                    val mayContinue = !visible || config.streamRestart == StreamRestartPolicy.EmitRestarted

                    val delay = if (mayContinue) config.retry.delayBeforeRetry(number, error) else null
                    if (delay != null) {
                        notify { onAttemptFailed(attempt, error, NextStep.Retry(delay)) }
                        if (visible) {
                            emit(StreamEvent.Restarted(attempt, target))
                            visible = false
                        }
                        config.sleeper(delay)
                        continue
                    }
                    if (mayContinue && next != null && config.failover.failoverOn(error)) {
                        notify { onAttemptFailed(attempt, error, NextStep.Failover(next)) }
                        if (visible) {
                            emit(StreamEvent.Restarted(attempt, next))
                            visible = false
                        }
                        lastError = error
                        break
                    }
                    notify { onAttemptFailed(attempt, error, NextStep.GiveUp) }
                    throw error.withAttempts(attempts)
                }
            }
            throw (lastError ?: LlmException(LlmErrorKind.Unknown, "No target was tried")).withAttempts(attempts)
        } catch (e: DownstreamFailure) {
            throw e.cause!!
        }
    }

    suspend fun chat(request: ChatRequest): ChatResponse {
        var response: ChatResponse? = null
        run(request, live = false) { if (it is StreamEvent.Completed) response = it.response }
        return checkNotNull(response)
    }

    /** Collects one provider stream, enforcing the first-event, idle and total timeouts. */
    private suspend fun collectAttempt(
        provider: LlmProvider,
        request: ProviderRequest,
        onEvent: suspend (ProviderEvent) -> Unit,
    ) {
        val timeouts = config.timeouts
        val body: suspend () -> Unit = {
            coroutineScope {
                // Rendezvous: no read-ahead, so backpressure reaches the provider (and the socket).
                val channel = Channel<ProviderEvent>(Channel.RENDEZVOUS)
                launch {
                    var cause: Throwable? = null
                    try {
                        provider.stream(request).collect { channel.send(it) }
                    } catch (e: Throwable) {
                        cause = e
                        if (e is CancellationException) throw e
                    } finally {
                        channel.close(cause) // a provider failure reaches the reader below
                    }
                }
                var first = true
                while (true) {
                    val limit = if (first) timeouts.firstEvent else timeouts.idle
                    val result = if (limit == null) {
                        channel.receiveCatching()
                    } else {
                        withTimeoutOrNull(limit) { channel.receiveCatching() } ?: run {
                            channel.cancel()
                            val what = if (first) "No response from ${provider.id}" else "No stream event from ${provider.id}"
                            throw LlmException(LlmErrorKind.Timeout, "$what within $limit", provider = provider.id)
                        }
                    }
                    if (result.isClosed) {
                        result.exceptionOrNull()?.let { throw it }
                        break
                    }
                    first = false
                    onEvent(result.getOrThrow())
                }
            }
        }
        val total = timeouts.total
        if (total == null) {
            body()
        } else {
            withTimeoutOrNull(total) { body(); true }
                ?: throw LlmException(LlmErrorKind.Timeout, "${provider.id} attempt exceeded $total", provider = provider.id)
        }
    }

    private inline fun notify(block: LlmListener.() -> Unit) {
        val listener = config.listener ?: return
        try {
            listener.block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A listener must not break requests.
        }
    }

    private class DownstreamFailure(cause: Throwable) : RuntimeException(cause)

    companion object {
        /**
         * Fits a structured-output request to what the target supports. Downgrades only
         * happen when the request allows them ([ProviderCapabilities.missingFor] checked that).
         */
        fun adaptResponseFormat(request: ChatRequest, capabilities: ProviderCapabilities): Pair<ChatRequest, StructuredEnforcement?> =
            when (val format = request.responseFormat) {
                ResponseFormat.Text -> request to null
                ResponseFormat.JsonMode -> request to StructuredEnforcement.ProviderJsonMode
                is ResponseFormat.JsonSchema -> when (capabilities.structuredOutput) {
                    StructuredOutputSupport.JsonSchema -> request to StructuredEnforcement.ProviderSchema
                    StructuredOutputSupport.JsonMode ->
                        request.copy(responseFormat = ResponseFormat.JsonMode, messages = withSchemaInstruction(request.messages, format)) to
                            StructuredEnforcement.ProviderJsonMode
                    StructuredOutputSupport.None ->
                        request.copy(responseFormat = ResponseFormat.Text, messages = withSchemaInstruction(request.messages, format)) to
                            StructuredEnforcement.PromptOnly
                }
            }

        private fun withSchemaInstruction(messages: List<Message>, format: ResponseFormat.JsonSchema): List<Message> {
            val instruction = Message.System(
                "Reply with only a JSON value that matches this JSON Schema. No prose, no code fences.\n${format.schema}",
            )
            val leadingSystem = messages.takeWhile { it is Message.System }.size
            return messages.take(leadingSystem) + instruction + messages.drop(leadingSystem)
        }
    }
}

internal fun LlmException.withAttempts(list: List<Attempt>): LlmException = apply { attempts = list.toList() }
