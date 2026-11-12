package com.undici77.aitoolset.chat

import com.intellij.icons.AllIcons
import com.undici77.aitoolset.Icons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColorUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.text.DefaultCaret
import javax.swing.text.JTextComponent
import com.intellij.openapi.editor.ex.EditorEx
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.SwingConstants
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLDocument

/**
 * One chat message. An assistant message has, top to bottom: an optional collapsed reasoning section,
 * the answer body (plain text while streaming, Markdown once rendered), a status row while the run is
 * active, and a footer or error row when it ends.
 */
class MessageView(private val project: Project, val isUser: Boolean) : JPanel(VerticalLayout(JBUI.scale(6))) {
    private val gap = JBUI.scale(6)
    /** Answer parts that will not change any more. */
    private val stableBody = JPanel(VerticalLayout(gap)).apply { isOpaque = false }
    /** The still-growing end of a streaming answer; updated in place on each [flush]. */
    private val tailBody = JPanel(VerticalLayout(gap)).apply { isOpaque = false }
    private var tailParts: List<Pair<Segment, JComponent>> = emptyList()
    private val text = StringBuilder()
    /** Characters of [text] already rendered into [stableBody]. */
    private var renderedUpTo = 0
    private var textDirty = false
    private val pendingReasoning = StringBuilder()
    val currentText: String get() = text.toString()

    private var reasoningArea: JBTextArea? = null

