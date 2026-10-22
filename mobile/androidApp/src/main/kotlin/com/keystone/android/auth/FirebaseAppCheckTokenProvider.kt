package com.keystone.android.auth

import com.google.firebase.appcheck.FirebaseAppCheck
import com.keystone.shared.auth.AppCheckTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * App Check tokens from Firebase: Play Integrity in release builds, the debug provider in
 * debug builds (see AppCheckFactory in src/debug and src/release). Firebase caches the
 * token and refreshes it before expiry, so most calls return immediately.
 *
 * Never throws and never waits long: a missing token must not block the request.
 */
class FirebaseAppCheckTokenProvider @Inject constructor(
    private val appCheck: FirebaseAppCheck,
) : AppCheckTokenProvider {
    override suspend fun token(): String? = withTimeoutOrNull(TIMEOUT_MS) {
        try {
            appCheck.getAppCheckToken(false).await().token
        } catch (e: CancellationException) {
            throw e // our caller was cancelled, or the timeout fired (→ null above)
        } catch (e: Exception) {
            null // not registered yet, Play Integrity unavailable, no network…
        }
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
    }
}
