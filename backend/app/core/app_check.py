"""Firebase App Check: is this request coming from *our* app on a genuine device?

Sign-in proves *who* the user is; App Check proves *what* is calling. The app gets a
short-lived App Check token (Play Integrity on real devices, a registered debug token in
development) and sends it as `X-Firebase-AppCheck`. We verify it like the ID token:
RS256 signature against Google's published App Check keys (JWKS, cached), issuer
`https://firebaseappcheck.googleapis.com/<project number>`, audience containing
`projects/<project number>`, and not expired.

Modes (APP_CHECK_MODE):
  off      no checks (local development)
  monitor  verify and log failures, but let requests through (roll-out step: see how many
           real clients would be rejected before enforcing)
  enforce  reject requests without a valid token with 401 `app_check_failed`
"""

import asyncio
import base64
import logging
import time
from typing import Any, Literal, Protocol

import httpx
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicNumbers
from google.auth import exceptions as google_exceptions
from google.auth import jwt

from app.core.auth import stale_or_raise

logger = logging.getLogger("keystone.app_check")

APP_CHECK_JWKS_URL = "https://firebaseappcheck.googleapis.com/v1/jwks"
AppCheckMode = Literal["off", "monitor", "enforce"]


class AppCheckError(Exception):
    """Missing or invalid App Check token. The message is for logs only."""


class KeySource(Protocol):
    async def get(self) -> dict[str, str]:
        """kid → PEM public key."""
        ...


class AppCheckJwks:
    """Google's App Check signing keys, cached (they rotate; Google suggests ~6 hours)."""

    def __init__(self, url: str = APP_CHECK_JWKS_URL, ttl_s: float = 6 * 3600) -> None:
        self._url = url
        self._ttl_s = ttl_s
        self._keys: dict[str, str] = {}
        self._expires = 0.0
        self._lock = asyncio.Lock()

    async def get(self) -> dict[str, str]:
        if time.monotonic() < self._expires:
            return self._keys
        async with self._lock:
            if time.monotonic() >= self._expires:
                try:
                    async with httpx.AsyncClient(timeout=5.0) as client:
                        response = await client.get(self._url)
                        response.raise_for_status()
                    self._keys = jwks_to_pem(response.json())
                    self._expires = time.monotonic() + self._ttl_s
                except (httpx.HTTPError, ValueError, KeyError) as exc:
                    self._keys, self._expires = stale_or_raise(self._keys, exc, "app_check")
        return self._keys


def jwks_to_pem(jwks: dict[str, Any]) -> dict[str, str]:
    keys = {}
    for jwk in jwks.get("keys", []):
        if jwk.get("kty") != "RSA" or "kid" not in jwk:
            continue
        numbers = RSAPublicNumbers(_b64_int(jwk["e"]), _b64_int(jwk["n"]))
        pem = numbers.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        keys[jwk["kid"]] = pem.decode()
    return keys


def _b64_int(value: str) -> int:
    padded = value + "=" * (-len(value) % 4)
    return int.from_bytes(base64.urlsafe_b64decode(padded), "big")


class AppCheckVerifier:
    def __init__(
        self,
        project_number: str,
        *,
        keys: KeySource | None = None,
        allowed_app_ids: frozenset[str] = frozenset(),
    ) -> None:
        self._audience = f"projects/{project_number}"
        self._issuer = f"https://firebaseappcheck.googleapis.com/{project_number}"
        self._keys = keys or AppCheckJwks()
        self._allowed_app_ids = allowed_app_ids  # empty: any app registered in the project

    async def verify(self, token: str | None) -> str:
        """Returns the verified app ID (`sub`)."""
        if not token:
            raise AppCheckError("missing token")
        try:
            # Audience is a list in App Check tokens; google-auth only compares strings,
            # so the signature and expiry are checked here and the audience below.
            claims: dict[str, Any] = jwt.decode(  # type: ignore[no-untyped-call]
                token, certs=await self._keys.get(), audience=None, clock_skew_in_seconds=10
            )
        except (ValueError, google_exceptions.GoogleAuthError) as exc:
            raise AppCheckError(f"invalid token: {type(exc).__name__}") from exc
        if claims.get("iss") != self._issuer:
            raise AppCheckError("wrong issuer")
        audience = claims.get("aud")
        audiences = audience if isinstance(audience, list) else [audience]
        if self._audience not in audiences:
            raise AppCheckError("wrong audience")
        app_id = claims.get("sub")
        if not isinstance(app_id, str) or not app_id:
            raise AppCheckError("missing app id")
        if self._allowed_app_ids and app_id not in self._allowed_app_ids:
            raise AppCheckError("app not allowed")
        return app_id