    private val statusLabel = JBLabel("", AnimatedIcon.Default.INSTANCE, SwingConstants.LEFT).apply { foreground = UIUtil.getContextHelpForeground() }
    private val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(statusLabel); isVisible = false }
    private var phase: RunPhase? = null
    private var runStartNanos = 0L

    /** "You" above a user bubble; the model name (with the AI icon) above an answer. */
    private val header = JBLabel(if (isUser) "You" else "Assistant").apply {
        font = JBUI.Fonts.smallFont().asBold()
        foreground = UIUtil.getContextHelpForeground()
        if (isUser) horizontalAlignment = SwingConstants.RIGHT else icon = Icons.Sparkle
    }
    /**
     * Rounded bubble holding the message. User: accent-tinted, on the right, as wide as the text needs.
     * Answer: neutral tint, full width, also holding reasoning, status, warnings and errors (the stats footer stays below).
     */
    private val bubble: JPanel = if (isUser) Bubble(USER_BUBBLE_BACKGROUND, JBUI.Borders.empty(6, 12)) else Bubble(ANSWER_BUBBLE_BACKGROUND, JBUI.Borders.empty(8, 12))

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
        val body = JPanel(VerticalLayout(gap)).apply { isOpaque = false; add(stableBody); add(tailBody) }
        add(header)
        bubble.add(body)
        if (isUser) add(BubbleRow(bubble) { naturalBubbleWidth() })
        else {
            bubble.add(statusRow)
            add(bubble)
        }
    }

    /** Shows [model] in the answer header (known once the request is prepared). */
    fun setModel(model: String) { if (!isUser && model.isNotBlank()) header.text = model }

    /** Width the bubble needs to show its text without wrapping. */
    private fun naturalBubbleWidth(): Int {
        val b = bubble
        val fm = getFontMetrics(UIUtil.getLabelFont())
        val textWidth = text.lines().maxOfOrNull { fm.stringWidth(it.replace("\t", "    ")) } ?: 0
        val notes = b.components.filterIsInstance<JBLabel>().maxOfOrNull { it.preferredSize.width } ?: 0
        val insets = b.insets
        return maxOf(textWidth, notes) + insets.left + insets.right + JBUI.scale(4)
    }

    private fun plainArea(initial: String) = JBTextArea(initial).apply {
        lineWrap = true; wrapStyleWord = true; isEditable = false; isOpaque = false
        border = JBUI.Borders.empty(); font = UIUtil.getLabelFont()
        hideCaret()
    }

    private fun relayout() { revalidate(); repaint() }

    private fun resetBody() {
        stableBody.removeAll(); tailBody.removeAll(); tailParts = emptyList()
        text.setLength(0); renderedUpTo = 0; textDirty = false
    }

    fun setPlain(s: String) {
        resetBody(); text.append(s)
        stableBody.add(plainArea(s)); relayout()
    }

    /** Small grey line under a user message, e.g. the context that was sent with it. */
    fun addNote(note: String, tooltip: String? = null) {
        val label = JBLabel(note).apply { foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont(); toolTipText = tooltip }
        bubble.add(label); relayout()
    }

    /** Buffers [delta]; nothing is laid out until [flush]. */
    fun appendStreaming(delta: String) { text.append(delta); textDirty = true }

    /** Buffers [delta]; nothing is laid out until [flush]. */
    fun appendReasoning(delta: String) { pendingReasoning.append(delta) }

    /**
     * Applies buffered deltas in one layout pass: finished blocks (paragraphs, closed code fences) are
     * rendered once into the stable part, the open end is rendered as Markdown and updated in place.
     * Returns true when the layout changed. Called by the panel at a fixed frame rate while streaming.
     */
    fun flush(): Boolean {
        var changed = false
        if (pendingReasoning.isNotEmpty()) {
            val area = ensureReasoning()
            area.append(pendingReasoning.toString()); pendingReasoning.setLength(0)
            changed = area.parent?.isVisible == true
        }
        if (textDirty) { textDirty = false; renderProgress(final = false); changed = true }
        if (changed) relayout()
        return changed
    }

    /** Renders a complete answer (history replay). Same block structure as a streamed answer. */
    fun render(markdown: String) {
        resetBody(); text.append(markdown)
        renderProgress(final = true)
        relayout()
    }

    private fun renderProgress(final: Boolean) {
        val rest = text.substring(renderedUpTo)
        if (final) {
            // Reuse the tail components where they already show the right thing: no flash at the end.
            updateTail(rest)
            for ((_, c) in tailParts) stableBody.add(c)
            tailBody.removeAll(); tailParts = emptyList()
            renderedUpTo = text.length
            return
        }
        val cut = MarkdownLite.stableLength(rest)
        if (cut > 0) {
            tailBody.removeAll(); tailParts = emptyList()
            for (seg in MarkdownLite.split(rest.substring(0, cut))) stableBody.add(segmentView(seg))
            renderedUpTo += cut
        }
        updateTail(rest.substring(cut))
    }

    private fun updateTail(md: String) {
        val segs = MarkdownLite.split(md)
        val sameShape = segs.size == tailParts.size && segs.indices.all { i ->
            val old = tailParts[i].first
            val new = segs[i]
            (old is Segment.Text && new is Segment.Text) || (old is Segment.Code && new is Segment.Code && old.lang == new.lang)
        }
        if (sameShape) {
            tailParts = segs.mapIndexed { i, seg ->
                val c = tailParts[i].second
                if (seg != tailParts[i].first) when (seg) {
                    is Segment.Text -> (c as JEditorPane).text = htmlDoc(MarkdownLite.toHtml(seg.md))
                    is Segment.Code -> (c as CodeBlock).setCode(seg.code)
                }
                seg to c
            }
        } else {
            tailBody.removeAll()
            tailParts = segs.map { it to segmentView(it).also(tailBody::add) }
        }
    }

    private fun segmentView(seg: Segment): JComponent = when (seg) {
        is Segment.Text -> htmlPane(MarkdownLite.toHtml(seg.md))
        is Segment.Code -> CodeBlock(seg.lang, seg.code)
    }

    private fun ensureReasoning(): JBTextArea = reasoningArea ?: run {
        val a = plainArea("").apply { foreground = UIUtil.getContextHelpForeground() }
        val content = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(JBUI.Borders.customLineLeft(JBColor.border()), JBUI.Borders.emptyLeft(8))
            add(a, BorderLayout.CENTER)
            isVisible = false
        }
        val toggle = ActionLink("Show reasoning") {
            content.isVisible = !content.isVisible
            (it.source as ActionLink).text = if (content.isVisible) "Hide reasoning" else "Show reasoning"
            relayout()
        }
        bubble.add(JPanel(VerticalLayout(JBUI.scale(4))).apply { isOpaque = false; add(toggle); add(content) }, 0) // above the answer
        reasoningArea = a
        a
    }

    // ---- run status ----

    fun startRun(p: RunPhase) {
        runStartNanos = System.nanoTime()
        statusRow.isVisible = true
        setPhase(p)
    }

    fun setPhase(p: RunPhase) {
        phase = p
        if (p is RunPhase.Waiting) setModel(p.model)
        tick()
    }

    /** Refreshes the status text (elapsed time). Called by the panel's timer while the run is active. */
    fun tick() {
        val p = phase ?: return
        val secs = RunStats.secs((System.nanoTime() - runStartNanos) / 1_000_000)
        statusLabel.text = when (p) {
            is RunPhase.Preparing -> p.detail
            is RunPhase.Connecting -> "Connecting to ${p.provider}…"
            is RunPhase.Waiting -> {
                val waited = (System.nanoTime() - runStartNanos) / 1_000_000_000
                "Waiting for ${p.model.ifBlank { "the model" }} · $secs" +
                    if (waited >= 5) " (loading the model or reading the prompt)" else ""
            }
            RunPhase.Thinking -> "Thinking · $secs"
            RunPhase.Writing -> "Writing · $secs"
        }
    }

    /** Ends the run: renders the final answer and shows the footer, warning or error. */
    fun endRun(outcome: RunOutcome, onRetry: () -> Unit, onContinue: () -> Unit, onSettings: () -> Unit) {
        phase = null
        statusRow.isVisible = false
        flush()
        renderProgress(final = true)
        outcome.stats?.model?.let(::setModel)
        // The model name is in the header already.
        val stats = outcome.stats?.summary(includeModel = false)
        when (outcome) {
            is RunOutcome.Done -> addFooter(stats)
            is RunOutcome.Truncated -> {
                addRow(warning("Cut off: the max output tokens limit was reached."), ActionLink("Continue") { onContinue() })
                addFooter(stats)
            }
            is RunOutcome.Stopped -> addFooter(listOfNotNull("Stopped", stats).joinToString(" · "))
            is RunOutcome.Failed -> {
                val links = mutableListOf<JComponent>(ActionLink("Retry") { onRetry() })
                if (outcome.offerSettings) links += ActionLink("Open Settings") { onSettings() }
                bubble.add(errorText(outcome.message))
                addRow(*links.toTypedArray())
            }
        }
        relayout()
    }

    private fun addFooter(s: String?) {
        if (s.isNullOrBlank()) return
        add(JBLabel(s).apply { foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont() })
    }

    private fun warning(s: String) = JBLabel(s, AllIcons.General.Warning, SwingConstants.LEFT)

    /** Wrapped, selectable error text; long bodies (e.g. a raw HTTP error) are cut with an expander. */
    private fun errorText(message: String): JComponent {
        val short = message.lineSequence().first().let { if (it.length > 300) it.take(300) + "…" else it }
        val area = plainArea(short).apply { foreground = JBColor.RED }
        val panel = JPanel(BorderLayout()).apply { isOpaque = false; add(area, BorderLayout.CENTER) }
        if (short != message) {
            panel.add(ActionLink("Details") {
                area.text = if (area.text == short) message else short
                (it.source as ActionLink).text = if (area.text == short) "Details" else "Less"
                relayout()
            }, BorderLayout.SOUTH)
        }
        return panel
    }

    private fun addRow(vararg items: JComponent) {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        items.forEach { row.add(it) }
        bubble.add(row)
    }

    private fun htmlDoc(html: String) = "<html><body>$html</body></html>"

    private fun htmlPane(html: String): JComponent = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        isEditable = false; isOpaque = false; border = JBUI.Borders.empty(); font = UIUtil.getLabelFont()
        // Per-document rules (the kit's own sheet is shared); setText keeps this document.
        (document as? HTMLDocument)?.styleSheet?.addRule(
            MarkdownLite.css(ColorUtil.toHtmlColor(JBColor.border()), ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground()))
        )
        text = htmlDoc(html)
        hideCaret()
        addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) e.url?.let { BrowserUtil.browse(it.toString()) } }
    }

    /** Read-only highlighted code with Copy / Insert at Caret. The text can be replaced while streaming. */
    private inner class CodeBlock(lang: String, code: String) : JPanel(BorderLayout()) {
        private val editor: EditorTextField

        init {
            isOpaque = false
            val status = JBLabel("").apply { foreground = JBColor.GRAY }
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(langToExt(lang))
                .takeIf { it !is com.intellij.openapi.fileTypes.UnknownFileType } ?: PlainTextFileType.INSTANCE
            editor = object : EditorTextField(code, project, fileType) {
                // The horizontal scrollbar is drawn inside the editor's height: reserve room so it never covers the last line.
                override fun getPreferredSize(): Dimension {
                    val d = super.getPreferredSize()
                    val ex = editor as? EditorEx ?: return d
                    val bar = ex.scrollPane.horizontalScrollBar
                    if (width > 0 && ex.contentComponent.preferredSize.width > width) d.height += bar.preferredSize.height
                    return d
                }
            }.apply {
                setOneLineMode(false)
                isViewer = true
                addSettingsProvider {
                    it.settings.isLineNumbersShown = false
                    it.setHorizontalScrollbarVisible(true)
                    it.setVerticalScrollbarVisible(false)
                }
            }
            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JBLabel(lang.ifBlank { "code" }).apply { foreground = JBColor.GRAY }, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
                    isOpaque = false
                    add(status)
                    add(ActionLink("Copy") { CopyPasteManager.getInstance().setContents(StringSelection(editor.text)) })
                    add(ActionLink("Insert at Caret") { insertAtCaret(editor.text, status) })
                }, BorderLayout.EAST)
            }
            add(header, BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
        }

        fun setCode(code: String) { if (editor.text != code) editor.text = code }
    }

    private fun insertAtCaret(code: String, status: JBLabel) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) { status.text = "No editor open"; return }
        WriteCommandAction.runWriteCommandAction(project, "Insert AI Code", null, {
            val sel = editor.selectionModel
            if (sel.hasSelection()) editor.document.replaceString(sel.selectionStart, sel.selectionEnd, code)
            else editor.document.insertString(editor.caretModel.offset, code)
        })
        status.text = ""
    }

    companion object {
        /**
         * Read-only text still gets a caret when focused, painted as a dark bar on the first letter.
         * This caret never paints and never follows appended text; selection and copy keep working.
         */
        private fun JTextComponent.hideCaret() {
            val blink = caret?.blinkRate ?: 0
            caret = object : DefaultCaret() {
                override fun paint(g: Graphics?) {}
                override fun isVisible() = false
            }.apply { updatePolicy = DefaultCaret.NEVER_UPDATE; blinkRate = blink }
        }

        fun langToExt(lang: String): String = when (lang.lowercase()) {
            "cpp", "c++", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "h", "hpp" -> "h"
            "cmake" -> "cmake"
            "py", "python" -> "py"
            "sh", "bash", "shell", "zsh" -> "sh"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "kotlin", "kt" -> "kt"
            "java" -> "java"
            "js", "javascript" -> "js"
            "ts", "typescript" -> "ts"
            "rust", "rs" -> "rs"
            "go" -> "go"
            "xml" -> "xml"
            "md", "markdown" -> "md"
            else -> "txt"
        }
    }
}

