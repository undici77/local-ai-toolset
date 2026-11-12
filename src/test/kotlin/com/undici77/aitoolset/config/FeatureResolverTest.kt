package com.undici77.aitoolset.config

import com.undici77.aitoolset.llm.ProviderType
import com.undici77.aitoolset.prompt.Defaults
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class FeatureResolverTest {
    private val ollama = ProviderState().apply { id = "ollama"; name = "Ollama"; baseUrl = "http://x/v1"; defaultModel = "dm" }
    private val anth = ProviderState().apply { id = "anthropic"; type = "anthropic"; defaultModel = "sonnet" }
    private val providers = listOf(ollama, anth)

    private fun global() = Defaults.feature(FeatureId.COMMIT_MESSAGE).apply { providerId = "ollama"; model = "gm" }

    @Test fun globalOnly() {
        val e = FeatureResolver.resolve(global(), null, providers)
        assertEquals("gm", e.model)
        assertEquals(Scope.GLOBAL, e.scope)
        assertNull(e.maxTokens)
        assertEquals("low", e.reasoningEffort)
        assertNull(e.temperature)
    }

    @Test fun overrideOnlyModel() {
        val e = FeatureResolver.resolve(global(), FeatureOverride(model = "pm"), providers)
        assertEquals("pm", e.model)
        assertEquals("ollama", e.provider.id)
        assertEquals(Scope.PROJECT, e.scope)
        assertTrue(e.systemPrompt.startsWith("Generate a commit message"))
    }

    @Test fun overridePromptOnly() {
        val e = FeatureResolver.resolve(global(), FeatureOverride(systemPrompt = "SYS"), providers)
        assertEquals("SYS", e.systemPrompt)
        assertEquals("gm", e.model)
    }

    @Test fun overrideProviderSwitchesType() {
        val e = FeatureResolver.resolve(global(), FeatureOverride(providerId = "anthropic", model = ""), providers)
        assertEquals(ProviderType.ANTHROPIC, e.endpointType)
        assertEquals("gm", e.model) // blank override keeps the global model
    }

    @Test fun unknownProvider() {
        try {
            FeatureResolver.resolve(global(), FeatureOverride(providerId = "nope"), providers)
            fail()
        } catch (e: ConfigException) {
            assertTrue(e.message!!, e.message!!.contains("'nope' (from project)"))
        }
    }

    @Test fun noProvider() {
        try {
            FeatureResolver.resolve(Defaults.feature(FeatureId.COMMIT_MESSAGE), null, providers)
            fail()
        } catch (e: ConfigException) {
            assertEquals("No provider configured for Commit message.", e.message)
        }
    }

    @Test fun modelFallsBackToProviderDefault() {
        val g = global().apply { model = "" }
        assertEquals("dm", FeatureResolver.resolve(g, null, providers).model)
        ollama.defaultModel = ""
        try { FeatureResolver.resolve(g, null, providers); fail() } catch (e: ConfigException) {
            assertEquals("No model set for Commit message.", e.message)
        }
    }

    @Test fun parseHeaders() {
        assertEquals(mapOf("A" to "b", "C" to "d:e"), FeatureResolver.parseHeaders("A: b\n\nbad\nC: d:e"))
    }

    @Test fun projectOverridesRoundTrip() {
        val dir = Files.createTempDirectory("aitoolset")
        val po = ProjectOverrides(dir)
        assertNull(po.read(FeatureId.COMMIT_MESSAGE))
        po.write(FeatureId.COMMIT_MESSAGE, FeatureOverride("ollama", "m", 0.2, 300, 1000, "SYS", "USR {diff}"))
        val r = po.read(FeatureId.COMMIT_MESSAGE)!!
        assertEquals(FeatureOverride("ollama", "m", 0.2, 300, 1000, "SYS", "USR {diff}"), r)
        assertTrue(po.hasOverride(FeatureId.COMMIT_MESSAGE))
        val json = Files.readString(dir.resolve(".ai-toolset/config.json"))
        assertTrue(json, json.contains("\"provider_id\": \"ollama\""))
        assertFalse(json, Regex("\"[a-z]+[A-Z]\\w*\"").containsMatchIn(json))
        assertTrue(Files.exists(dir.resolve(".ai-toolset/prompts/commit-message.system.md")))
        po.remove(FeatureId.COMMIT_MESSAGE)
        assertFalse(Files.exists(dir.resolve(".ai-toolset/prompts/commit-message.system.md")))
        assertFalse(Files.exists(dir.resolve(".ai-toolset/config.json")))
        assertNull(po.read(FeatureId.COMMIT_MESSAGE))
    }

    @Test fun malformedConfigThrows() {
        val dir = Files.createTempDirectory("aitoolset")
        Files.createDirectories(dir.resolve(".ai-toolset"))
        Files.writeString(dir.resolve(".ai-toolset/config.json"), "{ not json")
        try { ProjectOverrides(dir).read(FeatureId.CHAT); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("Invalid .ai-toolset/config.json"))
        }
    }

    @Test fun camelCaseKeysIgnoredWithWarning() {
        val dir = Files.createTempDirectory("aitoolset")
        Files.createDirectories(dir.resolve(".ai-toolset"))
        Files.writeString(dir.resolve(".ai-toolset/config.json"), "{\"features\":{\"chat\":{\"providerId\":\"x\"}}}")
        try { ProjectOverrides(dir).read(FeatureId.CHAT); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("no known keys"))
        }
    }

    @Test fun settingsXmlHasNoCamelCase() {
        val s = SettingsState()
        s.providers.add(ProviderState().apply { id = "a"; name = "A"; baseUrl = "u"; defaultModel = "m"; extraHeaders = "K: V" })
        s.features.add(Defaults.feature(FeatureId.COMMIT_MESSAGE))
        val xml = XmlSerializer.serialize(s)
        assertNotNull(xml)
        val text = org.jdom.output.XMLOutputter().outputString(xml)
        val names = Regex("<([A-Za-z_]+)|\\s([A-Za-z_]+)=").findAll(text).map { it.groupValues[1] + it.groupValues[2] }
        for (n in names) assertFalse("camelCase name: $n in $text", Regex("[a-z][A-Z]").containsMatchIn(n))
    }
}
