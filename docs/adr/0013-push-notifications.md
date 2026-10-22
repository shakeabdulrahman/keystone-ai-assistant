# ADR 0013: Push notifications through FCM, with no HR details in the payload

- Status: Accepted
- Date: 2026-10-16

## Context
Employees should hear about leave decisions and HR request updates without opening the app. Push payloads pass through Google's servers (and Apple's for iOS) and show on lock screens, where anyone can read them.

## Decision
- **Firebase Cloud Messaging**, called from the backend through the **HTTP v1 API**. A service-account key signs a short JWT, which is exchanged for an OAuth token and cached for about an hour. There's no Admin SDK dependency.
- **Payloads are generic.** The title is "Keystone" and the body is a sentence like "Your leave request has an update". The `data` holds a `kind` and an opaque reference. The details appear in the app, behind sign-in.
- **Device tokens live in a `devices` table.** A token is unique and moves to the account that registered it most recently. It is removed on sign-out, and the app also deletes the token locally. Tokens that FCM reports as `UNREGISTERED` are pruned.
- **On Android 13 and later, permission is requested from an explained card on the home screen**, never at launch. It is offered once.
- **Events go through one `NotificationService`.** It writes the in-app notification first, then the push.
- **In the demo, a "manager" approves leave after 20 seconds.** In production, a webhook from the HR system would trigger the same path.

## Consequences
- Free, and the standard mechanism on Android (with the same pipeline for iOS through APNs and FCM).
- Lock-screen privacy comes at the cost of less informative notifications. That's the right trade-off for HR data.
- The service-account key is a deployment secret (mounted as a file, never baked into the image), so it has to be managed.

## Alternatives considered
- **Detailed payloads ("Annual leave 14–15 Dec approved"):** convenient, but they leak HR data to the lock screen and to third parties.
- **Polling from the app:** costs battery, is slow, and doesn't work when the app is closed.
- **The Firebase Admin SDK:** a heavy dependency for a single HTTP call.
- **WebSockets or a long-lived connection:** these die in the background on mobile, so they can't replace push.
