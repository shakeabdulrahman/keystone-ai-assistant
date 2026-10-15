package com.keystone.android.auth

import kotlinx.coroutines.flow.StateFlow

/** Who is signed in. The whole UI switches on this. */
sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val uid: String, val email: String?) : AuthState
}

/** Sign-in failures the UI can explain. Account existence is never revealed. */
enum class AuthError { InvalidEmail, InvalidCredentials, EmailInUse, WeakPassword, TooManyAttempts, Network, Unknown }

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

/**
 * Identity, independent of the provider. ViewModels depend on this interface, so they're
 * tested with a fake and Firebase could be swapped without touching the UI.
 */
interface AuthService {
    val authState: StateFlow<AuthState>
    suspend fun signIn(email: String, password: String): AuthResult
    suspend fun createAccount(email: String, password: String): AuthResult
    fun signOut()
}
