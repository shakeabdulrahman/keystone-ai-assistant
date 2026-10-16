"""The agent loop end to end: model asks for tools, the server runs them, the model answers."""

import uuid
from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient

from app.ai.llm.fake import FakeLLMProvider
from app.ai.llm.types import ToolCall
from tests.conftest import Harness, as_user, build_test_app
from tests.sse import Frame, parse_sse

ALICE = as_user("alice")


async def ask(c: AsyncClient, text: str, headers: dict[str, str] = ALICE) -> list[Frame]:
    response = await c.post(
        "/v1/chat",
        json={"client_message_id": str(uuid.uuid4()), "content": text},
        headers=headers,
    )
    assert response.status_code == 200
    return parse_sse(response.text)


@pytest.fixture
async def balance_flow() -> AsyncIterator[tuple[AsyncClient, Harness]]:
    provider = FakeLLMProvider(
        turns=[
            [ToolCall("call_1", "getLeaveBalance", {})],
            "You have plenty of annual leave left.",
        ]
    )
    harness = build_test_app(provider)
    transport = ASGITransport(app=harness.app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c, harness


async def test_tool_call_streams_started_result_then_answer(
    balance_flow: tuple[AsyncClient, Harness],
) -> None:
    c, _ = balance_flow
    frames = await ask(c, "How many leave days do I have?")

    types = [f.event for f in frames]
    assert types[:3] == ["message.started", "tool.started", "tool.result"]
    assert types[-1] == "message.completed"
    started, result = frames[1].data, frames[2].data
    assert started["call_id"] == result["call_id"] == "call_1"
    assert started["label"] == "Checking your leave balance"
    assert (result["status"], result["ui"], result["tool"]) == (
        "ok",
        "leave_balance",
        "getLeaveBalance",
    )
    assert {i["type"] for i in result["data"]["items"]} == {"Annual", "Sick", "Casual"}
    text = "".join(f.data["text"] for f in frames if f.event == "text.delta")
    assert text == "You have plenty of annual leave left."


async def test_tool_result_is_fed_back_to_the_model(
    balance_flow: tuple[AsyncClient, Harness],
) -> None:
    c, harness = balance_flow
    await ask(c, "How many leave days do I have?")

    first, second = harness.provider.requests
    assert {t.name for t in first.tools} >= {"getLeaveBalance", "getTasks"}
    assistant, tool = second.messages[-2], second.messages[-1]
    assert assistant.role == "assistant"
    assert assistant.tool_calls[0].name == "getLeaveBalance"
    assert (tool.role, tool.tool_call_id, tool.tool_name) == ("tool", "call_1", "getLeaveBalance")
    assert '"Annual"' in tool.content


async def test_answer_is_saved_with_its_card_and_audited(
    balance_flow: tuple[AsyncClient, Harness],
) -> None:
    c, harness = balance_flow
    frames = await ask(c, "How many leave days do I have?")
    conversation_id = frames[0].data["conversation_id"]

    messages = (await c.get(f"/v1/conversations/{conversation_id}/messages", headers=ALICE)).json()[
        "items"
    ]
    answer = messages[-1]
    assert answer["content"] == "You have plenty of annual leave left."
    assert answer["parts"][0]["ui"] == "leave_balance"
    assert answer["parts"][0]["data"] == frames[2].data["data"]

    [entry] = harness.audit.entries
    assert (entry.tool_name, entry.status, str(entry.message_id)) == (
        "getLeaveBalance",
        "ok",
        frames[0].data["message_id"],
    )


async def test_bad_arguments_are_returned_to_the_model_which_recovers() -> None:
    provider = FakeLLMProvider(
        turns=[
            [ToolCall("c1", "getTasks", {"status": "whenever"})],
            [ToolCall("c2", "getTasks", {"status": "pending"})],
            "You have a few tasks pending.",
        ]
    )
    harness = build_test_app(provider)
    async with AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test") as c:
        frames = await ask(c, "What are my tasks?")

    results = [f.data for f in frames if f.event == "tool.result"]
    assert [r["status"] for r in results] == ["error", "ok"]
    assert results[0]["ui"] is None
    assert "invalid_arguments" in provider.requests[1].messages[-1].content
    assert frames[-1].event == "message.completed"


async def test_step_limit_stops_a_looping_model() -> None:
    provider = FakeLLMProvider(
        turns=[[ToolCall("again", "getNotifications", {})]]
    )  # repeats forever
    harness = build_test_app(provider, max_steps=3)
    async with AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test") as c:
        frames = await ask(c, "Notifications?")

    assert len(provider.requests) == 3
    assert sum(f.event == "tool.result" for f in frames) == 2
    text = "".join(f.data["text"] for f in frames if f.event == "text.delta")
    assert "couldn't finish" in text
    assert frames[-1].event == "message.completed"


async def test_several_tools_in_one_step() -> None:
    provider = FakeLLMProvider(
        turns=[
            [ToolCall("a", "getTasks", {}), ToolCall("b", "getAppointments", {})],
            "Here is your week.",
        ]
    )
    harness = build_test_app(provider)
    async with AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test") as c:
        frames = await ask(c, "What's on this week?")

    assert [f.data["ui"] for f in frames if f.event == "tool.result"] == ["tasks", "appointments"]


async def test_users_get_their_own_records() -> None:
    provider = FakeLLMProvider(turns=[[ToolCall("x", "getUserProfile", {})], "Done."])
    alice_app = build_test_app(provider)
    async with AsyncClient(transport=ASGITransport(app=alice_app.app), base_url="http://test") as c:
        alice = [
            f
            for f in await ask(c, "Who am I?", as_user("alice", "alice@x.dev"))
            if f.event == "tool.result"
        ]
        provider.requests.clear()
        bob = [
            f
            for f in await ask(c, "Who am I?", as_user("bob", "bob@x.dev"))
            if f.event == "tool.result"
        ]

    assert alice[0].data["data"]["email"] == "alice@x.dev"
    assert bob[0].data["data"]["email"] == "bob@x.dev"


async def test_demo_mode_routes_by_keyword() -> None:
    harness = build_test_app(FakeLLMProvider(demo=True))
    async with AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test") as c:
        frames = await ask(c, "How many leave days do I have left?")

    tools = [f.data["tool"] for f in frames if f.event == "tool.started"]
    assert tools == ["getLeaveBalance"]
    assert frames[-1].event == "message.completed"
