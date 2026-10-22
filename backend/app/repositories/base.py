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


ActionStatus = Literal["pending", "executing", "executed", "cancelled", "expired", "failed"]


@dataclass(frozen=True)
class ActionRecord:
    """A change the assistant proposed, waiting for (or past) the employee's decision."""

    id: uuid.UUID
    user_id: uuid.UUID
    conversation_id: uuid.UUID
    message_id: uuid.UUID  # the assistant message that proposed it
    call_id: str
    tool_name: str
    arguments: dict[str, Any]  # frozen at proposal time: exactly what the card showed
    card: dict[str, Any]  # title, fields, notes, confirm_label
    status: ActionStatus
    created_at: datetime
    expires_at: datetime
    decided_at: datetime | None = None
    result: dict[str, Any] | None = None  # reference + message once executed
    error_code: str | None = None
    error_message: str | None = None  # user-safe text when it failed


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


class ActionRepository(Protocol):
    async def create(self, action: ActionRecord) -> None: ...

    async def get_owned(self, user_id: uuid.UUID, action_id: uuid.UUID) -> ActionRecord | None:
        """None if missing or someone else's (indistinguishable on purpose)."""
        ...

    async def transition(
        self,
        user_id: uuid.UUID,
        action_id: uuid.UUID,
        from_status: ActionStatus,
        to_status: ActionStatus,
        now: datetime,
        *,
        unexpired_only: bool = False,
    ) -> ActionRecord | None:
        """Atomic compare-and-set of the status. Returns the updated action, or None if it
        wasn't in `from_status` (someone else got there first) or had expired."""
        ...

    async def finish(
        self,
        action_id: uuid.UUID,
        status: ActionStatus,
        *,
        result: dict[str, Any] | None = None,
        error_code: str | None = None,
        error_message: str | None = None,
    ) -> ActionRecord: ...

    async def get_many(self, action_ids: list[uuid.UUID]) -> dict[uuid.UUID, ActionRecord]: ...

    async def open_for_user(self, user_id: uuid.UUID, now: datetime) -> list[ActionRecord]:
        """Pending, unexpired proposals the user hasn't decided on yet (newest first)."""
        ...


class DeviceRepository(Protocol):
    async def register(self, user_id: uuid.UUID, token: str, platform: str) -> None:
        """Adds the token for this user, or moves it here from another account."""
        ...

    async def unregister(self, user_id: uuid.UUID, token: str) -> None: ...

    async def tokens_for(self, user_id: uuid.UUID) -> list[str]: ...

    async def delete_tokens(self, tokens: list[str]) -> None:
        """Forget tokens the push service says are no longer valid."""
        ...
