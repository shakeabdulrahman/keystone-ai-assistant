"""Write actions: the assistant proposes, the employee confirms, the change happens once."""

import asyncio
import json
import uuid
from datetime import timedelta
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient

from app.ai.llm.fake import FakeLLMProvider
from app.ai.llm.types import ToolCall
from app.ai.tools.action_tools import DASH, working_days
from tests.conftest import NOW, TODAY, Harness, as_user, build_test_app
from tests.sse import Frame, parse_sse

ALICE, BOB = as_user("alice"), as_user("bob")
# TODAY is Thursday 2026-10-15. Generated demo leave never starts after TODAY + 45 days,
# so these dates can't collide with a (random) test user's existing requests.
MON, TUE, WED = "2026-12-14", "2026-12-15", "2026-12-16"


def leave(start: str = MON, end: str = TUE, kind: str = "annual", call_id: str = "c1") -> ToolCall:
    return ToolCall(
        call_id, "submitLeaveRequest", {"leave_type": kind, "start_date": start, "end_date": end}
    )


def client_for(harness: Harness) -> AsyncClient:
    return AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test")


async def ask(
    c: AsyncClient, text: str, headers: dict[str, str] = ALICE, conversation_id: str | None = None
) -> list[Frame]:
    body: dict[str, Any] = {"client_message_id": str(uuid.uuid4()), "content": text}
    if conversation_id:
        body["conversation_id"] = conversation_id
    response = await c.post("/v1/chat", json=body, headers=headers)
    assert response.status_code == 200
    return parse_sse(response.text)


def confirmation(frames: list[Frame]) -> dict[str, Any]:
    [frame] = [f for f in frames if f.event == "confirmation.required"]
    return frame.data


async def user_id(harness: Harness, uid: str = "alice") -> uuid.UUID:
    return (await harness.users.get_or_create(uid, None, None)).id


def proposing(
    *calls: ToolCall, reply: str = "I've prepared it. Please confirm on the card."
) -> FakeLLMProvider:
    return FakeLLMProvider(turns=[list(calls), reply])


async def test_proposal_streams_a_card_and_changes_nothing() -> None:
    harness = build_test_app(proposing(leave()))
    async with client_for(harness) as c:
        frames = await ask(c, "Book annual leave on 26 and 27 October")

    types = [f.event for f in frames]
    assert (
        types.index("tool.started")
        < types.index("tool.result")
        < types.index("confirmation.required")
    )
    result = next(f for f in frames if f.event == "tool.result").data
    assert (result["status"], result["ui"], result["data"]["status"]) == (
        "ok",
        None,
        "awaiting_confirmation",
    )

    card = confirmation(frames)
    assert card["title"] == "Request annual leave" and card["confirm_label"] == "Submit request"
    fields = {f["label"]: f["value"] for f in card["fields"]}
    assert fields["Dates"] == f"Mon 14 Dec 2026{DASH}Tue 15 Dec 2026"
    assert fields["Working days"] == "2"
    assert card["expires_at"] == (NOW + timedelta(minutes=15)).isoformat()

    # The model is told it's waiting, not done.
    tool_message = harness.provider.requests[1].messages[-1]
    assert json.loads(tool_message.content)["status"] == "awaiting_confirmation"
    assert "Never say the change is done" in harness.provider.requests[1].system

    # Nothing happened in the HR system.
    requests = await harness.hris.leave_requests(await user_id(harness))
    assert all(not r.id.startswith("LR-105") for r in requests)
    [stored] = harness.actions.actions.values()
    assert stored.status == "pending" and stored.arguments["start_date"] == MON


