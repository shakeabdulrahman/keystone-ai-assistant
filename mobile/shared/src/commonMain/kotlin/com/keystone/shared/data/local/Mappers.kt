package com.keystone.shared.data.local

import com.keystone.shared.data.remote.KeystoneJson
import com.keystone.shared.data.remote.ToolCardParser
import com.keystone.shared.data.remote.dto.ConversationDto
import com.keystone.shared.data.remote.dto.MessageDto
import com.keystone.shared.data.remote.dto.ToolResultPartDto
import com.keystone.shared.domain.model.Conversation
import com.keystone.shared.domain.model.StoredMessage
import com.keystone.shared.domain.model.ToolCard
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

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
    partsJson = KeystoneJson.encodeToString(PartsSerializer, parts),
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
    cards = cardsFromParts(partsJson),
)

private val PartsSerializer = ListSerializer(JsonElement.serializer())

/** Successful tool results with a known card type; anything else is skipped. */
internal fun cardsFromParts(partsJson: String): List<ToolCard> {
    val parts = try {
        KeystoneJson.decodeFromString(PartsSerializer, partsJson)
    } catch (e: SerializationException) {
        return emptyList()
    } catch (e: IllegalArgumentException) {
        return emptyList()
    }
    return parts.mapNotNull { element ->
        val part = try {
            KeystoneJson.decodeFromJsonElement(ToolResultPartDto.serializer(), element)
        } catch (e: IllegalArgumentException) {
            return@mapNotNull null // a part type this app version doesn't understand
        }
        if (part.type == "tool_result" && part.status == "ok") ToolCardParser.parse(part.ui, part.data) else null
    }
}
