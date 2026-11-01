package io.github.shakeabdulrahman.keystone.llm.http


/**
 * The HTTP layer providers use. Implement it to plug in your own HTTP stack, or a fake in
 * tests. `llm-ktor` provides the default.
 *
 * Contract:
 * - the response is only valid inside [handler]; the transport releases it afterwards,
 *   including when the coroutine is cancelled;
 * - [handler] runs in the calling coroutine (no `withContext`): providers emit flow values
 *   from inside it;
 * - I/O failures are thrown as [io.github.shakeabdulrahman.keystone.llm.LlmException] with kind
 *   `Network` or `Timeout`; HTTP error statuses are *returned*, not thrown.
 */
public interface HttpTransport : AutoCloseable {
    public suspend fun <T> execute(request: HttpRequest, handler: suspend (HttpResponse) -> T): T

    override fun close() {}
}

public class HttpRequest(
    public val method: String,
    public val url: String,
    /** Header names are case-insensitive. Never log values: they hold credentials. */
    public val headers: Map<String, String>,
    public val body: ByteArray? = null,
    public val contentType: String? = null,
) {
    override fun toString(): String = "HttpRequest($method ${url.substringBefore('?')})"
}

public interface HttpResponse {
    public val status: Int

    /** Case-insensitive header lookup. */
    public fun header(name: String): String?

    /** The next chunk of the body as it arrives, or `null` at the end. Chunks can be any size. */
    public suspend fun readChunk(): ByteArray?
}

/** Reads the rest of the body, up to [limit] bytes (the rest is dropped). For error bodies. */
public suspend fun HttpResponse.readText(limit: Int = 64 * 1024): String {
    val out = ArrayList<ByteArray>()
    var size = 0
    while (size < limit) {
        val chunk = readChunk() ?: break
        val take = minOf(chunk.size, limit - size)
        out += if (take == chunk.size) chunk else chunk.copyOf(take)
        size += take
    }
    val all = ByteArray(size)
    var pos = 0
    for (c in out) {
        c.copyInto(all, pos)
        pos += c.size
    }
    return all.decodeToString()
}
