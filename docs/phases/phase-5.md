# Phase 5 — Actions with confirmation

**Outcome:** the assistant can now *do* things: book leave, withdraw a leave request, raise an HR request, mark a task as done. It never acts on its own. Ask "Book annual leave on the 26th and 27th" and it prepares a card showing exactly what will be submitted (dates, 2 working days, balance 14 → 12), plus a **Submit request** button. Only your tap makes the change. The card then shows "Leave request LR-1050 submitted", and still does when you reopen the conversation. Ask later "did it go through?" and the assistant knows.

## 1. What we built

| Area | What exists now | Key files |
| --- | --- | --- |
| Write tools | `submitLeaveRequest`, `cancelLeaveRequest`, `createHrRequest`, `completeTask`. Each has a *prepare* half (validate, build the card) and an *execute* half (runs after confirmation) | `backend/app/ai/tools/action_tools.py` |
| HR rules | Weekends skipped (working days), balance check, no overlap, no past annual leave, sick leave not in advance (up to 14 days back), 30-day cap, policy notes (4 weeks' notice for 3+ days) | `action_tools.py` |
| HRIS writes | `HrisClient` write methods; the demo HRIS layers changes over its generated data (balances update) | `backend/app/integrations/hris.py` |
| Actions store | `actions` table: frozen arguments, card, status, result, error, timestamps (migration `0005`); atomic status transitions | `app/db/models/action.py`, `repositories/sql.py` |
| Action service | Propose, confirm (idempotent, race-safe, re-validates), cancel, expiry after 15 min | `backend/app/services/actions.py` |
| API | `GET /v1/actions/{id}`, `POST …/confirm`, `POST …/cancel`; 409 codes for expired, cancelled, in-progress and done | `backend/app/api/actions.py` |
| Chat integration | `confirmation.required` event; at most one proposal per answer; the model is told "awaiting confirmation"; outcomes added to later context | `chat_service.py`, `app/ai/events.py` |
| History | Confirmation cards are saved in message parts and returned with their **current** status | `api/conversations.py` |
| Prompt v4 | When to propose, never claim it's done, exact dates, ask if unclear, look up IDs first | `app/ai/prompts/system.md` |
| Shared KMP | `PendingAction` model, `ConfirmationRequired` event, actions from cached parts, `ActionRepository` (409 → fetch the real state) | `shared/.../domain/model/Action.kt`, `data/repository/ActionRepositoryImpl.kt` |
| Android | `ConfirmationCard`: fields, notes, Confirm/Cancel, spinner, outcome, screen-reader announcements; double taps ignored; retry after offline; waits out "in progress" | `ui/chat/cards/ConfirmationCard.kt`, `ChatViewModel.kt` |
| Tests | Backend 171 with Postgres (propose, confirm, idempotency, 5 simultaneous confirms, cancel, expiry, other users, 8 rule violations, stale balance at confirm, overlap, one action per answer, withdraw refunds, history state, model context, failure honesty, demo mode, atomic claims on Postgres). Mobile: actions parsing (2), repository (3), action fixture, ViewModel (6) | `backend/tests/test_actions.py`, `mobile/**/test` |
| CI | `alembic check` fails the build if models and migrations disagree | `.github/workflows/backend.yml` |

## 2. How it works

```
"Book annual leave on 26 and 27 October"
  └▶ model ─▶ submitLeaveRequest{annual, 2026-10-26, 2026-10-27}
               └▶ prepare: working days 2 · balance 14 ✓ · no overlap ✓ · not in the past ✓
               └▶ actions row (pending, args frozen, expires in 15 min)
  ◀── SSE tool.result {status: awaiting_confirmation}
  ◀── SSE confirmation.required {action_id, title, fields, notes, confirm_label}
  └▶ model is told "awaiting confirmation" ─▶ "I've prepared it, please confirm."

Employee taps "Submit request"
  └▶ POST /v1/actions/{id}/confirm
       UPDATE actions SET status='executing' WHERE id=… AND status='pending' AND expires_at>now
         ├─ 0 rows: already done → return the same result · expired/cancelled → 409
         └─ 1 row: prepare again (rules may have changed) ─▶ execute ─▶ HRIS
                    └▶ status executed + result {reference LR-1050}  (or failed + reason)
  ◀── card: ✓ "Leave request LR-1050 submitted."
```

- **The model can propose but not confirm.** There is no tool for confirming. Even a prompt-injected or confused model can at most show the employee a card.
- **What you see is what runs.** The arguments are frozen in the `actions` row at proposal time, and confirming runs exactly those.
- **Idempotency by design.** The action ID is the idempotency key. Confirming twice (double tap, retry on a bad network) returns the stored result.
- **Race safety in the database.** A conditional `UPDATE … WHERE status='pending'` lets exactly one request win. Tested with 5 simultaneous confirms, and with 10 claims racing on real Postgres.
- **Re-validation.** Between proposal and confirmation, the balance may have been used elsewhere. The rules run again, and a failure becomes "not done: you have 1 day left", with nothing changed.
- **Honest uncertainty.** If the HR system errors mid-change, the card says "check your requests before trying again" rather than claiming either outcome. Internal details stay in the logs.
- **Closing the loop for the model.** Later turns add a line like `[Action 'Request annual leave': confirmed and done, reference LR-1050]` to the old answer, so the model answers "did it go through?" truthfully.

## 3. Why we chose it

| Decision | Why | Alternative rejected |
| --- | --- | --- |
| Propose → confirm → execute | Errors and injections can't change data; the user stays in control | Execute when the model calls the tool |
| Confirmation as an API call from a button | A real control, auditable | "Reply yes to confirm" in chat (an injected "yes" passes) |
| Frozen arguments | The user confirms exactly what they saw | Re-asking the model at confirm time |
| Atomic compare-and-set on status | Exactly-once under races, no locks held | Read-then-write in Python (race) |
| 409 + fetch the real state on the client | The card always shows the truth after a conflict | Showing a generic error |
| One action per answer | Each card is a clear, separate decision | Batches of changes in one card |
| 15-minute expiry | Old cards can't be confirmed days later out of context | No expiry |
| Rules in the server tool | One source of truth; the app stays simple | Validating in the app |

## 4. Bugs and lessons from this phase

1. **A field called `title` vanished from a tool's schema.** The Phase 3 schema cleaner removed every `"title"` key to reduce noise for small models, including the `title` *property* of `createHrRequest`. The model would never have been told the field existed, while the server still required it. The existing schema test caught it. The fix strips only string-valued metadata titles, and a new test checks that every required field is still described.
2. **Time in tests.** Chat used the real clock while the demo HR data used a fixed date, so date rules would have drifted. `ChatService` now takes a clock, like the action service.
3. **Review findings (fixed):**
   - The app hid the buttons using the phone's clock, which can be wrong. Expiry is now decided by the server only.
   - One malformed confirmation event would have failed the whole answer. It's now skipped like an unknown event.
   - A card the server reported as "in progress" would have spun forever. The app now checks back a few times.

## 5. How to run and test it

1. **Backend:** `docker compose up --build` (applies migration `0005`).
2. **Android Studio:** sync and run.
3. **Try it:**
   - "Book annual leave on Monday and Tuesday after next" (also a suggestion chip) → card → **Submit request** → ✓ LR-1050. Then "show my leave requests": LR-1050 is pending, and the balance went down.
   - Tap Submit twice quickly: only one request is created.
   - "Cancel LR-1050" → **Withdraw request** → the days go back to your balance.
   - "Raise an IT ticket: my monitor flickers" → card → **Send request**.
   - "Mark my code of conduct task as done" (it looks up the task first).
   - Rules: "Book 7 days of casual leave next month" → "you have N days of casual leave left". "Book leave last Monday" → can't be in the past.
   - Leave a card for 15+ minutes, then tap: it shows "expired, ask again".
   - Reopen the conversation from history: the card shows its final state. Ask "did my leave go through?"
4. **curl** (with `AUTH_MODE=dev`): find the `action_id` in the `confirmation.required` event, then `curl -X POST localhost:8000/v1/actions/<id>/confirm -H 'Authorization: Bearer dev:abdul'`. Run it twice: same answer.
5. **Fake mode:** "book annual leave" prepares Monday–Tuesday after next; "raise a ticket …" prepares an IT request.
6. **Tests:** `cd backend && uv run pytest` · `cd mobile && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest`

The demo HR system keeps changes in memory, so restarting the backend forgets booked leave. The `actions` table (and so the cards in history) keeps the record.

## 6. Interview concepts demonstrated

- Human-in-the-loop AI: the model proposes, a person decides, the server executes
- Prompt-injection containment by capability design (no confirm tool, frozen arguments)
- Idempotency keys and exactly-once effects; compare-and-set state transitions
- Optimistic concurrency without locks; race testing (in memory and on Postgres)
- State machines with expiry; 409 conflict semantics; reconciling client state with the server's truth
- Re-validation at execution time (time-of-check vs time-of-use)
- Audit trails for AI actions; feeding outcomes back into the model's context
- Accessible async UI: live-region announcements, busy states, safe retries

**60-second explanation:**

> "The assistant can book leave or raise requests, but it never acts directly. Write tools have two halves. When the model calls one, the server only validates it against HR rules (working days, balance, overlaps, notice periods), stores a pending action with the arguments frozen, and streams a confirmation card to the app. The model is told it's awaiting confirmation. The change runs only when the employee taps Confirm, which calls a confirm endpoint. That endpoint uses an atomic compare-and-set from pending to executing, so double taps and retries can't run it twice, re-checks the rules, executes, and stores the result. Actions expire after 15 minutes, other users get a 404, and outcomes are fed back into the model's context so it can answer 'did that go through?' truthfully. The model can at worst propose something, never do it."

## 7. Likely interview questions

1. **How do you stop the AI from making changes the user didn't intend?** It can't make changes at all; it can only create proposals. The only execution path is an authenticated confirm request from the user's tap, with the exact frozen arguments.
2. **What if the user double-taps or the network retries?** The action ID is the idempotency key, and the first request flips `pending → executing` atomically. Later requests see `executed` and return the stored result.
3. **What happens if the balance changed between proposal and confirmation?** The rules run again before executing. The action becomes `failed` with a clear reason, and nothing changes in the HR system.
4. **Why not let the model ask "are you sure?" in text?** Text confirmation is ambiguous and injectable. A button tied to an action ID is unambiguous, auditable and can't be faked by content.
5. **How would you make this exactly-once with a real HR system?** Pass our action ID as the HRIS's idempotency key. If the call times out, reconcile by querying for that key before retrying, which is why the app says "check before trying again".
6. **Would you ever skip confirmation?** For low-risk, easily reversible actions (marking a notification read), as an explicit policy per tool, never as a model decision.

## 8. Check yourself

1. What does the model receive after calling `submitLeaveRequest`, and why not "done"?
2. Two confirm requests arrive in the same millisecond. Walk through what each one gets.
3. Why are the arguments stored with the action instead of re-sent by the app on confirm?
4. A card was created 20 minutes ago. What happens when it's tapped, on the server and in the app?
5. How does the model know, a day later, that LR-1050 was submitted?

## 9. CV / LinkedIn line

> Designed human-in-the-loop AI actions for an HR assistant: LLM tool calls create validated, frozen proposals rendered as native Compose confirmation cards; execution happens only on user confirmation through an idempotent, race-safe API (atomic state transitions, expiry, re-validation, audit trail), making prompt injection unable to change data.

## Next: Phase 6 — Voice

Talk to Keystone: on-device speech-to-text (Android SpeechRecognizer) for the question, streaming text-to-speech for the answer, a push-to-talk button with live transcription, and voice-friendly answers. Free and private, with no cloud speech API.
