# ADR 0014: Abuse protection: layered rate limits, App Check, strict HTTP defaults

- Status: Accepted
- Date: 2026-10-16

## Context
Each chat message costs model calls, and a public API attracts scripts. A stolen ID token, a buggy retry loop or a flood of anonymous requests could use up the free AI quota or the database. Sign-in proves *who* is calling, not that the caller is our app.

## Decision
- **Token-bucket rate limits in layers:** per IP on every request (before token verification), per user on every authenticated request, and stricter per-user limits for chat (12/min, burst 5) and confirmations (20/min). The answer is `429 rate_limited` with `Retry-After`, which the app shows as "try again in N seconds".
- **The in-memory limiter sits behind a `RateLimiter` interface.** Its key count is bounded (LRU), so a flood of IPs can't exhaust memory. Redis replaces it when there's more than one instance.
- **Client IPs come from the rightmost trusted `X-Forwarded-For` hop** (`TRUSTED_PROXY_HOPS`), never from the forgeable leftmost entry.
- **Firebase App Check** (Play Integrity on Android), with modes `off` → `monitor` → `enforce`, so it can be rolled out without locking out real users.
- **Strict HTTP defaults:** a 64 KB body limit (also for chunked uploads), `Cache-Control: no-store`, `nosniff`, deny framing, a restrictive CSP, HSTS in production, and API docs off in production.
- **Key outages are 503, never 401.** If Google's signing keys can't be fetched, the cached ones are used for another minute. With none cached, the answer is `503 auth_unavailable`, so users aren't signed out by someone else's outage.

## Consequences
- Abuse costs are bounded per user, and an attacker needs a genuine app *and* an account.
- In-memory limits reset on restart and aren't shared across instances. That's acceptable for one free instance and documented.
- App Check depends on Google Play services; devices without them fail in enforce mode.

## Alternatives considered
- **Gateway or WAF rate limiting (Cloudflare, API Gateway):** good at scale, but not free with per-user keys, and it can't see our user IDs.
- **A slowapi/limits library:** fine, but a 60-line token bucket is easier to test and to explain.
- **API keys in the app:** extractable from any APK. App Check attestation can't be copied that way.
