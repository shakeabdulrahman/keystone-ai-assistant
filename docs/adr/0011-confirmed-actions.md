# ADR 0011: Write actions are proposed by the model and executed only on user confirmation

- Status: Accepted
- Date: 2026-10-16

## Context
The assistant should do things for employees: book leave, raise requests, complete tasks. A language model can misunderstand ("next Friday"), hallucinate, or be manipulated by prompt injection, and an HR change made in error is costly to undo.

## Decision
- Write tools have two halves:
  - **prepare** runs when the model calls the tool. It validates the request against the HR rules and returns a card. Nothing changes.
  - **execute** runs only from `POST /v1/actions/{id}/confirm`, a request the app sends when the employee taps the button.
- The model has no way to confirm. The tool result it receives says `awaiting_confirmation`.
- **The arguments are frozen at proposal time and stored with the action.** The employee confirms exactly what they saw.
- **The rules are checked again just before executing.** A business-rule failure at that point becomes a clear "not done" message.
- **Confirming is idempotent and race-safe.** The action ID is the idempotency key. Moving from `pending` to `executing` is a single conditional UPDATE, so only one confirm can win.
- **Proposals expire after 15 minutes.** An answer can propose at most one action.
- **Every proposal and decision is stored** (the `actions` table): status, timestamps, result, error. The outcome is added to the model's context in later turns, so it can answer "did my leave go through?".

## Consequences
- Prompt injection or a model mistake can at worst propose something, and the employee sees it before anything changes.
- One extra tap per action. This is the right trade for HR data, and low-risk actions could later skip confirmation as a deliberate policy.
- A real HRIS integration must accept our reference as its own idempotency key to make the whole chain exactly-once. The demo HRIS keeps writes in memory.

## Alternatives considered
- **Executing directly when the model calls the tool:** fast, but unsafe.
- **Asking the model to "ask the user first" in the prompt:** that is a request, not a control.
- **A confirmation step in chat text ("type yes"):** ambiguous, and an injected "yes" could pass it.
- **Client-side execution:** puts business rules and HRIS credentials in the app.
