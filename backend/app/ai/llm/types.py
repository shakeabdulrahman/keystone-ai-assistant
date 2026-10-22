"""Provider-neutral types. Business logic speaks only these; adapters translate.

Tool calling, in provider-neutral terms:
- the request lists the tools the model may use (`ToolSpec`, arguments as JSON Schema);
- the model may answer with `ToolCallRequested` events instead of (or as well as) text;
- the caller runs the tools and continues the conversation with `role="tool"` messages.
"""

from dataclasses import dataclass, field
from typing import Any, Literal

Role = Literal["user", "assistant", "tool"]
ModelTier = Literal["fast", "smart"]
FinishReason = Literal["stop", "length", "tool_calls", "cancelled", "error"]


@dataclass(frozen=True, slots=True)
class ToolCall:
    id: str
    name: str
    arguments: dict[str, Any]
    # Opaque provider data that must be sent back with the call in the next request
    # (e.g. Gemini's "thought signature"). Business logic never reads it.
    signature: str | None = None


@dataclass(frozen=True, slots=True)
class ChatMessage:
    role: Role
    content: str
    # assistant messages: the tools the model asked for in this step
    tool_calls: tuple[ToolCall, ...] = ()
    # tool messages: which call this result answers
    tool_call_id: str | None = None
    tool_name: str | None = None


@dataclass(frozen=True, slots=True)
class ToolSpec:
    name: str
    description: str
    parameters: dict[str, Any]  # JSON Schema of the arguments object


@dataclass(frozen=True, slots=True)
class LLMRequest:
    system: str
    messages: list[ChatMessage]
    tools: list[ToolSpec] = field(default_factory=list)
    tier: ModelTier = "smart"
    max_output_tokens: int = 1024
    temperature: float = 0.3


@dataclass(frozen=True, slots=True)
class TextDelta:
    text: str


@dataclass(frozen=True, slots=True)
class ToolCallRequested:
    call: ToolCall


@dataclass(frozen=True, slots=True)
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0

    def __add__(self, other: "Usage") -> "Usage":
        return Usage(
            self.input_tokens + other.input_tokens, self.output_tokens + other.output_tokens
        )


@dataclass(frozen=True, slots=True)
class Done:
    finish_reason: FinishReason = "stop"
    usage: Usage = field(default_factory=Usage)


LLMEvent = TextDelta | ToolCallRequested | Done
