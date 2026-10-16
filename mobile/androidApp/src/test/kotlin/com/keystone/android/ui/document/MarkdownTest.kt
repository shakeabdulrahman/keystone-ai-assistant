package com.keystone.android.ui.document

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {

    @Test
    fun `paragraphs and bullet lists are separated`() {
        val body = """
            Every employee receives, per year:

            - **Annual leave:** 20 working days
            - **Sick leave:** 10
              working days

            Part-time employees receive leave
            in proportion.
        """.trimIndent()

        assertEquals(
            listOf(
                MdBlock.Paragraph("Every employee receives, per year:"),
                MdBlock.Bullets(listOf("**Annual leave:** 20 working days", "**Sick leave:** 10 working days")),
                MdBlock.Paragraph("Part-time employees receive leave in proportion."),
            ),
            parseMarkdown(body),
        )
    }

    @Test
    fun `bold markers are removed and styled`() {
        val text = inlineMarkdown("Up to **5 days** by **31 March**.")
        assertEquals("Up to 5 days by 31 March.", text.text)
        assertEquals(listOf("5 days", "31 March"), text.spanStyles.map { text.text.substring(it.start, it.end) })
    }

    @Test
    fun `an unmatched marker is kept as typed`() {
        assertEquals("2 ** 3", inlineMarkdown("2 ** 3").text)
    }
}
