package com.keystone.android.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthService @Inject constructor(
    private val auth: FirebaseAuth,
) : AuthService {

    // Firebase restores the signed-in user synchronously from disk at startup.
    private val state = MutableStateFlow(auth.currentUser.toAuthState())
    override val authState: StateFlow<AuthState> = state.asStateFlow()

    init {
        auth.addAuthStateListener { state.value = it.currentUser.toAuthState() }
    }

    override suspend fun signIn(email: String, password: String): AuthResult =
        runAuth { auth.signInWithEmailAndPassword(email.trim(), password).await() }

    override suspend fun createAccount(email: String, password: String): AuthResult =
        runAuth { auth.createUserWithEmailAndPassword(email.trim(), password).await() }

    override fun signOut() = auth.signOut()

    private suspend fun runAuth(block: suspend () -> Unit): AuthResult = try {
        block()
        AuthResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AuthResult.Failure(e.toAuthError())
    }
}

private fun FirebaseUser?.toAuthState(): AuthState =
    if (this == null) AuthState.SignedOut else AuthState.SignedIn(uid, email)

/** Order matters: the weak-password exception is a subclass of invalid-credentials. */
internal fun Exception.toAuthError(): AuthError = when (this) {
    is FirebaseNetworkException -> AuthError.Network
    is FirebaseTooManyRequestsException -> AuthError.TooManyAttempts
    is FirebaseAuthWeakPasswordException -> AuthError.WeakPassword
    is FirebaseAuthUserCollisionException -> AuthError.EmailInUse
    is FirebaseAuthInvalidCredentialsException ->
        if (errorCode == "ERROR_INVALID_EMAIL") AuthError.InvalidEmail else AuthError.InvalidCredentials
    // "No such user" is reported like a wrong password so attackers can't probe for accounts.
    is FirebaseAuthInvalidUserException -> AuthError.InvalidCredentials
    else -> AuthError.Unknown
}
