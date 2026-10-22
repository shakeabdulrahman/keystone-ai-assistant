"""Hardening: headers, body limits, rate limits and Firebase App Check."""

import base64
import time
import uuid
from typing import Any

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from google.auth import crypt, jwt
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError

from app.ai.llm.fake import FakeLLMProvider
from app.core.app_check import AppCheckError, AppCheckVerifier, jwks_to_pem
from app.core.config import Settings
from app.core.rate_limit import InMemoryRateLimiter, Rule
from tests.conftest import Harness, as_user, build_test_app

ALICE, BOB = as_user("alice"), as_user("bob")


def client_for(harness: Harness) -> AsyncClient:
    return AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test")


def chat_body(text: str = "Hi") -> dict[str, str]:
    return {"client_message_id": str(uuid.uuid4()), "content": text}


async def test_every_response_has_security_headers_and_is_never_cached() -> None:
    async with client_for(build_test_app()) as c:
        for response in (await c.get("/v1/home", headers=ALICE), await c.get("/nope")):
            assert response.headers["x-content-type-options"] == "nosniff"
            assert response.headers["x-frame-options"] == "DENY"
            assert response.headers["referrer-policy"] == "no-referrer"
            assert response.headers["cache-control"] == "no-store"
            assert "default-src 'none'" in response.headers["content-security-policy"]
            assert "strict-transport-security" not in response.headers  # only in production


async def test_oversized_bodies_are_rejected_before_parsing() -> None:
    async with client_for(build_test_app()) as c:
        big = {"client_message_id": str(uuid.uuid4()), "content": "x" * 70_000}
        response = await c.post("/v1/chat", json=big, headers=ALICE)
        assert response.status_code == 413
        assert response.json()["code"] == "payload_too_large"

        async def chunks() -> Any:  # no Content-Length: counted as it arrives
            for _ in range(80):
                yield b"x" * 1024

        chunked = await c.post(
            "/v1/chat", content=chunks(), headers={**ALICE, "content-type": "application/json"}
        )
        assert chunked.status_code == 413


async def test_chat_is_rate_limited_per_user_with_retry_after() -> None:
    async with client_for(build_test_app(FakeLLMProvider("ok"))) as c:
        codes = [
            (await c.post("/v1/chat", json=chat_body(), headers=ALICE)).status_code
            for _ in range(6)
        ]
        assert codes == [200] * 5 + [429]  # burst of 5, then the bucket is empty

        limited = await c.post("/v1/chat", json=chat_body(), headers=ALICE)
        assert limited.status_code == 429
        assert limited.json()["code"] == "rate_limited"
        assert int(limited.headers["retry-after"]) >= 1
        assert limited.json()["retry_after_s"] == int(limited.headers["retry-after"])

        # Another user isn't affected, and reading history still works for Alice.
        assert (await c.post("/v1/chat", json=chat_body(), headers=BOB)).status_code == 200
        assert (await c.get("/v1/conversations", headers=ALICE)).status_code == 200


def test_token_bucket_refills_over_time() -> None:
    now = [1000.0]
    limiter = InMemoryRateLimiter(clock=lambda: now[0])
    rule = Rule("chat", per_minute=6, burst=2)  # one token every 10 seconds
    assert [limiter.hit(rule, "u").allowed for _ in range(3)] == [True, True, False]
    assert limiter.hit(rule, "u").retry_after_s == 10
    now[0] += 10
    assert limiter.hit(rule, "u").allowed
    assert not limiter.hit(rule, "u").allowed
    now[0] += 3600  # refills only up to the burst size
    assert [limiter.hit(rule, "u").allowed for _ in range(3)] == [True, True, False]


def test_limiter_memory_is_bounded() -> None:
    limiter = InMemoryRateLimiter(max_keys=100)
    rule = Rule("ip", per_minute=60, burst=1)
    for i in range(1000):
        limiter.hit(rule, f"10.0.{i // 256}.{i % 256}")
    assert len(limiter._buckets) == 100


async def test_ip_limit_applies_even_without_sign_in() -> None:
    settings_rate = Settings(
        app_env="test", log_level="WARNING", auth_mode="dev", rate_ip_per_minute=1
    )
    from app.main import create_app

    app = create_app(settings_rate)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        codes = [(await c.get("/v1/meta")).status_code for _ in range(105)]
        assert codes[:100] == [200] * 100 and codes[-1] == 429
        assert (await c.get("/healthz")).status_code == 200  # probes are never limited


# --- App Check ---------------------------------------------------------------------------

PROJECT_NUMBER = "123456789012"
APP_ID = "1:123456789012:android:abc123"


