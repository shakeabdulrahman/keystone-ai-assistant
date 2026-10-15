package com.keystone.android.ui.conversations

import app.cash.turbine.test
import com.keystone.android.MainDispatcherRule
import com.keystone.android.fakes.FakeConversationRepository
import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.Conversation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeConversationRepository()
    private val sample = Conversation("c1", "Leave policy", Instant.fromEpochMilliseconds(1_000))

    @Test
    fun `shows cached conversations and refreshes on open`() = runTest(mainDispatcherRule.dispatcher) {
        repo.conversations.value = listOf(sample)
        val viewModel = ConversationsViewModel(repo)

        viewModel.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(listOf(sample), state.conversations)
            assertTrue(state.hasRefreshedOnce)
        }
        assertEquals("refreshConversations", repo.calls.first())
    }

    @Test
    fun `offline refresh keeps the cached list and reports the error`() = runTest(mainDispatcherRule.dispatcher) {
        repo.conversations.value = listOf(sample)
        repo.refreshResult = AppResult.Failure(AppError.Network)
        val viewModel = ConversationsViewModel(repo)

        viewModel.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(listOf(sample), state.conversations)
            assertEquals(AppError.Network, state.refreshError)
        }
    }

    @Test
    fun `failed rename surfaces an action error`() = runTest(mainDispatcherRule.dispatcher) {
        repo.actionResult = AppResult.Failure(AppError.Network)
        val viewModel = ConversationsViewModel(repo)

        viewModel.uiState.test {
            viewModel.onRename("c1", "  New title  ")
            advanceUntilIdle()
            assertEquals(AppError.Network, expectMostRecentItem().actionError)
        }
        assertTrue("rename:c1:New title" in repo.calls)
    }

    @Test
    fun `blank rename is ignored`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = ConversationsViewModel(repo)
        viewModel.onRename("c1", "   ")
        advanceUntilIdle()
        assertTrue(repo.calls.none { it.startsWith("rename") })
    }
}
