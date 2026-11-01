package io.github.shakeabdulrahman.keystone.llm.openai

import io.github.shakeabdulrahman.keystone.llm.Auth
import io.github.shakeabdulrahman.keystone.llm.ChatRequest
import io.github.shakeabdulrahman.keystone.llm.FinishReason
import io.github.shakeabdulrahman.keystone.llm.LlmClient
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ModelRef
import io.github.shakeabdulrahman.keystone.llm.ResponseFormat
import io.github.shakeabdulrahman.keystone.llm.RetryPolicy
import io.github.shakeabdulrahman.keystone.llm.StreamEvent
import io.github.shakeabdulrahman.keystone.llm.Tool
import io.github.shakeabdulrahman.keystone.llm.ToolArguments
import io.github.shakeabdulrahman.keystone.llm.ToolChoice
import io.github.shakeabdulrahman.keystone.llm.Usage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Fixtures are hand-written from the documented Chat Completions streaming format (and the
 * shapes Keystone's backend handled for Groq); they are not captured from live traffic.
 */
class OpenAiCompatibleProviderTest {
    private val model = ModelRef("groq", "test-model")

    private fun client(transport: FakeHttpTransport, options: OpenAiCompatibleOptions = OpenAiCompatibleOptions()) = LlmClient {
        provider(
            OpenAiCompatibleProvider(
                id = "groq",
                baseUrl = "https://api.example.test/openai/v1/",
                auth = Auth.ApiKey("test-key-not-real"),
                capabilities = { OpenAiCompatibleProvider.OpenAiCapabilities },
                options = options,
                transport = transport,
            ),
        )
        retry = RetryPolicy.None
    }

    private val textStream = sse(
        """{"id":"chatcmpl-1","model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
        """{"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Hello"}}]}""",
        """{"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":", wörld 🚀"}}]}""",
        """{"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        """{"id":"chatcmpl-1","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":4}}}""",
        "[DONE]",
    )

    @Test
    fun textStreamIsMappedForEveryChunkSize() = runTest {
        for (size in listOf(1, 2, 3, 5, 64, 4096)) {
            val transport = FakeHttpTransport(chunkSize = size) {
                FakeHttpTransport.Canned(200, textStream, mapOf("X-Request-Id" to "req_abc"))
            }
            val events = client(transport).stream(ChatRequest.of(model, Message.user("hi"))).toList()
            val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
            assertEquals("Hello, wörld 🚀", text, "chunk size $size")
            val response = assertIs<StreamEvent.Completed>(events.last()).response
            assertEquals(FinishReason.Stop, response.finishReason)
            assertEquals(Usage(12, 5, cachedInputTokens = 4), response.usage)
            assertEquals("req_abc", response.metadata.requestId)
            assertEquals("chatcmpl-1", response.metadata.responseId)
            assertEquals("test-model", response.metadata.model)
            assertEquals(0, transport.openResponses)
        }
    }

    @Test
    fun requestBodyAndHeadersAreMapped() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.Canned(200, textStream) }
        val tool = Tool("get_weather", "Weather", buildJsonObject { put("type", "object") })
        val request = ChatRequest(
            messages = listOf(
                Message.system("Be brief."),
                Message.user("Weather in Chennai?"),
                Message.Assistant(
                    toolCalls = listOf(
                        io.github.shakeabdulrahman.keystone.llm.ToolCall(
                            "call_1", "get_weather",
                            ToolArguments.Valid(buildJsonObject { put("city", "Chennai") }), "{\"city\":\"Chennai\"}",
                        ),
                    ),
                ),
                Message.ToolResult("call_1", "get_weather", "{\"c\":31}"),
            ),
            route = io.github.shakeabdulrahman.keystone.llm.Route(model),
            tools = listOf(tool),
            toolChoice = ToolChoice.Specific("get_weather"),
            responseFormat = ResponseFormat.JsonMode,
            maxOutputTokens = 100,
            temperature = 0.2,
            stopSequences = listOf("END"),
            providerOptions = mapOf("groq" to buildJsonObject { put("seed", 7) }),
        )
        client(transport, OpenAiCompatibleOptions(maxTokensField = "max_completion_tokens")).chat(request)

        val sent = transport.requests.single()
        assertEquals("https://api.example.test/openai/v1/chat/completions", sent.url)
        assertEquals("Bearer test-key-not-real", sent.headers["Authorization"])
        assertEquals("HttpRequest(POST https://api.example.test/openai/v1/chat/completions)", sent.toString())
        val expected = Json.parseToJsonElement(
            """
            {"model":"test-model",
             "messages":[
               {"role":"system","content":"Be brief."},
               {"role":"user","content":"Weather in Chennai?"},
               {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Chennai\"}"}}]},
               {"role":"tool","tool_call_id":"call_1","content":"{\"c\":31}"}],
             "tools":[{"type":"function","function":{"name":"get_weather","description":"Weather","parameters":{"type":"object"}}}],
             "tool_choice":{"type":"function","function":{"name":"get_weather"}},
             "response_format":{"type":"json_object"},
             "max_completion_tokens":100,"temperature":0.2,"stop":["END"],
             "stream":true,"stream_options":{"include_usage":true},
             "seed":7}
            """,
        )
        assertEquals(expected, Json.parseToJsonElement(sent.body!!.decodeToString()))
    }

    @Test
    fun jsonSchemaFormatIsSent() = runTest {
        val transport = FakeHttpTransport { FakeHttpTransport.Canned(200, textStream) }
        val schema = buildJsonObject { put("type", "object") }
        client(transport).chat(ChatRequest.of(model, Message.user("x")).copy(responseFormat = ResponseFormat.JsonSchema("person", schema, strict = true)))
        val body = Json.parseToJsonElement(transport.requests.single().body!!.decodeToString()).jsonObject
        assertEquals(
            Json.parseToJsonElement("""{"type":"json_schema","json_schema":{"name":"person","schema":{"type":"object"},"strict":true}}"""),
            body["response_format"],
        )
    }

    @Test
    fun parallelToolCallsAreAssembledByIndex() = runTest {
        val stream = sse(
            """{"id":"c","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\": \"Che"}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Delhi\"}"}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"nnai\"}"}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"x_groq":{"usage":{"prompt_tokens":30,"completion_tokens":20}}}""",
            "[DONE]",
        )
        val tool = Tool("get_weather", "Weather", Json.parseToJsonElement("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""").jsonObject)
        val transport = FakeHttpTransport(chunkSize = 3) { FakeHttpTransport.Canned(200, stream) }
        val response = client(transport).chat(ChatRequest.of(model, Message.user("x")).copy(tools = listOf(tool)))
        assertEquals(FinishReason.ToolCalls, response.finishReason)
        assertEquals(listOf("call_a", "call_b"), response.toolCalls.map { it.id })
        assertEquals(
            listOf("Chennai", "Delhi"),
            response.toolCalls.map { (it.argumentsOrNull!!["city"] as kotlinx.serialization.json.JsonPrimitive).content },
        )
        assertEquals(Usage(30, 20), response.usage) // Groq puts usage under x_groq
    }

    @Test
    fun httpErrorsAreClassifiedWithRetryAfterAndProviderDetails() = runTest {
        val transport = FakeHttpTransport {
            FakeHttpTransport.Canned(
                429,
                """{"error":{"message":"Rate limit reached for model test-model","type":"tokens","code":"rate_limit_exceeded"}}""",
                mapOf("retry-after" to "2", "x-request-id" to "req_429"),
            )
        }
        val e = assertFailsWith<LlmException> { client(transport).chat(ChatRequest.of(model, Message.user("x"))) }
        assertEquals(LlmErrorKind.RateLimited, e.kind)
        assertEquals(429, e.httpStatus)
        assertEquals(2.seconds, e.retryAfter)
        assertEquals("rate_limit_exceeded", e.providerCode)
        assertEquals("req_429", e.requestId)
        assertTrue("test-key" !in e.message!!)
    }

    @Test
    fun quotaAndAuthErrorsAreNotTransient() = runTest {
        for ((status, body, kind) in listOf(
            Triple(429, """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""", LlmErrorKind.QuotaExceeded),
            Triple(401, """{"error":{"message":"Invalid API Key","type":"invalid_request_error","code":"invalid_api_key"}}""", LlmErrorKind.Authentication),
            Triple(404, "not json at all", LlmErrorKind.NotFound),
        )) {
            val transport = FakeHttpTransport { FakeHttpTransport.Canned(status, body) }
            val e = assertFailsWith<LlmException> { client(transport).chat(ChatRequest.of(model, Message.user("x"))) }
            assertEquals(kind, e.kind)
            assertEquals(false, e.isTransient)
        }
    }

    @Test
    fun midStreamErrorAndMalformedChunksAreTypedErrors() = runTest {
        val midStream = sse(
            """{"id":"c","choices":[{"index":0,"delta":{"content":"Hi"}}]}""",
            """{"error":{"message":"overloaded","type":"server_error"}}""",
        )
        val e1 = assertFailsWith<LlmException> {
            client(FakeHttpTransport { FakeHttpTransport.Canned(200, midStream) }).stream(ChatRequest.of(model, Message.user("x"))).toList()
        }
        assertEquals(LlmErrorKind.ServerError, e1.kind)

        val malformed = sse("{not json")
        val e2 = assertFailsWith<LlmException> {
            client(FakeHttpTransport { FakeHttpTransport.Canned(200, malformed) }).chat(ChatRequest.of(model, Message.user("x")))
        }
        assertEquals(LlmErrorKind.MalformedResponse, e2.kind)
    }

    @Test
    fun streamCutOffBeforeFinishIsANetworkError() = runTest {
        val cutOff = sse("""{"id":"c","choices":[{"index":0,"delta":{"content":"Hel"}}]}""")
        val e = assertFailsWith<LlmException> {
            client(FakeHttpTransport { FakeHttpTransport.Canned(200, cutOff) }).chat(ChatRequest.of(model, Message.user("x")))
        }
        assertEquals(LlmErrorKind.Network, e.kind)
    }

    @Test
    fun readingStopsAtDoneAndTheResponseIsReleased() = runTest {
        val transport = FakeHttpTransport(chunkSize = 4096) {
            FakeHttpTransport.Canned(200, textStream + "data: {\"garbage after done\"\n\n")
        }
        client(transport).chat(ChatRequest.of(model, Message.user("x"))) // would fail if it parsed past [DONE]
        assertEquals(0, transport.openResponses)
    }

    @Test
    fun headerAuthIsCalledPerRequestAndNoKeyMeansNoHeader() = runTest {
        var calls = 0
        val transport = FakeHttpTransport { FakeHttpTransport.Canned(200, textStream) }
        val llm = LlmClient {
            provider(
                OpenAiCompatibleProvider(
                    "proxy", "https://proxy.example.test/v1",
                    Auth.Headers { calls++; mapOf("Authorization" to "Bearer short-lived-$calls") },
                    transport = transport,
                ),
            )
            provider(OpenAiCompatibleProvider("local", "http://localhost:11434/v1", Auth.None, transport = transport))
        }
        llm.chat(ChatRequest.of(ModelRef("proxy", "m"), Message.user("x")))
        llm.chat(ChatRequest.of(ModelRef("proxy", "m"), Message.user("x")))
        assertEquals("Bearer short-lived-2", transport.requests[1].headers["Authorization"])
        llm.chat(ChatRequest.of(ModelRef("local", "m"), Message.user("x")))
        assertEquals(null, transport.requests[2].headers["Authorization"])
    }

    @Test
    fun apiKeyIsNeverPrinted() {
        assertEquals("ApiKey(***)", Auth.ApiKey("sk-secret").toString())
        assertTrue("sk-secret" !in OpenAiCompatibleProvider("x", "https://a.test", Auth.ApiKey("sk-secret"), transport = FakeHttpTransport { error("unused") }).toString())
    }
}
