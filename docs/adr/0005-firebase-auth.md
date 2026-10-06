# ADR 0005: Identity: Firebase Authentication

- Status: Accepted
- Date: 2026-10-06

## Context
Users must sign in; building password storage is risky and adds nothing to the portfolio.

## Decision
Sign in with Firebase Auth on the device. The backend verifies the Firebase ID token on every request and maps it to its own users table. Firebase App Check proves requests come from the genuine app.

## Consequences
Free on the Spark plan, secure by default, and keeps the backend stateless. The mobile app depends on an AuthTokenProvider interface, so the identity provider can change.

## Alternatives considered
Own JWT auth server: more code and more risk with no interview value.
