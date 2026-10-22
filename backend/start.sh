#!/bin/sh
# Container start: migrate, load the knowledge base (idempotent: unchanged documents are
# skipped), then serve. Used by docker-compose and by the cloud deployment.
set -e
alembic upgrade head
if [ "${INGEST_ON_START:-1}" = "1" ]; then
  python -m app.rag.ingest || echo "Knowledge base NOT loaded - see the error above"
fi
# PORT is set by hosting platforms (Render: 10000); 8000 locally.
exec uvicorn app.main:app --host 0.0.0.0 --port "${PORT:-8000}" --proxy-headers
