# Phase 2B — Sign-in and history in the app

**Outcome:** the app now has real accounts. Users sign in with email and password (Firebase), every request carries a verified token, and conversations live on: a history list with rename and delete, past chats that reopen and continue, and an on-device cache so history opens instantly and still works offline. Signing out wipes the cache.

## 1. What we built

| Area | What exists now | Key files |
| --- | --- | --- |
| Auth abstraction | `AuthService` interface (state, sign in, create account, sign out) and `AuthTokenProvider` for the shared layer | `androidApp/.../auth/AuthService.kt`, `shared/.../auth/AuthTokenProvider.kt` |
| Firebase | Email/password sign-in, friendly error mapping (no account enumeration), token provider | `FirebaseAuthService.kt`, `FirebaseTokenProvider.kt` |
| Token on every request | Ktor `HttpSend` interceptor: attach bearer token, on 401 refresh once and retry | `shared/.../auth/BearerAuth.kt` |
| Offline cache | Room (Kotlin Multiplatform) database: conversations + messages, DAOs with Flow, bundled SQLite | `shared/.../data/local/` |
| Offline-first repository | Cache is the source of truth for the UI; refresh from server; optimistic rename/delete with rollback; paging | `ConversationRepositoryImpl.kt` |
| Session handling | Auth-gated UI (signed-out users only see sign-in); backend session per user; cache wiped on sign-out and before sign-in | `ui/AppViewModel.kt`, `ui/KeystoneApp.kt` |
| Screens | Sign-in/create account; conversation history (pull-to-refresh, skeletons, empty/error/offline states, rename/delete dialogs); chat reopens past conversations | `ui/auth/`, `ui/conversations/`, `ui/chat/` |
| Tests | Shared: auth retry (4), repository with fake DAOs (8). App: sign-in (5), history (4), app session (2), chat history (1 new) | `shared/src/commonTest`, `androidApp/src/test` |
| CI | Placeholder `google-services.json` (or a real one from a secret) so CI builds without committing it | `.github/workflows/android.yml` |

## 2. How it works

```
SignInScreen ─▶ SignInViewModel ─▶ AuthService (Firebase) ─▶ authState: SignedIn
                                                                   │
KeystoneApp observes authState ─ SignedOut → SignInRoute           │
                                └ SignedIn  → signed-in NavHost     ▼
                                              AppViewModel ─▶ POST /v1/auth/session (once per user)

Any API call ─▶ HttpSend interceptor ─▶ token(force=false) ─▶ request ─▶ 401? ─▶ token(force=true) ─▶ retry once

History screen ─▶ observeConversations() (Room Flow, instant/offline)
              └▶ refreshConversations() (server → replace cache → observers update)
```

- **The database is the single source of truth for the UI.** Screens observe Room; network calls only write into Room. That's what makes offline work without special cases.
- **Optimistic updates.** Rename and delete change the cache immediately (the UI reacts at once). If the server refuses or the network is down, the old values are restored and a snackbar explains.
- **Sessions are isolated.** The signed-in navigation graph is created per session, the cache is wiped on sign-out and again before any new sign-in (in case the app was killed before sign-out finished).
- **Errors don't leak account information.** "Wrong password" and "no such user" both say "Email or password is incorrect."

## 3. Why we chose it

| Decision | Why | Alternative rejected |
| --- | --- | --- |
| `AuthService` interface in the app, `AuthTokenProvider` in shared | ViewModels are tested with fakes; shared code has no Firebase dependency; iOS can plug in its own provider | Calling FirebaseAuth from ViewModels |
| Custom `HttpSend` interceptor | Asks Firebase for the token each time (Firebase caches it), so a new user never gets a stale token; one retry on 401 | Ktor Auth plugin's own token cache (must be cleared manually on user change) |
| Room KMP with bundled SQLite | Same DB code on Android and iOS; Flow queries; the team already knows Room | SQLDelight (fine, but new to learn); no cache |
| Cache only, destructive migrations | It's a copy of server data: on schema change, drop and refetch instead of writing migrations | Full migration path for a cache |
| Replace-on-refresh for the first page | Conversations deleted on another device disappear here too | Merge-only (stale rows forever) |
| Email/password sign-in first | Simplest secure option; Google sign-in needs SHA-1 setup | Social sign-in in the MVP |
| Wipe cache on sign-out | HR data on a shared or lost device | Keep data for "faster next login" |

