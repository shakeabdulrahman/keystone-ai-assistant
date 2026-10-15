"""Conversation titles.

A conversation starts with a fallback title (the first question, shortened), so the
list never shows a blank row. After the first complete answer, a background task asks
the model for a short title. It runs after the answer is delivered, so it never
delays the user, and any failure just leaves the fallback in place.
"""

import asyncio
import contextlib
import logging
import uuid

from app.ai.llm.base import LLMError, LLMProvider
from app.ai.llm.types import ChatMessage, LLMRequest, TextDelta
from app.repositories.base import ConversationRepository

logger = logging.getLogger("keystone.titles")

MAX_TITLE_CHARS = 60

_TITLE_PROMPT = (
    "Write a short title (3 to 6 words) for this HR conversation. "
    "Reply with the title only: no quotes, no punctuation at the end."
)


def fallback_title(first_message: str) -> str:
    text = " ".join(first_message.split())
    return text if len(text) <= MAX_TITLE_CHARS else text[: MAX_TITLE_CHARS - 1].rstrip() + "…"


def clean_title(raw: str) -> str | None:
    title = " ".join(raw.split()).strip().strip("\"'“”.").strip()
    if title.lower().startswith("title:"):
        title = title[len("title:") :].strip()
    if not title:
        return None
    return title if len(title) <= MAX_TITLE_CHARS else title[:MAX_TITLE_CHARS].rstrip()


class TitleGenerator:
    def __init__(self, provider: LLMProvider, conversations: ConversationRepository) -> None:
        self._provider = provider
        self._conversations = conversations
        # Keep references so running tasks aren't garbage-collected mid-flight.
        self._tasks: set[asyncio.Task[None]] = set()

    def schedule(self, conversation_id: uuid.UUID, question: str, answer: str) -> None:
        task = asyncio.create_task(self.generate(conversation_id, question, answer))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def generate(self, conversation_id: uuid.UUID, question: str, answer: str) -> None:
        request = LLMRequest(
            system=_TITLE_PROMPT,
            messages=[ChatMessage("user", f"Question: {question}\n\nAnswer: {answer[:1500]}")],
            tier="fast",
            max_output_tokens=20,
            temperature=0.2,
        )
        try:
            parts: list[str] = []
            async with contextlib.aclosing(self._provider.stream(request)) as events:
                async for event in events:
                    if isinstance(event, TextDelta):
                        parts.append(event.text)
            title = clean_title("".join(parts))
            if title:
                await self._conversations.rename(conversation_id, title, generated=True)
        except LLMError as exc:
            logger.info("title_generation_failed", extra={"code": exc.code})
        except Exception:
            logger.exception("title_generation_crashed")

    async def drain(self) -> None:
        """Wait for in-flight title tasks (used at shutdown and in tests)."""
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
