package com.keystone.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.cancel

/**
 * Attaches `Authorization: Bearer <token>` to every request. If the server answers 401
 * (typically an expired token), it asks the provider for a fresh token and retries once.
 * A second 401 is returned to the caller, which surfaces it as "sign in again".
 *
 * Built on HttpSend so it also covers streaming requests (`prepare...().execute {}`).
 */
internal fun HttpClient.installBearerAuth(tokens: AuthTokenProvider) {
    plugin(HttpSend).intercept { request ->
        tokens.token(forceRefresh = false)?.let { request.headers[HttpHeaders.Authorization] = "Bearer $it" }
        val call = execute(request)
        if (call.response.status != HttpStatusCode.Unauthorized) return@intercept call

        val fresh = tokens.token(forceRefresh = true) ?: return@intercept call
        call.response.cancel() // release the first response before retrying
        request.headers[HttpHeaders.Authorization] = "Bearer $fresh"
        execute(request)
    }
}
