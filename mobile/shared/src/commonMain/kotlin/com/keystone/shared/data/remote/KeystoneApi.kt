package com.keystone.shared.data.remote

import com.keystone.shared.data.remote.dto.ChatRequestDto
import com.keystone.shared.data.remote.dto.ConversationDto
import com.keystone.shared.data.remote.dto.ConversationPageDto
import com.keystone.shared.data.remote.dto.MessagePageDto
import com.keystone.shared.data.remote.dto.MetaDto
import com.keystone.shared.data.remote.dto.ProfileDto
import com.keystone.shared.data.remote.dto.RenameDto
import com.keystone.shared.data.remote.sse.SseEvent
import com.keystone.shared.data.remote.sse.SseEventParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
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

    internal suspend fun startSession(): ProfileDto = client.post("$baseUrl/v1/auth/session").body()

    internal suspend fun deleteAccount() {
        client.delete("$baseUrl/v1/me")
    }

    internal suspend fun listConversations(limit: Int, cursor: String?): ConversationPageDto =
        client.get("$baseUrl/v1/conversations") {
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }.body()

    internal suspend fun listMessages(conversationId: String, limit: Int): MessagePageDto =
        client.get("$baseUrl/v1/conversations/$conversationId/messages") {
            parameter("limit", limit)
        }.body()

    internal suspend fun renameConversation(conversationId: String, title: String): ConversationDto =
        client.patch("$baseUrl/v1/conversations/$conversationId") {
            contentType(ContentType.Application.Json)
            setBody(RenameDto(title))
        }.body()

    internal suspend fun deleteConversation(conversationId: String) {
        client.delete("$baseUrl/v1/conversations/$conversationId")
    }

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
