package io.github.shakeabdulrahman.keystone.llm.sse

import io.github.shakeabdulrahman.keystone.llm.InternalKeystoneLlmApi
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException

/** One Server-Sent Event. [data] lines are joined with `\n`. */
@InternalKeystoneLlmApi
public data class SseEvent(
    val event: String = "message",
    val data: String,
    val id: String? = null,
)

/**
 * Incremental `text/event-stream` decoder that works on raw bytes as they arrive.
 *
 * Network chunks have no relation to events, so it handles:
 * - UTF-8 sequences split between chunks (bytes are held until the character is complete);
 * - line endings `\n`, `\r\n` and `\r`, including a `\r\n` split between chunks;
 * - several events in one chunk and one event over many chunks;
 * - comment lines (`:` keep-alives), a leading BOM, `id`, unknown fields (ignored);
 * - a bounded buffer: a line longer than [maxLineBytes] fails as malformed instead of
 *   growing memory without limit.
 *
 * One deviation from the WHATWG spec: at end of stream a final event that lacks its closing
 * blank line is still delivered (the spec discards it). Some LLM servers end that way, and
 * dropping their last event would lose the finish reason. Providers decide whether a stream
 * that ends without their end marker counts as cut off.
 *
 * Not thread-safe; use one per stream.
 */
@InternalKeystoneLlmApi
public class SseDecoder(private val maxLineBytes: Int = 1 shl 20) {
    private val utf8 = Utf8Accumulator()
    private val line = StringBuilder()
    private var lastWasCr = false
    private var atStart = true

    private var eventName: String? = null
    private val data = StringBuilder()
    private var hasData = false
    private var lastId: String? = null

    /** Feeds the next chunk; returns the events it completed (often none). */
    public fun feed(bytes: ByteArray): List<SseEvent> = process(utf8.decode(bytes))

    /** Call at end of stream. Returns any final event (see class docs). */
    public fun finish(): List<SseEvent> {
        val out = process(utf8.flush()).toMutableList()
        if (line.isNotEmpty()) {
            processLine(line.toString())
            line.clear()
        }
        dispatch()?.let { out += it }
        return out
    }

    private fun process(text: String): List<SseEvent> {
        if (text.isEmpty()) return emptyList()
        var out: MutableList<SseEvent>? = null
        var start = 0
        var i = 0
        if (atStart) {
            atStart = false
            if (text[0] == '\uFEFF') {
                start = 1
                i = 1
            }
        }
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' && lastWasCr) {
                // Second half of a \r\n that was split: the line already ended.
                lastWasCr = false
                start = i + 1
            } else if (c == '\n' || c == '\r') {
                line.append(text, start, i)
                lastWasCr = c == '\r'
                val event = endLine()
                if (event != null) (out ?: mutableListOf<SseEvent>().also { out = it }) += event
                start = i + 1
            } else {
                lastWasCr = false
            }
            i++
        }
        if (start < text.length) {
            line.append(text, start, text.length)
            // Chars, not bytes, but a fine bound: one char is at most 3 UTF-8 bytes.
            if (line.length > maxLineBytes) {
                throw LlmException(LlmErrorKind.MalformedResponse, "Stream line exceeds $maxLineBytes characters")
            }
        }
        return out ?: emptyList()
    }

    private fun endLine(): SseEvent? {
        val text = line.toString()
        line.clear()
        if (text.isEmpty()) return dispatch()
        processLine(text)
        return null
    }

    private fun processLine(text: String) {
        if (text.startsWith(':')) return // comment / keep-alive
        val colon = text.indexOf(':')
        val field = if (colon == -1) text else text.substring(0, colon)
        var value = if (colon == -1) "" else text.substring(colon + 1)
        if (value.startsWith(' ')) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
                if (data.length > maxLineBytes) {
                    throw LlmException(LlmErrorKind.MalformedResponse, "Stream event exceeds $maxLineBytes characters")
                }
            }
            "id" -> if ('\u0000' !in value) lastId = value
            // "retry" is for browser reconnection; LLM streams aren't resumable. Others: ignored.
        }
    }

    private fun dispatch(): SseEvent? {
        val event = if (hasData) SseEvent(eventName ?: "message", data.toString(), lastId) else null
        eventName = null
        data.clear()
        hasData = false
        return event
    }
}

/**
 * Decodes UTF-8 across chunk boundaries: an incomplete trailing sequence is held back until
 * the next chunk. Invalid bytes become U+FFFD instead of failing the stream.
 */
internal class Utf8Accumulator {
    private var pending = ByteArray(0)

    fun decode(chunk: ByteArray): String {
        val bytes = if (pending.isEmpty()) chunk else pending + chunk
        val keep = incompleteTail(bytes)
        pending = if (keep == 0) ByteArray(0) else bytes.copyOfRange(bytes.size - keep, bytes.size)
        return bytes.decodeToString(0, bytes.size - keep)
    }

    fun flush(): String {
        val rest = pending
        pending = ByteArray(0)
        return rest.decodeToString() // a truncated sequence becomes U+FFFD
    }

    /** Number of bytes at the end that start a multi-byte sequence not yet complete. */
    private fun incompleteTail(bytes: ByteArray): Int {
        val n = bytes.size
        // Walk back over at most 3 continuation bytes to the lead byte.
        var i = n - 1
        var continuation = 0
        while (i >= 0 && continuation < 3 && (bytes[i].toInt() and 0xC0) == 0x80) {
            i--
            continuation++
        }
        if (i < 0) return 0
        val lead = bytes[i].toInt() and 0xFF
        val needed = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> return 0 // invalid lead: let the decoder replace it
        }
        val have = continuation + 1
        return if (have < needed) have else 0
    }
}
