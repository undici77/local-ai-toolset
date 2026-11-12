package com.undici77.aitoolset.commit

import com.undici77.aitoolset.config.AiToolsetSettings
import com.undici77.aitoolset.config.ConfigException
import com.undici77.aitoolset.config.FeatureId
import com.undici77.aitoolset.config.FeatureResolver
import com.undici77.aitoolset.config.ProjectOverrides
import com.undici77.aitoolset.core.Notifier
import com.undici77.aitoolset.git.DiffBuilder
import com.undici77.aitoolset.git.Git
import com.undici77.aitoolset.llm.ChatMessage
import com.undici77.aitoolset.llm.ChatRequest
import com.undici77.aitoolset.llm.LlmClients
import com.undici77.aitoolset.llm.StreamEvent
import com.undici77.aitoolset.llm.Role
import com.undici77.aitoolset.prompt.OutputCleaner
import com.undici77.aitoolset.prompt.TemplateEngine
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.vcs.commit.CommitWorkflowUi
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class CommitMessageService(private val project: Project) {
    @Volatile var job: Job? = null
    val isRunning get() = job?.isActive == true

    fun cancel() { job?.cancel() }

    private class Prepared(val system: String, val user: String, val request: ChatRequest, val endpoint: com.undici77.aitoolset.llm.Endpoint, val providerId: String)

    fun start(ui: CommitWorkflowUi, hint: String?) {
        val changes = ui.getIncludedChanges()
        val unversioned = ui.getIncludedUnversionedFiles()
        val previous = ui.commitMessageUi.text
        if (changes.isEmpty() && unversioned.isEmpty()) {
            Notifier.error(project, "AI Toolset", "No changes are checked for commit.")
            return
        }
        val paths = changes.map { ChangesUtil.getFilePath(it) }
        val newFiles = unversioned
        var providerId: String? = null
        val runJob = Job()
        job = runJob
        // Status bar progress with a cancel button; it and the Stop action both end the run.
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating commit message", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    runBlockingCancellable {
                        val inner = coroutineContext.job
                        val link = runJob.invokeOnCompletion { if (runJob.isCancelled) inner.cancel() }
                        indicator.text = "Reading changes…"
                        val acc = StringBuilder()
                        try {
                            val prepared = withContext(Dispatchers.IO) {
                                val settings = AiToolsetSettings.get()
                                val feature = FeatureResolver.resolve(
                                    settings.feature(FeatureId.COMMIT_MESSAGE),
                                    ProjectOverrides.of(project)?.read(FeatureId.COMMIT_MESSAGE),
                                    settings.state.providers,
                                )
                                providerId = feature.provider.id
                                val vcsManager = ProjectLevelVcsManager.getInstance(project)
                                val byRoot = LinkedHashMap<Path, MutableList<String>>()
                                for (fp in paths) {
                                    var vcsRoot = vcsManager.getVcsRootObjectFor(fp) ?: continue
                                    var rel = vcsRoot.path.toNioPath().relativize(Path.of(fp.path)).toString().replace('\\', '/')
                                    if (rel.isBlank()) {
                                        // A submodule entry is its own VCS root: diff it from the parent repo,
                                        // where git reports it as a "Subproject commit" change.
                                        val parent = fp.parentPath ?: continue
                                        vcsRoot = vcsManager.getVcsRootObjectFor(parent) ?: continue
                                        rel = vcsRoot.path.toNioPath().relativize(Path.of(fp.path)).toString().replace('\\', '/')
                                        if (rel.isBlank()) continue
                                    }
                                    if (vcsRoot.vcs?.name != "Git") continue
                                    byRoot.getOrPut(vcsRoot.path.toNioPath()) { mutableListOf() }.add(rel)
                                }
                                val untrackedPairs = mutableListOf<Pair<String, String?>>()
                                var untrackedRoot: Path? = null
                                for (fp in newFiles) {
                                    val vcsRoot = vcsManager.getVcsRootObjectFor(fp) ?: continue
                                    if (vcsRoot.vcs?.name != "Git") continue
                                    val root = vcsRoot.path.toNioPath()
                                    untrackedRoot = untrackedRoot ?: root
                                    val text = readAction {
                                        val vf = fp.virtualFile
                                        if (vf == null || vf.isDirectory || vf.length > 200_000) null else VfsUtilCore.loadText(vf)
                                    }
                                    untrackedPairs.add(root.relativize(Path.of(fp.path)).toString().replace('\\', '/') to text)
                                }
                                if (byRoot.isEmpty() && untrackedPairs.isEmpty()) {
                                    throw ConfigException("Generate commit message currently supports Git only.")
                                }
                                val git = Git()
                                val raw = StringBuilder()
                                for ((root, rel) in byRoot) raw.append(git.diffAgainstHead(root, rel))
                                val firstRoot = byRoot.keys.firstOrNull() ?: untrackedRoot!!
                                val d = DiffBuilder.build(raw.toString(), untrackedPairs, feature.maxContextChars)
                                val vars = mapOf(
                                    "diff" to d.diff, "files" to d.files, "branch" to git.branch(firstRoot),
                                    "recent_commits" to git.recentSubjects(firstRoot), "hint" to hint.orEmpty(),
                                )
                                val userTpl = TemplateEngine.ensureContains(feature.userTemplate, "diff", "\n\nDiff:\n{diff}")
                                val user = TemplateEngine.render(userTpl, vars)
                                val system = TemplateEngine.render(feature.systemPrompt, vars)
                                val req = ChatRequest(feature.model, system, listOf(ChatMessage(Role.USER, user)), feature.temperature, feature.maxTokens, feature.reasoningEffort)
                                Prepared(system, user, req, FeatureResolver.endpoint(feature.provider), feature.provider.id)
                            }
                            indicator.text = "Connecting…"
                            var last = 0L
                            var shown = ""
                            LlmClients.create(prepared.endpoint).streamEvents(prepared.request) { ev ->
                                when (ev) {
                                    StreamEvent.Connected -> indicator.text = "Waiting for ${prepared.request.model}…"
                                    is StreamEvent.Reasoning -> indicator.text = "Thinking…"
                                    is StreamEvent.Text -> {
                                        indicator.text = "Writing…"
                                        acc.append(ev.text)
                                        val now = System.nanoTime()
                                        if (now - last > 60_000_000L) {
                                            last = now
                                            // Same cleaning as the final text, so fences/labels/quotes never flash in the field.
                                            val snapshot = OutputCleaner.cleanCommitMessagePartial(acc.toString())
                                            if (snapshot != shown) {
                                                shown = snapshot
                                                withContext(Dispatchers.EDT) { ui.commitMessageUi.text = snapshot }
                                            }
                                        }
                                    }
                                    is StreamEvent.Finished -> {}
                                }
                            }
                            val cleaned = OutputCleaner.cleanCommitMessage(acc.toString())
                            withContext(Dispatchers.EDT) {
                                if (cleaned.isBlank()) {
                                    ui.commitMessageUi.text = previous
                                    Notifier.error(project, "AI Toolset", "Model returned an empty message.")
                                } else ui.commitMessageUi.text = cleaned
                            }
                        } catch (e: CancellationException) {
                            val partial = OutputCleaner.cleanCommitMessage(acc.toString())
                            withContext(Dispatchers.EDT + kotlinx.coroutines.NonCancellable) {
                                ui.commitMessageUi.text = if (partial.isBlank()) previous else partial
                            }
                            throw e
                        } catch (e: Throwable) {
                            val msg = Notifier.describe(e, providerId)
                            withContext(Dispatchers.EDT) {
                                ui.commitMessageUi.text = previous
                                Notifier.error(project, "AI Toolset", msg)
                            }
                        } finally {
                            link.dispose()
                        }
                    }
                } catch (_: ProcessCanceledException) {
                } catch (_: CancellationException) {
                } finally {
                    runJob.complete()
                    job = null
                    ApplicationManager.getApplication().invokeLater { ActivityTracker.getInstance().inc() }
                }
            }
        })
        ActivityTracker.getInstance().inc()
    }
}
