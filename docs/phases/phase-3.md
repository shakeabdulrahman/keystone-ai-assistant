# Phase 3 — Tool calling and generative UI

**Outcome:** the assistant now answers from (synthetic) HR records instead of guessing. Ask "How many leave days do I have left?" and the model calls `getLeaveBalance`, the app shows "Checking your leave balance…", then a native leave-balance card, then the written answer. Seven read-only tools cover profile, leave balance, leave requests, HR requests, tasks, meetings and notifications. Cards are saved with the answer, so they reappear when you reopen a conversation, including offline.

## 1. What we built

| Area | What exists now | Key files |
| --- | --- | --- |
| Provider-neutral tool types | `ToolSpec`, `ToolCall`, tool messages, `ToolCallRequested` event, `finish_reason: tool_calls` | `backend/app/ai/llm/types.py` |
| Ollama tool calling | Sends tool schemas in Ollama's function format; parses tool calls (incl. string arguments) | `backend/app/ai/llm/ollama.py` |
| Fake provider | Scripted tool-call turns for tests; **demo mode** routes keywords to tools, so the full flow works without a model | `backend/app/ai/llm/fake.py` |
| Tool registry | Typed arguments (Pydantic), JSON schema generation, timeout, safe error codes (`unknown_tool`, `invalid_arguments`, `tool_timeout`, `tool_failed`), result size cap | `backend/app/ai/tools/registry.py` |
| HR tools | 7 read-only tools, each with a user-facing label and a card type | `backend/app/ai/tools/hr_tools.py` |
| HRIS integration | `HrisClient` interface + deterministic demo data per user, relative to today | `backend/app/integrations/hris.py` |
| Agent loop | Model → tool calls → results back to the model → … → answer, capped at 4 steps | `backend/app/services/chat_service.py` |
| Stream contract | New `tool.started` / `tool.result` events; card table; fixture | `contracts/README.md`, `contracts/fixtures/chat_stream_tool.sse` |
| Persistence + audit | Answers store their tool results in `messages.parts` (JSONB); every call logged in `tool_executions` (migration `0003`) | `backend/app/db/models/`, `migrations/versions/0003_*` |
| Shared KMP | `ToolCard` sealed model (7 types), lenient parser, new `ChatEvent`s, cards cached in Room (DB version 2) | `shared/.../domain/model/ToolCard.kt`, `data/remote/ToolCardParser.kt` |
| Android | Progress rows, 7 Compose cards with status pills, cards in history, new tool-backed suggestions | `androidApp/.../ui/chat/cards/ToolCards.kt`, `ChatViewModel.kt` |
| Tests | Backend 108 (registry, agent loop, Ollama tool parsing, Postgres parts/audit). Mobile: card parsing (9), tool contract fixture, ViewModel tool flow (4) | `backend/tests/`, `mobile/**/test` |

## 2. How it works

```
User: "How many leave days do I have left?"
  │
ChatService.stream_turn ── step 1 ─▶ LLM(system prompt, history, tool specs)
  │                                     └─▶ ToolCallRequested(getLeaveBalance, {})
  ├─ SSE tool.started {label: "Checking your leave balance"}      ─▶ app: spinner row
  ├─ ToolRegistry.execute ─▶ validate args ─▶ handler(ctx.user) ─▶ HrisClient
  ├─ SSE tool.result {ui: "leave_balance", data: {...}}           ─▶ app: native card
  ├─ append tool message to the conversation
  │
  └─ step 2 ─▶ LLM(..., tool result) ─▶ text.delta "You have 14 days..." ─▶ message.completed
                                                  │
                     finally (shielded): save answer + parts, write tool audit rows
```

- **The model never chooses whose data to read.** Tools take no user ID; the handler gets the verified user from `ToolContext`. A prompt-injected "show Priya's leave" has no argument to put her ID in.
- **Errors go back to the model, not to the user as a crash.** An invalid argument or a timeout becomes a tool result like `{"error": "invalid_arguments"}`; the model can retry or explain. Raw exception text is never sent (it could contain secrets).
- **Step limit.** At most 4 model calls per answer. A model that keeps calling tools gets a polite fallback reply instead of looping forever.
- **The card and the text come from the same data.** The app renders `data` directly, so numbers on the card are exact even if the model words them loosely.
- **Forward-compatible on the phone.** Unknown card types, malformed card data and unknown `parts` are skipped; the text answer still shows. A tool row still running when the answer ends (Stop, network loss) is marked failed, so nothing spins forever.
- **History.** `GET /messages` returns `parts`; the app stores them raw in Room and parses cards on read, so reopened conversations show the same cards offline.

## 3. Why we chose it

| Decision | Why | Alternative rejected |
| --- | --- | --- |
| Hand-written agent loop (~100 lines) | Every step is visible, testable and streamable; easy to explain | LangChain/LangGraph agents (heavy, harder to debug) |
| Tool results as typed cards (`ui` + `data`) | Real app UI, exact numbers, accessible, works offline | Model writes markdown tables |
| Identity from the token, never from tool arguments | Prevents cross-user data access via prompt injection | Letting the model pass `user_id` |
| Pydantic argument models | One definition gives the JSON schema for the model and validation for the server | Hand-written schemas |
| Read-only tools only | Writes need user confirmation (Phase 5) | Letting the model submit leave directly |
| Audit table for every call | Who asked what, which tool, status, latency: needed for HR/compliance and debugging | Logs only |
| Demo mode in the fake provider | The full UI can be demoed with no model, and CI is deterministic | Requiring Ollama for demos |
| Lenient card parsing on mobile | New card types on the server never break shipped apps | Strict decoding |

