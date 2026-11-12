package com.undici77.aitoolset.prompt

object OutputCleaner {
    private val THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val OPEN_THINK = Regex("^\\s*<think>.*", RegexOption.DOT_MATCHES_ALL)
    private val FENCE = Regex("^```[^\\n]*\\n(.*?)\\n?```$", RegexOption.DOT_MATCHES_ALL)
    private val LABEL = Regex("^commit message:\\s*", RegexOption.IGNORE_CASE)

    fun cleanCommitMessage(raw: String): String {
        var s = THINK.replace(raw, "")
        s = OPEN_THINK.replace(s, "").trim()
        FENCE.matchEntire(s)?.let { s = it.groupValues[1].trim() }
        if (s.length >= 2 && s.first() == s.last() && s.first() in "\"'`") s = s.substring(1, s.length - 1)
        s = LABEL.replace(s.trim(), "")
        return s.lines().joinToString("\n") { it.trimEnd() }.trim()
    }

    private val OPEN_FENCE = Regex("^```[^\\n]*(\\n|$)")
    private val CLOSING_FENCE = Regex("\\n?`{1,3}\\s*$")

    /**
     * Best-effort cleaning of a partial answer while it streams: drops an opening code fence (and its
     * language), a closing fence that is arriving, a "Commit message:" label and a leading double quote.
     * The final text still goes through [cleanCommitMessage].
     */
    fun cleanCommitMessagePartial(raw: String): String {
        var s = THINK.replace(raw, "")
        s = OPEN_THINK.replace(s, "").trimStart()
        if (s.startsWith("```")) s = OPEN_FENCE.replaceFirst(s, "")
        if (raw.trimStart().startsWith("```")) s = CLOSING_FENCE.replace(s, "")
        s = LABEL.replace(s.trimStart(), "")
        if (s.startsWith("\"")) s = s.substring(1)
        return s.lines().joinToString("\n") { it.trimEnd() }.trim()
    }
}
