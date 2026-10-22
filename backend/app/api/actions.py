"""Confirming or cancelling an action the assistant proposed.

These endpoints are the only way an action runs. The model has no tool for confirming,
so a confused or manipulated model can propose changes but never make them.
"""

from datetime import datetime
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from app.api.deps import AppCheckRejectedError, CurrentUser, limit_actions
from app.core.errors import problem
from app.repositories.base import ActionRecord, ActionRepository
from app.services.actions import ActionError, ActionService

router = APIRouter(prefix="/v1/actions", tags=["actions"])


def get_action_service(request: Request) -> ActionService:
    service: ActionService = request.app.state.action_service
    return service


def get_action_repository(request: Request) -> ActionRepository:
    service: ActionService = request.app.state.action_service
    return service.repository


Actions = Annotated[ActionService, Depends(get_action_service)]


class FieldOut(BaseModel):
    label: str
    value: str


class ActionResultOut(BaseModel):
    reference: str
    message: str


class ActionErrorOut(BaseModel):
    code: str
    message: str


class ActionOut(BaseModel):
    id: UUID
    tool: str
    # pending | executing | executed | cancelled | expired | failed
    status: str
    title: str
    fields: list[FieldOut]
    notes: list[str]
    confirm_label: str
    expires_at: datetime
    result: ActionResultOut | None
    error: ActionErrorOut | None

    @classmethod
    def of(cls, a: ActionRecord) -> "ActionOut":
        return cls(
            id=a.id,
            tool=a.tool_name,
            status=a.status,
            title=str(a.card.get("title", "")),
            fields=[FieldOut.model_validate(f) for f in a.card.get("fields", [])],
            notes=list(a.card.get("notes", [])),
            confirm_label=str(a.card.get("confirm_label", "Confirm")),
            expires_at=a.expires_at,
            result=ActionResultOut.model_validate(a.result) if a.result else None,
            error=ActionErrorOut(code=a.error_code, message=a.error_message or "")
            if a.error_code
            else None,
        )


@router.get("/{action_id}")
async def get_action(action_id: UUID, user: CurrentUser, actions: Actions) -> ActionOut:
    return ActionOut.of(await actions.get(user, action_id))


@router.post("/{action_id}/confirm", dependencies=[Depends(limit_actions)])
async def confirm_action(action_id: UUID, user: CurrentUser, actions: Actions) -> ActionOut:
    """Runs the action. Safe to retry: an executed action returns the same result again.
    A business-rule failure at this point (e.g. balance changed) returns 200 with
    `status: failed` and a user-facing `error.message`; nothing was changed."""
    return ActionOut.of(await actions.confirm(user, action_id))


@router.post("/{action_id}/cancel", dependencies=[Depends(limit_actions)])
async def cancel_action(action_id: UUID, user: CurrentUser, actions: Actions) -> ActionOut:
    return ActionOut.of(await actions.cancel(user, action_id))


async def _action_error(_: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, ActionError)
    return problem(exc.status, exc.code, exc.code.replace("_", " ").capitalize(), exc.message)


async def _app_check_rejected(_: Request, __: Exception) -> JSONResponse:
    return problem(
        403, "app_check_failed", "App not verified", "Please update the app from the Play Store."
    )


def register_action_errors(app: FastAPI) -> None:
    app.add_exception_handler(ActionError, _action_error)
    app.add_exception_handler(AppCheckRejectedError, _app_check_rejected)


def enrich_confirmation_parts(
    parts: list[dict[str, Any]], actions: dict[UUID, ActionRecord]
) -> list[dict[str, Any]]:
    """Stored confirmation cards show their *current* state (confirmed, expired, ...)."""
    enriched = []
    for part in parts:
        if part.get("type") == "confirmation":
            try:
                action = actions.get(UUID(str(part.get("action_id"))))
            except ValueError:
                action = None
            if action is not None:
                out = ActionOut.of(action)
                part = {
                    **part,
                    "status": out.status,
                    "result": out.result.model_dump() if out.result else None,
                    "error": out.error.model_dump() if out.error else None,
                }
        enriched.append(part)
    return enriched
