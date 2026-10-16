# Keystone — AI HR Assistant

An AI-powered mobile assistant for employees: ask about leave, HR requests, onboarding and company policies by text or voice. The assistant answers from live HR data through **tool calling**, from company documents through **RAG**, streams its replies, and renders real app UI (cards, confirmations, sources) instead of plain text.

> Status: **Phase 3 — Tool calling and generative UI cards** complete. See the [roadmap](#roadmap). The full README (architecture diagrams, screenshots, demo video) arrives in Phase 8.

## Architecture at a glance

```
Android app (Compose)  ─┐
iOS app (SwiftUI, later)─┤  KMP shared core: domain · Ktor client · repositories
                         │
                         │  HTTPS (REST + SSE streaming)
                         ▼
            AI gateway — Python / FastAPI
            auth · chat · agent orchestrator · tools · RAG · LLMProvider
                         │
         ┌───────────────┼──────────────────┐
         ▼               ▼                  ▼
 PostgreSQL + pgvector   Blob storage   LLM providers (Ollama · Gemini · Groq)
```

The mobile app never holds AI keys. Changing LLM vendor changes one backend adapter, not the app.

## Repository layout

| Path | What lives there |
| --- | --- |
| `backend/` | FastAPI AI gateway, migrations, tests, Dockerfile |
| `mobile/` | Android app, KMP `shared` module, design system |
| `docs/adr/` | Architecture Decision Records |
| `docs/phases/` | What was built in each phase and why |
| `contracts/` | Stream event contract + fixtures shared by backend and mobile tests |
| `.github/workflows/` | CI for backend and Android |
| `docker-compose.yml` | Local Postgres + pgvector and the API |

## Run it locally

Prerequisites: Docker Desktop, Android Studio (latest stable), JDK 17+.

```bash
# 1. Backend + database (Ollama running locally with: ollama pull qwen2.5:7b)
cp backend/.env.example backend/.env
docker compose up --build
curl http://localhost:8000/healthz      # {"status":"ok"}
curl http://localhost:8000/readyz       # database + pgvector checks
curl -N -X POST http://localhost:8000/v1/chat -H 'content-type: application/json' \
  -d '{"client_message_id":"'$(uuidgen)'","content":"How many leave days do I have left?"}'

# 2. Android
#    Open the mobile/ folder in Android Studio and run the "androidApp" configuration
#    on an emulator. Needs mobile/androidApp/google-services.json (see docs/firebase-setup.md).
#    Sign in, then tap "Ask Keystone" and try "How many leave days do I have left?"
```

Backend without Docker (needs a local Postgres with pgvector):

```bash
cd backend
uv sync
uv run alembic upgrade head
uv run uvicorn app.main:app --reload
uv run pytest
```

## Roadmap

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Foundations: repo, backend skeleton, KMP + Android skeleton, CI | Done |
| 1 | Streaming chat (SSE, provider abstraction, Fake + Ollama) | Done |
| 2 | Firebase sign-in, conversations and history, offline cache | Done |
| 3 | Tool calling + generative UI cards | Done |
| 4 | RAG over HR documents with citations | Next |
| 5 | Write actions with user confirmation | |
| 6 | Voice | |
| 7 | Home dashboard + push notifications | |
| 8 | Security hardening, observability, deployment, demo | |

## Key decisions

See [docs/adr](docs/adr/README.md). Highlights: Python/FastAPI gateway, PostgreSQL + pgvector, SSE streaming, Firebase Auth, KMP for shared logic, free LLM providers behind an abstraction, no agent framework.
