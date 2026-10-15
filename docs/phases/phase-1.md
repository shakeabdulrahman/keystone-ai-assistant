# Phase 1 — Streaming chat

**Outcome:** you type a question in the app and the answer appears word by word from a local AI model (Ollama, Qwen 2.5 7B), through our own gateway. Stop, retry, errors, keep-alives and follow-up questions all work. The model provider is swappable by configuration, and the whole flow is testable without any AI at all.

## 1. What we built

| Area | What exists now | Key files |
| --- | --- | --- |
| Provider abstraction | `LLMProvider` interface with provider-neutral types and errors | `backend/app/ai/llm/base.py`, `types.py` |
| Providers | `FakeLLMProvider` (scripted, free, instant) and `OllamaProvider` (local model over HTTP) chosen by `LLM_PROVIDER` | `fake.py`, `ollama.py`, `factory.py` |
| Event contract | Typed stream events, SSE encoding with sequence numbers, written spec and shared fixtures | `backend/app/ai/events.py`, `contracts/` |
| Chat endpoint | `POST /v1/chat` streams `message.started → text.delta* → message.completed / error`, with keep-alives | `backend/app/api/chat.py`, `streaming.py` |
| Chat service | Validation, idempotency, conversation context window, metrics logging, partial-answer saving on disconnect | `backend/app/services/chat_service.py` |
| Conversation store | Interface + temporary in-memory implementation (Postgres in Phase 2) | `conversation_store.py` |
| Prompt | Versioned system prompt file for an HR assistant that admits what it can't see yet | `backend/app/ai/prompts/` |
| KMP shared | SSE parser, streaming API call, event mapper, `ChatRepository` returning `Flow<ChatEvent>` | `mobile/shared/.../data/remote/`, `data/repository/ChatRepositoryImpl.kt` |
| Android | Chat screen (bubbles, typing indicator, live text, Stop, Retry, suggestions, empty/error states), type-safe navigation from Home | `mobile/androidApp/.../ui/chat/` |
| Tests | Backend 40 (providers, API, streaming, cancellation, contract). Mobile: SSE parser, repository, contract fixtures, ChatViewModel | `backend/tests/`, `mobile/**/test` |

## 2. How it works

```
ChatScreen ── onSend ──▶ ChatViewModel ── collect ──▶ ChatRepository.sendMessage(): Flow<ChatEvent>
                              ▲                                   │ Ktor: POST /v1/chat, read body line by line
                              │ StateFlow<ChatUiState>            ▼
                              │                          SseEventParser → ChatStreamMapper
                              │
FastAPI /v1/chat ─ start_turn (404/409/422 before streaming) ─ StreamingResponse
   └ with_heartbeat( ChatService.stream_turn ) ── LLMProvider.stream ──▶ Ollama /api/chat (NDJSON)
```

1. The app generates a `client_message_id` and posts the question. Without a `conversation_id`, the server starts a new conversation.
2. `start_turn` runs **before** the response starts, so a bad request gets a proper HTTP error (404 unknown conversation, 409 duplicate message, 422 invalid input).
3. `stream_turn` sends `message.started`, then forwards each model fragment as `text.delta`, then `message.completed` with token usage. A provider failure mid-answer becomes an `error` event with a stable code and a user-safe message.
4. `with_heartbeat` sends an SSE comment every 15 s of silence (while the model loads), so proxies and mobile networks don't drop the connection.
5. On the phone, the parser turns lines into events, the mapper turns events into domain types (**unknown event types are skipped**, so the server can add new ones later), and the repository guarantees exactly one final event even if the network cuts out.
6. The ViewModel buffers text and updates the UI at most every 40 ms. Stop cancels the collecting coroutine; that closes the HTTP connection; the server sees the disconnect, saves the partial answer as `interrupted`, and closes the model connection, so the model stops generating.

## 3. Why we chose it

| Decision | Why | Alternative rejected |
| --- | --- | --- |
| Own `LLMProvider` interface | Switching vendors is a config change; business logic never imports a vendor SDK; a fake makes tests free | LangChain abstractions; calling Ollama directly from the service |
| SSE for chat | One request, one-way stream: plain HTTP, works with auth headers, proxies and stateless servers | WebSocket (saved for realtime voice) |
| Typed events with `seq` | One stream carries text now and tool cards, citations and confirmations later; `seq` helps debugging | Streaming raw text |
| Validate before streaming | Bad requests get correct HTTP status codes instead of a 200 that contains an error | Everything inside the stream |
| Idempotent `client_message_id` | A retried request can't create a duplicate message | Server-generated IDs only |
| Failed exchanges excluded from context | A retry doesn't show the model the same question twice | Sending full raw history |
| Fixtures in `/contracts` used by both test suites | The Python server and Kotlin client can't drift apart silently | Separate hand-written samples |
| Throttled UI updates | Fewer recompositions with fast models, smooth scrolling | Recompose on every token |
| In-memory store behind an interface | Context works now; Postgres drops in next phase without API changes | Building persistence and streaming at once |

## 4. The bug we found (good interview story)

An end-to-end test with a simulated Ollama server showed that when the phone disconnected, **the model kept generating to the end**. Two causes, both invisible to plain unit tests:

1. Python doesn't close nested async generators automatically. Closing the HTTP response didn't close the chat service's stream, which didn't close the provider's stream. Fixed with `contextlib.aclosing` at each level.
2. Starlette cancels a disconnected stream through an **anyio cancel scope**, which cancels *every* `await` during cleanup, so the cleanup itself was cancelled before it closed the connection. Fixed by shielding the cleanup (`anyio.CancelScope(shield=True)`).

