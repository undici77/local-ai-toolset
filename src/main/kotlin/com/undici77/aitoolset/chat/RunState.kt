package com.undici77.aitoolset.chat

/** What the model is doing right now, for one chat run. */
sealed interface RunPhase {
    data class Preparing(val detail: String) : RunPhase
    data class Connecting(val provider: String) : RunPhase
    /** Request accepted, no output yet: the server is loading the model or reading the prompt. */
    data class Waiting(val model: String) : RunPhase
    data object Thinking : RunPhase
    data object Writing : RunPhase
}

/**
 * [outputTokens] comes from the server when it reports usage; otherwise it is estimated from the
 * streamed characters and [tokensEstimated] is true. Times are in milliseconds from the start of the run.
 */
data class RunStats(
    val model: String,
    val elapsedMs: Long,
    val firstOutputMs: Long?,
    val outputTokens: Int,
    val tokensEstimated: Boolean,
) {
    fun summary(includeModel: Boolean = true): String {
        val parts = mutableListOf<String>()
        if (includeModel && model.isNotBlank()) parts += model
        parts += secs(elapsedMs)
        if (outputTokens > 0) {
            parts += (if (tokensEstimated) "~" else "") + "$outputTokens tok"
            val genMs = elapsedMs - (firstOutputMs ?: 0)
            if (genMs >= 200) parts += "%.0f tok/s".format(outputTokens * 1000.0 / genMs)
        }
        return parts.joinToString(" · ")
    }

    companion object {
        fun secs(ms: Long) = if (ms < 10_000) "%.1f s".format(ms / 1000.0) else "${ms / 1000} s"
    }
}

sealed interface RunOutcome {
    val stats: RunStats?

    data class Done(override val stats: RunStats) : RunOutcome
    /** The answer was cut off by the output token limit. */
    data class Truncated(override val stats: RunStats) : RunOutcome
    data class Stopped(override val stats: RunStats?) : RunOutcome
    /** [offerSettings]: the error is likely fixed in Settings (configuration, connection, authentication). */
    data class Failed(val message: String, val offerSettings: Boolean, override val stats: RunStats?) : RunOutcome
}
