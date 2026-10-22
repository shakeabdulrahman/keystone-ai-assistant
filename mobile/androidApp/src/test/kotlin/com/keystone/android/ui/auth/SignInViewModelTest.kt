package com.keystone.android.ui.auth

import com.keystone.android.MainDispatcherRule
import com.keystone.android.auth.AuthError
import com.keystone.android.auth.AuthResult
import com.keystone.android.fakes.FakeAuthService
import com.keystone.android.fakes.FakeConversationRepository
import com.keystone.android.fakes.FakeHomeRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SignInViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val auth = FakeAuthService()
    private val conversations = FakeConversationRepository()
    private val home = FakeHomeRepository()
    private val viewModel = SignInViewModel(auth, conversations, home)

    @Test
    fun `invalid email is caught before calling Firebase`() = runTest(mainDispatcherRule.dispatcher) {
        viewModel.onEmailChange("not-an-email")
        viewModel.onPasswordChange("secret123")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.emailInvalid)
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `new accounts need a long enough password`() = runTest(mainDispatcherRule.dispatcher) {
        viewModel.onToggleMode()
        viewModel.onEmailChange("new@keystone.dev")
        viewModel.onPasswordChange("short")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.passwordTooShort)
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `successful sign in clears any previous user's cache first`() = runTest(mainDispatcherRule.dispatcher) {
        viewModel.onEmailChange("  test@keystone.dev ")
        viewModel.onPasswordChange("password1")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(listOf("signIn:test@keystone.dev"), auth.calls)
        assertEquals(listOf("clearCache"), conversations.calls)
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun `wrong password shows a friendly error and clears the password`() = runTest(mainDispatcherRule.dispatcher) {
        auth.nextResult = AuthResult.Failure(AuthError.InvalidCredentials)
        viewModel.onEmailChange("test@keystone.dev")
        viewModel.onPasswordChange("wrong-password")
        viewModel.onSubmit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AuthError.InvalidCredentials, state.error)
        assertEquals("", state.password)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `typing clears the previous error`() = runTest(mainDispatcherRule.dispatcher) {
        auth.nextResult = AuthResult.Failure(AuthError.Network)
        viewModel.onEmailChange("test@keystone.dev")
        viewModel.onPasswordChange("password1")
        viewModel.onSubmit()
        advanceUntilIdle()

        viewModel.onPasswordChange("password2")

        assertEquals(null, viewModel.uiState.value.error)
    }
}
