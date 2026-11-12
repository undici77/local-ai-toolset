package com.undici77.aitoolset.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.coroutines.coroutineContext

/** One SSE event: `event:` name (nullable) and the joined `data:` payload. */
data class SseEvent(val event: String?, val data: String)

/** Feed lines one at a time; returns a completed event when a blank line terminates it. */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (data.isEmpty() && event == null) return null
            val ev = SseEvent(event, data.toString())
            event = null
            data.setLength(0)
            return ev
        }
        if (line.startsWith(":")) return null // comment
        val idx = line.indexOf(':')
        val field = if (idx < 0) line else line.substring(0, idx)
        var value = if (idx < 0) "" else line.substring(idx + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }

    fun flush(): SseEvent? = feed("")
}

/** [onOpen] runs once the server answered 2xx. [onEvent] returns false to stop reading. */
suspend fun HttpClient.streamSse(request: HttpRequest, onOpen: suspend () -> Unit = {}, onEvent: suspend (SseEvent) -> Boolean) {
    val resp = sendAsync(request, HttpResponse.BodyHandlers.ofLines()).await()
    val code = resp.statusCode()
    val stream = resp.body()
    if (code !in 200..299) {
        val body = withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            stream.use { s ->
                val it = s.iterator()
                while (it.hasNext() && sb.length < 2000) sb.append(it.next()).append('\n')
            }
            sb.toString().take(2000).trim()
        }
        throw httpError(code, body)
    }
    val handle = coroutineContext.job.invokeOnCompletion { stream.close() }
    try {
        onOpen()
        withContext(Dispatchers.IO) {
            val parser = SseParser()
            val it = stream.iterator()
            while (it.hasNext()) {
                val line = it.next()
                ensureActive()
                if (line.startsWith("{")) { // NDJSON without the `data:` prefix
                    if (!onEvent(SseEvent(null, line))) return@withContext
                    continue
                }
                val ev = parser.feed(line)
                if (ev != null && !onEvent(ev)) return@withContext
            }
            parser.flush()?.let { onEvent(it) }
        }
    } finally {
        handle.dispose()
        stream.close()
    }
}
