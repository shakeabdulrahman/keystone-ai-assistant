"""The streaming event contract between the gateway and the apps (see /contracts).

Every chat turn is one SSE stream:
    message.started → (text.delta | tool.started | tool.result)* → (message.completed | error)
Tool events come in pairs with the same call_id. Later phases add more event types
(citations, confirmation.required). Clients must ignore event types they don't know,
so old app versions keep working.
"""

import json
from typing import Any, Literal

from pydantic import BaseModel


class UsageOut(BaseModel):
    input_tokens: int
    output_tokens: int


class MessageStarted(BaseModel):
    type: Literal["message.started"] = "message.started"
    conversation_id: str
    message_id: str
    user_message_id: str


class TextDeltaOut(BaseModel):
    type: Literal["text.delta"] = "text.delta"
    text: str


class ToolStarted(BaseModel):
    type: Literal["tool.started"] = "tool.started"
    call_id: str
    tool: str
    label: str  # human-readable, e.g. "Checking your leave balance"


class ToolResultOut(BaseModel):
    type: Literal["tool.result"] = "tool.result"
    call_id: str
    tool: str
    status: Literal["ok", "error"]
    ui: str | None  # which card to render; null for errors
    data: dict[str, Any]


class MessageCompleted(BaseModel):
    type: Literal["message.completed"] = "message.completed"
    message_id: str
    finish_reason: Literal["stop", "length"]
    usage: UsageOut


class StreamError(BaseModel):
    type: Literal["error"] = "error"
    code: str
    message: str  # safe to show to users; never contains internals
    retryable: bool


StreamEvent = (
    MessageStarted | TextDeltaOut | ToolStarted | ToolResultOut | MessageCompleted | StreamError
)


class SseEncoder:
    """Serialises events as SSE frames and stamps a per-stream sequence number.

    `seq` lets clients detect gaps and lets us correlate client and server logs.
    """

    def __init__(self) -> None:
        self._seq = 0

    def encode(self, event: StreamEvent) -> str:
        self._seq += 1
        payload = {"seq": self._seq, **event.model_dump(exclude={"type"})}
        return f"event: {event.type}\ndata: {json.dumps(payload, separators=(',', ':'))}\n\n"


SSE_KEEPALIVE = ": keep-alive\n\n"  # SSE comment line; clients ignore it