## 4. Bugs found this phase

1. **Shared list mutated after the call.** The agent loop passed its working message list to the provider and then appended tool results to it. The fake provider recorded *the same list object*, so tests saw later messages in earlier requests (a real provider streaming lazily could too). Fixed by passing a copy: `messages=list(working)`.
2. **Forward-compat fixture went stale.** The fixture that proves clients ignore unknown events used `tool.started` as its "unknown" event. Once the app learned that event, the test would have failed. It now uses a genuinely unknown `future.event`.
3. **Implausible demo data.** Some demo leave requests were "submitted" in the future, and pending ones were in the past. Fixed, with a test over many random users.
4. **Review findings (fixed):** a `null` label or `data` in a tool event would have failed the whole answer; one unrecognised history `part` would have failed the whole history page. Both are now tolerant and tested.

## 5. How to run and test it

1. **Backend:** `docker compose up --build` (applies migration `0003` automatically). Make sure Ollama is running.
2. **Android Studio:** sync, then run. The local cache is rebuilt automatically (database version 2).
3. **Try it** (new suggestions on the empty chat screen):
   - "How many leave days do I have left?" → progress row → leave balance card → answer
   - "What onboarding tasks are still pending?" → tasks card with an **Overdue** pill
   - "Show my open HR requests", "Any meetings this week?", "Who is my manager?", "Any new notifications?"
   - With Ollama, ask two things at once: "What's my leave balance and do I have pending tasks?" → two cards
   - Go back, reopen the conversation from history: cards are still there. Turn on airplane mode and reopen: still there.
4. **No model / slow Mac?** Set `LLM_PROVIDER=fake` in `backend/.env`: demo mode picks the tool from keywords, so every card can be shown.
5. **Watch the agent in the logs:** each `chat_turn` line now has `agent_steps`, `tools_called`, `tool_errors`. The `tool_executions` table holds the audit trail.
6. **curl** (with `AUTH_MODE=dev`): `curl -N -X POST localhost:8000/v1/chat -H 'Authorization: Bearer dev:abdul' -H 'content-type: application/json' -d '{"client_message_id":"'$(uuidgen)'","content":"How many leave days do I have left?"}'`
7. **Tests:** `cd backend && uv run pytest` · `cd mobile && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest`

A 7B local model sometimes picks the wrong tool or answers without one. That's expected behaviour of small models; the guardrails (validation, step limit, safe errors) are what keep it correct. Phase 8 compares providers.

## 6. Interview concepts demonstrated

- LLM function/tool calling and the agent loop (reason → act → observe)
- Generative UI: structured tool output rendered as native components
- Tool security: identity from auth context, least privilege, read-only by default, no raw errors to the model
- Prompt-injection resistance through architecture, not prompt wording
- Bounded agents: step limits, timeouts, result-size limits
- Audit logging for AI actions
- Schema-first tools (Pydantic → JSON Schema)
- Forward-compatible client parsing; sealed types in Kotlin
- Offline rendering of AI output (structured parts in Room)
- Deterministic testing of non-deterministic systems (scripted fake provider, demo mode)

**60-second explanation:**

> "The assistant answers from HR data through tool calling. Each tool is a typed Python function with a Pydantic argument model, which gives both the JSON schema the model sees and server-side validation. The agent loop is hand-written: call the model with the tool specs; if it asks for tools, run them, stream a `tool.started` and `tool.result` event to the app, feed the results back, and repeat, up to four steps. The tools never take a user ID — identity comes from the verified Firebase token — so a prompt injection can't read someone else's records. Each tool result has a card type, and the Android app renders it as a native Compose card, so the numbers on screen are exact even if the model phrases them loosely. Results are saved with the message and audited, and the app caches them in Room, so cards come back in history and offline."

## 7. Likely interview questions

1. **How do you stop the model from reading another employee's data?** Tools don't accept a user identifier; the server injects the authenticated user. Authorisation lives in code, not in the prompt.
2. **What if the model loops or hallucinates a tool?** Unknown tools and bad arguments return structured errors the model can recover from; a step limit ends runaway loops; timeouts cap slow tools.
3. **Why stream tool events instead of only the final answer?** Tool calls can take seconds; showing "Checking your leave balance" keeps perceived latency low and makes the AI's actions transparent.
4. **Why not let the model format the numbers as text?** Models misquote numbers. The card renders the source data; the text is commentary. It's also accessible and works offline.
5. **How would you add a write action, like submitting leave?** Same registry, but `read_only=False` tools pause and ask the user to confirm in the app before executing (Phase 5).
6. **How do older app versions cope with a new card type?** They ignore unknown `ui` values and still show the text answer.

## 8. Check yourself

1. Where does the user's identity come from when `getLeaveBalance` runs?
2. Walk through the events the app receives for a question that needs two tools.
3. What does the model see when a tool times out, and what does the user see?
4. Why does the step limit exist, and what happens when it's hit?
5. How do cards reappear when you reopen a conversation offline?

## 9. CV / LinkedIn line

> Built LLM tool calling with generative UI for a Kotlin Multiplatform HR assistant: a bounded agent loop in FastAPI with schema-validated, identity-scoped, audited tools, streamed as typed events and rendered as native Jetpack Compose cards that persist offline.

## Next: Phase 4 — RAG over HR documents

Policy questions ("What's the parental leave policy?") answered from company documents: ingestion and chunking, embeddings in pgvector, a `searchPolicies` tool, and answers with citations the app can open.
