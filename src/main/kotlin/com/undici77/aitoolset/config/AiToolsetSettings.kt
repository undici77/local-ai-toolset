package com.undici77.aitoolset.config

import com.undici77.aitoolset.prompt.Defaults
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

// Every persisted field has an explicit snake_case name, so the XML never contains camelCase keys.
@Tag("provider")
class ProviderState {
    @get:Attribute("id") var id = ""
    @get:Attribute("name") var name = ""
    @get:Attribute("type") var type = "openai_compat" // "openai_compat" | "anthropic"
    @get:Attribute("base_url") var baseUrl = ""
    @get:Attribute("default_model") var defaultModel = ""
    @get:Tag("extra_headers") var extraHeaders = "" // "K: V" per line
    @get:Tag("extra_body") var extraBody = "" // JSON object merged into every request

    fun copy(): ProviderState = ProviderState().also {
        it.id = id; it.name = name; it.type = type; it.baseUrl = baseUrl
        it.defaultModel = defaultModel; it.extraHeaders = extraHeaders; it.extraBody = extraBody
    }

    override fun toString() = name.ifBlank { id }
}

@Tag("feature")
class FeatureState {
    @get:Attribute("feature_id") var featureId = ""
    @get:Attribute("provider_id") var providerId = ""
    @get:Attribute("model") var model = ""
    @get:Tag("system_prompt") var systemPrompt = ""
    @get:Tag("user_template") var userTemplate = ""
    @get:Attribute("temperature") var temperature = "" // "" = omit
    // null = never set (use Defaults.reasoningEffort); "" = explicitly the server default
    @get:Attribute("reasoning_effort") var reasoningEffort: String? = null
    // "" = not set: max output tokens is left to the server, context limit uses Defaults.contextChars().
    // New attribute names on purpose: values stored by 0.1.0 (400 tokens etc.) are dropped.
    @get:Attribute("max_output_tokens") var maxTokens = ""
    @get:Attribute("context_limit_chars") var maxContextChars = ""

    fun copy(): FeatureState = FeatureState().also {
        it.featureId = featureId; it.providerId = providerId; it.model = model
        it.systemPrompt = systemPrompt; it.userTemplate = userTemplate; it.temperature = temperature
        it.reasoningEffort = reasoningEffort
        it.maxTokens = maxTokens; it.maxContextChars = maxContextChars
    }
}

@Tag("settings")
class SettingsState {
    @get:XCollection(propertyElementName = "providers", style = XCollection.Style.v2)
    var providers: MutableList<ProviderState> = mutableListOf()

    @get:XCollection(propertyElementName = "features", style = XCollection.Style.v2)
    var features: MutableList<FeatureState> = mutableListOf()

    @get:OptionTag("git_executable") var gitExecutable = "git"
    @get:OptionTag("advanced_mode") var advancedMode = false
    /** Chat input: Enter sends and Shift+Enter inserts a new line (as in AI CLIs). Off: Enter inserts a new line. */
    @get:OptionTag("enter_sends") var enterSends = false
}

@Service(Service.Level.APP)
@State(name = "LocalAiToolsetSettings", storages = [Storage("local-ai-toolset.xml")])
class AiToolsetSettings : PersistentStateComponent<SettingsState> {
    private var state = SettingsState()

    override fun getState() = state
    override fun loadState(s: SettingsState) { state = s }

    /** Returns the stored feature config, or creates it from [Defaults] and stores it. */
    fun feature(id: FeatureId): FeatureState {
        state.features.firstOrNull { it.featureId == id.key }?.let { return it }
        return Defaults.feature(id).also { state.features.add(it) }
    }

    fun provider(id: String): ProviderState? = state.providers.firstOrNull { it.id == id }

    companion object {
        fun get(): AiToolsetSettings = ApplicationManager.getApplication().getService(AiToolsetSettings::class.java)
    }
}
