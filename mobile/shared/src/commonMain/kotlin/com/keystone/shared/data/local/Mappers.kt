package com.keystone.shared.data.local

import com.keystone.shared.data.remote.KeystoneJson
import com.keystone.shared.data.remote.ToolCardParser
import com.keystone.shared.data.remote.dto.ActionDto
import com.keystone.shared.data.remote.dto.CitationsDto
import com.keystone.shared.data.remote.dto.ConversationDto
import com.keystone.shared.data.remote.dto.MessageDto
import com.keystone.shared.data.remote.dto.ToolResultPartDto
import com.keystone.shared.data.remote.dto.toDomain
import com.keystone.shared.domain.model.Citation
import com.keystone.shared.domain.model.Conversation
import com.keystone.shared.domain.model.PendingAction
import com.keystone.shared.domain.model.StoredMessage
import com.keystone.shared.domain.model.ToolCard
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    citations = citationsFromParts(partsJson),
    actions = actionsFromParts(partsJson),
)

private val PartsSerializer = ListSerializer(JsonElement.serializer())

private fun decodeParts(partsJson: String): List<JsonElement> = try {
    KeystoneJson.decodeFromString(PartsSerializer, partsJson)
} catch (e: IllegalArgumentException) {
    emptyList() // covers SerializationException: a corrupt cache entry shows no extras
}

/** Decodes one part, or null if it isn't the expected shape (e.g. a newer part type). */
private fun <T> JsonElement.decodeOrNull(serializer: KSerializer<T>): T? = try {
    KeystoneJson.decodeFromJsonElement(serializer, this)
} catch (e: IllegalArgumentException) {
    null
}

private fun JsonElement.partType(): String? =
    ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull

/** Successful tool results with a known card type; anything else is skipped. */
internal fun cardsFromParts(partsJson: String): List<ToolCard> =
    decodeParts(partsJson)
        .filter { it.partType() == "tool_result" }
        .mapNotNull { it.decodeOrNull(ToolResultPartDto.serializer()) }
        .filter { it.status == "ok" }
        .mapNotNull { ToolCardParser.parse(it.ui, it.data) }

/** The sources the answer cited (from its `citations` part), or none. */
internal fun citationsFromParts(partsJson: String): List<Citation> =
    decodeParts(partsJson)
        .filter { it.partType() == "citations" }
        .mapNotNull { it.decodeOrNull(CitationsDto.serializer()) }
        .flatMap { part -> part.items.map { it.toDomain() } }

/** Confirmation cards the answer showed, with the state the server reported for them. */
internal fun actionsFromParts(partsJson: String): List<PendingAction> =
    decodeParts(partsJson)
        .filter { it.partType() == "confirmation" }
        .mapNotNull { it.decodeOrNull(ActionDto.serializer()) }
        .mapNotNull { dto ->
            try {
                dto.toDomain()
            } catch (e: IllegalArgumentException) {
                null // e.g. a malformed date: skip this card, keep the rest
            }
        }
