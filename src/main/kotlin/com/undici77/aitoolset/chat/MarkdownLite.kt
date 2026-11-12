package com.undici77.aitoolset.chat

sealed interface Segment {
    data class Text(val md: String) : Segment
    data class Code(val lang: String, val code: String) : Segment
}

object MarkdownLite {
    private val FENCE_OPEN = Regex("^\\s*(```+|~~~+)\\s*([^\\s`]*)\\s*$")
    private val HEADING = Regex("^ {0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
    private val STRIKE = Regex("~~(?!\\s)(.+?)(?<!\\s)~~")
    private val CODE = Regex("`([^`\\n]+)`")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC = Regex("(?<![*\\w])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![*\\w])")
    private val LINK = Regex("\\[([^\\]\\n]+)]\\((https?://[^)\\s]+)\\)")

    /** Fenced blocks ``` or ~~~ with optional language; an unterminated fence runs to the end. */
    fun split(md: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val text = StringBuilder()
        var fence: String? = null
        var lang = ""
        val code = StringBuilder()
        fun flushText() { if (text.isNotBlank()) out += Segment.Text(text.toString().trim('\n')); text.setLength(0) }
        for (line in md.lines()) {
            val f = fence
            if (f == null) {
                val m = FENCE_OPEN.matchEntire(line)
                if (m != null) { flushText(); fence = m.groupValues[1].take(3); lang = m.groupValues[2]; code.setLength(0) }
                else text.append(line).append('\n')
            } else if (line.trim().startsWith(f) && line.trim().all { it == f[0] }) {
                out += Segment.Code(lang, code.toString().removeSuffix("\n")); fence = null
            } else code.append(line).append('\n')
        }
        if (fence != null) out += Segment.Code(lang, code.toString().removeSuffix("\n")) else flushText()
        return out
    }

    /**
     * Length of the prefix of [md] that is final while more text is still arriving: it ends right after
     * a blank line or a closing code fence, outside any open fence. A blank line inside a list is a cut
     * only once the next line shows the list has ended (not indented, not a list item), so nested items
     * stay with their parent. 0 when there is no such point yet.
     */
    fun stableLength(md: String): Int {
        var cut = 0
        var fence: String? = null
        var pos = 0
        var inList = false
        var pending = -1 // cut candidate after a blank line inside a list
        while (true) {
            val nl = md.indexOf('\n', pos)
            val complete = nl >= 0
            val line = if (complete) md.substring(pos, nl) else md.substring(pos)
            if (fence == null && pending >= 0 && line.isNotBlank()) {
                val continues = line[0].isWhitespace() || BULLET.matches(line) || NUMBERED.matches(line)
                // An incomplete line decides only when its first character cannot start a list item.
                if (continues) pending = -1
                else if (complete || line[0] !in LIST_START) { cut = pending; pending = -1; inList = false }
            }
            if (!complete) break // the last line is still incomplete
            pos = nl + 1
            val f = fence
            if (f == null) {
                val m = FENCE_OPEN.matchEntire(line)
                when {
                    m != null -> { fence = m.groupValues[1].take(3); inList = false }
                    line.isBlank() -> if (!inList) cut = pos else if (pending < 0) pending = pos
                    BULLET.matches(line) || NUMBERED.matches(line) -> inList = true
                    inList && line[0].isWhitespace() -> {}
                    else -> inList = false
                }
            } else if (line.trim().startsWith(f) && line.trim().all { it == f[0] }) {
                fence = null; cut = pos; inList = false
            }
        }
        return cut
    }

    private const val LIST_START = "-*+0123456789"

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun inline(raw: String): String {
        var s = escape(raw)
        val codes = mutableListOf<String>()
        s = CODE.replace(s) { codes += "<code>${it.groupValues[1]}</code>"; "\u0001${codes.size - 1}\u0001" }
        s = LINK.replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        s = BOLD.replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = ITALIC.replace(s) { "<i>${it.groupValues[1]}</i>" }
        s = STRIKE.replace(s) { "<s>${it.groupValues[1]}</s>" }
        return Regex("\u0001(\\d+)\u0001").replace(s) { codes[it.groupValues[1].toInt()] }
    }

    /**
     * HTML-escapes first, then applies a subset of Markdown: headings, lists (nested by indentation,
     * numbering kept), blockquotes, tables, horizontal rules and inline code/bold/italic/strike/links.
     * Plain lines keep their line breaks (chat style) instead of being joined into paragraphs.
     */
    fun toHtml(md: String): String = Renderer(md.lines()).render()

    /**
     * Style rules for [toHtml] output in a Swing HTML pane. Font sizes are relative to the body font
     * (Swing honours `%`, not `px`, for font-size); its default `h4` is smaller than the body text.
     */
    fun css(borderColor: String, mutedColor: String): String = """
        h1 { font-size: 140%; margin-top: 6px; margin-bottom: 2px }
        h2 { font-size: 125%; margin-top: 6px; margin-bottom: 2px }
        h3 { font-size: 110%; margin-top: 4px; margin-bottom: 2px }
        h4 { font-size: 100%; margin-top: 4px; margin-bottom: 2px }
        ul, ol { margin-top: 2px; margin-bottom: 2px }
        li ul, li ol { margin-left-ltr: 18px; margin-right-rtl: 18px }
        table { margin-top: 4px; margin-bottom: 4px }
        th { text-align: left; border: 1px solid $borderColor }
        td { border: 1px solid $borderColor }
        blockquote { margin: 2px 0 2px 2px; padding-left: 8px; border-left: 2px solid $borderColor; color: $mutedColor }
        div.hr { border-top: 1px solid $borderColor; margin-top: 6px; margin-bottom: 6px; font-size: 1px }
    """.trimIndent()

    private fun indentOf(s: String): Int = s.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4L else 1L }.toInt()

