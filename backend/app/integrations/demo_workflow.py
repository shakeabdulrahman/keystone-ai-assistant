"""Simulates what happens on the HR side after an employee acts, so the demo feels alive.

A real HRIS would call us back (a webhook) when a manager approves leave. Here, a
short while after a leave request is submitted, a "manager" approves it; an HR
request moves to "in progress". Each event goes through `NotificationService`, so it
appears in the app's notifications and as a push on the employee's phone.
"""

import asyncio
import logging
from collections.abc import Awaitable, Callable

from app.integrations.hris import HrisClient, HrisError
from app.notifications.service import NotificationService
from app.repositories.base import ActionRecord, UserRecord

logger = logging.getLogger("keystone.demo_workflow")


class DemoWorkflow:
    def __init__(
        self, hris: HrisClient, notifications: NotificationService, *, delay_s: float = 20.0
    ) -> None:
        self._hris = hris
        self._notifications = notifications
        self._delay_s = delay_s
        self._tasks: set[asyncio.Task[None]] = set()

    def on_action_executed(self, user: UserRecord, action: ActionRecord) -> None:
        """ActionService listener. Never blocks the confirm request: work runs later."""
        if self._delay_s <= 0 or not action.result:
            return
        ref = str(action.result.get("reference", ""))
        if action.tool_name == "submitLeaveRequest":
            self._schedule(lambda: self._approve_leave(user, ref))
        elif action.tool_name == "createHrRequest":
            self._schedule(lambda: self._pick_up_request(user, ref))

    async def drain(self) -> None:
        """Wait for scheduled events (tests); on shutdown, pending ones are cancelled."""
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)

    def cancel(self) -> None:
        for task in self._tasks:
            task.cancel()

    def _schedule(self, work: Callable[[], Awaitable[None]]) -> None:
        task = asyncio.create_task(self._after_delay(work))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def _after_delay(self, work: Callable[[], Awaitable[None]]) -> None:
        try:
            await asyncio.sleep(self._delay_s)
            await work()
        except HrisError as exc:
            # e.g. the employee withdrew the request before the "manager" got to it.
            logger.info("demo_workflow_skipped", extra={"reason": str(exc)})
        except Exception:
            logger.exception("demo_workflow_failed")

    async def _approve_leave(self, user: UserRecord, ref: str) -> None:
        request = await self._hris.decide_leave(user.id, ref, "approved")
        await self._notifications.notify(
            user.id,
            kind="leave",
            ref=ref,
            detail_title="Leave approved",
            detail_body=(
                f"Your {request.leave_type.lower()} leave {ref} was approved by your manager."
            ),
            push_body="Your leave request has an update.",
        )

    async def _pick_up_request(self, user: UserRecord, ref: str) -> None:
        request = await self._hris.set_hr_request_status(user.id, ref, "in_progress")
        await self._notifications.notify(
            user.id,
            kind="hr_request",
            ref=ref,
            detail_title="HR request in progress",
            detail_body=f"The {request.category} team is working on {ref}: {request.title}.",
            push_body="One of your HR requests has an update.",
        )
