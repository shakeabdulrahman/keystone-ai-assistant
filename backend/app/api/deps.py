"""Shared FastAPI dependencies. Tests override these instead of patching globals."""

import logging
from typing import Annotated, Protocol

from fastapi import Depends, HTTPException, Request

from app.core.auth import InvalidTokenError, Principal, TokenVerifier
from app.core.config import Settings
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


async def get_current_user(
    principal: Annotated[Principal, Depends(get_principal)],
    users: Annotated[UserRepository, Depends(get_users)],
) -> UserRecord:
    """The caller's own user record, created on first sight."""
    return await users.get_or_create(principal.uid, principal.email, principal.name)


CurrentUser = Annotated[UserRecord, Depends(get_current_user)]
