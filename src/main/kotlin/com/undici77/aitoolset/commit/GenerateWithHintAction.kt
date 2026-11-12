package com.undici77.aitoolset.commit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys

class GenerateWithHintAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val p = e.project
        val ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        e.presentation.isEnabledAndVisible = p != null && ui != null
        e.presentation.isEnabled = p != null && ui != null && !p.service<CommitMessageService>().isRunning
    }

    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        val svc = p.service<CommitMessageService>()
        if (svc.isRunning) return
        val hint = Messages.showInputDialog(p, "What is this change about? (optional)", "Commit Message Hint", null) ?: return
        svc.start(ui, hint.trim().ifEmpty { null })
    }
}
