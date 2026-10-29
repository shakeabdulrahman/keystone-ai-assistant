"""Adapter for any OpenAI-compatible chat API: Groq, OpenRouter, Mistral, Together,
Cerebras, OpenAI itself... They all accept POST {base_url}/chat/completions with the same
messages, tools and streaming format, so one adapter covers them all.

Streaming: Server-Sent Events, `data: {json}` lines ending with `data: [DONE]`. Text
arrives in `delta.content`; tool calls arrive in pieces in `delta.tool_calls` (keyed by
`index`), so they're assembled and emitted when the stream ends.

The key goes in the Authorization header and is never logged.
"""

import json
import logging
from collections.abc import AsyncGenerator
from typing import Any

import anyio
import httpx
from pydantic import SecretStr

from app.ai.llm.base import (
    LLMConfigurationError,
    LLMError,
    LLMRateLimitedError,
    LLMTimeoutError,
    LLMUnavailableError,
)
from app.ai.llm.types import (
    ChatMessage,
    Done,
    FinishReason,
    LLMEvent,
    LLMRequest,
    TextDelta,
    ToolCall,
    ToolCallRequested,
    ToolSpec,
    Usage,
)

logger = logging.getLogger("keystone.llm.openai_compat")


class OpenAICompatProvider:
    def __init__(
        self,
        *,
        name: str,
        base_url: str,
        api_key: SecretStr,
        model: str,
        timeout_s: float = 120.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.name = name
        self.model = model
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            headers={"authorization": f"Bearer {api_key.get_secret_value()}"},
            timeout=httpx.Timeout(timeout_s, connect=10.0),
            transport=transport,
        )

    async def stream(self, request: LLMRequest) -> AsyncGenerator[LLMEvent, None]:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": to_openai_messages(request.system, request.messages),
            "temperature": request.temperature,
            "max_tokens": request.max_output_tokens,
            "stream": True,
        }
        if request.tools:
            payload["tools"] = [_tool(t) for t in request.tools]
            payload["tool_choice"] = "auto"

        calls: dict[int, dict[str, str]] = {}  # index → {id, name, arguments (JSON text)}
        usage = Usage()
        finish: FinishReason = "stop"
        try:
            http_request = self._client.build_request("POST", "/chat/completions", json=payload)
            response = await self._client.send(http_request, stream=True)
            try:
                if response.status_code >= 400:
                    await response.aread()
                    raise _error_for(response, self.name, self.model)
                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break
                    chunk = json.loads(data)
                    # Usage: OpenAI puts it in `usage`; Groq in `x_groq.usage`.
                    raw = chunk.get("usage") or (chunk.get("x_groq") or {}).get("usage")
                    if raw:
                        usage = Usage(
                            int(raw.get("prompt_tokens", 0)), int(raw.get("completion_tokens", 0))
                        )
                    for choice in chunk.get("choices") or []:
                        delta = choice.get("delta") or {}
                        if text := delta.get("content"):
                            yield TextDelta(text)
                        for piece in delta.get("tool_calls") or []:
                            slot = calls.setdefault(
                                int(piece.get("index", 0)), {"id": "", "name": "", "arguments": ""}
                            )
                            slot["id"] = piece.get("id") or slot["id"]
                            function = piece.get("function") or {}
                            slot["name"] += function.get("name") or ""
                            slot["arguments"] += function.get("arguments") or ""
                        if choice.get("finish_reason") == "length":
                            finish = "length"
            finally:
                with anyio.CancelScope(shield=True):
                    await response.aclose()
            for index in sorted(calls):
                yield ToolCallRequested(_parse_call(calls[index], index))
            yield Done("tool_calls" if calls else finish, usage)
        except LLMError:
            raise
        except httpx.ConnectError as exc:
            raise LLMUnavailableError(f"cannot connect to {self.name}") from exc
        except httpx.TimeoutException as exc:
            raise LLMTimeoutError(f"{self.name} timed out") from exc
        except (httpx.HTTPError, json.JSONDecodeError) as exc:
            raise LLMUnavailableError(f"{self.name} stream failed: {type(exc).__name__}") from exc

    async def aclose(self) -> None:
        await self._client.aclose()


def _error_for(response: httpx.Response, name: str, model: str) -> LLMError:
    status = response.status_code
    code, detail = "", ""
    try:
        error = response.json().get("error", {})
        if isinstance(error, dict):
            code = str(error.get("code") or error.get("type") or "")
            detail = str(error.get("message", ""))[:300]
        else:
            detail = str(error)[:300]
    except (ValueError, AttributeError):
        pass
    logger.warning(
        "llm_provider_error",
        extra={"provider": name, "status": status, "code": code, "detail": detail, "model": model},
    )
    if status == 429:
        retry = response.headers.get("retry-after", "")
        return LLMRateLimitedError(
            f"{name} rate limit: {detail}",
            float(retry) if retry.replace(".", "").isdigit() else None,
        )
    if status == 400 and code == "tool_use_failed":
        # The model wrote a malformed tool call: a model hiccup, not our configuration.
        return LLMUnavailableError(f"{name} tool call failed: {detail}")
    if status in (400, 401, 403, 404):
        return LLMConfigurationError(f"{name} rejected the request ({status} {code}): {detail}")
    return LLMUnavailableError(f"{name} returned HTTP {status}: {detail}")


def to_openai_messages(system: str, messages: list[ChatMessage]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = [{"role": "system", "content": system}]
    for m in messages:
        if m.role == "tool":
            out.append({"role": "tool", "tool_call_id": m.tool_call_id or "", "content": m.content})
        elif m.role == "assistant" and m.tool_calls:
            out.append(
                {
                    "role": "assistant",
                    "content": m.content or None,
                    "tool_calls": [
                        {
                            "id": c.id,
                            "type": "function",
                            "function": {"name": c.name, "arguments": json.dumps(c.arguments)},
                        }
                        for c in m.tool_calls
                    ],
                }
            )
        else:
            out.append({"role": m.role, "content": m.content})
    return out


def _tool(tool: ToolSpec) -> dict[str, Any]:
    parameters = tool.parameters or {"type": "object", "properties": {}}
    return {
        "type": "function",
        "function": {"name": tool.name, "description": tool.description, "parameters": parameters},
    }


def _parse_call(slot: dict[str, str], index: int) -> ToolCall:
    try:
        arguments = json.loads(slot["arguments"]) if slot["arguments"].strip() else {}
    except ValueError:
        arguments = {"_raw": slot["arguments"]}  # the tool's validation reports it to the model
    if not isinstance(arguments, dict):
        arguments = {"_raw": arguments}
    return ToolCall(id=slot["id"] or f"call_{index}", name=slot["name"], arguments=arguments)
