"""Gemini adapters against a mocked HTTP API (no key, no network)."""

import json
from typing import Any

import httpx
import pytest
from pydantic import SecretStr

from app.ai.llm.base import LLMConfigurationError, LLMRateLimitedError, LLMUnavailableError
from app.ai.llm.gemini import GeminiProvider, gemini_schema, to_gemini_contents
from app.ai.llm.types import (
    ChatMessage,
    Done,
    LLMRequest,
    TextDelta,
    ToolCall,
    ToolCallRequested,
    ToolSpec,
)
from app.core.config import Settings
from app.rag.embeddings import EmbeddingError, GeminiEmbedder

KEY = SecretStr("test-key-not-real")


def sse(*chunks: dict[str, Any]) -> bytes:
    return "".join(f"data: {json.dumps(c)}\r\n\r\n" for c in chunks).encode()


def provider(handler: Any) -> GeminiProvider:
    return GeminiProvider(KEY, "gemini-test", transport=httpx.MockTransport(handler))


def request(**kw: Any) -> LLMRequest:
    return LLMRequest(
        system="You are Keystone.",
        messages=[ChatMessage("user", "How many leave days?")],
        tools=kw.get("tools", []),
    )


async def collect(p: GeminiProvider, r: LLMRequest) -> list[Any]:
    return [e async for e in p.stream(r)]


async def test_streams_text_and_usage_and_sends_key_in_header() -> None:
    seen: dict[str, Any] = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["url"] = str(req.url)
        seen["key"] = req.headers.get("x-goog-api-key")
        seen["body"] = json.loads(req.content)
        body = sse(
            {"candidates": [{"content": {"role": "model", "parts": [{"text": "You have "}]}}]},
            {
                "candidates": [
                    {"content": {"parts": [{"text": "14 days."}]}, "finishReason": "STOP"}
                ],
                "usageMetadata": {"promptTokenCount": 30, "candidatesTokenCount": 5},
            },
        )
        return httpx.Response(200, content=body, headers={"content-type": "text/event-stream"})

    events = await collect(provider(handler), request())

    assert [e.text for e in events if isinstance(e, TextDelta)] == ["You have ", "14 days."]
    done = events[-1]
    assert isinstance(done, Done) and done.finish_reason == "stop"
    assert (done.usage.input_tokens, done.usage.output_tokens) == (30, 5)
    assert seen["url"].endswith("/models/gemini-test:streamGenerateContent?alt=sse")
    assert "key=" not in seen["url"]  # never in the URL (access logs)
    assert seen["key"] == "test-key-not-real"
    assert seen["body"]["systemInstruction"]["parts"][0]["text"] == "You are Keystone."
    assert seen["body"]["contents"] == [
        {"role": "user", "parts": [{"text": "How many leave days?"}]}
    ]


async def test_function_calls_become_tool_calls_with_signature() -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        decl = body["tools"][0]["functionDeclarations"][0]
        assert decl["name"] == "getLeaveBalance"
        assert decl["parameters"]["type"] == "OBJECT"
        part = {
            "functionCall": {"name": "getLeaveBalance", "args": {"leave_type": "annual"}},
            "thoughtSignature": "sig-abc",
        }
        return httpx.Response(200, content=sse({"candidates": [{"content": {"parts": [part]}}]}))

    tool = ToolSpec(
        "getLeaveBalance",
        "Leave balance",
        {"type": "object", "properties": {"leave_type": {"type": "string"}}},
    )
    events = await collect(provider(handler), request(tools=[tool]))

    call = next(e.call for e in events if isinstance(e, ToolCallRequested))
    assert (call.name, call.arguments, call.signature) == (
        "getLeaveBalance",
        {"leave_type": "annual"},
        "sig-abc",
    )
    assert call.id.startswith("call_")
    assert isinstance(events[-1], Done) and events[-1].finish_reason == "tool_calls"


async def test_thought_parts_are_never_shown() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        parts = [{"text": "secret reasoning", "thought": True}, {"text": "Answer."}]
        return httpx.Response(200, content=sse({"candidates": [{"content": {"parts": parts}}]}))

    events = await collect(provider(handler), request())
    assert [e.text for e in events if isinstance(e, TextDelta)] == ["Answer."]


async def test_blocked_prompt_gets_a_polite_reply() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=sse({"promptFeedback": {"blockReason": "SAFETY"}}))

    events = await collect(provider(handler), request())
    assert any(isinstance(e, TextDelta) and "can't help" in e.text for e in events)


async def test_max_tokens_maps_to_length() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        chunk = {
            "candidates": [{"content": {"parts": [{"text": "Long"}]}, "finishReason": "MAX_TOKENS"}]
        }
        return httpx.Response(200, content=sse(chunk))

    events = await collect(provider(handler), request())
    assert isinstance(events[-1], Done) and events[-1].finish_reason == "length"


@pytest.mark.parametrize(
    ("status", "error"),
    [
        (429, LLMRateLimitedError),
        (400, LLMConfigurationError),
        (403, LLMConfigurationError),
        (503, LLMUnavailableError),
    ],
)
async def test_http_errors_map_to_llm_errors(status: int, error: type[Exception]) -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"error": {"status": "SOME_REASON", "message": "x"}})

    with pytest.raises(error) as info:
        await collect(provider(handler), request())
    assert "test-key-not-real" not in str(info.value)


