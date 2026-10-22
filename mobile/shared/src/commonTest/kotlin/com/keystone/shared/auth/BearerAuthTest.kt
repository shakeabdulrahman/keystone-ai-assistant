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

class BearerAuthTest {

    private class FakeTokens(private val tokens: List<String?>) : AuthTokenProvider {
        val requests = mutableListOf<Boolean>()
        override suspend fun token(forceRefresh: Boolean): String? {
            requests += forceRefresh
            return tokens[minOf(requests.size - 1, tokens.lastIndex)]
        }
    }

    private val ok = """{"name":"keystone","version":"1","environment":"test","api_version":"v1"}"""
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val problem = headersOf(HttpHeaders.ContentType, "application/problem+json")

    @Test
    fun attachesBearerToken() = runTest {
        var seen: String? = null
        val engine = MockEngine { request ->
            seen = request.headers[HttpHeaders.Authorization]
            respond(ok, HttpStatusCode.OK, json)
        }
        val repo = SystemRepositoryImpl(KeystoneApi(createHttpClient(engine, FakeTokens(listOf("t1"))), "http://test"))

        repo.backendInfo()

        assertEquals("Bearer t1", seen)
    }

    @Test
    fun refreshesOnceAfter401AndRetries() = runTest {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seen += request.headers[HttpHeaders.Authorization]
            if (seen.size == 1) {
                respond("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized, problem)
            } else {
                respond(ok, HttpStatusCode.OK, json)
            }
        }
        val tokens = FakeTokens(listOf("expired", "fresh"))
        val repo = SystemRepositoryImpl(KeystoneApi(createHttpClient(engine, tokens), "http://test"))

        val result = repo.backendInfo()

        assertIs<AppResult.Success<*>>(result)
        assertEquals(listOf<String?>("Bearer expired", "Bearer fresh"), seen)
        assertEquals(listOf(false, true), tokens.requests)
    }

    @Test
    fun secondUnauthorizedIsReturnedNotLooped() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized, problem)
        }
        val repo = SystemRepositoryImpl(
            KeystoneApi(createHttpClient(engine, FakeTokens(listOf("a", "b"))), "http://test"),
        )

        val result = repo.backendInfo()

        assertEquals(2, calls)
        assertEquals(AppResult.Failure(AppError.Http(401, "unauthorized")), result)
    }

    @Test
    fun signedOutSendsNoHeader() = runTest {
        var header: String? = "unset"
        val engine = MockEngine { request ->
            header = request.headers[HttpHeaders.Authorization]
            respond(ok, HttpStatusCode.OK, json)
        }
        val repo = SystemRepositoryImpl(KeystoneApi(createHttpClient(engine, FakeTokens(listOf(null))), "http://test"))

        repo.backendInfo()

        assertEquals(null, header)
    }
}
