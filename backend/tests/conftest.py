from collections.abc import AsyncIterator

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.api.deps import get_health_checker
from app.core.config import Settings
from app.main import create_app


class FakeHealthChecker:
    """Stands in for the real database so unit tests need no Postgres."""

    def __init__(self, result: dict[str, str] | None = None, error: Exception | None = None):
        self.result = result or {"database": "ok", "pgvector": "0.8.0"}
        self.error = error

    async def check(self) -> dict[str, str]:
        if self.error:
            raise self.error
        return self.result


@pytest.fixture
def settings() -> Settings:
    return Settings(app_env="test", log_level="WARNING")


@pytest.fixture
def app(settings: Settings) -> FastAPI:
    return create_app(settings)


@pytest.fixture
async def client(app: FastAPI) -> AsyncIterator[AsyncClient]:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c


def use_health_checker(app: FastAPI, checker: FakeHealthChecker) -> None:
    app.dependency_overrides[get_health_checker] = lambda: checker