async def test_confirm_runs_once_and_is_idempotent() -> None:
    harness = build_test_app(proposing(leave()))
    async with client_for(harness) as c:
        action_id = confirmation(await ask(c, "Book leave"))["action_id"]
        before = {
            b.leave_type: b.remaining_days
            for b in await harness.hris.leave_balances(await user_id(harness))
        }

        first = (await c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE)).json()
        again = (await c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE)).json()

    assert first["status"] == "executed"
    assert first["result"] == {
        "reference": "LR-1050",
        "message": "Leave request LR-1050 submitted. Your manager will review it.",
    }
    assert again == first  # same answer, not a second request
    uid = await user_id(harness)
    created = [r for r in await harness.hris.leave_requests(uid) if r.id.startswith("LR-105")]
    assert [(r.id, r.status, r.days) for r in created] == [("LR-1050", "pending", 2.0)]
    after = {b.leave_type: b.remaining_days for b in await harness.hris.leave_balances(uid)}
    assert after["Annual"] == before["Annual"] - 2


async def test_simultaneous_confirms_execute_once() -> None:
    harness = build_test_app(proposing(leave()))
    async with client_for(harness) as c:
        action_id = confirmation(await ask(c, "Book leave"))["action_id"]
        responses = await asyncio.gather(
            *(c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE) for _ in range(5))
        )
    statuses = sorted(r.status_code for r in responses)
    executed = [r.json() for r in responses if r.status_code == 200]
    assert executed and all(r["result"]["reference"] == "LR-1050" for r in executed)
    assert all(code in (200, 409) for code in statuses)
    created = [
        r
        for r in await harness.hris.leave_requests(await user_id(harness))
        if r.id.startswith("LR-105")
    ]
    assert len(created) == 1


async def test_cancel_then_confirm_is_refused() -> None:
    harness = build_test_app(proposing(leave()))
    async with client_for(harness) as c:
        action_id = confirmation(await ask(c, "Book leave"))["action_id"]
        cancelled = await c.post(f"/v1/actions/{action_id}/cancel", headers=ALICE)
        assert cancelled.json()["status"] == "cancelled"
        assert (await c.post(f"/v1/actions/{action_id}/cancel", headers=ALICE)).status_code == 200
        refused = await c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE)
    assert refused.status_code == 409 and refused.json()["code"] == "action_cancelled"


async def test_proposals_expire() -> None:
    now = [NOW]
    harness = build_test_app(proposing(leave()), clock=lambda: now[0])
    async with client_for(harness) as c:
        action_id = confirmation(await ask(c, "Book leave"))["action_id"]
        now[0] = NOW + timedelta(minutes=16)
        refused = await c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE)
        shown = (await c.get(f"/v1/actions/{action_id}", headers=ALICE)).json()
    assert refused.status_code == 409 and refused.json()["code"] == "action_expired"
    assert "prepare it again" in refused.json()["detail"]
    assert shown["status"] == "expired"


async def test_other_users_cannot_see_or_confirm_an_action() -> None:
    harness = build_test_app(proposing(leave()))
    async with client_for(harness) as c:
        action_id = confirmation(await ask(c, "Book leave"))["action_id"]
        for path in ("", "/confirm", "/cancel"):
            method = c.get if path == "" else c.post
            response = await method(f"/v1/actions/{action_id}{path}", headers=BOB)
            assert response.status_code == 404
        assert (
            await c.post(f"/v1/actions/{uuid.uuid4()}/confirm", headers=ALICE)
        ).status_code == 404
    assert harness.actions.actions[uuid.UUID(action_id)].status == "pending"


@pytest.mark.parametrize(
    ("call", "reason"),
    [
        (
            leave("2026-11-02", "2026-11-10", kind="casual"),
            "of casual leave left, but this request needs 7 days",
        ),
        (leave("2026-10-24", "2026-10-25"), "all on a weekend"),
        (leave("2026-10-01", "2026-10-02"), "can't start in the past"),
        (leave("2026-10-20", "2026-10-20", kind="sick"), "can't be booked in advance"),
        (leave(TUE, MON), "end_date is before start_date"),
        (
            ToolCall("c1", "cancelLeaveRequest", {"request_id": "LR-9999"}),
            "couldn't find leave request LR-9999",
        ),
        (ToolCall("c1", "completeTask", {"task_id": "T-999"}), "couldn't find task T-999"),
        (
            ToolCall("c1", "createHrRequest", {"category": "Legal", "title": "Contract question"}),
            "category",
        ),
    ],
)
async def test_rule_breaking_requests_are_rejected_before_any_card(
    call: ToolCall, reason: str
) -> None:
    harness = build_test_app(proposing(call, reply="Sorry, I can't do that."))
    async with client_for(harness) as c:
        frames = await ask(c, "Do it")
    assert "confirmation.required" not in [f.event for f in frames]
    result = next(f for f in frames if f.event == "tool.result").data
    assert result["status"] == "error"
    assert reason in result["data"]["message"]
    assert reason in json.loads(harness.provider.requests[1].messages[-1].content)["detail"]
    assert harness.actions.actions == {}


