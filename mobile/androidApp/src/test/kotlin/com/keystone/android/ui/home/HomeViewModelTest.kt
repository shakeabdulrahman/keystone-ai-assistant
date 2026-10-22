package com.keystone.android.ui.home

import com.keystone.android.MainDispatcherRule
import com.keystone.android.fakes.FakeHomeRepository
import com.keystone.android.push.PushEvent
import com.keystone.android.push.PushEvents
import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.BackendInfo
import com.keystone.shared.domain.model.HomeCounts
import com.keystone.shared.domain.model.HomeDashboard
import com.keystone.shared.domain.repository.SystemRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
            return queue.removeFirstOrNull() ?: AppResult.Failure(AppError.Unknown)
        }
    }

    private fun dashboard(name: String) = HomeDashboard(
        generatedAt = Instant.fromEpochMilliseconds(0),
        firstName = name,
        leave = emptyList(),
        attention = emptyList(),
        upcoming = emptyList(),
        counts = HomeCounts(0, 0, 0),
        suggestions = emptyList(),
    )

    private val push = PushEvents()

    @Test
    fun `cached dashboard shows at once, then the fresh one replaces it`() = runTest(mainDispatcherRule.dispatcher) {
        val home = FakeHomeRepository(cached = dashboard("Cached")).apply {
            server = dashboard("Fresh")
            gate = CompletableDeferred()
        }
        val vm = HomeViewModel(FakeSystemRepository(AppResult.Success(info)), home, push)
        runCurrent()

        assertEquals("Cached", vm.uiState.value.dashboard?.firstName)
        assertTrue(vm.uiState.value.refreshing)

        home.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals("Fresh", vm.uiState.value.dashboard?.firstName)
        assertFalse(vm.uiState.value.refreshing)
        assertNull(vm.uiState.value.refreshError)
        assertEquals(BackendStatus.Connected(info), vm.uiState.value.backend)
    }

    @Test
    fun `offline keeps the cached dashboard and says so`() = runTest(mainDispatcherRule.dispatcher) {
        val home = FakeHomeRepository(cached = dashboard("Cached")).apply {
            nextResult = AppResult.Failure(AppError.Network)
        }
        val vm = HomeViewModel(FakeSystemRepository(AppResult.Failure(AppError.Network)), home, push)
        advanceUntilIdle()

        assertEquals("Cached", vm.uiState.value.dashboard?.firstName)
        assertEquals(AppError.Network, vm.uiState.value.refreshError)
        assertEquals(BackendStatus.Unreachable(AppError.Network), vm.uiState.value.backend)
    }

    @Test
    fun `a push notification refreshes the dashboard`() = runTest(mainDispatcherRule.dispatcher) {
        val home = FakeHomeRepository()
        HomeViewModel(FakeSystemRepository(AppResult.Success(info)), home, push)
        advanceUntilIdle()
        assertEquals(1, home.refreshes)

        push.emit(PushEvent(kind = "leave", ref = "LR-1050"))
        advanceUntilIdle()
        assertEquals(2, home.refreshes)
    }

    @Test
    fun `refreshes never overlap`() = runTest(mainDispatcherRule.dispatcher) {
        val home = FakeHomeRepository().apply { gate = CompletableDeferred() }
        val vm = HomeViewModel(FakeSystemRepository(AppResult.Success(info)), home, push)
        runCurrent()
        vm.onRefresh() // e.g. ON_RESUME right after opening
        vm.onRefresh()
        assertEquals(1, home.refreshes) // still only the first request in flight
        home.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, home.refreshes) // the asks during it collapse into one follow-up
    }

    @Test
    fun `a push during a refresh triggers one more refresh afterwards`() =
        runTest(mainDispatcherRule.dispatcher) {
            val home = FakeHomeRepository().apply { gate = CompletableDeferred() }
            HomeViewModel(FakeSystemRepository(AppResult.Success(info)), home, push)
            runCurrent()
            push.emit(PushEvent(kind = "leave", ref = "LR-1050")) // arrives mid-refresh
            runCurrent()
            home.gate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, home.refreshes)
        }

    @Test
    fun `retry checks the backend again and reloads`() = runTest(mainDispatcherRule.dispatcher) {
        val system = FakeSystemRepository(AppResult.Failure(AppError.Network), AppResult.Success(info))
        val home = FakeHomeRepository().apply { nextResult = AppResult.Failure(AppError.Network) }
        val vm = HomeViewModel(system, home, push)
        advanceUntilIdle()
        assertEquals(AppError.Network, vm.uiState.value.refreshError)

        home.nextResult = AppResult.Success(Unit)
        home.server = dashboard("Back")
        vm.onRetry()
        advanceUntilIdle()

        assertEquals(2, system.calls)
        assertEquals(BackendStatus.Connected(info), vm.uiState.value.backend)
        assertEquals("Back", vm.uiState.value.dashboard?.firstName)
        assertNull(vm.uiState.value.refreshError)
    }
}
