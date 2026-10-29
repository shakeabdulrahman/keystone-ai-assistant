"""OpenAI-compatible adapter (Groq) and cross-vendor failover, against mocked HTTP."""

import json
from collections.abc import AsyncGenerator
from typing import Any

import httpx
import pytest
from pydantic import SecretStr

from app.ai.llm.base import (
    LLMConfigurationError,
    LLMError,
    LLMRateLimitedError,
    LLMUnavailableError,
)
from app.ai.llm.fallback import FallbackProvider
from app.ai.llm.openai_compat import OpenAICompatProvider, to_openai_messages
from app.ai.llm.types import (
    ChatMessage,
    Done,
    LLMEvent,
    LLMRequest,
    TextDelta,
    ToolCall,
    ToolCallRequested,
    ToolSpec,
)
from app.core.config import Settings

KEY = SecretStr("gsk-test-not-real")


def sse(*chunks: dict[str, Any]) -> bytes:
    body = "".join(f"data: {json.dumps(c)}\n\n" for c in chunks)
    return (body + "data: [DONE]\n\n").encode()


def groq(handler: Any) -> OpenAICompatProvider:
    return OpenAICompatProvider(
        name="groq",
        base_url="https://api.groq.test/openai/v1",
        api_key=KEY,
        model="llama-test",
        transport=httpx.MockTransport(handler),
    )


def request(tools: list[ToolSpec] | None = None) -> LLMRequest:
    return LLMRequest(
        system="You are Keystone.",
        messages=[ChatMessage("user", "How many leave days?")],
        tools=tools or [],
    )


async def collect(provider: Any, r: LLMRequest) -> list[Any]:
    return [e async for e in provider.stream(r)]


def delta(**d: Any) -> dict[str, Any]:
    return {"choices": [{"index": 0, "delta": d, "finish_reason": None}]}


async def test_streams_text_usage_and_sends_bearer_key() -> None:
    seen: dict[str, Any] = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen.update(
            url=str(req.url), auth=req.headers["authorization"], body=json.loads(req.content)
        )
        return httpx.Response(
            200,
            content=sse(
                delta(role="assistant", content="You have "),
                delta(content="14 days."),
                {"choices": [], "x_groq": {"usage": {"prompt_tokens": 40, "completion_tokens": 6}}},
            ),
        )

    events = await collect(groq(handler), request())

    assert [e.text for e in events if isinstance(e, TextDelta)] == ["You have ", "14 days."]
    done = events[-1]
    assert isinstance(done, Done) and done.finish_reason == "stop"
    assert (done.usage.input_tokens, done.usage.output_tokens) == (40, 6)
    assert seen["url"] == "https://api.groq.test/openai/v1/chat/completions"
    assert seen["auth"] == "Bearer gsk-test-not-real"
    assert seen["body"]["stream"] is True and seen["body"]["model"] == "llama-test"
    assert seen["body"]["messages"][0] == {"role": "system", "content": "You are Keystone."}


