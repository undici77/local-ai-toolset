package com.undici77.aitoolset.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateEngineTest {
    @Test fun simpleSubstitution() {
        assertEquals("Hi Bob", TemplateEngine.render("Hi {name}", mapOf("name" to "Bob")))
    }

    @Test fun unknownPlaceholderKept() {
        assertEquals("a {x} b {Y} { z }", TemplateEngine.render("a {x} b {Y} { z }", emptyMap()))
    }

    @Test fun conditionalPresent() {
        assertEquals("A\nhint: h\nB", TemplateEngine.render("A{?hint:\nhint: {hint}}\nB", mapOf("hint" to "h")))
    }

    @Test fun conditionalAbsent() {
        assertEquals("A\nB", TemplateEngine.render("A{?hint:\nhint: {hint}}\nB", mapOf("hint" to " ")))
        assertEquals("A\nB", TemplateEngine.render("A{?hint:\nhint: {hint}}\nB", emptyMap()))
    }

    @Test fun nestedBracesInsideConditional() {
        assertEquals("x {literal} h y", TemplateEngine.render("x{?hint: {literal} {hint}} y", mapOf("hint" to "h")))
    }

    @Test fun unterminatedConditionalUnchanged() {
        assertEquals("a {?hint: b", TemplateEngine.render("a {?hint: b", mapOf("hint" to "h")))
    }

    @Test fun collapsesNewlines() {
        assertEquals("a\n\nb", TemplateEngine.render("a\n\n\n\nb", emptyMap()))
    }

    @Test fun ensureContains() {
        assertEquals("t\n\nDiff:\n{diff}", TemplateEngine.ensureContains("t", "diff", "\n\nDiff:\n{diff}"))
        assertEquals("t {diff}", TemplateEngine.ensureContains("t {diff}", "diff", "\n\nDiff:\n{diff}"))
    }

    @Test fun defaultCommitTemplateRenders() {
        val t = Defaults.feature(com.undici77.aitoolset.config.FeatureId.COMMIT_MESSAGE).userTemplate
        val vars = mapOf("branch" to "main", "recent_commits" to "- x", "files" to "M a", "diff" to "D", "hint" to "")
        val out = TemplateEngine.render(t, vars)
        assertEquals("Branch: main\n\nRecent commits:\n- x\n\nChanged files:\nM a\n\nDiff:\nD", out)
    }
}
