package com.keystone.shared.data.repository

import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import com.keystone.shared.domain.model.BackendInfo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemRepositoryImplTest {

    private fun repository(handler: MockRequestHandler): SystemRepositoryImpl {
        val client = createHttpClient(MockEngine(handler))
        return SystemRepositoryImpl(KeystoneApi(client, baseUrl = "http://test/"))
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun mapsMetaResponseToDomainModel() = runTest {
        val repo = repository { request ->
            assertEquals("http://test/v1/meta", request.url.toString())
            respond(
                """{"name":"keystone","version":"0.1.0","environment":"local","api_version":"v1","extra":"ignored"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        assertEquals(
            AppResult.Success(BackendInfo("keystone", "0.1.0", "local")),
            repo.backendInfo(),
        )
    }

    @Test
    fun mapsProblemJsonToHttpErrorWithCode() = runTest {
        val repo = repository {
            respond(
                """{"code":"service_unavailable","status":503}""",
                HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }

        assertEquals(AppResult.Failure(AppError.Http(503, "service_unavailable")), repo.backendInfo())
    }

    @Test
    fun mapsMalformedBodyToInvalidResponse() = runTest {
        val repo = repository { respond("""{"unexpected":true}""", HttpStatusCode.OK, jsonHeaders) }

        assertEquals(AppResult.Failure(AppError.InvalidResponse), repo.backendInfo())
    }

    @Test
    fun mapsIoFailureToNetworkError() = runTest {
        val repo = repository { throw IOException("connection refused") }

        assertEquals(AppResult.Failure(AppError.Network), repo.backendInfo())
    }
}
