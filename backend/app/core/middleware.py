"""Request context middleware: correlation ID + access log.

Written as pure ASGI middleware (not Starlette's BaseHTTPMiddleware) so it never
buffers response bodies. That matters from Phase 1, when chat responses stream.
"""

import json
import logging
import re
import time
import uuid

from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.logging import request_id_var

REQUEST_ID_HEADER = "x-request-id"
_VALID_REQUEST_ID = re.compile(r"^[A-Za-z0-9\-]{8,64}$")

logger = logging.getLogger("keystone.access")


class RequestContextMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request_id = self._incoming_request_id(scope) or uuid.uuid4().hex
        token = request_id_var.set(request_id)
        started = time.perf_counter()
        status_code = 500
        response_started = False

        async def send_wrapper(message: Message) -> None:
            nonlocal status_code, response_started
            if message["type"] == "http.response.start":
                response_started = True
                status_code = message["status"]
                MutableHeaders(scope=message)[REQUEST_ID_HEADER] = request_id
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception as exc:
            # Last line of defence: log the error, return a generic problem+json and
            # never leak stack traces or internals to the client.
            logger.exception("unhandled_error", extra={"error_type": type(exc).__name__})
            if response_started:
                raise
            await self._send_internal_error(send_wrapper, request_id)
        finally:
            logger.info(
                "request",
                extra={
                    "method": scope["method"],
                    "path": scope["path"],  # path only: query strings may carry user data
                    "status": status_code,
                    "duration_ms": round((time.perf_counter() - started) * 1000, 1),
                },
            )
            request_id_var.reset(token)

    @staticmethod
    async def _send_internal_error(send: Send, request_id: str) -> None:
        body = json.dumps(
            {
                "type": "https://keystone.dev/problems/internal_error",
                "title": "Something went wrong",
                "status": 500,
                "code": "internal_error",
                "request_id": request_id,
            }
        ).encode()
        await send(
            {
                "type": "http.response.start",
                "status": 500,
                "headers": [
                    (b"content-type", b"application/problem+json"),
                    (b"content-length", str(len(body)).encode()),
                ],
            }
        )
        await send({"type": "http.response.body", "body": body})

    @staticmethod
    def _incoming_request_id(scope: Scope) -> str | None:
        """Accept a client-supplied ID only if it is well-formed (prevents log injection)."""
        for name, value in scope["headers"]:
            if name.decode("latin-1") == REQUEST_ID_HEADER:
                candidate = value.decode("latin-1")
                return candidate if _VALID_REQUEST_ID.match(candidate) else None
        return None
