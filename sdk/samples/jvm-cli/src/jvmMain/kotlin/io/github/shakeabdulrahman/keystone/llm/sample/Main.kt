package io.github.shakeabdulrahman.keystone.llm.sample

import io.github.shakeabdulrahman.keystone.llm.Auth
import io.github.shakeabdulrahman.keystone.llm.ChatRequest
import io.github.shakeabdulrahman.keystone.llm.LlmClient
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.LlmListener
import io.github.shakeabdulrahman.keystone.llm.Attempt
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ModelRef
import io.github.shakeabdulrahman.keystone.llm.Route
import io.github.shakeabdulrahman.keystone.llm.StreamEvent
import io.github.shakeabdulrahman.keystone.llm.gemini.GeminiProvider
import io.github.shakeabdulrahman.keystone.llm.openai.OpenAiCompatibleProvider
import kotlinx.coroutines.runBlocking

/**
 * Streams one answer, with Groq as primary and Gemini as fallback (Keystone's setup).
 *
 * Keys come from environment variables only; nothing is hardcoded or printed:
 *   GROQ_API_KEY, GROQ_MODEL, GEMINI_API_KEY, GEMINI_MODEL
 * Model names are deliberately not defaulted: vendors retire them, so pick current ones.
 *
 *   ./gradlew :samples:jvm-cli:jvmRun --args="What is Kotlin Multiplatform?"
 */
fun main(args: Array<String>) = runBlocking {
    val groqKey = System.getenv("GROQ_API_KEY")
    val geminiKey = System.getenv("GEMINI_API_KEY")
    val targets = buildList {
        if (groqKey != null) add(ModelRef("groq", requireEnv("GROQ_MODEL")))
        if (geminiKey != null) add(ModelRef("gemini", requireEnv("GEMINI_MODEL")))
    }
    if (targets.isEmpty()) {
        System.err.println("Set GROQ_API_KEY + GROQ_MODEL and/or GEMINI_API_KEY + GEMINI_MODEL.")
        return@runBlocking
    }

    val llm = LlmClient {
        groqKey?.let { provider(OpenAiCompatibleProvider("groq", "https://api.groq.com/openai/v1", Auth.ApiKey(it))) }
        geminiKey?.let { provider(GeminiProvider(Auth.ApiKey(it))) }
        defaultRoute = Route(targets)
        listener = object : LlmListener {
            override fun onAttemptFailed(attempt: Attempt, error: LlmException, next: LlmListener.NextStep) {
                System.err.println("[${attempt.target} attempt ${attempt.number}] ${error.kind} -> $next")
            }
        }
    }

    val question = args.joinToString(" ").ifBlank { "In two sentences, what is Kotlin Multiplatform?" }
    llm.use { client ->
        try {
            client.stream(ChatRequest(listOf(Message.user(question)))).collect { event ->
                when (event) {
                    is StreamEvent.Started -> System.err.println("[answering: ${event.target}]")
                    is StreamEvent.TextDelta -> print(event.text)
                    is StreamEvent.Completed -> println("\n[finish=${event.response.finishReason} usage=${event.response.usage}]")
                    else -> Unit
                }
            }
        } catch (e: LlmException) {
            System.err.println("\nFailed: ${e.kind} (${e.message}); attempts=${e.attempts.size}")
        }
    }
}

private fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("Set $name")
