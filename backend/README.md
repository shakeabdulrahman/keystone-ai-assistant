# Keystone backend (AI gateway)

FastAPI service that sits between the mobile apps and every model, tool and data store.

```bash
uv sync                          # install deps
cp .env.example .env
uv run alembic upgrade head      # needs Postgres with pgvector (see ../docker-compose.yml)
uv run uvicorn app.main:app --reload
uv run pytest                    # unit tests (integration tests run when DATABASE_URL points at a real DB)
uv run ruff check . && uv run ruff format --check . && uv run mypy app
```

| Endpoint | Purpose |
| --- | --- |
| `GET /healthz` | Liveness |
| `GET /readyz` | Readiness: database + pgvector |
| `GET /v1/meta` | Service name, version, environment |
| `GET /docs` | OpenAPI UI (disabled in production) |
