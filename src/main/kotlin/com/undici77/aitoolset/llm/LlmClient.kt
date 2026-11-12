package com.undici77.aitoolset.llm

import java.net.http.HttpClient
import java.time.Duration

interface LlmClient {
    /**
     * Streams [StreamEvent]s; a leading `<think>` block in the text is reported as reasoning.
     * Throws [LlmException] on HTTP/protocol error. Cancellable via coroutine cancellation.
     */
    suspend fun streamEvents(req: ChatRequest, onEvent: suspend (StreamEvent) -> Unit)

    suspend fun listModels(): List<String>
}

/** Answer text only (no reasoning, no status). */
suspend fun LlmClient.stream(req: ChatRequest, onDelta: suspend (String) -> Unit) =
    streamEvents(req) { if (it is StreamEvent.Text) onDelta(it.text) }

object LlmClients {
    // HTTP/1.1 is forced because some local servers handle the h2c upgrade badly.
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun create(ep: Endpoint): LlmClient = when (ep.type) {
        ProviderType.OPENAI_COMPAT -> OpenAiCompatClient(http, ep)
        ProviderType.ANTHROPIC -> AnthropicClient(http, ep)
    }
}
