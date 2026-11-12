package com.undici77.aitoolset.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StableLengthTest {
    private fun stable(md: String) = md.substring(0, MarkdownLite.stableLength(md))

    @Test fun nothingStableWithoutBlankLine() = assertEquals("", stable("Hello wor"))

    @Test fun cutsAfterBlankLine() = assertEquals("Para one.\n\n", stable("Para one.\n\nPara tw"))

    @Test fun incompleteLastLineIsNeverStable() = assertEquals("a\n\n", stable("a\n\nb\n"))

    @Test fun noCutInsideOpenFence() = assertEquals("Intro\n\n", stable("Intro\n\n```kt\nval a = 1\n\nval b"))

    @Test fun cutsAfterClosingFence() = assertEquals("```kt\nx\n```\n", stable("```kt\nx\n```\nAfter"))

    @Test fun blankLineInsideListIsNotACutUntilTheListEnds() {
        val md = "Intro\n\n1. Item 1\n\n   - a\n   - b\n\n2. Item 2\n\n3. Item 3\n\nAfter"
        assertEquals("Intro\n\n", stable(md.substringBefore("After")))
        assertEquals("Intro\n\n", stable(md.substringBefore("   - a") + "   "))
        assertEquals("Intro\n\n", stable(md.substringBefore("2. Item 2") + "2"))
        assertEquals(md.substringBefore("After"), stable(md))
    }

    @Test fun listFollowedByParagraphCutsWhenTheParagraphStarts() =
        assertEquals("- a\n\n", stable("- a\n\nNext para"))

    /** Streaming a nested list renders the same HTML as rendering it at once. */
    @Test fun chunkedNestedListMatchesWhole() {
        val md = "1. Item 1\n\n   - a\n   - b\n\n2. Item 2\n\nEnd"
        for (i in 0..md.length) {
            val cut = MarkdownLite.stableLength(md.substring(0, i))
            val chunks = listOf(md.substring(0, cut), md.substring(cut)).filter { it.isNotBlank() }
            val html = chunks.joinToString("") { MarkdownLite.toHtml(it) }
            assertEquals("at $i", MarkdownLite.toHtml(md).replace("<br>", ""), html.replace("<br>", ""))
        }
    }

    /** Rendering stable prefix + tail separately gives the same segments as rendering everything at once. */
    @Test fun chunkedSplitMatchesWholeSplit() {
        val md = "Intro text\n\n1. one\n\n2. two\n\n```py\nprint(1)\n\nprint(2)\n```\nEnd **bold**"
        for (i in 0..md.length) {
            val prefix = md.substring(0, i)
            val cut = MarkdownLite.stableLength(prefix)
            val chunked = MarkdownLite.split(md.substring(0, cut)) + MarkdownLite.split(md.substring(cut))
            assertEquals("at $i", MarkdownLite.split(md).joinToString("|") { segKey(it) }.replace("\n", ""),
                chunked.joinToString("|") { segKey(it) }.replace("\n", "").let(::normalize))
        }
    }

    private fun segKey(s: Segment) = when (s) { is Segment.Text -> "T"; is Segment.Code -> "C:${s.lang}:${s.code}" }

    // Text segments may be split into several at blank lines; collapse runs of text for comparison.
    private fun normalize(k: String) = k.replace(Regex("T(\\|T)+"), "T")
}

class OrderedListTest {
    @Test fun keepsModelNumbering() {
        val html = MarkdownLite.toHtml("2. second")
        assertEquals(true, html.contains("<ol start=\"2\">"))
    }
}
