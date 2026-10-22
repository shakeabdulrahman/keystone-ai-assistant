package com.keystone.shared.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory stand-ins for the Room DAOs, so repository logic is tested on every platform. */
class FakeConversationDao : ConversationDao() {
    val rows = MutableStateFlow<Map<String, ConversationEntity>>(emptyMap())

    override fun observeAll(): Flow<List<ConversationEntity>> =
        rows.map { it.values.sortedByDescending { c -> c.lastMessageAtMillis } }

    override suspend fun get(id: String) = rows.value[id]

    override suspend fun upsertAll(items: List<ConversationEntity>) {
        rows.value = rows.value + items.associateBy { it.id }
    }

    override suspend fun rename(id: String, title: String) {
        val row = rows.value[id] ?: return
        rows.value = rows.value + (id to row.copy(title = title))
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

class FakeMessageDao : MessageDao() {
    val rows = MutableStateFlow<Map<String, MessageEntity>>(emptyMap())

    override fun observe(conversationId: String): Flow<List<MessageEntity>> =
        rows.map { all -> all.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAtMillis } }

    override suspend fun getAll(conversationId: String) =
        rows.value.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAtMillis }

    override suspend fun upsertAll(items: List<MessageEntity>) {
        rows.value = rows.value + items.associateBy { it.id }
    }

    override suspend fun deleteForConversation(conversationId: String) {
        rows.value = rows.value.filterValues { it.conversationId != conversationId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

class FakeCachedJsonDao : CachedJsonDao() {
    val rows = MutableStateFlow<Map<String, CachedJsonEntity>>(emptyMap())

    override fun observe(key: String): Flow<CachedJsonEntity?> =
        rows.map { it[key] }

    override suspend fun put(entity: CachedJsonEntity) {
        rows.value = rows.value + (entity.key to entity)
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}
