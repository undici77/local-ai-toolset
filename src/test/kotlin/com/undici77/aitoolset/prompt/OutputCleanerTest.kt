package com.undici77.aitoolset.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputCleanerTest {
    private fun c(s: String) = OutputCleaner.cleanCommitMessage(s)

    @Test fun thinkTags() {
        assertEquals("feat: x", c("<think>hmm\nlines</think>\nfeat: x"))
        assertEquals("", c("<think>never closed"))
    }

    @Test fun fences() {
        assertEquals("feat: x\n\nbody", c("```text\nfeat: x\n\nbody\n```"))
    }

    @Test fun quotes() {
        assertEquals("feat: x", c("\"feat: x\""))
        assertEquals("feat: x", c("`feat: x`"))
    }

    @Test fun label() {
        assertEquals("feat: x", c("Commit message: feat: x"))
    }

    @Test fun trailingWhitespace() {
        assertEquals("a\nb", c("a  \nb \n\n"))
    }

    @Test fun partialDropsOpeningFenceAndArrivingClose() {
        assertEquals("", OutputCleaner.cleanCommitMessagePartial("```"))
        assertEquals("", OutputCleaner.cleanCommitMessagePartial("```git"))
        assertEquals("feat: add x", OutputCleaner.cleanCommitMessagePartial("```text\nfeat: add x"))
        assertEquals("feat: add x", OutputCleaner.cleanCommitMessagePartial("```\nfeat: add x\n``"))
        assertEquals("feat: add x", OutputCleaner.cleanCommitMessagePartial("```\nfeat: add x\n```"))
    }

    @Test fun partialDropsLabelAndLeadingQuote() {
        assertEquals("fix: y", OutputCleaner.cleanCommitMessagePartial("Commit message: fix: y"))
        assertEquals("fix: y", OutputCleaner.cleanCommitMessagePartial("\"fix: y"))
    }

    @Test fun partialKeepsBackticksWithoutFence() {
        assertEquals("fix: handle `null`", OutputCleaner.cleanCommitMessagePartial("fix: handle `null`"))
    }

    @Test fun partialMatchesFinalForCompleteAnswers() {
        for (raw in listOf("```\nfeat: a\n\nbody\n```", "Commit message: fix: b", "<think>x</think>\nchore: c")) {
            assertEquals(raw, OutputCleaner.cleanCommitMessage(raw), OutputCleaner.cleanCommitMessagePartial(raw))
        }
    }
}
