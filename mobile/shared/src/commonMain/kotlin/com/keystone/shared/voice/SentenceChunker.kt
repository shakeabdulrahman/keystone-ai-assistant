package com.keystone.shared.voice

/**
 * Splits streamed answer text into sentences, so speech can start after the first
 * sentence instead of waiting for the whole answer (lower "time to first word").
 *
 * Feed text deltas in with [add]; it returns every sentence completed so far. Call
 * [finish] at the end for whatever is left. Shared (KMP) so iOS speaks the same way.
 *
 * A sentence ends at '.', '!' or '?' followed by whitespace, or at a line break, except:
 * - abbreviations ("e.g.", "Dr."), so "e.g. annual leave" isn't cut in two;
 * - decimals ("2.5 days") never match, because no whitespace follows the dot.
 * Very short sentences are joined with the next one ([minChars]) so speech doesn't sound choppy.
 */
class SentenceChunker(private val minChars: Int = 12) {
    private val buffer = StringBuilder()

    fun add(delta: String): List<String> {
        buffer.append(delta)
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            val atEnd = i + 1 >= buffer.length
            val boundary = when {
                c == '\n' -> true
                c in ENDINGS && !atEnd && buffer[i + 1].isWhitespace() -> !endsWithAbbreviation(buffer, start, i)
                else -> false
            }
            if (boundary) {
                val sentence = buffer.substring(start, i + 1).trim()
                if (sentence.length >= minChars || (c == '\n' && sentence.isNotEmpty())) {
                    sentences += sentence
                    start = i + 1
                }
            }
            i++
        }
        buffer.deleteRange(0, start)
        return sentences
    }

    /** The rest of the answer (the last sentence often has no trailing space). */
    fun finish(): String? {
        val rest = buffer.toString().trim()
        buffer.clear()
        return rest.ifEmpty { null }
    }

    private fun endsWithAbbreviation(text: CharSequence, from: Int, dot: Int): Boolean {
        if (text[dot] != '.') return false
        var wordStart = dot
        while (wordStart > from && !text[wordStart - 1].isWhitespace()) wordStart--
        val word = text.substring(wordStart, dot + 1).lowercase().trimStart('(', '"', '\'')
        return word in ABBREVIATIONS
    }

    private companion object {
        val ENDINGS = setOf('.', '!', '?')
        val ABBREVIATIONS = setOf(
            "e.g.", "i.e.", "etc.", "vs.", "approx.", "dr.", "mr.", "mrs.", "ms.", "no.", "jan.", "feb.",
            "mar.", "apr.", "jun.", "jul.", "aug.", "sep.", "sept.", "oct.", "nov.", "dec.",
        )
    }
}
