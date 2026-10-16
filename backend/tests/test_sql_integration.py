"""The same behaviour, against real Postgres. Skipped unless RUN_INTEGRATION=1 (CI sets it).

Requires the database to be migrated (`alembic upgrade head`).
"""

import asyncio
import os
import uuid
from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

from app.ai.llm.fake import FakeLLMProvider
from app.ai.llm.types import ToolCall
from app.ai.tools.hr_tools import build_hr_registry
from app.core.config import Settings
from app.db.session import Database
from app.integrations.hris import DemoHrisClient
from app.repositories.base import DuplicateMessageError, NewMessage
from app.repositories.sql import (
    SqlConversationRepository,
    SqlToolAuditRepository,
    SqlUserRepository,
)
from app.services.chat_service import AgentTools, ChatService
from tests.conftest import as_user, build_test_app
from tests.sse import parse_sse

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(os.getenv("RUN_INTEGRATION") != "1", reason="needs a real database"),
]

ALICE, BOB = as_user("alice"), as_user("bob")


@pytest.fixture
async def db() -> AsyncIterator[Database]:
    database = Database(Settings())
    async with database.engine.begin() as conn:
        await conn.execute(text("TRUNCATE users, conversations, messages, tool_executions CASCADE"))
    yield database
    await database.dispose()


@pytest.fixture
async def client(db: Database) -> AsyncIterator[AsyncClient]:
    harness = build_test_app(FakeLLMProvider("Stored answer"))
    users = SqlUserRepository(db.session_factory)
    conversations = SqlConversationRepository(db.session_factory)
    harness.app.state.users = users
    harness.app.state.conversations = conversations
    harness.app.state.chat_service = ChatService(
        harness.provider, conversations, history_messages=20, max_output_tokens=64
    )
    transport = ASGITransport(app=harness.app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


async def chat(c: AsyncClient, content: str, conversation_id: str | None = None) -> str:
    body: dict[str, object] = {"client_message_id": str(uuid.uuid4()), "content": content}
    if conversation_id:
        body["conversation_id"] = conversation_id
    response = await c.post("/v1/chat", json=body, headers=ALICE)
    assert response.status_code == 200
    conversation: str = parse_sse(response.text)[0].data["conversation_id"]
    return conversation


async def test_chat_history_round_trip(client: AsyncClient) -> None:
    conversation = await chat(client, "First")
    await chat(client, "Second", conversation)

    messages = (
        await client.get(f"/v1/conversations/{conversation}/messages", headers=ALICE)
    ).json()["items"]
    assert [(m["role"], m["content"], m["status"]) for m in messages] == [
        ("user", "First", "complete"),
        ("assistant", "Stored answer", "complete"),
        ("user", "Second", "complete"),
        ("assistant", "Stored answer", "complete"),
    ]


async def test_isolation_and_pagination(client: AsyncClient) -> None:
    created = [await chat(client, f"Q{i}") for i in range(3)]

    page1 = (await client.get("/v1/conversations?limit=2", headers=ALICE)).json()
    page2 = (
        await client.get(
            "/v1/conversations", params={"limit": 2, "cursor": page1["next_cursor"]}, headers=ALICE
        )
    ).json()

    assert [i["id"] for i in page1["items"] + page2["items"]] == list(reversed(created))
    assert page2["next_cursor"] is None
    assert (await client.get("/v1/conversations", headers=BOB)).json()["items"] == []
    assert (await client.get(f"/v1/conversations/{created[0]}", headers=BOB)).status_code == 404


async def test_duplicate_client_message_hits_unique_constraint(db: Database) -> None:
    users = SqlUserRepository(db.session_factory)
    repo = SqlConversationRepository(db.session_factory)
    user = await users.get_or_create("u1", None, None)
    conversation = await repo.create(user.id, "t")
    message = NewMessage(role="user", content="hi", client_message_id="same-id")

    await repo.add_message(conversation.id, message)
    with pytest.raises(DuplicateMessageError):
        await repo.add_message(conversation.id, message)


async def test_concurrent_first_requests_create_one_user(db: Database) -> None:
    users = SqlUserRepository(db.session_factory)
    results = await asyncio.gather(*(users.get_or_create("racer", None, None) for _ in range(5)))
    assert len({u.id for u in results}) == 1


async def test_delete_me_cascades_everything(client: AsyncClient, db: Database) -> None:
    await chat(client, "Something personal")
    assert (await client.delete("/v1/me", headers=ALICE)).status_code == 204

    async with db.engine.connect() as conn:
        counts = [
            await conn.scalar(text(f"SELECT count(*) FROM {table}"))  # noqa: S608
            for table in ("users", "conversations", "messages")
        ]
    assert counts == [0, 0, 0]


async def test_tool_answer_parts_and_audit_are_persisted(db: Database) -> None:
    provider = FakeLLMProvider(turns=[[ToolCall("c1", "getLeaveBalance", {})], "Plenty left."])
    harness = build_test_app(provider)
    conversations = SqlConversationRepository(db.session_factory)
    harness.app.state.users = SqlUserRepository(db.session_factory)
    harness.app.state.conversations = conversations
    harness.app.state.chat_service = ChatService(
        provider,
        conversations,
        history_messages=20,
        max_output_tokens=64,
        tools=AgentTools(
            build_hr_registry(), DemoHrisClient(), SqlToolAuditRepository(db.session_factory)
        ),
    )
    async with AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test") as c:
        conversation = await chat(c, "Leave balance?")
        messages = (
            await c.get(f"/v1/conversations/{conversation}/messages", headers=ALICE)
        ).json()["items"]

    assert messages[-1]["parts"][0]["ui"] == "leave_balance"
    async with db.engine.connect() as conn:
        row = (
            await conn.execute(text("SELECT tool_name, status, latency_ms FROM tool_executions"))
        ).one()
    assert (row.tool_name, row.status) == ("getLeaveBalance", "ok")
