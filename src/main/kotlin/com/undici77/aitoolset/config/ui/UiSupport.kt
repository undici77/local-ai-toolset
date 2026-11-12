package com.undici77.aitoolset.config.ui

import com.undici77.aitoolset.config.FeatureResolver
import com.undici77.aitoolset.config.ProviderState
import com.undici77.aitoolset.config.Secrets
import com.undici77.aitoolset.llm.ChatMessage
import com.undici77.aitoolset.llm.ChatRequest
import com.undici77.aitoolset.llm.Endpoint
import com.undici77.aitoolset.llm.LlmClients
import com.undici77.aitoolset.llm.stream
import com.undici77.aitoolset.llm.ProviderType
import com.undici77.aitoolset.llm.Role
import com.undici77.aitoolset.core.Notifier
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.DocumentAdapter
import kotlinx.coroutines.runBlocking
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

internal fun JTextComponent.onChange(f: () -> Unit) {
    document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = f()
    })
}

/** Shared, mutable working copy of the providers list (applied on Apply). */
class ProvidersModel {
    val items = mutableListOf<ProviderState>()
    /** providerId -> new key (null = clear). Written to [Secrets] on Apply. */
    val pendingKeys = mutableMapOf<String, String?>()
    val removedIds = mutableSetOf<String>()
    private val listeners = mutableListOf<() -> Unit>()

    private val addedListeners = mutableListOf<(ProviderState) -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun addAddedListener(l: (ProviderState) -> Unit) { addedListeners += l }
    fun fireAdded(p: ProviderState) = addedListeners.forEach { it(p) }
    fun fire() = listeners.forEach { it() }
}

/** Background helpers used by the settings page. Everything here is user-initiated. */
internal object UiLlm {
    private fun <T> async(work: () -> T, done: (Result<T>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = runCatching(work)
            ApplicationManager.getApplication().invokeLater({ done(r) }, ModalityState.any())
        }
    }

    private fun endpoint(p: ProviderState, model: ProvidersModel): Endpoint {
        val key = if (model.pendingKeys.containsKey(p.id)) model.pendingKeys[p.id] else Secrets.get(p.id)
        return Endpoint(ProviderType.fromKey(p.type), p.baseUrl, key, FeatureResolver.parseHeaders(p.extraHeaders), FeatureResolver.parseExtraBody(p))
    }

    fun fetchModels(p: ProviderState, model: ProvidersModel, done: (Result<List<String>>) -> Unit) {
        val copy = p.copy()
        async({ runBlocking { LlmClients.create(endpoint(copy, model)).listModels() } }, done)
    }

    fun testConnection(p: ProviderState, modelName: String, model: ProvidersModel, done: (String) -> Unit) {
        val copy = p.copy()
        val t0 = System.nanoTime()
        async({
            val sb = StringBuilder()
            runBlocking {
                LlmClients.create(endpoint(copy, model)).stream(
                    ChatRequest(modelName, null, listOf(ChatMessage(Role.USER, "Reply with: OK")), maxTokens = 16),
                ) { sb.append(it) }
            }
            sb.toString()
        }) { r ->
            val ms = (System.nanoTime() - t0) / 1_000_000
            done(
                r.fold(
                    { "✓ OK (${ms} ms): ${it.trim().take(40)}" },
                    { "✗ " + Notifier.describe(it, copy.name.ifBlank { copy.id }, copy.baseUrl) },
                ),
            )
        }
    }
}
