package com.undici77.aitoolset.chat

import com.undici77.aitoolset.config.ui.AiToolsetConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareToggleAction
import com.undici77.aitoolset.config.AiToolsetSettings
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false).apply { isCloseable = false }
        toolWindow.contentManager.addContent(content)
        val settings = object : DumbAwareAction("Settings…", "Open Local AI Toolset settings", AllIcons.General.GearPlain) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AiToolsetConfigurable::class.java)
            }
        }
        val newChat = object : DumbAwareAction("New Chat", "Clear the conversation", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) = project.service<ChatService>().clear()
        }
        toolWindow.setTitleActions(listOf<AnAction>(settings, newChat))
        val enterSends = object : DumbAwareToggleAction("Enter to Send", "Enter sends the message, Shift+Enter inserts a new line. Off: Ctrl+Enter sends.", null) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = AiToolsetSettings.get().state.enterSends
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                AiToolsetSettings.get().state.enterSends = state
                panel.applyEnterMode()
            }
        }
        // Tool window options (⋮) menu.
        toolWindow.setAdditionalGearActions(DefaultActionGroup(enterSends, Separator.getInstance(), settings))
    }
}
