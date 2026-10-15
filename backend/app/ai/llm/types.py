"""Provider-neutral types. Business logic speaks only these; adapters translate.

Tool-call types join in Phase 3.
"""

from dataclasses import dataclass, field
from typing import Literal

Role = Literal["user", "assistant"]
ModelTier = Literal["fast", "smart"]
FinishReason = Literal["stop", "length", "cancelled", "error"]


@dataclass(frozen=True, slots=True)
class ChatMessage:
    role: Role
    content: str


@dataclass(frozen=True, slots=True)
class LLMRequest:
    system: str
    messages: list[ChatMessage]
    tier: ModelTier = "smart"
    max_output_tokens: int = 1024
    temperature: float = 0.3


@dataclass(frozen=True, slots=True)
class TextDelta:
    text: str


@dataclass(frozen=True, slots=True)
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0


@dataclass(frozen=True, slots=True)
class Done:
    finish_reason: FinishReason = "stop"
    usage: Usage = field(default_factory=Usage)


LLMEvent = TextDelta | Done
