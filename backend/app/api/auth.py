"""Session and profile endpoints."""

from datetime import datetime
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel

from app.api.deps import CurrentUser, get_principal, get_users
from app.core.auth import Principal
from app.repositories.base import UserRecord, UserRepository

router = APIRouter(prefix="/v1", tags=["auth"])


class ProfileOut(BaseModel):
    id: UUID
    email: str | None
    display_name: str | None
    role: str
    created_at: datetime

    @classmethod
    def of(cls, user: UserRecord) -> "ProfileOut":
        return cls(
            id=user.id,
            email=user.email,
            display_name=user.display_name,
            role=user.role,
            created_at=user.created_at,
        )


@router.post("/auth/session", response_model=ProfileOut)
async def start_session(
    user: CurrentUser,
    principal: Annotated[Principal, Depends(get_principal)],
    users: Annotated[UserRepository, Depends(get_users)],
) -> ProfileOut:
    """Called by the app right after sign-in: creates the user on first visit and keeps
    email/name in sync with the identity provider."""
    if (principal.email, principal.name) != (user.email, user.display_name):
        user = await users.update_profile(user.id, principal.email, principal.name)
    return ProfileOut.of(user)


@router.get("/me", response_model=ProfileOut)
async def me(user: CurrentUser) -> ProfileOut:
    return ProfileOut.of(user)


@router.delete("/me", status_code=204, response_class=Response)
async def delete_me(
    user: CurrentUser, users: Annotated[UserRepository, Depends(get_users)]
) -> Response:
    """Permanently deletes the user's account data: profile, conversations, messages."""
    await users.delete(user.id)
    return Response(status_code=204)
