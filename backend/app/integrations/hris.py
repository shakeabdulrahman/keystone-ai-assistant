"""HR data source.

In a real company this data lives in an HR system (Workday, BambooHR, SAP
SuccessFactors) behind its own API, not in our database. `HrisClient` is that
boundary. `DemoHrisClient` generates believable, synthetic data that is:
  - deterministic per user (seeded by the user's ID), so it doesn't change between requests;
  - relative to today, so "upcoming" meetings are always upcoming;
  - different for every user, so data isolation is visible in demos and tests.
Replacing it with a real HRIS means writing one class that implements `HrisClient`.

Writes (Phase 5) are kept in memory on top of the generated data, so they show up in
later reads. A real HRIS persists them; the demo forgets them when the server restarts
(the confirmed actions themselves are still stored in our database).
"""

import hashlib
import random
import re
import uuid
from dataclasses import dataclass, replace
from datetime import UTC, date, datetime, time, timedelta
from typing import Protocol


@dataclass(frozen=True)
class EmployeeProfile:
    name: str
    email: str | None
    job_title: str
    department: str
    manager: str
    location: str
    start_date: date


@dataclass(frozen=True)
class LeaveBalance:
    leave_type: str
    entitled_days: float
    used_days: float

    @property
    def remaining_days(self) -> float:
        return self.entitled_days - self.used_days


@dataclass(frozen=True)
class LeaveRequest:
    id: str
    leave_type: str
    start: date
    end: date
    days: float
    status: str  # pending | approved | rejected | cancelled
    submitted_on: date


@dataclass(frozen=True)
class HrRequest:
    id: str
    category: str  # IT | Payroll | Facilities | HR
    title: str
    status: str  # submitted | in_progress | resolved
    updated_on: date


@dataclass(frozen=True)
class Task:
    id: str
    title: str
    category: str  # Onboarding | Training | Compliance
    due: date
    status: str  # pending | done


@dataclass(frozen=True)
class Appointment:
    id: str
    title: str
    with_person: str
    start: datetime
    end: datetime
    location: str


@dataclass(frozen=True)
class Notification:
    id: str
    title: str
    body: str
    created: datetime
    read: bool


class HrisError(Exception):
    """The HR system refused a change (e.g. the record no longer exists)."""


def person_name(display_name: str | None, email: str | None) -> str | None:
    """The user's name from their sign-in: the display name, or else one derived from the
    email address ("rahman.basha@…" → "Rahman Basha"). None if neither gives a name."""
    if display_name and display_name.strip():
        return " ".join(display_name.split())
    if not email or "@" not in email:
        return None
    local = email.split("@", 1)[0].split("+", 1)[0]
    words = [w for w in re.split(r"[._\-]+", local) if w.isalpha()]
    return " ".join(w.capitalize() for w in words) or None


class HrisClient(Protocol):
    async def profile(
        self, user_id: uuid.UUID, email: str | None, name: str | None = None
    ) -> EmployeeProfile: ...
    async def leave_balances(self, user_id: uuid.UUID) -> list[LeaveBalance]: ...
    async def leave_requests(self, user_id: uuid.UUID) -> list[LeaveRequest]: ...
    async def hr_requests(self, user_id: uuid.UUID) -> list[HrRequest]: ...
    async def tasks(self, user_id: uuid.UUID) -> list[Task]: ...
    async def appointments(self, user_id: uuid.UUID) -> list[Appointment]: ...
    async def notifications(self, user_id: uuid.UUID) -> list[Notification]: ...

    # --- Writes (always on behalf of the signed-in user, after they confirmed) ---
    async def submit_leave(
        self, user_id: uuid.UUID, leave_type: str, start: date, end: date, days: float
    ) -> LeaveRequest: ...
    async def cancel_leave(self, user_id: uuid.UUID, request_id: str) -> LeaveRequest: ...
    async def create_hr_request(
        self, user_id: uuid.UUID, category: str, title: str, details: str
    ) -> HrRequest: ...
    async def complete_task(self, user_id: uuid.UUID, task_id: str) -> Task: ...

    # --- Workflow events (in a real HRIS these happen on the HR side, e.g. a manager) ---
    async def decide_leave(
        self, user_id: uuid.UUID, request_id: str, decision: str
    ) -> LeaveRequest: ...
    async def set_hr_request_status(
        self, user_id: uuid.UUID, request_id: str, status: str
    ) -> HrRequest: ...
    async def add_notification(self, user_id: uuid.UUID, title: str, body: str) -> Notification: ...


