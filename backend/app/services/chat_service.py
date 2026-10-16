"""One chat turn: validate, build context, run the agent loop, persist the outcome.

The agent loop: call the model with the available tools; if it asks for tools, run them
(as the signed-in user), feed the results back, and call the model again, until it answers
in text or the step limit is reached. Text and tool activity stream to the app as it happens.

Split in two on purpose:
- `start_turn` runs before the HTTP response begins, so problems (unknown
  conversation, duplicate message) become normal 4xx responses.
- `stream_turn` runs inside the SSE response; failures become `error` events.
"""

import asyncio
import contextlib
import logging
import time
import uuid
from collections.abc import AsyncGenerator
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import anyio
from fastapi import HTTPException

from app.ai.events import (
    CitationOut,
    CitationsOut,
    MessageCompleted,
    MessageStarted,
    StreamError,
    StreamEvent,
    TextDeltaOut,
    ToolResultOut,
    ToolStarted,
    UsageOut,
)
from app.ai.llm.base import LLMError, LLMProvider
from app.ai.llm.types import (
    ChatMessage,
    Done,
    LLMRequest,
    TextDelta,
    ToolCall,
    ToolCallRequested,
    Usage,
)
from app.ai.prompts import SYSTEM_PROMPT_VERSION, system_prompt
from app.ai.tools.registry import ToolContext, ToolExecution, ToolRegistry
from app.integrations.hris import HrisClient
from app.rag.citations import Citation, SourceBook
from app.rag.retriever import Retriever
from app.repositories.base import (
    ConversationRepository,
    DuplicateMessageError,
    MessageRecord,
    MessageStatus,
    NewMessage,
    ToolAuditEntry,
    ToolAuditRepository,
    UserRecord,
)
from app.services.titles import TitleGenerator, fallback_title

logger = logging.getLogger("keystone.chat")

# What users see for each failure. Internal details stay in the logs.
_USER_MESSAGES = {
    "ai_unavailable": "The assistant is unavailable right now. Please try again in a moment.",
    "ai_timeout": "The assistant took too long to respond. Please try again.",
    "ai_rate_limited": "The assistant is busy right now. Please try again shortly.",
    "ai_misconfigured": "The assistant isn't set up correctly. Please contact support.",
    "internal_error": "Something went wrong while answering. Please try again.",
}

_STEP_LIMIT_REPLY = (
    "I couldn't finish looking that up. Please try asking again, a bit more specifically."
)


@dataclass(frozen=True)
class AgentTools:
    """Everything the agent loop needs to run tools. None means "chat without tools"."""

    registry: ToolRegistry
    hris: HrisClient
    audit: ToolAuditRepository
    max_steps: int = 4
    knowledge: Retriever | None = None  # policy search (RAG); None disables it


@dataclass(frozen=True)
class ChatTurn:
    user: UserRecord
    conversation_id: uuid.UUID
    user_message_id: uuid.UUID
    history: list[ChatMessage]  # oldest first, ends with the new user message
    is_first_exchange: bool


