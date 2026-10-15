package com.keystone.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keystone.android.auth.AuthError
import com.keystone.android.auth.AuthResult
import com.keystone.android.auth.AuthService
import com.keystone.shared.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val mode: Mode = Mode.SignIn,
    val isSubmitting: Boolean = false,
    val emailInvalid: Boolean = false,
    val passwordTooShort: Boolean = false,
    val error: AuthError? = null,
) {
    enum class Mode { SignIn, CreateAccount }
}

const val MIN_PASSWORD_LENGTH = 8

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val auth: AuthService,
    private val conversations: ConversationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) =
        _uiState.update { it.copy(email = value, emailInvalid = false, error = null) }

    fun onPasswordChange(value: String) =
        _uiState.update { it.copy(password = value, passwordTooShort = false, error = null) }

    fun onToggleMode() = _uiState.update {
        it.copy(
            mode = if (it.mode == SignInUiState.Mode.SignIn) SignInUiState.Mode.CreateAccount else SignInUiState.Mode.SignIn,
            error = null,
            passwordTooShort = false,
        )
    }

    fun onSubmit() {
        val state = _uiState.value
        if (state.isSubmitting) return
        val email = state.email.trim()
        val emailInvalid = !EMAIL.matches(email)
        // Only new passwords are checked locally; existing ones are whatever the user chose.
        val passwordTooShort =
            state.mode == SignInUiState.Mode.CreateAccount && state.password.length < MIN_PASSWORD_LENGTH
        if (emailInvalid || passwordTooShort || state.password.isEmpty()) {
            _uiState.update { it.copy(emailInvalid = emailInvalid, passwordTooShort = passwordTooShort) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            // Never show one user's cached history to another user on a shared device.
            conversations.clearCache()
            val result = when (state.mode) {
                SignInUiState.Mode.SignIn -> auth.signIn(email, state.password)
                SignInUiState.Mode.CreateAccount -> auth.createAccount(email, state.password)
            }
            // On success the app's auth state changes and the signed-in UI replaces this screen.
            _uiState.update {
                it.copy(
                    isSubmitting = false,
                    password = if (result is AuthResult.Failure) "" else it.password,
                    error = (result as? AuthResult.Failure)?.error,
                )
            }
        }
    }

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
