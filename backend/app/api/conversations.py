"""Conversation history endpoints. Every query is scoped to the signed-in user."""

from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from pydantic import BaseModel, Field, field_validator

from app.api.deps import CurrentUser, get_conversations
from app.repositories.base import ConversationRecord, ConversationRepository, MessageRecord

router = APIRouter(prefix="/v1/conversations", tags=["conversations"])

Conversations = Annotated[ConversationRepository, Depends(get_conversations)]


class ConversationOut(BaseModel):
    id: UUID
    title: str
    created_at: datetime
    updated_at: datetime
    last_message_at: datetime

    @classmethod
    def of(cls, c: ConversationRecord) -> "ConversationOut":
        return cls(
            id=c.id,
            title=c.title,
            created_at=c.created_at,
            updated_at=c.updated_at,
            last_message_at=c.last_message_at,
        )


class ConversationPage(BaseModel):
    items: list[ConversationOut]
    next_cursor: str | None


class MessageOut(BaseModel):
    id: UUID
    role: Literal["user", "assistant"]
    content: str
    status: Literal["complete", "interrupted", "failed"]
    created_at: datetime

    @classmethod
    def of(cls, m: MessageRecord) -> "MessageOut":
        return cls(
            id=m.id, role=m.role, content=m.content, status=m.status, created_at=m.created_at
        )


class MessagePage(BaseModel):
    items: list[MessageOut]  # oldest first
    next_cursor: str | None  # pass as `cursor` to load older messages


class RenameIn(BaseModel):
    title: str = Field(min_length=1, max_length=120)

    @field_validator("title")
    @classmethod
    def not_blank(cls, value: str) -> str:
        stripped = " ".join(value.split())
        if not stripped:
            raise ValueError("must not be blank")
        return stripped


async def _owned(
    conversations: ConversationRepository, user_id: UUID, conversation_id: UUID
) -> ConversationRecord:
    found = await conversations.get_owned(user_id, conversation_id)
    if found is None:
        raise HTTPException(status_code=404, detail="Conversation not found")
    return found


@router.get("", response_model=ConversationPage)
async def list_conversations(
    user: CurrentUser,
    conversations: Conversations,
    limit: Annotated[int, Query(ge=1, le=50)] = 20,
    cursor: Annotated[str | None, Query(max_length=200)] = None,
) -> ConversationPage:
    page = await conversations.list_for_user(user.id, limit, cursor)
    return ConversationPage(
        items=[ConversationOut.of(c) for c in page.items], next_cursor=page.next_cursor
    )


@router.get("/{conversation_id}", response_model=ConversationOut)
async def get_conversation(
    conversation_id: UUID, user: CurrentUser, conversations: Conversations
) -> ConversationOut:
    return ConversationOut.of(await _owned(conversations, user.id, conversation_id))


@router.get("/{conversation_id}/messages", response_model=MessagePage)
async def list_messages(
    conversation_id: UUID,
    user: CurrentUser,
    conversations: Conversations,
    limit: Annotated[int, Query(ge=1, le=200)] = 50,
    cursor: Annotated[str | None, Query(max_length=200)] = None,
) -> MessagePage:
    await _owned(conversations, user.id, conversation_id)
    page = await conversations.list_messages(conversation_id, limit, cursor)
    return MessagePage(items=[MessageOut.of(m) for m in page.items], next_cursor=page.next_cursor)


@router.patch("/{conversation_id}", response_model=ConversationOut)
async def rename_conversation(
    conversation_id: UUID, body: RenameIn, user: CurrentUser, conversations: Conversations
) -> ConversationOut:
    await _owned(conversations, user.id, conversation_id)
    # A user's own title always wins over an AI-generated one.
    await conversations.rename(conversation_id, body.title, generated=True)
    return ConversationOut.of(await _owned(conversations, user.id, conversation_id))


@router.delete("/{conversation_id}", status_code=204, response_class=Response)
async def delete_conversation(
    conversation_id: UUID, user: CurrentUser, conversations: Conversations
) -> Response:
    await _owned(conversations, user.id, conversation_id)
    await conversations.soft_delete(conversation_id)
    return Response(status_code=204)
