# Contributing

## Build and test

Requires JDK 17+. iOS targets require macOS with Xcode.

```bash
cd sdk
./gradlew jvmTest                 # fast: all common tests on the JVM
./gradlew allTests                # every target available on this machine
./gradlew compileKotlinIosArm64   # iOS compile check (macOS)
./gradlew :samples:jvm-cli:jvmRun --args="Hello"   # needs keys in env vars, see the sample
```

## Rules

- **No real keys or network in tests.** Providers take an `HttpTransport`; tests use a fake
  that serves fixtures in small byte chunks. Any string that looks like a key in a test must
  say it isn't one (`test-key-not-real`).
- **Never log or put in exception messages**: prompts, model output, keys, auth headers.
- **Public API is deliberate.** `explicitApi()` is on. Anything a user can call needs KDoc
  saying what it guarantees. Cross-module internals get `@InternalKeystoneLlmApi`.
- **No new dependencies** without a reason in the PR; versions come from
  `mobile/gradle/libs.versions.toml`.
- **Behaviour comes with tests.** A streaming change needs a chunk-boundary test. A
  retry/failover change needs a deterministic test (inject the sleeper; no real waits).
- **Provider fixtures** must say where they came from (documented format or captured
  traffic) and must not contain real keys, prompts or personal data.
- Follow the repo's `.editorconfig` (4 spaces, 120 columns) and Kotlin official style.
  Test names are camelCase (Kotlin/Native-safe).
- Add a line to `CHANGELOG.md` under **Unreleased**.

## Adding a provider

Implement `LlmProvider` (see [docs/providers.md](docs/providers.md#custom-providers)): map
the wire format to `ProviderEvent`s, classify errors as `LlmException`, declare capabilities
honestly, and leave retries and failover to the client.
