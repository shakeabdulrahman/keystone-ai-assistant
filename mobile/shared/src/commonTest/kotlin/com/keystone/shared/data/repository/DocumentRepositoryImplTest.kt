package com.keystone.shared.data.repository

import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class DocumentRepositoryImplTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val body = """{"id":"leave-policy","title":"Leave Policy","version":"2026.1",
        "effective":"2026-01-01","owner":"People Team","updated_at":"2026-10-16T07:00:00Z",
        "sections":[{"index":0,"heading":"Overview","body":"Intro"},
                    {"index":1,"heading":"Leave entitlement","body":"- **Annual leave:** 20 days"}]}"""

    private fun repository(handler: MockRequestHandler) =
        DocumentRepositoryImpl(KeystoneApi(createHttpClient(MockEngine(handler)), "http://test"))

    @Test
    fun loadsADocumentOnceThenServesItFromMemory() = runTest {
        var requests = 0
        val repo = repository { request ->
            requests++
            assertEquals("http://test/v1/documents/leave-policy", request.url.toString())
            respond(body, HttpStatusCode.OK, json)
        }

        val doc = when (val first = repo.document("leave-policy")) {
            is AppResult.Success -> first.value
            is AppResult.Failure -> fail("expected a document, got ${first.error}")
        }
        repo.document("leave-policy")

        assertEquals(1, requests)
        assertEquals(listOf("Overview", "Leave entitlement"), doc.sections.map { it.heading })
    }

    @Test
    fun unknownDocumentIsAnHttpFailureAndIsNotCached() = runTest {
        var requests = 0
        val repo = repository {
            requests++
            respond(
                """{"code":"not_found","status":404}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
        assertEquals(AppResult.Failure(AppError.Http(404, "not_found")), repo.document("nope"))
        repo.document("nope")
        assertEquals(2, requests)
    }

    @Test
    fun malformedDateIsAFailureNotACrash() = runTest {
        val repo = repository { respond(body.replace("2026-01-01", "soon"), HttpStatusCode.OK, json) }
        assertIs<AppResult.Failure>(repo.document("leave-policy"))
    }
}
