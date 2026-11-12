package com.undici77.aitoolset.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ProviderType(val key: String) {
    OPENAI_COMPAT("openai_compat"),
    ANTHROPIC("anthropic");

    companion object {
        fun fromKey(key: String): ProviderType = entries.firstOrNull { it.key == key } ?: OPENAI_COMPAT
    }
}

enum class Role { SYSTEM, USER, ASSISTANT }

/** [apiContent], when set, is what is sent to the API instead of [content] (which is what the UI shows). */
data class ChatMessage(val role: Role, val content: String, val apiContent: String? = null)

data class ChatRequest(
    val model: String,
    val system: String?,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null, // null = let the server decide (Anthropic: fallback in AnthropicClient)
    val reasoningEffort: String? = null, // blank/null = server default; sent verbatim as reasoning_effort
)

data class Endpoint(
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String?,
    val extraHeaders: Map<String, String> = emptyMap(),
    val extraBody: kotlinx.serialization.json.JsonObject? = null, // merged into every request body
)

class LlmException(message: String, val status: Int? = null) : Exception(message)

/** Exception for a non-2xx answer: the server's own error text when [body] is JSON, else the raw body (cut). */
fun httpError(code: Int, body: String): LlmException =
    LlmException("HTTP $code: ${errorMessageOf(body) ?: body.trim().take(2000)}", code)

/**
 * Error text from a JSON error body: `error.message` (OpenAI, Anthropic), `error` as a string (Ollama,
 * llama.cpp), `message`, or `detail` (vLLM / FastAPI, also a list of `{msg}`). Null when none is found.
 */
internal fun errorMessageOf(body: String): String? {
    val obj = runCatching { Json.parseToJsonElement(body.trim()) }.getOrNull() as? JsonObject ?: return null
    fun text(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        is JsonObject -> text(e["message"]) ?: text(e["msg"])
        is JsonArray -> e.mapNotNull { text(it) }.joinToString("; ").ifEmpty { null }
        else -> null
    }
    return text(obj["error"]) ?: text(obj["message"]) ?: text(obj["detail"])
}
