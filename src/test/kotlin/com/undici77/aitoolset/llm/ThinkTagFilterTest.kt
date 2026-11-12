package com.undici77.aitoolset.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkTagFilterTest {
    /** Feeds [chunks] and returns (reasoning, text). */
    private fun run(vararg chunks: String): Pair<String, String> = runBlocking {
        val r = StringBuilder(); val t = StringBuilder()
        val f = ThinkTagFilter { ev ->
            when (ev) {
                is StreamEvent.Reasoning -> r.append(ev.text)
                is StreamEvent.Text -> t.append(ev.text)
                else -> {}
            }
        }
        chunks.forEach { f.text(it) }
        f.finish()
        r.toString() to t.toString()
    }

    @Test fun noTag() = assertEquals("" to "Hello world", run("Hello", " world"))

    @Test fun wholeBlockInOneDelta() = assertEquals("plan" to "Answer", run("<think>plan</think>\n\nAnswer"))

    @Test fun tagsSplitAtEveryPosition() {
        val full = "  <think>a b</think>\nAnswer <think>x</think>"
        for (i in 1 until full.length) for (j in i until full.length) {
            val got = run(full.substring(0, i), full.substring(i, j), full.substring(j))
            assertEquals("split $i/$j", "a b" to "Answer <think>x</think>", got)
        }
    }

    @Test fun tagLaterInAnswerIsKept() = assertEquals("" to "Use <think> tags", run("Use <think> tags"))

    @Test fun unclosedBlockIsReasoning() = assertEquals("still thinking" to "", run("<think>still ", "thinking"))

    @Test fun shortPrefixThatIsNotATag() = assertEquals("" to "<th", run("<th"))
}
