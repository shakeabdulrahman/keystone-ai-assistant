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
from app.rag.embeddings import HashingEmbedder
from app.rag.eval import evaluate, load_cases
from app.rag.ingest import ingest
from app.rag.retriever import Retriever
from app.rag.sql_store import SqlKnowledgeStore
from app.repositories.base import ActionRecord, DuplicateMessageError, NewMessage
from app.repositories.sql import (
    SqlActionRepository,
    SqlConversationRepository,
    SqlToolAuditRepository,
    SqlUserRepository,
)
from app.services.chat_service import AgentTools, ChatService
from tests.conftest import KNOWLEDGE_DIR, as_user, build_test_app
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
        await conn.execute(
            text("TRUNCATE users, conversations, messages, tool_executions, documents CASCADE")
        )
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


async def test_knowledge_base_on_postgres(db: Database, tmp_path: object) -> None:
    """pgvector cosine search, full-text search and idempotent ingestion on the real database."""
    store, embedder = SqlKnowledgeStore(db.session_factory), HashingEmbedder()
    first = await ingest(KNOWLEDGE_DIR, store, embedder)
    assert len(first.added) >= 7
    assert (await ingest(KNOWLEDGE_DIR, store, embedder)).chunks == 0

    retriever = Retriever(store, embedder)
    hits = await retriever.search("How many days can I carry over to next year?")
    assert (hits[0].chunk.document_id, hits[0].chunk.heading) == (
        "leave-policy",
        "Carry-over and expiry",
    )
    text_hits = await store.text_search("VPN", 5)
    assert text_hits and all(h.chunk.document_id == "remote-work" for h in text_hits)
    assert await store.text_search("the and of", 5) == []  # stopwords only: no query at all
    # Vectors from another model are never compared with these.
    assert await store.vector_search(await embedder.embed_query("leave"), "other-model", 5) == []

    # Same quality as the in-memory store: the SQL ranking agrees.
    result = await evaluate(retriever, load_cases(KNOWLEDGE_DIR / "eval" / "retrieval.jsonl"))
    assert result.document_hit_rate >= 0.9

    doc = await store.get_document("leave-policy")
    assert doc is not None and doc.embedding_model == "hashing-v1"
    await store.delete_documents(["leave-policy"])
    assert await store.get_document("leave-policy") is None
    remaining = await store.text_search("carry over annual leave", 10)
    assert all(h.chunk.document_id != "leave-policy" for h in remaining)  # chunks cascade


async def test_action_claims_are_atomic_on_postgres(db: Database) -> None:
    """Ten confirms racing for one action: exactly one wins the pending → executing step."""
    from datetime import UTC, datetime, timedelta

    users = SqlUserRepository(db.session_factory)
    conversations = SqlConversationRepository(db.session_factory)
    actions = SqlActionRepository(db.session_factory)
    user = await users.get_or_create("alice", None, None)
    other = await users.get_or_create("bob", None, None)
    conversation = await conversations.create(user.id, "Leave")
    now = datetime.now(UTC)
    action = ActionRecord(
        id=uuid.uuid4(),
        user_id=user.id,
        conversation_id=conversation.id,
        message_id=uuid.uuid4(),
        call_id="c1",
        tool_name="submitLeaveRequest",
        arguments={"leave_type": "annual"},
        card={"title": "Request annual leave", "fields": []},
        status="pending",
        created_at=now,
        expires_at=now + timedelta(minutes=15),
    )
    await actions.create(action)

    assert await actions.get_owned(other.id, action.id) is None  # not Bob's
    assert await actions.transition(other.id, action.id, "pending", "executing", now) is None

    results = await asyncio.gather(
        *(actions.transition(user.id, action.id, "pending", "executing", now) for _ in range(10))
    )
    assert sum(r is not None for r in results) == 1

    done = await actions.finish(
        action.id, "executed", result={"reference": "LR-1050", "message": "ok"}
    )
    assert done.status == "executed" and done.result == {"reference": "LR-1050", "message": "ok"}
    assert (await actions.get_many([action.id, uuid.uuid4()]))[action.id].status == "executed"

    # Expired proposals can't be claimed.
    stale = ActionRecord(
        **{
            **action.__dict__,
            "id": uuid.uuid4(),
            "status": "pending",
            "expires_at": now - timedelta(seconds=1),
        }
    )
    await actions.create(stale)
    assert (
        await actions.transition(
            user.id, stale.id, "pending", "executing", now, unexpired_only=True
        )
        is None
    )