_FIRST = ["Aisha", "Rahul", "Maria", "Chen", "Fatima", "Arjun", "Sofia", "Daniel", "Priya", "Omar"]
_LAST = ["Khan", "Sharma", "Garcia", "Wei", "Ali", "Nair", "Rossi", "Smith", "Iyer", "Hassan"]
_TITLES = [
    ("Senior Android Engineer", "Engineering"),
    ("Product Designer", "Design"),
    ("Data Analyst", "Analytics"),
    ("HR Business Partner", "People"),
    ("Backend Engineer", "Engineering"),
]
_LOCATIONS = ["Chennai", "Bengaluru", "London", "Dubai", "Remote"]


class DemoHrisClient:
    """Synthetic, per-user HR data. Clearly fake: never shown as real employee data."""

    def __init__(self, today: date | None = None) -> None:
        self._today = today  # fixed in tests; None means "the real today"
        # In-memory changes per user, layered over the generated data.
        self._new_leave: dict[uuid.UUID, list[LeaveRequest]] = {}
        self._cancelled_leave: dict[uuid.UUID, set[str]] = {}
        self._new_hr: dict[uuid.UUID, list[HrRequest]] = {}
        self._done_tasks: dict[uuid.UUID, set[str]] = {}
        self._new_notifications: dict[uuid.UUID, list[Notification]] = {}

    def _now(self) -> date:
        return self._today or datetime.now(UTC).date()

    @staticmethod
    def _rng(user_id: uuid.UUID, topic: str) -> random.Random:
        seed = hashlib.sha256(f"{user_id}:{topic}".encode()).digest()
        return random.Random(int.from_bytes(seed[:8], "big"))  # noqa: S311 (not for security)

    async def profile(
        self, user_id: uuid.UUID, email: str | None, name: str | None = None
    ) -> EmployeeProfile:
        rng = self._rng(user_id, "profile")
        title, department = rng.choice(_TITLES)
        demo_name = f"{rng.choice(_FIRST)} {rng.choice(_LAST)}"
        return EmployeeProfile(
            # The signed-in person's own name; the generated one only if we know nothing.
            name=person_name(name, email) or demo_name,
            email=email,
            job_title=title,
            department=department,
            manager=f"{rng.choice(_FIRST)} {rng.choice(_LAST)}",
            location=rng.choice(_LOCATIONS),
            start_date=self._now() - timedelta(days=rng.randint(20, 900)),
        )

    async def leave_balances(self, user_id: uuid.UUID) -> list[LeaveBalance]:
        rng = self._rng(user_id, "balance")
        base = [
            LeaveBalance("Annual", 20, rng.randint(0, 14)),
            LeaveBalance("Sick", 10, rng.randint(0, 4)),
            LeaveBalance("Casual", 6, rng.randint(0, 5)),
        ]
        # New requests use days up; cancelled or rejected ones give them back.
        delta: dict[str, float] = {}
        for r in self._new_leave.get(user_id, []):
            if r.status in ("pending", "approved"):
                delta[r.leave_type] = delta.get(r.leave_type, 0) + r.days
        cancelled = self._cancelled_leave.get(user_id, set())
        for r in self._generated_leave(user_id):
            if r.id in cancelled and r.status in ("pending", "approved"):
                delta[r.leave_type] = delta.get(r.leave_type, 0) - r.days
        return [
            LeaveBalance(
                b.leave_type, b.entitled_days, max(0, b.used_days + delta.get(b.leave_type, 0))
            )
            for b in base
        ]

    async def leave_requests(self, user_id: uuid.UUID) -> list[LeaveRequest]:
        cancelled = self._cancelled_leave.get(user_id, set())
        generated = [
            replace(r, status="cancelled") if r.id in cancelled else r
            for r in self._generated_leave(user_id)
        ]
        everything = generated + self._new_leave.get(user_id, [])
        return sorted(everything, key=lambda r: r.start, reverse=True)

    def _generated_leave(self, user_id: uuid.UUID) -> list[LeaveRequest]:
        rng = self._rng(user_id, "leave")
        today = self._now()
        requests = []
        for i, status in enumerate(["pending", "approved", "approved", "rejected"]):
            # Pending requests are always for future dates; others can be past or future.
            offset = rng.randint(5, 45) if status == "pending" else rng.randint(-60, 45)
            start = today + timedelta(days=offset)
            days = rng.choice([1, 2, 3, 5])
            requests.append(
                LeaveRequest(
                    id=f"LR-{1040 + i}",
                    leave_type=rng.choice(["Annual", "Casual", "Sick"]),
                    start=start,
                    end=start + timedelta(days=days - 1),
                    days=float(days),
                    status=status,
                    # Never "submitted" in the future, even for leave that starts soon.
                    submitted_on=min(start - timedelta(days=rng.randint(3, 20)), today),
                )
            )
        return requests

    async def hr_requests(self, user_id: uuid.UUID) -> list[HrRequest]:
        rng = self._rng(user_id, "hr")
        catalog = [
            ("IT", "Laptop replacement"),
            ("Payroll", "Correction to last month's salary slip"),
            ("Facilities", "Access card not working"),
            ("HR", "Employment verification letter"),
            ("IT", "VPN access for client project"),
        ]
        picked = rng.sample(catalog, 4)
        statuses = ["submitted", "in_progress", "in_progress", "resolved"]
        today = self._now()
        generated = [
            HrRequest(
                id=f"HR-{2210 + i}",
                category=category,
                title=title,
                status=statuses[i],
                updated_on=today - timedelta(days=rng.randint(0, 12)),
            )
            for i, (category, title) in enumerate(picked)
        ]
        return self._new_hr.get(user_id, [])[::-1] + generated

    async def tasks(self, user_id: uuid.UUID) -> list[Task]:
        rng = self._rng(user_id, "tasks")
        today = self._now()
        catalog = [
            ("Complete information security training", "Compliance"),
            ("Upload ID proof and address documents", "Onboarding"),
            ("Set up 1:1 with your manager", "Onboarding"),
            ("Read the code of conduct", "Compliance"),
            ("Finish the 'Working at Keystone' course", "Training"),
            ("Enrol in the health insurance plan", "Onboarding"),
        ]
        done = self._done_tasks.get(user_id, set())
        tasks = [
            Task(
                id=f"T-{300 + i}",
                title=title,
                category=category,
                due=today + timedelta(days=rng.randint(-5, 21)),
                status="done" if rng.random() < 0.35 else "pending",
            )
            for i, (title, category) in enumerate(rng.sample(catalog, 5))
        ]
        return [replace(t, status="done") if t.id in done else t for t in tasks]

    async def appointments(self, user_id: uuid.UUID) -> list[Appointment]:
        rng = self._rng(user_id, "appointments")
        today = self._now()
        catalog = [
            ("1:1 with your manager", "Your manager", "Teams"),
            ("Benefits enrolment session", "HR team", "Room 4B"),
            ("Quarterly performance check-in", "Your manager", "Teams"),
            ("New joiner welcome lunch", "People team", "Cafeteria"),
        ]
        items = []
        for i, (title, who, where) in enumerate(catalog):
            day = today + timedelta(days=rng.randint(-10, 14))
            start = datetime.combine(day, time(hour=rng.choice([10, 11, 14, 16])), UTC)
            items.append(
                Appointment(f"A-{50 + i}", title, who, start, start + timedelta(minutes=30), where)
            )
        return sorted(items, key=lambda a: a.start)

    async def notifications(self, user_id: uuid.UUID) -> list[Notification]:
        rng = self._rng(user_id, "notifications")
        now = datetime.combine(self._now(), time(hour=9), UTC)
        catalog = [
            ("Leave approved", "Your annual leave request was approved by your manager."),
            ("HR request updated", "Your laptop replacement request is now in progress."),
            ("Training due soon", "Information security training is due in 3 days."),
            ("Payslip available", "Your payslip for last month is ready to view."),
        ]
        generated = [
            Notification(
                id=f"N-{900 + i}",
                title=title,
                body=body,
                created=now - timedelta(hours=rng.randint(1, 96)),
                read=rng.random() < 0.4,
            )
            for i, (title, body) in enumerate(catalog)
        ]
        return self._new_notifications.get(user_id, [])[::-1] + generated

    # --- Writes ------------------------------------------------------------------------

    async def submit_leave(
        self, user_id: uuid.UUID, leave_type: str, start: date, end: date, days: float
    ) -> LeaveRequest:
        mine = self._new_leave.setdefault(user_id, [])
        request = LeaveRequest(
            id=f"LR-{1050 + len(mine)}",
            leave_type=leave_type,
            start=start,
            end=end,
            days=days,
            status="pending",
            submitted_on=self._now(),
        )
        mine.append(request)
        return request

    async def cancel_leave(self, user_id: uuid.UUID, request_id: str) -> LeaveRequest:
        mine = self._new_leave.get(user_id, [])
        for i, r in enumerate(mine):
            if r.id == request_id:
                mine[i] = replace(r, status="cancelled")
                return mine[i]
        for r in self._generated_leave(user_id):
            if r.id == request_id:
                self._cancelled_leave.setdefault(user_id, set()).add(request_id)
                return replace(r, status="cancelled")
        raise HrisError(f"leave request {request_id} not found")

    async def create_hr_request(
        self, user_id: uuid.UUID, category: str, title: str, details: str
    ) -> HrRequest:
        mine = self._new_hr.setdefault(user_id, [])
        request = HrRequest(
            id=f"HR-{2230 + len(mine)}",
            category=category,
            title=title,
            status="submitted",
            updated_on=self._now(),
        )
        mine.append(request)
        return request

    async def complete_task(self, user_id: uuid.UUID, task_id: str) -> Task:
        for t in await self.tasks(user_id):
            if t.id == task_id:
                self._done_tasks.setdefault(user_id, set()).add(task_id)
                return replace(t, status="done")
        raise HrisError(f"task {task_id} not found")

    # --- Workflow events ------------------------------------------------------------------

    async def decide_leave(
        self, user_id: uuid.UUID, request_id: str, decision: str
    ) -> LeaveRequest:
        mine = self._new_leave.get(user_id, [])
        for i, r in enumerate(mine):
            if r.id == request_id:
                if r.status != "pending":
                    raise HrisError(f"{request_id} is {r.status}")
                mine[i] = replace(r, status=decision)  # rejected days return to the balance
                return mine[i]
        raise HrisError(f"leave request {request_id} not found")

    async def set_hr_request_status(
        self, user_id: uuid.UUID, request_id: str, status: str
    ) -> HrRequest:
        mine = self._new_hr.get(user_id, [])
        for i, r in enumerate(mine):
            if r.id == request_id:
                mine[i] = replace(r, status=status, updated_on=self._now())
                return mine[i]
        raise HrisError(f"HR request {request_id} not found")

    async def add_notification(self, user_id: uuid.UUID, title: str, body: str) -> Notification:
        mine = self._new_notifications.setdefault(user_id, [])
        note = Notification(
            id=f"N-{950 + len(mine)}",
            title=title,
            body=body,
            created=datetime.now(UTC),
            read=False,
        )
        mine.append(note)
        return note
