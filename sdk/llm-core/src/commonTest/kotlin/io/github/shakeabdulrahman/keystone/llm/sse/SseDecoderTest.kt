package io.github.shakeabdulrahman.keystone.llm.sse

import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SseDecoderTest {

    /** Feeds [bytes] split into chunks of [size] and returns all events, including at finish. */
    private fun decode(bytes: ByteArray, size: Int): List<SseEvent> {
        val decoder = SseDecoder()
        val out = mutableListOf<SseEvent>()
        var i = 0
        while (i < bytes.size) {
            val end = minOf(bytes.size, i + size)
            out += decoder.feed(bytes.copyOfRange(i, end))
            i = end
        }
        out += decoder.finish()
        return out
    }

    /** The result must not depend on how the network chunked the bytes. */
    private fun assertSameForEveryChunking(text: String, expected: List<SseEvent>) {
        val bytes = text.encodeToByteArray()
        for (size in 1..bytes.size) {
            assertEquals(expected, decode(bytes, size), "chunk size $size")
        }
    }

    @Test
    fun severalEventsInOneChunkAndOneEventOverManyChunks() {
        assertSameForEveryChunking(
            "data: one\n\ndata: two\n\nevent: done\ndata: three\n\n",
            listOf(SseEvent(data = "one"), SseEvent(data = "two"), SseEvent("done", "three")),
        )
    }

    @Test
    fun crlfAndLoneCRLineEndingsIncludingACRLFSplitBetweenChunks() {
        assertSameForEveryChunking(
            "data: a\r\n\r\ndata: b\r\rdata: c\n\n",
            listOf(SseEvent(data = "a"), SseEvent(data = "b"), SseEvent(data = "c")),
        )
    }

    @Test
    fun crlfInsideAMultiLineEventIsOneLineEndNotTwo() {
        // If \r\n counted as two line ends, the empty "line" would dispatch "a" early.
        assertSameForEveryChunking(
            "data: a\r\ndata: b\r\n\r\n",
            listOf(SseEvent(data = "a\nb")),
        )
    }

    @Test
    fun multiByteUTF8SplitAtEveryPosition() {
        // 2-, 3- and 4-byte characters.
        val text = "data: héllo → 🚀 done\n\n"
        assertSameForEveryChunking(text, listOf(SseEvent(data = "héllo → 🚀 done")))
    }

    @Test
    fun commentsAndKeepAlivesAreIgnored() {
        assertSameForEveryChunking(
            ": keep-alive\n\n:ping\ndata: x\n\n",
            listOf(SseEvent(data = "x")),
        )
    }

    @Test
    fun multiLineDataIsJoinedWithNewlines() {
        assertSameForEveryChunking("data: a\ndata:b\ndata\n\n", listOf(SseEvent(data = "a\nb\n")))
    }

    @Test
    fun idIsRememberedAndUnknownFieldsAreIgnored() {
        assertSameForEveryChunking(
            "id: 7\nretry: 100\nfoo: bar\ndata: x\n\ndata: y\n\n",
            listOf(SseEvent(data = "x", id = "7"), SseEvent(data = "y", id = "7")),
        )
    }

    @Test
    fun leadingByteOrderMarkIsSkipped() {
        assertSameForEveryChunking("\uFEFFdata: x\n\n", listOf(SseEvent(data = "x")))
    }

    @Test
    fun anEventWithoutDataIsNotDispatched() {
        assertSameForEveryChunking("event: ping\n\ndata: x\n\n", listOf(SseEvent(data = "x")))
    }

    @Test
    fun finalEventWithoutTrailingBlankLineIsDeliveredAtFinish() {
        assertSameForEveryChunking("data: a\n\ndata: last", listOf(SseEvent(data = "a"), SseEvent(data = "last")))
    }

    @Test
    fun aTruncatedUTF8SequenceAtEndOfStreamBecomesAReplacementCharacter() {
        val bytes = "data: ok ".encodeToByteArray() + byteArrayOf(0xF0.toByte(), 0x9F.toByte())
        assertEquals(listOf(SseEvent(data = "ok \uFFFD")), decode(bytes, 3))
    }

    @Test
    fun anEndlessLineFailsAsMalformedInsteadOfBufferingForever() {
        val decoder = SseDecoder(maxLineBytes = 16)
        val error = assertFailsWith<LlmException> { decoder.feed("data: 0123456789abcdefghij".encodeToByteArray()) }
        assertEquals(LlmErrorKind.MalformedResponse, error.kind)
    }
}
