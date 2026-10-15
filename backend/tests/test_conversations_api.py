import uuid
from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient

from app.ai.llm.fake import FakeLLMProvider
from tests.conftest import Harness, as_user, build_test_app
from tests.sse import parse_sse

ALICE, BOB = as_user("alice"), as_user("bob")


@pytest.fixture
async def ctx() -> AsyncIterator[tuple[AsyncClient, Harness]]:
    harness = build_test_app(FakeLLMProvider("Sure, here is the answer."))
    transport = ASGITransport(app=harness.app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c, harness


async def chat(c: AsyncClient, text: str, conversation_id: str | None = None) -> str:
    body: dict[str, object] = {"client_message_id": str(uuid.uuid4()), "content": text}
    if conversation_id:
        body["conversation_id"] = conversation_id
    response = await c.post("/v1/chat", json=body, headers=ALICE)
    started = parse_sse(response.text)[0]
    conversation: str = started.data["conversation_id"]
    return conversation


async def test_list_is_newest_first_with_fallback_titles(
    ctx: tuple[AsyncClient, Harness],
) -> None:
    c, _ = ctx
    first = await chat(c, "How many leave days do I get per year?")
    second = await chat(c, "What is the dress code?")
    await chat(c, "And on Fridays?", second)

    response = await c.get("/v1/conversations", headers=ALICE)

    assert response.status_code == 200
    items = response.json()["items"]
    assert [i["id"] for i in items] == [second, first]
    assert items[1]["title"] == "How many leave days do I get per year?"


async def test_pagination_walks_every_conversation_once(ctx: tuple[AsyncClient, Harness]) -> None:
    c, _ = ctx
    created = [await chat(c, f"Question {i}") for i in range(5)]

    seen: list[str] = []
    cursor = None
    while True:
        params = {"limit": "2", **({"cursor": cursor} if cursor else {})}
        page = (await c.get("/v1/conversations", params=params, headers=ALICE)).json()
        seen += [i["id"] for i in page["items"]]
        cursor = page["next_cursor"]
        if cursor is None:
            break

    assert seen == list(reversed(created))


async def test_users_only_see_their_own_conversations(ctx: tuple[AsyncClient, Harness]) -> None:
    c, _ = ctx
    alice_conversation = await chat(c, "Private")

    assert (await c.get("/v1/conversations", headers=BOB)).json()["items"] == []
    for method, path in [
        ("GET", f"/v1/conversations/{alice_conversation}"),
        ("GET", f"/v1/conversations/{alice_conversation}/messages"),
        ("DELETE", f"/v1/conversations/{alice_conversation}"),
    ]:
        response = await c.request(method, path, headers=BOB)
        assert response.status_code == 404, (method, path)
    response = await c.patch(
        f"/v1/conversations/{alice_conversation}", json={"title": "pwned"}, headers=BOB
    )
    assert response.status_code == 404


async def test_messages_are_returned_oldest_first(ctx: tuple[AsyncClient, Harness]) -> None:
    c, _ = ctx
    conversation = await chat(c, "Q1")
    await chat(c, "Q2", conversation)

    body = (await c.get(f"/v1/conversations/{conversation}/messages", headers=ALICE)).json()

    assert [(m["role"], m["content"]) for m in body["items"]] == [
        ("user", "Q1"),
        ("assistant", "Sure, here is the answer."),
        ("user", "Q2"),
        ("assistant", "Sure, here is the answer."),
    ]
    assert body["next_cursor"] is None


async def test_message_pagination_loads_older_pages(ctx: tuple[AsyncClient, Harness]) -> None:
    c, _ = ctx
    conversation = await chat(c, "Q1")
    await chat(c, "Q2", conversation)
    url = f"/v1/conversations/{conversation}/messages"

    latest = (await c.get(url, params={"limit": "3"}, headers=ALICE)).json()
    older = (
        await c.get(url, params={"limit": "3", "cursor": latest["next_cursor"]}, headers=ALICE)
    ).json()

    answer = "Sure, here is the answer."
    assert [m["content"] for m in latest["items"]] == [answer, "Q2", answer]
    assert [m["content"] for m in older["items"]] == ["Q1"]
    assert older["next_cursor"] is None


async def test_rename_and_delete(ctx: tuple[AsyncClient, Harness]) -> None:
    c, _ = ctx
    conversation = await chat(c, "Q1")

    renamed = await c.patch(
        f"/v1/conversations/{conversation}", json={"title": "  My   leave  "}, headers=ALICE
    )
    assert renamed.json()["title"] == "My leave"

    assert (await c.delete(f"/v1/conversations/{conversation}", headers=ALICE)).status_code == 204
    assert (await c.get(f"/v1/conversations/{conversation}", headers=ALICE)).status_code == 404
    assert (await c.get("/v1/conversations", headers=ALICE)).json()["items"] == []


async def test_bad_cursor_is_400(ctx: tuple[AsyncClient, Harness]) -> None:
    c, _ = ctx
    response = await c.get("/v1/conversations", params={"cursor": "%%%"}, headers=ALICE)
    assert response.status_code == 400
