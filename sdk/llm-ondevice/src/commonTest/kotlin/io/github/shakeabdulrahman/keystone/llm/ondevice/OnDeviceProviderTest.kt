package io.github.shakeabdulrahman.keystone.llm.ondevice

import io.github.shakeabdulrahman.keystone.llm.ChatRequest
import io.github.shakeabdulrahman.keystone.llm.LlmClient
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.LlmProvider
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ModelRef
import io.github.shakeabdulrahman.keystone.llm.ProviderCapabilities
import io.github.shakeabdulrahman.keystone.llm.ProviderEvent
import io.github.shakeabdulrahman.keystone.llm.ProviderRequest
import io.github.shakeabdulrahman.keystone.llm.FinishReason
import io.github.shakeabdulrahman.keystone.llm.Route
import io.github.shakeabdulrahman.keystone.llm.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OnDeviceProviderTest {
    private val local = ModelRef("on-device", "tiny")
    private val cloud = ModelRef("cloud", "big")

    private class FakeEngine(var availability: OnDeviceAvailability = OnDeviceAvailability.Available) : OnDeviceEngine {
        var lastPrompt: OnDevicePrompt? = null
        override suspend fun availability(model: String) = availability
        override fun generate(model: String, prompt: OnDevicePrompt): Flow<OnDeviceOutput> {
            lastPrompt = prompt
            return flowOf(OnDeviceOutput.Text("Hi "), OnDeviceOutput.Text("there"), OnDeviceOutput.Done(outputTokens = 2))
        }
    }

    private val cloudProvider = object : LlmProvider {
        override val id = "cloud"
        override fun capabilities(model: String) = ProviderCapabilities(
            io.github.shakeabdulrahman.keystone.llm.Support.Native,
            io.github.shakeabdulrahman.keystone.llm.Support.Native,
            io.github.shakeabdulrahman.keystone.llm.StructuredOutputSupport.JsonSchema,
        )
        override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
            emit(ProviderEvent.TextDelta("from cloud"))
            emit(ProviderEvent.Finish(FinishReason.Stop))
        }
    }

    @Test
    fun generatesTextAndKeepsUnknownCountsUnknown() = runTest {
        val engine = FakeEngine()
        val llm = LlmClient { provider(OnDeviceProvider(engine)) }
        val response = llm.chat(ChatRequest(listOf(Message.system("Be kind."), Message.user("Hello")), route = Route(local)))
        assertEquals("Hi there", response.text)
        assertEquals(Usage(inputTokens = null, outputTokens = 2), response.usage)
        assertEquals(OnDevicePrompt("Be kind.", listOf(OnDevicePrompt.Turn(true, "Hello")), null, null, emptyList()), engine.lastPrompt)
    }

    @Test
    fun unavailableModelFallsBackToTheCloud() = runTest {
        val engine = FakeEngine(OnDeviceAvailability.NotReady("downloading"))
        val llm = LlmClient {
            provider(OnDeviceProvider(engine))
            provider(cloudProvider)
        }
        val response = llm.chat(ChatRequest(listOf(Message.user("Hello")), route = Route(local, cloud)))
        assertEquals("from cloud", response.text)
        assertEquals(LlmErrorKind.Unsupported, response.metadata.attempts.first().error)
    }

    @Test
    fun toolsAreRejectedBeforeReachingTheEngine() = runTest {
        val engine = FakeEngine()
        val llm = LlmClient { provider(OnDeviceProvider(engine)) }
        val tool = io.github.shakeabdulrahman.keystone.llm.Tool("t", "d", kotlinx.serialization.json.buildJsonObject { })
        val e = assertFailsWith<LlmException> { llm.chat(ChatRequest(listOf(Message.user("x")), route = Route(local), tools = listOf(tool))) }
        assertEquals(LlmErrorKind.Unsupported, e.kind)
        assertEquals(null, engine.lastPrompt)
    }
}
