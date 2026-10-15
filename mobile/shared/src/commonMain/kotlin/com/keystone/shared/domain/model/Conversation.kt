package com.keystone.shared.domain.model

import kotlinx.datetime.Instant

data class Conversation(
    val id: String,
    val title: String,
    val lastMessageAt: Instant,
)

data class StoredMessage(
    val id: String,
    val conversationId: String,
    val role: Role,
    val content: String,
    val status: Status,
    val createdAt: Instant,
) {
    enum class Role { User, Assistant }

    /** Mirrors the backend: Complete, Interrupted (stopped/disconnected) or Failed. */
    enum class Status { Complete, Interrupted, Failed }
}

data class UserProfile(
    val id: String,
    val email: String?,
    val displayName: String?,
)
