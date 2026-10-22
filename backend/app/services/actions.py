"""Confirmed actions: the only path from "the assistant proposed it" to "it happened".

Lifecycle:
    pending ──confirm──▶ executing ──▶ executed | failed
       │ └──cancel──▶ cancelled
       └──(15 min)──▶ expired

- Only the owner can confirm or cancel (other users get the same 404 as a missing action).
- Confirming is idempotent: the action ID is the idempotency key. A second confirm of an
  executed action returns the same result; it never runs twice.
- `pending → executing` is one atomic compare-and-set in the database, so two confirms
  racing (double tap, retry on a flaky network) can't both run it.
- The arguments are the ones frozen at proposal time, and the rules are checked again
  before running (the balance may have changed in the meantime).
"""

import logging
import time
import uuid
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Any

from app.ai.tools.registry import ToolContext, ToolExecution, ToolRegistry, ToolRejected
from app.core.metrics import ACTIONS
from app.integrations.hris import HrisClient
from app.repositories.base import ActionRecord, ActionRepository, UserRecord

logger = logging.getLogger("keystone.actions")

ACTION_TTL = timedelta(minutes=15)

_UNCERTAIN = "The HR system didn't confirm the change. Check your requests before trying again."


class ActionError(Exception):
    """An action that can't be confirmed or cancelled now. Becomes a problem+json reply."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


class ActionService:
    def __init__(
        self,
        actions: ActionRepository,
        registry: ToolRegistry,
        hris: HrisClient,
        *,
        ttl: timedelta = ACTION_TTL,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
        listeners: list[Callable[[UserRecord, ActionRecord], None]] | None = None,
    ) -> None:
        self._actions = actions
        self._registry = registry
        self._hris = hris
        self._ttl = ttl
        self._clock = clock
        # Told about every executed action (e.g. the demo HR workflow). Must not block.
        self._listeners = listeners or []

    @property
    def repository(self) -> ActionRepository:
        return self._actions

    async def propose(
        self,
        user: UserRecord,
        conversation_id: uuid.UUID,
        message_id: uuid.UUID,
        execution: ToolExecution,
    ) -> ActionRecord:
        assert execution.pending_arguments is not None
        now = self._clock()
        action = ActionRecord(
            id=uuid.uuid4(),
            user_id=user.id,
            conversation_id=conversation_id,
            message_id=message_id,
            call_id=execution.call_id,
            tool_name=execution.tool,
            arguments=execution.pending_arguments,
            card=execution.data,
            status="pending",
            created_at=now,
            expires_at=now + self._ttl,
        )
        await self._actions.create(action)
        logger.info(
            "action_proposed", extra={"tool": action.tool_name, "action_id": str(action.id)}
        )
        return action

    async def get(self, user: UserRecord, action_id: uuid.UUID) -> ActionRecord:
        action = await self._actions.get_owned(user.id, action_id)
        if action is None:
            raise ActionError(404, "not_found", "Action not found")
        return await self._expire_if_due(user, action)

    async def confirm(self, user: UserRecord, action_id: uuid.UUID) -> ActionRecord:
        action = await self.get(user, action_id)
        if action.status == "executed":
            return action  # idempotent: the same answer every time
        if action.status == "pending":
            claimed = await self._actions.transition(
                user.id, action_id, "pending", "executing", self._clock(), unexpired_only=True
            )
            if claimed is not None:
                return await self._run(user, claimed)
            action = await self.get(user, action_id)  # lost a race or just expired
            if action.status == "executed":
                return action
        raise self._closed(action)

    async def cancel(self, user: UserRecord, action_id: uuid.UUID) -> ActionRecord:
        action = await self.get(user, action_id)
        if action.status in ("cancelled", "expired"):
            return action  # nothing will happen either way: idempotent
        if action.status == "pending":
            cancelled = await self._actions.transition(
                user.id, action_id, "pending", "cancelled", self._clock()
            )
            if cancelled is not None:
                ACTIONS.labels(cancelled.tool_name, "cancelled").inc()
                logger.info("action_cancelled", extra={"action_id": str(action_id)})
                return cancelled
            action = await self.get(user, action_id)
        raise self._closed(action)

    async def _run(self, user: UserRecord, action: ActionRecord) -> ActionRecord:
        ctx = ToolContext(user=user, hris=self._hris, today=self._clock().date())
        started = time.perf_counter()
        status = "failed"
        try:
            result = await self._registry.run_confirmed(action.tool_name, action.arguments, ctx)
            status = "executed"
            done = await self._actions.finish(action.id, "executed", result=_clean(result))
            for listener in self._listeners:
                try:
                    listener(user, done)
                except Exception:
                    logger.exception("action_listener_failed")
            return done
        except ToolRejected as exc:
            # Something changed since the proposal (e.g. the balance). Nothing happened.
            return await self._actions.finish(
                action.id, "failed", error_code="rejected", error_message=exc.message
            )
        except Exception:
            logger.exception("action_failed", extra={"tool": action.tool_name})
            return await self._actions.finish(
                action.id, "failed", error_code="action_failed", error_message=_UNCERTAIN
            )
        finally:
            ACTIONS.labels(action.tool_name, status).inc()
            logger.info(
                "action_confirmed",
                extra={
                    "tool": action.tool_name,
                    "action_id": str(action.id),
                    "status": status,
                    "latency_ms": round((time.perf_counter() - started) * 1000),
                },
            )

    async def _expire_if_due(self, user: UserRecord, action: ActionRecord) -> ActionRecord:
        if action.status == "pending" and action.expires_at <= self._clock():
            expired = await self._actions.transition(
                user.id, action.id, "pending", "expired", self._clock()
            )
            if expired is not None:
                ACTIONS.labels(expired.tool_name, "expired").inc()
                return expired
            refreshed = await self._actions.get_owned(user.id, action.id)
            return refreshed or action
        return action

    @staticmethod
    def _closed(action: ActionRecord) -> ActionError:
        if action.status == "executing":
            return ActionError(409, "action_in_progress", "This action is already being processed.")
        if action.status == "expired":
            return ActionError(
                409,
                "action_expired",
                "This request expired. Ask the assistant to prepare it again.",
            )
        if action.status == "cancelled":
            return ActionError(409, "action_cancelled", "This request was cancelled.")
        if action.status == "executed":
            return ActionError(409, "action_done", "This request was already completed.")
        return ActionError(409, "action_failed", action.error_message or "This request failed.")


def _clean(result: dict[str, Any]) -> dict[str, Any]:
    return {
        "reference": str(result.get("reference", "")),
        "message": str(result.get("message", "")),
    }
