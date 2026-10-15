"""Ollama adapter: local open-weight models over Ollama's HTTP API (free, no keys).

Ollama streams newline-delimited JSON from POST /api/chat; each line carries a
fragment of the reply, and the last line has `done: true` plus token counts.
"""

import json
import logging
from collections.abc import AsyncGenerator
from typing import Any

import anyio
import httpx

from app.ai.llm.base import (
    LLMConfigurationError,
    LLMError,
    LLMTimeoutError,
    LLMUnavailableError,
)
from app.ai.llm.types import Done, FinishReason, LLMEvent, LLMRequest, TextDelta, Usage

logger = logging.getLogger("keystone.llm.ollama")


class OllamaProvider:
    name = "ollama"

    def __init__(
        self,
        base_url: str,
        model: str,
        *,
        timeout_s: float = 120.0,
        transport: httpx.AsyncBaseTransport | None = None,  # injectable for tests
    ) -> None:
        self.model = model
        # Generous read timeout: the first token waits for the model to load into memory.
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=httpx.Timeout(timeout_s, connect=5.0),
            transport=transport,
        )

    async def stream(self, request: LLMRequest) -> AsyncGenerator[LLMEvent, None]:
        payload: dict[str, Any] = {
            "model": self.model,
            "stream": True,
            "messages": [{"role": "system", "content": request.system}]
            + [{"role": m.role, "content": m.content} for m in request.messages],
            "options": {
                "temperature": request.temperature,
                "num_predict": request.max_output_tokens,
            },
        }
        try:
            request_ = self._client.build_request("POST", "/api/chat", json=payload)
            response = await self._client.send(request_, stream=True)
            try:
                if response.status_code == 404:
                    raise LLMConfigurationError(
                        f"model '{self.model}' not found; run: ollama pull {self.model}"
                    )
                if response.status_code >= 400:
                    raise LLMUnavailableError(f"ollama returned HTTP {response.status_code}")

                async for line in response.aiter_lines():
                    if not line.strip():
                        continue
                    chunk = json.loads(line)
                    if error := chunk.get("error"):
                        raise LLMUnavailableError(f"ollama error: {error}")
                    text = chunk.get("message", {}).get("content", "")
                    if text:
                        yield TextDelta(text)
                    if chunk.get("done"):
                        yield Done(
                            _finish_reason(chunk.get("done_reason")),
                            Usage(
                                input_tokens=int(chunk.get("prompt_eval_count", 0)),
                                output_tokens=int(chunk.get("eval_count", 0)),
                            ),
                        )
                        return
                raise LLMUnavailableError("ollama stream ended without a final chunk")
            finally:
                # When the app disconnects, the server cancels this task and keeps cancelling
                # every await inside it, so an unshielded close never completes and Ollama
                # keeps generating. Shielding guarantees the connection is really closed.
                with anyio.CancelScope(shield=True):
                    await response.aclose()
        except LLMError:
            raise
        except httpx.ConnectError as exc:
            raise LLMUnavailableError("cannot connect to ollama; is it running?") from exc
        except httpx.TimeoutException as exc:
            raise LLMTimeoutError("ollama timed out") from exc
        except (httpx.HTTPError, json.JSONDecodeError) as exc:
            raise LLMUnavailableError(f"ollama stream failed: {type(exc).__name__}") from exc

    async def aclose(self) -> None:
        await self._client.aclose()


def _finish_reason(raw: str | None) -> FinishReason:
    return "length" if raw == "length" else "stop"
