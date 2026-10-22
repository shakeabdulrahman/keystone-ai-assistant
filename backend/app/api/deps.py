"""Shared FastAPI dependencies. Tests override these instead of patching globals."""

import logging
from typing import Annotated, Protocol

from fastapi import Depends, HTTPException, Request

from app.core.app_check import AppCheckError, AppCheckVerifier
from app.core.auth import (
    InvalidTokenError,
    Principal,
    TokenVerifier,
    VerificationUnavailableError,
)
from app.core.config import Settings
from app.core.metrics import RATE_LIMITED
from app.core.rate_limit import RateLimitedError, RateLimiter, Rule
from app.repositories.base import ConversationRepository, UserRecord, UserRepository
from app.services.chat_service import ChatService

logger = logging.getLogger("keystone.auth")


class HealthChecker(Protocol):
    async def check(self) -> dict[str, str]: ...


def get_settings_dep(request: Request) -> Settings:
    settings: Settings = request.app.state.settings
    return settings


def get_health_checker(request: Request) -> HealthChecker:
    checker: HealthChecker = request.app.state.db
    return checker


def get_chat_service(request: Request) -> ChatService:
    service: ChatService = request.app.state.chat_service
    return service


def get_users(request: Request) -> UserRepository:
    users: UserRepository = request.app.state.users
    return users


def get_conversations(request: Request) -> ConversationRepository:
    conversations: ConversationRepository = request.app.state.conversations
    return conversations


def _bearer_token(request: Request) -> str | None:
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    return token.strip() if scheme.lower() == "bearer" and token.strip() else None


async def get_principal(request: Request) -> Principal:
    verifier: TokenVerifier = request.app.state.token_verifier
    try:
        return await verifier.verify(_bearer_token(request))
    except InvalidTokenError as exc:
        # The reason goes to logs only; clients get a generic 401.
        logger.info("auth_rejected", extra={"reason": str(exc)})
        raise HTTPException(
            status_code=401,
            detail="Sign in again to continue",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


class AppCheckRejectedError(Exception):
    """APP_CHECK_MODE=enforce and the request didn't come from a verified app."""


async def check_app_check(request: Request) -> None:
    """Runs before sign-in verification on every authenticated endpoint."""
    settings: Settings = request.app.state.settings
    if settings.app_check_mode == "off":
        return
    verifier: AppCheckVerifier = request.app.state.app_check
    try:
        await verifier.verify(request.headers.get("x-firebase-appcheck"))
    except AppCheckError as exc:
        logger.warning(
            "app_check_failed", extra={"reason": str(exc), "mode": settings.app_check_mode}
        )
        if settings.app_check_mode == "enforce":
            raise AppCheckRejectedError from exc
    except VerificationUnavailableError:
        if settings.app_check_mode == "enforce":
            raise  # 503: can't tell, so don't let it through
        # monitor: we only watch, so a key outage mustn't break the app


def enforce_rate_limit(request: Request, rule_name: str, key: str) -> None:
    limiter: RateLimiter | None = request.app.state.rate_limiter
    if limiter is None:
        return
    rule: Rule = request.app.state.rate_rules[rule_name]
    decision = limiter.hit(rule, key)
    if not decision.allowed:
        logger.info("rate_limited", extra={"rule": rule.name})
        RATE_LIMITED.labels(rule.name).inc()
        raise RateLimitedError(rule, decision.retry_after_s)


async def get_current_user(
    request: Request,
    _app_check: Annotated[None, Depends(check_app_check)],
    principal: Annotated[Principal, Depends(get_principal)],
    users: Annotated[UserRepository, Depends(get_users)],
) -> UserRecord:
    """The caller's own user record, created on first sight. Every authenticated request
    also counts against the user's general rate limit."""
    user = await users.get_or_create(principal.uid, principal.email, principal.name)
    enforce_rate_limit(request, "user", str(user.id))
    return user


CurrentUser = Annotated[UserRecord, Depends(get_current_user)]


async def limit_chat(request: Request, user: CurrentUser) -> None:
    """Stricter limit for chat: each message can mean several model calls."""
    enforce_rate_limit(request, "chat", str(user.id))


async def limit_actions(request: Request, user: CurrentUser) -> None:
    enforce_rate_limit(request, "actions", str(user.id))
