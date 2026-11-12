package com.undici77.aitoolset.config.ui

import com.undici77.aitoolset.config.AiToolsetSettings
import com.undici77.aitoolset.config.FeatureId
import com.undici77.aitoolset.config.ProviderState
import com.undici77.aitoolset.config.Secrets
import com.undici77.aitoolset.llm.ProviderPreset
import com.undici77.aitoolset.llm.ProviderPresets
import com.undici77.aitoolset.llm.ProviderType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class ProvidersPanel(private val project: Project, private val model: ProvidersModel) {
    private val listModel = CollectionListModel<ProviderState>()
    private val list = JBList(listModel)

    private val name = JBTextField()
    private val type = ComboBox(arrayOf("OpenAI-compatible", "Anthropic"))
    private val baseUrl = JBTextField()
    private val apiKey = JBPasswordField()
    private val keyStatus = JBLabel("").apply { foreground = JBColor.GRAY }
    private val clearKey = ActionLink("Clear key") { clearKeyOfSelected() }
    private val defaultModel = ComboBox<String>(DefaultComboBoxModel()).apply { isEditable = true }
    private val fetchBtn = JButton("Fetch models")
    private val fetchMsg = JBLabel("")
    private val headers = JBTextArea(3, 30)
    private val extraBody = JBTextArea(4, 30)
    private val testBtn = JButton("Test connection")
    private val testMsg = JBLabel("")
    private val usedBy = JBLabel("").apply { foreground = JBColor.GRAY }

    /** Fires shortly after the user stops typing a key; fetches models only to fill an empty default model. */
    private val autoFetchTimer = javax.swing.Timer(800) { fetch(auto = true) }.apply { isRepeats = false }

    private var loading = false
    private var selected: ProviderState? = null
    val component: JComponent

    init {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", name)
            .addLabeledComponent("Type:", type)
            .addLabeledComponent("Base URL:", baseUrl)
            .addComponentToRightColumn(JBLabel("OpenAI-compatible: include /v1 · Anthropic: without /v1").apply { foreground = JBColor.GRAY })
            .addLabeledComponent("API key:", apiKey)
            .addComponentToRightColumn(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply { add(keyStatus); add(clearKey) })
            .addLabeledComponent("Default model:", defaultModel)
            .addComponentToRightColumn(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply { add(fetchBtn); add(fetchMsg) })
            .addLabeledComponent("Extra headers:", JBScrollPane(headers))
            .addComponentToRightColumn(JBLabel("One \"Key: Value\" per line").apply { foreground = JBColor.GRAY })
            .addLabeledComponent("Extra request body:", JBScrollPane(extraBody))
            .addComponentToRightColumn(JBLabel("<html>JSON object merged into every request, e.g. {\"chat_template_kwargs\": {\"enable_thinking\": false}}</html>").apply { foreground = JBColor.GRAY })
            .addComponent(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply { add(testBtn); add(testMsg) })
            .addComponent(usedBy)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        form.border = JBUI.Borders.empty(0, 10, 0, 0)

        val left = ToolbarDecorator.createDecorator(list)
            .setAddAction { showPresetPopup(it.preferredPopupPoint) }
            .setRemoveAction { removeSelected() }
            .setRemoveActionUpdater { val s = list.selectedValue; s != null && usersOf(s).isEmpty() }
            .disableUpDownActions()
            .createPanel()

        val splitter = JBSplitter(false, 0.3f).apply { firstComponent = left; secondComponent = form }
        component = JPanel(BorderLayout()).apply { add(splitter, BorderLayout.CENTER); border = JBUI.Borders.empty(8) }

        list.cellRenderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.name.ifBlank { it.id } }
        list.addListSelectionListener { if (!it.valueIsAdjusting) select(list.selectedValue) }

        name.onChange { edit { it.name = name.text; listModel.contentsChanged(it); model.fire() } }
        baseUrl.onChange { edit { it.baseUrl = baseUrl.text } }
        headers.onChange { edit { it.extraHeaders = headers.text } }
        extraBody.onChange { edit { it.extraBody = extraBody.text } }
        type.addActionListener { edit { it.type = if (type.selectedIndex == 1) ProviderType.ANTHROPIC.key else ProviderType.OPENAI_COMPAT.key } }
        (defaultModel.editor.editorComponent as javax.swing.text.JTextComponent).onChange {
            edit { it.defaultModel = (defaultModel.editor.item as? String).orEmpty() }
        }
        apiKey.onChange {
            val s = selected
            if (!loading && s != null && apiKey.password.isNotEmpty()) {
                model.pendingKeys[s.id] = String(apiKey.password)
                autoFetchTimer.restart()
            }
        }
        fetchBtn.addActionListener { fetch(auto = false) }
        testBtn.addActionListener { test() }
        setFormEnabled(false)
        model.addListener { updateUsedBy() }
    }

    fun reset() {
        val settings = AiToolsetSettings.get().state
        model.items.clear()
        model.items.addAll(settings.providers.map { it.copy() })
        model.pendingKeys.clear()
        model.removedIds.clear()
        selected = null
        listModel.replaceAll(model.items)
        setFormEnabled(false)
        model.fire()
    }

    fun isModified(): Boolean {
        val stored = AiToolsetSettings.get().state.providers
        if (model.pendingKeys.isNotEmpty() || model.removedIds.isNotEmpty()) return true
        if (stored.size != model.items.size) return true
        return stored.zip(model.items).any { (a, b) ->
            a.id != b.id || a.name != b.name || a.type != b.type || a.baseUrl != b.baseUrl ||
                a.defaultModel != b.defaultModel || a.extraHeaders != b.extraHeaders || a.extraBody != b.extraBody
        }
    }

    fun apply() {
        val st = AiToolsetSettings.get().state
        st.providers = model.items.map { it.copy() }.toMutableList()
        val keys = model.pendingKeys.toMap()
        val removed = model.removedIds.toSet()
        model.pendingKeys.clear()
        model.removedIds.clear()
        ApplicationManager.getApplication().executeOnPooledThread {
            keys.forEach { (id, k) -> Secrets.set(id, k) }
            removed.forEach { Secrets.set(it, null) }
        }
    }

    private fun edit(f: (ProviderState) -> Unit) {
        val s = selected ?: return
        if (loading) return
        f(s)
    }

    private fun usersOf(p: ProviderState): List<FeatureId> {
        val st = AiToolsetSettings.get().state
        return FeatureId.entries.filter { f -> st.features.any { it.featureId == f.key && it.providerId == p.id } }
    }

    private fun updateUsedBy() {
        val s = selected
        val users = s?.let { usersOf(it) }.orEmpty()
        usedBy.text = if (users.isEmpty()) "" else "Used by: ${users.joinToString { it.title }} (cannot be removed)"
    }

    private fun showPresetPopup(point: com.intellij.ui.awt.RelativePoint) {
        val step = object : BaseListPopupStep<ProviderPreset>("Add provider", ProviderPresets.all) {
            override fun getTextFor(value: ProviderPreset) = value.name
            override fun getSeparatorAbove(value: ProviderPreset) =
                if (value === ProviderPresets.generic.first()) com.intellij.openapi.ui.popup.ListSeparator() else null
            override fun onChosen(value: ProviderPreset, finalChoice: Boolean): PopupStep<*>? {
                addPreset(value)
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).show(point)
    }

    private fun addPreset(p: ProviderPreset) {
        var id = p.id
        var n = 2
        while (model.items.any { it.id == id }) id = "${p.id}-${n++}"
        val s = ProviderState().also {
            it.id = id; it.name = if (id == p.id) p.name else "${p.name} ${id.substringAfterLast('-')}"
            it.type = p.type.key; it.baseUrl = p.baseUrl; it.defaultModel = p.defaultModel
        }
        model.items.add(s)
        listModel.add(s)
        list.setSelectedValue(s, true)
        model.fire()
        model.fireAdded(s)
        if (s.defaultModel.isBlank() && !p.keyRequired) fetch(auto = true)
    }

    private fun removeSelected() {
        val s = list.selectedValue ?: return
        if (usersOf(s).isNotEmpty()) return
        model.items.remove(s)
        model.pendingKeys.remove(s.id)
        model.removedIds.add(s.id)
        listModel.remove(s)
        selected = null
        setFormEnabled(false)
        model.fire()
    }

    private fun select(p: ProviderState?) {
        selected = p
        setFormEnabled(p != null)
        fetchMsg.text = ""; testMsg.text = ""
        if (p == null) { updateUsedBy(); return }
        loading = true
        try {
            name.text = p.name
            type.selectedIndex = if (ProviderType.fromKey(p.type) == ProviderType.ANTHROPIC) 1 else 0
            baseUrl.text = p.baseUrl
            apiKey.text = ""
            keyStatus.text = ""
            val m = defaultModel.model as DefaultComboBoxModel<String>
            m.removeAllElements()
            defaultModel.editor.item = p.defaultModel
            headers.text = p.extraHeaders
            extraBody.text = p.extraBody
        } finally {
            loading = false
        }
        updateUsedBy()
        refreshKeyStatus(p)
    }

    /** Read in the background, only because the user picked this provider in the list. */
    private fun refreshKeyStatus(p: ProviderState) {
        if (model.pendingKeys.containsKey(p.id)) {
            keyStatus.text = if (model.pendingKeys[p.id] == null) "key will be cleared" else "new key will be saved on Apply"
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val has = runCatching { !Secrets.get(p.id).isNullOrBlank() }.getOrDefault(false)
            ApplicationManager.getApplication().invokeLater({
                if (selected === p && !model.pendingKeys.containsKey(p.id)) {
                    keyStatus.text = if (has) "•••• stored (leave blank to keep)" else "no key stored"
                }
            }, ModalityState.any())
        }
    }

    private fun clearKeyOfSelected() {
        val s = selected ?: return
        model.pendingKeys[s.id] = null
        loading = true; apiKey.text = ""; loading = false
        keyStatus.text = "key will be cleared"
    }

    private fun setFormEnabled(on: Boolean) {
        listOf(name, type, baseUrl, apiKey, defaultModel, fetchBtn, headers, extraBody, testBtn, clearKey).forEach { it.isEnabled = on }
        if (!on) {
            loading = true
            name.text = ""; baseUrl.text = ""; apiKey.text = ""; headers.text = ""; extraBody.text = ""; keyStatus.text = ""
            loading = false
        }
    }

    /** [auto]: silent, and only fills the default model if it is still empty (first model returned). */
    private fun fetch(auto: Boolean) {
        val s = selected ?: return
        if (auto && (s.defaultModel.isNotBlank() || s.baseUrl.isBlank())) return
        fetchMsg.foreground = JBColor.GRAY; fetchMsg.text = "Fetching…"
        UiLlm.fetchModels(s, model) { r ->
            if (selected !== s) return@fetchModels
            r.onSuccess { ids ->
                val current = (defaultModel.editor.item as? String).orEmpty()
                defaultModel.model = DefaultComboBoxModel(ids.toTypedArray())
                defaultModel.editor.item = current
                fetchMsg.text = "${ids.size} models"
                if (current.isBlank() && ids.isNotEmpty()) {
                    defaultModel.editor.item = ids.first()
                    fetchMsg.text = "${ids.size} models · picked ${ids.first()}"
                }
            }.onFailure {
                if (auto) { fetchMsg.text = "" } else {
                    fetchMsg.foreground = JBColor.RED
                    fetchMsg.text = it.message ?: it.javaClass.simpleName
                }
            }
        }
    }

    private fun test() {
        val s = selected ?: return
        val modelName = (defaultModel.editor.item as? String).orEmpty().ifBlank { s.defaultModel }
        if (modelName.isBlank()) { testMsg.foreground = JBColor.RED; testMsg.text = "✗ Set a default model first"; return }
        testMsg.foreground = JBColor.GRAY; testMsg.text = "Testing…"
        UiLlm.testConnection(s, modelName, model) {
            testMsg.foreground = if (it.startsWith("✓")) JBColor(0x2E7D32, 0x6BBF6B) else JBColor.RED
            testMsg.text = it
        }
    }
}