A regression test now starts a tiny HTTP server that streams forever, cancels the request exactly the way the server does, and asserts the upstream connection closes within one second. It fails without the fix and passes with it.

Why it matters: with paid APIs, every abandoned answer would keep costing tokens; with local models it wastes CPU/GPU that other users need.

## 5. How to run and test it

1. **Update your `.env`** (it was created in Phase 0 with the fake provider). In `backend/.env`, set:
   ```
   LLM_PROVIDER=ollama
   OLLAMA_MODEL=qwen2.5:7b
   ```
   Make sure Ollama is running (llama icon in the menu bar).
2. **Rebuild and start the backend:** `docker compose up --build`
3. **Watch a stream in Terminal:**
   ```bash
   curl -N -X POST http://localhost:8000/v1/chat \
     -H 'content-type: application/json' \
     -d '{"client_message_id":"'$(uuidgen)'","content":"How do I request annual leave?"}'
   ```
   You see `message.started`, maybe a few `: keep-alive` lines while the model loads, then many `text.delta` events, then `message.completed` with token counts. `-N` stops curl from buffering.
4. **Look at the server logs:** one `chat_turn` line per answer with `ttft_ms` (time to first token), `duration_ms`, token counts and status — never the question or answer text.
5. **Android:** run the app, tap **Ask Keystone**, try a suggestion. Then try: Stop in the middle of an answer; stop Ollama and send (you get a friendly error with Retry); ask a follow-up like "Can you shorten that?" (the model remembers the conversation).
6. **No AI available?** Set `LLM_PROVIDER=fake` to get a scripted streaming reply.
7. **Tests:** `cd backend && uv run pytest` and `cd mobile && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest`.

Note: conversations are kept in memory until Phase 2, so restarting the backend forgets them.

## 6. Interview concepts demonstrated

- LLM streaming end to end; time to first token (TTFT) as the key latency metric
- Provider abstraction (ports and adapters); vendor independence
- SSE protocol design; event versioning and forward compatibility
- Idempotency keys for safe retries
- Context-window management (recent history, excluding failed turns)
- Backpressure and cancellation: client Stop → HTTP close → server cancellation → model stops
- Async Python: generators, `aclosing`, cancel scopes and shielding
- Keep-alives for long-lived HTTP responses
- Contract testing across two languages
- Kotlin Flow: cold flows, `catch` vs try/catch (exception transparency), cancellation
- UI performance: throttling state updates during streaming
- Observability without logging user content

**60-second explanation:**

> "The app posts a message to `/v1/chat` and gets back a Server-Sent Events stream: message started, text deltas, then completed or an error. On the server, the chat service depends on an LLMProvider interface; today the implementation is Ollama running a local model, and in tests it's a scripted fake, so the suite runs without any AI. The event format is written down as a contract with fixtures that both the Python and Kotlin tests replay. On mobile, a KMP repository parses the stream into a cold Flow of domain events, and the ViewModel batches text updates every 40 milliseconds. Stop simply cancels the coroutine: that closes the connection, the server notices, saves the partial answer and closes the model stream. Getting that last part right needed shielded cleanup, because the server's cancel scope was cancelling the cleanup itself — I caught it with an end-to-end test and added a regression test."

## 7. Likely interview questions

1. **Why SSE and not WebSockets for chat?**
   Chat is request-then-stream, one direction. SSE is ordinary HTTP: auth headers, retries, load balancers and HTTP/2 work unchanged, and servers stay stateless. WebSockets are for genuinely two-way traffic like realtime voice.

2. **How do you stop paying for tokens the user will never read?**
   Treat the client disconnect as cancellation and propagate it all the way to the model call: close each async generator explicitly and shield the cleanup from cancellation. Then verify it end to end, because unit tests with fakes didn't catch it.

3. **How do you keep old app versions working as the stream evolves?**
   Clients ignore unknown event types and fields, servers only add, and both sides test against shared fixtures, including one with unknown events.

4. **What is TTFT and how do you improve it?**
   Time to first token: what users perceive as speed. Measure it per request; improve it with smaller or warmer models, shorter prompts, prompt caching, and keeping the model loaded. Stream immediately rather than waiting for the full answer.

5. **How do you handle a dropped connection mid-answer?**
   The server saves the partial answer as interrupted and excludes it from future context. The client guarantees a final event (Failed(Network)) and offers Retry, which sends a new idempotency key.

## 8. Check yourself

1. Which errors come back as HTTP status codes, and which as `error` events? Why the split?
2. What does `with_heartbeat` send, and what problem does it solve?
3. Why does the client ignore unknown event types instead of failing?
4. What happens, step by step, when the user taps Stop?
5. Why isn't a failed question sent back to the model on retry?

## 9. CV / LinkedIn line

> Built end-to-end LLM streaming for a Kotlin Multiplatform app: a FastAPI gateway with a provider-agnostic LLM interface (local Ollama + scripted fake), an SSE event protocol verified by cross-language contract tests, and cancellation that propagates from the app's Stop button to the model, caught and fixed through end-to-end testing.

## Next: Phase 2 — Sign-in and conversation history

Firebase Authentication on the app, token verification on the backend, Postgres tables for users, conversations and messages (replacing the in-memory store), conversation list and history screens, and an offline cache with Room.
