"""Uniform error responses using RFC 9457 `application/problem+json`.

Every error has a stable machine-readable `code` that the mobile app maps to its
own error types. Unexpected errors never leak internals to the client.
"""

from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.auth import VerificationUnavailableError
from app.core.logging import request_id_var
from app.core.rate_limit import RateLimitedError

PROBLEM_JSON = "application/problem+json"

_STATUS_CODES = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
    409: "conflict",
    413: "payload_too_large",
    422: "validation_error",
    429: "rate_limited",
    503: "service_unavailable",
}


def problem(
    status: int,
    code: str,
    title: str,
    detail: str | None = None,
    headers: dict[str, str] | None = None,
    **extra: Any,
) -> JSONResponse:
    body: dict[str, Any] = {
        "type": f"https://keystone.dev/problems/{code}",
        "title": title,
        "status": status,
        "code": code,
        "request_id": request_id_var.get(),
    }
    if detail:
        body["detail"] = detail
    body.update(extra)
    return JSONResponse(body, status_code=status, media_type=PROBLEM_JSON, headers=headers)


async def _http_exception(_: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, StarletteHTTPException)
    code = _STATUS_CODES.get(exc.status_code, "http_error")
    detail = exc.detail if isinstance(exc.detail, str) else None
    headers = getattr(exc, "headers", None)  # e.g. WWW-Authenticate on 401
    return problem(exc.status_code, code, code.replace("_", " ").capitalize(), detail, headers)


async def _validation_exception(_: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, RequestValidationError)
    # Report where and why, but never echo the submitted values back.
    errors = [
        {"loc": [str(p) for p in err["loc"]], "msg": err["msg"], "type": err["type"]}
        for err in exc.errors()
    ]
    return problem(422, "validation_error", "Request validation failed", errors=errors)


async def _rate_limited(_: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, RateLimitedError)
    return problem(
        429,
        "rate_limited",
        "Too many requests",
        f"Too many requests. Try again in {exc.retry_after_s} seconds.",
        headers={"Retry-After": str(exc.retry_after_s)},
        retry_after_s=exc.retry_after_s,
    )


async def _verification_unavailable(_: Request, exc: Exception) -> JSONResponse:
    return problem(
        503,
        "auth_unavailable",
        "Sign-in check unavailable",
        "We couldn't verify your sign-in just now. Try again in a moment.",
        headers={"Retry-After": "5"},
    )


def register_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(StarletteHTTPException, _http_exception)
    app.add_exception_handler(VerificationUnavailableError, _verification_unavailable)
    app.add_exception_handler(RateLimitedError, _rate_limited)
    app.add_exception_handler(RequestValidationError, _validation_exception)
    # Unexpected exceptions are handled in RequestContextMiddleware so the response
    # still carries the request ID.
