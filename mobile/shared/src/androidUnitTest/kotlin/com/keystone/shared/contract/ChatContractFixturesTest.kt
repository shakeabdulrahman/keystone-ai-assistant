package com.keystone.shared.contract

import com.keystone.shared.core.AppError
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import com.keystone.shared.data.repository.ChatRepositoryImpl
import com.keystone.shared.domain.model.ChatEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Plays the shared fixtures in /contracts/fixtures through the real client stack.
 * The backend tests validate the same files, so the two sides can't drift apart silently.
 */
class ChatContractFixturesTest {

    private fun fixture(name: String): String {
        val dir = System.getProperty("keystone.contractsDir")
            ?: error("keystone.contractsDir not set; run through Gradle")
        return File(dir, name).readText()
    }

    private suspend fun play(name: String): List<ChatEvent> {
        val body = fixture(name)
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val repo = ChatRepositoryImpl(KeystoneApi(createHttpClient(engine), "http://test"))
        return repo.sendMessage(null, "client-1", "Hi").toList()
    }

    @Test
    fun basicFixture() = runTest {
        val events = play("chat_stream_basic.sse")
        assertIs<ChatEvent.Started>(events.first())
        assertEquals(
            "Hi! I can help with leave and HR questions.",
            events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text },
        )
        assertIs<ChatEvent.Completed>(events.last())
    }

    @Test
    fun errorFixture() = runTest {
        val events = play("chat_stream_error.sse")
        assertEquals(ChatEvent.Failed(AppError.Assistant("ai_unavailable", retryable = true)), events.last())
    }

    @Test
    fun forwardCompatibleFixtureIgnoresUnknownEventsAndFields() = runTest {
        val events = play("chat_stream_forward_compat.sse")
        assertEquals(
            listOf(ChatEvent.Started::class, ChatEvent.Delta::class, ChatEvent.Completed::class),
            events.map { it::class },
        )
    }
}
