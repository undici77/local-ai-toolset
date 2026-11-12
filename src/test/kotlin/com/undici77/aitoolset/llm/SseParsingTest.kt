package com.undici77.aitoolset.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParsingTest {
    private fun parse(vararg lines: String): List<SseEvent> {
        val p = SseParser()
        val out = mutableListOf<SseEvent>()
        for (l in lines) p.feed(l)?.let { out += it }
        p.flush()?.let { out += it }
        return out
    }

    @Test fun multiLineData() {
        assertEquals(listOf(SseEvent(null, "a\nb")), parse("data: a", "data: b", ""))
    }

    @Test fun eventName() {
        assertEquals(listOf(SseEvent("ping", "{}")), parse("event: ping", "data: {}", ""))
    }

    @Test fun commentsIgnored() {
        assertEquals(listOf(SseEvent(null, "x")), parse(": keep-alive", "data: x", ""))
    }

    @Test fun trailingEventFlushed() {
        assertEquals(listOf(SseEvent(null, "x")), parse("data: x"))
    }

    @Test fun blankLineAloneYieldsNothing() {
        assertNull(SseParser().feed(""))
    }

    @Test fun twoEvents() {
        assertEquals(2, parse("data: 1", "", "data: 2", "").size)
    }
}
