package com.keystone.android.ui.chat

import com.keystone.android.MainDispatcherRule
import com.keystone.android.ui.chat.ChatMessageUi.Role
import com.keystone.android.ui.chat.ChatMessageUi.Status
import com.keystone.shared.core.AppError
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.TokenUsage
import com.keystone.shared.domain.repository.ChatRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private data class Call(val conversationId: String?, val clientMessageId: String, val content: String)

    /** Hand-written fake: each call replays the next scripted flow and records its arguments. */
    private class FakeChatRepository(vararg scripts: Flow<ChatEvent>) : ChatRepository {
        private val queue = ArrayDeque(scripts.toList())
        val calls = mutableListOf<Call>()
        override fun sendMessage(conversationId: String?, clientMessageId: String, content: String): Flow<ChatEvent> {
            calls += Call(conversationId, clientMessageId, content)
            return queue.removeFirst()
        }
    }

    private fun reply(conversationId: String, vararg words: String, gapMs: Long = 0) = flow<ChatEvent> {
        emit(ChatEvent.Started(conversationId, "m-$conversationId", "u"))
        words.forEach {
            if (gapMs > 0) delay(gapMs)
            emit(ChatEvent.Delta(it))
        }
        emit(ChatEvent.Completed("m-$conversationId", truncated = false, usage = TokenUsage(1, words.size)))
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(repo: ChatRepository) =
        ChatViewModel(repo, testScheduler.timeSource)

    @Test
    fun `send shows question and streamed answer`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeChatRepository(reply("c1", "Hello ", "there")))

        vm.onInputChange("  Hi  ")
        vm.onSend()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("", state.input)
        assertFalse(state.isResponding)
        assertEquals(listOf(Role.User, Role.Assistant), state.messages.map { it.role })
        assertEquals("Hi", state.messages[0].text) // trimmed
        assertEquals("Hello there", state.messages[1].text)
        assertEquals(Status.Complete, state.messages[1].status)
    }

    @Test
    fun `partial text appears while streaming`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeChatRepository(reply("c1", "One ", "two ", "three", gapMs = 100)))

        vm.onSuggestion("Question")
        advanceTimeBy(250)
        runCurrent()

        val streaming = vm.uiState.value.messages.last()
        assertEquals(Status.Streaming, streaming.status)
        assertTrue(vm.uiState.value.isResponding)
        assertTrue(streaming.text.startsWith("One "))
        assertNotEquals("One two three", streaming.text)

        advanceUntilIdle()
        assertEquals("One two three", vm.uiState.value.messages.last().text)
    }

    @Test
    fun `follow-up reuses the conversation id from the server`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeChatRepository(reply("c42", "a"), reply("c42", "b"))
        val vm = viewModel(repo)

        vm.onSuggestion("first")
        advanceUntilIdle()
        vm.onSuggestion("second")
        advanceUntilIdle()

        assertEquals(listOf(null, "c42"), repo.calls.map { it.conversationId })
    }

    @Test
    fun `failure shows error and retry asks the same question again`() = runTest(mainDispatcherRule.dispatcher) {
        val failing = flow<ChatEvent> {
            emit(ChatEvent.Started("c1", "m1", "u1"))
            emit(ChatEvent.Delta("Partial"))
            emit(ChatEvent.Failed(AppError.Assistant("ai_unavailable", retryable = true)))
        }
        val repo = FakeChatRepository(failing, reply("c1", "Recovered"))
        val vm = viewModel(repo)

        vm.onSuggestion("Leave balance?")
        advanceUntilIdle()

        val failed = vm.uiState.value.messages.last()
        assertEquals(Status.Failed, failed.status)
        assertEquals(AppError.Assistant("ai_unavailable", true), failed.error)
        assertFalse(vm.uiState.value.isResponding)

        vm.onRetry(failed.id)
        advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(listOf(Role.User, Role.Assistant), messages.map { it.role }) // no duplicate question
        assertEquals("Recovered", messages.last().text)
        assertEquals(listOf("Leave balance?", "Leave balance?"), repo.calls.map { it.content })
        assertNotEquals(repo.calls[0].clientMessageId, repo.calls[1].clientMessageId)
        assertEquals("c1", repo.calls[1].conversationId)
    }

    @Test
    fun `stop keeps partial text and ends responding`() = runTest(mainDispatcherRule.dispatcher) {
        val endless = flow<ChatEvent> {
            emit(ChatEvent.Started("c1", "m1", "u1"))
            emit(ChatEvent.Delta("Partial answer"))
            awaitCancellation()
        }
        val vm = viewModel(FakeChatRepository(endless))

        vm.onSuggestion("Tell me everything")
        advanceTimeBy(100)
        runCurrent()
        vm.onStop()
        advanceUntilIdle()

        val last = vm.uiState.value.messages.last()
        assertEquals(Status.Stopped, last.status)
        assertEquals("Partial answer", last.text) // buffered text isn't lost on Stop
        assertFalse(vm.uiState.value.isResponding)
    }

    @Test
    fun `blank input is not sent`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeChatRepository()
        val vm = viewModel(repo)

        vm.onInputChange("   ")
        vm.onSend()
        advanceUntilIdle()

        assertTrue(repo.calls.isEmpty())
        assertTrue(vm.uiState.value.messages.isEmpty())
    }

    @Test
    fun `input is capped at the backend limit`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeChatRepository())
        vm.onInputChange("x".repeat(MAX_MESSAGE_CHARS + 50))
        assertEquals(MAX_MESSAGE_CHARS, vm.uiState.value.input.length)
    }
}
