package com.keystone.android.auth

import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.keystone.android.BuildConfig
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
    override suspend fun token(): String? {
        val token = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                appCheck.getAppCheckToken(false).await().token
            } catch (e: CancellationException) {
                throw e // our caller was cancelled, or the timeout fired (→ null below)
            } catch (e: Exception) {
                // Not registered yet, debug token not allowed, Play Integrity unavailable…
                debugLog("App Check token failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
        if (token == null) debugLog("No App Check token: the request is sent without one")
        return token
    }

    /** Debug builds only, so problems are visible in Logcat (filter: KeystoneAppCheck). */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
        const val TAG = "KeystoneAppCheck"
    }
}
