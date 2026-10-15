# Phase 2A — Accounts and persistent conversations (backend)

**Outcome:** the gateway now knows who is calling and remembers everything. Conversations and messages are stored in Postgres, every query is limited to the caller's own data, and the app can list, page through, rename and delete conversations. Sign-in tokens from Firebase are verified on every request; a dev mode lets you test locally before the app has real sign-in (Phase 2B).

## 1. What we built

| Area | What exists now | Key files |
| --- | --- | --- |
| Database | `users`, `conversations`, `messages` tables, indexes, unique constraint for idempotency; migration `0002` (reversible) | `backend/app/db/models/`, `migrations/versions/0002_*` |
| Repositories | Storage interfaces + Postgres implementation + in-memory twin for fast tests | `backend/app/repositories/` |
| Auth | Firebase ID-token verification (RS256, audience, issuer, expiry) with cached Google certificates; dev mode refused in production | `backend/app/core/auth.py`, `app/api/deps.py` |
| Profile | `POST /v1/auth/session`, `GET /v1/me`, `DELETE /v1/me` (deletes all data) | `backend/app/api/auth.py` |
| History API | List (keyset pagination), detail, messages (paged, older pages via cursor), rename, soft delete | `backend/app/api/conversations.py` |
| Chat | Saves both sides of every exchange with status, model, prompt version, tokens and latency; IDs in the stream match stored IDs | `backend/app/services/chat_service.py` |
| Titles | Fallback title from the first question; AI title generated in the background after the first answer | `backend/app/services/titles.py` |
| Streaming fix | `ClosingStreamingResponse`: cleanup runs however the client disconnects | `backend/app/api/streaming.py` |
| Tests | 78 total: auth with real RSA-signed tokens, ownership isolation, pagination, titles, disconnect regressions, and Postgres integration tests | `backend/tests/` |
| Docs | API contract, Firebase setup guide | `contracts/README.md`, `docs/firebase-setup.md` |

## 2. How it works

```
Request ─▶ RequestContextMiddleware ─▶ get_principal (verify token) ─▶ get_current_user (get or create)
                                                                            │ user.id
          /v1/conversations/*  ─▶ ConversationRepository.get_owned(user.id, id) ─▶ 404 unless yours
          /v1/chat             ─▶ ChatService.start_turn(user.id, ...) ─▶ stream ─▶ save answer (shielded)
                                                                            └▶ TitleGenerator (background)
```

