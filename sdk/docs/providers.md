# Providers and capabilities

A provider is registered under an id. Requests name `ModelRef(providerId, model)`.

## OpenAI-compatible

```kotlin
OpenAiCompatibleProvider(
    id = "groq",
    baseUrl = "https://api.groq.com/openai/v1",       // anything serving POST {baseUrl}/chat/completions
    auth = Auth.ApiKey(key),                          // sent as Authorization: Bearer
    capabilities = { OpenAiCompatibleProvider.GenericCapabilities },
    options = OpenAiCompatibleOptions(
        maxTokensField = "max_tokens",                // or "max_completion_tokens"
        includeStreamUsage = true,                    // stream_options.include_usage
        extraHeaders = mapOf(),                       // e.g. OpenRouter attribution headers
        requestIdHeader = "x-request-id",
    ),
)
```

Capabilities are **declared**, because compatible servers differ:

| Preset | Tools | Forced tool choice | Structured output |
|---|---|---|---|
| `GenericCapabilities` (default) | native | native | JSON mode |
| `OpenAiCapabilities` | native | native | JSON schema |
| `TextOnlyCapabilities` | unsupported | unsupported | none |

Capabilities can depend on the model: `capabilities = { model -> if (...) OpenAiCapabilities else GenericCapabilities }`.
Check your server's documentation before claiming more than the default.

What's mapped: messages (system, user, assistant with tool calls, tool results), tools,
`tool_choice`, `response_format`, max tokens, temperature, `top_p`, stop sequences. In the
stream: `delta.content`, `delta.tool_calls` (by `index`), `finish_reason`, `usage` and
`x_groq.usage`, the `[DONE]` marker, and mid-stream `error` objects. Only the first choice is
read. `reasoning`/`reasoning_content` deltas are ignored.

## Gemini

```kotlin
GeminiProvider(auth = Auth.ApiKey(key))   // id = "gemini", baseUrl = .../v1beta
```

- The key goes in the `x-goog-api-key` header, never in the URL.
- System messages become `systemInstruction`. Tool results become `functionResponse` parts,
  and consecutive ones are merged into one turn.
- Schemas are converted to Gemini's OpenAPI subset: `anyOf [X, null]` and `type: [X, "null"]`
  become `nullable`, and unsupported keywords (`additionalProperties`, `format`, `default`, …)
  are dropped. Gemini therefore enforces less than your full schema; the SDK validates the
  result client-side regardless.
- `thoughtSignature` on function calls is kept in `ToolCall.providerMetadata` and sent back.
  Pass the assistant message back unchanged.
- Thinking parts (`thought: true`) are not shown. Thinking tokens are counted as output tokens.
- A blocked prompt (`promptFeedback.blockReason`) is `LlmErrorKind.ContentFiltered`.

## On-device

`llm-ondevice` defines the boundary only:

```kotlin
class MyEngine : OnDeviceEngine {
    override suspend fun availability(model: String) = OnDeviceAvailability.Available
    override fun generate(model: String, prompt: OnDevicePrompt): Flow<OnDeviceOutput> = flow { … }
}
LlmClient {
    provider(OnDeviceProvider(MyEngine()))
    provider(cloudProvider)
    defaultRoute = Route(ModelRef("on-device", "local-model"), ModelRef("groq", cloudModel))
}
```

On-device is text only. A model that isn't ready or isn't supported fails with
`Unsupported`, so the route falls back to the cloud. No engine ships with the SDK yet: runtime
adapters (Android system models, Apple's on-device model, llama.cpp) belong in separate
modules once they can be tested on devices.

## Custom providers

Implement `LlmProvider`:

- `stream()` must be cold and cancellable, emit `ProviderEvent`s, and end with exactly one
  `Finish` (a stream without it is treated as cut off and retried as a network failure);
- throw only `LlmException` with a correct `kind`; don't retry or fail over yourself;
- send tool calls as `ToolCallChunk`s keyed by index (whole calls are one chunk).

The engine validates, assembles, retries and fails over for you.

## Custom HTTP

Every HTTP provider takes `transport: HttpTransport?`. Pass `KtorHttpTransport(yourHttpClient)`
to share your app's client (pinning, proxies), or your own implementation. Tests use a fake
that serves fixtures in small byte chunks. Don't install Ktor plugins that log bodies or
headers: they'd buffer streams and could log keys.
