"""The home dashboard: what needs the employee's attention, in one request.

Everything is read in parallel (`asyncio.gather`), so the screen costs one round trip
and the slowest HR call, not the sum of all of them. Each attention item carries what
the app should do when it's tapped: ask the assistant a ready-made question, or reopen
the conversation with an unconfirmed card.
"""

import asyncio
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Literal

from app.integrations.hris import HrisClient
from app.repositories.base import ActionRepository, UserRecord

UPCOMING_DAYS = 14
DUE_SOON_DAYS = 3


@dataclass(frozen=True)
class AttentionItem:
    id: str
    kind: (
        str  # pending_action | overdue_task | task_due_soon | leave_pending | unread_notifications
    )
    severity: Literal["high", "normal"]
    title: str
    detail: str
    # What tapping does: {"type": "ask", "prompt": ...} or {"type": "open_conversation", ...}
    action: dict[str, str]

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "kind": self.kind,
            "severity": self.severity,
            "title": self.title,
            "detail": self.detail,
            "action": self.action,
        }


class HomeService:
    def __init__(
        self,
        hris: HrisClient,
        actions: ActionRepository,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._hris = hris
        self._actions = actions
        self._clock = clock

    async def build(self, user: UserRecord) -> dict[str, Any]:
        now = self._clock()
        today = now.date()
        uid: uuid.UUID = user.id
        # Two typed groups (gather keeps precise types for up to six awaitables); both
        # groups still run concurrently with each other.
        people, work = await asyncio.gather(
            asyncio.gather(
                self._hris.profile(uid, user.email),
                self._hris.leave_balances(uid),
                self._hris.leave_requests(uid),
                self._hris.notifications(uid),
            ),
            asyncio.gather(
                self._hris.hr_requests(uid),
                self._hris.tasks(uid),
                self._hris.appointments(uid),
                self._actions.open_for_user(uid, now),
            ),
        )
        profile, balances, leave, notes = people
        hr, tasks, meetings, open_actions = work

        attention: list[AttentionItem] = []
        for a in open_actions:
            attention.append(
                AttentionItem(
                    id=f"action-{a.id}",
                    kind="pending_action",
                    severity="high",
                    title=f"Waiting for you: {str(a.card.get('title', 'a request')).lower()}",
                    detail="Prepared in chat but not confirmed yet. It expires soon.",
                    action={
                        "type": "open_conversation",
                        "conversation_id": str(a.conversation_id),
                        "action_id": str(a.id),
                    },
                )
            )
        pending_tasks = sorted((t for t in tasks if t.status == "pending"), key=lambda t: t.due)
        for t in pending_tasks:
            if t.due < today:
                days = (today - t.due).days
                attention.append(
                    AttentionItem(
                        id=f"task-{t.id}",
                        kind="overdue_task",
                        severity="high",
                        title=t.title,
                        detail=f"Overdue by {days} day{'s' if days != 1 else ''}",
                        action={"type": "ask", "prompt": f"Help me with my task {t.id}: {t.title}"},
                    )
                )
            elif (t.due - today).days <= DUE_SOON_DAYS:
                when = "today" if t.due == today else f"on {t.due:%a} {t.due.day} {t.due:%b}"
                attention.append(
                    AttentionItem(
                        id=f"task-{t.id}",
                        kind="task_due_soon",
                        severity="normal",
                        title=t.title,
                        detail=f"Due {when}",
                        action={"type": "ask", "prompt": f"Help me with my task {t.id}: {t.title}"},
                    )
                )
        pending_leave = [r for r in leave if r.status == "pending"]
        if pending_leave:
            n = len(pending_leave)
            attention.append(
                AttentionItem(
                    id="leave-pending",
                    kind="leave_pending",
                    severity="normal",
                    title=f"{n} leave request{'s' if n != 1 else ''} awaiting approval",
                    detail=", ".join(r.id for r in pending_leave[:3]),
                    action={"type": "ask", "prompt": "Show my pending leave requests"},
                )
            )
        unread = [n for n in notes if not n.read]
        if unread:
            attention.append(
                AttentionItem(
                    id="notifications-unread",
                    kind="unread_notifications",
                    severity="normal",
                    title=f"{len(unread)} unread notification{'s' if len(unread) != 1 else ''}",
                    detail=unread[0].title,
                    action={"type": "ask", "prompt": "What are my unread notifications?"},
                )
            )

        horizon = now + timedelta(days=UPCOMING_DAYS)
        upcoming = [m for m in meetings if now <= m.start <= horizon][:3]
        open_hr = [r for r in hr if r.status != "resolved"]

        return {
            "generated_at": now.isoformat(),
            "first_name": profile.name.split(" ")[0],
            "leave": [
                {"type": b.leave_type, "remaining": b.remaining_days, "entitled": b.entitled_days}
                for b in balances
            ],
            "attention": [item.to_dict() for item in attention],
            "upcoming": [
                {
                    "id": m.id,
                    "title": m.title,
                    "with": m.with_person,
                    "start": m.start.isoformat(),
                    "location": m.location,
                }
                for m in upcoming
            ],
            "counts": {
                "open_hr_requests": len(open_hr),
                "pending_leave_requests": len(pending_leave),
                "unread_notifications": len(unread),
            },
            "suggestions": _suggestions(attention, open_hr_count=len(open_hr)),
        }


def _suggestions(attention: list[AttentionItem], *, open_hr_count: int) -> list[str]:
    """Questions worth asking right now, most relevant first (max 4)."""
    out = []
    if any(a.kind == "overdue_task" for a in attention):
        out.append("Which of my tasks are overdue?")
    if open_hr_count:
        out.append("What's the status of my HR requests?")
    out += ["How many leave days do I have left?", "Can I carry over unused leave?"]
    return out[:4]