private val BUBBLE_ARC get() = JBUI.scale(14)

/** User bubble: accent tint that follows the theme (mix of panel background and link colour). */
private val USER_BUBBLE_BACKGROUND = JBColor.lazy {
    ColorUtil.mix(UIUtil.getPanelBackground(), JBUI.CurrentTheme.Link.Foreground.ENABLED, 0.14)
}

/** Answer bubble: neutral tint, a little darker (light theme) or lighter (dark theme) than the panel. */
private val ANSWER_BUBBLE_BACKGROUND = JBColor.lazy {
    ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.05)
}

/** Rounded, filled panel for one message. */
private class Bubble(private val fill: java.awt.Color, padding: javax.swing.border.Border) : JPanel(VerticalLayout(JBUI.scale(6))) {
    init {
        isOpaque = false
        border = padding
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            g2.fillRoundRect(0, 0, width, height, BUBBLE_ARC, BUBBLE_ARC)
        } finally { g2.dispose() }
    }
}

/**
 * Right-aligns [bubble]: it is as wide as its text needs ([naturalWidth]), at most 85% of the row,
 * and wraps beyond that. The left gap is computed from the row width at each layout.
 */
private class BubbleRow(bubble: JComponent, private val naturalWidth: () -> Int) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        add(object : JComponent() {
            override fun getPreferredSize(): Dimension {
                val avail = this@BubbleRow.width.takeIf { it > 0 } ?: return Dimension(0, 0)
                val minGap = (avail * 0.15).toInt()
                return Dimension(maxOf(minGap, avail - naturalWidth()), 0)
            }
        }, BorderLayout.WEST)
        add(bubble, BorderLayout.CENTER)
    }
}

