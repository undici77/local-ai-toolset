package com.undici77.aitoolset.llm

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class AnthropicClient(private val http: HttpClient, private val ep: Endpoint) : LlmClient {
    private companion object {
        const val DEFAULT_MAX_TOKENS = 8192
        val EFFORTS = setOf("low", "medium", "high", "xhigh", "max") // other values (e.g. "none") are not valid for Anthropic: omitted
    }

    private val base = ep.baseUrl.trimEnd('/')

    private fun builder(url: String): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
            .header("x-api-key", ep.apiKey.orEmpty())
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
        ep.extraHeaders.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    override suspend fun streamEvents(req: ChatRequest, onEvent: suspend (StreamEvent) -> Unit) {
        val body = buildJsonObject {
            put("model", req.model)
            put("max_tokens", req.maxTokens ?: DEFAULT_MAX_TOKENS) // required by the API
            if (req.system != null) put("system", req.system)
            put("messages", buildJsonArray {
                for (m in req.messages) add(buildJsonObject {
                    put("role", m.role.name.lowercase())
                    put("content", m.apiContent ?: m.content)
                })
            })
            put("stream", true)
            req.temperature?.let { put("temperature", it) }
            req.reasoningEffort?.trim()?.takeIf { it in EFFORTS }?.let { e ->
                put("output_config", buildJsonObject { put("effort", e) })
            }
        }.let { b -> JsonObject(b.toMutableMap().apply { putAll(ep.extraBody.orEmpty()) }) }
        val request = builder("$base/v1/messages").header("accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val filter = ThinkTagFilter(onEvent)
        var finish = FinishReason.UNKNOWN
        var outputTokens: Int? = null
        http.streamSse(request, onOpen = { onEvent(StreamEvent.Connected) }) { ev ->
            val obj = Json.parseToJsonElement(ev.data).jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_delta" -> {
                    val delta = obj["delta"] as? JsonObject
                    when (delta?.get("type")?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) filter.text(it) }
                        "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { filter.reasoning(it) }
                    }
                    true
                }
                "message_delta" -> {
                    when ((obj["delta"] as? JsonObject)?.get("stop_reason")?.jsonPrimitive?.contentOrNull) {
                        null -> {}
                        "max_tokens" -> finish = FinishReason.LENGTH
                        else -> finish = FinishReason.STOP
                    }
                    (obj["usage"] as? JsonObject)?.get("output_tokens")?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
                    true
                }
                "message_stop" -> false
                "error" -> {
                    val msg = (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: obj.toString()
                    throw LlmException(msg)
                }
                else -> true
            }
        }
        filter.finish()
        onEvent(StreamEvent.Finished(finish, outputTokens))
    }

    override suspend fun listModels(): List<String> {
        val resp = http.sendAsync(builder("$base/v1/models").GET().build(), HttpResponse.BodyHandlers.ofString()).await()
        if (resp.statusCode() !in 200..299) throw httpError(resp.statusCode(), resp.body())
        return parseModelIds(resp.body())
    }
}
