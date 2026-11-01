# Streaming

```kotlin
llm.stream(request).collect { event -> … }
```

## Event order

```
Started
(TextDelta | ToolCallStarted | ToolCallDelta)*
ToolCallCompleted*        // validated calls, once the answer has ended
Completed(response)       // the same ChatResponse chat() would return
```

A failure ends the flow with `LlmException`. With `StreamRestartPolicy.EmitRestarted`, a
`Restarted(failed, next)` may appear after output, followed by a fresh `Started`.

- `Started` is emitted just before the first visible event. Retries and failover that happen
  before it are invisible to you (but listed in `response.metadata.attempts`).
- `ToolCallDelta.argumentsDelta` is a fragment: concatenate per `index`. Use
  `ToolCallCompleted` for the validated result.

## What counts as visible output

Text and tool-call events. Metadata, usage and the finish reason don't count, so a provider
that fails after sending only headers can still be retried or failed over transparently.

## Cancellation

The flow is cold and cooperative. Cancelling the collecting coroutine, or using `take(n)` or
`first()`, cancels the provider's flow. The HTTP response is released and the connection
closed. A cancelled request is never retried.

If *your* collector throws, the exception propagates unchanged and is not treated as a
provider failure.

## Timeouts

`Timeouts(firstEvent = 60s, idle = 60s, total = null)` per attempt:

- `firstEvent`: time until the provider's first event (connection plus time to first token);
- `idle`: the longest gap between events;
- `total`: off by default, because long answers legitimately take long.

A timeout is `LlmErrorKind.Timeout` (transient). Before any output it is retried. After
output it ends the stream.

## Decoding guarantees (SSE)

The decoder works on raw bytes. The result doesn't depend on how the network chunked them,
and the tests check every chunk size for:

- UTF-8 sequences split between chunks;
- `\n`, `\r\n` and `\r` line endings, including `\r\n` split in two;
- multiple events per chunk and one event across many chunks;
- comments/keep-alives, a leading BOM, `id`, unknown fields;
- truncated UTF-8 at end of stream (becomes U+FFFD) and a bounded line length.

A final event without its closing blank line is still delivered. The spec says to drop it,
but some servers end that way. Each provider decides whether the stream ended properly:
OpenAI-compatible streams need `[DONE]` or a `finish_reason`, and Gemini streams need a
`finishReason`. A stream that ends otherwise is a `Network` failure ("cut off").