async def test_rules_are_checked_again_at_confirmation() -> None:
    harness = build_test_app(proposing(leave(MON, MON, kind="casual")))
    async with client_for(harness) as c:
        action_id = confirmation(await ask(c, "Book casual leave"))["action_id"]
        # Meanwhile the balance is used up elsewhere (e.g. in the HR portal).
        uid = await user_id(harness)
        left = {b.leave_type: b for b in await harness.hris.leave_balances(uid)}[
            "Casual"
        ].remaining_days
        await harness.hris.submit_leave(uid, "Casual", TODAY, TODAY, left)

        outcome = (await c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE)).json()
    assert outcome["status"] == "failed"
    assert outcome["error"]["code"] == "rejected"
    assert "of casual leave left" in outcome["error"]["message"]
    assert [
        r.start for r in await harness.hris.leave_requests(uid) if r.id.startswith("LR-105")
    ] == [TODAY]


async def test_overlapping_leave_is_rejected() -> None:
    harness = build_test_app(
        FakeLLMProvider(turns=[[leave()], "Prepared.", [leave(TUE, WED)], "No."])
    )
    async with client_for(harness) as c:
        frames = await ask(c, "Book leave")
        await c.post(f"/v1/actions/{confirmation(frames)['action_id']}/confirm", headers=ALICE)
        second = await ask(
            c, "And the day after too", conversation_id=frames[0].data["conversation_id"]
        )
    result = next(f for f in second if f.event == "tool.result").data
    assert "overlap your pending annual leave LR-1050" in result["data"]["message"]


async def test_only_one_action_per_answer() -> None:
    harness = build_test_app(
        proposing(
            leave(call_id="c1"),
            ToolCall("c2", "createHrRequest", {"category": "it", "title": "New monitor please"}),
        )
    )
    async with client_for(harness) as c:
        frames = await ask(c, "Book leave and order a monitor")
    assert len([f for f in frames if f.event == "confirmation.required"]) == 1
    results = [f.data for f in frames if f.event == "tool.result"]
    assert results[1]["status"] == "error"
    assert "one change can be prepared at a time" in results[1]["data"]["message"]
    assert len(harness.actions.actions) == 1


async def test_raising_an_hr_request_executes() -> None:
    call = ToolCall(
        "c1",
        "createHrRequest",
        {"category": "payroll", "title": "Wrong tax code", "details": "Since May"},
    )
    harness = build_test_app(proposing(call))
    async with client_for(harness) as c:
        frames = await ask(c, "Raise a payroll ticket")
        fields = {f["label"]: f["value"] for f in confirmation(frames)["fields"]}
        assert fields == {"Team": "Payroll", "Subject": "Wrong tax code", "Details": "Since May"}
        outcome = (
            await c.post(f"/v1/actions/{confirmation(frames)['action_id']}/confirm", headers=ALICE)
        ).json()
    assert outcome["result"]["reference"] == "HR-2230"
    newest = (await harness.hris.hr_requests(await user_id(harness)))[0]
    assert (newest.id, newest.category, newest.status) == ("HR-2230", "Payroll", "submitted")


async def test_completing_a_task_executes() -> None:
    provider = FakeLLMProvider(turns=["placeholder", "Please confirm."])
    harness = build_test_app(provider)
    uid = await user_id(harness)
    pending = next(t for t in await harness.hris.tasks(uid) if t.status == "pending")
    provider.turns = [
        [ToolCall("c1", "completeTask", {"task_id": pending.id.lower()})],
        "Please confirm.",
    ]
    async with client_for(harness) as c:
        frames = await ask(c, "Mark it done")
        outcome = (
            await c.post(f"/v1/actions/{confirmation(frames)['action_id']}/confirm", headers=ALICE)
        ).json()
    assert outcome["status"] == "executed" and outcome["result"]["reference"] == pending.id
    assert {t.id: t.status for t in await harness.hris.tasks(uid)}[pending.id] == "done"


