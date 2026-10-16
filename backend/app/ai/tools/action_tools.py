"""Write tools: things the assistant can *do*, always with the employee's confirmation.

Each tool has two halves:
- `handler` (prepare): runs when the model calls the tool. It validates the request
  against the HR rules and returns a proposal for the confirmation card. Nothing changes.
- `execute`: runs only when the employee taps Confirm (POST /v1/actions/{id}/confirm),
  with the exact arguments that were shown. The rules are checked again first.

Business-rule failures raise `ToolRejected` with a message written for the employee,
which the model passes on ("you only have 3 annual leave days left").
"""

import re
from datetime import date, timedelta
from typing import Annotated, Any, Literal

from pydantic import BeforeValidator, Field, model_validator

from app.ai.tools.registry import Tool, ToolArgs, ToolContext, ToolRejected
from app.integrations.hris import LeaveRequest

MAX_WORKING_DAYS = 30
DASH = " \u2013 "  # en dash for date ranges
SICK_BACKDATE_DAYS = 14


def _lower(value: Any) -> Any:
    return value.lower().strip() if isinstance(value, str) else value


def _upper(value: Any) -> Any:
    return value.upper().strip() if isinstance(value, str) else value


def _category(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    canonical = {"it": "IT", "payroll": "Payroll", "facilities": "Facilities", "hr": "HR"}
    return canonical.get(value.lower().strip(), value)


def fmt_date(d: date) -> str:
    return f"{d:%a} {d.day} {d:%b %Y}"  # "Tue 3 Nov 2026"


def fmt_days(days: float) -> str:
    n = int(days) if days == int(days) else days
    return f"{n} day" if n == 1 else f"{n} days"


def working_days(start: date, end: date) -> int:
    """Monday to Friday, inclusive. Public holidays are left to the HR system."""
    return sum(1 for i in range((end - start).days + 1) if (start + timedelta(i)).weekday() < 5)


def _field(label: str, value: str) -> dict[str, str]:
    return {"label": label, "value": value}


# --- Submit leave ---------------------------------------------------------------------


class SubmitLeaveArgs(ToolArgs):
    leave_type: Annotated[Literal["annual", "sick", "casual"], BeforeValidator(_lower)]
    start_date: date = Field(description="First day off, YYYY-MM-DD")
    end_date: date = Field(description="Last day off, YYYY-MM-DD (same as start for one day)")
    reason: str | None = Field(None, max_length=200, description="Optional note for the manager")

    @model_validator(mode="after")
    def _order(self) -> "SubmitLeaveArgs":
        if self.end_date < self.start_date:
            raise ValueError("end_date is before start_date")
        return self


def _active(r: LeaveRequest) -> bool:
    return r.status in ("pending", "approved")


async def prepare_submit_leave(args: SubmitLeaveArgs, ctx: ToolContext) -> dict[str, Any]:
    kind = args.leave_type.capitalize()
    days = working_days(args.start_date, args.end_date)
    if days == 0:
        raise ToolRejected("Those dates are all on a weekend, so no leave is needed.")
    if days > MAX_WORKING_DAYS:
        raise ToolRejected(
            f"That's {days} working days. Requests over {MAX_WORKING_DAYS} days go through "
            "the People Team; please raise an HR request instead."
        )
    if args.leave_type == "sick":
        if args.start_date > ctx.today:
            raise ToolRejected("Sick leave can't be booked in advance; record it when you're ill.")
        if args.start_date < ctx.today - timedelta(days=SICK_BACKDATE_DAYS):
            raise ToolRejected(
                f"Sick leave can be recorded up to {SICK_BACKDATE_DAYS} days back. "
                "For older absences, raise an HR request."
            )
    elif args.start_date < ctx.today:
        raise ToolRejected(f"{kind} leave can't start in the past.")

    balances = {b.leave_type: b for b in await ctx.hris.leave_balances(ctx.user.id)}
    balance = balances.get(kind)
    remaining = balance.remaining_days if balance else 0
    if days > remaining:
        raise ToolRejected(
            f"You have {fmt_days(remaining)} of {kind.lower()} leave left, "
            f"but this request needs {fmt_days(days)}."
        )
    for r in await ctx.hris.leave_requests(ctx.user.id):
        if _active(r) and r.start <= args.end_date and args.start_date <= r.end:
            raise ToolRejected(
                f"Those dates overlap your {r.status} {r.leave_type.lower()} leave {r.id} "
                f"({fmt_date(r.start)}{DASH}{fmt_date(r.end)})."
            )

    dates = (
        fmt_date(args.start_date)
        if args.start_date == args.end_date
        else f"{fmt_date(args.start_date)}{DASH}{fmt_date(args.end_date)}"
    )
    fields = [
        _field("Type", f"{kind} leave"),
        _field("Dates", dates),
        _field("Working days", str(days)),
        _field("Balance after", f"{fmt_days(remaining)} → {fmt_days(remaining - days)}"),
    ]
    if args.reason:
        fields.append(_field("Note", args.reason))
    notes = []
    if args.leave_type == "annual":
        lead = working_days(ctx.today, args.start_date) - 1
        if days >= 3 and (args.start_date - ctx.today).days < 28:
            notes.append("Leave of 3 days or more should be requested 4 weeks ahead.")
        elif days < 3 and lead < 5:
            notes.append("Short leave should be requested 5 working days ahead.")
        if days > 10:
            notes.append("Over 10 days also needs your department head's approval.")
    return {
        "title": f"Request {kind.lower()} leave",
        "fields": fields,
        "notes": notes,
        "confirm_label": "Submit request",
    }


async def execute_submit_leave(args: SubmitLeaveArgs, ctx: ToolContext) -> dict[str, Any]:
    days = working_days(args.start_date, args.end_date)
    request = await ctx.hris.submit_leave(
        ctx.user.id, args.leave_type.capitalize(), args.start_date, args.end_date, float(days)
    )
    return {
        "reference": request.id,
        "message": f"Leave request {request.id} submitted. Your manager will review it.",
    }


# --- Withdraw leave -------------------------------------------------------------------

_LEAVE_ID = re.compile(r"^LR-\d{1,6}$")


class CancelLeaveArgs(ToolArgs):
    request_id: Annotated[str, BeforeValidator(_upper)] = Field(
        pattern=_LEAVE_ID.pattern, description="Leave request ID, e.g. LR-1041"
    )


async def _find_leave(args: CancelLeaveArgs, ctx: ToolContext) -> LeaveRequest:
    for r in await ctx.hris.leave_requests(ctx.user.id):
        if r.id == args.request_id:
            if not _active(r):
                raise ToolRejected(f"{r.id} is {r.status}, so there's nothing to withdraw.")
            if r.start <= ctx.today:
                raise ToolRejected(
                    f"{r.id} has already started. Talk to your manager to change it."
                )
            return r
    # Same message whether it doesn't exist or isn't theirs (it can't be: reads are per user).
    raise ToolRejected(f"I couldn't find leave request {args.request_id} in your requests.")


async def prepare_cancel_leave(args: CancelLeaveArgs, ctx: ToolContext) -> dict[str, Any]:
    r = await _find_leave(args, ctx)
    return {
        "title": "Withdraw leave request",
        "fields": [
            _field("Request", r.id),
            _field("Type", f"{r.leave_type} leave"),
            _field("Dates", f"{fmt_date(r.start)}{DASH}{fmt_date(r.end)}"),
            _field("Status", r.status.capitalize()),
        ],
        "notes": [f"{fmt_days(r.days)} will go back to your balance."],
        "confirm_label": "Withdraw request",
    }


async def execute_cancel_leave(args: CancelLeaveArgs, ctx: ToolContext) -> dict[str, Any]:
    r = await ctx.hris.cancel_leave(ctx.user.id, args.request_id)
    return {"reference": r.id, "message": f"Leave request {r.id} has been withdrawn."}


# --- Raise an HR request --------------------------------------------------------------


class HrRequestCreateArgs(ToolArgs):
    category: Annotated[Literal["IT", "Payroll", "Facilities", "HR"], BeforeValidator(_category)]
    title: str = Field(min_length=5, max_length=120, description="Short subject line")
    details: str = Field("", max_length=1000, description="What the team needs to know")


async def prepare_hr_request(args: HrRequestCreateArgs, ctx: ToolContext) -> dict[str, Any]:
    fields = [_field("Team", args.category), _field("Subject", args.title.strip())]
    if args.details.strip():
        fields.append(_field("Details", args.details.strip()))
    return {
        "title": "Raise an HR request",
        "fields": fields,
        "notes": [],
        "confirm_label": "Send request",
    }


async def execute_hr_request(args: HrRequestCreateArgs, ctx: ToolContext) -> dict[str, Any]:
    r = await ctx.hris.create_hr_request(
        ctx.user.id, args.category, args.title.strip(), args.details.strip()
    )
    return {"reference": r.id, "message": f"Request {r.id} sent to the {r.category} team."}


# --- Complete a task ------------------------------------------------------------------


class CompleteTaskArgs(ToolArgs):
    task_id: Annotated[str, BeforeValidator(_upper)] = Field(
        pattern=r"^T-\d{1,6}$", description="Task ID, e.g. T-301"
    )


async def prepare_complete_task(args: CompleteTaskArgs, ctx: ToolContext) -> dict[str, Any]:
    for t in await ctx.hris.tasks(ctx.user.id):
        if t.id == args.task_id:
            if t.status == "done":
                raise ToolRejected(f"'{t.title}' is already done.")
            return {
                "title": "Mark task as done",
                "fields": [
                    _field("Task", t.title),
                    _field("Category", t.category),
                    _field("Due", fmt_date(t.due)),
                ],
                "notes": [],
                "confirm_label": "Mark done",
            }
    raise ToolRejected(f"I couldn't find task {args.task_id} in your tasks.")


async def execute_complete_task(args: CompleteTaskArgs, ctx: ToolContext) -> dict[str, Any]:
    t = await ctx.hris.complete_task(ctx.user.id, args.task_id)
    return {"reference": t.id, "message": f"'{t.title}' is marked as done."}


_CONFIRM_NOTE = " This only prepares the change: the employee reviews and confirms it in the app."

ACTION_TOOLS: list[Tool] = [
    Tool(
        name="submitLeaveRequest",
        description="Prepare a leave request (annual, sick or casual) for the employee to "
        "confirm. Use exact dates (YYYY-MM-DD); work out dates like 'next Monday' from "
        "today's date." + _CONFIRM_NOTE,
        label="Preparing your leave request",
        ui="confirmation",
        args_model=SubmitLeaveArgs,
        handler=prepare_submit_leave,
        execute=execute_submit_leave,
    ),
    Tool(
        name="cancelLeaveRequest",
        description="Prepare withdrawing one of the employee's pending or approved leave "
        "requests that hasn't started yet. Look the ID up with getLeaveRequests first."
        + _CONFIRM_NOTE,
        label="Preparing to withdraw your leave",
        ui="confirmation",
        args_model=CancelLeaveArgs,
        handler=prepare_cancel_leave,
        execute=execute_cancel_leave,
    ),
    Tool(
        name="createHrRequest",
        description="Prepare a new support request to the IT, Payroll, Facilities or HR "
        "team." + _CONFIRM_NOTE,
        label="Preparing your HR request",
        ui="confirmation",
        args_model=HrRequestCreateArgs,
        handler=prepare_hr_request,
        execute=execute_hr_request,
    ),
    Tool(
        name="completeTask",
        description="Prepare marking one of the employee's pending tasks as done. Look the "
        "ID up with getTasks first." + _CONFIRM_NOTE,
        label="Preparing to update your task",
        ui="confirmation",
        args_model=CompleteTaskArgs,
        handler=prepare_complete_task,
        execute=execute_complete_task,
    ),
]
