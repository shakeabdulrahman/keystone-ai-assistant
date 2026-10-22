# Interview cheat sheet

One page to reread before an interview. Each phase's file in `docs/phases/` has the long version.

## The 30-second pitch

> "Keystone is an AI HR assistant I built end to end: a Kotlin Multiplatform Android app and a FastAPI gateway that owns all the AI. It streams answers over SSE, calls tools for live HR data and renders native cards, answers policy questions with RAG and verified citations, and can book leave, but only after the user confirms on a card, through an idempotent endpoint. It has on-device voice, a server-driven home screen, privacy-safe push, rate limits, App Check and metrics, and it's deployed for free."

## Numbers to remember

- 12 tools (8 read, 4 write), 7 policy documents, 16 ADRs, 8 phases
- 223 backend tests and 134 mobile tests; strict mypy; `alembic check` in CI
- Chat limit 12/min (burst 5); actions 20/min; user 120/min; IP 300/min; 64 KB body limit
- Actions expire after 15 minutes; tool timeout 5 s; at most 4 agent steps per message
- 768-dimension embeddings; RRF hybrid retrieval

## Design questions and answers

| Question | Answer in one breath |
| --- | --- |
| Why a backend gateway instead of calling the LLM from the app? | Keys can't be secured in an APK. The server enforces auth, quotas, data scoping and prompts, and the vendor can change without an app release. |
| Why SSE, not WebSockets? | One-way token streaming over plain HTTP: works with proxies and auth headers, is simple to resume and test. WebSockets would add state for no benefit. |
| How does tool calling work? | The model gets JSON-schema tool specs. When it returns a call, the server validates the arguments with Pydantic, runs the tool with a timeout and the user's identity, streams a card event, and feeds the result back. Up to 4 steps. |
| Why no LangChain? | The loop is short code I fully control and test: streaming events, timeouts, audit. Frameworks hide exactly what interviews ask about. |
| How do you stop hallucinated citations? | Retrieval is a tool; the model may cite only passage IDs it received; the server drops unknown IDs before sending `citations`. |
| Hybrid search? | Vector similarity catches meaning and full-text search catches exact terms ("LR-1050", "carry over"). Reciprocal Rank Fusion merges the rankings without tuning score scales. |
| How do you keep an AI from changing data wrongly? | It can't execute. A write tool only stores a validated proposal with frozen arguments. The user's tap calls confirm, an atomic `pending → executing` update, which re-validates and executes once. |
| Idempotency? | The client message ID makes chat sends idempotent; the action ID makes confirms idempotent. Retries return the stored result. |
| Offline-first? | Room is the source of truth for the UI; the network refreshes it. Whole-screen data (home) is cached as validated JSON. |
| KMP: what's shared? | Domain models, the Ktor API client, the SSE parser, repositories, Room, the voice sentence chunker. Not shared: UI, platform services (speech, Firebase). |
| Rate limiting design? | Token buckets in layers (IP before auth, then user, chat, actions); 429 with Retry-After; bounded memory; behind an interface so Redis can replace it. |
| What is App Check? | Attestation that the request comes from our genuine app on a real device (Play Integrity). It complements sign-in, which proves the user, not the client. Rolled out monitor → enforce. |
| What do you measure? | Time to first output, turn duration, agent steps, tokens, tool calls and latency per tool, RAG source kinds, action outcomes, rate-limit hits. All low cardinality, no user content. |
| Push privacy? | Payloads pass through Google and show on lock screens, so they're generic ("your leave request has an update"); details load behind sign-in. |
| Biggest bug you hit? | A schema cleaner removed a *property* named `title`, so the model never knew a required field existed. Caught by a test that every required field is described. |
| What would you do with more time? | iOS on the same core, OpenTelemetry traces, Redis limits, a real HRIS through signed webhooks, an eval set for answer quality in CI. |

## Trade-offs to volunteer (shows judgement)

- Free Gemini may use prompts for training, so the cloud demo uses only fictional data.
- In-memory rate limits are per instance. Fine for one free instance, but the next step is Redis.
- On-device speech varies by device and language, so there's a fallback to the standard recognizer.
- Generic pushes are less informative, which is the right trade for HR data.

## Android-specific talking points

Compose state hoisting and `StateFlow`; `callbackFlow` with `awaitClose` for the speech recognizer; Android 11 package visibility (`<queries>`); the Android 13 notification permission; service lifecycle (FCM `onNewToken` work completing before return); R8 release builds; signing secrets outside git; Hilt modules for platform services behind interfaces; virtual-time tests with Turbine.
