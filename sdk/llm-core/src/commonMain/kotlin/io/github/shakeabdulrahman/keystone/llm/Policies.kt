package io.github.shakeabdulrahman.keystone.llm

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Retries of the same target.
 *
 * Every attempt is a new, billable model call: LLM requests aren't idempotent in cost, and a
 * provider may have done (and billed) some work before failing. Keep [maxAttempts] small.
 *
 * Delay before retry `n` (1-based): `min(maxDelay, initialDelay × multiplier^(n-1))`, then
 * ±[jitter] (a fraction). If the error carries a `Retry-After`, that is used instead, as long
 * as it is at most [maxRetryAfter]; a longer requested wait means "don't retry this target"
 * and the client moves on to failover.
 *
 * @property maxAttempts attempts per target, including the first. 1 = no retries.
 * @property retryOn which failures to retry. Default: [LlmException.isTransient].
 */
public class RetryPolicy(
    public val maxAttempts: Int = 3,
    public val initialDelay: Duration = 500.milliseconds,
    public val maxDelay: Duration = 8.seconds,
    public val multiplier: Double = 2.0,
    public val jitter: Double = 0.2,
    public val maxRetryAfter: Duration = 30.seconds,
    private val random: Random = Random.Default,
    public val retryOn: (LlmException) -> Boolean = { it.isTransient },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(!initialDelay.isNegative() && !maxDelay.isNegative()) { "delays must not be negative" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
        require(jitter in 0.0..1.0) { "jitter must be within 0..1" }
    }

    /**
     * The delay before retrying after failed attempt [attempt] (1-based), or `null` if this
     * error shouldn't be retried at all.
     */
    public fun delayBeforeRetry(attempt: Int, error: LlmException): Duration? {
        if (attempt >= maxAttempts || !retryOn(error)) return null
        error.retryAfter?.let { requested ->
            return if (requested <= maxRetryAfter) requested.coerceAtLeast(Duration.ZERO) else null
        }
        var base = initialDelay
        repeat(attempt - 1) { base = (base * multiplier).coerceAtMost(maxDelay) }
        base = base.coerceAtMost(maxDelay)
        if (jitter == 0.0 || base == Duration.ZERO) return base
        val factor = 1.0 + jitter * (random.nextDouble() * 2.0 - 1.0)
        return base * factor
    }

    public companion object {
        public val None: RetryPolicy = RetryPolicy(maxAttempts = 1)
    }
}

/**
 * Moving to the next [Route] target after a target has failed (after its retries).
 *
 * The default fails over on transient errors and on errors specific to one provider or
 * model (auth, permission, not found, quota, unsupported capability, unparseable response).
 * It does not fail over on [LlmErrorKind.InvalidRequest], [LlmErrorKind.ContentFiltered] and
 * [LlmErrorKind.StructuredOutput], which would most likely fail the same way elsewhere.
 * Failing over on auth errors keeps an app working through a revoked key, but can hide
 * misconfiguration: watch [LlmListener.onAttemptFailed].
 */
public class FailoverPolicy(
    public val failoverOn: (LlmException) -> Boolean = { it.kind in DEFAULT_KINDS },
) {
    public companion object {
        public val DEFAULT_KINDS: Set<LlmErrorKind> = setOf(
            LlmErrorKind.Network, LlmErrorKind.Timeout, LlmErrorKind.RateLimited, LlmErrorKind.ServerError,
            LlmErrorKind.QuotaExceeded, LlmErrorKind.Authentication, LlmErrorKind.PermissionDenied,
            LlmErrorKind.NotFound, LlmErrorKind.Unsupported, LlmErrorKind.MalformedResponse,
        )
        public val Default: FailoverPolicy = FailoverPolicy()
        public val Never: FailoverPolicy = FailoverPolicy { false }
    }
}

/**
 * What a stream may do after it has shown output and then fails.
 */
public enum class StreamRestartPolicy {
    /**
     * Fail the flow. No retry or failover once anything visible was emitted, so output is
     * never duplicated or spliced from two answers. The default.
     */
    Never,

    /**
     * Retry / fail over under the usual policies, emitting [StreamEvent.Restarted] first so
     * the caller can discard the partial answer.
     */
    EmitRestarted,
}

/**
 * Time limits for one attempt. `null` disables a limit. Exceeding one is an
 * [LlmErrorKind.Timeout] failure (retryable), never a silent hang.
 *
 * @property firstEvent until the provider's first event (time to first token, incl. connect).
 * @property idle between two events of a stream.
 * @property total the whole attempt. Off by default, as long answers legitimately take time.
 */
public data class Timeouts(
    val firstEvent: Duration? = 60.seconds,
    val idle: Duration? = 60.seconds,
    val total: Duration? = null,
)

/**
 * Observes retries and failover, e.g. for logs or metrics. Calls are made on the request's
 * coroutine, so keep them fast; exceptions thrown here are caught and ignored. Nothing passed
 * here contains prompts, output or keys.
 */
public interface LlmListener {
    public fun onAttemptStarted(target: ModelRef, attempt: Int) {}

    /** [next] is what the client does now. */
    public fun onAttemptFailed(attempt: Attempt, error: LlmException, next: NextStep) {}

    public fun onSucceeded(attempt: Attempt) {}

    public sealed interface NextStep {
        public data class Retry(val delay: Duration) : NextStep
        public data class Failover(val to: ModelRef) : NextStep
        public data object GiveUp : NextStep
    }
}
