package com.keystone.shared.domain.repository

import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.Conversation
import com.keystone.shared.domain.model.StoredMessage
import com.keystone.shared.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow

/**
 * Offline-first conversation history.
 *
 * `observe*` functions read the on-device cache and emit immediately (also with no
 * network). `refresh*` functions fetch from the server and update the cache, which in
 * turn updates every observer: the database is the single source of truth for the UI.
 */
interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>

    /** Reloads the first page from the server. Returns whether more pages exist. */
    suspend fun refreshConversations(): AppResult<Boolean>

    /** Appends the next page. Returns false when there is nothing more to load. */
    suspend fun loadMoreConversations(): AppResult<Boolean>

    fun observeMessages(conversationId: String): Flow<List<StoredMessage>>

    /** Reloads the latest messages of one conversation. */
    suspend fun refreshMessages(conversationId: String): AppResult<Unit>

    /** Optimistic: the cache changes first and is rolled back if the server refuses. */
    suspend fun rename(conversationId: String, title: String): AppResult<Unit>

    /** Optimistic, like [rename]. */
    suspend fun delete(conversationId: String): AppResult<Unit>

    /** Wipes all cached history (on sign-out: HR data must not outlive the session). */
    suspend fun clearCache()
}

interface AccountRepository {
    /** Registers the signed-in user with the backend and returns their profile. */
    suspend fun startSession(): AppResult<UserProfile>

    /** Permanently deletes the account and all its data on the server. */
    suspend fun deleteAccount(): AppResult<Unit>
}
