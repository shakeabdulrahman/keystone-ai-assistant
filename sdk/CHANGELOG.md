# Changelog

All notable changes are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org) (0.x: the API may change in minor versions).

## [Unreleased]

### Added
- `llm-core`: `LlmClient` with `chat`, `stream` (cold `Flow<StreamEvent>`) and `chatStructured`;
  typed messages, tools, tool calls with validated arguments, `ResponseFormat`; declared
  `ProviderCapabilities`; `RetryPolicy`, `FailoverPolicy`, `StreamRestartPolicy`, `Timeouts`,
  `LlmListener`; `Usage` with reported/estimated/unknown source; exact `Money`, `PriceTable`,
  `CostReport`; incremental byte-level SSE decoder; HTTP error classification with
  `Retry-After`; JSON-schema generator and subset validator.
- `llm-ktor`: `KtorHttpTransport`.
- `llm-openai`: `OpenAiCompatibleProvider` for OpenAI-compatible Chat Completions APIs.
- `llm-gemini`: `GeminiProvider` for `streamGenerateContent`.
- `llm-ondevice`: `OnDeviceEngine` boundary and `OnDeviceProvider` adapter.
- JVM sample (`samples/jvm-cli`) reading keys from environment variables.

### Verification status
- 86 unit tests pass in a JVM-only smoke harness (Kotlin 2.0.21 compiler, JUnit 4, with
  stand-ins for `kotlin-test` and `runTest`). Tests needing the serialization compiler plugin
  were not run there.
- Not yet verified: a Gradle build, the Android and iOS targets, `llm-ktor`, and calls to real
  provider endpoints.
