# Phase 0 — Foundations

**Outcome:** a monorepo with a running, tested FastAPI backend on Postgres + pgvector, an Android app built on a Kotlin Multiplatform shared core and a design system, and CI for both. No AI yet, on purpose: every later phase adds features to this base instead of fixing plumbing.

## 1. What we built

| Area | What exists now | Key files |
| --- | --- | --- |
| Repo | Monorepo layout, README, 9 Architecture Decision Records, `.gitignore` that blocks secrets | `README.md`, `docs/adr/` |
| Backend | FastAPI app factory, typed settings from env vars, JSON logs with request IDs, RFC 9457 errors, `/healthz`, `/readyz`, `/v1/meta` | `backend/app/` |
| Database | Async SQLAlchemy engine, Alembic migrations, first migration enables pgvector | `backend/app/db/`, `backend/migrations/` |
| Backend tests | 15 tests: health, readiness, errors, request IDs, config secrecy, real-DB integration | `backend/tests/` |
| Local stack | Postgres 16 + pgvector and the API in Docker Compose; non-root Docker image | `docker-compose.yml`, `backend/Dockerfile` |
| KMP shared | Ktor client factory (`expect`/`actual` engines), typed API, `AppResult`/`AppError`, first repository, 4 common tests | `mobile/shared/` |
| Design system | Keystone light/dark colour schemes, type scale, shapes, `StatusPill`, `SkeletonBlock` | `mobile/core/designsystem/` |
| Android app | Hilt, Compose, Home screen that shows backend status with loading, success and error states; 3 ViewModel tests | `mobile/androidApp/` |
| CI | Backend: lint, types, migrations, tests against real Postgres, Docker build. Android: unit tests, lint, debug APK. Secret scanning | `.github/workflows/` |

## 2. How it works

```
Android HomeScreen ──observes── HomeViewModel (StateFlow<HomeUiState>)
                                     │ calls
                                     ▼
                     SystemRepository (interface, KMP domain)
                                     │ implemented by
                                     ▼
              SystemRepositoryImpl → KeystoneApi → Ktor HttpClient (OkHttp / Darwin)
                                     │ GET /v1/meta
                                     ▼
 FastAPI: RequestContextMiddleware (request ID, access log, crash guard)
          → router → handler → JSON (or problem+json on error)
```

- **Request IDs.** Every response carries `X-Request-ID`. A client may send one; the server accepts it only if it matches a strict pattern (stops log injection). The same ID appears in every log line and error body, so a user's bug report can be traced to exact server logs.
- **Errors.** Expected errors become `application/problem+json` with a stable `code`. Unexpected exceptions are caught in the middleware, logged with their type, and returned as a generic 500, so no stack traces or secrets reach the client. Validation errors say *where* and *why*, never echo the submitted values.
- **Liveness vs readiness.** `/healthz` only says "the process is alive" and touches nothing. `/readyz` checks Postgres and pgvector and returns 503 if they are not ready. Orchestrators restart on failed liveness but only stop routing traffic on failed readiness.
- **Mobile error flow.** `safeApiCall` converts every failure (network, HTTP status, bad JSON) into a typed `AppError`, rethrowing coroutine cancellation. The ViewModel turns `AppResult` into UI state; the screen maps `AppError` to user-friendly strings.

## 3. Why we chose it

| Decision | Why | Alternative rejected |
| --- | --- | --- |
| Pure ASGI middleware | Never buffers response bodies, which matters once chat streams in Phase 1 | Starlette `BaseHTTPMiddleware` (known streaming pitfalls) |
| `SecretStr` for the DB URL | Secrets can't leak through logs, reprs or error pages | Plain strings |
| Dependency overrides in tests | Unit tests run in 0.1 s with no database; one integration test proves the real thing | Patching globals / always needing Postgres |
| Alembic from day one | Schema changes are versioned, reviewable and reversible | `create_all()` at startup |
| KMP shared module without a DI framework | Shared code stays plain Kotlin; Hilt wires it on Android, iOS can wire it its own way | Koin in shared code |
| `AppResult` instead of exceptions | Callers must handle failure; `when` is checked for exhaustiveness at compile time | Throwing exceptions up to the UI |
| Stateless `HomeScreen` + stateful `HomeRoute` | Previews and UI tests need no ViewModel or Hilt | One composable that does everything |
| Brand colours, not dynamic colour | Consistent product look on every device | Material You dynamic colour |
| Cleartext only in debug, only to `10.0.2.2`/`localhost` | Release builds are HTTPS-only by configuration, not by convention | App-wide `usesCleartextTraffic=true` |
| `allowBackup=false` | HR data and tokens must not end up in device backups | Default backups |

## 4. How to run and test it

