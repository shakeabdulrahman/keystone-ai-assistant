# ADR 0016: Free cloud deployment: Render + Neon + Gemini

- Status: Accepted (refines ADR 0009)
- Date: 2026-10-16

## Context
The demo needs a public HTTPS backend at no cost. Ollama can't run on free servers.

## Decision
- **API:** the existing Docker image on Render's free web service, defined in `render.yaml` (Blueprint). Secrets are entered in Render, never in git. The Firebase key is a Render Secret File.
- **Database:** Neon free Postgres with pgvector, using the direct (unpooled) connection. Provider URLs are normalised for asyncpg in settings.
- **AI:** a Gemini adapter (`LLM_PROVIDER=gemini`) for chat (streaming `streamGenerateContent`, function calling) and embeddings (`gemini-embedding-001` at 768 dimensions, the same column size as nomic-embed-text). Model names are settings.
- **One start script** for compose and cloud: migrate, ingest (idempotent), serve on `$PORT`.
- **A post-deploy smoke test** with the standard library only.

## Consequences
- Zero cost. Cold starts after idle (~1 minute) are acceptable for a demo; the smoke test waits for them.
- **Free Gemini prompts may be used by Google to improve its products, so only fictional demo data is used.** A real deployment would use a paid tier or a self-hosted model with a data-processing agreement.
- Switching embedding providers requires re-ingesting (vectors from different models aren't comparable). Ingest detects the model change.

## Alternatives considered
- **Fly.io / Railway:** free tiers changed or need a card.
- **Google Cloud Run + Cloud SQL:** Cloud SQL isn't free.
- **Groq for chat:** fast and free, but no embeddings. Gemini covers both with one key.
