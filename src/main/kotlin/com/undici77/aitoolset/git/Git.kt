package com.undici77.aitoolset.git

import com.undici77.aitoolset.config.AiToolsetSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import java.nio.file.Path

class GitException(message: String) : Exception(message)

/** Runs the git CLI. Only call off the EDT. */
class Git(private val exe: String = AiToolsetSettings.get().state.gitExecutable.ifBlank { "git" }) {
    fun run(root: Path, vararg args: String, timeoutMs: Int = 15_000): String {
        val cmd = GeneralCommandLine(exe, *args).withWorkingDirectory(root).withCharset(Charsets.UTF_8)
            .withEnvironment("GIT_PAGER", "cat").withEnvironment("LC_ALL", "C")
        val out = ExecUtil.execAndGetOutput(cmd, timeoutMs)
        if (out.exitCode != 0 || out.isTimeout) {
            throw GitException(out.stderr.ifBlank { "git ${args.joinToString(" ")} failed" })
        }
        return out.stdout
    }

    fun branch(root: Path) = runCatching { run(root, "rev-parse", "--abbrev-ref", "HEAD").trim() }.getOrDefault("")

    fun recentSubjects(root: Path, n: Int = 10) =
        runCatching { run(root, "log", "-n", "$n", "--no-merges", "--pretty=format:- %s") }.getOrDefault("")

    fun hasHead(root: Path) = runCatching { run(root, "rev-parse", "--verify", "HEAD"); true }.getOrDefault(false)

    /** Working tree vs HEAD (or the empty tree when there is no commit yet), limited to [relPaths]. */
    fun diffAgainstHead(root: Path, relPaths: List<String>): String {
        val relPaths = relPaths.filter { it.isNotBlank() }
        if (relPaths.isEmpty()) return ""
        val base = if (hasHead(root)) "HEAD" else EMPTY_TREE
        val sb = StringBuilder()
        for (batch in relPaths.chunked(200)) {
            sb.append(run(root, "diff", "--no-color", "--no-ext-diff", "-M", base, "--", *batch.toTypedArray(), timeoutMs = 30_000))
        }
        return sb.toString()
    }

    fun show(root: Path, hash: String) =
        run(root, "show", "--no-color", "--no-ext-diff", "--stat", "--patch", "-M", hash, timeoutMs = 30_000)

    companion object {
        const val EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
    }
}
