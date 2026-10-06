# ADR 0008: No agent framework

- Status: Accepted
- Date: 2026-10-06

## Context
We need a tool-calling loop, RAG and streaming.

## Decision
Write a small in-house orchestrator (~300 lines) on top of provider SDKs.

## Consequences
Interviewers probe exactly what frameworks hide: message formats, tool schemas, loop limits, error handling. Owning the code keeps it debuggable and testable with fakes.

## Alternatives considered
LangChain / LlamaIndex: fast to start, heavy abstractions, frequent API churn.
