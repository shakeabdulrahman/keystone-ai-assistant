package com.keystone.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.keystone.android.MainDispatcherRule
import com.keystone.android.fakes.FakeConversationRepository
import com.keystone.android.ui.chat.ChatMessageUi.Role
import com.keystone.android.ui.chat.ChatMessageUi.Status
import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.ChatEvent
import com.keystone.shared.domain.model.Citation
import com.keystone.shared.domain.model.PendingAction
import com.keystone.shared.domain.model.StoredMessage
import com.keystone.shared.domain.model.TokenUsage
import com.keystone.shared.domain.model.ToolCard
import com.keystone.shared.domain.repository.ActionRepository
import com.keystone.shared.domain.repository.ChatRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
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

    private val history = FakeConversationRepository()

    /** Scripted decisions; records every call so tests can check nothing runs twice. */
    private class FakeActions : ActionRepository {
        val calls = mutableListOf<String>()
        var next: (String, String) -> AppResult<PendingAction> = { _, _ -> AppResult.Failure(AppError.Unknown) }
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun confirm(actionId: String) = record("confirm", actionId)
        override suspend fun cancel(actionId: String) = record("cancel", actionId)
        override suspend fun get(actionId: String) = record("get", actionId)

        private suspend fun record(kind: String, id: String): AppResult<PendingAction> {
            calls += "$kind:$id"
            gate?.await()
            return next(kind, id)
        }
    }

    private val actions = FakeActions()

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        repo: ChatRepository,
        conversationId: String? = null,
    ) = ChatViewModel(
        repo,
        history,
        actions,
        SavedStateHandle(mapOf(ChatViewModel.CONVERSATION_ID_ARG to conversationId)),
        testScheduler.timeSource,
    )

    private fun stored(id: String, role: StoredMessage.Role, text: String, status: StoredMessage.Status) =
        StoredMessage(id, "c7", role, text, status, Instant.fromEpochMilliseconds(id.length.toLong()))

    @Test
    fun `opening a past conversation shows its history and continues it`() = runTest(mainDispatcherRule.dispatcher) {
        history.serverMessages = mapOf(
            "c7" to listOf(
                stored("1", StoredMessage.Role.User, "Old question", StoredMessage.Status.Complete),
                stored("22", StoredMessage.Role.Assistant, "Half an ans", StoredMessage.Status.Interrupted),
            ),
        )
        val repo = FakeChatRepository(reply("c7", "Follow-up answer"))
        val vm = viewModel(repo, conversationId = "c7")
        advanceUntilIdle()

        assertEquals(listOf("Old question", "Half an ans"), vm.uiState.value.messages.map { it.text })
        assertEquals(Status.Stopped, vm.uiState.value.messages[1].status)
        assertFalse(vm.uiState.value.isLoadingHistory)

        vm.onSuggestion("Next question")
        advanceUntilIdle()

        assertEquals("c7", repo.calls.single().conversationId) // continues the same conversation
        assertEquals(4, vm.uiState.value.messages.size)
        assertTrue("refreshMessages:c7" in history.calls) // cache synced after the answer
    }

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

    private val balance = ToolCard.LeaveBalance(2026, listOf(ToolCard.LeaveBalance.Item("Annual", 20.0, 6.0, 14.0)))

    @Test
    fun `tool call shows progress then its card above the answer`() = runTest(mainDispatcherRule.dispatcher) {
        val script = flow<ChatEvent> {
            emit(ChatEvent.Started("c1", "m1", "u1"))
            emit(ChatEvent.ToolStarted("call_1", "getLeaveBalance", "Checking your leave balance"))
            delay(100)
            emit(ChatEvent.ToolResult("call_1", "getLeaveBalance", ok = true, card = balance))
            emit(ChatEvent.Delta("You have 14 days left."))
            emit(ChatEvent.Completed("m1", truncated = false, usage = TokenUsage(1, 1)))
        }
        val vm = viewModel(FakeChatRepository(script))

        vm.onSuggestion("How many leave days do I have left?")
        advanceTimeBy(50)
        runCurrent()
        val running = vm.uiState.value.messages.last().tools.single()
        assertEquals(ToolActivityUi.State.Running, running.state)
        assertEquals("Checking your leave balance", running.label)

        advanceUntilIdle()
        val answer = vm.uiState.value.messages.last()
        assertEquals(
            listOf(ToolActivityUi("call_1", "Checking your leave balance", ToolActivityUi.State.Done, balance)),
            answer.tools,
        )
        assertEquals("You have 14 days left.", answer.text)
        assertEquals(Status.Complete, answer.status)
    }

    @Test
    fun `failed tool is marked failed and the answer still arrives`() = runTest(mainDispatcherRule.dispatcher) {
        val script = flow<ChatEvent> {
            emit(ChatEvent.Started("c1", "m1", "u1"))
            emit(ChatEvent.ToolStarted("call_1", "getTasks", "Checking your tasks"))
            emit(ChatEvent.ToolResult("call_1", "getTasks", ok = false, card = null))
            emit(ChatEvent.Delta("I couldn't load your tasks."))
            emit(ChatEvent.Completed("m1", truncated = false, usage = TokenUsage(1, 1)))
        }
        val vm = viewModel(FakeChatRepository(script))

        vm.onSuggestion("What are my tasks?")
        advanceUntilIdle()

        val answer = vm.uiState.value.messages.last()
        assertEquals(ToolActivityUi.State.Failed, answer.tools.single().state)
        assertEquals("I couldn't load your tasks.", answer.text)
    }

    @Test
    fun `no progress row keeps spinning after stop or failure`() = runTest(mainDispatcherRule.dispatcher) {
        val hanging = flow<ChatEvent> {
            emit(ChatEvent.Started("c1", "m1", "u1"))
            emit(ChatEvent.ToolStarted("call_1", "getTasks", "Checking your tasks"))
            awaitCancellation()
        }
        val broken = flow<ChatEvent> {
            emit(ChatEvent.Started("c1", "m2", "u2"))
            emit(ChatEvent.ToolStarted("call_2", "getTasks", "Checking your tasks"))
            emit(ChatEvent.Failed(AppError.Network))
        }
        val vm = viewModel(FakeChatRepository(hanging, broken))

        vm.onSuggestion("Tasks?")
        advanceTimeBy(100)
        runCurrent()
        vm.onStop()
        advanceUntilIdle()
        assertEquals(ToolActivityUi.State.Failed, vm.uiState.value.messages.last().tools.single().state)

        vm.onSuggestion("Tasks again?")
        advanceUntilIdle()
        val failed = vm.uiState.value.messages.last()
        assertEquals(Status.Failed, failed.status)
        assertEquals(ToolActivityUi.State.Failed, failed.tools.single().state)
    }

    @Test
    fun `history shows the cards saved with past answers`() = runTest(mainDispatcherRule.dispatcher) {
        history.serverMessages = mapOf(
            "c7" to listOf(
                stored("1", StoredMessage.Role.User, "Leave?", StoredMessage.Status.Complete),
                stored("22", StoredMessage.Role.Assistant, "14 days.", StoredMessage.Status.Complete)
                    .copy(cards = listOf(balance)),
            ),
        )
        val vm = viewModel(FakeChatRepository(), conversationId = "c7")
        advanceUntilIdle()

        val answer = vm.uiState.value.messages[1]
        assertEquals(listOf(balance), answer.tools.map { it.card })
        assertTrue(answer.tools.all { it.state == ToolActivityUi.State.Done })
    }

    private val source = Citation(1, "leave-policy", "Leave Policy", 5, "Carry-over and expiry", "You may carry over…")

    @Test
    fun `cited answer shows its sources`() = runTest(mainDispatcherRule.dispatcher) {
        val script = flow<ChatEvent> {
            emit(ChatEvent.Started("c1", "m1", "u1"))
            emit(ChatEvent.ToolStarted("call_1", "searchPolicies", "Searching HR policies"))
            emit(ChatEvent.ToolResult("call_1", "searchPolicies", ok = true, card = null))
            emit(ChatEvent.Delta("Up to 5 days [1]."))
            emit(ChatEvent.Citations(listOf(source)))
            emit(ChatEvent.Completed("m1", truncated = false, usage = TokenUsage(1, 1)))
        }
        val vm = viewModel(FakeChatRepository(script))

        vm.onSuggestion("Can I carry over unused leave?")
        advanceUntilIdle()

        val answer = vm.uiState.value.messages.last()
        assertEquals("Up to 5 days [1].", answer.text)
        assertEquals(listOf(source), answer.citations)
        assertEquals(ToolActivityUi.State.Done, answer.tools.single().state)
        assertEquals(Status.Complete, answer.status)
    }

    @Test
    fun `history shows saved sources`() = runTest(mainDispatcherRule.dispatcher) {
        history.serverMessages = mapOf(
            "c7" to listOf(
                stored("1", StoredMessage.Role.User, "Carry over?", StoredMessage.Status.Complete),
                stored("22", StoredMessage.Role.Assistant, "Up to 5 days [1].", StoredMessage.Status.Complete)
                    .copy(citations = listOf(source)),
            ),
        )
        val vm = viewModel(FakeChatRepository(), conversationId = "c7")
        advanceUntilIdle()

        assertEquals(listOf(source), vm.uiState.value.messages[1].citations)
    }

    private val proposal = PendingAction(
        id = "a1",
        tool = "submitLeaveRequest",
        title = "Request annual leave",
        fields = listOf(PendingAction.Field("Working days", "2")),
        notes = emptyList(),
        confirmLabel = "Submit request",
        expiresAt = Instant.fromEpochMilliseconds(4_102_444_800_000), // year 2100
        status = PendingAction.Status.Pending,
    )

    private fun proposingReply() = flow<ChatEvent> {
        emit(ChatEvent.Started("c1", "m1", "u1"))
        emit(ChatEvent.ToolStarted("call_1", "submitLeaveRequest", "Preparing your leave request"))
        emit(ChatEvent.ToolResult("call_1", "submitLeaveRequest", ok = true, card = null))
        emit(ChatEvent.ConfirmationRequired(proposal))
        emit(ChatEvent.Delta("Please confirm."))
        emit(ChatEvent.Completed("m1", truncated = false, usage = TokenUsage(1, 1)))
    }

    private fun executed() = proposal.copy(
        status = PendingAction.Status.Executed,
        result = PendingAction.Result("LR-1050", "Leave request LR-1050 submitted."),
    )

    @Test
    fun `a proposal shows a confirmation card and changes nothing by itself`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakeChatRepository(proposingReply()))
            vm.onSuggestion("Book leave")
            advanceUntilIdle()

            assertEquals(listOf(ActionUi(proposal)), vm.uiState.value.messages.last().actions)
            assertEquals(emptyList<String>(), actions.calls) // only a tap confirms
        }

    @Test
    fun `confirming shows the result and a double tap sends one request`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakeChatRepository(proposingReply()))
            vm.onSuggestion("Book leave")
            advanceUntilIdle()
            actions.gate = CompletableDeferred()
            actions.next = { _, _ -> AppResult.Success(executed()) }

            vm.onConfirmAction("a1")
            runCurrent()
            assertTrue(vm.uiState.value.messages.last().actions.single().busy)
            vm.onConfirmAction("a1") // second tap while the first is in flight
            actions.gate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("confirm:a1"), actions.calls)
            val card = vm.uiState.value.messages.last().actions.single()
            assertEquals(ActionUi(executed()), card)
            vm.onConfirmAction("a1") // closed: ignored
            vm.onCancelAction("a1")
            advanceUntilIdle()
            assertEquals(listOf("confirm:a1"), actions.calls)
        }

    @Test
    fun `offline confirm keeps the card open with an error and can be retried`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakeChatRepository(proposingReply()))
            vm.onSuggestion("Book leave")
            advanceUntilIdle()
            actions.next = { _, _ -> AppResult.Failure(AppError.Network) }

            vm.onConfirmAction("a1")
            advanceUntilIdle()
            val failed = vm.uiState.value.messages.last().actions.single()
            assertEquals(ActionUi(proposal, busy = false, error = AppError.Network), failed)

            actions.next = { _, _ -> AppResult.Success(executed()) }
            vm.onConfirmAction("a1")
            advanceUntilIdle()
            assertEquals(ActionUi(executed()), vm.uiState.value.messages.last().actions.single())
        }

    @Test
    fun `an action another request is running is checked until it settles`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakeChatRepository(proposingReply()))
            vm.onSuggestion("Book leave")
            advanceUntilIdle()
            var gets = 0
            actions.next = { kind, _ ->
                when (kind) {
                    "confirm" -> AppResult.Success(proposal.copy(status = PendingAction.Status.Executing))
                    else -> AppResult.Success(if (++gets < 2) proposal.copy(status = PendingAction.Status.Executing) else executed())
                }
            }

            vm.onConfirmAction("a1")
            advanceUntilIdle()

            assertEquals(listOf("confirm:a1", "get:a1", "get:a1"), actions.calls)
            assertEquals(ActionUi(executed()), vm.uiState.value.messages.last().actions.single())
        }

    @Test
    fun `cancel closes the card`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeChatRepository(proposingReply()))
        vm.onSuggestion("Book leave")
        advanceUntilIdle()
        actions.next = { _, _ -> AppResult.Success(proposal.copy(status = PendingAction.Status.Cancelled)) }

        vm.onCancelAction("a1")
        advanceUntilIdle()

        assertEquals(listOf("cancel:a1"), actions.calls)
        assertEquals(PendingAction.Status.Cancelled, vm.uiState.value.messages.last().actions.single().action.status)
    }

    @Test
    fun `history shows the saved state of past proposals`() = runTest(mainDispatcherRule.dispatcher) {
        history.serverMessages = mapOf(
            "c7" to listOf(
                stored("1", StoredMessage.Role.User, "Book leave", StoredMessage.Status.Complete),
                stored("22", StoredMessage.Role.Assistant, "Please confirm.", StoredMessage.Status.Complete)
                    .copy(actions = listOf(executed())),
            ),
        )
        val vm = viewModel(FakeChatRepository(), conversationId = "c7")
        advanceUntilIdle()

        assertEquals(listOf(ActionUi(executed())), vm.uiState.value.messages[1].actions)
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
