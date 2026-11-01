package io.github.shakeabdulrahman.keystone.llm

import io.github.shakeabdulrahman.keystone.llm.testing.FakeProvider
import io.github.shakeabdulrahman.keystone.llm.testing.RecordingListener
import io.github.shakeabdulrahman.keystone.llm.testing.answer
import io.github.shakeabdulrahman.keystone.llm.testing.failure
import io.github.shakeabdulrahman.keystone.llm.testing.testClient
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryFailoverTest {
    private val a = ModelRef("a", "model-a")
    private val b = ModelRef("b", "model-b")
    private fun request(vararg targets: ModelRef) = ChatRequest(listOf(Message.user("hi")), route = Route(targets.toList()))

    @Test
    fun transientFailureIsRetriedWithExponentialBackoff() = runTest {
        val provider = FakeProvider("a") { call, _ ->
            if (call < 3) throw failure(LlmErrorKind.ServerError, status = 503) else answer("ok")
        }
        val (client, sleeps) = testClient(provider)
        val response = client.chat(request(a))
        assertEquals("ok", response.text)
        assertEquals(3, provider.calls)
        assertEquals(listOf(500.milliseconds, 1.seconds), sleeps)
        assertEquals(listOf(LlmErrorKind.ServerError, LlmErrorKind.ServerError, null), response.metadata.attempts.map { it.error })
    }

    @Test
    fun attemptLimitIsRespectedAndAllAttemptsAreReported() = runTest {
        val provider = FakeProvider("a") { _, _ -> throw failure(LlmErrorKind.Network) }
        val (client, _) = testClient(provider)
        val error = assertFailsWith<LlmException> { client.chat(request(a)) }
        assertEquals(3, provider.calls)
        assertEquals(LlmErrorKind.Network, error.kind)
        assertEquals(listOf(1, 2, 3), error.attempts.map { it.number })
    }

    @Test
    fun authenticationAndInvalidRequestsAreNotRetried() = runTest {
        for (kind in listOf(LlmErrorKind.Authentication, LlmErrorKind.InvalidRequest, LlmErrorKind.QuotaExceeded)) {
            val provider = FakeProvider("a") { _, _ -> throw failure(kind) }
            val (client, sleeps) = testClient(provider)
            assertFailsWith<LlmException> { client.chat(request(a)) }
            assertEquals(1, provider.calls, "$kind")
            assertTrue(sleeps.isEmpty())
        }
    }

    @Test
    fun retryAfterIsUsedInsteadOfBackoff() = runTest {
        val provider = FakeProvider("a") { call, _ ->
            if (call == 1) throw failure(LlmErrorKind.RateLimited, retryAfter = 7.seconds) else answer("ok")
        }
        val (client, sleeps) = testClient(provider)
        client.chat(request(a))
        assertEquals(listOf(7.seconds), sleeps)
    }

    @Test
    fun retryAfterLongerThanTheCapSkipsRetryAndFailsOver() = runTest {
        val first = FakeProvider("a") { _, _ -> throw failure(LlmErrorKind.RateLimited, retryAfter = 10.seconds * 60) }
        val second = FakeProvider("b") { _, _ -> answer("from b") }
        val listener = RecordingListener()
        val (client, sleeps) = testClient(first, second) { this.listener = listener }
        val response = client.chat(request(a, b))
        assertEquals("from b", response.text)
        assertEquals(1, first.calls)
        assertTrue(sleeps.isEmpty())
        assertEquals(
            listOf(
                "start a/model-a #1",
                "fail a/model-a #1 RateLimited: failover to b/model-b",
                "start b/model-b #1",
                "ok b/model-b #1",
            ),
            listener.log,
        )
    }

    @Test
    fun failoverFollowsRouteOrderAfterRetriesAreExhausted() = runTest {
        val order = mutableListOf<String>()
        val first = FakeProvider("a") { _, _ -> order += "a"; throw failure(LlmErrorKind.ServerError) }
        val second = FakeProvider("b") { _, _ -> order += "b"; answer("ok") }
        val (client, _) = testClient(first, second)
        val response = client.chat(request(a, b))
        assertEquals(listOf("a", "a", "a", "b"), order)
        assertEquals(b, response.metadata.target)
        assertEquals(listOf(a, a, a, b), response.metadata.attempts.map { it.target })
    }

    @Test
    fun invalidRequestDoesNotFailOverByDefault() = runTest {
        val first = FakeProvider("a") { _, _ -> throw failure(LlmErrorKind.InvalidRequest) }
        val second = FakeProvider("b") { _, _ -> answer("never") }
        val (client, _) = testClient(first, second)
        assertFailsWith<LlmException> { client.chat(request(a, b)) }
        assertEquals(0, second.calls)
    }

    @Test
    fun failoverPolicyNeverStopsAtTheFirstTarget() = runTest {
        val first = FakeProvider("a") { _, _ -> throw failure(LlmErrorKind.ServerError) }
        val second = FakeProvider("b") { _, _ -> answer("never") }
        val (client, _) = testClient(first, second) {
            failover = FailoverPolicy.Never
            retry = RetryPolicy.None
        }
        assertFailsWith<LlmException> { client.chat(request(a, b)) }
        assertEquals(0, second.calls)
    }

    @Test
    fun unsupportedCapabilityFailsOverWithoutCallingTheProvider() = runTest {
        val noTools = FakeProvider("a", ProviderCapabilities(Support.Unsupported, Support.Unsupported, StructuredOutputSupport.None)) { _, _ ->
            answer("never")
        }
        val withTools = FakeProvider("b") { _, _ -> answer("ok") }
        val (client, _) = testClient(noTools, withTools)
        val tool = Tool("t", "d", kotlinx.serialization.json.buildJsonObject { })
        val response = client.chat(request(a, b).copy(tools = listOf(tool)))
        assertEquals(0, noTools.calls)
        assertEquals(LlmErrorKind.Unsupported, response.metadata.attempts.first().error)
    }

    @Test
    fun unsupportedOnTheOnlyTargetIsATypedError() = runTest {
        val noSchema = FakeProvider("a", ProviderCapabilities(Support.Native, Support.Native, StructuredOutputSupport.JsonMode)) { _, _ -> answer("{}") }
        val (client, _) = testClient(noSchema)
        val format = ResponseFormat.JsonSchema("r", kotlinx.serialization.json.buildJsonObject { })
        val error = assertFailsWith<LlmException> { client.chat(request(a).copy(responseFormat = format)) }
        assertEquals(LlmErrorKind.Unsupported, error.kind)
        assertEquals(0, noSchema.calls)
    }

    @Test
    fun aStreamThatEndsWithoutFinishIsATransientNetworkFailure() = runTest {
        val provider = FakeProvider("a") { call, _ ->
            emit(ProviderEvent.TextDelta("partial"))
            if (call == 2) emit(ProviderEvent.Finish(FinishReason.Stop))
        }
        val (client, _) = testClient(provider)
        val response = client.chat(request(a))
        assertEquals("partial", response.text) // the first, cut-off answer was discarded
        assertEquals(LlmErrorKind.Network, response.metadata.attempts.first().error)
    }

    @Test
    fun unexpectedProviderExceptionsAreWrappedAndNotRetried() = runTest {
        val provider = FakeProvider("a") { _, _ -> error("bug") }
        val (client, _) = testClient(provider)
        val e = assertFailsWith<LlmException> { client.chat(request(a)) }
        assertEquals(LlmErrorKind.Unknown, e.kind)
        assertEquals(1, provider.calls)
        assertTrue(e.cause is IllegalStateException)
    }

    @Test
    fun backoffIsCappedAndJitterStaysWithinBounds() {
        val policy = RetryPolicy(maxAttempts = 10, initialDelay = 1.seconds, maxDelay = 5.seconds, jitter = 0.2, random = Random(42))
        val e = failure(LlmErrorKind.ServerError)
        for (attempt in 1..9) {
            val base = minOf(5.0, 1.0 * (1 shl (attempt - 1)))
            val d = policy.delayBeforeRetry(attempt, e)!!.inWholeMilliseconds / 1000.0
            assertTrue(d in base * 0.8..base * 1.2, "attempt $attempt: $d")
        }
        assertNull(policy.delayBeforeRetry(10, e))
    }
}
