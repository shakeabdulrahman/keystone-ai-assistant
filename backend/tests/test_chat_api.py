import uuid
from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient

from app.ai.llm.base import LLMConfigurationError, LLMUnavailableError
from app.ai.llm.fake import FakeLLMProvider
from tests.conftest import Harness, as_user, build_test_app
from tests.sse import Frame, parse_sse

ALICE = as_user("alice")


@pytest.fixture
def harness() -> Harness:
    return build_test_app()


@pytest.fixture
async def client(harness: Harness) -> AsyncIterator[AsyncClient]:
    transport = ASGITransport(app=harness.app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


async def send(
    client: AsyncClient,
    content: str,
    conversation_id: str | None = None,
    cid: str | None = None,
    headers: dict[str, str] | None = None,
) -> tuple[int, list[Frame], str]:
    body: dict[str, object] = {"client_message_id": cid or str(uuid.uuid4()), "content": content}
    if conversation_id:
        body["conversation_id"] = conversation_id
    response = await client.post("/v1/chat", json=body, headers=headers or ALICE)
    frames = parse_sse(response.text) if response.status_code == 200 else []
    return response.status_code, frames, response.headers.get("content-type", "")


async def test_streams_started_deltas_completed(client: AsyncClient) -> None:
    status, frames, content_type = await send(client, "Hi")

    assert status == 200
    assert content_type.startswith("text/event-stream")
    types = [f.event for f in frames]
    assert types[0] == "message.started"
    assert set(types[1:-1]) == {"text.delta"}
    assert types[-1] == "message.completed"
    text = "".join(f.data["text"] for f in frames if f.event == "text.delta")
    assert text == "Hello from Keystone"
    assert [f.data["seq"] for f in frames] == list(range(1, len(frames) + 1))
    assert frames[-1].data["message_id"] == frames[0].data["message_id"]


async def test_conversation_and_messages_are_persisted(
    client: AsyncClient, harness: Harness
) -> None:
    _, frames, _ = await send(client, "Hi")
    conversation_id = uuid.UUID(frames[0].data["conversation_id"])

    stored = await harness.conversations.recent_messages(conversation_id, 10)
    assert [(m.role, m.content, m.status) for m in stored] == [
        ("user", "Hi", "complete"),
        ("assistant", "Hello from Keystone", "complete"),
    ]
    # The ID announced in the stream is the stored message's ID.
    assert str(stored[1].id) == frames[0].data["message_id"]
    assert str(stored[0].id) == frames[0].data["user_message_id"]


async def test_follow_up_sends_conversation_history(client: AsyncClient, harness: Harness) -> None:
    _, frames, _ = await send(client, "First question")
    conversation_id = frames[0].data["conversation_id"]

    status, frames2, _ = await send(client, "Second question", conversation_id)

    assert status == 200
    assert frames2[0].data["conversation_id"] == conversation_id
    sent = harness.provider.requests[-1].messages
    assert [(m.role, m.content) for m in sent] == [
        ("user", "First question"),
        ("assistant", "Hello from Keystone"),
        ("user", "Second question"),
    ]


async def test_history_is_limited_to_recent_messages() -> None:
    harness = build_test_app(FakeLLMProvider("ok"), history_messages=3)
    transport = ASGITransport(app=harness.app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        _, frames, _ = await send(c, "q1")
        cid = frames[0].data["conversation_id"]
        await send(c, "q2", cid)
        await send(c, "q3", cid)
    assert [m.content for m in harness.provider.requests[-1].messages] == ["q2", "ok", "q3"]


async def test_other_users_conversation_is_404(client: AsyncClient) -> None:
    _, frames, _ = await send(client, "Alice's private question")
    alice_conversation = frames[0].data["conversation_id"]

    status, _, content_type = await send(
        client, "Let me in", alice_conversation, headers=as_user("mallory")
    )

    assert status == 404
    assert content_type == "application/problem+json"


async def test_unknown_conversation_is_404(client: AsyncClient) -> None:
    status, _, _ = await send(client, "Hi", conversation_id=str(uuid.uuid4()))
    assert status == 404


async def test_duplicate_client_message_id_is_409(client: AsyncClient) -> None:
    cid = str(uuid.uuid4())
    _, frames, _ = await send(client, "Hi")
    conversation_id = frames[0].data["conversation_id"]
    await send(client, "Again", conversation_id, cid=cid)
    status, _, _ = await send(client, "Again", conversation_id, cid=cid)
    assert status == 409


@pytest.mark.parametrize("content", ["", "   ", "x" * 4001])
async def test_invalid_content_is_422(client: AsyncClient, content: str) -> None:
    status, _, _ = await send(client, content)
    assert status == 422


async def test_invalid_token_is_401(client: AsyncClient) -> None:
    status, _, _ = await send(client, "Hi", headers={"Authorization": "Bearer not-a-dev-token"})
    assert status == 401


async def test_provider_failure_becomes_error_event_and_is_saved_as_failed() -> None:
    provider = FakeLLMProvider("a b c", fail_with=LLMUnavailableError("boom"), fail_after=1)
    harness = build_test_app(provider)
    transport = ASGITransport(app=harness.app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        status, frames, _ = await send(c, "Hi")

    assert status == 200
    assert [f.event for f in frames] == ["message.started", "text.delta", "error"]
    assert frames[-1].data["code"] == "ai_unavailable"
    assert frames[-1].data["retryable"] is True
    assert "boom" not in frames[-1].data["message"]
    stored = await harness.conversations.recent_messages(
        uuid.UUID(frames[0].data["conversation_id"]), 10
    )
    assert (stored[-1].status, stored[-1].content) == ("failed", "a ")


async def test_misconfiguration_is_not_retryable() -> None:
    provider = FakeLLMProvider("x", fail_with=LLMConfigurationError("no model"), fail_after=0)
    harness = build_test_app(provider)
    transport = ASGITransport(app=harness.app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        _, frames, _ = await send(c, "Hi")
    assert frames[-1].data == {
        "seq": 2,
        "code": "ai_misconfigured",
        "message": "The assistant isn't set up correctly. Please contact support.",
        "retryable": False,
    }


async def test_failed_exchange_is_not_sent_back_as_context() -> None:
    provider = FakeLLMProvider("a b", fail_with=LLMUnavailableError("x"), fail_after=1)
    harness = build_test_app(provider)
    transport = ASGITransport(app=harness.app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        _, frames, _ = await send(c, "What is my leave balance?")
        cid = frames[0].data["conversation_id"]
        provider.fail_with = None
        await send(c, "What is my leave balance?", cid)  # the user's retry
    assert [m.content for m in provider.requests[-1].messages] == ["What is my leave balance?"]


async def test_voice_questions_get_spoken_answer_rules() -> None:
    import uuid as _uuid

    from httpx import ASGITransport, AsyncClient

    from app.ai.llm.fake import FakeLLMProvider
    from tests.conftest import as_user, build_test_app

    harness = build_test_app(FakeLLMProvider("Fourteen days."))
    async with AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test") as c:
        for mode in ("voice", None):
            body = {"client_message_id": str(_uuid.uuid4()), "content": "How many days?"}
            if mode:
                body["input_mode"] = mode
            assert (
                await c.post("/v1/chat", json=body, headers=as_user("alice"))
            ).status_code == 200
        bad = {"client_message_id": str(_uuid.uuid4()), "content": "Hi", "input_mode": "telepathy"}
        assert (await c.post("/v1/chat", json=bad, headers=as_user("alice"))).status_code == 422

    voice, typed = harness.provider.requests[0].system, harness.provider.requests[-1].system
    assert "read aloud" in voice and 'a spoken "yes" does not confirm anything' in voice
    assert "read aloud" not in typed  # text questions are unchanged (old apps send no mode)