```bash
# Backend + database (Docker Desktop running)
cp backend/.env.example backend/.env
docker compose up --build
curl -i http://localhost:8000/healthz    # 200 {"status":"ok"} + x-request-id header
curl http://localhost:8000/readyz        # {"status":"ready","checks":{"database":"ok","pgvector":"0.8.x"}}
curl http://localhost:8000/nope          # 404 problem+json with request_id
open http://localhost:8000/docs          # interactive API docs

# Backend tests without Docker
cd backend && uv sync && uv run pytest
```

Android:

1. Open the `mobile/` folder in Android Studio (latest stable). Let Gradle sync finish; it downloads the SDK and dependencies.
2. Run the `androidApp` configuration on an emulator. With the backend running you see **Backend · Connected · keystone v0.1.0 · local**.
3. Stop the backend and tap **Retry**: the card shows **Unreachable** with a friendly message.
4. Tests: `./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest`.
5. Real phone instead of emulator: run `adb reverse tcp:8000 tcp:8000` and build with `-Pkeystone.apiBaseUrl=http://localhost:8000`.

Verified in this phase: all backend tests, ruff, ruff format and strict mypy pass on Python 3.12 and 3.13; migrations upgrade, downgrade and re-apply against a real Postgres 16 with pgvector; `/readyz` reports pgvector. The Android code was reviewed statically for API and visibility errors; its first compile happens in Android Studio.

## 5. Interview concepts demonstrated

- Monorepo with clear mobile/backend contract boundaries
- 12-factor configuration and secret handling
- Structured logging and request correlation (observability foundations)
- Standard error contracts (RFC 9457) and stable error codes
- Liveness vs readiness probes
- Database migrations and reversible schema changes
- Kotlin Multiplatform `expect`/`actual`, shared domain and data layers
- Clean Architecture dependency rule; repository pattern
- Unidirectional data flow: immutable UI state in a `StateFlow`
- Testability through fakes and dependency injection
- Network security config, no cleartext in release, backups disabled
- CI with service containers and integration tests

**60-second explanation you can say out loud:**

> "Before writing any AI code I built the foundation. The backend is a FastAPI service with typed configuration from environment variables, structured JSON logs where every request carries a correlation ID, and a standard problem-JSON error format, so clients get stable error codes and never see internals. It has separate liveness and readiness probes; readiness checks Postgres and the pgvector extension we'll need for RAG. On mobile, a Kotlin Multiplatform module holds the domain models, the Ktor networking and the repositories, so iOS can reuse them. Errors become a typed AppError instead of exceptions, the ViewModel exposes immutable state through StateFlow, and the Compose screen is stateless so it's easy to preview and test. CI runs lint, type checks and tests on both sides, including integration tests against a real Postgres."

## 6. Likely interview questions

1. **Why separate liveness and readiness probes?**
   Liveness answers "should this container be restarted?"; readiness answers "should it receive traffic?". If the database is down, restarting the API won't help, so only readiness fails and the load balancer stops sending requests until the database recovers.

2. **How do you correlate a user's error report with server logs?**
   Every response and every log line carries the same request ID. The app can show or report that ID; we search logs for it and see the whole request's path. Client-supplied IDs are validated to prevent log injection.

3. **Why does `safeApiCall` rethrow `CancellationException`?**
   Coroutine cancellation is signalled by that exception. Swallowing it would turn a cancelled request (user left the screen) into a fake "network error" and break structured concurrency.

4. **What does KMP share here, and what doesn't it share?**
   Domain models, the API client, error mapping and repositories are shared. UI, DI wiring and platform engines are not: OkHttp on Android and NSURLSession on iOS come through `expect`/`actual`. Sharing logic, not UI, keeps each platform native.

5. **How do you stop secrets leaking?**
   No secrets in the app or repo; config from env vars; `SecretStr` on the server; `.gitignore` for `.env` and keystores; gitleaks in CI; error responses never include exception text; logs record metadata, never message bodies.

## 7. Check yourself

Answer these before Phase 1 (answers are in sections 2 and 3):

1. Which endpoint should a load balancer use to decide whether to send traffic, and why not the other one?
2. What happens to a request whose `X-Request-ID` header contains a newline?
3. Why is the middleware written as pure ASGI rather than with `BaseHTTPMiddleware`?
4. Where does `AppError` get turned into text the user sees, and why there?
5. Why can a release build of the app not call `http://` URLs, even by mistake?

## 8. CV / LinkedIn line

> Architected the foundation of an AI HR assistant: a FastAPI gateway (typed config, structured logging with request correlation, RFC 9457 errors, readiness probes, Alembic + pgvector) and a Kotlin Multiplatform Android app (Ktor, Hilt, Compose, Clean Architecture), with CI running integration tests against Postgres.

## Next: Phase 1 — Streaming chat

Define the streaming event contract, build `LLMProvider` with a Fake implementation, stream `POST /v1/chat` over SSE, parse it with Ktor in the shared module, and render tokens live in a Compose chat screen. Then plug in Ollama.
