import json

import httpx
import pytest

from app.ai.llm.base import LLMConfigurationError, LLMUnavailableError
from app.ai.llm.fake import FakeLLMProvider
from app.ai.llm.ollama import OllamaProvider
from app.ai.llm.types import (
    ChatMessage,
    Done,
    LLMEvent,
    LLMRequest,
    TextDelta,
    ToolCall,
    ToolCallRequested,
    ToolSpec,
    Usage,
)

REQUEST = LLMRequest(system="sys", messages=[ChatMessage("user", "hello there")])


async def collect(provider: FakeLLMProvider | OllamaProvider) -> list[LLMEvent]:
    return [event async for event in provider.stream(REQUEST)]


async def test_fake_streams_words_then_done() -> None:
    events = await collect(FakeLLMProvider("one two three"))
    assert events == [
        TextDelta("one "),
        TextDelta("two "),
        TextDelta("three"),
        Done("stop", Usage(input_tokens=2, output_tokens=3)),
    ]


async def test_fake_can_fail_mid_stream() -> None:
    provider = FakeLLMProvider("a b c", fail_with=LLMUnavailableError("down"), fail_after=2)
    seen: list[LLMEvent] = []
    with pytest.raises(LLMUnavailableError):
        async for event in provider.stream(REQUEST):
            seen.append(event)
    assert seen == [TextDelta("a "), TextDelta("b ")]


def ollama_with(handler: httpx.MockTransport) -> OllamaProvider:
    return OllamaProvider("http://ollama", "qwen2.5:7b", transport=handler)


def ndjson(*lines: dict[str, object]) -> bytes:
    return "\n".join(json.dumps(line) for line in lines).encode()


async def test_ollama_maps_stream_and_usage() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["model"] == "qwen2.5:7b"
        assert body["stream"] is True
        assert body["messages"][0] == {"role": "system", "content": "sys"}
        assert body["messages"][1] == {"role": "user", "content": "hello there"}
        return httpx.Response(
            200,
            content=ndjson(
                {"message": {"role": "assistant", "content": "Hel"}, "done": False},
                {"message": {"role": "assistant", "content": "lo"}, "done": False},
                {
                    "message": {"role": "assistant", "content": ""},
                    "done": True,
                    "done_reason": "stop",
                    "prompt_eval_count": 12,
                    "eval_count": 2,
                },
            ),
        )

    events = await collect(ollama_with(httpx.MockTransport(handler)))
    assert events == [TextDelta("Hel"), TextDelta("lo"), Done("stop", Usage(12, 2))]


async def test_ollama_missing_model_is_configuration_error() -> None:
    transport = httpx.MockTransport(lambda _: httpx.Response(404, json={"error": "not found"}))
    with pytest.raises(LLMConfigurationError) as info:
        await collect(ollama_with(transport))
    assert info.value.retryable is False


async def test_ollama_connection_refused_is_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(LLMUnavailableError):
        await collect(ollama_with(httpx.MockTransport(handler)))


async def test_ollama_error_line_is_unavailable() -> None:
    transport = httpx.MockTransport(
        lambda _: httpx.Response(200, content=ndjson({"error": "model crashed"}))
    )
    with pytest.raises(LLMUnavailableError):
        await collect(ollama_with(transport))


async def test_ollama_stream_without_done_is_unavailable() -> None:
    transport = httpx.MockTransport(
        lambda _: httpx.Response(200, content=ndjson({"message": {"content": "Hi"}, "done": False}))
    )
    with pytest.raises(LLMUnavailableError):
        await collect(ollama_with(transport))


async def test_ollama_sends_tools_and_parses_tool_calls() -> None:
    sent: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        sent.update(json.loads(request.content))
        return httpx.Response(
            200,
            content=ndjson(
                {
                    "message": {
                        "role": "assistant",
                        "content": "",
                        "tool_calls": [
                            {"function": {"name": "getTasks", "arguments": {"status": "pending"}}}
                        ],
                    },
                    "done": False,
                },
                {"message": {"content": ""}, "done": True, "done_reason": "stop", "eval_count": 9},
            ),
        )

    provider = ollama_with(httpx.MockTransport(handler))
    request = LLMRequest(
        system="sys",
        messages=[
            ChatMessage("user", "tasks?"),
            ChatMessage("assistant", "", tool_calls=(ToolCall("c0", "getProfile", {}),)),
            ChatMessage("tool", '{"name":"A"}', tool_call_id="c0", tool_name="getProfile"),
        ],
        tools=[ToolSpec("getTasks", "Tasks", {"type": "object", "properties": {}})],
    )
    events = [e async for e in provider.stream(request)]

    assert sent["tools"] == [
        {
            "type": "function",
            "function": {
                "name": "getTasks",
                "description": "Tasks",
                "parameters": {"type": "object", "properties": {}},
            },
        }
    ]
    messages = sent["messages"]
    assert isinstance(messages, list)
    assert messages[2]["tool_calls"] == [{"function": {"name": "getProfile", "arguments": {}}}]
    assert messages[3] == {"role": "tool", "content": '{"name":"A"}', "tool_name": "getProfile"}
    call_event = events[0]
    assert isinstance(call_event, ToolCallRequested)
    assert (call_event.call.name, call_event.call.arguments) == ("getTasks", {"status": "pending"})
    assert call_event.call.id  # generated when Ollama doesn't provide one
    assert events[-1] == Done("tool_calls", Usage(0, 9))


async def test_ollama_tolerates_string_arguments() -> None:
    transport = httpx.MockTransport(
        lambda _: httpx.Response(
            200,
            content=ndjson(
                {
                    "message": {
                        "tool_calls": [
                            {"function": {"name": "getTasks", "arguments": '{"status":"done"}'}}
                        ]
                    },
                    "done": True,
                }
            ),
        )
    )
    events = await collect(ollama_with(transport))
    assert isinstance(events[0], ToolCallRequested)
    assert events[0].call.arguments == {"status": "done"}
