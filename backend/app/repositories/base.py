"""Storage ports. Services depend on these protocols; Postgres and in-memory adapters
implement them.

Records are plain dataclasses, not ORM objects, so nothing outside the repository
layer can accidentally lazy-load or mutate database state.
"""

import uuid
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Literal, Protocol

MessageRole = Literal["user", "assistant"]
MessageStatus = Literal["complete", "interrupted", "failed"]


@dataclass(frozen=True)
class UserRecord:
    id: uuid.UUID
    firebase_uid: str
    email: str | None
    display_name: str | None
    role: str
    created_at: datetime


@dataclass(frozen=True)
class ConversationRecord:
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    title_generated: bool
    created_at: datetime
    updated_at: datetime
    last_message_at: datetime


@dataclass(frozen=True)
class MessageRecord:
    id: uuid.UUID
    conversation_id: uuid.UUID
    role: MessageRole
    content: str
    status: MessageStatus
    created_at: datetime
    parts: list[dict[str, Any]] | None = None


@dataclass(frozen=True)
class NewMessage:
    role: MessageRole
    content: str
    status: MessageStatus = "complete"
    client_message_id: str | None = None
    model: str | None = None
    prompt_version: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    latency_ms: int | None = None
    id: uuid.UUID | None = None  # lets the stream announce the ID before the row exists
    parts: list[dict[str, Any]] | None = None


@dataclass(frozen=True)
class ToolAuditEntry:
    user_id: uuid.UUID
    conversation_id: uuid.UUID
    message_id: uuid.UUID
    call_id: str
    tool_name: str
    arguments: dict[str, Any]
    status: str
    error_code: str | None
    latency_ms: int


@dataclass(frozen=True)
class Page[T]:
    items: list[T]
    next_cursor: str | None


class DuplicateMessageError(Exception):
    """The client_message_id was already used in this conversation."""


class UserRepository(Protocol):
    async def get_or_create(
        self, firebase_uid: str, email: str | None, display_name: str | None
    ) -> UserRecord: ...
    async def update_profile(
        self, user_id: uuid.UUID, email: str | None, display_name: str | None
    ) -> UserRecord: ...
    async def delete(self, user_id: uuid.UUID) -> None: ...


class ConversationRepository(Protocol):
    async def create(self, user_id: uuid.UUID, title: str) -> ConversationRecord: ...

    async def get_owned(
        self, user_id: uuid.UUID, conversation_id: uuid.UUID
    ) -> ConversationRecord | None:
        """None if missing, deleted, or owned by someone else (indistinguishable on purpose)."""
        ...

    async def list_for_user(
        self, user_id: uuid.UUID, limit: int, cursor: str | None
    ) -> Page[ConversationRecord]: ...
    async def rename(self, conversation_id: uuid.UUID, title: str, generated: bool) -> None: ...
    async def soft_delete(self, conversation_id: uuid.UUID) -> None: ...

    async def add_message(self, conversation_id: uuid.UUID, message: NewMessage) -> MessageRecord:
        """Stores a message and bumps the conversation's last_message_at.
        Raises DuplicateMessageError for a reused client_message_id."""
        ...

    async def recent_messages(self, conversation_id: uuid.UUID, limit: int) -> list[MessageRecord]:
        """The latest `limit` messages, oldest first."""
        ...

    async def list_messages(
        self, conversation_id: uuid.UUID, limit: int, cursor: str | None
    ) -> Page[MessageRecord]:
        """A page of messages, oldest first; `next_cursor` points to older messages."""
        ...


class ToolAuditRepository(Protocol):
    async def record(self, entries: list[ToolAuditEntry]) -> None: ...
