package com.keystone.android.ui

import com.keystone.android.MainDispatcherRule
import com.keystone.android.auth.AuthState
import com.keystone.android.fakes.FakeAccountRepository
import com.keystone.android.fakes.FakeAuthService
import com.keystone.android.fakes.FakeConversationRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `sign out wipes the cache before ending the session`() = runTest(mainDispatcherRule.dispatcher) {
        val auth = FakeAuthService(AuthState.SignedIn("u1", "a@b.c"))
        val conversations = FakeConversationRepository()
        val viewModel = AppViewModel(auth, FakeAccountRepository(), conversations)

        viewModel.signOut()
        advanceUntilIdle()

        assertEquals(listOf("clearCache"), conversations.calls)
        assertEquals("signOut", auth.calls.last())
        assertEquals(AuthState.SignedOut, viewModel.authState.value)
    }

    @Test
    fun `backend session starts once per signed-in user`() = runTest(mainDispatcherRule.dispatcher) {
        val auth = FakeAuthService(AuthState.SignedIn("u1", "a@b.c"))
        val accounts = FakeAccountRepository()
        AppViewModel(auth, accounts, FakeConversationRepository())
        advanceUntilIdle()

        auth.authState.value = AuthState.SignedIn("u1", "a@b.c") // same user again: no new session
        advanceUntilIdle()
        auth.authState.value = AuthState.SignedIn("u2", "x@y.z")
        advanceUntilIdle()

        assertEquals(2, accounts.sessions.size)
    }
}
