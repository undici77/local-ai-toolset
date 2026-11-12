package com.undici77.aitoolset.llm

enum class FinishReason { STOP, LENGTH, UNKNOWN }

/** What a streaming call reports, in order: [Connected], any mix of [Reasoning]/[Text], then [Finished]. */
sealed interface StreamEvent {
    /** The server accepted the request (HTTP 2xx); the model may still be loading or reading the prompt. */
    data object Connected : StreamEvent
    data class Reasoning(val text: String) : StreamEvent
    data class Text(val text: String) : StreamEvent
    /** [outputTokens] is null when the server does not report usage. */
    data class Finished(val reason: FinishReason, val outputTokens: Int?) : StreamEvent
}

/**
 * Moves a leading `<think>…</think>` block found in the answer text into [StreamEvent.Reasoning].
 * Works when the tags are split across deltas. Only a block that starts before any visible text is
 * treated as reasoning, so an answer that talks about `<think>` is left alone.
 */
class ThinkTagFilter(private val out: suspend (StreamEvent) -> Unit) {
    private enum class Mode { START, THINK, ANSWER }

    private var mode = Mode.START
    private val pending = StringBuilder()
    /** After `</think>`, whitespace is dropped until the answer starts. */
    private var trimLeading = false
    var sawText = false
        private set

    suspend fun text(delta: String) {
        if (mode == Mode.ANSWER) { emitText(delta); return }
        pending.append(delta)
        while (true) {
            when (mode) {
                Mode.START -> {
                    val s = pending.trimStart()
                    when {
                        s.isEmpty() -> return
                        s.startsWith(OPEN) -> { pending.setLength(0); pending.append(s.substring(OPEN.length)); mode = Mode.THINK }
                        OPEN.startsWith(s) -> return // could still become "<think>"
                        else -> { mode = Mode.ANSWER; flushText(); return }
                    }
                }
                Mode.THINK -> {
                    val idx = pending.indexOf(CLOSE)
                    if (idx >= 0) {
                        if (idx > 0) out(StreamEvent.Reasoning(pending.substring(0, idx)))
                        val rest = pending.substring(idx + CLOSE.length)
                        pending.setLength(0)
                        mode = Mode.ANSWER
                        trimLeading = true
                        emitText(rest)
                        return
                    }
                    // Keep a tail that could be the start of "</think>".
                    val keep = (CLOSE.length - 1 downTo 1).firstOrNull { pending.endsWith(CLOSE.substring(0, it)) } ?: 0
                    val emit = pending.length - keep
                    if (emit > 0) { out(StreamEvent.Reasoning(pending.substring(0, emit))); pending.delete(0, emit) }
                    return
                }
                Mode.ANSWER -> { flushText(); return }
            }
        }
    }

    suspend fun reasoning(delta: String) { if (delta.isNotEmpty()) out(StreamEvent.Reasoning(delta)) }

    /** Call at end of stream: text held back while deciding is released. */
    suspend fun finish() {
        when (mode) {
            Mode.START -> if (pending.isNotBlank()) flushText()
            Mode.THINK -> if (pending.isNotEmpty()) out(StreamEvent.Reasoning(pending.toString()))
            Mode.ANSWER -> {}
        }
        pending.setLength(0)
    }

    private suspend fun flushText() {
        val s = pending.toString(); pending.setLength(0)
        if (s.isNotEmpty()) emitText(s)
    }

    private suspend fun emitText(raw: String) {
        val s = if (trimLeading) raw.trimStart() else raw
        if (s.isEmpty()) return
        trimLeading = false
        sawText = true
        out(StreamEvent.Text(s))
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