class Keys:
    def __init__(self) -> None:
        self.private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        numbers = self.private.public_key().public_numbers()

        def b64(n: int) -> str:
            raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
            return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

        self.jwks = {
            "keys": [
                {
                    "kty": "RSA",
                    "kid": "k1",
                    "alg": "RS256",
                    "n": b64(numbers.n),
                    "e": b64(numbers.e),
                }
            ]
        }
        self.pem = self.private.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ).decode()

    async def get(self) -> dict[str, str]:
        return jwks_to_pem(self.jwks)

    def token(self, **overrides: Any) -> str:
        now = int(time.time())
        claims = {
            "iss": f"https://firebaseappcheck.googleapis.com/{PROJECT_NUMBER}",
            "aud": [f"projects/{PROJECT_NUMBER}", "projects/keystone-hr"],
            "sub": APP_ID,
            "iat": now,
            "exp": now + 3600,
        } | overrides
        signer = crypt.RSASigner.from_string(self.pem, key_id="k1")
        return jwt.encode(signer, claims).decode()


KEYS = Keys()


async def test_app_check_accepts_a_genuine_token() -> None:
    verifier = AppCheckVerifier(PROJECT_NUMBER, keys=KEYS, allowed_app_ids=frozenset({APP_ID}))
    assert await verifier.verify(KEYS.token()) == APP_ID


@pytest.mark.parametrize(
    ("token", "reason"),
    [
        (None, "missing"),
        ("not-a-jwt", "invalid"),
        (KEYS.token(iss="https://evil.example/123"), "issuer"),
        (KEYS.token(aud=["projects/999"]), "audience"),
        (KEYS.token(exp=int(time.time()) - 3600, iat=int(time.time()) - 7200), "invalid"),
        (KEYS.token(sub="1:123456789012:android:someone-else"), "not allowed"),
        (Keys().token(), "invalid"),  # signed with a key Google never published
    ],
)
async def test_app_check_rejects_bad_tokens(token: str | None, reason: str) -> None:
    verifier = AppCheckVerifier(PROJECT_NUMBER, keys=KEYS, allowed_app_ids=frozenset({APP_ID}))
    with pytest.raises(AppCheckError, match=reason):
        await verifier.verify(token)


def app_with_app_check(mode: str) -> Harness:
    harness = build_test_app()
    harness.app.state.settings = Settings(
        app_env="test", auth_mode="dev", app_check_mode=mode, firebase_project_number=PROJECT_NUMBER
    )
    harness.app.state.app_check = AppCheckVerifier(PROJECT_NUMBER, keys=KEYS)
    return harness


async def test_monitor_mode_lets_requests_through() -> None:
    async with client_for(app_with_app_check("monitor")) as c:
        assert (await c.get("/v1/home", headers=ALICE)).status_code == 200


async def test_enforce_mode_rejects_unverified_apps_with_a_clear_code() -> None:
    async with client_for(app_with_app_check("enforce")) as c:
        rejected = await c.get("/v1/home", headers=ALICE)
        assert rejected.status_code == 403
        assert rejected.json()["code"] == "app_check_failed"
        ok = await c.get("/v1/home", headers={**ALICE, "X-Firebase-AppCheck": KEYS.token()})
        assert ok.status_code == 200
        # Probes and metadata don't need it (load balancers have no App Check token).
        assert (await c.get("/healthz")).status_code == 200


def test_app_check_needs_the_project_number() -> None:
    with pytest.raises(ValidationError, match="FIREBASE_PROJECT_NUMBER"):
        Settings(app_env="test", app_check_mode="enforce")


def test_client_ip_uses_rightmost_trusted_hop() -> None:
    from app.core.rate_limit import client_ip

    scope = {
        "client": ("10.0.0.5", 1234),
        "headers": [(b"x-forwarded-for", b"6.6.6.6, 203.0.113.9")],  # 6.6.6.6 is forged
    }
    assert client_ip(scope, 0) == "10.0.0.5"
    assert client_ip(scope, 1) == "203.0.113.9"
    assert client_ip({"client": ("10.0.0.5", 1), "headers": []}, 1) == "10.0.0.5"


async def test_key_outage_is_503_not_401_and_stale_keys_are_used() -> None:
    from app.core.auth import GoogleCertificateSource, VerificationUnavailableError

    source = GoogleCertificateSource("http://127.0.0.1:9/unreachable")
    with pytest.raises(VerificationUnavailableError):
        await source.get()

    source._certs = {"kid": "PEM"}  # previously fetched keys
    source._expires_at = 0.0
    assert await source.get() == {"kid": "PEM"}  # stale-if-error


async def test_key_outage_maps_to_503() -> None:
    import httpx
    from fastapi import FastAPI

    from app.core.auth import VerificationUnavailableError
    from app.core.errors import register_error_handlers

    app = FastAPI()
    register_error_handlers(app)

    @app.get("/x")
    async def boom() -> None:
        raise VerificationUnavailableError("keys")

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
        response = await client.get("/x")
    assert response.status_code == 503
    assert response.json()["code"] == "auth_unavailable"
    assert response.headers["retry-after"] == "5"
