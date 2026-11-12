package com.undici77.aitoolset.chat.actions

import com.undici77.aitoolset.chat.ChatService
import com.undici77.aitoolset.chat.ContextItem
import com.undici77.aitoolset.config.AiToolsetSettings
import com.undici77.aitoolset.config.FeatureId
import com.undici77.aitoolset.config.ProjectOverrides
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

class AskAboutSelectionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null &&
            editor.selectionModel.hasSelection() && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val sel = editor.selectionModel
        val doc = editor.document
        val start = doc.getLineNumber(sel.selectionStart) + 1
        var endOffset = sel.selectionEnd
        // A selection that ends at the very start of a line does not include that line.
        if (endOffset > sel.selectionStart && doc.getLineStartOffset(doc.getLineNumber(endOffset)) == endOffset) endOffset--
        val end = maxOf(start, doc.getLineNumber(endOffset) + 1)
        val svc = project.service<ChatService>()
        svc.addContext(listOf(ContextItem(file, start..end)))
        val prefill = runCatching {
            ProjectOverrides.of(project)?.read(FeatureId.ASK_SELECTION)?.userTemplate?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: AiToolsetSettings.get().feature(FeatureId.ASK_SELECTION).userTemplate
        ToolWindowManager.getInstance(project).getToolWindow("AI Chat")?.activate { svc.prefill(prefill) }
    }
}
