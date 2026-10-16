package com.keystone.shared.voice

/**
 * Turns answer text into something a text-to-speech engine reads naturally:
 * no citation markers ("[1]"), no Markdown symbols, and symbols said as words.
 * Returns an empty string when nothing is left to say (e.g. a lone "[2].").
 */
fun speakable(text: String): String = text
    .replace(CITATION, "")
    .replace(BOLD, "")
    .replace(HEADING, "")
    .replace(BULLET, "")
    .replace(URL, "the link on screen")
    .replace(" → ", " to ")
    .replace("→", " to ")
    .replace(" – ", " to ") // date ranges: "Mon 26 Oct – Tue 27 Oct"
    .replace("&", " and ")
    .replace(SPACE_BEFORE_PUNCTUATION, "$1")
    .replace(WHITESPACE, " ")
    .trim()
    .let { if (it.all { c -> !c.isLetterOrDigit() }) "" else it }

private val CITATION = Regex("""\s*\[\d{1,2}(?:\s*,\s*\d{1,2})*]""")
private val BOLD = Regex("""\*\*|__|`""")
private val HEADING = Regex("""(?m)^#+\s*""")
private val BULLET = Regex("""(?m)^\s*[-*•]\s+""")
private val URL = Regex("""https?://\S+""")
private val SPACE_BEFORE_PUNCTUATION = Regex("""\s+([.,!?;:])""")
private val WHITESPACE = Regex("""\s+""")
