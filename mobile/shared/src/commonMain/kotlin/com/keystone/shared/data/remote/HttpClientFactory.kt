package com.keystone.shared.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** The platform's native HTTP engine: OkHttp on Android, NSURLSession (Darwin) on iOS. */
expect fun platformHttpEngine(): HttpClientEngine

internal val ProblemJson = ContentType("application", "problem+json")

internal val KeystoneJson = Json {
    ignoreUnknownKeys = true // new backend fields must never crash older app versions
}

/**
 * Builds the single HttpClient used by every API in the app.
 * The engine is injectable so tests can pass a MockEngine.
 */
fun createHttpClient(engine: HttpClientEngine = platformHttpEngine()): HttpClient =
    HttpClient(engine) {
        expectSuccess = true // non-2xx responses throw, and safeApiCall maps them to AppError
        install(ContentNegotiation) {
            json(KeystoneJson)
            // Backend errors use RFC 9457 problem+json; decode them with the same rules.
            json(KeystoneJson, contentType = ProblemJson)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            // No overall request timeout: streaming chat responses (Phase 1) can run for minutes.
            socketTimeoutMillis = 30_000
        }
        defaultRequest { accept(ContentType.Application.Json) }
    }
