package com.keystone.shared.data.remote.sse

import kotlin.test.Test
import kotlin.test.assertEquals

class SseEventParserTest {

    private fun parse(vararg lines: String): List<SseEvent> {
        val parser = SseEventParser()
        return lines.mapNotNull(parser::consume) + listOfNotNull(parser.finish())
    }

    @Test
    fun parsesNamedEvent() {
        assertEquals(
            listOf(SseEvent("text.delta", """{"text":"Hi"}""")),
            parse("event: text.delta", """data: {"text":"Hi"}""", ""),
        )
    }

    @Test
    fun ignoresKeepAliveComments() {
        assertEquals(
            listOf(SseEvent("a", "1")),
            parse(": keep-alive", "", "event: a", "data: 1", ""),
        )
    }

    @Test
    fun joinsMultipleDataLinesWithNewline() {
        assertEquals(listOf(SseEvent("message", "line1\nline2")), parse("data: line1", "data: line2", ""))
    }

    @Test
    fun handlesCarriageReturnsAndMissingSpace() {
        assertEquals(listOf(SseEvent("x", "v")), parse("event:x\r", "data:v\r", "\r"))
    }

    @Test
    fun blankLineWithoutDataDispatchesNothing() {
        assertEquals(emptyList(), parse("event: orphan", "", ""))
    }

    @Test
    fun flushesFinalEventWithoutTrailingBlankLine() {
        assertEquals(listOf(SseEvent("done", "{}")), parse("event: done", "data: {}"))
    }

    @Test
    fun eventNameResetsBetweenEvents() {
        assertEquals(
            listOf(SseEvent("first", "1"), SseEvent("message", "2")),
            parse("event: first", "data: 1", "", "data: 2", ""),
        )
    }
}
