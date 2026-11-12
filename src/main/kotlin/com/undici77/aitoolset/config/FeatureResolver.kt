package com.undici77.aitoolset.config

import com.undici77.aitoolset.llm.Endpoint
import com.undici77.aitoolset.llm.ProviderType
import com.undici77.aitoolset.prompt.Defaults

class ConfigException(message: String) : Exception(message)

enum class Scope { GLOBAL, PROJECT }

data class EffectiveFeature(
    val feature: FeatureId,
    val provider: ProviderState,
    val endpointType: ProviderType,
    val model: String,
    val systemPrompt: String,
    val userTemplate: String,
    val temperature: Double?,
    val maxTokens: Int?,
    val maxContextChars: Int,
    val reasoningEffort: String,
    val scope: Scope,
)

object FeatureResolver {
    /** Field-by-field: project override value if non-null/non-blank, else global value. */
    fun resolve(global: FeatureState, override: FeatureOverride?, providers: List<ProviderState>): EffectiveFeature {
        val feature = FeatureId.fromKey(global.featureId) ?: throw ConfigException("Unknown feature '${global.featureId}'.")
        val scope = if (override != null) Scope.PROJECT else Scope.GLOBAL
        val providerId = override?.providerId?.takeIf { it.isNotBlank() } ?: global.providerId
        val from = if (override?.providerId?.isNotBlank() == true) "project" else "global settings"
        if (providerId.isBlank()) throw ConfigException("No provider configured for ${feature.title}.")
        val provider = providers.firstOrNull { it.id == providerId }
            ?: throw ConfigException("Provider '$providerId' (from $from) does not exist in Settings.")
        val model = (override?.model?.takeIf { it.isNotBlank() } ?: global.model).ifBlank { provider.defaultModel }
        if (model.isBlank() && feature.usesModel) throw ConfigException("No model set for ${feature.title}.")
        return EffectiveFeature(
            feature = feature,
            provider = provider,
            endpointType = ProviderType.fromKey(provider.type),
            model = model,
            systemPrompt = override?.systemPrompt?.takeIf { it.isNotBlank() } ?: global.systemPrompt,
            userTemplate = override?.userTemplate?.takeIf { it.isNotBlank() } ?: global.userTemplate,
            temperature = override?.temperature ?: global.temperature.trim().toDoubleOrNull(),
            maxTokens = override?.maxTokens ?: global.maxTokens.trim().toIntOrNull(),
            maxContextChars = override?.maxContextChars ?: global.maxContextChars.trim().toIntOrNull() ?: Defaults.contextChars(feature),
            reasoningEffort = override?.reasoningEffort ?: global.reasoningEffort ?: Defaults.reasoningEffort(feature),
            scope = scope,
        )
    }

    fun parseHeaders(raw: String): Map<String, String> = raw.lines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.toMap()

    /** Blank = none. Anything else must be a JSON object. */
    fun parseExtraBody(p: ProviderState): kotlinx.serialization.json.JsonObject? {
        if (p.extraBody.isBlank()) return null
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(p.extraBody) as? kotlinx.serialization.json.JsonObject
                ?: throw IllegalArgumentException("not an object")
        } catch (e: Exception) {
            throw ConfigException("Extra request body of provider '${p.name.ifBlank { p.id }}' is not a valid JSON object.")
        }
    }

    /** Reads the secret from PasswordSafe. Call only off the EDT. */
    fun endpoint(p: ProviderState): Endpoint =
        Endpoint(ProviderType.fromKey(p.type), p.baseUrl, Secrets.get(p.id), parseHeaders(p.extraHeaders), parseExtraBody(p))
}
