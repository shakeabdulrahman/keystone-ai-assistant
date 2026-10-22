"""Postgres implementations of the repository ports (SQLAlchemy 2, async).

Each method runs in its own short transaction. Sessions are never held open
across a streaming response, so a slow model can't pin a database connection.
"""

import uuid
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import and_, delete, or_, select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.models import Conversation, Device, Message, PendingAction, ToolExecution, User
from app.repositories.base import (
    ActionRecord,
    ActionStatus,
    ConversationRecord,
    DuplicateMessageError,
    MessageRecord,
    NewMessage,
    Page,
    ToolAuditEntry,
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
        row.parts,
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
            parts=message.parts,
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


class SqlToolAuditRepository:
    def __init__(self, sessions: async_sessionmaker[AsyncSession]) -> None:
        self._sessions = sessions

    async def record(self, entries: list[ToolAuditEntry]) -> None:
        if not entries:
            return
        async with self._sessions() as session:
            session.add_all(
                ToolExecution(
                    id=uuid.uuid4(),
                    user_id=e.user_id,
                    conversation_id=e.conversation_id,
                    message_id=e.message_id,
                    call_id=e.call_id,
                    tool_name=e.tool_name,
                    arguments=e.arguments,
                    status=e.status,
                    error_code=e.error_code,
                    latency_ms=e.latency_ms,
                )
                for e in entries
            )
            await session.commit()


def _action(row: PendingAction) -> ActionRecord:
    return ActionRecord(
        id=row.id,
        user_id=row.user_id,
        conversation_id=row.conversation_id,
        message_id=row.message_id,
        call_id=row.call_id,
        tool_name=row.tool_name,
        arguments=row.arguments,
        card=row.card,
        status=row.status,  # type: ignore[arg-type]  # constrained by the code that writes it
        created_at=row.created_at,
        expires_at=row.expires_at,
        decided_at=row.decided_at,
        result=row.result,
        error_code=row.error_code,
        error_message=row.error_message,
    )


class SqlActionRepository:
    def __init__(self, sessions: async_sessionmaker[AsyncSession]) -> None:
        self._sessions = sessions

    async def create(self, action: ActionRecord) -> None:
        async with self._sessions() as session:
            session.add(
                PendingAction(
                    id=action.id,
                    user_id=action.user_id,
                    conversation_id=action.conversation_id,
                    message_id=action.message_id,
                    call_id=action.call_id,
                    tool_name=action.tool_name,
                    arguments=action.arguments,
                    card=action.card,
                    status=action.status,
                    created_at=action.created_at,
                    expires_at=action.expires_at,
                )
            )
            await session.commit()

    async def get_owned(self, user_id: uuid.UUID, action_id: uuid.UUID) -> ActionRecord | None:
        async with self._sessions() as session:
            row = await session.scalar(
                select(PendingAction).where(
                    PendingAction.id == action_id, PendingAction.user_id == user_id
                )
            )
            return _action(row) if row else None

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
        # One UPDATE ... WHERE status = :from: the database decides who wins a race, so two
        # simultaneous confirms can never both execute.
        conditions = [
            PendingAction.id == action_id,
            PendingAction.user_id == user_id,
            PendingAction.status == from_status,
        ]
        if unexpired_only:
            conditions.append(PendingAction.expires_at > now)
        async with self._sessions() as session:
            row = await session.scalar(
                update(PendingAction)
                .where(*conditions)
                .values(status=to_status, decided_at=now)
                .returning(PendingAction)
            )
            await session.commit()
            return _action(row) if row else None

    async def finish(
        self,
        action_id: uuid.UUID,
        status: ActionStatus,
        *,
        result: dict[str, Any] | None = None,
        error_code: str | None = None,
        error_message: str | None = None,
    ) -> ActionRecord:
        async with self._sessions() as session:
            row = await session.scalar(
                update(PendingAction)
                .where(PendingAction.id == action_id)
                .values(
                    status=status,
                    result=result,
                    error_code=error_code,
                    error_message=error_message,
                )
                .returning(PendingAction)
            )
            await session.commit()
            assert row is not None
            return _action(row)

    async def get_many(self, action_ids: list[uuid.UUID]) -> dict[uuid.UUID, ActionRecord]:
        if not action_ids:
            return {}
        async with self._sessions() as session:
            rows = await session.scalars(
                select(PendingAction).where(PendingAction.id.in_(action_ids))
            )
            return {r.id: _action(r) for r in rows}

    async def open_for_user(self, user_id: uuid.UUID, now: datetime) -> list[ActionRecord]:
        async with self._sessions() as session:
            rows = await session.scalars(
                select(PendingAction)
                .where(
                    PendingAction.user_id == user_id,
                    PendingAction.status == "pending",
                    PendingAction.expires_at > now,
                )
                .order_by(PendingAction.created_at.desc())
                .limit(10)
            )
            return [_action(r) for r in rows]


class SqlDeviceRepository:
    def __init__(self, sessions: async_sessionmaker[AsyncSession]) -> None:
        self._sessions = sessions

    async def register(self, user_id: uuid.UUID, token: str, platform: str) -> None:
        now = datetime.now(UTC)
        async with self._sessions() as session:
            await session.execute(
                insert(Device)
                .values(id=uuid.uuid4(), user_id=user_id, token=token, platform=platform)
                .on_conflict_do_update(
                    index_elements=[Device.token],
                    set_={"user_id": user_id, "platform": platform, "last_seen_at": now},
                )
            )
            await session.commit()

    async def unregister(self, user_id: uuid.UUID, token: str) -> None:
        async with self._sessions() as session:
            await session.execute(
                delete(Device).where(Device.token == token, Device.user_id == user_id)
            )
            await session.commit()

    async def tokens_for(self, user_id: uuid.UUID) -> list[str]:
        async with self._sessions() as session:
            rows = await session.scalars(
                select(Device.token).where(Device.user_id == user_id).order_by(Device.token)
            )
            return list(rows)

    async def delete_tokens(self, tokens: list[str]) -> None:
        if not tokens:
            return
        async with self._sessions() as session:
            await session.execute(delete(Device).where(Device.token.in_(tokens)))
            await session.commit()
