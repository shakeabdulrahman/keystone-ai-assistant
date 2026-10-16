package com.keystone.shared.data.repository

import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import com.keystone.shared.domain.model.PendingAction
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class ActionRepositoryImplTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun body(status: String, result: String = "null") = """{"id":"a1","tool":"submitLeaveRequest",
        "status":"$status","title":"Request annual leave","fields":[],"notes":[],
        "confirm_label":"Submit request","expires_at":"2026-10-15T09:15:00Z","result":$result,"error":null}"""

    private fun repository(handler: MockRequestHandler) =
        ActionRepositoryImpl(KeystoneApi(createHttpClient(MockEngine(handler)), "http://test"))

    private fun AppResult<PendingAction>.value(): PendingAction = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("expected success, got $error")
    }

    @Test
    fun confirmPostsAndReturnsTheResult() = runTest {
        val repo = repository { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://test/v1/actions/a1/confirm", request.url.toString())
            respond(body("executed", """{"reference":"LR-1050","message":"Submitted."}"""), HttpStatusCode.OK, json)
        }
        val action = repo.confirm("a1").value()
        assertEquals(PendingAction.Status.Executed, action.status)
        assertEquals("LR-1050", action.result?.reference)
    }

    @Test
    fun conflictShowsWhatActuallyHappened() = runTest {
        val calls = mutableListOf<String>()
        val repo = repository { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            if (request.method == HttpMethod.Post) {
                respond(
                    """{"code":"action_expired","status":409,"detail":"This request expired."}""",
                    HttpStatusCode.Conflict,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            } else {
                respond(body("expired"), HttpStatusCode.OK, json)
            }
        }
        assertEquals(PendingAction.Status.Expired, repo.confirm("a1").value().status)
        assertEquals(listOf("POST /v1/actions/a1/confirm", "GET /v1/actions/a1"), calls)
    }

    @Test
    fun offlineIsAFailureSoTheCardCanOfferARetry() = runTest {
        val repo = repository { throw IOException("offline") }
        assertEquals(AppResult.Failure(AppError.Network), repo.cancel("a1"))
    }
}