class ChatService:
    def __init__(
        self,
        provider: LLMProvider,
        conversations: ConversationRepository,
        *,
        history_messages: int,
        max_output_tokens: int,
        titles: TitleGenerator | None = None,
        tools: AgentTools | None = None,
    ) -> None:
        self._provider = provider
        self._conversations = conversations
        self._history_messages = history_messages
        self._max_output_tokens = max_output_tokens
        self._titles = titles
        self._tools = tools

    async def start_turn(
        self,
        user: UserRecord,
        conversation_id: uuid.UUID | None,
        client_message_id: str,
        content: str,
    ) -> ChatTurn:
        user_id = user.id
        if conversation_id is None:
            conversation = await self._conversations.create(user_id, fallback_title(content))
        else:
            found = await self._conversations.get_owned(user_id, conversation_id)
            # Same 404 whether it doesn't exist or belongs to someone else: no ID probing.
            if found is None:
                raise HTTPException(status_code=404, detail="Conversation not found")
            conversation = found

        try:
            user_message = await self._conversations.add_message(
                conversation.id,
                NewMessage(role="user", content=content, client_message_id=client_message_id),
            )
        except DuplicateMessageError as exc:
            raise HTTPException(status_code=409, detail="This message was already sent") from exc

        # Fetch extra rows so that dropping failed exchanges still leaves enough context.
        recent = await self._conversations.recent_messages(
            conversation.id, self._history_messages * 2
        )
        kept = _completed_exchanges(recent)[-self._history_messages :]
        history = [ChatMessage(role=m.role, content=m.content) for m in kept]
        is_first = not conversation.title_generated and len(recent) == 1
        return ChatTurn(user, conversation.id, user_message.id, history, is_first)

    async def stream_turn(self, turn: ChatTurn) -> AsyncGenerator[StreamEvent, None]:
        today = datetime.now(UTC).date()
        system = system_prompt(today)
        tool_specs = self._tools.registry.specs() if self._tools else []
        max_steps = self._tools.max_steps if self._tools else 1
        working: list[ChatMessage] = list(turn.history)  # grows with tool calls/results

        assistant_id = uuid.uuid4()  # announced now, used as the stored row's ID at the end
        parts: list[str] = []
        executions: list[ToolExecution] = []
        sources = SourceBook()  # numbers every retrieved passage once per answer
        citations: list[Citation] = []
        status: MessageStatus = "failed"
        finish = "error"
        usage = Usage()
        steps = 0
        started = time.perf_counter()
        first_output_ms: float | None = None

        def mark_first_output() -> None:
            nonlocal first_output_ms
            if first_output_ms is None:
                first_output_ms = (time.perf_counter() - started) * 1000

        yield MessageStarted(
            conversation_id=str(turn.conversation_id),
            message_id=str(assistant_id),
            user_message_id=str(turn.user_message_id),
        )
        try:
            while True:
                steps += 1
                request = LLMRequest(
                    system=system,
                    messages=list(working),  # a snapshot: `working` keeps growing below
                    tools=tool_specs,
                    max_output_tokens=self._max_output_tokens,
                )
                step_text: list[str] = []
                calls: list[ToolCall] = []
                # aclosing: if *we* are closed mid-stream (client gone), close the provider's
                # stream too. Python does not close nested async generators automatically, and
                # an unclosed provider stream keeps the model generating tokens nobody reads.
                async with contextlib.aclosing(self._provider.stream(request)) as events:
                    async for event in events:
                        if isinstance(event, TextDelta):
                            mark_first_output()
                            step_text.append(event.text)
                            parts.append(event.text)
                            yield TextDeltaOut(text=event.text)
                        elif isinstance(event, ToolCallRequested):
                            calls.append(event.call)
                        elif isinstance(event, Done):
                            finish, usage = event.finish_reason, usage + event.usage

                if not calls or self._tools is None:
                    break  # the model answered in text: done
                if steps >= max_steps:
                    # Safety valve: a confused model must not loop forever (cost, latency).
                    logger.warning("agent_step_limit", extra={"steps": steps})
                    parts.append(_STEP_LIMIT_REPLY)
                    yield TextDeltaOut(text=_STEP_LIMIT_REPLY)
                    finish = "stop"
                    break

                working.append(
                    ChatMessage("assistant", "".join(step_text), tool_calls=tuple(calls))
                )
                ctx = ToolContext(
                    user=turn.user,
                    hris=self._tools.hris,
                    today=today,
                    knowledge=self._tools.knowledge,
                    sources=sources,
                )
                for call in calls:
                    mark_first_output()
                    yield ToolStarted(
                        call_id=call.id,
                        tool=call.name,
                        label=self._tools.registry.label_for(call.name),
                    )
                    execution = await self._tools.registry.execute(call, ctx)
                    executions.append(execution)
                    yield ToolResultOut(
                        call_id=execution.call_id,
                        tool=execution.tool,
                        status=execution.status,
                        ui=execution.ui,
                        data=execution.data,
                    )
                    working.append(
                        ChatMessage(
                            "tool",
                            execution.for_model(),
                            tool_call_id=call.id,
                            tool_name=call.name,
                        )
                    )

            citations = sources.cited("".join(parts))
            if citations:
                yield CitationsOut(
                    items=[CitationOut.model_validate(c.to_dict()) for c in citations]
                )
            status = "complete"
            yield MessageCompleted(
                message_id=str(assistant_id),
                finish_reason="length" if finish == "length" else "stop",
                usage=UsageOut(input_tokens=usage.input_tokens, output_tokens=usage.output_tokens),
            )
        except LLMError as exc:
            logger.warning("llm_error", extra={"code": exc.code, "error_type": type(exc).__name__})
            yield StreamError(
                code=exc.code, message=_USER_MESSAGES[exc.code], retryable=exc.retryable
            )
        except (asyncio.CancelledError, GeneratorExit):
            # Client disconnected or pressed Stop. Keep the partial answer, then let the
            # cancellation propagate so the provider stream closes and generation stops.
            status, finish = "interrupted", "cancelled"
            raise
        except Exception:
            logger.exception("chat_stream_failed")
            yield StreamError(
                code="internal_error", message=_USER_MESSAGES["internal_error"], retryable=True
            )
        finally:
            duration_ms = (time.perf_counter() - started) * 1000
            # Shielded: on disconnect we're inside a cancelled scope, and an unshielded
            # database write would be cancelled before it happened (see Phase 1 notes).
            with anyio.CancelScope(shield=True):
                await self._save_answer(
                    turn,
                    assistant_id,
                    "".join(parts),
                    status,
                    usage,
                    duration_ms,
                    executions,
                    citations,
                )
            logger.info(
                "chat_turn",
                extra={
                    "provider": self._provider.name,
                    "model": self._provider.model,
                    "prompt_version": SYSTEM_PROMPT_VERSION,
                    "status": status,
                    "finish_reason": finish,
                    "history_messages": len(turn.history),
                    "agent_steps": steps,
                    "tools_called": [e.tool for e in executions],
                    "tool_errors": sum(e.status == "error" for e in executions),
                    "sources_retrieved": len(sources),
                    "sources_cited": len(citations),
                    "ttft_ms": round(first_output_ms, 1) if first_output_ms is not None else None,
                    "duration_ms": round(duration_ms, 1),
                    "input_tokens": usage.input_tokens,
                    "output_tokens": usage.output_tokens,
                },
            )
            if status == "complete" and turn.is_first_exchange and self._titles is not None:
                self._titles.schedule(
                    turn.conversation_id, turn.history[-1].content, "".join(parts)
                )

    async def _save_answer(
        self,
        turn: ChatTurn,
        message_id: uuid.UUID,
        text: str,
        status: MessageStatus,
        usage: Usage,
        duration_ms: float,
        executions: list[ToolExecution],
        citations: list[Citation],
    ) -> None:
        parts = [_tool_part(e) for e in executions]
        if citations:
            parts.append({"type": "citations", "items": [c.to_dict() for c in citations]})
        try:
            await self._conversations.add_message(
                turn.conversation_id,
                NewMessage(
                    id=message_id,
                    role="assistant",
                    content=text,
                    status=status,
                    model=self._provider.model,
                    prompt_version=SYSTEM_PROMPT_VERSION,
                    input_tokens=usage.input_tokens or None,
                    output_tokens=usage.output_tokens or None,
                    latency_ms=round(duration_ms),
                    # Cards and sources, so reopening the conversation shows them again.
                    parts=parts or None,
                ),
            )
            if self._tools is not None and executions:
                await self._tools.audit.record(
                    [
                        ToolAuditEntry(
                            user_id=turn.user.id,
                            conversation_id=turn.conversation_id,
                            message_id=message_id,
                            call_id=e.call_id,
                            tool_name=e.tool,
                            arguments=e.arguments,
                            status=e.status,
                            error_code=e.error_code,
                            latency_ms=e.latency_ms,
                        )
                        for e in executions
                    ]
                )
        except Exception:
            # The user already has the answer on screen; failing to save it must not crash
            # the stream. Log loudly so it shows up in monitoring.
            logger.exception("save_answer_failed")


def _tool_part(execution: ToolExecution) -> dict[str, Any]:
    return {
        "type": "tool_result",
        "call_id": execution.call_id,
        "tool": execution.tool,
        "status": execution.status,
        "ui": execution.ui,
        "data": execution.data,
    }


def _completed_exchanges(messages: list[MessageRecord]) -> list[MessageRecord]:
    """Messages worth sending back to the model as context.

    A question whose answer failed or was stopped is dropped together with the partial
    answer; otherwise a retry would show the model the same question twice. The last
    message (the new question) is always kept.
    """
    kept: list[MessageRecord] = []
    for i, message in enumerate(messages):
        is_new_question = i == len(messages) - 1
        if message.role == "assistant":
            if message.status == "complete":
                kept.append(message)
        elif is_new_question:
            kept.append(message)
        else:
            answer = messages[i + 1] if i + 1 < len(messages) else None
            if answer is not None and answer.role == "assistant" and answer.status == "complete":
                kept.append(message)
    return kept
