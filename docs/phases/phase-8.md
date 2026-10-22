# Phase 8 — Security hardening, observability, deployment and demo

**Outcome:** Keystone is ready to show. The API protects itself:
- rate limits per IP, per user, for chat and for actions, with "try again in N seconds" in the app;
- Firebase App Check, so only genuine copies of the app get through;
- strict HTTP headers and a request size limit.

Its health is visible on a Grafana dashboard: time to first answer, tool latency, rate-limit hits. It deploys for free (Render, Neon, Gemini) with one blueprint and a smoke test. The repository now has a full README with architecture diagrams, a 3-minute demo script and an interview cheat sheet.

## 1. What we built

| Area | What exists now | Key files |
| --- | --- | --- |
| Rate limits | Token buckets: IP 300/min (before auth), user 120/min, chat 12/min (burst 5), confirm/cancel 20/min; `429 rate_limited` + `Retry-After`; bounded memory (LRU); forge-proof client IP (`TRUSTED_PROXY_HOPS`) | `app/core/rate_limit.py`, `app/api/deps.py` |
| HTTP hardening | `nosniff`, `X-Frame-Options: DENY`, CSP `default-src 'none'`, `Referrer-Policy`, `Permissions-Policy`, `Cache-Control: no-store`, HSTS in production; 64 KB body limit (also chunked) → `413` | `app/core/security.py` |
| App Check | Verifies `X-Firebase-AppCheck` JWTs against Google's keys (issuer, audience, app allow-list); `off` / `monitor` / `enforce` | `app/core/app_check.py` |
| Key outages | Google key fetch fails → cached keys for another minute, else `503 auth_unavailable` (never a 401 that signs users out) | `app/core/auth.py`, `app/core/errors.py` |
| Metrics | `/metrics` (bearer token): HTTP by route template, chat turns, time to first output, duration, agent steps, tokens, tool calls and latency, RAG sources, actions, rate limits, pushes | `app/core/metrics.py`, `app/api/metrics.py` |
| Dashboards | Prometheus + Grafana in the compose `observability` profile, a provisioned 14-panel dashboard | `ops/`, `docker-compose.yml` |
| Gemini | Streaming chat with function calling (`streamGenerateContent`), schema conversion, thought signatures kept, safety blocks handled, key in a header; embeddings at 768 dims, normalised | `app/ai/llm/gemini.py`, `app/rag/embeddings.py` |
| Deployment | `render.yaml` blueprint, Neon URL normalisation for asyncpg, `start.sh` (migrate → ingest → serve on `$PORT`), pinned `uv`, container healthcheck, smoke test | `render.yaml`, `backend/start.sh`, `scripts/smoke_test.py`, `docs/deployment.md` |
| Supply chain | `pip-audit` on every push and weekly; Dependabot (uv, Gradle, Docker, Actions); gitleaks (existing) | `.github/workflows/security.yml`, `.github/dependabot.yml` |
| Mobile | App Check (Play Integrity in release, debug provider in debug) through a shared `AppCheckTokenProvider` Ktor plugin; `Retry-After` parsed into `AppError.Http`; clear copy for 429 / 403 / 503 / 401 / 413; release API URL and signing from `local.properties` or environment; unsigned release build in CI | `shared/.../auth/AppCheck.kt`, `androidApp/.../auth/`, `ui/common/ErrorText.kt`, `androidApp/build.gradle.kts` |
| Docs | Final README (Mermaid diagrams, security model), demo script, interview cheat sheet, ADRs 0014–0016 | `README.md`, `docs/` |
| Tests | Backend **223** with Postgres (+40: headers, 413 both ways, chat 429 after 5, Retry-After, refill on a fake clock, bounded memory, IP limit, forged X-Forwarded-For, App Check valid/expired/wrong project/monitor/enforce, key outage → 503 and stale keys, metrics token and counters, Gemini streaming/tools/errors/schema/history/embeddings, Neon URLs). Mobile **134** (+8: App Check header, provider failure, header kept across the 401 retry, Retry-After; error copy) | `backend/tests/test_security.py`, `test_metrics.py`, `test_gemini.py`, `mobile/**/test` |

## 2. How it works

```
Request ─▶ RequestContext (request ID, access log, metrics by route template)
        ─▶ SecurityHeaders (on every response, errors included)
        ─▶ BodySizeLimit  (Content-Length check, then counted while streaming) ── 413
        ─▶ IpRateLimit    (X-Forwarded-For: rightmost trusted hop) ─────────── 429
        ─▶ route deps: App Check (monitor/enforce) ─▶ Firebase ID token ─▶ user limit
                       ─▶ chat / actions limit ──────────────────────────────── 429
        ─▶ handler ─▶ LLMProvider (Ollama locally | Gemini in the cloud)
```

