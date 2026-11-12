package com.undici77.aitoolset.prompt

object TemplateEngine {
    private val NAME = Regex("[a-z][a-z0-9_]*")
    private val MANY_NEWLINES = Regex("\n{3,}")

    fun render(template: String, vars: Map<String, String>): String =
        MANY_NEWLINES.replace(renderInner(template, vars), "\n\n")

    private fun renderInner(t: String, vars: Map<String, String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c != '{') { sb.append(c); i++; continue }
            if (i + 1 < t.length && t[i + 1] == '?') {
                var j = i + 2
                while (j < t.length && (t[j].isLowerCase() || t[j].isDigit() || t[j] == '_')) j++
                val name = t.substring(i + 2, j)
                if (name.isNotEmpty() && NAME.matches(name) && j < t.length && t[j] == ':') {
                    var depth = 1
                    var k = j + 1
                    while (k < t.length && depth > 0) {
                        if (t[k] == '{') depth++ else if (t[k] == '}') depth--
                        k++
                    }
                    if (depth == 0) {
                        if (!vars[name].isNullOrBlank()) {
                            sb.append(renderInner(t.substring(j + 1, k - 1), vars))
                        }
                        i = k
                        continue
                    }
                    // unterminated: leave as-is
                    sb.append(c); i++; continue
                }
            } else {
                val end = t.indexOf('}', i + 1)
                if (end > 0) {
                    val name = t.substring(i + 1, end)
                    if (NAME.matches(name) && vars.containsKey(name)) {
                        sb.append(vars[name]); i = end + 1; continue
                    }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    fun ensureContains(template: String, varName: String, suffix: String): String =
        if (template.contains("{$varName}")) template else template + suffix
}
