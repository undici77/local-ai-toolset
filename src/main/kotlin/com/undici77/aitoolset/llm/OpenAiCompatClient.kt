package com.undici77.aitoolset.llm

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class OpenAiCompatClient(private val http: HttpClient, private val ep: Endpoint) : LlmClient {
    private val base = ep.baseUrl.trimEnd('/')

    private fun builder(url: String): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
        if (!ep.apiKey.isNullOrBlank()) b.header("Authorization", "Bearer ${ep.apiKey}")
        ep.extraHeaders.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    override suspend fun streamEvents(req: ChatRequest, onEvent: suspend (StreamEvent) -> Unit) {
        val host = URI.create(base).host
        val body = buildJsonObject {
            put("model", req.model)
            put("stream", true)
            put("messages", buildJsonArray {
                if (!req.system.isNullOrEmpty()) add(buildJsonObject { put("role", "system"); put("content", req.system) })
                for (m in req.messages) add(buildJsonObject {
                    put("role", m.role.name.lowercase())
                    put("content", m.apiContent ?: m.content)
                })
            })
            req.temperature?.let { put("temperature", it) }
            req.maxTokens?.let { put(if (host == "api.openai.com") "max_completion_tokens" else "max_tokens", it) }
            req.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { put("reasoning_effort", it) }
        }.let { b -> JsonObject(b.toMutableMap().apply { putAll(ep.extraBody.orEmpty()) }) }
        val filter = ThinkTagFilter(onEvent)
        var sawReasoning = false
        var finish = FinishReason.UNKNOWN
        var outputTokens: Int? = null
        val request = builder("$base/chat/completions")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        http.streamSse(request, onOpen = { onEvent(StreamEvent.Connected) }) { ev ->
            if (ev.data == "[DONE]") return@streamSse false
            val obj = Json.parseToJsonElement(ev.data).jsonObject
            obj["error"]?.let { err ->
                val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
                throw LlmException(msg)
            }
            (obj["usage"] as? JsonObject)?.get("completion_tokens")?.let { (it as? JsonPrimitive)?.intOrNull }?.let { outputTokens = it }
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            when ((choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull) {
                null -> {}
                "length" -> finish = FinishReason.LENGTH
                else -> finish = FinishReason.STOP
            }
            val delta = choice?.get("delta") as? JsonObject
            // Thinking models stream their reasoning in a separate field; it is reported, never part of the answer.
            val reasoning = ((delta?.get("reasoning_content") ?: delta?.get("reasoning")) as? JsonPrimitive)?.contentOrNull
            if (!reasoning.isNullOrEmpty()) { sawReasoning = true; filter.reasoning(reasoning) }
            val text = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
            if (!text.isNullOrEmpty()) filter.text(text)
            true
        }
        filter.finish()
        if (sawReasoning && !filter.sawText) {
            throw LlmException("The model used all its tokens thinking and returned no answer. Lower the reasoning effort or raise max output tokens.")
        }
        onEvent(StreamEvent.Finished(finish, outputTokens))
    }

    override suspend fun listModels(): List<String> {
        val resp = http.sendAsync(builder("$base/models").GET().build(), HttpResponse.BodyHandlers.ofString()).await()
        if (resp.statusCode() !in 200..299) throw httpError(resp.statusCode(), resp.body())
        return parseModelIds(resp.body())
    }
}

internal fun parseModelIds(body: String): List<String> =
    (Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList()))
        .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.sorted()