- **Cheap checks first.** The IP limit and body limit run before any token verification or database work, so floods cost almost nothing.
- **Token bucket.** Each key has a bucket that refills at the per-minute rate and holds up to `burst` tokens. Short bursts are fine, and sustained abuse is capped. `Retry-After` says exactly when the next token arrives.
- **Which IP?** Behind Render's proxy, the TCP peer is the proxy. Each proxy *appends* to `X-Forwarded-For`, so the trustworthy entry is the one the last trusted proxy wrote, counted from the right. Anything to its left was written by the client and could be forged to dodge limits.
- **App Check vs sign-in.** The ID token says *who* the user is. The App Check token says the request comes from our genuine app on a real device. A script with a stolen ID token has no valid App Check token. Monitor mode logs failures without blocking, so you can see what enforcing would break before switching.
- **Metrics without privacy cost.** Labels are route templates (`/v1/conversations/{conversation_id}`), tool names and outcomes, never user IDs or message text. That keeps Prometheus small (low cardinality) and the data harmless.
- **Gemini as a drop-in.** The adapter translates our `LLMRequest` into Gemini's format: `systemInstruction`; `contents` with `user`/`model` roles; tool results as `functionResponse` parts merged into one turn; tool schemas converted to Gemini's OpenAPI subset (`anyOf[X, null]` → `X` + `nullable`). The chat service doesn't know which model it's talking to.

## 3. Why we chose it

| Decision | Why | Alternative rejected |
| --- | --- | --- |
| Own token bucket behind an interface | 60 testable lines, per-user keys, Redis-ready | A library or a paid gateway |
| Layered limits (IP → user → chat/actions) | Each layer protects a different resource | One global limit |
| Rightmost trusted X-Forwarded-For hop | Can't be forged by the client | Leftmost entry (spoofable) or `--forwarded-allow-ips '*'` |
| App Check in monitor first | See failures before blocking real users | Enforcing on day one |
| 503, not 401, when Google's keys are unreachable | An outage elsewhere shouldn't sign everyone out | Treating it as an invalid token |
| Metrics token, disabled by default | Usage patterns are sensitive | A public `/metrics` |
| Render + Neon + Gemini | All free, Docker image unchanged, Postgres with pgvector | Cloud Run + Cloud SQL (paid), Fly (card) |
| Model names as settings | Free models change; no rebuild needed | Hard-coded model IDs |
| Release signing from local.properties/env | Keys never in git, the same config in CI | A keystore in the repository |

## 4. Bugs and lessons from this phase

1. **"Can't reach Google" looked like a crash.** Running the production configuration locally, without access to Google's key servers, the smoke test found that a failed key download surfaced as a **500**. In real life it would have looked to users like a broken server, and as a 401 it would have signed them out. The fix: stale-if-error caching of the signing keys, then `503 auth_unavailable` with `Retry-After`, and the app says "try again in a moment". The lesson: test the production configuration with its dependencies *down*.
2. **Hosted Postgres URLs don't fit asyncpg.** Neon gives `postgres://…?sslmode=require&channel_binding=require`. SQLAlchemy needs the `postgresql+asyncpg` scheme, and asyncpg rejects libpq's `sslmode`. The settings now normalise these URLs (tested, and verified against a real Postgres with `sslmode=disable`).
3. **Forgeable IPs.** The easy option, uvicorn's `--forwarded-allow-ips '*'`, would let anyone set their own `X-Forwarded-For` and get a fresh rate-limit bucket per request. The rightmost-trusted-hop rule closes that.
4. **Reproducible builds.** The Dockerfile copied `uv:latest`, so the same commit could build differently next month. It's pinned now, and Dependabot proposes updates.
5. **Gemini details that would bite later:** thinking models attach a "thought signature" to function calls, which must be sent back with the next request, so `ToolCall` carries an opaque `signature`. "Thought" parts are reasoning summaries and are never shown to users. An object schema with no properties is rejected, so it's omitted.

## 5. How to run and test it

1. **Backend:** `docker compose up --build`. The API now starts through `start.sh`.
2. **Limits:** send 6 quick messages. The 6th shows "You're going a bit fast. Try again in N seconds".
   `curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8000/v1/chat -H 'content-type: application/json' -d @big.json` with a body over 64 KB gives `413`.
3. **Headers:** `curl -I localhost:8000/healthz` shows `cache-control: no-store`, `x-frame-options: DENY` and the CSP.
4. **Dashboard:** `docker compose --profile observability up -d`, open http://localhost:3000 and chat for a minute: turns, time to first output, tool calls and latency fill in. Raw data: `curl -H 'Authorization: Bearer local-dev-metrics-token' localhost:8000/metrics`.
5. **Android:** sync Gradle (new: Firebase App Check). Run the debug app and copy the debug secret from Logcat (`DebugAppCheckProvider`) into Firebase → App Check → Manage debug tokens. Start the backend with `APP_CHECK_MODE=monitor` and `FIREBASE_PROJECT_NUMBER=…`; the log shows no more `app_check_failed`. Then try `enforce`.
6. **Cloud:** follow `docs/deployment.md`, then `python3 scripts/smoke_test.py https://<your-service>.onrender.com`.
7. **Release build:** set `keystone.releaseApiBaseUrl` and the signing keys in `mobile/local.properties`, then `./gradlew :androidApp:assembleRelease`.
8. **Tests:** `cd backend && uv run pytest` · `cd mobile && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest`