- **Token verification.** Firebase signs ID tokens with Google's private keys. We download Google's public certificates (cached for as long as Google's `Cache-Control` says), check the RS256 signature, that `aud` is our project ID, `iss` is `securetoken.google.com/<project>`, the token hasn't expired, and `sub` (the user ID) is present. No service-account secret is needed.
- **Users** are created on first sight (`ON CONFLICT DO NOTHING`, so two simultaneous first requests can't create two rows).
- **Authorisation** is enforced in one place: every conversation lookup goes through `get_owned(user_id, conversation_id)`, which returns nothing for missing, deleted or someone else's conversations. The API returns the same `404` for all three, so IDs can't be probed.
- **Keyset pagination.** Cursors encode the last item's `(timestamp, id)`. Queries use `WHERE (ts, id) < cursor ORDER BY ts DESC, id DESC LIMIT n+1`, backed by an index. Unlike `OFFSET`, this stays fast on large tables and never skips or repeats items when new ones arrive.
- **Short transactions.** Each repository call is its own transaction. No database connection is held open while the model streams, so a slow model can't exhaust the connection pool.
- **Saving the answer** happens in a shielded cleanup step, so it completes even when the request is being cancelled.

## 3. Why we chose it

| Decision | Why | Alternative rejected |
| --- | --- | --- |
| Verify Firebase JWTs ourselves with public certs | No secret to manage, fast, provider-agnostic shape | Firebase Admin SDK with a service-account key |
| Dev auth mode, blocked in production by config validation | Test everything with curl before the app signs in; impossible to ship by accident | Disabling auth locally with an `if` |
| Repository interfaces + in-memory twins | 70+ tests run in about a second; integration tests prove the SQL | Mocking SQLAlchemy |
| Plain dataclass records | ORM objects never leak into services (no lazy loading surprises) | Returning ORM models |
| Keyset pagination | Stable and fast | `OFFSET`/page numbers |
| Soft delete for conversations, hard delete for accounts | Undo-able user action vs. a real "delete my data" right | One or the other everywhere |
| Unique `(conversation_id, client_message_id)` | Idempotency enforced by the database, not just code | Checking in Python only (races) |
| Background titles | Never slows the answer; failures are harmless | Generating the title before replying |

## 4. Bugs found this phase

1. **Disconnect during a write.** If the phone vanished while the server was *sending* a chunk (rather than waiting for the model), Starlette stopped iterating without closing the stream. Cleanup never ran, so the partial answer wasn't saved, and a real model would have kept generating. Fixed with `ClosingStreamingResponse`, which always closes the body iterator. A regression test simulates a failing socket write and fails without the fix.
2. **Duplicate detection only worked in memory.** In Postgres, SQLAlchemy *autoflushes* the INSERT when the next statement runs, so the duplicate-key error surfaced earlier than the code expected and became a 500 instead of a 409. The integration test against real Postgres caught it; the in-memory tests could not. This is why both kinds of test exist.

## 5. How to run and test it

```bash
cd ~/Documents/AI-Project/keystone-ai-assistant
docker compose up --build        # applies migration 0002 automatically
```

In a second Terminal tab (dev mode, so the token is just `dev:<name>`):

```bash
H='Authorization: Bearer dev:abdul:abdul@example.com'
curl -s -X POST localhost:8000/v1/auth/session -H "$H"
curl -sN -X POST localhost:8000/v1/chat -H "$H" -H 'content-type: application/json' \
  -d '{"client_message_id":"'$(uuidgen)'","content":"How do I request annual leave?"}'
curl -s localhost:8000/v1/conversations -H "$H"
# copy an "id" from the list, then:
curl -s localhost:8000/v1/conversations/<id>/messages -H "$H"
curl -s -o /dev/null -w "%{http_code}\n" localhost:8000/v1/conversations/<id> -H 'Authorization: Bearer dev:someone-else'   # 404
```

Restart the backend (`Ctrl+C`, `docker compose up`) and list again: the history is still there. The app keeps working as before: without a token, dev mode treats it as `dev-user`.

Interactive docs at http://localhost:8000/docs show every endpoint; click **Authorize** and enter `dev:abdul` to try them.

## 6. Interview concepts demonstrated

- Authentication vs authorisation; JWT verification (signature, audience, issuer, expiry); key rotation via cached JWKS/certs
- Multi-tenant data isolation; preventing ID enumeration (IDOR) with uniform 404s
- Database migrations; indexes for real query patterns; partial indexes
- Keyset (cursor) pagination
- Idempotency enforced by unique constraints; race-safe upserts
- Repository pattern with in-memory fakes + integration tests against the real database
- Connection-pool hygiene with long-lived streaming responses
- Background work after the response (titles) and graceful shutdown
- GDPR-style data deletion
- Cancellation-safe persistence

**60-second explanation:**

> "The gateway verifies a Firebase ID token on every request: RS256 signature against Google's public certificates, audience, issuer and expiry. That gives a stable user ID, and every data access goes through repository methods that take that ID, so a user can only ever see their own conversations; someone else's ID returns the same 404 as a missing one. Conversations and messages live in Postgres with keyset pagination and a unique constraint that makes message sends idempotent. The streaming endpoint never holds a database connection while the model is talking: it saves the user message, streams, then saves the answer in a shielded cleanup step so it survives disconnects. Tests run against in-memory repositories for speed and against real Postgres in CI, and the Postgres tests caught a bug the fast tests couldn't."

## 7. Likely interview questions

1. **How does your backend trust a Firebase token without calling Firebase on each request?** It verifies the JWT locally with Google's published public keys, cached according to their cache headers, and checks audience, issuer, expiry and subject.
2. **What stops user A from reading user B's conversation?** Ownership is part of every query (`WHERE user_id = :caller`). Lookups that fail return a uniform 404, and tests cover each endpoint with a second user.
3. **Why cursor pagination instead of page numbers?** It's O(log n) with the right index, and results don't shift when new conversations arrive while you scroll.
4. **How do you make "send message" safe to retry?** The app sends a client-generated ID; a unique constraint rejects duplicates, and the API returns 409.
5. **Why not hold one DB transaction for the whole chat turn?** The model can stream for a minute; holding a connection that long exhausts the pool under load. Short transactions before and after are enough.

## 8. Check yourself

1. Which four checks make a Firebase ID token valid for our backend?
2. Why does the API return 404, not 403, for someone else's conversation?
3. What problem does `ClosingStreamingResponse` solve that the Phase 1 fix didn't?
4. Why did only the Postgres test catch the duplicate-message bug?
5. What's the difference between soft-deleting a conversation and `DELETE /v1/me`?

## 9. CV / LinkedIn line

> Implemented secure multi-tenant persistence for an AI assistant: Firebase JWT verification, per-user data isolation, keyset pagination and idempotent writes on Postgres, with cancellation-safe saving of streamed AI answers, verified by unit and real-database integration tests.

## Next: Phase 2B — Sign-in and history in the app

Firebase email sign-in screens, automatic token attachment and refresh in the KMP networking layer, conversation list and history screens, and a Room offline cache so history opens instantly and works without a connection.
