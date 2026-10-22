package com.keystone.android.ui

import com.keystone.android.MainDispatcherRule
import com.keystone.android.auth.AuthState
import com.keystone.android.fakes.FakeAccountRepository
import com.keystone.android.fakes.FakeAuthService
import com.keystone.android.fakes.FakeConversationRepository
import com.keystone.android.fakes.FakeDeviceRepository
import com.keystone.android.fakes.FakeHomeRepository
import com.keystone.android.fakes.FakePushTokens
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `sign out wipes the cache before ending the session`() = runTest(mainDispatcherRule.dispatcher) {
        val auth = FakeAuthService(AuthState.SignedIn("u1", "a@b.c"))
        val conversations = FakeConversationRepository()
        val viewModel = AppViewModel(auth, FakeAccountRepository(), conversations, FakeHomeRepository(), FakeDeviceRepository(), FakePushTokens())

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
        AppViewModel(auth, accounts, FakeConversationRepository(), FakeHomeRepository(), FakeDeviceRepository(), FakePushTokens())
        advanceUntilIdle()

        auth.authState.value = AuthState.SignedIn("u1", "a@b.c") // same user again: no new session
        advanceUntilIdle()
        auth.authState.value = AuthState.SignedIn("u2", "x@y.z")
        advanceUntilIdle()

        assertEquals(2, accounts.sessions.size)
    }

    @Test
    fun `this phone is registered for pushes after sign-in`() = runTest(mainDispatcherRule.dispatcher) {
        val devices = FakeDeviceRepository()
        AppViewModel(
            FakeAuthService(AuthState.SignedIn("u1", "a@b.c")),
            FakeAccountRepository(),
            FakeConversationRepository(),
            FakeHomeRepository(),
            devices,
            FakePushTokens("tok-1"),
        )
        advanceUntilIdle()
        assertEquals(listOf("register:tok-1"), devices.calls)
    }

    @Test
    fun `sign out stops pushes while still signed in, then wipes every cache`() =
        runTest(mainDispatcherRule.dispatcher) {
            val auth = FakeAuthService(AuthState.SignedIn("u1", "a@b.c"))
            var signedInWhenUnregistering = false
            val devices = FakeDeviceRepository { call ->
                if (call == "unregister") signedInWhenUnregistering = auth.authState.value is AuthState.SignedIn
            }
            val tokens = FakePushTokens("tok-1")
            val home = FakeHomeRepository()
            val viewModel = AppViewModel(auth, FakeAccountRepository(), FakeConversationRepository(), home, devices, tokens)
            advanceUntilIdle()

            viewModel.signOut()
            advanceUntilIdle()

            assertEquals(listOf("register:tok-1", "unregister:tok-1"), devices.calls)
            assertTrue(signedInWhenUnregistering) // the API call still had the user's token
            assertEquals(1, tokens.deleted) // the next account gets a new push token
            assertTrue(home.cleared)
            assertEquals(AuthState.SignedOut, auth.authState.value)
        }
}
