import pytest
from fastapi import FastAPI
from httpx import AsyncClient
from pydantic import BaseModel, Field

from app.core.config import Settings
from app.main import create_app


async def test_generates_request_id_when_missing(client: AsyncClient) -> None:
    response = await client.get("/healthz")
    assert len(response.headers["x-request-id"]) == 32


async def test_echoes_valid_client_request_id(client: AsyncClient) -> None:
    response = await client.get("/healthz", headers={"X-Request-ID": "abc-12345-def"})
    assert response.headers["x-request-id"] == "abc-12345-def"


async def test_replaces_malformed_request_id(client: AsyncClient) -> None:
    response = await client.get("/healthz", headers={"X-Request-ID": "bad id\nINJECTED"})
    assert response.headers["x-request-id"] != "bad id\nINJECTED"
    assert len(response.headers["x-request-id"]) == 32


async def test_unknown_route_returns_problem_json(client: AsyncClient) -> None:
    response = await client.get("/nope")
    assert response.status_code == 404
    assert response.headers["content-type"] == "application/problem+json"
    body = response.json()
    assert body["code"] == "not_found"
    assert body["request_id"] == response.headers["x-request-id"]


class Payload(BaseModel):
    message: str = Field(min_length=1, max_length=10)


@pytest.fixture
def app_with_test_routes(settings: Settings) -> FastAPI:
    app = create_app(settings)

    @app.post("/echo")
    async def echo(payload: Payload) -> Payload:
        return payload

    @app.get("/boom")
    async def boom() -> None:
        raise RuntimeError("internal secret detail")

    return app


async def test_validation_error_does_not_echo_input(app_with_test_routes: FastAPI) -> None:
    from httpx import ASGITransport, AsyncClient

    async with AsyncClient(
        transport=ASGITransport(app=app_with_test_routes), base_url="http://test"
    ) as c:
        response = await c.post("/echo", json={"message": "way-too-long-SECRET"})
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "validation_error"
    assert body["errors"][0]["loc"] == ["body", "message"]
    assert "SECRET" not in response.text


async def test_unhandled_error_is_generic_500_with_request_id(
    app_with_test_routes: FastAPI,
) -> None:
    from httpx import ASGITransport, AsyncClient

    async with AsyncClient(
        transport=ASGITransport(app=app_with_test_routes, raise_app_exceptions=False),
        base_url="http://test",
    ) as c:
        response = await c.get("/boom")
    assert response.status_code == 500
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["code"] == "internal_error"
    assert response.json()["request_id"] == response.headers["x-request-id"]
    assert "internal secret detail" not in response.text
