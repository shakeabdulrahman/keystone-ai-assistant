package com.keystone.android.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.keystone.shared.auth.AuthTokenProvider
import kotlinx.coroutines.tasks.await
import java.io.IOException
import javax.inject.Inject

/**
 * Gives the shared networking layer the current Firebase ID token. Firebase caches the
 * token and refreshes it before it expires (tokens live about an hour), so this is cheap.
 */
class FirebaseTokenProvider @Inject constructor(
    private val auth: FirebaseAuth,
) : AuthTokenProvider {
    override suspend fun token(forceRefresh: Boolean): String? {
        val user = auth.currentUser ?: return null
        return try {
            user.getIdToken(forceRefresh).await().token
        } catch (e: FirebaseNetworkException) {
            // Report as a network problem, which the app already explains and retries.
            throw IOException("Could not refresh the sign-in token", e)
        }
    }
}
