package io.github.shakeabdulrahman.keystone.llm.openai

import io.github.shakeabdulrahman.keystone.llm.http.HttpRequest
import io.github.shakeabdulrahman.keystone.llm.http.HttpResponse
import io.github.shakeabdulrahman.keystone.llm.http.HttpTransport

/**
 * Serves canned responses, delivering the body in chunks of [chunkSize] bytes so tests
 * exercise arbitrary network chunking. Records requests and whether the body was released.
 */
class FakeHttpTransport(
    private val chunkSize: Int = 7,
    private val respond: (HttpRequest) -> Canned,
) : HttpTransport {
    class Canned(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    val requests = mutableListOf<HttpRequest>()
    var openResponses = 0
        private set
    var bytesRead = 0
        private set

    override suspend fun <T> execute(request: HttpRequest, handler: suspend (HttpResponse) -> T): T {
        requests += request
        val canned = respond(request)
        val bytes = canned.body.encodeToByteArray()
        var position = 0
        openResponses++
        try {
            return handler(object : HttpResponse {
                override val status = canned.status
                override fun header(name: String) = canned.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                override suspend fun readChunk(): ByteArray? {
                    if (position >= bytes.size) return null
                    val end = minOf(bytes.size, position + chunkSize)
                    return bytes.copyOfRange(position, end).also {
                        bytesRead += it.size
                        position = end
                    }
                }
            })
        } finally {
            openResponses--
        }
    }
}

fun sse(vararg payloads: String): String = payloads.joinToString("") { "data: $it\n\n" }
