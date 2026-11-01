package io.github.shakeabdulrahman.keystone.llm

import io.github.shakeabdulrahman.keystone.llm.testing.FakeProvider
import io.github.shakeabdulrahman.keystone.llm.testing.answer
import io.github.shakeabdulrahman.keystone.llm.testing.failure
import io.github.shakeabdulrahman.keystone.llm.testing.testClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class StreamingTest {
    private val a = ModelRef("a", "model-a")
    private val b = ModelRef("b", "model-b")
    private fun request(vararg targets: ModelRef) = ChatRequest(listOf(Message.user("hi")), route = Route(targets.toList()))

    @Test
    fun eventsAreNormalizedAndEndWithCompleted() = runTest {
        val (client, _) = testClient(FakeProvider("a") { _, _ -> answer("Hel", "lo") })
        val events = client.stream(request(a)).toList()
        assertEquals(StreamEvent.Started(a, "req-1"), events[0])
        assertEquals(listOf(StreamEvent.TextDelta("Hel"), StreamEvent.TextDelta("lo")), events.subList(1, 3))
        val done = assertIs<StreamEvent.Completed>(events.last())
        assertEquals("Hello", done.response.text)
        assertEquals(Usage(10, 5), done.response.usage)
        assertEquals(4, events.size)
    }

    @Test
    fun failureBeforeAnyOutputFailsOverInvisibly() = runTest {
        val first = FakeProvider("a") { _, _ ->
            emit(ProviderEvent.Metadata(requestId = "x")) // metadata is not visible output
            throw failure(LlmErrorKind.ServerError)
        }
        val second = FakeProvider("b") { _, _ -> answer("from b") }
        val (client, _) = testClient(first, second) { retry = RetryPolicy.None }
        val events = client.stream(request(a, b)).toList()
        assertEquals(StreamEvent.Started(b, "req-1"), events.first())
        assertTrue(events.none { it is StreamEvent.Restarted })
    }

    @Test
    fun noRetryOrFailoverAfterVisibleOutput() = runTest {
        val first = FakeProvider("a") { _, _ ->
            emit(ProviderEvent.TextDelta("partial "))
            throw failure(LlmErrorKind.Network)
        }
        val second = FakeProvider("b") { _, _ -> answer("never") }
        val (client, sleeps) = testClient(first, second)
        val seen = mutableListOf<StreamEvent>()
        val error = assertFailsWith<LlmException> { client.stream(request(a, b)).collect { seen += it } }
        assertEquals(LlmErrorKind.Network, error.kind)
        assertEquals(1, first.calls)
        assertEquals(0, second.calls)
        assertTrue(sleeps.isEmpty())
        assertEquals(listOf(StreamEvent.Started(a, null), StreamEvent.TextDelta("partial ")), seen)
    }

    @Test
    fun optInRestartEmitsRestartedBeforeTheNewAnswer() = runTest {
        val first = FakeProvider("a") { _, _ ->
            emit(ProviderEvent.TextDelta("partial "))
            throw failure(LlmErrorKind.ServerError)
        }
        val second = FakeProvider("b") { _, _ -> answer("full") }
        val (client, _) = testClient(first, second) {
            streamRestart = StreamRestartPolicy.EmitRestarted
            retry = RetryPolicy.None
        }
        val events = client.stream(request(a, b)).toList()
        assertEquals(StreamEvent.Started(a, null), events[0])
        assertEquals(StreamEvent.TextDelta("partial "), events[1])
        val restarted = assertIs<StreamEvent.Restarted>(events[2])
        assertEquals(b, restarted.next)
        assertEquals(LlmErrorKind.ServerError, restarted.failed.error)
        assertEquals(StreamEvent.Started(b, "req-1"), events[3])
        assertEquals("full", assertIs<StreamEvent.Completed>(events.last()).response.text)
    }

    @Test
    fun chatMayRetryAfterPartialOutputBecauseNothingWasShown() = runTest {
        val provider = FakeProvider("a") { call, _ ->
            if (call == 1) {
                emit(ProviderEvent.TextDelta("partial"))
                throw failure(LlmErrorKind.Network)
            }
            answer("complete")
        }
        val (client, _) = testClient(provider)
        assertEquals("complete", client.chat(request(a)).text)
    }

    @Test
    fun cancellingTheCollectorCancelsTheProviderAndIsNotRetried() = runTest {
        val providerCancelled = CompletableDeferred<Boolean>()
        val provider = FakeProvider("a") { _, _ ->
            emit(ProviderEvent.TextDelta("first"))
            try {
                awaitCancellation()
            } finally {
                providerCancelled.complete(true)
            }
        }
        val (client, _) = testClient(provider)
        val job = launch { client.stream(request(a)).collect { } }
        delay(50.milliseconds)
        job.cancel()
        job.join()
        assertTrue(providerCancelled.await())
        assertEquals(1, provider.calls)
    }

    @Test
    fun takeStopsTheStreamCleanly() = runTest {
        val (client, _) = testClient(FakeProvider("a") { _, _ -> answer("a", "b", "c") })
        val events = client.stream(request(a)).take(2).toList()
        assertEquals(2, events.size)
    }

    @Test
    fun exceptionsFromTheCollectorAreRethrownNotRetried() = runTest {
        val provider = FakeProvider("a") { _, _ -> answer("x") }
        val (client, _) = testClient(provider)
        val thrown = assertFailsWith<IllegalStateException> {
            client.stream(request(a)).onEach { if (it is StreamEvent.TextDelta) error("collector bug") }.collect()
        }
        assertEquals("collector bug", thrown.message)
        assertEquals(1, provider.calls)
    }

    @Test
    fun firstEventTimeoutIsARetryableTimeout() = runTest {
        val provider = FakeProvider("a") { call, _ ->
            if (call == 1) delay(10_000.milliseconds)
            answer("ok")
        }
        val (client, _) = testClient(provider) { timeouts = Timeouts(firstEvent = 100.milliseconds, idle = null) }
        val response = client.chat(request(a))
        assertEquals("ok", response.text)
        assertEquals(LlmErrorKind.Timeout, response.metadata.attempts.first().error)
    }

    @Test
    fun idleTimeoutAfterOutputEndsTheStream() = runTest {
        val provider = FakeProvider("a") { _, _ ->
            emit(ProviderEvent.TextDelta("x"))
            delay(10_000.milliseconds)
        }
        val (client, _) = testClient(provider) { timeouts = Timeouts(firstEvent = null, idle = 100.milliseconds) }
        val e = assertFailsWith<LlmException> { client.stream(request(a)).toList() }
        assertEquals(LlmErrorKind.Timeout, e.kind)
    }

    @Test
    fun toolCallsAreAssembledAcrossChunksAndValidated() = runTest {
        val weather = Tool(
            "get_weather", "Weather for a city",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("city")) }
            },
        )
        val provider = FakeProvider("a") { _, _ ->
            // Two parallel calls, interleaved, with arguments split mid-token.
            emit(ProviderEvent.ToolCallChunk(0, id = "c0", name = "get_weather"))
            emit(ProviderEvent.ToolCallChunk(1, id = "c1", name = "get_weather", argumentsDelta = "{\"ci"))
            emit(ProviderEvent.ToolCallChunk(0, argumentsDelta = "{\"city\":\"Chen"))
            emit(ProviderEvent.ToolCallChunk(1, argumentsDelta = "ty\": 42}"))
            emit(ProviderEvent.ToolCallChunk(0, argumentsDelta = "nai\"}"))
            emit(ProviderEvent.ToolCallChunk(2, id = "c2", name = "launch_rockets", argumentsDelta = "{}"))
            emit(ProviderEvent.Finish(FinishReason.Stop))
        }
        val (client, _) = testClient(provider)
        val events = client.stream(request(a).copy(tools = listOf(weather))).toList()

        val started = events.filterIsInstance<StreamEvent.ToolCallStarted>()
        assertEquals(listOf("c0", "c1", "c2"), started.map { it.id })
        val deltas0 = events.filterIsInstance<StreamEvent.ToolCallDelta>().filter { it.index == 0 }.joinToString("") { it.argumentsDelta }
        assertEquals("{\"city\":\"Chennai\"}", deltas0)

        val response = assertIs<StreamEvent.Completed>(events.last()).response
        assertEquals(FinishReason.ToolCalls, response.finishReason)
        val (ok, badType, unknown) = response.toolCalls
        assertEquals("Chennai", ok.argumentsOrNull!!["city"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        val mismatch = assertIs<ToolArgumentError.SchemaMismatch>(assertIs<ToolArguments.Invalid>(badType.arguments).error)
        assertEquals("$.city", mismatch.violations.single().path)
        assertEquals(ToolArguments.Invalid(ToolArgumentError.UnknownTool), unknown.arguments)
        // Completed events come after all deltas and before Completed.
        val completedIdx = events.indexOfFirst { it is StreamEvent.ToolCallCompleted }
        assertTrue(events.subList(completedIdx, events.size - 1).all { it is StreamEvent.ToolCallCompleted })
    }

    @Test
    fun malformedToolArgumentsAreReportedNotThrown() = runTest {
        val tool = Tool("t", "d", buildJsonObject { put("type", "object") })
        val provider = FakeProvider("a") { _, _ ->
            emit(ProviderEvent.ToolCallChunk(0, id = "c", name = "t", argumentsDelta = "{\"a\": "))
            emit(ProviderEvent.Finish(FinishReason.Length))
        }
        val (client, _) = testClient(provider)
        val call = client.chat(request(a).copy(tools = listOf(tool))).toolCalls.single()
        assertIs<ToolArgumentError.MalformedJson>(assertIs<ToolArguments.Invalid>(call.arguments).error)
        assertEquals("{\"a\": ", call.rawArguments)
    }

    @Test
    fun streamIsColdAndEachCollectionIsANewRequest() = runTest {
        val provider = FakeProvider("a") { _, _ -> answer("x") }
        val (client, _) = testClient(provider)
        val flow = client.stream(request(a))
        assertEquals(0, provider.calls)
        flow.first()
        flow.first()
        assertEquals(2, provider.calls)
    }
}
