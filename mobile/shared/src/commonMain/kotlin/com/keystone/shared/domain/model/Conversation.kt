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
    /** Cards from the tools the assistant used for this answer, in call order. */
    val cards: List<ToolCard> = emptyList(),
    /** Policy sources the answer cited. */
    val citations: List<Citation> = emptyList(),
    /** Changes the answer prepared, with their current state (confirmed, expired, ...). */
    val actions: List<PendingAction> = emptyList(),
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
