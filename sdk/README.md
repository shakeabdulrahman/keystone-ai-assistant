# Keystone LLM: a Kotlin Multiplatform LLM client

One Kotlin API for chat models on **Android and iOS** (and the JVM): OpenAI-compatible APIs,
Google Gemini, and on-device models behind an adapter. It covers streaming, tool calling,
structured output, retries with failover across providers, and token usage and cost.

It was extracted from [Keystone](../README.md), an AI assistant whose backend ran on Groq
with failover to Gemini (ADR [0017](../docs/adr/0017-multi-provider-failover.md)). The ideas
that kept that app running through vendor outages are the core of this library. See
[ADR 0018](../docs/adr/0018-kmp-llm-sdk.md) for the design decisions.

> **Status: 0.1.0-SNAPSHOT, unreleased.** The API may change in 0.x minor versions. Read
> [What's not done](#whats-not-done-yet) before depending on it.

## Why

- **One API, many vendors.** Groq, OpenAI, OpenRouter, Mistral, Together, Ollama and your own
  proxy all speak OpenAI's Chat Completions, so adding one is configuration, not code.
- **Streaming that's correct.** Bytes are decoded incrementally (UTF-8 and line endings split
  across network chunks, keep-alives, cut-off streams), and you get typed events as a cold,
  cancellable `Flow`.
- **Failover that doesn't garble answers.** Retries and provider switches happen only before
  anything is shown. After that, a failure is an error, unless you opt into explicit
  `Restarted` events.
- **Honest capabilities.** Providers declare what they support. A request needing tools or a
  JSON schema fails with `Unsupported` (or fails over) instead of being silently ignored.
- **No invented numbers.** Unknown token usage stays unknown, and costs come from *your*
  versioned price table.

## Modules

| Artifact | What | Depends on |
|---|---|---|
| `llm-core` | Public API, engine (retry/failover/timeouts), SSE decoder, tools, structured output, cost | coroutines, kotlinx-serialization-json |
| `llm-ktor` | Default `HttpTransport` (OkHttp on Android/JVM, Darwin on iOS) | Ktor client |
| `llm-openai` | `OpenAiCompatibleProvider` | core, ktor |
| `llm-gemini` | `GeminiProvider` | core, ktor |
| `llm-ondevice` | `OnDeviceEngine` interface + `OnDeviceProvider` adapter (no runtime included) | core |

Targets: `android`, `jvm`, `iosArm64`, `iosSimulatorArm64`. Versions follow
[`mobile/gradle/libs.versions.toml`](../mobile/gradle/libs.versions.toml) (Kotlin 2.1.21, Ktor 3.1.3).

## Setup

Not published yet. To use it from another Gradle build in this repo:

```kotlin
// settings.gradle.kts
includeBuild("../sdk")
// build.gradle.kts
// (a composite build matches project names, so no "keystone-" prefix here)
commonMain.dependencies {
    implementation("io.github.shakeabdulrahman:llm-openai")
    implementation("io.github.shakeabdulrahman:llm-gemini")
}
```

Or run `./gradlew publishToMavenLocal` from `sdk/` and depend on
`io.github.shakeabdulrahman:keystone-llm-openai:0.1.0-SNAPSHOT` from `mavenLocal()`.

## Quick start

```kotlin
val llm = LlmClient {
    provider(OpenAiCompatibleProvider(id = "groq", baseUrl = "https://api.groq.com/openai/v1", auth = Auth.ApiKey(groqKey)))
    provider(GeminiProvider(auth = Auth.ApiKey(geminiKey)))
    defaultRoute = Route(ModelRef("groq", groqModel), ModelRef("gemini", geminiModel)) // primary, then fallback
}

// A complete answer
val answer = llm.chat(ChatRequest(listOf(Message.user("Summarise our leave policy in one line."))))
println(answer.text)
println(answer.usage)               // Usage(inputTokens=…, outputTokens=…, source=Reported), or nulls if unknown
println(answer.metadata.attempts)   // every attempt, including retries and failover

// Streaming
llm.stream(ChatRequest(listOf(Message.user("Tell me a story")))).collect { event ->
    when (event) {
        is StreamEvent.Started -> showProvider(event.target)
        is StreamEvent.TextDelta -> append(event.text)
        is StreamEvent.Completed -> showUsage(event.response.usage)
        else -> Unit
    }
}
```

Model names are always yours to choose: vendors retire models often, so the SDK hardcodes none.

**Keys.** Never put keys in source or committed config. On a phone, any key you ship can be
extracted. Prefer pointing `baseUrl` at your own backend and authenticating with
`Auth.Headers { mapOf("Authorization" to "Bearer ${session.token()}") }`. The sample reads
keys from environment variables.

## Guides

- [Providers and capabilities](docs/providers.md): OpenAI-compatible, Gemini, on-device, custom providers and transports
- [Streaming](docs/streaming.md): event order, cancellation, timeouts, what "visible output" means
- [Tools and structured output](docs/tools-and-structured-output.md)
- [Retries and failover](docs/reliability.md)
- [Usage and cost](docs/usage-and-cost.md)
- [Releasing](docs/releasing.md) · [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md)

## Behaviour guarantees

- `stream()` is cold: each collection is one new request. Cancelling the collector cancels the
  HTTP call and releases the connection.
- In `stream()`, no retry or failover happens after the first `Started` event unless
  `streamRestart = StreamRestartPolicy.EmitRestarted`. Then a `Restarted` event tells you to
  discard what you showed.
- `chat()` buffers, so it may retry or fail over at any point. **Every attempt is a separate,
  billable call.**
- Errors are `LlmException` with a typed `kind`. `CancellationException` is never wrapped.
- Exception messages never contain prompts, output or keys. The provider's own error text is
  kept separately in `providerMessage`.
- The SDK never executes tools.

## What's not done yet

- **Not built with Gradle yet.** The code was written and unit-tested with a JVM-only
  compile-and-test harness, because the environment it was written in had no access to Maven
  Central. The first `./gradlew build` (including the Android and iOS targets and the
  `llm-ktor` module, which the harness couldn't compile) still needs to run. See
  [CHANGELOG](CHANGELOG.md).
- No requests have been made to real provider endpoints yet. Wire fixtures are hand-written
  from documented formats.
- Text only: no images or audio. Single choice (`n = 1`). No embeddings.
- No shipped on-device engine (Android system models, Apple Foundation Models): only the boundary.
- No binary-compatibility validation yet, so no binary-stability promise.
- No license chosen yet, which blocks any public release.
