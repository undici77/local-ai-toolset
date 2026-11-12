package com.undici77.aitoolset.chat

import com.intellij.openapi.vfs.VirtualFile

/** [lines] is 1-based inclusive; null = whole file. */
data class ContextItem(val file: VirtualFile, val lines: IntRange? = null) {
    val label: String get() = file.name + (lines?.let { ":${it.first}-${it.last}" } ?: "")
}

/** What was actually sent with a chat message: [files] included, [omitted] left out as too large, [chars] of context text. */
data class ContextSummary(val files: List<String>, val omitted: List<String>, val chars: Int) {
    /** e.g. "3 files · ~2.1k tokens · 1 omitted (too large)". */
    val text: String get() {
        val parts = mutableListOf<String>()
        if (files.isNotEmpty()) parts += "${plural(files.size, "file")} · ~${approxTokens(chars)} tokens"
        if (omitted.isNotEmpty()) parts += "${omitted.size} omitted (too large)"
        return parts.joinToString(" · ")
    }

    val tooltip: String get() = buildString {
        append("<html>")
        if (files.isNotEmpty()) append("Sent: ").append(files.joinToString(", ") { MarkdownLite.escape(it) })
        if (omitted.isNotEmpty()) {
            if (files.isNotEmpty()) append("<br>")
            append("Omitted, over the context limit: ").append(omitted.joinToString(", ") { MarkdownLite.escape(it) })
        }
        append("</html>")
    }

    companion object {
        private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

        /** ~4 characters per token; "850", "2.1k", "12k". */
        fun approxTokens(chars: Int): String {
            val t = chars / 4
            return when {
                t < 1000 -> "$t"
                t < 10_000 -> String.format(java.util.Locale.ROOT, "%.1fk", t / 1000.0).replace(".0k", "k")
                else -> "${t / 1000}k"
            }
        }
    }
}
