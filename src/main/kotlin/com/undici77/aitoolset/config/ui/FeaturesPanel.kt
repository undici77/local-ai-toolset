package com.undici77.aitoolset.config.ui

import com.undici77.aitoolset.config.AiToolsetSettings
import com.undici77.aitoolset.config.FeatureId
import com.undici77.aitoolset.config.FeatureOverride
import com.undici77.aitoolset.config.FeatureState
import com.undici77.aitoolset.config.ProjectOverrides
import com.undici77.aitoolset.config.Scope
import com.undici77.aitoolset.prompt.Defaults
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

/** Everything editable for one (feature, scope). */
internal data class FormValues(
    val providerId: String,
    val model: String,
    val temperature: String,
    val maxTokens: String,
    val maxContextChars: String,
    val reasoningEffort: String,
    val systemPrompt: String,
    val userTemplate: String,
)

private class ProviderItem(val id: String, val label: String) {
    override fun toString() = label
}

class FeaturesPanel(private val project: Project, private val providers: ProvidersModel) {
    private val featureBox = ComboBox(DefaultComboBoxModel(FeatureId.entries.toTypedArray()))
    private val globalRb = JRadioButton("Global")
    private val projectRb = JRadioButton("This project (.ai-toolset/)")
    private val effective = JBLabel("").apply { foreground = JBColor.GRAY }
    private val providerBox = ComboBox<ProviderItem>()
    private val modelBox = ComboBox<String>(DefaultComboBoxModel()).apply { isEditable = true }
    private val fetchBtn = JButton("…")
    private val fetchMsg = JBLabel("")
    private val temperature = JBTextField()
    private val reasoning = ComboBox(DefaultComboBoxModel(arrayOf("", "none", "low", "medium", "high", "xhigh"))).apply { isEditable = true }
    private val maxTokens = JBTextField()
    private val maxContext = JBTextField()
    private val system = JBTextArea(10, 60).apply { font = Font(Font.MONOSPACED, Font.PLAIN, font.size) }
    private val user = JBTextArea(6, 60).apply { font = Font(Font.MONOSPACED, Font.PLAIN, font.size) }
    private val help = JBLabel("").apply { foreground = JBColor.GRAY }
    private val removeLink = ActionLink("Remove project override") { markRemove() }
    private val resetLink = ActionLink("Reset to defaults") { resetToDefaults() }

    private val edits = mutableMapOf<Pair<FeatureId, Scope>, FormValues>()
    private val removals = mutableSetOf<FeatureId>()
    private var currentKey: Pair<FeatureId, Scope>? = null
    private var loading = false
    val component: JComponent

    private val feature get() = featureBox.selectedItem as FeatureId
    private val scope get() = if (projectRb.isSelected) Scope.PROJECT else Scope.GLOBAL

    private val labelOfProvider = mutableListOf<JComponent>()
    private lateinit var providerRow: JComponent
    private lateinit var modelRow: JComponent
    private lateinit var tempRow: JComponent
    private lateinit var reasoningRow: JComponent
    private lateinit var tokensRow: JComponent
    private lateinit var contextRow: JComponent
    private lateinit var systemRow: JComponent

