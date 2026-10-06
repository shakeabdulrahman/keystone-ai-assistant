# ADR 0002: Backend: Python 3.12 + FastAPI

- Status: Accepted
- Date: 2026-10-06

## Context
The AI gateway needs streaming, async I/O, and the best access to AI tooling.

## Decision
Use Python 3.12, FastAPI, Pydantic v2, SQLAlchemy 2 (async) with asyncpg, Alembic, managed with uv.

## Consequences
Provider SDKs, document parsing and evaluation tooling arrive in Python first, and AI engineering roles expect it. FastAPI streams natively and generates OpenAPI. Cost: request/response models are duplicated in Kotlin, mitigated by an OpenAPI spec and shared JSON contract fixtures.

## Alternatives considered
Kotlin + Ktor (shared models end to end, weaker AI ecosystem); Node/TypeScript.
