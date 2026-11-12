package com.undici77.aitoolset.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {
    @Test fun splitTextAndCode() {
        val segs = MarkdownLite.split("Intro\n```cpp\nint x;\n```\nOutro")
        assertEquals(listOf(Segment.Text("Intro"), Segment.Code("cpp", "int x;"), Segment.Text("Outro")), segs)
    }

    @Test fun unterminatedFence() {
        assertEquals(listOf(Segment.Code("py", "a\nb")), MarkdownLite.split("```py\na\nb"))
    }

    @Test fun tildeFenceAndNoLang() {
        assertEquals(listOf(Segment.Code("", "x")), MarkdownLite.split("~~~\nx\n~~~"))
    }

    @Test fun htmlIsEscaped() {
        val html = MarkdownLite.toHtml("<script>alert(1)</script> & **b**")
        assertFalse(html, html.contains("<script>"))
        assertTrue(html, html.contains("&lt;script&gt;") && html.contains("&amp;") && html.contains("<b>b</b>"))
    }

    @Test fun lists() {
        val html = MarkdownLite.toHtml("- a\n- b\n\n1. x\n2. y")
        assertTrue(html, html.contains("<ul><li>a</li><li>b</li></ul>"))
        assertTrue(html, html.contains("<ol start=\"1\"><li>x</li><li>y</li></ol>"))
    }

    @Test fun inlineCodeIsNotFormatted() {
        val html = MarkdownLite.toHtml("use `a**b**c` now *it*")
        assertTrue(html, html.contains("<code>a**b**c</code>"))
        assertTrue(html, html.contains("<i>it</i>"))
    }

    @Test fun linksAndHeadings() {
        val html = MarkdownLite.toHtml("## Title\n[x](https://a.b/c) [bad](javascript:alert(1))")
        assertTrue(html, html.startsWith("<h2>Title</h2>"))
        assertTrue(html, html.contains("<a href=\"https://a.b/c\">x</a>"))
        assertFalse(html, html.contains("href=\"javascript"))
    }

    @Test fun headingLevelsAndClosingHashes() {
        assertEquals("<h1>A</h1>", MarkdownLite.toHtml("# A #"))
        assertEquals("<h4>F</h4>", MarkdownLite.toHtml("###### F"))
        assertEquals("#nospace", MarkdownLite.toHtml("#nospace"))
    }

    @Test fun horizontalRuleIsNotABullet() {
        assertEquals("a<div class=\"hr\"></div>b", MarkdownLite.toHtml("a\n---\nb"))
        assertEquals("<div class=\"hr\"></div>", MarkdownLite.toHtml("* * *"))
        assertEquals("<ul><li>x</li></ul>", MarkdownLite.toHtml("- x"))
    }

    @Test fun blockquote() {
        val html = MarkdownLite.toHtml("> **note**\n> - item\nafter")
        assertEquals("<blockquote><b>note</b><ul><li>item</li></ul></blockquote>after", html)
    }

    @Test fun table() {
        val html = MarkdownLite.toHtml("| Name | `a|b` | N |\n|:--|:-:|--:|\n| x | **y** |\n\nend")
        assertEquals(
            "<table cellspacing=\"0\" cellpadding=\"3\"><tr><th>Name</th><th align=\"center\"><code>a|b</code></th><th align=\"right\">N</th></tr>" +
                "<tr><td>x</td><td align=\"center\"><b>y</b></td><td align=\"right\"></td></tr></table>end",
            html,
        )
    }

    @Test fun pipeLineWithoutSeparatorIsText() {
        assertEquals("a | b<br>c", MarkdownLite.toHtml("a | b\nc"))
    }

    @Test fun nestedLists() {
        val html = MarkdownLite.toHtml("1. one\n   - a\n   - b\n2. two\n- top")
        assertEquals(
            "<ol start=\"1\"><li>one<ul><li>a</li><li>b</li></ul></li><li>two</li></ol><ul><li>top</li></ul>",
            html,
        )
    }

    @Test fun blankLineInsideListKeepsItOpen() {
        val html = MarkdownLite.toHtml("1. a\n\n2. b\n\n   more\n\nafter")
        assertEquals("<ol start=\"1\"><li>a</li><li>b<br>more</li></ol>after", html)
    }

    @Test fun strikethrough() {
        assertTrue(MarkdownLite.toHtml("~~old~~ new").startsWith("<s>old</s>"))
    }
}
