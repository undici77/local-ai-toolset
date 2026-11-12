package com.undici77.aitoolset.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class ClientsHttpTest {
    private lateinit var server: HttpServer
    private var lastBody = ""
    private var lastHeaders = mapOf<String, String>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    private fun reply(ex: HttpExchange, status: Int, body: String, type: String = "text/event-stream") {
        lastBody = ex.requestBody.readBytes().decodeToString()
        lastHeaders = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            reply(ex, 200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"!\"}}]}\n\n" +
                    "data: [DONE]\n\n")
        }
        server.createContext("/v1/messages") { ex ->
            reply(ex, 200,
                "event: message_start\ndata: {\"type\":\"message_start\"}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi \"}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"there\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }
        server.createContext("/unauth/chat/completions") { ex -> reply(ex, 401, "{\"error\":\"bad key\"}", "application/json") }
        server.createContext("/v1/models") { ex ->
            reply(ex, 200, "{\"data\":[{\"id\":\"b\"},{\"id\":\"a\"}]}", "application/json")
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun collect(client: LlmClient, req: ChatRequest): String = runBlocking {
        val sb = StringBuilder()
        client.stream(req) { sb.append(it) }
        sb.toString()
    }

    @Test fun openAiStreamsAndSendsExpectedRequest() {
        val client = LlmClients.create(Endpoint(ProviderType.OPENAI_COMPAT, "$base/v1", "sk-test"))
        val text = collect(client, ChatRequest("m", "SYS", listOf(ChatMessage(Role.USER, "hi")), 0.2, 99))
        assertEquals("Hello!", text)
        assertTrue(lastBody, lastBody.contains("\"stream\":true"))
        assertTrue(lastBody, lastBody.contains("\"role\":\"system\",\"content\":\"SYS\""))
        assertTrue(lastBody, lastBody.contains("\"max_tokens\":99"))
        assertTrue(lastBody, lastBody.contains("\"temperature\":0.2"))
        assertEquals("Bearer sk-test", lastHeaders["authorization"])
    }

    @Test fun openAiOmitsAuthWithoutKeyAndTemperatureWhenNull() {
        val client = LlmClients.create(Endpoint(ProviderType.OPENAI_COMPAT, "$base/v1", null))
        collect(client, ChatRequest("m", null, listOf(ChatMessage(Role.USER, "hi"))))
        assertTrue(!lastHeaders.containsKey("authorization"))
        assertTrue(!lastBody.contains("temperature"))
        assertTrue(!lastBody.contains("\"system\""))
    }

    @Test fun openAiSendsReasoningEffortAndMergesExtraBody() {
        val extra = kotlinx.serialization.json.Json.parseToJsonElement("{\"chat_template_kwargs\":{\"enable_thinking\":false}}")
            as kotlinx.serialization.json.JsonObject
        val client = LlmClients.create(Endpoint(ProviderType.OPENAI_COMPAT, "$base/v1", null, extraBody = extra))
        collect(client, ChatRequest("m", null, listOf(ChatMessage(Role.USER, "hi")), reasoningEffort = "low"))
        assertTrue(lastBody, lastBody.contains("\"reasoning_effort\":\"low\""))
        assertTrue(lastBody, lastBody.contains("\"chat_template_kwargs\":{\"enable_thinking\":false}"))
        collect(client, ChatRequest("m", null, listOf(ChatMessage(Role.USER, "hi")), reasoningEffort = ""))
        assertTrue(lastBody, !lastBody.contains("reasoning_effort"))
    }

    @Test fun openAiFailsClearlyWhenOnlyReasoningIsReturned() {
        server.createContext("/think/chat/completions") { ex ->
            reply(ex, 200, "data: {\"choices\":[{\"delta\":{\"reasoning\":\"hmm\"}}]}\n\ndata: [DONE]\n\n")
        }
        val client = LlmClients.create(Endpoint(ProviderType.OPENAI_COMPAT, "$base/think", null))
        try {
            collect(client, ChatRequest("m", null, listOf(ChatMessage(Role.USER, "hi"))))
            fail("expected exception")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("thinking"))
        }
    }

    @Test fun anthropicMapsEffortToOutputConfig() {
        val client = LlmClients.create(Endpoint(ProviderType.ANTHROPIC, base, "ak"))
        collect(client, ChatRequest("m", null, listOf(ChatMessage(Role.USER, "hi")), reasoningEffort = "low"))
        assertTrue(lastBody, lastBody.contains("\"output_config\":{\"effort\":\"low\"}"))
        collect(client, ChatRequest("m", null, listOf(ChatMessage(Role.USER, "hi")), reasoningEffort = "none"))
        assertTrue(lastBody, !lastBody.contains("output_config"))
    }

    @Test fun anthropicStreamsAndPlacesSystemSeparately() {
        val client = LlmClients.create(Endpoint(ProviderType.ANTHROPIC, base, "ak-test"))
        val text = collect(client, ChatRequest("m", "SYS", listOf(ChatMessage(Role.USER, "hi")), null, 50))
        assertEquals("Hi there", text)
        assertTrue(lastBody, lastBody.contains("\"system\":\"SYS\""))
        assertTrue(lastBody, lastBody.contains("\"stream\":true"))
        assertTrue(lastBody, lastBody.contains("\"max_tokens\":50"))
        assertEquals("ak-test", lastHeaders["x-api-key"])
        assertEquals("2023-06-01", lastHeaders["anthropic-version"])
    }

    private fun events(client: LlmClient, req: ChatRequest = ChatRequest("m", null, listOf(ChatMessage(Role.USER, "hi")))) =
        runBlocking { mutableListOf<StreamEvent>().also { l -> client.streamEvents(req) { l += it } } }

    @Test fun openAiReportsReasoningFinishReasonAndUsage() {
        server.createContext("/len/chat/completions") { ex ->
            reply(ex, 200,
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}],\"usage\":{\"completion_tokens\":7}}\n\n" +
                    "data: [DONE]\n\n")
        }
        val ev = events(LlmClients.create(Endpoint(ProviderType.OPENAI_COMPAT, "$base/len", null)))
        assertEquals(
            listOf(StreamEvent.Connected, StreamEvent.Reasoning("hmm"), StreamEvent.Text("Hi"), StreamEvent.Finished(FinishReason.LENGTH, 7)),
            ev,
        )
    }

    @Test fun openAiMovesThinkTagsToReasoning() {
        server.createContext("/tag/chat/completions") { ex ->
            reply(ex, 200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"<think>p\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"lan</think>Ok\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n")
        }
        val ev = events(LlmClients.create(Endpoint(ProviderType.OPENAI_COMPAT, "$base/tag", null)))
        assertEquals("plan", ev.filterIsInstance<StreamEvent.Reasoning>().joinToString("") { it.text })
        assertEquals("Ok", ev.filterIsInstance<StreamEvent.Text>().joinToString("") { it.text })
        assertEquals(StreamEvent.Finished(FinishReason.STOP, null), ev.last())
    }

    @Test fun anthropicReportsThinkingStopReasonAndUsage() {
        server.createContext("/a2/v1/messages") { ex ->
            reply(ex, 200,
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}\n\n" +
                    "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n" +
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":5}}\n\n" +
                    "data: {\"type\":\"message_stop\"}\n\n")
        }
        val ev = events(LlmClients.create(Endpoint(ProviderType.ANTHROPIC, "$base/a2", "k")))
        assertEquals(
            listOf(StreamEvent.Connected, StreamEvent.Reasoning("hmm"), StreamEvent.Text("Hi"), StreamEvent.Finished(FinishReason.LENGTH, 5)),
            ev,
        )
    }

    @Test fun http401Throws() {
        val client = LlmClients.create(Endpoint(ProviderType.OPENAI_COMPAT, "$base/unauth", "x"))
        try {
            collect(client, ChatRequest("m", null, listOf(ChatMessage(Role.USER, "hi"))))
            fail("expected exception")
        } catch (e: LlmException) {
            assertEquals(401, e.status)
            assertEquals("HTTP 401: bad key", e.message)
        }
    }

    @Test fun listModelsSorted() {
        val client = LlmClients.create(Endpoint(ProviderType.OPENAI_COMPAT, "$base/v1", null))
        assertEquals(listOf("a", "b"), runBlocking { client.listModels() })
    }

    @Test fun apiContentOverridesContent() {
        val client = LlmClients.create(Endpoint(ProviderType.OPENAI_COMPAT, "$base/v1", null))
        collect(client, ChatRequest("m", null, listOf(ChatMessage(Role.USER, "short", "FULL"))))
        assertTrue(lastBody, lastBody.contains("FULL") && !lastBody.contains("short"))
    }
}
