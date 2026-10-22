"""Sending push notifications.

`FcmPushSender` uses the Firebase Cloud Messaging HTTP v1 API (free). It authenticates
with a service-account key: we sign a short JWT with the key and exchange it for a
one-hour OAuth token, cached until shortly before it expires. No Firebase Admin SDK.

`LogPushSender` is used when no key is configured (local development, tests): it only
logs that a push would have been sent.

Privacy: push payloads travel through Google's (and Apple's) servers and appear on the
lock screen, so they carry *no HR details*: only a generic sentence plus a kind and a
reference the app uses to fetch the real data after the user opens it.
"""

import json
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import httpx
from google.auth import crypt, jwt

logger = logging.getLogger("keystone.push")

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
TOKEN_URL = "https://oauth2.googleapis.com/token"  # noqa: S105 (a URL, not a password)


@dataclass(frozen=True)
class PushMessage:
    title: str  # generic: shown on the lock screen
    body: str
    data: dict[str, str] = field(default_factory=dict)  # e.g. {"kind": "leave", "ref": "LR-1050"}


@dataclass(frozen=True)
class PushResult:
    sent: int
    stale_tokens: list[str]  # the app was uninstalled or the token rotated: forget them


class PushSender(Protocol):
    async def send(self, tokens: list[str], message: PushMessage) -> PushResult: ...


class LogPushSender:
    """Development stand-in: logs instead of sending. Never logs the message content."""

    def __init__(self) -> None:
        self.sent: list[tuple[list[str], PushMessage]] = []  # inspected by tests

    async def send(self, tokens: list[str], message: PushMessage) -> PushResult:
        self.sent.append((tokens, message))
        logger.info("push_logged", extra={"devices": len(tokens), "kind": message.data.get("kind")})
        return PushResult(sent=len(tokens), stale_tokens=[])


class FcmPushSender:
    def __init__(
        self, service_account: dict[str, Any], *, client: httpx.AsyncClient | None = None
    ) -> None:
        self._account = service_account
        self._project = str(service_account["project_id"])
        self._signer = crypt.RSASigner.from_service_account_info(service_account)  # type: ignore[no-untyped-call]
        self._client = client or httpx.AsyncClient(timeout=10.0)
        self._token: str | None = None
        self._token_expires = 0.0

    @classmethod
    def from_file(cls, path: str) -> "FcmPushSender":
        return cls(json.loads(Path(path).read_text(encoding="utf-8")))

    async def send(self, tokens: list[str], message: PushMessage) -> PushResult:
        sent, stale = 0, []
        for token in tokens:
            outcome = await self._send_one(token, message)
            if outcome == "ok":
                sent += 1
            elif outcome == "stale":
                stale.append(token)
        logger.info(
            "push_sent", extra={"sent": sent, "stale": len(stale), "kind": message.data.get("kind")}
        )
        return PushResult(sent, stale)

    async def _send_one(self, token: str, message: PushMessage) -> str:
        body = {
            "message": {
                "token": token,
                "notification": {"title": message.title, "body": message.body},
                "data": message.data,
                "android": {"priority": "high", "notification": {"channel_id": "hr_updates"}},
            }
        }
        url = f"https://fcm.googleapis.com/v1/projects/{self._project}/messages:send"
        try:
            response = await self._client.post(
                url, json=body, headers={"Authorization": f"Bearer {await self._access_token()}"}
            )
        except httpx.HTTPError as exc:
            logger.warning("push_failed", extra={"error_type": type(exc).__name__})
            return "error"
        if response.status_code == 200:
            return "ok"
        if (
            response.status_code == 404
            or "UNREGISTERED" in response.text
            or (response.status_code == 400 and "registration token" in response.text.lower())
        ):
            return "stale"
        if response.status_code in (401, 403):
            self._token = None  # maybe revoked: mint a new one next time
        logger.warning("push_rejected", extra={"status": response.status_code})
        return "error"

    async def _access_token(self) -> str:
        if self._token and time.time() < self._token_expires - 60:
            return self._token
        now = int(time.time())
        assertion = jwt.encode(  # type: ignore[no-untyped-call]
            self._signer,
            {
                "iss": self._account["client_email"],
                "scope": FCM_SCOPE,
                "aud": TOKEN_URL,
                "iat": now,
                "exp": now + 3600,
            },
        )
        response = await self._client.post(
            TOKEN_URL,
            data={
                "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion": assertion.decode(),
            },
        )
        response.raise_for_status()
        payload = response.json()
        self._token = str(payload["access_token"])
        self._token_expires = now + float(payload.get("expires_in", 3600))
        return self._token

    async def aclose(self) -> None:
        await self._client.aclose()
