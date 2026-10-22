package com.keystone.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.CancellationException

/**
 * Supplies a Firebase App Check token: proof that a request comes from a genuine copy of
 * our app on a real device (Play Integrity on Android, App Attest on iOS), not a script
 * replaying a stolen sign-in token. Implemented per platform.
 */
interface AppCheckTokenProvider {
    /** The current token, or null if none could be obtained (the server then decides). */
    suspend fun token(): String?
}

internal const val APP_CHECK_HEADER = "X-Firebase-AppCheck"

/**
 * Adds the App Check header to every request. A failure to get a token never blocks the
 * request: in "monitor" mode the server only logs it, and in "enforce" mode it answers
 * 403 `app_check_failed`, which the app explains.
 */
internal fun HttpClient.installAppCheck(provider: AppCheckTokenProvider) {
    plugin(HttpSend).intercept { request ->
        val token = try {
            provider.token()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        token?.let { request.headers[APP_CHECK_HEADER] = it }
        execute(request)
    }
}
