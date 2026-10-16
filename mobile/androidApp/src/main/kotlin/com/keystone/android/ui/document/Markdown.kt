package com.keystone.android.ui.document

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The small Markdown subset our policy documents use: paragraphs, "- " bullet lists and
 * **bold**. A full Markdown library would be overkill, and this keeps rendering native.
 */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
}

fun parseMarkdown(body: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    val bullets = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(paragraph.joinToString(" "))
        paragraph.clear()
    }

    fun flushBullets() {
        if (bullets.isNotEmpty()) blocks += MdBlock.Bullets(bullets.toList())
        bullets.clear()
    }

    for (raw in body.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> {
                flushParagraph()
                flushBullets()
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flushParagraph()
                bullets += line.drop(2).trim()
            }
            bullets.isNotEmpty() -> bullets[bullets.lastIndex] = bullets.last() + " " + line // wrapped item
            else -> paragraph += line
        }
    }
    flushParagraph()
    flushBullets()
    return blocks
}

/** Applies **bold**; an unmatched "**" is shown as typed. */
fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val start = rest.indexOf("**")
        val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
        if (start < 0 || end < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(start + 2, end)) }
        rest = rest.substring(end + 2)
    }
}
