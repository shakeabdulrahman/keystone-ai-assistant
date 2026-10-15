"""Streaming mechanics: heartbeats, cancellation, and the shared contract fixtures."""

import asyncio
import contextlib
import uuid
from collections.abc import AsyncIterator
from pathlib import Path

import anyio
import pytest
from pydantic import BaseModel

from app.ai.events import (
    SSE_KEEPALIVE,
    MessageCompleted,
    MessageStarted,
    StreamError,
    TextDeltaOut,
)
from app.ai.llm.fake import FakeLLMProvider
from app.ai.llm.ollama import OllamaProvider
from app.api.chat import _encode
from app.api.streaming import with_heartbeat
from app.repositories.memory import InMemoryConversationRepository
from app.services.chat_service import ChatService, ChatTurn
from tests.sse import parse_sse

FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"
MODELS: dict[str, type[BaseModel]] = {
    "message.started": MessageStarted,
    "text.delta": TextDeltaOut,
    "message.completed": MessageCompleted,
    "error": StreamError,
}
USER = uuid.uuid4()


async def test_heartbeat_fills_silence() -> None:
    async def slow() -> AsyncIterator[str]:
        await asyncio.sleep(0.05)
        yield "data"

    items = [item async for item in with_heartbeat(slow(), interval_s=0.01)]
    assert items[-1] == "data"
    assert SSE_KEEPALIVE in items


async def test_heartbeat_passes_items_through_without_pings_when_fast() -> None:
    async def fast() -> AsyncIterator[str]:
        for i in range(3):
            yield str(i)

    assert [i async for i in with_heartbeat(fast(), interval_s=1)] == ["0", "1", "2"]


async def _service(
    provider: FakeLLMProvider | OllamaProvider,
) -> tuple[ChatService, InMemoryConversationRepository, ChatTurn]:
    repo = InMemoryConversationRepository()
    service = ChatService(provider, repo, history_messages=20, max_output_tokens=64)
    turn = await service.start_turn(USER, None, "c1", "Hi")
    return service, repo, turn


async def test_stopping_mid_stream_saves_partial_answer_as_interrupted() -> None:
    service, repo, turn = await _service(FakeLLMProvider("one two three four", delay_s=0.01))

    stream = service.stream_turn(turn)
    received = [await anext(stream) for _ in range(3)]  # started + two deltas
    await stream.aclose()  # what happens when the client disconnects

    assert isinstance(received[0], MessageStarted)
    assistant = (await repo.recent_messages(turn.conversation_id, 10))[-1]
    assert (assistant.role, assistant.status, assistant.content) == (
        "assistant",
        "interrupted",
        "one two ",
    )


async def test_cancelling_the_response_task_saves_interrupted() -> None:
    service, repo, turn = await _service(FakeLLMProvider("w " * 200, delay_s=0.01))

    async def consume() -> None:
        async for _ in with_heartbeat(_encode(service, turn), interval_s=1):
            pass

    async with anyio.create_task_group() as tg:  # the way the web server cancels
        tg.start_soon(consume)
        await anyio.sleep(0.05)
        tg.cancel_scope.cancel()

    assistant = (await repo.recent_messages(turn.conversation_id, 10))[-1]
    assert assistant.status == "interrupted"


async def _start_endless_ndjson_server() -> tuple[asyncio.Server, int, asyncio.Event]:
    """A tiny HTTP server that streams Ollama-style NDJSON forever.

    `closed` is set the moment the client closes the TCP connection, which is how
    we observe whether the gateway really stopped the upstream generation.
    """
    closed = asyncio.Event()

    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        data = b""
        while b"\r\n\r\n" not in data:
            data += await reader.read(4096)
        writer.write(
            b"HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\n"
            b"Transfer-Encoding: chunked\r\n\r\n"
        )
        eof = asyncio.ensure_future(reader.read(1))  # completes when the client hangs up
        try:
            while not eof.done():
                line = b'{"message":{"content":"x "},"done":false}\n'
                writer.write(f"{len(line):x}\r\n".encode() + line + b"\r\n")
                await writer.drain()
                await asyncio.sleep(0.01)
        except ConnectionError:
            pass
        finally:
            closed.set()
            eof.cancel()
            writer.close()

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    return server, port, closed


