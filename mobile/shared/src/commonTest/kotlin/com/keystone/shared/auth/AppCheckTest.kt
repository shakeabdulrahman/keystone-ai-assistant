package com.keystone.shared.auth

import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import com.keystone.shared.data.repository.SystemRepositoryImpl
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AppCheckTest {

    private val ok = """{"name":"keystone","version":"1","environment":"test","api_version":"v1"}"""
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun appCheck(block: suspend () -> String?) = object : AppCheckTokenProvider {
        override suspend fun token(): String? = block()
    }

    private fun tokens(vararg values: String) = object : AuthTokenProvider {
        var calls = 0
        override suspend fun token(forceRefresh: Boolean): String = values[minOf(calls++, values.lastIndex)]
    }

    @Test
    fun attachesAppCheckHeader() = runTest {
        var seen: String? = null
        val engine = MockEngine { request ->
            seen = request.headers["X-Firebase-AppCheck"]
            respond(ok, HttpStatusCode.OK, json)
        }
        val repo = SystemRepositoryImpl(KeystoneApi(createHttpClient(engine, appCheck = appCheck { "ac-token" }), "http://test"))

        repo.backendInfo()

        assertEquals("ac-token", seen)
    }

    @Test
    fun providerFailureStillSendsTheRequestWithoutHeader() = runTest {
        var seen: String? = "unset"
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            seen = request.headers["X-Firebase-AppCheck"]
            respond(ok, HttpStatusCode.OK, json)
        }
        val client = createHttpClient(engine, appCheck = appCheck { error("Play Integrity unavailable") })

        val result = SystemRepositoryImpl(KeystoneApi(client, "http://test")).backendInfo()

        assertIs<AppResult.Success<*>>(result)
        assertEquals(1, requests)
        assertNull(seen)
    }

    @Test
    fun headerSurvivesTheBearerRefreshRetry() = runTest {
        val seen = mutableListOf<Pair<String?, String?>>()
        val engine = MockEngine { request ->
            seen += request.headers[HttpHeaders.Authorization] to request.headers["X-Firebase-AppCheck"]
            if (seen.size == 1) respond("{}", HttpStatusCode.Unauthorized, json) else respond(ok, HttpStatusCode.OK, json)
        }
        val client = createHttpClient(engine, tokens("old", "new"), appCheck { "ac" })

        SystemRepositoryImpl(KeystoneApi(client, "http://test")).backendInfo()

        assertEquals(listOf("Bearer old" to "ac", "Bearer new" to "ac"), seen)
    }

    @Test
    fun rateLimitCarriesRetryAfter() = runTest {
        val engine = MockEngine {
            respond(
                """{"code":"rate_limited","retry_after_s":7}""",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.ContentType to listOf("application/problem+json"), HttpHeaders.RetryAfter to listOf("7")),
            )
        }
        val result = SystemRepositoryImpl(KeystoneApi(createHttpClient(engine), "http://test")).backendInfo()

        val error = assertIs<AppResult.Failure>(result).error
        assertEquals(AppError.Http(429, "rate_limited", 7), error)
    }
}
