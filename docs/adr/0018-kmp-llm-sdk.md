# ADR 0018: Extract a Kotlin Multiplatform LLM SDK (`sdk/`)

- Status: Accepted
- Date: 2026-10-29

## Context
Keystone's provider-agnostic LLM layer (ADR 0007, 0017) lives in the Python backend. Its
ideas carry over to an on-device Kotlin client: one OpenAI-compatible adapter for many
vendors, a typed error taxonomy, and failover only before the first output. The Kotlin app
has a tested SSE parser, but it reads lines through Ktor rather than raw byte chunks.

## Decision
- A **standalone Gradle build in `sdk/`**, not modules inside `mobile/`. The library must not
  inherit the app's Hilt, Room or Firebase plugins, and it must be publishable by itself. It
  reads `mobile/gradle/libs.versions.toml`, so the app and the SDK stay on one Kotlin/Ktor
  version set. The app can dogfood it later with `includeBuild("../sdk")`.
- **Modules:** `llm-core` (public API, engine, SSE decoder, cost; no Ktor), `llm-ktor` (the
  default `HttpTransport`), `llm-openai`, `llm-gemini`, `llm-ondevice` (interfaces only).
  Ktor types appear only in `llm-ktor`'s optional `KtorHttpTransport(HttpClient)` constructor.
- **Targets:** Android, JVM (fast tests; backend use), iosArm64, iosSimulatorArm64.
- **Behaviour carried over from Keystone, made explicit and configurable:** no failover or
  retry after a stream has shown output (unless the caller opts into `Restarted` events);
  errors classified once, with `Retry-After` honoured; keys never logged.
- **Fixed rather than copied:** byte-level SSE decoding (UTF-8 and CRLF split across chunks),
  `Retry-After` HTTP-date support, malformed tool arguments reported as typed errors instead
  of `{"_raw": ...}`.
- `explicitApi()` is on. A binary-compatibility validator is not configured yet (its version
  could not be verified offline); until it is, there is no binary-stability promise.

## Consequences
- Two Gradle builds in one repo; CI builds both.
- Calling vendors directly from a phone puts a key on the device. The SDK supports custom
  base URLs and per-request auth headers, so apps can route through their own backend
  (which is what Keystone does). The README says so plainly.

## Alternatives considered
- Modules inside `mobile/`: less setup, but couples the library to the app's plugins.
- A separate repository: cleanest for publishing, but loses the shared history and ADRs.
  Easy to split out later because `sdk/` is self-contained.
