package com.keystone.shared.data.local

import com.keystone.shared.data.remote.dto.ConversationDto
import com.keystone.shared.data.remote.dto.MessageDto
import com.keystone.shared.domain.model.Conversation
import com.keystone.shared.domain.model.StoredMessage
import kotlinx.datetime.Instant

internal fun ConversationDto.toEntity() = ConversationEntity(
    id = id,
    title = title,
    lastMessageAtMillis = Instant.parse(lastMessageAt).toEpochMilliseconds(),
)

internal fun ConversationEntity.toDomain() = Conversation(
    id = id,
    title = title,
    lastMessageAt = Instant.fromEpochMilliseconds(lastMessageAtMillis),
)

internal fun MessageDto.toEntity(conversationId: String) = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    status = status,
    createdAtMillis = Instant.parse(createdAt).toEpochMilliseconds(),
)

internal fun MessageEntity.toDomain() = StoredMessage(
    id = id,
    conversationId = conversationId,
    role = if (role == "user") StoredMessage.Role.User else StoredMessage.Role.Assistant,
    content = content,
    status = when (status) {
        "complete" -> StoredMessage.Status.Complete
        "interrupted" -> StoredMessage.Status.Interrupted
        else -> StoredMessage.Status.Failed
    },
    createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
)
