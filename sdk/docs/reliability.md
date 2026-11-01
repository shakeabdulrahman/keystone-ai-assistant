# Retries and failover

```kotlin
LlmClient {
    retry = RetryPolicy(maxAttempts = 3, initialDelay = 500.milliseconds, maxDelay = 8.seconds, multiplier = 2.0, jitter = 0.2, maxRetryAfter = 30.seconds)
    failover = FailoverPolicy.Default
    streamRestart = StreamRestartPolicy.Never
    timeouts = Timeouts(firstEvent = 60.seconds, idle = 60.seconds)
    listener = myListener
}
```

## The algorithm

For each target of the route, in order:

1. If the target lacks a capability the request needs, record an `Unsupported` attempt and
   (if the failover policy allows it) move on without calling it.
2. Call it. On failure, classify the error:
   - **Retry** the same target if output wasn't shown, attempts < `maxAttempts`, and
     `retryOn(error)` (default: `isTransient`). The delay is the server's `Retry-After` if it
     sent one (≤ `maxRetryAfter`), else exponential backoff with jitter. A `Retry-After`
     above the cap means "don't retry here".
   - Otherwise **fail over** to the next target if output wasn't shown and `failoverOn(error)`.
   - Otherwise **throw** the error, with every attempt in `LlmException.attempts`.

"Output wasn't shown" is always true for `chat()`, which buffers. For `stream()`, it's true
until `Started` is emitted, unless `StreamRestartPolicy.EmitRestarted`, which allows both
retry and failover after output and announces them with `Restarted`.

## Classification

| Kind | Typical cause | Transient (retried) | Fails over by default |
|---|---|---|---|
| `Network` | connection error, stream cut off | ✓ | ✓ |
| `Timeout` | HTTP 408, `Timeouts` exceeded | ✓ | ✓ |
| `RateLimited` | HTTP 429 | ✓ | ✓ |
| `ServerError` | HTTP 5xx/529, Groq's `tool_use_failed` | ✓ | ✓ |
| `QuotaExceeded` | 429 `insufficient_quota` | | ✓ |
| `Authentication` / `PermissionDenied` | 401 / 403 | | ✓ |
| `NotFound` | 404, e.g. a retired model | | ✓ |
| `Unsupported` | missing capability, on-device model not ready | | ✓ |
| `MalformedResponse` | unparseable stream | | ✓ |
| `InvalidRequest` | 400/413/422 | | |
| `ContentFiltered` | safety block | | |
| `StructuredOutput` | bad JSON from the model | | |
| `Unknown` | bug in a custom provider | | |

Failing over on auth errors keeps an app working through a revoked key, but it can hide
misconfiguration. Watch the listener. Override with `FailoverPolicy { error -> … }`.

## Retry-After

`retry-after-ms` (OpenAI) wins. `Retry-After` is read as seconds (decimals allowed) or as an
HTTP-date. A date is measured against the response's own `Date` header, so a wrong phone clock
doesn't matter. Invalid values are ignored.

## Cost and idempotency

LLM calls are not idempotent in cost. Every retry and failover attempt is a new request that
the provider may bill, including attempts that failed after generating part of an answer.
Keep `maxAttempts` small, and use `RetryPolicy.None` for expensive requests.

## Observability

`LlmListener` receives `onAttemptStarted`, `onAttemptFailed(attempt, error, nextStep)` and
`onSucceeded`. Attempts carry target, number, duration, error kind, HTTP status and provider
request id. They carry no prompt, output or key. `LlmException.message` is also free of them;
the provider's own text is in `providerMessage` (truncated) because it may echo input.
