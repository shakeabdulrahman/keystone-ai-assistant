package io.github.shakeabdulrahman.keystone.llm.http

import io.github.shakeabdulrahman.keystone.llm.InternalKeystoneLlmApi
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.sse.SseDecoder
import io.github.shakeabdulrahman.keystone.llm.sse.SseEvent

/**
 * Reads the body as Server-Sent Events, calling [onEvent] for each as soon as it is complete.
 * Returns normally at end of body, or when [onEvent] returns `false` (end marker seen).
 */
@InternalKeystoneLlmApi
public suspend fun HttpResponse.forEachSseEvent(onEvent: suspend (SseEvent) -> Boolean) {
    val decoder = SseDecoder()
    while (true) {
        val chunk = readChunk() ?: break
        for (event in decoder.feed(chunk)) if (!onEvent(event)) return
    }
    for (event in decoder.finish()) if (!onEvent(event)) return
}

/**
 * The [LlmException] for an error response: reads (a bounded part of) the body, lets [parse]
 * extract the provider's error code and message, and classifies it.
 */
@InternalKeystoneLlmApi
public suspend fun HttpResponse.toLlmException(
    provider: String,
    requestIdHeader: String?,
    parse: (body: String) -> Pair<String?, String?>,
): LlmException {
    val body = try {
        readText(limit = 16 * 1024)
    } catch (e: LlmException) {
        "" // the error body itself failed to arrive; the status is still meaningful
    }
    val (code, message) = runCatching { parse(body) }.getOrDefault(null to null)
    return HttpErrors.exception(
        provider = provider,
        status = status,
        providerCode = code,
        providerMessage = message,
        requestId = requestIdHeader?.let { header(it) },
        retryAfter = HttpErrors.retryAfter(header("retry-after"), header("retry-after-ms"), header("date")),
    )
}

/** The headers for [auth]; [apiKeyHeader] says how this provider sends a static key. */
@InternalKeystoneLlmApi
public suspend fun io.github.shakeabdulrahman.keystone.llm.Auth.toHeaders(
    apiKeyHeader: (key: String) -> Pair<String, String>,
): Map<String, String> = when (this) {
    is io.github.shakeabdulrahman.keystone.llm.Auth.ApiKey -> mapOf(apiKeyHeader(value))
    is io.github.shakeabdulrahman.keystone.llm.Auth.Headers -> provide()
    io.github.shakeabdulrahman.keystone.llm.Auth.None -> emptyMap()
}
