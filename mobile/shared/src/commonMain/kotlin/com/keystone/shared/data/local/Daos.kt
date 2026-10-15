package com.keystone.shared.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageAtMillis DESC")
    abstract fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract suspend fun get(id: String): ConversationEntity?

    @Upsert
    abstract suspend fun upsertAll(items: List<ConversationEntity>)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    abstract suspend fun rename(id: String, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    abstract suspend fun deleteAll()

    /** Atomic: observers never see the list half-replaced. */
    @Transaction
    open suspend fun replaceAll(items: List<ConversationEntity>) {
        deleteAll()
        upsertAll(items)
    }
}

@Dao
abstract class MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC")
    abstract fun observe(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC")
    abstract suspend fun getAll(conversationId: String): List<MessageEntity>

    @Upsert
    abstract suspend fun upsertAll(items: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun deleteForConversation(conversationId: String)

    @Query("DELETE FROM messages")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun replaceForConversation(conversationId: String, items: List<MessageEntity>) {
        deleteForConversation(conversationId)
        upsertAll(items)
    }
}
