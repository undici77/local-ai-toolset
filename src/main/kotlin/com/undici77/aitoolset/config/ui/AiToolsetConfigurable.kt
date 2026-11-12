package com.undici77.aitoolset.config.ui

import com.undici77.aitoolset.config.AiToolsetSettings
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class AiToolsetConfigurable(private val project: Project) : Configurable {
    private var providersPanel: ProvidersPanel? = null
    private var featuresPanel: FeaturesPanel? = null
    private var tabs: JBTabbedPane? = null
    private var advanced: JBCheckBox? = null

    override fun getDisplayName() = "Local AI Toolset"

    override fun createComponent(): JComponent {
        val model = ProvidersModel()
        val pp = ProvidersPanel(project, model)
        pp.reset()
        val fp = FeaturesPanel(project, model)
        providersPanel = pp
        featuresPanel = fp
        val tp = JBTabbedPane().apply { addTab("Providers", pp.component) }
        tabs = tp
        val adv = JBCheckBox("Advanced setup (per-feature provider, model and prompts)")
        advanced = adv
        val hint = JBLabel("Easy setup: add a provider and it is used for every feature. Prompts use the built-in defaults.")
            .apply { foreground = JBColor.GRAY }
        adv.addActionListener { updateTabs(); hint.isVisible = !adv.isSelected }
        reset()
        hint.isVisible = !adv.isSelected
        val top = JPanel(BorderLayout()).apply {
            add(adv, BorderLayout.NORTH); add(hint, BorderLayout.CENTER)
            border = JBUI.Borders.empty(8, 8, 0, 8)
        }
        return JPanel(BorderLayout()).apply { add(top, BorderLayout.NORTH); add(tp, BorderLayout.CENTER) }
    }

    private fun updateTabs() {
        val tp = tabs ?: return
        val fp = featuresPanel ?: return
        val shown = tp.tabCount > 1
        val want = advanced?.isSelected == true
        if (want && !shown) tp.addTab("Features", fp.component)
        if (!want && shown) tp.removeTabAt(1)
    }

    override fun isModified() =
        advanced?.isSelected != AiToolsetSettings.get().state.advancedMode ||
            providersPanel?.isModified() == true || featuresPanel?.isModified() == true

    override fun apply() {
        AiToolsetSettings.get().state.advancedMode = advanced?.isSelected == true
        providersPanel?.apply()
        featuresPanel?.apply()
    }

    override fun reset() {
        advanced?.isSelected = AiToolsetSettings.get().state.advancedMode
        updateTabs()
        providersPanel?.reset()
        featuresPanel?.reset()
    }

    override fun disposeUIResources() {
        providersPanel = null
        featuresPanel = null
        tabs = null
        advanced = null
    }
}
