"""Deterministic provider for tests and offline UI work. Costs nothing, needs no network."""

import asyncio
from collections.abc import AsyncGenerator, Sequence

from app.ai.llm.base import LLMError
from app.ai.llm.types import Done, LLMEvent, LLMRequest, TextDelta, Usage

DEFAULT_REPLY = (
    "Hi! I'm Keystone, your HR assistant. I can't see your HR records yet, "
    "but soon I'll be able to check your leave balance, HR requests and "
    "onboarding tasks, and answer questions from company policies."
)


class FakeLLMProvider:
    """Replays a scripted reply word by word.

    - `reply`: text to stream (split on spaces so it looks like real tokens).
    - `delay_s`: pause between chunks, to exercise streaming UIs and cancellation.
    - `fail_with`: raise this error after `fail_after` chunks, to test error paths.
    """

    name = "fake"
    model = "fake-1"

    def __init__(
        self,
        reply: str = DEFAULT_REPLY,
        *,
        delay_s: float = 0.0,
        fail_with: LLMError | None = None,
        fail_after: int = 0,
    ) -> None:
        self.reply = reply
        self.delay_s = delay_s
        self.fail_with = fail_with
        self.fail_after = fail_after
        self.requests: list[LLMRequest] = []  # lets tests assert on the prompt sent

    async def stream(self, request: LLMRequest) -> AsyncGenerator[LLMEvent, None]:
        self.requests.append(request)
        chunks = _chunk(self.reply)
        for i, chunk in enumerate(chunks):
            if self.fail_with is not None and i == self.fail_after:
                raise self.fail_with
            if self.delay_s:
                await asyncio.sleep(self.delay_s)
            yield TextDelta(chunk)
        if self.fail_with is not None and self.fail_after >= len(chunks):
            raise self.fail_with
        input_tokens = sum(len(m.content.split()) for m in request.messages)
        yield Done("stop", Usage(input_tokens=input_tokens, output_tokens=len(chunks)))


def _chunk(text: str) -> Sequence[str]:
    words = text.split(" ")
    return [w if i == len(words) - 1 else w + " " for i, w in enumerate(words)]
