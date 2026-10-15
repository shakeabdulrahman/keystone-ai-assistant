package com.keystone.android.fakes

import com.keystone.android.auth.AuthResult
import com.keystone.android.auth.AuthService
import com.keystone.android.auth.AuthState
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.Conversation
import com.keystone.shared.domain.model.StoredMessage
import com.keystone.shared.domain.model.UserProfile
import com.keystone.shared.domain.repository.AccountRepository
import com.keystone.shared.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeAuthService(initial: AuthState = AuthState.SignedOut) : AuthService {
    override val authState = MutableStateFlow(initial)
    var nextResult: AuthResult = AuthResult.Success
    val calls = mutableListOf<String>()

    override suspend fun signIn(email: String, password: String): AuthResult {
        calls += "signIn:$email"
        if (nextResult == AuthResult.Success) authState.value = AuthState.SignedIn("uid-$email", email)
        return nextResult
    }

    override suspend fun createAccount(email: String, password: String): AuthResult {
        calls += "create:$email"
        if (nextResult == AuthResult.Success) authState.value = AuthState.SignedIn("uid-$email", email)
        return nextResult
    }

    override fun signOut() {
        calls += "signOut"
        authState.value = AuthState.SignedOut
    }
}

class FakeConversationRepository : ConversationRepository {
    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val messages = MutableStateFlow<Map<String, List<StoredMessage>>>(emptyMap())
    /** What the "server" holds; refreshMessages copies it into [messages]. */
    var serverMessages: Map<String, List<StoredMessage>> = emptyMap()
    var refreshResult: AppResult<Boolean> = AppResult.Success(false)
    var actionResult: AppResult<Unit> = AppResult.Success(Unit)
    val calls = mutableListOf<String>()

    override fun observeConversations(): Flow<List<Conversation>> = conversations

    override suspend fun refreshConversations(): AppResult<Boolean> {
        calls += "refreshConversations"
        return refreshResult
    }

    override suspend fun loadMoreConversations(): AppResult<Boolean> {
        calls += "loadMore"
        return AppResult.Success(false)
    }

    override fun observeMessages(conversationId: String): Flow<List<StoredMessage>> =
        messages.map { it[conversationId].orEmpty() }

    override suspend fun refreshMessages(conversationId: String): AppResult<Unit> {
        calls += "refreshMessages:$conversationId"
        serverMessages[conversationId]?.let { messages.value = messages.value + (conversationId to it) }
        return AppResult.Success(Unit)
    }

    override suspend fun rename(conversationId: String, title: String): AppResult<Unit> {
        calls += "rename:$conversationId:$title"
        return actionResult
    }

    override suspend fun delete(conversationId: String): AppResult<Unit> {
        calls += "delete:$conversationId"
        return actionResult
    }

    override suspend fun clearCache() {
        calls += "clearCache"
        conversations.value = emptyList()
        messages.value = emptyMap()
    }
}

class FakeAccountRepository : AccountRepository {
    val sessions = mutableListOf<Unit>()
    override suspend fun startSession(): AppResult<UserProfile> {
        sessions += Unit
        return AppResult.Success(UserProfile("id", "e", null))
    }

    override suspend fun deleteAccount(): AppResult<Unit> = AppResult.Success(Unit)
}
