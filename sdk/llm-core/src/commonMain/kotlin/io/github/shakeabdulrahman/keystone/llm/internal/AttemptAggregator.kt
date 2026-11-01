package io.github.shakeabdulrahman.keystone.llm.internal

import io.github.shakeabdulrahman.keystone.llm.Attempt
import io.github.shakeabdulrahman.keystone.llm.ChatRequest
import io.github.shakeabdulrahman.keystone.llm.ChatResponse
import io.github.shakeabdulrahman.keystone.llm.CostReport
import io.github.shakeabdulrahman.keystone.llm.FinishReason
import io.github.shakeabdulrahman.keystone.llm.Message
import io.github.shakeabdulrahman.keystone.llm.ModelRef
import io.github.shakeabdulrahman.keystone.llm.PriceTable
import io.github.shakeabdulrahman.keystone.llm.ProviderEvent
import io.github.shakeabdulrahman.keystone.llm.ResponseMetadata
import io.github.shakeabdulrahman.keystone.llm.StreamEvent
import io.github.shakeabdulrahman.keystone.llm.StructuredEnforcement
import io.github.shakeabdulrahman.keystone.llm.TokenEstimator
import io.github.shakeabdulrahman.keystone.llm.Usage
import io.github.shakeabdulrahman.keystone.llm.UsageSource

/** Turns one attempt's provider events into stream events and, at the end, a [ChatResponse]. */
internal class AttemptAggregator(
    private val target: ModelRef,
    private val request: ChatRequest,
    private val pricing: PriceTable?,
    private val estimator: TokenEstimator?,
) {
    private val text = StringBuilder()
    private val tools = ToolCallAssembler(request.tools)
    private var usage = Usage.Unknown
    private var finish: FinishReason? = null
    var requestId: String? = null
        private set
    private var responseId: String? = null
    private var model: String? = null

    val finished: Boolean get() = finish != null

    /** The user-visible events for [event] (empty for metadata, usage and finish). */
    fun onEvent(event: ProviderEvent): List<StreamEvent> = when (event) {
        is ProviderEvent.Metadata -> {
            event.requestId?.let { requestId = it }
            event.responseId?.let { responseId = it }
            event.model?.let { model = it }
            emptyList()
        }
        is ProviderEvent.TextDelta -> if (event.text.isEmpty()) {
            emptyList()
        } else {
            text.append(event.text)
            listOf(StreamEvent.TextDelta(event.text))
        }
        is ProviderEvent.ToolCallChunk -> tools.accept(event)
        is ProviderEvent.UsageUpdate -> {
            usage = event.usage
            emptyList()
        }
        is ProviderEvent.Finish -> {
            finish = event.reason
            emptyList()
        }
    }

    /** The completed tool-call events and the response. Call only when [finished]. */
    fun complete(attempts: List<Attempt>, enforcement: StructuredEnforcement?): Pair<List<StreamEvent>, ChatResponse> {
        val calls = tools.complete()
        val reason = finish ?: FinishReason.Unknown
        // Some providers (Gemini) say STOP when they return function calls.
        val normalized = if (calls.isNotEmpty() && (reason == FinishReason.Stop || reason == FinishReason.Unknown)) {
            FinishReason.ToolCalls
        } else {
            reason
        }
        val finalUsage = estimateIfUnknown(usage, calls.joinToString("") { it.rawArguments })
        val response = ChatResponse(
            message = Message.Assistant(text.toString(), calls),
            finishReason = normalized,
            usage = finalUsage,
            cost = pricing?.let { CostReport.calculate(finalUsage, it.priceOf(target), it.version) },
            metadata = ResponseMetadata(
                target = target,
                model = model,
                requestId = requestId,
                responseId = responseId,
                attempts = attempts,
                structuredEnforcement = enforcement,
            ),
        )
        val events = calls.mapIndexed { i, call -> StreamEvent.ToolCallCompleted(indexOf(i), call) }
        return events to response
    }

    private fun indexOf(position: Int): Int = tools.indices()[position]

    private fun estimateIfUnknown(reported: Usage, toolArguments: String): Usage {
        if (estimator == null) return reported
        if (reported.inputTokens != null && reported.outputTokens != null) return reported
        // Fill only what's missing; a mix of reported and estimated counts is labelled estimated.
        val input = reported.inputTokens ?: estimator.estimate(promptText())
        val output = reported.outputTokens ?: estimator.estimate(text.toString() + toolArguments)
        return Usage(input, output, reported.cachedInputTokens, UsageSource.Estimated)
    }

    private fun promptText(): String = buildString {
        for (m in request.messages) {
            when (m) {
                is Message.System -> append(m.text)
                is Message.User -> append(m.text)
                is Message.Assistant -> {
                    append(m.text)
                    m.toolCalls.forEach { append(it.name).append(it.rawArguments) }
                }
                is Message.ToolResult -> append(m.content)
            }
            append('\n')
        }
        request.tools.forEach { append(it.name).append(it.description).append(it.parameters.toString()) }
    }
}
