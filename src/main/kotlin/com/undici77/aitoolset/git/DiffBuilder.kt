package com.undici77.aitoolset.git

data class DiffResult(val diff: String, val files: String, val truncated: Boolean)

object DiffBuilder {
    private val NOISE = Regex("""(^|/)(package-lock\.json|pnpm-lock\.yaml)$|\.(lock|min\.js|svg|map)$""")

    private class Chunk(val path: String, val status: String, val text: String)

    /** [rawDiffs]: git diff output (may be several concatenated). [untracked]: (relPath, contentOrNull). */
    fun build(rawDiffs: String, untracked: List<Pair<String, String?>>, budget: Int, perFileCap: Int = 6000): DiffResult {
        val chunks = mutableListOf<Chunk>()
        splitChunks(rawDiffs).forEach { chunks += parseChunk(it, perFileCap) }
        for ((path, content) in untracked) {
            val header = "new file (untracked): $path\n"
            val body = when {
                content == null || content.contains('\u0000') -> ""
                NOISE.containsMatchIn(path) -> "[content omitted]"
                else -> cap(content, perFileCap)
            }
            chunks += Chunk(path, "A $path", header + body)
        }
        val diff = StringBuilder()
        val omitted = mutableListOf<String>()
        for (c in chunks) {
            if (omitted.isEmpty() && diff.length + c.text.length <= budget) {
                if (diff.isNotEmpty() && !diff.endsWith("\n")) diff.append('\n')
                diff.append(c.text)
            } else omitted += c.path
        }
        if (omitted.isNotEmpty()) {
            if (diff.isNotEmpty() && !diff.endsWith("\n")) diff.append('\n')
            diff.append("[diff omitted for ${omitted.size} more files: ${omitted.joinToString(", ")}]")
        }
        return DiffResult(diff.toString(), chunks.joinToString("\n") { it.status }, omitted.isNotEmpty())
    }

    private fun cap(s: String, cap: Int) =
        if (s.length <= cap) s else s.take(cap) + "\n… [truncated ${s.length - cap} chars]"

    private fun splitChunks(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in raw.lineSequence()) {
            if (line.startsWith("diff --git ") && cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
            cur.append(line).append('\n')
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out.filter { it.startsWith("diff --git ") }
    }

    private fun parseChunk(text: String, perFileCap: Int): Chunk {
        val lines = text.lines()
        val head = lines.first().removePrefix("diff --git ")
        var path = head.substringAfter(" b/", head)
        var status = "M"
        var renameFrom: String? = null
        var renameTo: String? = null
        for (l in lines.drop(1)) {
            if (l.startsWith("@@")) break
            when {
                l.startsWith("new file mode") -> status = "A"
                l.startsWith("deleted file mode") -> status = "D"
                l.startsWith("rename from ") -> renameFrom = l.removePrefix("rename from ")
                l.startsWith("rename to ") -> renameTo = l.removePrefix("rename to ")
            }
        }
        if (renameFrom != null && renameTo != null) { status = "R"; path = renameTo }
        val statusLine = if (status == "R") "R $renameFrom -> $renameTo" else "$status $path"
        val body = if (NOISE.containsMatchIn(path)) {
            val h = lines.takeWhile { !it.startsWith("@@") }.joinToString("\n")
            "$h\n[content omitted]\n"
        } else cap(text, perFileCap)
        return Chunk(path, statusLine, body)
    }
}
