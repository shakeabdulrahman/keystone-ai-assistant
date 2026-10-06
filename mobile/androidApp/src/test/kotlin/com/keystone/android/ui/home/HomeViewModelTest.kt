package com.keystone.android.ui.home

import app.cash.turbine.test
import com.keystone.android.MainDispatcherRule
import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.BackendInfo
import com.keystone.shared.domain.repository.SystemRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val info = BackendInfo("keystone", "0.1.0", "local")

    /** Hand-written fake: returns queued results in order. No mocking library needed. */
    private class FakeSystemRepository(vararg results: AppResult<BackendInfo>) : SystemRepository {
        private val queue = ArrayDeque(results.toList())
        var calls = 0
        override suspend fun backendInfo(): AppResult<BackendInfo> {
            calls++
            return queue.removeFirst()
        }
    }

    @Test
    fun `starts checking then shows connected`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = HomeViewModel(FakeSystemRepository(AppResult.Success(info)))

        viewModel.uiState.test {
            assertEquals(BackendStatus.Checking, awaitItem().backend)
            assertEquals(BackendStatus.Connected(info), awaitItem().backend)
        }
    }

    @Test
    fun `shows unreachable on network failure`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = HomeViewModel(FakeSystemRepository(AppResult.Failure(AppError.Network)))

        viewModel.uiState.test {
            assertEquals(BackendStatus.Checking, awaitItem().backend)
            assertEquals(BackendStatus.Unreachable(AppError.Network), awaitItem().backend)
        }
    }

    @Test
    fun `retry after failure recovers`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeSystemRepository(
            AppResult.Failure(AppError.Network),
            AppResult.Success(info),
        )
        val viewModel = HomeViewModel(repository)

        viewModel.uiState.test {
            assertEquals(BackendStatus.Checking, awaitItem().backend)
            assertEquals(BackendStatus.Unreachable(AppError.Network), awaitItem().backend)

            viewModel.onRetry()

            assertEquals(BackendStatus.Checking, awaitItem().backend)
            assertEquals(BackendStatus.Connected(info), awaitItem().backend)
        }
        assertEquals(2, repository.calls)
    }
}