    init {
        ButtonGroup().apply { add(globalRb); add(projectRb) }
        globalRb.isSelected = true
        projectRb.isEnabled = !project.isDefault && ProjectOverrides.of(project) != null

        val scopeRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { add(globalRb); add(projectRb) }
        val modelPanel = JPanel(BorderLayout(4, 0)).apply { add(modelBox, BorderLayout.CENTER); add(fetchBtn, BorderLayout.EAST) }
        val linksRow = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply { add(resetLink); add(removeLink) }

        providerRow = labeled("Provider:", providerBox)
        modelRow = labeled("Model:", modelPanel)
        tempRow = labeled("Temperature:", temperature)
        reasoningRow = labeled("Reasoning effort:", reasoning)
        tokensRow = labeled("Max output tokens:", maxTokens)
        contextRow = labeled("Context limit (chars):", maxContext)
        systemRow = JPanel(BorderLayout()).apply {
            add(JBLabel("System prompt:"), BorderLayout.NORTH); add(JBScrollPane(system), BorderLayout.CENTER)
        }
        val userRow = JPanel(BorderLayout()).apply {
            add(JBLabel("User template:"), BorderLayout.NORTH); add(JBScrollPane(user), BorderLayout.CENTER)
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Feature:", featureBox)
            .addLabeledComponent("Scope:", scopeRow)
            .addComponentToRightColumn(effective)
            .addComponent(providerRow).addComponent(modelRow).addComponent(fetchMsg)
            .addComponent(tempRow).addComponent(reasoningRow).addComponent(tokensRow).addComponent(contextRow)
            .addComponent(systemRow).addComponent(userRow)
            .addComponent(help)
            .addComponent(linksRow)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        component = JPanel(BorderLayout()).apply { add(JBScrollPane(form), BorderLayout.CENTER); border = JBUI.Borders.empty(8) }

        featureBox.addActionListener { switchTo() }
        globalRb.addActionListener { switchTo() }
        projectRb.addActionListener { switchTo() }
        fetchBtn.addActionListener { fetchModels() }
        providers.addListener { refreshProviderBox() }
        providers.addAddedListener { assignIfUnassigned(it.id) }
        refreshProviderBox()
        switchTo()
    }

    private fun labeled(text: String, c: JComponent): JComponent =
        JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel(text).apply { preferredSize = java.awt.Dimension(JBUI.scale(140), preferredSize.height) }, BorderLayout.WEST)
            add(c, BorderLayout.CENTER)
        }

    // ---- stored values ----

    private fun toForm(f: FeatureState) = FormValues(
        f.providerId, f.model, f.temperature, f.maxTokens, f.maxContextChars,
        f.reasoningEffort ?: FeatureId.fromKey(f.featureId)?.let { Defaults.reasoningEffort(it) }.orEmpty(),
        f.systemPrompt, f.userTemplate,
    )

    private fun loadStored(id: FeatureId, scope: Scope): FormValues {
        val g = AiToolsetSettings.get().feature(id)
        if (scope == Scope.GLOBAL) return toForm(g)
        val o = runCatching { ProjectOverrides.of(project)?.read(id) }.getOrNull()
        return FormValues(
            providerId = o?.providerId?.takeIf { it.isNotBlank() } ?: g.providerId,
            model = o?.model?.takeIf { it.isNotBlank() } ?: g.model,
            temperature = o?.temperature?.toString() ?: g.temperature,
            maxTokens = o?.maxTokens?.toString() ?: g.maxTokens,
            maxContextChars = o?.maxContextChars?.toString() ?: g.maxContextChars,
            reasoningEffort = o?.reasoningEffort ?: toForm(g).reasoningEffort,
            systemPrompt = o?.systemPrompt?.takeIf { it.isNotBlank() } ?: g.systemPrompt,
            userTemplate = o?.userTemplate?.takeIf { it.isNotBlank() } ?: g.userTemplate,
        )
    }

    private fun readForm() = FormValues(
        providerId = (providerBox.selectedItem as? ProviderItem)?.id.orEmpty(),
        model = (modelBox.editor.item as? String).orEmpty().trim(),
        temperature = temperature.text.trim(),
        maxTokens = maxTokens.text.trim(),
        maxContextChars = maxContext.text.trim(),
        reasoningEffort = (reasoning.editor.item as? String).orEmpty().trim(),
        systemPrompt = system.text,
        userTemplate = user.text,
    )

    private fun writeForm(v: FormValues) {
        loading = true
        try {
            selectProvider(v.providerId)
            modelBox.editor.item = v.model
            temperature.text = v.temperature
            maxTokens.text = v.maxTokens
            maxContext.text = v.maxContextChars
            reasoning.editor.item = v.reasoningEffort
            system.text = v.systemPrompt; system.caretPosition = 0
            user.text = v.userTemplate; user.caretPosition = 0
        } finally {
            loading = false
        }
    }

    // ---- switching ----

    private fun stashCurrent() {
        val k = currentKey ?: return
        val v = readForm()
        if (v != loadStored(k.first, k.second) || edits.containsKey(k)) edits[k] = v
    }

    private fun switchTo() {
        stashCurrent()
        val key = feature to scope
        currentKey = key
        writeForm(edits[key] ?: loadStored(key.first, key.second))
        val f = feature
        temperature.emptyText.text = "server default"
        maxTokens.emptyText.text = "server default"
        maxContext.emptyText.text = "default ${Defaults.contextChars(f)}"
        providerRow.isVisible = f.usesModel; modelRow.isVisible = f.usesModel; tempRow.isVisible = f.usesModel; reasoningRow.isVisible = f.usesModel
        tokensRow.isVisible = f.usesModel; contextRow.isVisible = f.usesModel; systemRow.isVisible = f.usesModel
        fetchMsg.isVisible = f.usesModel
        removeLink.isVisible = scope == Scope.PROJECT
        help.text = when (f) {
            FeatureId.COMMIT_MESSAGE -> "<html>Variables: {diff} {files} {branch} {recent_commits} {hint} · conditional: {?hint: … }</html>"
            FeatureId.CHAT -> "<html>Variables: {context} (system prompt only)</html>"
            FeatureId.ASK_SELECTION -> "<html>Prefill text only. The request itself uses the Chat feature config.</html>"
            FeatureId.EXPLAIN_COMMIT -> "<html>Variables: {commit}</html>"
        }
        effective.text = "Effective now: " +
            if (runCatching { ProjectOverrides.of(project)?.hasOverride(f) }.getOrNull() == true && !removals.contains(f)) "Project override" else "Global"
    }

    private fun refreshProviderBox() {
        val keep = (providerBox.selectedItem as? ProviderItem)?.id.orEmpty()
        val items = listOf(ProviderItem("", "(none)")) + providers.items.map { ProviderItem(it.id, it.name.ifBlank { it.id }) }
        providerBox.model = DefaultComboBoxModel(items.toTypedArray())
        loading = true
        selectProvider(keep)
        loading = false
    }

    private fun selectProvider(id: String) {
        val m = providerBox.model
        for (i in 0 until m.size) if (m.getElementAt(i).id == id) { providerBox.selectedIndex = i; return }
        providerBox.selectedIndex = 0
    }

    /** A newly added provider becomes the global provider of every feature that has none (or a deleted one). */
    private fun assignIfUnassigned(providerId: String) {
        stashCurrent()
        val known = providers.items.map { it.id }.toSet() - providerId
        for (f in FeatureId.entries.filter { it.usesModel }) {
            val key = f to Scope.GLOBAL
            val cur = edits[key] ?: loadStored(f, Scope.GLOBAL)
            if (cur.providerId.isBlank() || cur.providerId !in known) edits[key] = cur.copy(providerId = providerId)
        }
        val k = currentKey
        if (k != null && k.second == Scope.GLOBAL) edits[k]?.let { writeForm(it) }
    }

    private fun markRemove() {
        val f = feature
        removals.add(f)
        edits.remove(f to Scope.PROJECT)
        writeForm(loadGlobalOnly(f))
        effective.text = "Effective now: Global (override will be removed on Apply)"
    }

    private fun loadGlobalOnly(f: FeatureId) = toForm(AiToolsetSettings.get().feature(f))

    private fun resetToDefaults() {
        writeForm(toForm(Defaults.feature(feature)).let { it.copy(providerId = readForm().providerId, model = readForm().model) })
    }

    private fun fetchModels() {
        val id = (providerBox.selectedItem as? ProviderItem)?.id.orEmpty()
        val p = providers.items.firstOrNull { it.id == id } ?: return
        fetchMsg.foreground = JBColor.GRAY; fetchMsg.text = "Fetching…"
        UiLlm.fetchModels(p, providers) { r ->
            r.onSuccess { ids ->
                val cur = (modelBox.editor.item as? String).orEmpty()
                modelBox.model = DefaultComboBoxModel(ids.toTypedArray())
                modelBox.editor.item = cur
                fetchMsg.text = "${ids.size} models"
            }.onFailure {
                fetchMsg.foreground = JBColor.RED; fetchMsg.text = it.message ?: it.javaClass.simpleName
            }
        }
    }

    // ---- Configurable contract ----

    fun isModified(): Boolean {
        stashCurrent()
        if (removals.isNotEmpty()) return true
        return edits.any { (k, v) -> v != loadStored(k.first, k.second) }
    }

    fun apply() {
        stashCurrent()
        val settings = AiToolsetSettings.get()
        val po = ProjectOverrides.of(project)
        for ((k, v) in edits) {
            val (id, sc) = k
            if (sc == Scope.GLOBAL) {
                val s = settings.feature(id)
                s.providerId = v.providerId; s.model = v.model; s.temperature = v.temperature
                s.maxTokens = v.maxTokens.toIntOrNull()?.toString() ?: ""
                s.maxContextChars = v.maxContextChars.toIntOrNull()?.toString() ?: ""
                s.reasoningEffort = v.reasoningEffort
                s.systemPrompt = v.systemPrompt; s.userTemplate = v.userTemplate
            } else if (po != null && id !in removals) {
                po.write(id, FeatureOverride(
                    providerId = v.providerId.ifBlank { null }, model = v.model.ifBlank { null },
                    temperature = v.temperature.toDoubleOrNull(), maxTokens = v.maxTokens.toIntOrNull(),
                    maxContextChars = v.maxContextChars.toIntOrNull(), reasoningEffort = v.reasoningEffort, systemPrompt = v.systemPrompt, userTemplate = v.userTemplate,
                ))
            }
        }
        for (id in removals) po?.remove(id)
        if (po != null && (removals.isNotEmpty() || edits.keys.any { it.second == Scope.PROJECT })) po.refreshVfs()
        edits.clear(); removals.clear()
        currentKey = null
        switchTo()
    }

    fun reset() {
        edits.clear(); removals.clear()
        currentKey = null
        refreshProviderBox()
        switchTo()
    }
}
