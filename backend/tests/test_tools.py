"""Tool registry safety rules and the HR tools' behaviour."""

import asyncio
import json
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest

from app.ai.llm.types import ToolCall
from app.ai.tools.hr_tools import HR_TOOLS, build_hr_registry
from app.ai.tools.registry import NoArgs, Tool, ToolContext, ToolRegistry
from app.integrations.hris import DemoHrisClient
from app.repositories.base import UserRecord
from tests.conftest import TODAY


def user(name: str) -> UserRecord:
    return UserRecord(
        uuid.uuid5(uuid.NAMESPACE_DNS, name),
        name,
        f"{name}@x.dev",
        None,
        "member",
        datetime.now(UTC),
    )


def ctx(name: str = "alice") -> ToolContext:
    return ToolContext(user=user(name), hris=DemoHrisClient(today=TODAY), today=TODAY)


async def run(name: str, args: dict[str, Any] | None = None, who: str = "alice") -> Any:
    return await build_hr_registry().execute(ToolCall("c1", name, args or {}), ctx(who))


def test_every_tool_has_a_clean_object_schema() -> None:
    for spec in build_hr_registry().specs():
        assert spec.parameters["type"] == "object"
        assert "title" not in json.dumps(spec.parameters)  # noise for small models
        assert spec.description


async def test_unknown_tool_is_a_readable_error() -> None:
    result = await run("deleteEverything")
    assert (result.status, result.error_code, result.ui) == ("error", "unknown_tool", None)
    assert "getLeaveBalance" in result.for_model()  # tells the model what does exist


async def test_invalid_arguments_are_reported_not_raised() -> None:
    result = await run("getLeaveRequests", {"status": "maybe"})
    assert (result.status, result.error_code) == ("error", "invalid_arguments")
    assert "status" in result.data["message"]


async def test_arguments_are_normalised() -> None:
    result = await run("getLeaveRequests", {"status": " Pending "})
    assert result.status == "ok"
    assert all(item["status"] == "pending" for item in result.data["items"])


async def test_model_cannot_choose_whose_data_to_read() -> None:
    """Identity comes from the server's context; an injected user_id is simply ignored."""
    as_alice = await run("getLeaveBalance", {"user_id": str(user("bob").id)}, who="alice")
    alice = await run("getLeaveBalance", who="alice")
    bob = await run("getLeaveBalance", who="bob")
    assert as_alice.data == alice.data
    assert alice.data != bob.data  # different people, different (synthetic) records


async def test_demo_data_is_stable_for_a_user() -> None:
    first, second = (
        await run("getHrRequests", {"status": "all"}),
        await run("getHrRequests", {"status": "all"}),
    )
    assert first.data == second.data


@pytest.mark.parametrize(
    ("tool", "ui"),
    [(t.name, t.ui) for t in HR_TOOLS],
)
async def test_each_tool_returns_its_card_type(tool: str, ui: str) -> None:
    result = await run(tool)
    assert result.status == "ok"
    assert result.ui == ui
    json.dumps(result.data)  # must be JSON-serialisable for the stream and the database


async def test_filters() -> None:
    open_requests = await run("getHrRequests", {"status": "open"})
    assert all(r["status"] != "resolved" for r in open_requests.data["items"])
    upcoming = await run("getAppointments", {"when": "upcoming"})
    assert all(a["start"][:10] >= TODAY.isoformat() for a in upcoming.data["items"])
    unread = await run("getNotifications", {"unread_only": True})
    assert all(not n["read"] for n in unread.data["items"])


async def test_leave_balance_maths() -> None:
    result = await run("getLeaveBalance")
    for item in result.data["items"]:
        assert item["remaining"] == item["entitled"] - item["used"]


async def _slow(_: NoArgs, __: ToolContext) -> dict[str, Any]:
    await asyncio.sleep(1)
    return {}


async def _broken(_: NoArgs, __: ToolContext) -> dict[str, Any]:
    raise RuntimeError("database password=secret is wrong")


async def test_timeout_and_crash_become_safe_errors() -> None:
    registry = ToolRegistry(
        [
            Tool("slow", "d", "Working", "x", NoArgs, _slow),
            Tool("broken", "d", "Working", "x", NoArgs, _broken),
        ],
        timeout_s=0.05,
    )
    slow = await registry.execute(ToolCall("1", "slow", {}), ctx())
    broken = await registry.execute(ToolCall("2", "broken", {}), ctx())
    assert slow.error_code == "tool_timeout"
    assert broken.error_code == "tool_failed"
    assert "secret" not in broken.for_model()  # internals never reach the model or the app


async def _huge(_: NoArgs, __: ToolContext) -> dict[str, Any]:
    return {"items": [{"text": "x" * 500} for _ in range(100)]}


async def test_oversized_results_are_truncated() -> None:
    registry = ToolRegistry([Tool("huge", "d", "Working", "x", NoArgs, _huge)])
    result = await registry.execute(ToolCall("1", "huge", {}), ctx())
    assert result.data["truncated"] is True
    assert len(result.for_model()) <= 12_000


async def test_demo_leave_requests_are_plausible() -> None:
    hris = DemoHrisClient(today=TODAY)
    for _ in range(20):  # many users, so every random branch is exercised
        for r in await hris.leave_requests(uuid.uuid4()):
            assert r.submitted_on <= TODAY
            assert r.end >= r.start
            if r.status == "pending":
                assert r.start > TODAY
