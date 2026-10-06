# ADR 0006: Kotlin Multiplatform scope

- Status: Accepted
- Date: 2026-10-06

## Context
We want to show work beyond Android-only without slowing delivery.

## Decision
Share domain models, networking (Ktor), repositories, validation and later the Room cache in a KMP `shared` module. Keep UI, voice and Firebase SDK calls platform-native.

## Consequences
Business logic is written once and unit-tested in commonTest. UI and platform APIs differ too much to share cheaply; Compose Multiplatform UI can be evaluated later.

## Alternatives considered
Android-only (simpler, less signal); full Compose Multiplatform UI (more risk, little extra value now).
