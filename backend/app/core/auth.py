"""Verifying who is calling.

The app signs users in with Firebase Authentication and sends the Firebase ID token
(a JWT) as `Authorization: Bearer <token>`. We verify it ourselves:
  - signature: RS256 against Google's published public certificates (cached),
  - audience = our Firebase project ID, issuer = securetoken.google.com/<project>,
  - expiry/issued-at (checked by google-auth), and a non-empty subject (the user ID).
No service-account secret is needed for this, only the project ID.
"""

import asyncio
import logging
import time
from dataclasses import dataclass
from typing import Any, Protocol

import httpx
from google.auth import exceptions as google_exceptions
from google.auth import jwt

logger = logging.getLogger("keystone.auth")

FIREBASE_CERTS_URL = (
    "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"
)


@dataclass(frozen=True)
class Principal:
    """An authenticated identity, before it is mapped to our own user record."""

    uid: str
    email: str | None = None
    name: str | None = None


class InvalidTokenError(Exception):
    """Token missing, malformed, expired or not issued for this project. Message is internal."""


class TokenVerifier(Protocol):
    async def verify(self, token: str | None) -> Principal: ...


class CertificateSource(Protocol):
    async def get(self) -> dict[str, str]: ...


class GoogleCertificateSource:
    """Fetches Google's signing certificates and caches them for as long as Google allows."""

    def __init__(self, url: str = FIREBASE_CERTS_URL) -> None:
        self._url = url
        self._certs: dict[str, str] = {}
        self._expires_at = 0.0
        self._lock = asyncio.Lock()

    async def get(self) -> dict[str, str]:
        if time.monotonic() < self._expires_at:
            return self._certs
        async with self._lock:  # one refresh at a time, even under load
            if time.monotonic() >= self._expires_at:
                async with httpx.AsyncClient(timeout=5.0) as client:
                    response = await client.get(self._url)
                    response.raise_for_status()
                self._certs = response.json()
                self._expires_at = time.monotonic() + _max_age(response.headers)
        return self._certs


def _max_age(headers: httpx.Headers) -> float:
    for part in headers.get("cache-control", "").split(","):
        name, _, value = part.strip().partition("=")
        if name == "max-age" and value.isdigit():
            return float(value)
    return 3600.0


class FirebaseTokenVerifier:
    def __init__(self, project_id: str, certs: CertificateSource | None = None) -> None:
        self._project_id = project_id
        self._issuer = f"https://securetoken.google.com/{project_id}"
        self._certs = certs or GoogleCertificateSource()

    async def verify(self, token: str | None) -> Principal:
        if not token:
            raise InvalidTokenError("missing token")
        certs = await self._certs.get()
        try:
            claims: dict[str, Any] = jwt.decode(  # type: ignore[no-untyped-call]
                token, certs=certs, audience=self._project_id, clock_skew_in_seconds=10
            )
        except (ValueError, google_exceptions.GoogleAuthError) as exc:
            raise InvalidTokenError(f"invalid token: {type(exc).__name__}") from exc
        if claims.get("iss") != self._issuer:
            raise InvalidTokenError("wrong issuer")
        uid = claims.get("sub")
        if not isinstance(uid, str) or not uid:
            raise InvalidTokenError("missing subject")
        return Principal(uid=uid, email=claims.get("email"), name=claims.get("name"))


class DevTokenVerifier:
    """Local development only. Accepts `dev:<uid>` or `dev:<uid>:<email>`; no token = 'dev-user'.

    Settings refuse this mode in production, so it can't be enabled by accident there.
    """

    async def verify(self, token: str | None) -> Principal:
        if not token:
            return Principal(uid="dev-user", email="dev@keystone.local", name="Dev User")
        prefix, _, rest = token.partition(":")
        if prefix != "dev" or not rest:
            raise InvalidTokenError("dev mode expects 'dev:<uid>' tokens")
        uid, _, email = rest.partition(":")
        return Principal(uid=uid, email=email or None, name=None)
