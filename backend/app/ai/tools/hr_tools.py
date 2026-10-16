"""The HR tools the assistant can use. All read-only in Phase 3.

Descriptions are written for the model (when to call, what comes back); labels for
people (shown while the tool runs). Every handler reads data *for ctx.user only*.
"""

from typing import Annotated, Any, Literal

from pydantic import BeforeValidator, Field

from app.ai.tools.registry import NoArgs, Tool, ToolArgs, ToolContext, ToolRegistry


def _lower(value: Any) -> Any:
    return value.lower().strip() if isinstance(value, str) else value


class LeaveRequestArgs(ToolArgs):
    status: Annotated[
        Literal["pending", "approved", "rejected", "all"], BeforeValidator(_lower)
    ] = Field("all", description="Filter by status")


class HrRequestArgs(ToolArgs):
    status: Annotated[Literal["open", "resolved", "all"], BeforeValidator(_lower)] = Field(
        "open", description="'open' = submitted or in progress"
    )


class TaskArgs(ToolArgs):
    status: Annotated[Literal["pending", "done", "all"], BeforeValidator(_lower)] = Field(
        "pending", description="Filter by status"
    )


class AppointmentArgs(ToolArgs):
    when: Annotated[Literal["upcoming", "past", "all"], BeforeValidator(_lower)] = Field(
        "upcoming", description="Which meetings to list"
    )


class NotificationArgs(ToolArgs):
    unread_only: bool = Field(False, description="Only unread notifications")


async def get_user_profile(_: NoArgs, ctx: ToolContext) -> dict[str, Any]:
    p = await ctx.hris.profile(ctx.user.id, ctx.user.email)
    return {
        "name": p.name,
        "email": p.email,
        "job_title": p.job_title,
        "department": p.department,
        "manager": p.manager,
        "location": p.location,
        "start_date": p.start_date.isoformat(),
    }


async def get_leave_balance(_: NoArgs, ctx: ToolContext) -> dict[str, Any]:
    balances = await ctx.hris.leave_balances(ctx.user.id)
    return {
        "year": ctx.today.year,
        "unit": "days",
        "items": [
            {
                "type": b.leave_type,
                "entitled": b.entitled_days,
                "used": b.used_days,
                "remaining": b.remaining_days,
            }
            for b in balances
        ],
    }


async def get_leave_requests(args: LeaveRequestArgs, ctx: ToolContext) -> dict[str, Any]:
    requests = await ctx.hris.leave_requests(ctx.user.id)
    return {
        "filter": args.status,
        "items": [
            {
                "id": r.id,
                "type": r.leave_type,
                "start": r.start.isoformat(),
                "end": r.end.isoformat(),
                "days": r.days,
                "status": r.status,
                "submitted_on": r.submitted_on.isoformat(),
            }
            for r in requests
            if args.status == "all" or r.status == args.status
        ],
    }


async def get_hr_requests(args: HrRequestArgs, ctx: ToolContext) -> dict[str, Any]:
    requests = await ctx.hris.hr_requests(ctx.user.id)

    def wanted(status: str) -> bool:
        if args.status == "all":
            return True
        return (status == "resolved") == (args.status == "resolved")

    return {
        "filter": args.status,
        "items": [
            {
                "id": r.id,
                "category": r.category,
                "title": r.title,
                "status": r.status,
                "updated_on": r.updated_on.isoformat(),
            }
            for r in requests
            if wanted(r.status)
        ],
    }


async def get_tasks(args: TaskArgs, ctx: ToolContext) -> dict[str, Any]:
    tasks = sorted(await ctx.hris.tasks(ctx.user.id), key=lambda t: t.due)
    return {
        "filter": args.status,
        "items": [
            {
                "id": t.id,
                "title": t.title,
                "category": t.category,
                "due": t.due.isoformat(),
                "status": t.status,
                "overdue": t.status == "pending" and t.due < ctx.today,
            }
            for t in tasks
            if args.status == "all" or t.status == args.status
        ],
    }


async def get_appointments(args: AppointmentArgs, ctx: ToolContext) -> dict[str, Any]:
    items = await ctx.hris.appointments(ctx.user.id)

    def wanted(day_is_future: bool) -> bool:
        return args.when == "all" or (args.when == "upcoming") == day_is_future

    return {
        "filter": args.when,
        "items": [
            {
                "id": a.id,
                "title": a.title,
                "with": a.with_person,
                "start": a.start.isoformat(),
                "end": a.end.isoformat(),
                "location": a.location,
            }
            for a in items
            if wanted(a.start.date() >= ctx.today)
        ],
    }


async def get_notifications(args: NotificationArgs, ctx: ToolContext) -> dict[str, Any]:
    items = sorted(await ctx.hris.notifications(ctx.user.id), key=lambda n: n.created, reverse=True)
    return {
        "items": [
            {
                "id": n.id,
                "title": n.title,
                "body": n.body,
                "created_at": n.created.isoformat(),
                "read": n.read,
            }
            for n in items
            if not args.unread_only or not n.read
        ],
    }


HR_TOOLS: list[Tool] = [
    Tool(
        name="getUserProfile",
        description="The employee's own profile: name, job title, department, manager, "
        "location and start date. Use for questions about themselves or their manager.",
        label="Looking up your profile",
        ui="profile",
        args_model=NoArgs,
        handler=get_user_profile,
    ),
    Tool(
        name="getLeaveBalance",
        description="Remaining leave days for this year by leave type (annual, sick, casual). "
        "Use for 'how many days off do I have'.",
        label="Checking your leave balance",
        ui="leave_balance",
        args_model=NoArgs,
        handler=get_leave_balance,
    ),
    Tool(
        name="getLeaveRequests",
        description="The employee's leave requests with dates and approval status.",
        label="Checking your leave requests",
        ui="leave_requests",
        args_model=LeaveRequestArgs,
        handler=get_leave_requests,
    ),
    Tool(
        name="getHrRequests",
        description="The employee's HR/IT/payroll/facilities support requests and their status.",
        label="Checking your HR requests",
        ui="hr_requests",
        args_model=HrRequestArgs,
        handler=get_hr_requests,
    ),
    Tool(
        name="getTasks",
        description="The employee's onboarding, training and compliance tasks with due dates.",
        label="Checking your tasks",
        ui="tasks",
        args_model=TaskArgs,
        handler=get_tasks,
    ),
    Tool(
        name="getAppointments",
        description="The employee's HR-related meetings (1:1s, check-ins, sessions).",
        label="Checking your meetings",
        ui="appointments",
        args_model=AppointmentArgs,
        handler=get_appointments,
    ),
    Tool(
        name="getNotifications",
        description="Recent HR notifications for the employee (approvals, updates, reminders).",
        label="Checking your notifications",
        ui="notifications",
        args_model=NotificationArgs,
        handler=get_notifications,
    ),
]


def build_hr_registry(timeout_s: float = 5.0) -> ToolRegistry:
    return ToolRegistry(HR_TOOLS, timeout_s=timeout_s)
