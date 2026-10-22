# ADR 0015: Observability: Prometheus metrics with low cardinality, JSON logs, request IDs

- Status: Accepted
- Date: 2026-10-16

## Context
An AI assistant fails in new ways: slow first tokens, tools timing out, empty retrievals, models refusing. These need numbers, without logging what employees ask.

## Decision
- **`/metrics` in Prometheus format, protected by a bearer token** (disabled when no token is set).
- **Metrics:** HTTP requests and latency by *route template* (never the raw path, so IDs don't explode cardinality); chat turns by status and input mode; time to first output; turn duration; agent steps; tokens in and out; tool calls and latency per tool; RAG source kinds; actions by outcome; rate-limit hits; push outcomes.
- **No labels from user content or IDs.**
- **Logs are structured JSON with a request ID** (already from Phase 0). They never include message text, tokens or keys.
- **Local stack:** Prometheus and Grafana in a compose `observability` profile, with a provisioned dashboard.

## Consequences
- The dashboard answers "is it slow, and where?" (model, a tool, or retrieval) without reading conversations.
- Per-instance metrics are scraped per instance. Render's free tier has no scraper, so production metrics are pulled on demand or by a free external scraper.
- Tracing (OpenTelemetry) is the next step, if needed, and isn't included yet.

## Alternatives considered
- **A hosted APM (Datadog, New Relic):** paid beyond trials.
- **Logging prompts and answers for debugging:** a privacy problem for HR data.
- **LLM observability SaaS:** useful for prompt work, but sends conversations to a third party.
