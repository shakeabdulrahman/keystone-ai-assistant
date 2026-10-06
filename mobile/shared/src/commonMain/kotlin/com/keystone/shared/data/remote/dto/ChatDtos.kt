package com.keystone.shared.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `POST /v1/chat`. See /contracts/README.md. */
@Serializable
internal data class ChatRequestDto(
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("client_message_id") val clientMessageId: String,
    @SerialName("content") val content: String,
)

@Serializable
internal data class MessageStartedDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("user_message_id") val userMessageId: String,
)

@Serializable
internal data class TextDeltaDto(@SerialName("text") val text: String)

@Serializable
internal data class UsageDto(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
)

@Serializable
internal data class MessageCompletedDto(
    @SerialName("message_id") val messageId: String,
    @SerialName("finish_reason") val finishReason: String,
    @SerialName("usage") val usage: UsageDto,
)

@Serializable
internal data class StreamErrorDto(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String,
    @SerialName("retryable") val retryable: Boolean,
)