async def test_disconnect_closes_upstream_model_connection() -> None:
    """Regression: Starlette cancels a disconnected stream through an anyio cancel scope,
    which re-cancels every await during cleanup. Before the fix the upstream HTTP
    connection stayed open and the model kept generating after the user left."""
    server, port, upstream_closed = await _start_endless_ndjson_server()
    provider = OllamaProvider(f"http://127.0.0.1:{port}", "test-model")
    service, repo, turn = await _service(provider)

    async def consume() -> None:
        async for _ in with_heartbeat(_encode(service, turn), interval_s=1):
            pass

    try:
        async with anyio.create_task_group() as tg:
            tg.start_soon(consume)
            await anyio.sleep(0.2)
            tg.cancel_scope.cancel()  # what the server does when the phone disconnects

        await asyncio.wait_for(upstream_closed.wait(), timeout=1.0)
        # ...and the partial answer was still saved despite the cancellation.
        assistant = (await repo.recent_messages(turn.conversation_id, 10))[-1]
        assert assistant.status == "interrupted"
        assert assistant.content.startswith("x ")
    finally:
        await provider.aclose()
        server.close()


@pytest.mark.parametrize(
    "name", ["chat_stream_basic.sse", "chat_stream_error.sse", "chat_stream_forward_compat.sse"]
)
def test_contract_fixtures_match_server_models(name: str) -> None:
    """Every known event in the shared fixtures must be something the server can produce."""
    frames = parse_sse((FIXTURES / name).read_text())
    assert frames[0].event == "message.started"
    assert frames[-1].event in {"message.completed", "error"}
    for frame in frames:
        model = MODELS.get(frame.event)
        if model is None:
            continue  # unknown future event: clients ignore it, and so do we
        payload = {k: v for k, v in frame.data.items() if k != "seq"}
        model.model_validate(payload)


async def test_disconnect_during_write_still_saves_partial_answer() -> None:
    """Regression: when the client vanishes while a chunk is being *sent*, Starlette stops
    iterating without closing the generator. Without ClosingStreamingResponse, the
    partial answer was never saved and the model stream was never closed."""
    from tests.conftest import build_test_app

    harness = build_test_app(FakeLLMProvider("one two three four five six", delay_s=0))
    body = b'{"client_message_id":"9f0b6c3e-1111-4222-8333-444455556666","content":"Hi"}'
    received_body = False
    chunks_sent = 0

    async def receive() -> dict[str, object]:
        nonlocal received_body
        if not received_body:
            received_body = True
            return {"type": "http.request", "body": body, "more_body": False}
        await asyncio.sleep(3600)  # the client never says goodbye politely
        return {"type": "http.disconnect"}

    async def send(message: dict[str, object]) -> None:
        nonlocal chunks_sent
        if message["type"] == "http.response.body":
            chunks_sent += 1
            if chunks_sent == 3:
                raise OSError("client went away")  # e.g. phone lost signal mid-answer

    scope = {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.4"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/v1/chat",
        "raw_path": b"/v1/chat",
        "query_string": b"",
        "root_path": "",
        "headers": [
            (b"content-type", b"application/json"),
            (b"authorization", b"Bearer dev:alice"),
        ],
        "client": ("127.0.0.1", 1),
        "server": ("test", 80),
    }
    with contextlib.suppress(Exception):
        await harness.app(scope, receive, send)  # type: ignore[arg-type]

    conversation = next(iter(harness.conversations.conversations))
    assistant = (await harness.conversations.recent_messages(conversation, 10))[-1]
    assert assistant.role == "assistant"
    assert assistant.status == "interrupted"
