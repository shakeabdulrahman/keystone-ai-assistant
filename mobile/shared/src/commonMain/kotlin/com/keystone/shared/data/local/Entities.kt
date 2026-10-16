package com.keystone.shared.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached copy of a server conversation. Timestamps are epoch milliseconds (UTC). */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastMessageAtMillis: Long,
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId", "createdAtMillis"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val status: String,
    val createdAtMillis: Long,
    /** The answer's tool results as the server's JSON `parts` array, parsed into cards on read. */
    val partsJson: String = "[]",
)
