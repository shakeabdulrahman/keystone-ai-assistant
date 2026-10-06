from fastapi import FastAPI
from httpx import AsyncClient

from tests.conftest import FakeHealthChecker, use_health_checker


async def test_healthz_is_ok(client: AsyncClient) -> None:
    response = await client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


async def test_readyz_ready_when_database_and_pgvector_ok(
    app: FastAPI, client: AsyncClient
) -> None:
    use_health_checker(app, FakeHealthChecker())
    response = await client.get("/readyz")
    assert response.status_code == 200
    assert response.json() == {"status": "ready", "checks": {"database": "ok", "pgvector": "0.8.0"}}


async def test_readyz_not_ready_when_pgvector_missing(app: FastAPI, client: AsyncClient) -> None:
    use_health_checker(app, FakeHealthChecker({"database": "ok", "pgvector": "missing"}))
    response = await client.get("/readyz")
    assert response.status_code == 503
    assert response.json()["status"] == "not_ready"


async def test_readyz_hides_database_error_details(app: FastAPI, client: AsyncClient) -> None:
    secret_error = ConnectionError("password=hunter2 host=10.0.0.5")
    use_health_checker(app, FakeHealthChecker(error=secret_error))
    response = await client.get("/readyz")
    assert response.status_code == 503
    assert response.json() == {"status": "not_ready", "checks": {"database": "error"}}
    assert "hunter2" not in response.text


async def test_meta_returns_service_info(client: AsyncClient) -> None:
    response = await client.get("/v1/meta")
    assert response.status_code == 200
    assert response.json() == {
        "name": "keystone",
        "version": "0.1.0",
        "environment": "test",
        "api_version": "v1",
    }
