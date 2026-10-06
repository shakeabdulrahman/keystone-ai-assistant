package com.keystone.shared.data.remote.sse

/** One Server-Sent Event: its `event:` name and its (possibly multi-line) `data:`. */
data class SseEvent(val event: String, val data: String)

/**
 * Incremental parser for the `text/event-stream` format (WHATWG HTML spec, simplified).
 *
 * Feed it one line at a time, as lines arrive from the network; it returns an event
 * whenever a blank line completes one. Comment lines (starting with `:`) are keep-alives
 * and are ignored. Pure logic with no I/O, so it is trivial to unit test.
 */
class SseEventParser {
    private var eventName: String? = null
    private val dataLines = mutableListOf<String>()

    fun consume(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        return when {
            line.isEmpty() -> dispatch()
            line.startsWith(":") -> null
            else -> {
                val colon = line.indexOf(':')
                val field = if (colon == -1) line else line.substring(0, colon)
                var value = if (colon == -1) "" else line.substring(colon + 1)
                if (value.startsWith(" ")) value = value.substring(1)
                when (field) {
                    "event" -> eventName = value
                    "data" -> dataLines += value
                    // "id" and "retry" are part of the spec but unused by our protocol.
                }
                null
            }
        }
    }

    /** Call when the stream ends, in case the last event wasn't followed by a blank line. */
    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        val event = if (dataLines.isEmpty()) {
            null
        } else {
            SseEvent(event = eventName ?: "message", data = dataLines.joinToString("\n"))
        }
        eventName = null
        dataLines.clear()
        return event
    }
}