## 6. Interview concepts demonstrated

- Rate-limiting algorithms (token bucket vs fixed and sliding windows), layered limits, `Retry-After`, bounded state
- Client IP determination behind proxies; spoofing `X-Forwarded-For`
- Device and app attestation (Play Integrity / App Check) vs user authentication; staged rollout
- Defence in depth: security headers, body limits, `no-store`, HSTS, docs off in production
- Failure-mode design: stale-if-error caches, 503 vs 401 semantics
- Observability for AI systems: TTFT, tool latency, token usage, low-cardinality labels, Prometheus/Grafana
- Vendor-agnostic LLM integration: adapting tool schemas and message formats between providers
- Twelve-factor deployment: config in the environment, one image everywhere, a start script, a health check, smoke tests
- Supply-chain security: dependency audits, Dependabot, secret scanning, pinned build tools
- Android release engineering: signing secrets outside VCS, R8 builds in CI, build-variant source sets

**60-second explanation:**

> "For production I added protection in layers. Cheap checks run first: a request size limit and a per-IP token bucket, using the IP the trusted proxy appended so it can't be forged. Then come Firebase App Check, which proves the request comes from our genuine app via Play Integrity and is rolled out monitor-then-enforce, and the Firebase sign-in check. Last are per-user limits, with tighter ones for chat, because model calls cost money. Limits answer 429 with Retry-After, and the app says 'try again in N seconds'. Every response gets strict security headers and no-store caching. For observability, a token-protected Prometheus endpoint tracks time to first output, tool latency, tokens, actions and rate-limit hits, using route templates and tool names, never user data, on a Grafana dashboard. Deployment is free: the same Docker image runs on Render with Neon Postgres, and a Gemini adapter replaces Ollama by configuration only. A smoke test checks the deployment. Testing the production config with Google unreachable found that a key outage returned 500, so keys are now cached stale-if-error and failures return 503 rather than signing users out."

## 7. Likely interview questions

1. **Token bucket or sliding window?** A token bucket allows natural bursts (a user sending three quick follow-ups) while capping the sustained rate. It's O(1) per key and gives an exact Retry-After.
2. **How do you rate-limit across several instances?** Move the buckets to Redis, using an atomic Lua script or `INCR` with expiry per window. The `RateLimiter` interface makes that a new adapter, not a rewrite.
3. **What does App Check protect against, and what doesn't it?** It blocks scripts and modified apps from using a stolen token. It doesn't protect against a compromised but genuine device, and it doesn't replace authorisation. It's one layer.
4. **Why can't you trust `X-Forwarded-For`?** Clients can send it. Only the entries appended by proxies you control are trustworthy, so count from the right by the number of trusted hops.
5. **What would you alert on?** A falling chat success rate, p95 time to first output, a spike in tool errors per tool, a rising 429 rate (abuse or limits too tight), and readiness failures.
6. **How do you swap the LLM vendor?** Implement the `LLMProvider` stream interface (text deltas, tool calls, a final Done with usage), map errors to our error types, and convert tool schemas. The service, tools and app don't change, and a setting selects it.
7. **How are secrets handled end to end?** Environment variables or mounted files in production (Render secrets and Secret Files), `.env` locally (gitignored), `SecretStr` so they never print, gitleaks in CI. The app holds no secrets; its signing keys stay outside git.

## 8. Check yourself

1. A client sends `X-Forwarded-For: 1.2.3.4` and Render appends the real IP 203.0.113.9. With `TRUSTED_PROXY_HOPS=1`, which IP is limited, and why?
2. Burst 5 and 12/min: after 5 instant messages, how long until the 6th is allowed?
3. What happens to a request without an App Check token in each mode?
4. Google's key server is down and nothing is cached. What status does the app get, and what does it show?
5. Why is the HTTP metric labelled with `/v1/conversations/{conversation_id}` rather than the real path?
6. What two settings change to move from Ollama to Gemini, and what must happen to the stored embeddings?

## 9. CV / LinkedIn line

> Took an AI HR assistant (Kotlin Multiplatform + FastAPI) to production quality: layered token-bucket rate limiting with forge-proof client IPs, Firebase App Check attestation with staged enforcement, hardened HTTP defaults, Prometheus/Grafana observability for LLM latency and tool usage, a vendor-agnostic Gemini adapter, and a free one-click cloud deployment (Render, Neon/pgvector) with smoke tests, dependency audits and secret-free release signing.

## Project complete

All eight phases are done. Next ideas: an iOS app on the same KMP core, OpenTelemetry tracing, a Redis rate limiter, answer-quality evals in CI, and a real HRIS integration through signed webhooks.
