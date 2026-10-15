"""Token verification: real RS256 signatures against a locally generated key pair."""

import time
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from google.auth import crypt, jwt
from httpx import ASGITransport, AsyncClient

from app.core.auth import DevTokenVerifier, FirebaseTokenVerifier, InvalidTokenError, Principal
from tests.conftest import Harness, as_user, build_test_app

PROJECT = "keystone-test"


def _key_and_cert() -> tuple[bytes, str]:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test")])
    now = datetime.now(UTC)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(1)
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=1))
        .sign(key, hashes.SHA256())
    )
    pem_key = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    return pem_key, cert.public_bytes(serialization.Encoding.PEM).decode()


PRIVATE_KEY, CERT = _key_and_cert()
OTHER_KEY, _ = _key_and_cert()


class StaticCerts:
    async def get(self) -> dict[str, str]:
        return {"kid-1": CERT}


def make_token(key: bytes = PRIVATE_KEY, **overrides: Any) -> str:
    now = int(time.time())
    claims = {
        "iss": f"https://securetoken.google.com/{PROJECT}",
        "aud": PROJECT,
        "sub": "firebase-uid-123",
        "iat": now,
        "exp": now + 3600,
        "email": "alice@example.com",
        "name": "Alice",
    }
    claims.update(overrides)
    signer = crypt.RSASigner.from_string(key, key_id="kid-1")
    return jwt.encode(signer, claims).decode()  # type: ignore[no-untyped-call]


@pytest.fixture
def verifier() -> FirebaseTokenVerifier:
    return FirebaseTokenVerifier(PROJECT, certs=StaticCerts())


async def test_valid_firebase_token(verifier: FirebaseTokenVerifier) -> None:
    principal = await verifier.verify(make_token())
    assert principal == Principal("firebase-uid-123", "alice@example.com", "Alice")


@pytest.mark.parametrize(
    "token_kwargs",
    [
        {"aud": "someone-elses-project"},
        {"iss": "https://securetoken.google.com/someone-elses-project"},
        {"exp": int(time.time()) - 60, "iat": int(time.time()) - 3700},
        {"sub": ""},
    ],
    ids=["wrong-audience", "wrong-issuer", "expired", "no-subject"],
)
async def test_rejects_bad_claims(
    verifier: FirebaseTokenVerifier, token_kwargs: dict[str, Any]
) -> None:
    with pytest.raises(InvalidTokenError):
        await verifier.verify(make_token(**token_kwargs))


async def test_rejects_token_signed_by_another_key(verifier: FirebaseTokenVerifier) -> None:
    with pytest.raises(InvalidTokenError):
        await verifier.verify(make_token(key=OTHER_KEY))


@pytest.mark.parametrize("token", [None, "", "garbage", "a.b.c"])
async def test_rejects_missing_or_malformed(verifier: FirebaseTokenVerifier, token: str) -> None:
    with pytest.raises(InvalidTokenError):
        await verifier.verify(token)


async def test_dev_verifier() -> None:
    dev = DevTokenVerifier()
    assert (await dev.verify("dev:bob:bob@example.com")) == Principal("bob", "bob@example.com")
    assert (await dev.verify(None)).uid == "dev-user"
    with pytest.raises(InvalidTokenError):
        await dev.verify(make_token())  # real JWTs aren't accepted in dev mode


@pytest.fixture
async def client() -> AsyncIterator[tuple[AsyncClient, Harness]]:
    harness = build_test_app()
    transport = ASGITransport(app=harness.app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c, harness


async def test_session_creates_user_then_reuses_it(client: tuple[AsyncClient, Harness]) -> None:
    c, harness = client
    first = await c.post("/v1/auth/session", headers=as_user("u1", "u1@example.com"))
    second = await c.post("/v1/auth/session", headers=as_user("u1", "u1@example.com"))
    assert first.status_code == 200
    assert first.json()["id"] == second.json()["id"]
    assert first.json()["email"] == "u1@example.com"
    assert len(harness.users.users) == 1


async def test_401_has_problem_body_and_www_authenticate(
    client: tuple[AsyncClient, Harness],
) -> None:
    c, _ = client
    response = await c.get("/v1/me", headers={"Authorization": "Bearer nope"})
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json()["code"] == "unauthorized"


async def test_delete_me_removes_account(client: tuple[AsyncClient, Harness]) -> None:
    c, harness = client
    await c.post("/v1/auth/session", headers=as_user("leaver"))
    response = await c.delete("/v1/me", headers=as_user("leaver"))
    assert response.status_code == 204
    assert harness.users.users == {}
