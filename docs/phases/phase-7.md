# Phase 7 — Home dashboard and push notifications

**Outcome:** the app now tells you what needs attention before you ask. The home screen greets you by name and shows a "Needs your attention" list: an unconfirmed leave card from chat, overdue and due-soon tasks, pending leave and unread notifications. It also shows your leave balances, upcoming meetings and suggested questions. Tapping an item opens the chat with the right question already asked, or takes you back to the unconfirmed card. It's cached, so it opens instantly and offline. When something changes on the HR side (the demo "manager" approves your leave about 20 seconds after you book it), your phone gets a push notification that deliberately reveals nothing. The details appear when you open the app.

## 1. What we built

| Area | What exists now | Key files |
| --- | --- | --- |
| Home API | `GET /v1/home`: one request, built in parallel from HR data and open actions; attention items ordered by urgency, each with what tapping does | `backend/app/services/home.py`, `app/api/home.py` |
| Devices | `devices` table (migration `0006`); `POST /v1/devices`, `POST /v1/devices/unregister`; tokens move to the latest account and are pruned when FCM says they're gone | `app/db/models/device.py`, `app/api/devices.py`, `repositories/sql.py` |
| Push sender | `FcmPushSender` (FCM HTTP v1, service-account JWT → cached OAuth token); `LogPushSender` when no key is set | `app/notifications/push.py` |
| Notifications | `NotificationService`: detailed in-app notification + generic push | `app/notifications/service.py` |
| Demo HR workflow | After a confirmed leave request, a "manager" approves it; HR requests move to in progress (listener on `ActionService`) | `app/integrations/demo_workflow.py` |
| Shared KMP | `HomeDashboard` model, lenient parsing (unknown kinds and odd actions don't break it), Room `cached_json` table (DB version 3), `HomeRepository`, `DeviceRepository` | `shared/.../domain/model/Home.kt`, `data/repository/HomeRepositoryImpl.kt` |
| Android home | Greeting, attention cards (urgent styling), leave tiles, upcoming meetings, suggestion chips, pull to refresh, offline note, skeletons, backend status | `ui/home/HomeScreen.kt`, `HomeViewModel.kt` |
| Android push | `KeystoneMessagingService` (token rotation, foreground display), `hr_updates` channel, Android 13+ permission card, register after sign-in / unregister + delete token on sign-out, live refresh on push | `push/`, `KeystoneApplication.kt`, `ui/AppViewModel.kt` |
| Deep links | `ChatDestination(prompt)` asks once (not again on rotation) | `ChatViewModel.kt`, `Destinations.kt` |
| Tests | Backend 183 with Postgres (home content, pending card on home until decided, per-user isolation, device moves between accounts, notify + prune, demo approval with a discreet push, withdrawn-before-approval, FCM auth caching and stale tokens, network errors, Postgres devices + open actions). Mobile: home repository (5), home ViewModel (6), app session push (2), home prompt (1) | `backend/tests/test_home_and_push.py`, `mobile/**/test` |

## 2. How it works

```
Home screen ── observe Room cache (instant, offline)
     │└─ refresh on open / resume / pull / push ─▶ GET /v1/home
     │                                              └ asyncio.gather(profile, balances, leave, notifications,
     │                                                               hr, tasks, meetings, open actions)
     └─ tap attention item ─▶ Ask(prompt) → new chat asks it   |   OpenConversation → card waiting

Confirm leave ─▶ ActionService ─▶ listener ─▶ DemoWorkflow (20 s later)
                                               └ hris.decide_leave(approved)
                                               └ NotificationService.notify
                                                    ├ in-app: "Your annual leave LR-1050 was approved"  (behind sign-in)
                                                    └ FCM:    "Your leave request has an update."         (lock screen)
                                                              data {kind: leave, ref: LR-1050}
Phone ─▶ KeystoneMessagingService ─▶ notification + PushEvents ─▶ Home refreshes
```

- **One request, parallel inside.** The phone makes one round trip; the server reads eight sources concurrently. The response time is the slowest source, not the sum.
- **Server-driven attention.** The server decides what's urgent and what tapping does (`ask` with a prompt, or `open_conversation`). New kinds can be added without an app update; older apps show them with a generic style.
- **Offline-first, again.** The last dashboard is cached as JSON in Room. For data shown only as a whole, a JSON column is simpler than tables. It's validated before caching and wiped on sign-out and before sign-in.
- **Push privacy.** Pushes travel through Google's servers and appear on lock screens, so they never contain HR data. The app fetches details over the authenticated API.
- **Token lifecycle.** The phone registers after sign-in, re-registers on token rotation, and on sign-out unregisters (while still signed in, so the call is authorised) and deletes the token. If another person signs in on the same phone, the token moves to them on the server. Tokens FCM reports as unregistered are deleted.
- **Permission etiquette.** Android 13+ requires runtime permission for notifications. It's offered once from a card that explains the benefit and privacy, never as a pop-up at launch.

## 3. Why we chose it

| Decision | Why | Alternative rejected |
| --- | --- | --- |
| Aggregated `/v1/home` | One round trip; consistent snapshot; server owns priorities | Many calls from the phone |
| Server-provided actions on items | New item kinds without app releases | Hard-coded mapping in the app |
| JSON cache in Room | Simple, offline, atomic replace | Normalised tables for a read-only screen |
| FCM HTTP v1 with a JWT | Free; no Admin SDK; token cached for an hour | Firebase Admin SDK (heavy) |
| Generic push text | Lock-screen and third-party privacy for HR data | Detailed notifications |
| Explained permission card | Higher grant rate, respectful | Asking at first launch |
| Token unregister + delete on sign-out | Shared phones: pushes follow the signed-in person | Leaving tokens registered |
| Listener for demo events | Same path a real HRIS webhook would use | Faking notifications in the app |

## 4. Bugs and lessons from this phase

1. **Typed concurrency limits.** `asyncio.gather` keeps precise types for up to six awaitables; the home endpoint needs eight, and mypy flagged the result as `object`. Two nested gathers keep both type safety and concurrency.
2. **Services that stop too soon.** The first `onNewToken` started the device registration in the background and returned; Android can stop the messaging service right after, cancelling it. The registration now completes, with a time limit, before returning.
3. **Lost refreshes.** A push arriving while a refresh was running was ignored, though that refresh might predate the change. Requests during a refresh now trigger one more afterwards.
4. **A cached dashboard across users.** Signing out cleared it, but a session ending another way (crash, revoked token) could show the previous user's dashboard briefly. The cache is also cleared before every sign-in now.
5. **Lenient parsing, again.** One attention item with a `null` value would have failed the whole dashboard. Items are now parsed leniently, like tool cards and message parts.

## 5. How to run and test it

1. **Push key (optional, 2 minutes):** follow `docs/firebase-setup.md` → section 5. Without it, pushes are only logged.
2. **Backend:** `docker compose up --build` (applies migration `0006`).
3. **Android Studio:** sync (new: firebase-messaging), then run. Use a Google Play emulator image; Android 13+ shows the permission card.
4. **Try it:**
   - The home screen shows your name, what needs attention, leave tiles, meetings and suggestions. Pull down to refresh.
   - Tap the notification card's **Turn on** and allow.
   - Tap an overdue task: the chat opens and asks about it.
   - In chat, "Book annual leave on Monday and Tuesday after next", **don't** confirm, go back: home shows "Waiting for you: request annual leave" at the top. Tap it, confirm.
   - Leave the app (home button). About 20 seconds later, a notification arrives: "Your leave request has an update." Open it: home shows the new unread notification, and asking "What are my notifications?" shows "Leave approved".
   - Airplane mode: home still shows the last dashboard with an offline note.
   - Sign out and in as another user: their own dashboard; the first user no longer gets pushes on this phone.
5. **Without a key:** the backend log shows `push_logged` with `devices: 1` when the approval happens.
6. **Tests:** `cd backend && uv run pytest` · `cd mobile && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest`

## 6. Interview concepts demonstrated

- Backend-for-frontend aggregation; concurrent fan-out with `asyncio.gather`
- Server-driven UI (attention items with actions) and forward-compatible clients
- Offline-first caching of whole-screen data
- Push architecture: device registry, token lifecycle, rotation, pruning, shared devices
- FCM HTTP v1 with service-account JWT → OAuth token exchange, token caching
- Privacy engineering: minimal push payloads, lock-screen visibility
- Android 13 notification permission UX, notification channels, foreground vs background delivery
- Android service lifecycle pitfalls (work outliving a callback)
- Event-driven design: listeners on actions, a webhook-shaped demo workflow
- Coalescing refreshes (no overlap, no lost updates)

**60-second explanation:**

> "The home screen is powered by one aggregated endpoint. The server reads profile, balances, leave, tasks, meetings, notifications and unconfirmed actions in parallel and returns attention items already prioritised, each saying what tapping does: ask the assistant a prepared question or reopen the conversation with the pending card. The app caches the dashboard in Room, so it opens instantly and offline, and refreshes on open, on resume, on pull and on push. For pushes, the phone registers its FCM token after sign-in and unregisters and deletes it on sign-out, and tokens follow whoever signed in last on a phone. The backend sends through FCM's HTTP v1 API with a service-account JWT. Because push payloads go through Google and appear on the lock screen, they contain no HR data, just 'your leave request has an update'. The details load inside the app. In the demo, a simulated manager approves leave twenty seconds after you confirm it, through the same notification path a real HR webhook would use."

## 7. Likely interview questions

1. **Why one `/home` endpoint instead of letting the app call each API?** Fewer round trips on mobile networks, one consistent snapshot, and the server can change priorities without an app release.
2. **What goes into a push payload, and why so little?** A generic sentence and an opaque reference. Payloads pass through third parties and show on lock screens; sensitive data must be fetched over the authenticated API.
3. **How do you handle a phone shared by two employees?** Tokens are unique server-side and move to the latest account; sign-out unregisters and deletes the token; caches are wiped on sign-out and before sign-in.
4. **What happens when a user uninstalls the app?** FCM eventually answers `UNREGISTERED` for that token; we delete it on the next send.
5. **When and how do you ask for notification permission?** After sign-in, from an in-context card explaining the benefit, once. Never at first launch with no context.
6. **How would a real HR system trigger these notifications?** A signed webhook into the same `NotificationService` path the demo workflow uses. Idempotent by event ID.

## 8. Check yourself

1. Which eight things does `/v1/home` read, and why does it still take about the time of one?
2. What does tapping "Waiting for you: request annual leave" do, and who decided that?
3. Why does sign-out call unregister *before* signing out of Firebase?
4. What exactly appears on the lock screen when leave is approved, and where do the details appear?
5. A push arrives while the dashboard is refreshing. What happens?

## 9. CV / LinkedIn line

> Built a server-driven home dashboard and privacy-first push notifications for an AI HR assistant: a concurrent backend-for-frontend endpoint with prioritised, actionable items cached offline in Room KMP, FCM HTTP v1 delivery with device-token lifecycle management for shared phones, and payloads that never expose HR data on the lock screen.

## Next: Phase 8 — Security hardening, observability, deployment and demo

Rate limiting and abuse protection, Firebase App Check, dependency and secret scanning, metrics and tracing (time to first token, tool latency, retrieval quality), a free-tier cloud deployment, release signing, a full README with architecture diagrams, and a demo video script.
