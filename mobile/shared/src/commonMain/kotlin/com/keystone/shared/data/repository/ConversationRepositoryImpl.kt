package com.keystone.shared.data.repository

import com.keystone.shared.core.AppResult
import com.keystone.shared.core.map
import com.keystone.shared.data.local.ConversationDao
import com.keystone.shared.data.local.MessageDao
import com.keystone.shared.data.local.toDomain
import com.keystone.shared.data.local.toEntity
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.safeApiCall
import com.keystone.shared.domain.model.Conversation
import com.keystone.shared.domain.model.StoredMessage
import com.keystone.shared.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConversationRepositoryImpl(
    private val api: KeystoneApi,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) : ConversationRepository {

    private val pagingLock = Mutex()
    private var nextCursor: String? = null

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun refreshConversations(): AppResult<Boolean> = pagingLock.withLock {
        // Mapping happens inside safeApiCall so a malformed date becomes an error, not a crash.
        safeApiCall {
            val page = api.listConversations(PAGE_SIZE, cursor = null)
            // Replace, not merge: conversations deleted on another device disappear here too.
            conversationDao.replaceAll(page.items.map { it.toEntity() })
            nextCursor = page.nextCursor
            page.nextCursor != null
        }
    }

    override suspend fun loadMoreConversations(): AppResult<Boolean> = pagingLock.withLock {
        val cursor = nextCursor ?: return@withLock AppResult.Success(false)
        safeApiCall {
            val page = api.listConversations(PAGE_SIZE, cursor)
            conversationDao.upsertAll(page.items.map { it.toEntity() })
            nextCursor = page.nextCursor
            page.nextCursor != null
        }
    }

    override fun observeMessages(conversationId: String): Flow<List<StoredMessage>> =
        messageDao.observe(conversationId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun refreshMessages(conversationId: String): AppResult<Unit> =
        safeApiCall {
            val page = api.listMessages(conversationId, MESSAGE_PAGE_SIZE)
            messageDao.replaceForConversation(conversationId, page.items.map { it.toEntity(conversationId) })
        }

    override suspend fun rename(conversationId: String, title: String): AppResult<Unit> {
        val previous = conversationDao.get(conversationId)
        conversationDao.rename(conversationId, title) // the UI updates instantly
        val result = safeApiCall { api.renameConversation(conversationId, title) }
        if (result is AppResult.Failure && previous != null) {
            conversationDao.rename(conversationId, previous.title) // roll back
        }
        return result.map { }
    }

    override suspend fun delete(conversationId: String): AppResult<Unit> {
        val previous = conversationDao.get(conversationId)
        val previousMessages = messageDao.getAll(conversationId)
        conversationDao.delete(conversationId)
        messageDao.deleteForConversation(conversationId)
        val result = safeApiCall { api.deleteConversation(conversationId) }
        if (result is AppResult.Failure && previous != null) {
            conversationDao.upsertAll(listOf(previous)) // roll back
            messageDao.upsertAll(previousMessages)
        }
        return result
    }

    override suspend fun clearCache() {
        pagingLock.withLock { nextCursor = null }
        messageDao.deleteAll()
        conversationDao.deleteAll()
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val MESSAGE_PAGE_SIZE = 200
    }
}
