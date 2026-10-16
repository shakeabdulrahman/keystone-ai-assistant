package com.keystone.shared.contract

import com.keystone.shared.core.AppError
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import com.keystone.shared.data.repository.ChatRepositoryImpl
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.Citation
import com.keystone.shared.domain.model.PendingAction
import com.keystone.shared.domain.model.ToolCard
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

    @Test
    fun toolFixtureProducesTypedCard() = runTest {
        val events = play("chat_stream_tool.sse")
        assertEquals(
            listOf(
                ChatEvent.Started::class, ChatEvent.ToolStarted::class, ChatEvent.ToolResult::class,
                ChatEvent.Delta::class, ChatEvent.Completed::class,
            ),
            events.map { it::class },
        )
        assertEquals(
            ChatEvent.ToolStarted("call_1", "getLeaveBalance", "Checking your leave balance"),
            events[1],
        )
        val result = assertIs<ChatEvent.ToolResult>(events[2])
        assertEquals(
            ToolCard.LeaveBalance(
                year = 2026,
                items = listOf(
                    ToolCard.LeaveBalance.Item("Annual", 20.0, 6.0, 14.0),
                    ToolCard.LeaveBalance.Item("Sick", 10.0, 1.0, 9.0),
                ),
            ),
            result.card,
        )
    }

    @Test
    fun ragFixtureProducesCitations() = runTest {
        val events = play("chat_stream_rag.sse")
        assertEquals(
            listOf(
                ChatEvent.Started::class, ChatEvent.ToolStarted::class, ChatEvent.ToolResult::class,
                ChatEvent.Delta::class, ChatEvent.Delta::class, ChatEvent.Citations::class,
                ChatEvent.Completed::class,
            ),
            events.map { it::class },
        )
        val result = assertIs<ChatEvent.ToolResult>(events[2])
        assertEquals(null, result.card) // policy_search has no card in the app
        val citation = assertIs<ChatEvent.Citations>(events[5]).items.single()
        assertEquals(
            Citation(
                ref = 1,
                documentId = "leave-policy",
                title = "Leave Policy",
                sectionIndex = 5,
                heading = "Carry-over and expiry",
                snippet = "You may carry over up to 5 days of unused annual leave into the next calendar year. " +
                    "Carried-over days must be used by 31 March, after which they expire.",
            ),
            citation,
        )
    }

    @Test
    fun actionFixtureProducesAConfirmationCard() = runTest {
        val events = play("chat_stream_action.sse")
        assertEquals(
            listOf(
                ChatEvent.Started::class, ChatEvent.ToolStarted::class, ChatEvent.ToolResult::class,
                ChatEvent.ConfirmationRequired::class, ChatEvent.Delta::class, ChatEvent.Completed::class,
            ),
            events.map { it::class },
        )
        val action = assertIs<ChatEvent.ConfirmationRequired>(events[3]).action
        assertEquals("0f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a", action.id)
        assertEquals("Submit request", action.confirmLabel)
        assertEquals(PendingAction.Status.Pending, action.status)
        assertEquals(PendingAction.Field("Working days", "2"), action.fields[2])
    }
}