## 4. How to run and test it

1. **Switch the backend to real Firebase verification.** `backend/.env` now contains:
   ```
   AUTH_MODE=firebase
   FIREBASE_PROJECT_ID=keystone-hr
   ```
   Restart: `docker compose up --build`. (`dev:` curl tokens no longer work; set `AUTH_MODE=dev` again whenever you want to test with curl.)
2. **Android Studio:** sync Gradle (new: Firebase, Room, kotlinx-datetime), then run the app.
3. **Try it:**
   - Sign in with your Firebase test user, or tap "Create an account".
   - Try a wrong password, an invalid email, and airplane mode on the sign-in screen.
   - Ask a question, go back, open **Your conversations**: it's listed with an AI title. Open it and continue chatting.
   - Pull to refresh, rename, delete.
   - Turn on airplane mode and open history: cached conversations still show, with an offline note.
   - Sign out, sign in as a different user: the first user's history is not visible.
4. **Tests:** `./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest`

If KSP reports an error about Room's generated database constructor, add `ksp.useKSP2=false` to `mobile/gradle.properties` and sync again (a known KSP2 edge case).

## 5. Interview concepts demonstrated

- Offline-first architecture: database as single source of truth, network as a sync mechanism
- Optimistic UI with rollback
- Token-based auth on mobile: attach, refresh on 401, retry once, no infinite loops
- Session isolation and data hygiene on shared devices
- Kotlin Multiplatform persistence (Room KMP, expect/actual database builders)
- Flow-based reactive UI (`combine`, `stateIn(WhileSubscribed)`)
- Type-safe navigation with arguments; auth-gated navigation graphs
- Security UX: no account enumeration, no secrets in the repo, CI with placeholder config
- Testing with hand-written fakes (DAOs, auth, repositories), no mocking frameworks

**60-second explanation:**

> "Sign-in uses Firebase behind an AuthService interface, so ViewModels are tested with fakes and the shared Kotlin Multiplatform layer only sees an AuthTokenProvider. A Ktor HttpSend interceptor attaches the ID token to every call; on a 401 it forces one token refresh and retries once. History is offline-first: screens observe a Room database that's shared across Android and iOS, and network calls only write into it, so the UI shows cached data instantly and also offline. Rename and delete are optimistic with rollback. Because it's HR data, the cache is wiped on sign-out and again before the next sign-in, and the signed-in navigation graph is recreated per session."

## 6. Likely interview questions

1. **Why make the database the source of truth instead of the network response?** One code path for online and offline, instant screens, and consistency: every screen observing the same table updates together.
2. **How do you avoid an infinite 401 loop?** Refresh exactly once per request; a second 401 is returned to the caller, which shows "sign in again".
3. **What happens to cached data on sign-out, and why twice?** It's wiped before sign-out and before sign-in; the second covers the app being killed mid-sign-out.
4. **Why not show "user not found"?** It tells attackers which emails have accounts (account enumeration).
5. **How would you add iOS?** The shared module already has the Room builder and networking; an iOS app implements AuthTokenProvider with the Firebase iOS SDK and builds UI in SwiftUI.

## 7. Check yourself

1. Which component decides whether the user sees sign-in or the main app?
2. Walk through what happens when a token has expired during a chat request.
3. What does the user see in the history list with no network, and where does that data come from?
4. A rename fails on the server. What does the user see, and what happens to the title?
5. Why isn't `google-services.json` in git, and how does CI still build?

## 8. CV / LinkedIn line

> Built offline-first conversation history for a Kotlin Multiplatform AI app: Firebase authentication with automatic token refresh in a shared Ktor layer, Room KMP as the single source of truth with optimistic updates and rollback, and session isolation for sensitive HR data, all covered by fake-based unit tests.

## Next: Phase 3 — Tool calling and generative UI

The assistant starts using real (synthetic) HR data: tools like `getLeaveBalance` and `getHrRequests`, an agent loop on the backend, and native Compose cards in the chat when a tool returns structured data.
