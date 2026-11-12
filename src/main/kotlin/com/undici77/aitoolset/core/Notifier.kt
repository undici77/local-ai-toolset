package com.undici77.aitoolset.core

import com.undici77.aitoolset.config.AiToolsetSettings
import com.undici77.aitoolset.config.ConfigException
import com.undici77.aitoolset.config.ui.AiToolsetConfigurable
import com.undici77.aitoolset.llm.LlmException
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException

object Notifier {
    /** Short, user-facing message for [e]. Rethrows [CancellationException]. */
    fun describe(e: Throwable, providerName: String? = null, baseUrl: String? = null): String {
        if (e is CancellationException) throw e
        return when {
            e is ConfigException -> e.message ?: "Configuration error."
            e is LlmException && (e.status == 401 || e.status == 403) ->
                "Authentication failed for ${providerName ?: "the provider"}. Check the API key."
            e is ConnectException || e.cause is ConnectException ->
                "Cannot reach ${baseUrl ?: "the server"}. Is the server running?"
            e is HttpConnectTimeoutException || e is HttpTimeoutException ->
                "Timed out talking to ${baseUrl ?: "the server"}."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    /** True when the user most likely fixes [e] in Settings (configuration, connection, authentication, unknown model). */
    fun isSettingsFixable(e: Throwable): Boolean = e is ConfigException ||
        (e is LlmException && e.status in setOf(401, 403, 404)) ||
        e is ConnectException || e.cause is ConnectException || e is HttpConnectTimeoutException

    /** Describes [e] using the provider currently configured for the failing request, when known. */
    fun describe(e: Throwable, providerId: String?): String {
        val p = providerId?.let { AiToolsetSettings.get().provider(it) }
        return describe(e, p?.name?.ifBlank { p.id }, p?.baseUrl)
    }

    fun error(project: Project, title: String, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("AI Toolset")
            .createNotification(title, message, NotificationType.ERROR)
            .addAction(NotificationAction.createSimpleExpiring("Open Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AiToolsetConfigurable::class.java)
            })
            .notify(project)
    }
}