async def test_connection_failure_is_unavailable() -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("boom", request=req)

    with pytest.raises(LLMUnavailableError):
        await collect(provider(handler), request())


def test_history_mapping_merges_tool_results_and_keeps_signatures() -> None:
    calls = (
        ToolCall("c1", "getLeaveBalance", {}, signature="sig"),
        ToolCall("c2", "listTasks", {}),
    )
    contents = to_gemini_contents(
        [
            ChatMessage("user", "Leave and tasks?"),
            ChatMessage("assistant", "", tool_calls=calls),
            ChatMessage("tool", '{"annual": 14}', tool_call_id="c1", tool_name="getLeaveBalance"),
            ChatMessage("tool", "[1, 2]", tool_call_id="c2", tool_name="listTasks"),
            ChatMessage("assistant", "You have 14 days."),
        ]
    )
    assert [c["role"] for c in contents] == ["user", "model", "user", "model"]
    assert contents[1]["parts"][0] == {
        "functionCall": {"name": "getLeaveBalance", "args": {}},
        "thoughtSignature": "sig",
    }
    responses = contents[2]["parts"]
    assert responses[0]["functionResponse"] == {
        "name": "getLeaveBalance",
        "response": {"annual": 14},
    }
    assert responses[1]["functionResponse"]["response"] == {"result": [1, 2]}


def test_schema_conversion_handles_optional_and_drops_unknown_keywords() -> None:
    schema = {
        "type": "object",
        "properties": {
            "start": {"type": "string", "format": "date", "description": "Start"},
            "note": {
                "anyOf": [{"type": "string", "maxLength": 200}, {"type": "null"}],
                "default": None,
            },
            "tags": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["start"],
        "additionalProperties": False,
    }
    out = gemini_schema(schema)
    assert out["type"] == "OBJECT" and out["required"] == ["start"]
    assert "additionalProperties" not in out
    assert out["properties"]["start"] == {"type": "STRING", "description": "Start"}
    assert out["properties"]["note"] == {"type": "STRING", "nullable": True}
    assert out["properties"]["tags"]["items"] == {"type": "STRING"}


def test_real_tool_schemas_convert() -> None:
    from app.ai.tools.hr_tools import build_hr_registry

    for spec in build_hr_registry().specs():
        converted = gemini_schema(spec.parameters)
        for name in spec.parameters.get("required", []):
            assert name in converted["properties"], (spec.name, name)


def embed_handler(dims: int = 768, seen: dict[str, Any] | None = None) -> Any:
    def handler(req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        if seen is not None:
            seen.update(body=body, url=str(req.url), key=req.headers.get("x-goog-api-key"))
        return httpx.Response(
            200, json={"embeddings": [{"values": [3.0] * dims} for _ in body["requests"]]}
        )

    return handler


async def test_embedder_requests_768_dims_with_task_types_and_normalises() -> None:
    seen: dict[str, Any] = {}
    client = httpx.AsyncClient(
        base_url="https://example.test", transport=httpx.MockTransport(embed_handler(seen=seen))
    )
    embedder = GeminiEmbedder("k", client=client)

    docs = await embedder.embed_documents(["a", "b"])
    first = seen["body"]["requests"][0]
    assert first["taskType"] == "RETRIEVAL_DOCUMENT" and first["outputDimensionality"] == 768
    assert seen["url"].endswith("/models/gemini-embedding-001:batchEmbedContents")
    assert len(docs) == 2 and abs(sum(x * x for x in docs[0]) - 1) < 1e-6

    await embedder.embed_query("q")
    assert seen["body"]["requests"][0]["taskType"] == "RETRIEVAL_QUERY"


async def test_embedder_rejects_wrong_dimensions_and_errors() -> None:
    wrong = GeminiEmbedder(
        "k",
        client=httpx.AsyncClient(
            base_url="https://e.test", transport=httpx.MockTransport(embed_handler(3072))
        ),
    )
    with pytest.raises(EmbeddingError):
        await wrong.embed_query("q")

    failing = GeminiEmbedder(
        "k",
        client=httpx.AsyncClient(
            base_url="https://e.test", transport=httpx.MockTransport(lambda _: httpx.Response(429))
        ),
    )
    with pytest.raises(EmbeddingError):
        await failing.embed_query("q")


def test_gemini_settings() -> None:
    with pytest.raises(ValueError, match="GEMINI_API_KEY"):
        Settings(llm_provider="gemini", _env_file=None)
    s = Settings(llm_provider="gemini", gemini_api_key="k", _env_file=None)
    assert s.resolved_embedding_provider == "gemini"
    assert "k" not in repr(s.gemini_api_key)


def test_factories_build_gemini() -> None:
    from app.ai.llm.factory import create_llm_provider
    from app.rag.factory import create_embedder

    s = Settings(llm_provider="gemini", gemini_api_key="k", _env_file=None)
    assert create_llm_provider(s).name == "gemini"
    assert create_embedder(s).name == "gemini"
