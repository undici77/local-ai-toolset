package com.undici77.aitoolset.vcs

import com.undici77.aitoolset.Icons
import com.undici77.aitoolset.chat.ChatService
import com.undici77.aitoolset.config.AiToolsetSettings
import com.undici77.aitoolset.config.FeatureId
import com.undici77.aitoolset.prompt.Defaults
import com.undici77.aitoolset.config.ProjectOverrides
import com.undici77.aitoolset.core.AiScope
import com.undici77.aitoolset.git.Git
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.vcs.log.VcsLogDataKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path

class ExplainCommitAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val sel = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)
        e.presentation.isEnabledAndVisible = e.project != null && sel != null && sel.commits.size == 1
        e.presentation.icon = Icons.Sparkle
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val c = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits?.singleOrNull() ?: return
        val hash = c.hash.asString()
        val short = hash.take(10)
        val root = c.root.toNioPath()
        val svc = project.service<ChatService>()
        if (svc.isRunning && Messages.showYesNoDialog(
                project, "The AI Chat is still answering. Stop it and explain commit $short?", "Explain Commit",
                "Stop and Explain", "Cancel", Messages.getQuestionIcon(),
            ) != Messages.YES
        ) return
        ToolWindowManager.getInstance(project).getToolWindow("AI Chat")?.activate(null)
        AiScope.get(project).cs.launch(Dispatchers.EDT) {
            svc.stopAndWait()
            // The commit is read inside the run: the chat shows "Reading commit…" at once, and a git error lands there with Retry.
            svc.send(
                "Explain commit $short", FeatureId.EXPLAIN_COMMIT,
                preparing = "Reading commit $short…",
                loadVars = { mapOf("commit" to readCommit(project, root, hash)) },
            )
        }
    }

    private fun readCommit(project: Project, root: Path, hash: String): String {
        val max = runCatching {
            val g = AiToolsetSettings.get().feature(FeatureId.EXPLAIN_COMMIT)
            ProjectOverrides.of(project)?.read(FeatureId.EXPLAIN_COMMIT)?.maxContextChars
                ?: g.maxContextChars.trim().toIntOrNull() ?: Defaults.contextChars(FeatureId.EXPLAIN_COMMIT)
        }.getOrDefault(Defaults.contextChars(FeatureId.EXPLAIN_COMMIT))
        val out = Git().show(root, hash)
        return if (out.length > max) out.take(max) + "\n… [truncated]" else out
    }
}
