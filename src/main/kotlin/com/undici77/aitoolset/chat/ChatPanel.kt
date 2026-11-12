package com.undici77.aitoolset.chat

import com.undici77.aitoolset.config.AiToolsetSettings
import com.undici77.aitoolset.config.FeatureId
import com.undici77.aitoolset.config.FeatureResolver
import com.undici77.aitoolset.config.ProjectOverrides
import com.undici77.aitoolset.core.AiScope
import com.undici77.aitoolset.llm.ChatMessage
import com.undici77.aitoolset.llm.Role
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.TransferHandler

private data class ModelChoice(val providerId: String, val providerName: String, val model: String) {
    override fun toString() = "$providerName · $model"
}

class ChatPanel(private val project: Project, parent: Disposable) :
    SimpleToolWindowPanel(true, true), ChatService.Listener, Disposable {

    private val svc = project.service<ChatService>()
    private val messages = JPanel(VerticalLayout(JBUI.scale(14))).apply { border = JBUI.Borders.empty(8) }
    private val scroll = JBScrollPane(WidthTrackingPanel().apply { add(messages, BorderLayout.NORTH) })
    private val hint = JBLabel("<html><center>Ask anything. Add files with right-click → Add to AI Chat, or drag them here.</center></html>", javax.swing.SwingConstants.CENTER)
        .apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(24) }
    private val chips = JPanel(WrapLayout(FlowLayout.LEFT, 4, 2))
    private val tokens = JBLabel("").apply { foreground = JBColor.GRAY }
    private val input = JBTextArea(3, 20).apply { lineWrap = true; wrapStyleWord = true }
    private val modelBox = ComboBox<ModelChoice>()
    private val sendBtn = JButton("Send")

    /** Assistant bubble of the active run, and that run's id. */
    private var runView: MessageView? = null
    private var runId = 0L
    private var lastUserView: MessageView? = null
    /** While a run is active: applies buffered deltas and refreshes the status once per frame (~20 fps). */
    private val ticker = javax.swing.Timer(FRAME_MS) { frame() }
    private var ignoreModelEvents = false

    init {
        val inputScroll = JBScrollPane(input).apply { preferredSize = java.awt.Dimension(0, JBUI.scale(70)) }
        val controls = JPanel(BorderLayout(6, 0)).apply { add(modelBox, BorderLayout.CENTER); add(sendBtn, BorderLayout.EAST) }
        val bottom = JPanel(VerticalLayout(JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(6)
            add(chips); add(inputScroll); add(controls)
        }
        val center = JPanel(BorderLayout()).apply { add(scroll, BorderLayout.CENTER); add(bottom, BorderLayout.SOUTH) }
        setContent(center)
        scroll.border = JBUI.Borders.empty()
        scroll.horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        showHint()
        rebuildChips()
        rebuildModels()
        sendBtn.addActionListener { if (svc.isRunning) svc.stop() else send() }
        modelBox.addActionListener {
            if (ignoreModelEvents) return@addActionListener
            (modelBox.selectedItem as? ModelChoice)?.let { svc.modelOverride = it.providerId to it.model }
        }
        bindKeys()
        installDropHandler()
        svc.addListener(this, this)
        Disposer_register(parent, this)
        // Replay history in case the panel was recreated.
        svc.history.forEach { replay(it) }
    }

    private fun Disposer_register(parent: Disposable, child: Disposable) =
        com.intellij.openapi.util.Disposer.register(parent, child)

    override fun addNotify() {
        super.addNotify()
        rebuildModels() // pick up settings changes; reads settings only, never secrets
        applyEnterMode() // the option may have been changed from another project's chat
    }

    override fun dispose() { ticker.stop() }

    // ---- input ----

    private fun send() {
        val t = input.text.trim()
        if (t.isEmpty() || svc.isRunning) return
        input.text = ""
        svc.send(t)
        input.requestFocusInWindow()
    }

    private fun bindKeys() {
        val im = input.inputMap; val am = input.actionMap
        val sendAction = object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = send() }
        am.put("ai-send", sendAction)
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "ai-send")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), "ai-send")
        am.put("ai-stop", object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) { if (svc.isRunning) svc.stop() } })
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "ai-stop")
        am.put("ai-new-line", object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = input.replaceSelection("\n") })
        applyEnterMode()
    }

    private val enterSends get() = AiToolsetSettings.get().state.enterSends
    private val sendKeyHint get() = if (enterSends) "Enter" else "Ctrl+Enter"

    /** Binds Enter / Shift+Enter in the input from the "Enter to send" option. Ctrl/Cmd+Enter always sends. */
    fun applyEnterMode() {
        val im = input.inputMap
        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
        if (enterSends) {
            im.put(enter, "ai-send")
            im.put(shiftEnter, "ai-new-line")
            input.emptyText.text = "Ask… (Enter to send, Shift+Enter for a new line)"
        } else {
            // Removing the bindings falls back to the text area's default: insert a new line.
            im.remove(enter)
            im.remove(shiftEnter)
            input.emptyText.text = "Ask… (Ctrl+Enter to send)"
        }
        runningChanged()
        input.repaint()
    }

    private fun rebuildModels() {
        val settings = AiToolsetSettings.get()
        val choices = mutableListOf<ModelChoice>()
        for (p in settings.state.providers) if (p.defaultModel.isNotBlank()) {
            choices += ModelChoice(p.id, p.name.ifBlank { p.id }, p.defaultModel)
        }
        val resolved = runCatching {
            FeatureResolver.resolve(settings.feature(FeatureId.CHAT), ProjectOverrides.of(project)?.read(FeatureId.CHAT), settings.state.providers)
        }.getOrNull()
        if (resolved != null) {
            val c = ModelChoice(resolved.provider.id, resolved.provider.name.ifBlank { resolved.provider.id }, resolved.model)
            if (choices.none { it == c }) choices.add(0, c)
        }
        ignoreModelEvents = true
        try {
            modelBox.model = DefaultComboBoxModel(choices.toTypedArray())
            val ov = svc.modelOverride
            val selected = if (ov != null) choices.firstOrNull { it.providerId == ov.first && it.model == ov.second } else null
            val pick = selected ?: resolved?.let { r -> choices.firstOrNull { it.providerId == r.provider.id && it.model == r.model } }
            if (pick != null) modelBox.selectedItem = pick
        } finally { ignoreModelEvents = false }
        modelBox.isEnabled = choices.isNotEmpty() && !svc.isRunning
        if (choices.isEmpty()) modelBox.toolTipText = "No provider configured. Open Settings → Tools → Local AI Toolset."
    }

    // ---- context chips ----

    private fun rebuildChips() {
        chips.removeAll()
        for (item in svc.context) {
            val chip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                border = javax.swing.BorderFactory.createCompoundBorder(RoundedLineBorder(JBColor.border(), 10), JBUI.Borders.empty(1, 4))
                add(JBLabel(item.label, item.file.fileType.icon, javax.swing.SwingConstants.LEFT))
                add(ActionLink("×") { svc.removeContext(item) })
            }
            chips.add(chip)
        }
        chips.add(ActionLink("+ Add current file") { addCurrentFile() })
        chips.add(tokens)
        chips.revalidate(); chips.repaint()
        updateTokens()
    }

    private fun addCurrentFile() {
        val f = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        svc.addContext(listOf(ContextItem(f)))
    }

    private fun updateTokens() {
        val items = svc.context.toList()
        if (items.isEmpty()) { tokens.text = ""; return }
        AiScope.get(project).cs.launch(Dispatchers.Default) {
            val chars = readAction {
                items.sumOf { i ->
                    if (!i.file.isValid) 0L else {
                        val len = runCatching { VfsUtilCore.loadText(i.file) }.getOrDefault("")
                        (i.lines?.let { r -> len.lines().drop(r.first - 1).take(r.count()).sumOf { it.length + 1 } } ?: len.length).toLong()
                    }
                }
            }
            withContext(Dispatchers.EDT) { tokens.text = "~${chars / 4} tokens" }
        }
    }

    // ---- drag & drop ----

    private fun installDropHandler() {
        transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

            @Suppress("UNCHECKED_CAST")
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val files = runCatching { support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull() ?: return false
                val out = mutableListOf<ContextItem>()
                for (f in files) {
                    val vf = LocalFileSystem.getInstance().findFileByIoFile(f) ?: continue
                    if (vf.isDirectory) vf.children.filter { !it.isDirectory && !it.fileType.isBinary }.take(20).forEach { out += ContextItem(it) }
                    else if (!vf.fileType.isBinary) out += ContextItem(vf)
                }
                svc.addContext(out)
                return out.isNotEmpty()
            }
        }
        for (c in listOf<JComponent>(messages, input, scroll, chips)) c.transferHandler = transferHandler
    }

    // ---- listener ----

    private fun showHint() { messages.removeAll(); messages.add(hint); messages.revalidate(); messages.repaint() }

    private fun isAtBottom(): Boolean {
        val sb = scroll.verticalScrollBar
        return sb.value + sb.visibleAmount >= sb.maximum - JBUI.scale(24)
    }

    /** Lays out now and scrolls in the same pass, so the content never shows a frame at the old position. */
    private fun scrollToBottomIf(stick: Boolean) {
        if (!stick) return
        scroll.validate()
        scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
    }

    private fun addView(v: MessageView) {
        val stick = isAtBottom() || v.isUser
        if (hint.parent === messages) messages.remove(hint)
        messages.add(v); messages.revalidate(); messages.repaint()
        scrollToBottomIf(stick)
    }

    private fun replay(m: ChatMessage) {
        val v = MessageView(project, m.role == Role.USER)
        if (m.role == Role.USER) v.setPlain(m.content) else v.render(m.content)
        addView(v)
    }

    override fun contextChanged() = rebuildChips()

    override fun cleared() {
        runView = null; runId = 0; lastUserView = null
        ticker.stop()
        showHint()
    }

    override fun messageAdded(m: ChatMessage) {
        if (m.role != Role.USER) return
        val v = MessageView(project, true).also { it.setPlain(m.content) }
        lastUserView = v
        addView(v)
    }

    override fun runStarted(runId: Long, phase: RunPhase) {
        this.runId = runId
        val v = MessageView(project, false)
        // Best guess until the run reports the model it actually uses (features may use another one).
        (modelBox.selectedItem as? ModelChoice)?.let { v.setModel(it.model) }
        runView = v
        v.startRun(phase)
        addView(v)
        ticker.start()
    }

    private fun activeView(id: Long): MessageView? = if (id == runId) runView else null

    override fun contextSent(runId: Long, summary: ContextSummary) {
        if (runId == this.runId) lastUserView?.addNote(summary.text, summary.tooltip)
    }

    override fun runPhase(runId: Long, phase: RunPhase) { activeView(runId)?.setPhase(phase) }

    override fun reasoningDelta(runId: Long, text: String) { activeView(runId)?.appendReasoning(text) }

    override fun textDelta(runId: Long, text: String) { activeView(runId)?.appendStreaming(text) }

    private fun frame() {
        val v = runView ?: return
        val stick = isAtBottom()
        v.tick()
        if (v.flush()) scrollToBottomIf(stick)
    }

    override fun runEnded(runId: Long, outcome: RunOutcome) {
        val v = activeView(runId) ?: return
        val user = lastUserView
        val stick = isAtBottom()
        ticker.stop()
        runView = null
        v.endRun(
            outcome,
            onRetry = { retry(user, v) },
            onContinue = { if (!svc.isRunning) svc.continueAnswer() },
            onSettings = {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, com.undici77.aitoolset.config.ui.AiToolsetConfigurable::class.java)
            },
        )
        scrollToBottomIf(stick)
    }

    /** Replaces the failed exchange (question + error bubble) with a new attempt. Only for the latest exchange. */
    private fun retry(user: MessageView?, failed: MessageView) {
        if (svc.isRunning) return
        val n = messages.componentCount
        if (n == 0 || messages.getComponent(n - 1) !== failed) return
        messages.remove(failed)
        user?.let { messages.remove(it) }
        messages.revalidate(); messages.repaint()
        svc.retryLast()
    }

    override fun runningChanged() {
        sendBtn.text = if (svc.isRunning) "Stop" else "Send"
        sendBtn.icon = if (svc.isRunning) AllIcons.Actions.Suspend else null
        sendBtn.toolTipText = if (svc.isRunning) "Stop generating (Esc)" else "Send ($sendKeyHint)"
        // The model is chosen per run: switching it mid-answer would not apply and only confuse.
        modelBox.isEnabled = !svc.isRunning && modelBox.itemCount > 0
    }

    override fun prefill(text: String) {
        input.text = text
        input.caretPosition = input.document.length
        input.requestFocusInWindow()
    }
}

/** Scroll view that always matches the viewport width, so text wraps instead of being clipped on resize. */
private class WidthTrackingPanel : JPanel(BorderLayout()), javax.swing.Scrollable {
    override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: java.awt.Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(r: java.awt.Rectangle, orientation: Int, direction: Int) =
        if (orientation == javax.swing.SwingConstants.VERTICAL) r.height else r.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

private const val FRAME_MS = 50
