package com.keystone.shared.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class ProfileDto(
    @SerialName("id") val id: String,
    @SerialName("email") val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("role") val role: String,
)

@Serializable
internal data class ConversationDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("last_message_at") val lastMessageAt: String,
)

@Serializable
internal data class ConversationPageDto(
    @SerialName("items") val items: List<ConversationDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
internal data class MessageDto(
    @SerialName("id") val id: String,
    @SerialName("role") val role: String,
    @SerialName("content") val content: String,
    @SerialName("status") val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("parts") val parts: List<JsonElement> = emptyList(),
)

@Serializable
internal data class MessagePageDto(
    @SerialName("items") val items: List<MessageDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
internal data class RenameDto(@SerialName("title") val title: String)
