"""Google Gemini adapter (Gemini API, free tier via Google AI Studio keys).

Used for the cloud deployment, where Ollama can't run on a free server. Swapping it in is
configuration only (LLM_PROVIDER=gemini): the chat service, tools and app don't change.

Streaming: POST models/{model}:streamGenerateContent?alt=sse returns Server-Sent Events,
each a partial response with text and/or functionCall parts.

The API key goes in the `x-goog-api-key` header, never in the URL, so it can't end up in
access logs or error messages.
"""

import json
import logging
import uuid
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

logger = logging.getLogger("keystone.llm.gemini")

GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
_BLOCKED_REPLY = "I can't help with that request."


class GeminiProvider:
    name = "gemini"

    def __init__(
        self,
        api_key: SecretStr,
        model: str,
        *,
        base_url: str = GEMINI_BASE_URL,
        timeout_s: float = 120.0,
        thinking_budget: int | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.model = model
        self._thinking_budget = thinking_budget
        self._client = httpx.AsyncClient(
            base_url=base_url,
            headers={"x-goog-api-key": api_key.get_secret_value()},
            timeout=httpx.Timeout(timeout_s, connect=10.0),
            transport=transport,
        )

    async def stream(self, request: LLMRequest) -> AsyncGenerator[LLMEvent, None]:
        payload: dict[str, Any] = {
            "systemInstruction": {"parts": [{"text": request.system}]},
            "contents": to_gemini_contents(request.messages),
            "generationConfig": {
                "temperature": request.temperature,
                "maxOutputTokens": request.max_output_tokens,
            },
        }
        if self._thinking_budget is not None:
            payload["generationConfig"]["thinkingConfig"] = {
                "thinkingBudget": self._thinking_budget
            }
        if request.tools:
            payload["tools"] = [{"functionDeclarations": [_declaration(t) for t in request.tools]}]

        requested_tools = False
        produced_text = False
        usage = Usage()
        finish: FinishReason = "stop"
        try:
            http_request = self._client.build_request(
                "POST",
                f"/models/{self.model}:streamGenerateContent",
                params={"alt": "sse"},
                json=payload,
            )
            response = await self._client.send(http_request, stream=True)
            try:
                if response.status_code >= 400:
                    await response.aread()
                    raise _error_for(response, self.model)
                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    chunk = json.loads(line[5:].strip())
                    if meta := chunk.get("usageMetadata"):
                        usage = Usage(
                            int(meta.get("promptTokenCount", 0)),
                            int(meta.get("candidatesTokenCount", 0)),
                        )
                    candidates = chunk.get("candidates") or []
                    if not candidates:
                        if chunk.get("promptFeedback", {}).get("blockReason"):
                            yield TextDelta(_BLOCKED_REPLY)
                            produced_text = True
                        continue
                    candidate = candidates[0]
                    for part in (candidate.get("content") or {}).get("parts") or []:
                        if part.get("thought"):
                            continue  # the model's private reasoning summary: not for users
                        if "functionCall" in part:
                            requested_tools = True
                            yield ToolCallRequested(_parse_call(part))
                        elif text := part.get("text"):
                            produced_text = True
                            yield TextDelta(text)
                    reason = candidate.get("finishReason")
                    if reason == "MAX_TOKENS":
                        finish = "length"
                    elif (
                        reason in ("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST")
                        and not produced_text
                    ):
                        yield TextDelta(_BLOCKED_REPLY)
                        produced_text = True
            finally:
                # Same reason as Ollama: on disconnect the cleanup runs in a cancelled scope.
                with anyio.CancelScope(shield=True):
                    await response.aclose()
            yield Done("tool_calls" if requested_tools else finish, usage)
        except LLMError:
            raise
        except httpx.ConnectError as exc:
            raise LLMUnavailableError("cannot connect to Gemini") from exc
        except httpx.TimeoutException as exc:
            raise LLMTimeoutError("Gemini timed out") from exc
        except (httpx.HTTPError, json.JSONDecodeError) as exc:
            raise LLMUnavailableError(f"Gemini stream failed: {type(exc).__name__}") from exc

    async def aclose(self) -> None:
        await self._client.aclose()


def _error_for(response: httpx.Response, model: str) -> LLMError:
    status = response.status_code
    # Log Google's explanation (e.g. "models/x is not found", "API key not valid") so a
    # misconfiguration can be fixed from the logs. It never contains the key (sent in a
    # header) and is truncated; prompts aren't echoed in these errors.
    reason, detail = "", ""
    try:
        error = response.json().get("error", {})
        reason = str(error.get("status", ""))
        detail = str(error.get("message", ""))[:300]
    except (ValueError, AttributeError):
        pass
    logger.warning(
        "gemini_error", extra={"status": status, "reason": reason, "detail": detail, "model": model}
    )
    if status == 429:
        retry = response.headers.get("retry-after")
        return LLMRateLimitedError(
            "Gemini quota exceeded", float(retry) if retry and retry.isdigit() else None
        )
    if status in (400, 401, 403, 404):
        return LLMConfigurationError(f"Gemini rejected the request ({status} {reason})")
    return LLMUnavailableError(f"Gemini returned HTTP {status}")


def to_gemini_contents(messages: list[ChatMessage]) -> list[dict[str, Any]]:
    """Our messages → Gemini `contents`. Roles: user/model. Tool results become
    functionResponse parts; consecutive ones are merged into one turn, as Gemini expects."""
    contents: list[dict[str, Any]] = []
    for m in messages:
        if m.role == "tool":
            try:
                result = json.loads(m.content)
            except ValueError:
                result = {"text": m.content}
            if not isinstance(result, dict):
                result = {"result": result}
            part = {"functionResponse": {"name": m.tool_name or "", "response": result}}
            if (
                contents
                and contents[-1]["role"] == "user"
                and all("functionResponse" in p for p in contents[-1]["parts"])
            ):
                contents[-1]["parts"].append(part)
            else:
                contents.append({"role": "user", "parts": [part]})
            continue
        parts: list[dict[str, Any]] = []
        if m.content:
            parts.append({"text": m.content})
        for call in m.tool_calls:
            call_part: dict[str, Any] = {
                "functionCall": {"name": call.name, "args": call.arguments}
            }
            if call.signature:
                call_part["thoughtSignature"] = call.signature
            parts.append(call_part)
        if parts:
            contents.append({"role": "model" if m.role == "assistant" else "user", "parts": parts})
    return contents


def _parse_call(part: dict[str, Any]) -> ToolCall:
    call = part["functionCall"]
    args = call.get("args") or {}
    return ToolCall(
        id=str(call.get("id") or f"call_{uuid.uuid4().hex[:12]}"),
        name=str(call.get("name", "")),
        arguments=args if isinstance(args, dict) else {"_raw": args},
        signature=part.get("thoughtSignature"),
    )


def _declaration(tool: ToolSpec) -> dict[str, Any]:
    declaration: dict[str, Any] = {"name": tool.name, "description": tool.description}
    schema = gemini_schema(tool.parameters)
    if schema.get("properties"):  # Gemini rejects an object schema with no properties
        declaration["parameters"] = schema
    return declaration


_KEEP = {"type", "description", "enum", "properties", "required", "items", "nullable"}


def gemini_schema(schema: dict[str, Any]) -> dict[str, Any]:
    """JSON Schema (from Pydantic) → the OpenAPI subset Gemini accepts.

    `Optional[X]` arrives as anyOf[X, null] and becomes X + nullable. Keywords Gemini
    doesn't know (default, format "date", min/max lengths) are dropped: the server
    validates the arguments anyway, so nothing is lost but a hint.
    """
    node = dict(schema)
    any_of = node.pop("anyOf", None)
    if any_of:
        options = [o for o in any_of if o.get("type") != "null"]
        merged = {
            **(options[0] if options else {}),
            **{k: v for k, v in node.items() if k != "default"},
        }
        if len(options) < len(any_of):
            merged["nullable"] = True
        node = merged
    out: dict[str, Any] = {k: v for k, v in node.items() if k in _KEEP}
    if "properties" in out:
        out["properties"] = {k: gemini_schema(v) for k, v in out["properties"].items()}
    if "items" in out and isinstance(out["items"], dict):
        out["items"] = gemini_schema(out["items"])
    if isinstance(out.get("type"), str):
        out["type"] = out["type"].upper()  # Gemini's Type enum: STRING, OBJECT, ...
    return out
