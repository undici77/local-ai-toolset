package com.undici77.aitoolset.commit

import com.undici77.aitoolset.Icons
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys

class GenerateCommitMessageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val p = e.project
        val ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        e.presentation.isEnabledAndVisible = p != null && ui != null
        if (p != null && p.service<CommitMessageService>().isRunning) {
            e.presentation.icon = AllIcons.Actions.Suspend
            e.presentation.text = "Stop Generating"
        } else {
            e.presentation.icon = Icons.Sparkle
            e.presentation.text = "Generate Commit Message (AI)"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        val svc = p.service<CommitMessageService>()
        if (svc.isRunning) svc.cancel() else svc.start(ui, hint = null)
    }
}
