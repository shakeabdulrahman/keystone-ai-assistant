"""Postgres implementations of the repository ports (SQLAlchemy 2, async).

Each method runs in its own short transaction. Sessions are never held open
across a streaming response, so a slow model can't pin a database connection.
"""

import uuid
from datetime import UTC, datetime

from sqlalchemy import and_, delete, or_, select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.models import Conversation, Message, User
from app.repositories.base import (
    ConversationRecord,
    DuplicateMessageError,
    MessageRecord,
    NewMessage,
    Page,
    UserRecord,
)
from app.repositories.cursor import decode_cursor, encode_cursor


def _user(row: User) -> UserRecord:
    return UserRecord(
        row.id, row.firebase_uid, row.email, row.display_name, row.role, row.created_at
    )


def _conversation(row: Conversation) -> ConversationRecord:
    return ConversationRecord(
        row.id,
        row.user_id,
        row.title,
        row.title_generated,
        row.created_at,
        row.updated_at,
        row.last_message_at,
    )


def _message(row: Message) -> MessageRecord:
    return MessageRecord(
        row.id,
        row.conversation_id,
        row.role,  # type: ignore[arg-type]
        row.content,
        row.status,  # type: ignore[arg-type]
        row.created_at,
    )


class SqlUserRepository:
    def __init__(self, sessions: async_sessionmaker[AsyncSession]) -> None:
        self._sessions = sessions

    async def get_or_create(
        self, firebase_uid: str, email: str | None, display_name: str | None
    ) -> UserRecord:
        async with self._sessions() as session:
            existing = await session.scalar(select(User).where(User.firebase_uid == firebase_uid))
            if existing is not None:
                return _user(existing)
            # ON CONFLICT DO NOTHING: two first requests racing can't create two users.
            await session.execute(
                insert(User)
                .values(
                    id=uuid.uuid4(),
                    firebase_uid=firebase_uid,
                    email=email,
                    display_name=display_name,
                )
                .on_conflict_do_nothing(index_elements=["firebase_uid"])
            )
            await session.commit()
            row = await session.scalar(select(User).where(User.firebase_uid == firebase_uid))
            assert row is not None
            return _user(row)

    async def update_profile(
        self, user_id: uuid.UUID, email: str | None, display_name: str | None
    ) -> UserRecord:
        async with self._sessions() as session:
            row = await session.get(User, user_id)
            assert row is not None
            row.email, row.display_name = email, display_name
            await session.commit()
            await session.refresh(row)
            return _user(row)

    async def delete(self, user_id: uuid.UUID) -> None:
        async with self._sessions() as session:
            # Cascades to conversations and messages (ON DELETE CASCADE).
            await session.execute(delete(User).where(User.id == user_id))
            await session.commit()


class SqlConversationRepository:
    def __init__(self, sessions: async_sessionmaker[AsyncSession]) -> None:
        self._sessions = sessions

    async def create(self, user_id: uuid.UUID, title: str) -> ConversationRecord:
        async with self._sessions() as session:
            now = datetime.now(UTC)
            row = Conversation(
                id=uuid.uuid4(),
                user_id=user_id,
                title=title,
                created_at=now,
                updated_at=now,
                last_message_at=now,
            )
            session.add(row)
            await session.commit()
            await session.refresh(row)
            return _conversation(row)

    async def get_owned(
        self, user_id: uuid.UUID, conversation_id: uuid.UUID
    ) -> ConversationRecord | None:
        async with self._sessions() as session:
            row = await session.scalar(
                select(Conversation).where(
                    Conversation.id == conversation_id,
                    Conversation.user_id == user_id,
                    Conversation.deleted_at.is_(None),
                )
            )
            return _conversation(row) if row else None

    async def list_for_user(
        self, user_id: uuid.UUID, limit: int, cursor: str | None
    ) -> Page[ConversationRecord]:
        query = select(Conversation).where(
            Conversation.user_id == user_id, Conversation.deleted_at.is_(None)
        )
        if cursor:
            ts, last_id = decode_cursor(cursor)
            query = query.where(
                or_(
                    Conversation.last_message_at < ts,
                    and_(Conversation.last_message_at == ts, Conversation.id < last_id),
                )
            )
        query = query.order_by(Conversation.last_message_at.desc(), Conversation.id.desc())
        async with self._sessions() as session:
            rows = list(await session.scalars(query.limit(limit + 1)))
        items = [_conversation(r) for r in rows[:limit]]
        next_cursor = (
            encode_cursor(items[-1].last_message_at, items[-1].id) if len(rows) > limit else None
        )
        return Page(items, next_cursor)

    async def rename(self, conversation_id: uuid.UUID, title: str, generated: bool) -> None:
        async with self._sessions() as session:
            await session.execute(
                update(Conversation)
                .where(Conversation.id == conversation_id)
                .values(title=title, title_generated=generated, updated_at=datetime.now(UTC))
            )
            await session.commit()

    async def soft_delete(self, conversation_id: uuid.UUID) -> None:
        async with self._sessions() as session:
            await session.execute(
                update(Conversation)
                .where(Conversation.id == conversation_id)
                .values(deleted_at=datetime.now(UTC))
            )
            await session.commit()

    async def add_message(self, conversation_id: uuid.UUID, message: NewMessage) -> MessageRecord:
        now = datetime.now(UTC)  # set in Python: microsecond ordering even within a transaction
        row = Message(
            id=message.id or uuid.uuid4(),
            conversation_id=conversation_id,
            role=message.role,
            content=message.content,
            status=message.status,
            client_message_id=message.client_message_id,
            model=message.model,
            prompt_version=message.prompt_version,
            input_tokens=message.input_tokens,
            output_tokens=message.output_tokens,
            latency_ms=message.latency_ms,
            created_at=now,
        )
        async with self._sessions() as session:
            try:
                session.add(row)
                # Note: this UPDATE autoflushes the INSERT above, so a duplicate surfaces
                # here, not at commit. The whole block is the transaction.
                await session.execute(
                    update(Conversation)
                    .where(Conversation.id == conversation_id)
                    .values(last_message_at=now, updated_at=now)
                )
                await session.commit()
            except IntegrityError as exc:
                await session.rollback()
                if "uq_messages_client_id" in str(exc):
                    raise DuplicateMessageError from exc
                raise
            return _message(row)

    async def recent_messages(self, conversation_id: uuid.UUID, limit: int) -> list[MessageRecord]:
        async with self._sessions() as session:
            rows = list(
                await session.scalars(
                    select(Message)
                    .where(Message.conversation_id == conversation_id)
                    .order_by(Message.created_at.desc(), Message.id.desc())
                    .limit(limit)
                )
            )
        return [_message(r) for r in reversed(rows)]

    async def list_messages(
        self, conversation_id: uuid.UUID, limit: int, cursor: str | None
    ) -> Page[MessageRecord]:
        query = select(Message).where(Message.conversation_id == conversation_id)
        if cursor:
            ts, last_id = decode_cursor(cursor)
            query = query.where(
                or_(Message.created_at < ts, and_(Message.created_at == ts, Message.id < last_id))
            )
        query = query.order_by(Message.created_at.desc(), Message.id.desc()).limit(limit + 1)
        async with self._sessions() as session:
            rows = list(await session.scalars(query))
        page = [_message(r) for r in rows[:limit]]
        next_cursor = encode_cursor(page[-1].created_at, page[-1].id) if len(rows) > limit else None
        return Page(list(reversed(page)), next_cursor)
