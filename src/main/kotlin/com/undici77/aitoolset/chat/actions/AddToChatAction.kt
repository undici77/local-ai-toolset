package com.undici77.aitoolset.chat.actions

import com.undici77.aitoolset.chat.ChatService
import com.undici77.aitoolset.chat.ContextItem
import com.undici77.aitoolset.core.AiScope
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddToChatAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = e.project != null && !files.isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()
        if (files.isEmpty()) return
        AiScope.get(project).cs.launch(Dispatchers.Default) {
            val items = readAction { expand(files, MAX_FILES).map { ContextItem(it) } }
            withContext(Dispatchers.EDT) {
                project.service<ChatService>().addContext(items)
                StatusBar.Info.set("Added ${items.size} file(s) to AI Chat", project)
            }
        }
    }

    companion object {
        const val MAX_FILES = 50
        private val SKIP_DIRS = setOf(".git", "build", "out", ".idea", "node_modules")

        private fun skip(dir: VirtualFile) = dir.name in SKIP_DIRS || dir.name.startsWith("cmake-build-")

        fun expand(roots: List<VirtualFile>, max: Int): List<VirtualFile> {
            val out = LinkedHashSet<VirtualFile>()
            fun visit(f: VirtualFile) {
                if (out.size >= max || !f.isValid) return
                if (f.isDirectory) {
                    if (skip(f)) return
                    for (c in f.children) visit(c)
                } else if (!f.fileType.isBinary) out.add(f)
            }
            roots.forEach { r -> if (r.isDirectory || true) visit(r) }
            return out.toList()
        }
    }
}
