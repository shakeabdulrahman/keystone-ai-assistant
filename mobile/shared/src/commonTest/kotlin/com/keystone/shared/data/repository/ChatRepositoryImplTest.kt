package com.keystone.shared.data.repository

import com.keystone.shared.core.AppError
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.TokenUsage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRepositoryImplTest {

    private val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")

    private fun repository(handler: MockRequestHandler) =
        ChatRepositoryImpl(KeystoneApi(createHttpClient(MockEngine(handler)), "http://test"))

    private suspend fun send(repo: ChatRepositoryImpl, conversationId: String? = null) =
        repo.sendMessage(conversationId, "client-1", "Hello").toList()

    @Test
    fun streamsStartedDeltasAndCompleted() = runTest {
        val repo = repository {
            respond(
                """
                : keep-alive

                event: message.started
                data: {"seq":1,"conversation_id":"c1","message_id":"m1","user_message_id":"u1"}

                event: text.delta
                data: {"seq":2,"text":"Hi "}

                event: text.delta
                data: {"seq":3,"text":"there"}

                event: message.completed
                data: {"seq":4,"message_id":"m1","finish_reason":"stop","usage":{"input_tokens":5,"output_tokens":2}}

                """.trimIndent() + "\n",
                HttpStatusCode.OK,
                sseHeaders,
            )
        }

        assertEquals(
            listOf(
                ChatEvent.Started("c1", "m1", "u1"),
                ChatEvent.Delta("Hi "),
                ChatEvent.Delta("there"),
                ChatEvent.Completed("m1", truncated = false, usage = TokenUsage(5, 2)),
            ),
            send(repo),
        )
    }

    @Test
    fun sendsSnakeCaseRequestBody() = runTest {
        var body = ""
        val repo = repository { request ->
            body = request.body.toByteArray().decodeToString()
            assertEquals("http://test/v1/chat", request.url.toString())
            respond("", HttpStatusCode.OK, sseHeaders)
        }

        send(repo, conversationId = "c9")

        assertEquals(
            """{"conversation_id":"c9","client_message_id":"client-1","content":"Hello"}""",
            body,
        )
    }

    @Test
    fun errorEventBecomesAssistantFailure() = runTest {
        val repo = repository {
            respond(
                "event: error\ndata: {\"seq\":1,\"code\":\"ai_timeout\",\"message\":\"slow\",\"retryable\":true}\n\n",
                HttpStatusCode.OK,
                sseHeaders,
            )
        }

        assertEquals(listOf(ChatEvent.Failed(AppError.Assistant("ai_timeout", true))), send(repo))
    }

    @Test
    fun httpErrorBeforeStreamBecomesHttpFailure() = runTest {
        val repo = repository {
            respond(
                """{"code":"not_found","status":404}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }

        val events = send(repo)
        assertEquals(1, events.size)
        val error = (events.single() as ChatEvent.Failed).error
        assertTrue(error is AppError.Http && error.status == 404)
    }

    @Test
    fun networkFailureBecomesNetworkError() = runTest {
        val repo = repository { throw IOException("connection reset") }

        assertEquals(listOf(ChatEvent.Failed(AppError.Network)), send(repo))
    }

    @Test
    fun streamEndingWithoutFinalEventIsNetworkError() = runTest {
        val repo = repository {
            respond(
                "event: text.delta\ndata: {\"seq\":1,\"text\":\"Half an ans\"}\n\n",
                HttpStatusCode.OK,
                sseHeaders,
            )
        }

        assertEquals(
            listOf(ChatEvent.Delta("Half an ans"), ChatEvent.Failed(AppError.Network)),
            send(repo),
        )
    }

    @Test
    fun malformedKnownEventIsInvalidResponse() = runTest {
        val repo = repository {
            respond("event: text.delta\ndata: {\"seq\":1}\n\n", HttpStatusCode.OK, sseHeaders)
        }

        assertEquals(listOf(ChatEvent.Failed(AppError.InvalidResponse)), send(repo))
    }
}
