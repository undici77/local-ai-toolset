package com.undici77.aitoolset.config

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files
import java.nio.file.Path

data class FeatureOverride(
    val providerId: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val maxContextChars: Int? = null,
    val reasoningEffort: String? = null,
    val systemPrompt: String? = null,
    val userTemplate: String? = null,
)

/** `.ai-toolset/config.json` + `.ai-toolset/prompts/<feature>.{system,user}.md`. Pure java.nio. No secrets ever. */
class ProjectOverrides(private val root: Path) {
    private val dir: Path get() = root.resolve(DIR)
    private val configFile: Path get() = dir.resolve("config.json")
    private fun systemFile(f: FeatureId) = dir.resolve("prompts").resolve("${f.fileName}.system.md")
    private fun userFile(f: FeatureId) = dir.resolve("prompts").resolve("${f.fileName}.user.md")

    private fun readConfig(): JsonObject {
        if (!Files.isRegularFile(configFile)) return JsonObject(emptyMap())
        return try {
            Json.parseToJsonElement(Files.readString(configFile)) as? JsonObject
                ?: throw IllegalStateException("top-level value must be an object")
        } catch (e: Exception) {
            throw IllegalStateException("Invalid .ai-toolset/config.json: ${e.message}", e)
        }
    }

    private fun entry(cfg: JsonObject, f: FeatureId): JsonObject? =
        ((cfg["features"] as? JsonObject)?.get(f.key)) as? JsonObject

    private fun readText(p: Path): String? = if (Files.isRegularFile(p)) Files.readString(p) else null

    fun read(f: FeatureId): FeatureOverride? {
        val e = entry(readConfig(), f)
        val system = readText(systemFile(f))
        val user = readText(userFile(f))
        if (e == null && system == null && user == null) return null
        fun str(k: String) = (e?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val o = FeatureOverride(
            providerId = str("provider_id"),
            model = str("model"),
            temperature = (e?.get("temperature") as? JsonPrimitive)?.doubleOrNull,
            maxTokens = (e?.get("max_tokens") as? JsonPrimitive)?.intOrNull,
            maxContextChars = (e?.get("max_context_chars") as? JsonPrimitive)?.intOrNull,
            reasoningEffort = str("reasoning_effort"),
            systemPrompt = system,
            userTemplate = user,
        )
        val anyKnown = o.providerId != null || o.model != null || o.temperature != null ||
            o.maxTokens != null || o.maxContextChars != null || o.reasoningEffort != null
        if (e != null && e.isNotEmpty() && !anyKnown && system == null && user == null) {
            throw IllegalStateException("Invalid .ai-toolset/config.json: no known keys for '${f.key}'.")
        }
        return o
    }

    fun write(f: FeatureId, o: FeatureOverride) {
        val cfg = readConfig()
        val features = (cfg["features"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val e = (features[f.key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        fun put(k: String, v: JsonElement?) { if (v == null) e.remove(k) else e[k] = v }
        put("provider_id", o.providerId?.let { JsonPrimitive(it) })
        put("model", o.model?.let { JsonPrimitive(it) })
        put("temperature", o.temperature?.let { JsonPrimitive(it) })
        put("max_tokens", o.maxTokens?.let { JsonPrimitive(it) })
        put("max_context_chars", o.maxContextChars?.let { JsonPrimitive(it) })
        put("reasoning_effort", o.reasoningEffort?.let { JsonPrimitive(it) })
        features[f.key] = JsonObject(e)
        val out = JsonObject(cfg.toMutableMap().also { it["features"] = JsonObject(features) })
        Files.createDirectories(dir.resolve("prompts"))
        Files.writeString(configFile, PRETTY.encodeToString(JsonElement.serializer(), out) + "\n")
        o.systemPrompt?.let { Files.writeString(systemFile(f), it) }
        o.userTemplate?.let { Files.writeString(userFile(f), it) }
    }

    fun remove(f: FeatureId) {
        Files.deleteIfExists(systemFile(f))
        Files.deleteIfExists(userFile(f))
        if (!Files.isRegularFile(configFile)) return
        val cfg = readConfig()
        val features = (cfg["features"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        features.remove(f.key)
        if (features.isEmpty()) {
            Files.deleteIfExists(configFile)
        } else {
            val out = JsonObject(cfg.toMutableMap().also { it["features"] = JsonObject(features) })
            Files.writeString(configFile, PRETTY.encodeToString(JsonElement.serializer(), out) + "\n")
        }
    }

    fun hasOverride(f: FeatureId): Boolean =
        runCatching { entry(readConfig(), f) != null }.getOrDefault(false) ||
            Files.isRegularFile(systemFile(f)) || Files.isRegularFile(userFile(f))

    fun refreshVfs() {
        VfsUtil.markDirtyAndRefresh(true, true, true, dir.toFile())
    }

    companion object {
        const val DIR = ".ai-toolset"
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        fun of(project: Project): ProjectOverrides? =
            project.guessProjectDir()?.toNioPath()?.let(::ProjectOverrides)
    }
}
