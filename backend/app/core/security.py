"""HTTP hardening: security headers, no caching of personal data, request size limits.

Pure ASGI middleware, like RequestContextMiddleware, so streaming responses (chat) are
never buffered.
"""

import json

from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.logging import request_id_var

_HEADERS = {
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "referrer-policy": "no-referrer",
    # An API returns no HTML: forbid everything a browser might try to load from it.
    "content-security-policy": "default-src 'none'; frame-ancestors 'none'",
    "permissions-policy": "camera=(), microphone=(), geolocation=()",
}


class SecurityHeadersMiddleware:
    """Adds browser-hardening headers and `Cache-Control: no-store` (HR data must never
    sit in a shared or on-disk HTTP cache). HSTS only when serving over HTTPS in production."""

    def __init__(self, app: ASGIApp, *, hsts: bool = False) -> None:
        self.app = app
        self.hsts = hsts

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        async def send_wrapper(message: Message) -> None:
            if message["type"] == "http.response.start":
                headers = MutableHeaders(scope=message)
                for name, value in _HEADERS.items():
                    headers.setdefault(name, value)
                headers.setdefault("cache-control", "no-store")
                if self.hsts:
                    headers.setdefault(
                        "strict-transport-security", "max-age=31536000; includeSubDomains"
                    )
            await send(message)

        await self.app(scope, receive, send_wrapper)


class BodySizeLimitMiddleware:
    """Rejects request bodies over `max_bytes` with 413, before the app reads them.

    Declared sizes (Content-Length) are rejected up front; bodies without one (chunked
    uploads) are counted as they arrive and cut off at the limit.
    """

    def __init__(self, app: ASGIApp, *, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        declared = _content_length(scope)
        if declared is not None and declared > self.max_bytes:
            await _send_413(send)
            return

        received = 0
        too_large = False

        async def limited_receive() -> Message:
            nonlocal received, too_large
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_bytes:
                    too_large = True
                    # Pretend the client went away: the app stops reading, we answer 413.
                    return {"type": "http.disconnect"}
            return message

        response_started = False

        async def guarded_send(message: Message) -> None:
            nonlocal response_started
            if too_large:
                return  # we send our own 413 below
            response_started = response_started or message["type"] == "http.response.start"
            await send(message)

        try:
            await self.app(scope, limited_receive, guarded_send)
        except Exception:
            if not too_large:
                raise
            # The app noticed the cut-off body (e.g. ClientDisconnect): answer 413 below.
        if too_large and not response_started:
            await _send_413(send)


def _content_length(scope: Scope) -> int | None:
    for name, value in scope["headers"]:
        if name == b"content-length":
            try:
                return int(value)
            except ValueError:
                return None
    return None


async def _send_413(send: Send) -> None:
    body = json.dumps(
        {
            "type": "https://keystone.dev/problems/payload_too_large",
            "title": "Payload too large",
            "status": 413,
            "code": "payload_too_large",
            "request_id": request_id_var.get(),
        }
    ).encode()
    await send(
        {
            "type": "http.response.start",
            "status": 413,
            "headers": [
                (b"content-type", b"application/problem+json"),
                (b"content-length", str(len(body)).encode()),
            ],
        }
    )
    await send({"type": "http.response.body", "body": body})
