"""Runs against a real Postgres + pgvector. Skipped unless RUN_INTEGRATION=1 (CI sets it)."""

import os

import pytest

from app.core.config import Settings
from app.db.session import Database

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(os.getenv("RUN_INTEGRATION") != "1", reason="needs a real database"),
]


async def test_database_check_reports_pgvector() -> None:
    db = Database(Settings())
    try:
        checks = await db.check()
    finally:
        await db.dispose()
    assert checks["database"] == "ok"
    assert checks["pgvector"] != "missing"
