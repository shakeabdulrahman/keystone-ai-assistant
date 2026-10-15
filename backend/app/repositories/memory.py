"""In-memory implementations of the repository ports.

Used by unit tests (fast, no database). Behaviour mirrors the Postgres adapters;
integration tests run the same scenarios against real Postgres to keep them honest.
"""

import uuid
from dataclasses import replace
from datetime import UTC, datetime, timedelta

from app.repositories.base import (
    ConversationRecord,
    DuplicateMessageError,
    MessageRecord,
    NewMessage,
    Page,
    UserRecord,
)
from app.repositories.cursor import decode_cursor, encode_cursor


class _Clock:
    """Strictly increasing timestamps so ordering is deterministic in tests."""

    def __init__(self) -> None:
        self._last = datetime.now(UTC)

    def now(self) -> datetime:
        self._last = max(datetime.now(UTC), self._last + timedelta(microseconds=1))
        return self._last


class InMemoryUserRepository:
    def __init__(self) -> None:
        self.users: dict[uuid.UUID, UserRecord] = {}

    async def get_or_create(
        self, firebase_uid: str, email: str | None, display_name: str | None
    ) -> UserRecord:
        for user in self.users.values():
            if user.firebase_uid == firebase_uid:
                return user
        user = UserRecord(
            uuid.uuid4(), firebase_uid, email, display_name, "member", datetime.now(UTC)
        )
        self.users[user.id] = user
        return user

    async def update_profile(
        self, user_id: uuid.UUID, email: str | None, display_name: str | None
    ) -> UserRecord:
        self.users[user_id] = replace(self.users[user_id], email=email, display_name=display_name)
        return self.users[user_id]

    async def delete(self, user_id: uuid.UUID) -> None:
        self.users.pop(user_id, None)


class InMemoryConversationRepository:
    def __init__(self) -> None:
        self.conversations: dict[uuid.UUID, ConversationRecord] = {}
        self.deleted: set[uuid.UUID] = set()
        self.messages: dict[uuid.UUID, list[tuple[MessageRecord, str | None]]] = {}
        self._clock = _Clock()

    async def create(self, user_id: uuid.UUID, title: str) -> ConversationRecord:
        now = self._clock.now()
        record = ConversationRecord(uuid.uuid4(), user_id, title, False, now, now, now)
        self.conversations[record.id] = record
        self.messages[record.id] = []
        return record

    async def get_owned(
        self, user_id: uuid.UUID, conversation_id: uuid.UUID
    ) -> ConversationRecord | None:
        record = self.conversations.get(conversation_id)
        if record is None or record.user_id != user_id or conversation_id in self.deleted:
            return None
        return record

    async def list_for_user(
        self, user_id: uuid.UUID, limit: int, cursor: str | None
    ) -> Page[ConversationRecord]:
        items = sorted(
            (
                c
                for c in self.conversations.values()
                if c.user_id == user_id and c.id not in self.deleted
            ),
            key=lambda c: (c.last_message_at, c.id),
            reverse=True,
        )
        if cursor:
            ts, last_id = decode_cursor(cursor)
            items = [c for c in items if (c.last_message_at, c.id) < (ts, last_id)]
        page = items[:limit]
        next_cursor = (
            encode_cursor(page[-1].last_message_at, page[-1].id) if len(items) > limit else None
        )
        return Page(page, next_cursor)

    async def rename(self, conversation_id: uuid.UUID, title: str, generated: bool) -> None:
        record = self.conversations[conversation_id]
        self.conversations[conversation_id] = replace(
            record, title=title, title_generated=generated, updated_at=self._clock.now()
        )

    async def soft_delete(self, conversation_id: uuid.UUID) -> None:
        self.deleted.add(conversation_id)

    async def add_message(self, conversation_id: uuid.UUID, message: NewMessage) -> MessageRecord:
        stored = self.messages[conversation_id]
        if message.client_message_id and any(cid == message.client_message_id for _, cid in stored):
            raise DuplicateMessageError
        now = self._clock.now()
        record = MessageRecord(
            message.id or uuid.uuid4(),
            conversation_id,
            message.role,
            message.content,
            message.status,
            now,
        )
        stored.append((record, message.client_message_id))
        conversation = self.conversations[conversation_id]
        self.conversations[conversation_id] = replace(
            conversation, last_message_at=now, updated_at=now
        )
        return record

    async def recent_messages(self, conversation_id: uuid.UUID, limit: int) -> list[MessageRecord]:
        return [m for m, _ in self.messages.get(conversation_id, [])][-limit:]

    async def list_messages(
        self, conversation_id: uuid.UUID, limit: int, cursor: str | None
    ) -> Page[MessageRecord]:
        items = [m for m, _ in self.messages.get(conversation_id, [])]
        if cursor:
            ts, last_id = decode_cursor(cursor)
            items = [m for m in items if (m.created_at, m.id) < (ts, last_id)]
        page = items[-limit:]
        next_cursor = encode_cursor(page[0].created_at, page[0].id) if len(items) > limit else None
        return Page(page, next_cursor)