async def test_withdrawing_leave_gives_the_days_back() -> None:
    harness = build_test_app(
        FakeLLMProvider(
            turns=[
                [leave()],
                "Prepared.",
                [ToolCall("c2", "cancelLeaveRequest", {"request_id": "lr-1050"})],
                "Prepared.",
            ]
        )
    )
    async with client_for(harness) as c:
        frames = await ask(c, "Book leave")
        await c.post(f"/v1/actions/{confirmation(frames)['action_id']}/confirm", headers=ALICE)
        uid = await user_id(harness)
        booked = {b.leave_type: b.remaining_days for b in await harness.hris.leave_balances(uid)}[
            "Annual"
        ]
        second = await ask(c, "Cancel it", conversation_id=frames[0].data["conversation_id"])
        card = confirmation(second)
        assert card["title"] == "Withdraw leave request"
        await c.post(f"/v1/actions/{card['action_id']}/confirm", headers=ALICE)
    after = {b.leave_type: b.remaining_days for b in await harness.hris.leave_balances(uid)}[
        "Annual"
    ]
    assert after == booked + 2


async def test_history_shows_current_state_and_the_model_knows_the_outcome() -> None:
    harness = build_test_app(
        FakeLLMProvider(turns=[[leave()], "Please confirm.", "Yes, LR-1050 went through."])
    )
    async with client_for(harness) as c:
        frames = await ask(c, "Book leave")
        conversation_id = frames[0].data["conversation_id"]
        action_id = confirmation(frames)["action_id"]
        await c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE)

        page = (await c.get(f"/v1/conversations/{conversation_id}/messages", headers=ALICE)).json()
        await ask(c, "Did my leave go through?", conversation_id=conversation_id)

    part = next(p for p in page["items"][-1]["parts"] if p["type"] == "confirmation")
    assert part["action_id"] == action_id
    assert part["status"] == "executed" and part["result"]["reference"] == "LR-1050"
    context = harness.provider.requests[-1].messages
    previous_answer = next(m for m in reversed(context) if m.role == "assistant")
    assert (
        "[Action 'Request annual leave': confirmed and done, reference LR-1050]"
        in previous_answer.content
    )


async def test_an_unexpected_failure_is_reported_honestly() -> None:
    harness = build_test_app(proposing(leave()))

    async def broken(*_: object, **__: object) -> None:
        raise RuntimeError("connection reset by HRIS, password=hunter2")

    harness.hris.submit_leave = broken  # type: ignore[method-assign]
    async with client_for(harness) as c:
        action_id = confirmation(await ask(c, "Book leave"))["action_id"]
        outcome = (await c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE)).json()
    assert outcome["status"] == "failed" and outcome["error"]["code"] == "action_failed"
    assert "Check your requests" in outcome["error"]["message"]
    assert "hunter2" not in json.dumps(outcome)


async def test_demo_mode_prepares_leave_from_a_plain_request() -> None:
    harness = build_test_app(FakeLLMProvider(demo=True))
    # Demo mode books the Monday after next; keep this random user's calendar free.
    harness.hris._generated_leave = lambda _: []  # type: ignore[method-assign]
    async with client_for(harness) as c:
        frames = await ask(c, "Please book annual leave for me")
    card = confirmation(frames)
    assert card["tool"] == "submitLeaveRequest"
    text = "".join(f.data["text"] for f in frames if f.event == "text.delta")
    assert "tap the button to confirm" in text


def test_working_days_skip_weekends() -> None:
    from datetime import date

    assert working_days(date(2026, 10, 23), date(2026, 10, 26)) == 2  # Fri..Mon
    assert working_days(date(2026, 10, 24), date(2026, 10, 25)) == 0
