# ADR 0009: Hosting: local first, free tier later

- Status: Accepted
- Date: 2026-10-06

## Context
The demo eventually needs a public backend without cost.

## Decision
Develop locally with Docker Compose until Phase 8, then deploy containers to a free-tier host with Neon's free Postgres. Re-check free-tier terms at that time.

## Consequences
Avoids paying for idle infrastructure while building; containers keep the choice portable.

## Alternatives considered
Paid Cloud Run/Cloud SQL or AWS ECS/RDS from the start.
