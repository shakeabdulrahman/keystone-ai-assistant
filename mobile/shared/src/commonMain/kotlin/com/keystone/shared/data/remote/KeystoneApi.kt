package com.keystone.shared.data.remote

import com.keystone.shared.data.remote.dto.ChatRequestDto
import com.keystone.shared.data.remote.dto.MetaDto
import com.keystone.shared.data.remote.sse.SseEvent
import com.keystone.shared.data.remote.sse.SseEventParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Thin, typed wrapper over the backend's HTTP API. One method per endpoint. */
class KeystoneApi(
    private val client: HttpClient,
    baseUrl: String,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    internal suspend fun meta(): MetaDto = client.get("$baseUrl/v1/meta").body()

    /**
     * Opens `POST /v1/chat` and emits SSE events as their lines arrive.
     * The response body is read incrementally, never buffered whole.
     */
    internal fun streamChat(request: ChatRequestDto): Flow<SseEvent> = flow {
        client.preparePost("$baseUrl/v1/chat") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            setBody(request)
            // The server sends a keep-alive at least every 15 s, so 60 s of silence means trouble.
            timeout { socketTimeoutMillis = STREAM_IDLE_TIMEOUT_MS }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val parser = SseEventParser()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                parser.consume(line)?.let { emit(it) }
            }
            parser.finish()?.let { emit(it) }
        }
    }

    private companion object {
        const val STREAM_IDLE_TIMEOUT_MS = 60_000L
    }
}
