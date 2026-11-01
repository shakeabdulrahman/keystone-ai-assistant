package io.github.shakeabdulrahman.keystone.llm

import io.github.shakeabdulrahman.keystone.llm.internal.Engine
import io.github.shakeabdulrahman.keystone.llm.internal.EngineConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The entry point: one client for all registered providers.
 *
 * ```
 * val llm = LlmClient {
 *     provider(OpenAiCompatibleProvider(id = "groq", baseUrl = "https://api.groq.com/openai/v1", auth = Auth.ApiKey(key)))
 *     defaultRoute = Route(ModelRef("groq", "openai/gpt-oss-120b"))
 * }
 * val answer = llm.chat(ChatRequest(listOf(Message.user("Hello"))))
 * ```
 *
 * Thread-safe; share one instance. [close] releases the providers' HTTP clients.
 */
public class LlmClient internal constructor(
    private val providers: Map<String, LlmProvider>,
    private val engine: Engine,
) : AutoCloseable {

    /**
     * Streams the answer. Cold: nothing is sent until you collect, and each collection is a
     * new request. Cancelling the collector cancels the request and closes the connection.
     *
     * Retries and failover happen only before the first [StreamEvent.Started]; after that a
     * failure ends the flow with an [LlmException], unless the client uses
     * [StreamRestartPolicy.EmitRestarted].
     */
    public fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        engine.run(request, live = true) { emit(it) }
    }

    /**
     * The complete answer. Output is buffered until the end, so a failure at any point may
     * be retried or failed over (each attempt is a new, billable call).
     *
     * @throws LlmException when every allowed attempt failed; [LlmException.attempts] lists them.
     */
    public suspend fun chat(request: ChatRequest): ChatResponse = engine.chat(request)

    /** The capabilities the client checks requests against for [target]. */
    public fun capabilities(target: ModelRef): ProviderCapabilities =
        requireNotNull(providers[target.provider]) { "No provider '${target.provider}'" }.capabilities(target.model)

    /** Ids of the registered providers. */
    public val providerIds: Set<String> get() = providers.keys

    override fun close() {
        providers.values.forEach { runCatching { it.close() } }
    }

    public class Builder internal constructor() {
        private val providers = LinkedHashMap<String, LlmProvider>()

        /** Used when a request has no [ChatRequest.route]. */
        public var defaultRoute: Route? = null
        public var retry: RetryPolicy = RetryPolicy()
        public var failover: FailoverPolicy = FailoverPolicy.Default
        public var streamRestart: StreamRestartPolicy = StreamRestartPolicy.Never
        public var timeouts: Timeouts = Timeouts()
        public var listener: LlmListener? = null

        /** Prices for [ChatResponse.cost]. No prices, no cost: the SDK ships none. */
        public var pricing: PriceTable? = null

        /** Fills in token counts a provider didn't report, labelled [UsageSource.Estimated]. Off by default. */
        public var tokenEstimator: TokenEstimator? = null

        internal var sleeper: suspend (Duration) -> Unit = { delay(it) }
        internal var timeSource: TimeSource = TimeSource.Monotonic

        /** Registers [provider] under its [LlmProvider.id]. Ids must be unique. */
        public fun provider(provider: LlmProvider) {
            require(provider.id !in providers) { "Provider id '${provider.id}' is already registered" }
            providers[provider.id] = provider
        }

        internal fun build(): LlmClient {
            require(providers.isNotEmpty()) { "Register at least one provider" }
            defaultRoute?.targets?.forEach { require(it.provider in providers) { "Default route uses unknown provider '${it.provider}'" } }
            val config = EngineConfig(
                providers = providers.toMap(),
                defaultRoute = defaultRoute,
                retry = retry,
                failover = failover,
                streamRestart = streamRestart,
                timeouts = timeouts,
                listener = listener,
                pricing = pricing,
                estimator = tokenEstimator,
                sleeper = sleeper,
                timeSource = timeSource,
            )
            return LlmClient(config.providers, Engine(config))
        }
    }
}

/** Builds an [LlmClient]. */
public fun LlmClient(configure: LlmClient.Builder.() -> Unit): LlmClient =
    LlmClient.Builder().apply(configure).build()
