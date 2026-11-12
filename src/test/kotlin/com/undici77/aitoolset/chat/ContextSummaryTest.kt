package com.undici77.aitoolset.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSummaryTest {
    @Test fun approxTokens() {
        assertEquals("0", ContextSummary.approxTokens(3))
        assertEquals("850", ContextSummary.approxTokens(3400))
        assertEquals("2k", ContextSummary.approxTokens(8000))
        assertEquals("2.1k", ContextSummary.approxTokens(8400))
        assertEquals("12k", ContextSummary.approxTokens(49_000))
    }

    @Test fun text() {
        assertEquals("1 file · ~850 tokens", ContextSummary(listOf("a.kt"), emptyList(), 3400).text)
        assertEquals("3 files · ~2.1k tokens · 1 omitted (too large)", ContextSummary(listOf("a", "b", "c"), listOf("big.c"), 8400).text)
        assertEquals("2 omitted (too large)", ContextSummary(emptyList(), listOf("x", "y"), 40).text)
    }

    @Test fun tooltipEscapesNames() {
        val t = ContextSummary(listOf("a<b>.kt:1-3"), listOf("big.c"), 10).tooltip
        assertTrue(t, t.contains("Sent: a&lt;b&gt;.kt:1-3<br>Omitted, over the context limit: big.c"))
    }
}
