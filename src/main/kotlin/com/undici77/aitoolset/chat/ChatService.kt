package com.undici77.aitoolset.chat

import com.undici77.aitoolset.config.AiToolsetSettings
import com.undici77.aitoolset.config.EffectiveFeature
import com.undici77.aitoolset.config.FeatureId
import com.undici77.aitoolset.config.FeatureResolver
import com.undici77.aitoolset.config.ProjectOverrides
import com.undici77.aitoolset.core.AiScope
import com.undici77.aitoolset.core.Notifier
import com.undici77.aitoolset.llm.ChatMessage
import com.undici77.aitoolset.llm.ChatRequest
import com.undici77.aitoolset.llm.Endpoint
import com.undici77.aitoolset.llm.FinishReason
import com.undici77.aitoolset.llm.StreamEvent
import com.undici77.aitoolset.llm.LlmClients
import com.undici77.aitoolset.llm.Role
import com.undici77.aitoolset.prompt.TemplateEngine
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.ContainerUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) {
    /** All callbacks run on the EDT. Run callbacks carry the id given by [runStarted]. */
    interface Listener {
        fun contextChanged() {}
        fun messageAdded(m: ChatMessage) {}
        fun runStarted(runId: Long, phase: RunPhase) {}
        /** The context actually sent with the run's user message (chat only, when files are attached). */
        fun contextSent(runId: Long, summary: ContextSummary) {}
        fun runPhase(runId: Long, phase: RunPhase) {}
        fun reasoningDelta(runId: Long, text: String) {}
        fun textDelta(runId: Long, text: String) {}
        fun runEnded(runId: Long, outcome: RunOutcome) {}
        fun prefill(text: String) {}
        fun runningChanged() {}
        fun cleared() {}
    }

    /** EDT-only access. */
    val context = mutableListOf<ContextItem>()
    /** USER/ASSISTANT only. EDT-only access. */
    val history = mutableListOf<ChatMessage>()
    /** (providerId, model) chosen in the panel's combo box; null = feature config. */
    var modelOverride: Pair<String, String>? = null

    private var lastFeature = FeatureId.CHAT
    private var lastVars: Map<String, String> = emptyMap()
    private var lastLoadVars: (suspend () -> Map<String, String>)? = null
    private var lastPreparing: String? = null

    private val listeners = ContainerUtil.createLockFreeCopyOnWriteList<Listener>()
    @Volatile private var job: Job? = null
    /** Id of the run whose events are still delivered; 0 = none. EDT-only. */
    private var activeRun = 0L
    private var runSeq = 0L
    val isRunning: Boolean get() = job?.isActive == true

    fun addListener(l: Listener, parent: Disposable) {
        listeners.add(l)
        Disposer.register(parent) { listeners.remove(l) }
    }

    private fun fire(f: Listener.() -> Unit) = listeners.forEach { it.f() }

    fun addContext(items: List<ContextItem>) {
        var changed = false
        for (i in items) if (context.none { it.file == i.file && it.lines == i.lines }) { context.add(i); changed = true }
        if (changed) fire { contextChanged() }
    }

    fun removeContext(item: ContextItem) {
        if (context.remove(item)) fire { contextChanged() }
    }

    /** Drops the conversation. A run in progress is cancelled and its late events are ignored. */
    fun clear() {
        activeRun = 0
        job?.cancel()
        job = null
        history.clear()
        fire { cleared() }
        fire { contextChanged() }
        fire { runningChanged() }
    }

    fun prefill(text: String) = fire { prefill(text) }

    fun stop() { job?.cancel() }

    /** Stops the current run, if any, and returns once it has ended (bubble finished, history updated). */
    suspend fun stopAndWait() { job?.let { it.cancel(); it.join() } }

    /** Re-sends the last user message after an error: drops it from history and sends it again. */
    fun retryLast() {
        if (isRunning) return
        val last = history.lastOrNull() ?: return
        if (last.role != Role.USER) return
        history.removeAt(history.size - 1)
        send(last.content, lastFeature, lastVars, last.apiContent, lastLoadVars, lastPreparing)
    }

    /** Asks the model to go on after an answer was cut off by the token limit. */
    fun continueAnswer() = send("Continue exactly where you stopped. Do not repeat what you already wrote.")

    /**
     * [loadVars], when given, runs off the EDT as the first step of the run (e.g. reading a commit), so the
     * answer bubble shows [preparing] at once and a failure is reported in the chat with Retry.
     */
    fun send(
        userText: String,
        feature: FeatureId = FeatureId.CHAT,
        vars: Map<String, String> = emptyMap(),
        apiContent: String? = null,
        loadVars: (suspend () -> Map<String, String>)? = null,
        preparing: String? = null,
    ) {
        if (isRunning) return
        lastFeature = feature
        lastVars = vars
        lastLoadVars = loadVars
        lastPreparing = preparing
        val userMsg = ChatMessage(Role.USER, userText, apiContent)
        history.add(userMsg)
        fire { messageAdded(userMsg) }
        val snapshotContext = if (feature == FeatureId.CHAT) context.toList() else emptyList()
        val snapshotHistory = history.toList()
        val override = modelOverride
        val runId = ++runSeq
        activeRun = runId
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        var providerId: String? = null
        var model = ""
        var firstOutputMs: Long? = null
        var phase: RunPhase = RunPhase.Preparing(
            preparing ?: if (snapshotContext.isEmpty()) "Preparing request…"
            else "Reading ${snapshotContext.size} file${if (snapshotContext.size == 1) "" else "s"}…",
        )
        val acc = StringBuilder()
        var reasoningChars = 0
        var reportedTokens: Int? = null
        fun stats() = RunStats(
            model, ms(), firstOutputMs,
            reportedTokens ?: ((acc.length + reasoningChars + 3) / 4), reportedTokens == null,
        )
        /** Delivers [f] on the EDT, only while this run is still the active one. */
        suspend fun onEdt(f: () -> Unit) = withContext(Dispatchers.EDT) { if (activeRun == runId) f() }
        suspend fun enter(p: RunPhase) {
            if (p == phase) return
            phase = p
            onEdt { fire { runPhase(runId, p) } }
        }
        /** Ends the run: records the partial/complete answer, clears the job, notifies. */
        fun finishOnEdt(keepAnswer: Boolean, outcome: RunOutcome) {
            if (activeRun != runId) return
            if (keepAnswer && acc.isNotEmpty()) history.add(ChatMessage(Role.ASSISTANT, acc.toString()))
            job = null
            activeRun = 0
            fire { runEnded(runId, outcome) }
            fire { runningChanged() }
        }
        fire { runStarted(runId, phase) }
        job = AiScope.get(project).cs.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val settings = AiToolsetSettings.get()
                    var eff: EffectiveFeature = FeatureResolver.resolve(
                        settings.feature(feature), ProjectOverrides.of(project)?.read(feature), settings.state.providers,
                    )
                    if (override != null && feature == FeatureId.CHAT) {
                        val p = settings.provider(override.first)
                        if (p != null) eff = eff.copy(provider = p, endpointType = com.undici77.aitoolset.llm.ProviderType.fromKey(p.type), model = override.second)
                    }
                    providerId = eff.provider.id
                    model = eff.model
                    val (ctx, summary) = if (feature == FeatureId.CHAT) buildContext(snapshotContext, eff.maxContextChars) else "" to null
                    val allVars = vars + loadVars?.invoke().orEmpty() + ("context" to ctx)
                    val system = TemplateEngine.render(eff.systemPrompt, allVars)
                    val messages = snapshotHistory.toMutableList()
                    val lastIdx = messages.lastIndex
                    var renderedApi: String? = null
                    if (eff.userTemplate.isNotBlank() && lastIdx >= 0 && messages[lastIdx].role == Role.USER) {
                        val rendered = TemplateEngine.render(eff.userTemplate, allVars + ("input" to userText))
                        messages[lastIdx] = messages[lastIdx].copy(apiContent = rendered)
                        renderedApi = rendered
                    }
                    Prepared(
                        ChatRequest(eff.model, system.ifBlank { null }, messages, eff.temperature, eff.maxTokens, eff.reasoningEffort),
                        FeatureResolver.endpoint(eff.provider), renderedApi, eff.provider.name.ifBlank { eff.provider.id }, summary,
                    )
                }
                prepared.renderedApi?.let { api ->
                    // Keep the rendered content in history so follow-up questions still see it (e.g. the commit diff).
                    withContext(Dispatchers.EDT) {
                        val idx = history.indexOf(userMsg)
                        if (idx >= 0) history[idx] = userMsg.copy(apiContent = api)
                    }
                }
                prepared.summary?.let { s -> onEdt { fire { contextSent(runId, s) } } }
                enter(RunPhase.Connecting(prepared.providerName))
                var finish = FinishReason.UNKNOWN
                LlmClients.create(prepared.endpoint).streamEvents(prepared.request) { ev ->
                    when (ev) {
                        StreamEvent.Connected -> enter(RunPhase.Waiting(model))
                        is StreamEvent.Reasoning -> {
                            if (firstOutputMs == null) firstOutputMs = ms()
                            reasoningChars += ev.text.length
                            enter(RunPhase.Thinking)
                            onEdt { fire { reasoningDelta(runId, ev.text) } }
                        }
                        is StreamEvent.Text -> {
                            if (firstOutputMs == null) firstOutputMs = ms()
                            acc.append(ev.text)
                            enter(RunPhase.Writing)
                            onEdt { fire { textDelta(runId, ev.text) } }
                        }
                        is StreamEvent.Finished -> { finish = ev.reason; reportedTokens = ev.outputTokens }
                    }
                }
                val st = stats()
                withContext(Dispatchers.EDT) {
                    finishOnEdt(true, if (finish == FinishReason.LENGTH) RunOutcome.Truncated(st) else RunOutcome.Done(st))
                }
            } catch (e: CancellationException) {
                val st = if (model.isEmpty()) null else stats()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.EDT) { finishOnEdt(true, RunOutcome.Stopped(st)) }
                throw e
            } catch (e: Throwable) {
                val msg = Notifier.describe(e, providerId)
                val st = if (model.isEmpty()) null else stats()
                // A failed partial answer is not kept: Retry re-sends the question.
                withContext(Dispatchers.EDT) { finishOnEdt(false, RunOutcome.Failed(msg, Notifier.isSettingsFixable(e), st)) }
            }
        }
        fire { runningChanged() }
    }

    private class Prepared(
        val request: ChatRequest, val endpoint: Endpoint, val renderedApi: String?, val providerName: String, val summary: ContextSummary?,
    )

    /** Reads files off the EDT. Items that do not fit the budget are replaced by an "omitted" line. */
    private suspend fun buildContext(items: List<ContextItem>, budget: Int): Pair<String, ContextSummary?> {
        if (items.isEmpty()) return "" to null
        val sb = StringBuilder()
        val sent = mutableListOf<String>()
        val omitted = mutableListOf<String>()
        for (item in items) {
            val block = readAction { render(item) } ?: continue
            if (sb.length + block.length > budget) { sb.append("[omitted: ${item.label}, too large]\n"); omitted += item.label }
            else { sb.append(block); sent += item.label }
        }
        val text = sb.toString().trimEnd()
        return text to ContextSummary(sent, omitted, text.length).takeIf { sent.isNotEmpty() || omitted.isNotEmpty() }
    }

    private fun render(item: ContextItem): String? {
        val f: VirtualFile = item.file
        if (!f.isValid) return null
        val text = runCatching { VfsUtilCore.loadText(f) }.getOrNull() ?: return null
        val body = item.lines?.let { r ->
            val lines = text.lines()
            lines.subList((r.first - 1).coerceIn(0, lines.size), r.last.coerceIn(0, lines.size)).joinToString("\n")
        } ?: text
        val rel = ProjectFileIndex.getInstance(project).getContentRootForFile(f)?.let { VfsUtilCore.getRelativePath(f, it) } ?: f.name
        val range = item.lines?.let { " (lines ${it.first}-${it.last})" }.orEmpty()
        return "## $rel$range\n```${f.extension.orEmpty()}\n$body\n```\n"
    }
}