    /** Cells of a table row; `|` inside inline code or escaped as `\|` does not split. */
    internal fun tableCells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var inCode = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> { cur.append('|'); i++ }
                c == '`' -> { inCode = !inCode; cur.append(c) }
                c == '|' && !inCode -> { cells += cur.toString().trim(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        cells += cur.toString().trim()
        return cells
    }

    private class Renderer(private val lines: List<String>) {
        private val sb = StringBuilder()
        /** Open lists, innermost last: tag ("ul"/"ol") and the indentation of their items. */
        private val lists = ArrayDeque<Pair<String, Int>>()
        private var i = 0

        fun render(): String {
            while (i < lines.size) {
                val line = lines[i]
                val bullet = BULLET.matchEntire(line)
                val numbered = NUMBERED.matchEntire(line)
                val heading = HEADING.matchEntire(line)
                when {
                    HR.matches(line) -> { closeLists(); block(RULE) }
                    bullet != null -> item("ul", indentOf(line), bullet.groupValues[1], null)
                    numbered != null -> item("ol", indentOf(line), numbered.groupValues[2], numbered.groupValues[1].toIntOrNull() ?: 1)
                    line.isBlank() -> blank()
                    heading != null -> {
                        closeLists()
                        val level = minOf(heading.groupValues[1].length, 4)
                        block("<h$level>${inline(heading.groupValues[2])}</h$level>")
                    }
                    QUOTE.containsMatchIn(line) -> { closeLists(); quote() }
                    isTableStart() -> { closeLists(); table() }
                    lists.isNotEmpty() && indentOf(line) > 0 -> sb.append("<br>").append(inline(line.trim()))
                    else -> { closeLists(); sb.append(inline(line)).append("<br>") }
                }
                i++
            }
            closeLists()
            return sb.toString().replace(Regex("(<br>){3,}"), "<br><br>").removeSuffix("<br>")
        }

        /** Block elements bring their own spacing: drop the line break right before them. */
        private fun block(html: String) {
            if (sb.endsWith("<br>")) sb.setLength(sb.length - 4)
            sb.append(html)
        }

        private fun endsWithBlock() = sb.isEmpty() || BLOCK_END.containsMatchIn(sb.takeLast(16))

        private fun blank() {
            // A blank line inside a list keeps it open when the list continues right after.
            val next = lines.getOrNull(i + 1)
            if (lists.isNotEmpty() && next != null && (BULLET.matches(next) || NUMBERED.matches(next) || indentOf(next) > 0 && next.isNotBlank())) return
            closeLists()
            if (!endsWithBlock()) sb.append("<br>")
        }

        private fun item(tag: String, indent: Int, text: String, start: Int?) {
            while (lists.isNotEmpty() && lists.last().second > indent) closeList()
            val top = lists.lastOrNull()
            if (top != null && top.second == indent && top.first != tag) closeList()
            val same = lists.lastOrNull()
            if (same != null && same.second == indent) sb.append("</li>")
            else {
                // Keep the model's numbering: items separated by blank lines or code blocks would otherwise restart at 1.
                val open = if (start != null) "<ol start=\"$start\">" else "<ul>"
                if (lists.isEmpty()) block(open) else sb.append(open)
                lists.addLast(tag to indent)
            }
            sb.append("<li>").append(inline(text))
        }

        private fun closeList() { sb.append("</li></${lists.removeLast().first}>") }
        private fun closeLists() { while (lists.isNotEmpty()) closeList() }

        private fun quote() {
            val inner = mutableListOf<String>()
            while (i < lines.size && QUOTE.containsMatchIn(lines[i])) { inner += QUOTE.replaceFirst(lines[i], ""); i++ }
            i--
            block("<blockquote>${Renderer(inner).render()}</blockquote>")
        }

        private fun isTableStart(): Boolean =
            lines[i].contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) &&
                tableCells(lines[i]).size == tableCells(lines[i + 1]).size

        private fun table() {
            val header = tableCells(lines[i])
            val align = tableCells(lines[i + 1]).map {
                when {
                    it.startsWith(":") && it.endsWith(":") -> " align=\"center\""
                    it.endsWith(":") -> " align=\"right\""
                    else -> ""
                }
            }
            val out = StringBuilder("<table cellspacing=\"0\" cellpadding=\"3\">")
            fun row(cells: List<String>, td: String) {
                out.append("<tr>")
                for (c in header.indices) out.append("<$td${align[c]}>").append(inline(cells.getOrElse(c) { "" })).append("</$td>")
                out.append("</tr>")
            }
            row(header, "th")
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { row(tableCells(lines[i]), "td"); i++ }
            i--
            block(out.append("</table>").toString())
        }
    }

    private val HR = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
    private val QUOTE = Regex("^ {0,3}> ?")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val BLOCK_END = Regex("</(h\\d|ul|ol|li|table|blockquote|div)>$")

    /** Swing draws `<hr>` as a heavy dark bevel; a bordered empty block gives a thin line in the theme colour. */
    private const val RULE = "<div class=\"hr\"></div>"
}