async def test_tool_call_fragments_are_assembled() -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        assert body["tools"][0]["function"]["name"] == "getLeaveBalance"
        call = {"index": 0, "id": "call_1", "type": "function"}
        return httpx.Response(
            200,
            content=sse(
                delta(tool_calls=[{**call, "function": {"name": "getLeave", "arguments": ""}}]),
                delta(
                    tool_calls=[
                        {"index": 0, "function": {"name": "Balance", "arguments": '{"leave_'}}
                    ]
                ),
                delta(tool_calls=[{"index": 0, "function": {"arguments": 'type": "annual"}'}}]),
                {"choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]},
            ),
        )

    tool = ToolSpec("getLeaveBalance", "Leave", {"type": "object", "properties": {}})
    events = await collect(groq(handler), request([tool]))

    call = next(e.call for e in events if isinstance(e, ToolCallRequested))
    assert (call.id, call.name, call.arguments) == (
        "call_1",
        "getLeaveBalance",
        {"leave_type": "annual"},
    )
    assert isinstance(events[-1], Done) and events[-1].finish_reason == "tool_calls"


async def test_malformed_tool_arguments_are_passed_on_for_validation() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        piece = {"index": 0, "id": "c", "function": {"name": "getTasks", "arguments": "{oops"}}
        return httpx.Response(200, content=sse(delta(tool_calls=[piece])))

    events = await collect(groq(handler), request())
    call = next(e.call for e in events if isinstance(e, ToolCallRequested))
    assert call.arguments == {"_raw": "{oops"}


@pytest.mark.parametrize(
    ("status", "body", "error"),
    [
        (
            429,
            {"error": {"message": "slow down", "code": "rate_limit_exceeded"}},
            LLMRateLimitedError,
        ),
        (
            401,
            {"error": {"message": "Invalid API Key", "code": "invalid_api_key"}},
            LLMConfigurationError,
        ),
        (
            404,
            {"error": {"message": "model not found", "code": "model_not_found"}},
            LLMConfigurationError,
        ),
        (400, {"error": {"message": "bad call", "code": "tool_use_failed"}}, LLMUnavailableError),
        (503, {"error": {"message": "over capacity"}}, LLMUnavailableError),
    ],
)
async def test_http_errors_map_to_llm_errors(
    status: int, body: dict[str, Any], error: type[Exception]
) -> None:
    with pytest.raises(error) as info:
        await collect(groq(lambda _: httpx.Response(status, json=body)), request())
    assert "gsk-test-not-real" not in str(info.value)


def test_message_mapping_round_trips_tool_calls() -> None:
    messages = to_openai_messages(
        "sys",
        [
            ChatMessage("user", "Leave?"),
            ChatMessage("assistant", "", tool_calls=(ToolCall("c1", "getLeaveBalance", {}),)),
            ChatMessage("tool", '{"annual": 14}', tool_call_id="c1", tool_name="getLeaveBalance"),
        ],
    )
    assert messages[2] == {
        "role": "assistant",
        "content": None,
        "tool_calls": [
            {
                "id": "c1",
                "type": "function",
                "function": {"name": "getLeaveBalance", "arguments": "{}"},
            }
        ],
    }
    assert messages[3] == {"role": "tool", "tool_call_id": "c1", "content": '{"annual": 14}'}


class Scripted:
    """A provider that yields `events`, then raises `error` (if any)."""

    def __init__(self, name: str, events: list[LLMEvent], error: LLMError | None = None) -> None:
        self.name, self.model = name, f"{name}-model"
        self._events, self._error = events, error
        self.calls = 0

    async def stream(self, request: LLMRequest) -> AsyncGenerator[LLMEvent, None]:
        self.calls += 1
        for event in self._events:
            yield event
        if self._error:
            raise self._error


async def test_fallback_uses_next_vendor_when_first_fails_before_answering() -> None:
    first = Scripted("groq", [], LLMRateLimitedError("busy"))
    second = Scripted("gemini", [TextDelta("Hi"), Done()])

    events = await collect(FallbackProvider([first, second]), request())

    assert [e.text for e in events if isinstance(e, TextDelta)] == ["Hi"]
    assert (first.calls, second.calls) == (1, 1)


async def test_fallback_never_switches_vendor_mid_answer() -> None:
    first = Scripted("groq", [TextDelta("Half")], LLMUnavailableError("dropped"))
    second = Scripted("gemini", [TextDelta("Other"), Done()])

    with pytest.raises(LLMUnavailableError):
        await collect(FallbackProvider([first, second]), request())
    assert second.calls == 0


async def test_fallback_raises_the_last_error_when_all_fail() -> None:
    providers = [
        Scripted("groq", [], LLMRateLimitedError("busy")),
        Scripted("gemini", [], LLMUnavailableError("overloaded")),
    ]
    with pytest.raises(LLMUnavailableError, match="overloaded"):
        await collect(FallbackProvider(providers), request())


def test_groq_settings_and_factory() -> None:
    from app.ai.llm.factory import create_llm_provider

    with pytest.raises(ValueError, match="GROQ_API_KEY"):
        Settings(llm_provider="groq", _env_file=None)
    with pytest.raises(ValueError, match="unknown provider"):
        Settings(llm_provider="fake", llm_fallback_providers="openai", _env_file=None)
    with pytest.raises(ValueError, match="GEMINI_API_KEY"):
        Settings(
            llm_provider="groq", groq_api_key="k", llm_fallback_providers="gemini", _env_file=None
        )

    solo = Settings(llm_provider="groq", groq_api_key="k", _env_file=None)
    assert create_llm_provider(solo).name == "groq"
    assert solo.resolved_embedding_provider == "hashing"

    both = Settings(
        llm_provider="groq",
        groq_api_key="k",
        gemini_api_key="g",
        llm_fallback_providers="gemini",
        _env_file=None,
    )
    provider = create_llm_provider(both)
    assert isinstance(provider, FallbackProvider) and provider.name == "groq"
    assert both.resolved_embedding_provider == "gemini"
