package com.keystone.shared.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SentenceChunkerTest {

    /** Feeds [text] in small pieces, like a streaming model, and collects everything said. */
    private fun chunk(text: String, piece: Int = 3): List<String> {
        val chunker = SentenceChunker()
        val out = text.chunked(piece).flatMap(chunker::add).toMutableList()
        chunker.finish()?.let(out::add)
        return out
    }

    @Test
    fun sentencesAreReleasedAsSoonAsTheyEnd() {
        val chunker = SentenceChunker()
        assertEquals(emptyList(), chunker.add("You have 14 days of annual "))
        assertEquals(emptyList(), chunker.add("leave left.")) // no space yet: it might be "left.5"
        assertEquals(listOf("You have 14 days of annual leave left."), chunker.add(" Want to"))
        assertEquals("Want to", chunker.finish())
        assertNull(chunker.finish())
    }

    @Test
    fun abbreviationsAndDecimalsDoNotSplitSentences() {
        assertEquals(
            listOf("That covers e.g. annual leave of 2.5 days.", "Anything else?"),
            chunk("That covers e.g. annual leave of 2.5 days. Anything else?"),
        )
    }

    @Test
    fun shortSentencesAreJoinedSoSpeechIsNotChoppy() {
        assertEquals(listOf("Yes. Ok! You can carry over 5 days."), chunk("Yes. Ok! You can carry over 5 days."))
    }

    @Test
    fun lineBreaksEndSentences() {
        assertEquals(
            listOf("Here is what I found:", "- Annual: 14 days", "- Sick: 9 days"),
            chunk("Here is what I found:\n- Annual: 14 days\n- Sick: 9 days"),
        )
    }

    @Test
    fun chunkingDoesNotDependOnHowTheTextArrives() {
        val text = "Your leave starts on Monday. It ends on Tuesday! Your manager will review it."
        val expected = chunk(text, piece = 1)
        for (piece in listOf(2, 5, 17, text.length)) assertEquals(expected, chunk(text, piece))
    }
}
