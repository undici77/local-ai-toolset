# Local AI Toolset — Implementation Plan (step-by-step, for an implementing agent)

> **Step 0 for the implementer:** copy this file into the repo as `docs/implementation-plan.md`. Do the steps **in order**. Every step ends with **Done when**, and you don't start the next step until it holds. If an IntelliJ API named here doesn't compile on 2025.3, look up its current name in the platform sources (Gradle cache, `~/.gradle/caches/**/ideaIC*/` or the CLion jars) or at https://plugins.jetbrains.com/docs/intellij/. Keep the behaviour described here and don't add features.

---

## 1. Context

Build a **minimal, quiet** JetBrains plugin. The target is CLion, but it should run in any IntelliJ-platform IDE 2025.3+. It handles boring tasks with an LLM:

1. **Generate commit message** from the changes checked in the Commit tool window.
2. **AI Chat** tool window with files and selections as context.
3. **Add to AI Chat** (Project view / editor tab right-click).
4. **Ask AI about selection** (editor right-click).
5. **Explain commit** (Git log right-click).

**Hard rules (apply to every step):**
- No autocomplete, no inline suggestions, no gutter icons, no inspections.
- Nothing opens by itself. No startup activities, no network calls and no keychain/PasswordSafe reads until the user starts an action.
- Notifications **only for errors**, as one balloon with an "Open Settings" link.
- No vendor cloud by default. If no provider is configured, actions show the "Open Settings" error.
- API keys live **only** in PasswordSafe and never in project files.

**Configuration:** each feature has its own provider, model and prompt. The **global** settings live in the IDE. An optional **per-project** override lives in files committed to the repo (`.ai-toolset/`).

**Research background (Nov 2026):** UX entry points copied from JetBrains AI Assistant. Commit-message details from *AI Commits* (Blarc, MIT): only the checked changes, template variables, clicking again cancels. Chat-context and lazy-secret ideas from *DevoxxGenie* (MIT). Things we deliberately avoid, as ProxyAI and Tabby do them: a vendor cloud by default, autocomplete, bundled servers. Every target server speaks one of **two protocols**: OpenAI Chat Completions or Anthropic Messages.

**Naming rule for everything that isn't source code (strict):**
- **File and directory names** use `kebab-case`: `commit-message.system.md`, `ai-sparkle.svg`, `local-ai-toolset.xml`.
- **Keys** in JSON, XML and other config use `snake_case`: `provider_id`, `max_tokens`, `commit_message`.
- **Never** use camelCase or PascalCase in file names or config keys. Kotlin source code itself follows normal Kotlin conventions (`FeatureResolver.kt`, `providerId` variables).
- The only exceptions are names the platform imposes: `META-INF/plugin.xml`, `META-INF/pluginIcon.svg`, `gradle.properties` keys read by Gradle conventions, `build.gradle.kts`, `settings.gradle.kts`, the Gradle wrapper files.

**Build:** everything is built in **Docker** (no JDK on the host). Signing, Marketplace publishing and CI/CD are the **last steps (12–13)**. They reuse the same image and Make targets. Don't create CI files before Step 13.

---

## 2. Fixed technical decisions (do not change)

| Item | Value |
|---|---|
| Language | Kotlin (JVM 21 toolchain) |
| Build | Gradle (wrapper committed), **IntelliJ Platform Gradle Plugin 2.x** (`org.jetbrains.intellij.platform`) |
| Target platform | `clion("2025.3")`, `sinceBuild = "253"`, no `untilBuild` |
| plugin.xml depends | `com.intellij.modules.platform`, `com.intellij.modules.vcs` (no Git4Idea dependency, git is called through the CLI) |
| HTTP | `java.net.http.HttpClient` (JDK), streaming with SSE |
| JSON | `kotlinx.serialization.json` **element API only** (`buildJsonObject`, `Json.parseToJsonElement`). No `@Serializable` and no compiler plugin. The library ships with the platform, so don't bundle it |
| Git access | `git` CLI via `GeneralCommandLine` + `ExecUtil.execAndGetOutput`, always off the EDT |
| Async | Kotlin coroutines through a project service that receives a `CoroutineScope` in its constructor. UI updates via `withContext(Dispatchers.EDT)` |
| Markdown in chat | Our own small renderer: text segments → HTML in `JEditorPane`, fenced code → read-only `EditorTextField` with the matching file type |
| Tests | JUnit 4, plain unit tests (no IDE fixture needed) |
| Package / plugin id | `com.undici77.aitoolset` / `com.undici77.aitoolset` (put them in `gradle.properties` so they're easy to rename) |
| Plugin name | `Local AI Toolset` |

**Note for the implementer about the environment:** the dev container may not have Docker installed. If `docker` is missing, still write all the files, then ask the user to run `make test build verify` on their host and paste the output. Never install a JDK on the host as a workaround unless the user asks.

---

## 3. Final repository layout

```
.
├── docker/Dockerfile
├── docker-compose.yml
├── Makefile
├── .dockerignore
├── .gitignore
├── README.md
├── CHANGELOG.md
├── docs/implementation-plan.md
├── gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
├── gradlew, gradlew.bat
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── src
    ├── main
    │   ├── kotlin/com/undici77/aitoolset
    │   │   ├── AiToolsetBundle.kt            (optional; plain strings are OK in v1)
    │   │   ├── core/AiScope.kt               project service owning the CoroutineScope
    │   │   ├── core/Notifier.kt              error balloon + "Open Settings"
    │   │   ├── llm/Model.kt                  ChatMessage, ChatRequest, LlmException, ProviderType
    │   │   ├── llm/LlmClient.kt              interface + factory
    │   │   ├── llm/SseReader.kt
    │   │   ├── llm/OpenAiCompatClient.kt
    │   │   ├── llm/AnthropicClient.kt
    │   │   ├── llm/ProviderPresets.kt
    │   │   ├── config/FeatureId.kt
    │   │   ├── config/AiToolsetSettings.kt   app-level PersistentStateComponent
    │   │   ├── config/Secrets.kt
    │   │   ├── config/ProjectOverrides.kt    .ai-toolset/ read/write
    │   │   ├── config/FeatureResolver.kt     → EffectiveFeature
    │   │   ├── config/ui/AiToolsetConfigurable.kt
    │   │   ├── config/ui/ProvidersPanel.kt
    │   │   ├── config/ui/FeaturesPanel.kt
    │   │   ├── prompt/TemplateEngine.kt
    │   │   ├── prompt/Defaults.kt
    │   │   ├── prompt/OutputCleaner.kt
    │   │   ├── git/Git.kt                    CLI wrapper
    │   │   ├── git/DiffBuilder.kt
    │   │   ├── commit/CommitMessageService.kt
    │   │   ├── commit/GenerateCommitMessageAction.kt
    │   │   ├── commit/GenerateWithHintAction.kt
    │   │   ├── chat/ChatService.kt           project service: context items, history, send()
    │   │   ├── chat/ContextItem.kt
    │   │   ├── chat/ChatToolWindowFactory.kt
    │   │   ├── chat/ChatPanel.kt
    │   │   ├── chat/MessageView.kt           renders a single message (markdown-lite + code blocks)
    │   │   ├── chat/MarkdownLite.kt          split into segments + text→HTML
    │   │   ├── chat/actions/AddToChatAction.kt
    │   │   ├── chat/actions/AskAboutSelectionAction.kt
    │   │   └── vcs/ExplainCommitAction.kt
    │   └── resources
    │       ├── META-INF/plugin.xml
    │       ├── META-INF/pluginIcon.svg
    │       └── icons/{ai-sparkle.svg, ai-sparkle_dark.svg, ai-chat.svg, ai-chat_dark.svg}
    └── test/kotlin/com/undici77/aitoolset
        ├── prompt/TemplateEngineTest.kt
        ├── prompt/OutputCleanerTest.kt
        ├── llm/SseParsingTest.kt
        ├── llm/ClientsHttpTest.kt            uses com.sun.net.httpserver.HttpServer
        ├── git/DiffBuilderTest.kt
        ├── config/FeatureResolverTest.kt
        └── chat/MarkdownLiteTest.kt
```

---

## 4. Steps

### Step 1 — Docker build environment + Gradle skeleton

**1.1 `docker/Dockerfile`**
```dockerfile
FROM eclipse-temurin:21-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends git unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ARG UID=1000
ARG GID=1000
RUN groupadd -g ${GID} dev 2>/dev/null || true \
 && useradd -m -u ${UID} -g ${GID} -s /bin/bash dev 2>/dev/null || true
USER ${UID}:${GID}
ENV GRADLE_USER_HOME=/home/dev/.gradle
WORKDIR /src
```
(Pin the exact image tag/digest when CI is added. For now, a floating `21-jdk-noble` is fine.)

**1.2 `docker-compose.yml`**
```yaml
services:
  build:
    build:
      context: .
      dockerfile: docker/Dockerfile
      args: { UID: "${UID:-1000}", GID: "${GID:-1000}" }
    volumes:
      - .:/src
      - gradle-cache:/home/dev/.gradle
    working_dir: /src
volumes:
  gradle-cache:
```

**1.3 `Makefile`** (tabs, not spaces)
```make
DC := docker compose run --rm build
.PHONY: image wrapper build test verify clean shell
image:   ; docker compose build build
wrapper: ; docker run --rm -u $$(id -u):$$(id -g) -v $$PWD:/src -w /src gradle:9-jdk21 gradle wrapper --gradle-version 9.2.1
build:   ; $(DC) ./gradlew --no-daemon buildPlugin
test:    ; $(DC) ./gradlew --no-daemon test
verify:  ; $(DC) ./gradlew --no-daemon verifyPlugin
clean:   ; $(DC) ./gradlew --no-daemon clean
shell:   ; $(DC) bash
```
Before running `make wrapper`, check the current stable Gradle version (must be ≥ the minimum the chosen IntelliJ Platform Gradle Plugin requires; IPGP 2.20 requires Gradle 9). Commit `gradlew`, `gradlew.bat` and `gradle/wrapper/*`.

**1.4 `.dockerignore`**: `build/ .gradle/ .idea/ *.iml out/`. **`.gitignore`**: the same plus `.intellijPlatform/`.

**1.5 `settings.gradle.kts`**
```kotlin
rootProject.name = "local-ai-toolset"
```

**1.6 `gradle.properties`**
```properties
pluginGroup=com.undici77.aitoolset
pluginName=Local AI Toolset
pluginVersion=0.1.0
platformVersion=2025.3
pluginSinceBuild=253
kotlin.stdlib.default.dependency=false
org.gradle.configuration-cache=true
org.gradle.caching=true
org.gradle.jvmargs=-Xmx3g
```

**1.7 `build.gradle.kts`**
```kotlin
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "<latest 2.2.x/2.3.x that IPGP supports>"
    id("org.jetbrains.intellij.platform") version "<latest 2.x, e.g. 2.20.0>"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin { jvmToolchain(21) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        clion(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.CLion, "2025.3")
            recommended()
        }
    }
}
```
Fill in the `<…>` versions from the Gradle Plugin Portal. Use the latest stable versions at the time you implement, and write down the chosen values in `CHANGELOG.md`. If `recommended()` makes verification too slow, keep only the CLion 2025.3 entry and the latest CLion release.

**1.8 `src/main/resources/META-INF/plugin.xml`** (minimal for now):
```xml
<idea-plugin>
  <id>com.undici77.aitoolset</id>
  <name>Local AI Toolset</name>
  <vendor>Undici77</vendor>
  <description><![CDATA[Minimal, non-invasive LLM helpers: commit messages, chat with file context, explain commit. Works with Anthropic, OpenAI, Ollama, oMLX, LM Studio and any OpenAI-compatible server. No autocomplete.]]></description>
  <depends>com.intellij.modules.platform</depends>
  <depends>com.intellij.modules.vcs</depends>
  <extensions defaultExtensionNs="com.intellij">
    <notificationGroup id="AI Toolset" displayType="BALLOON"/>
  </extensions>
</idea-plugin>
```
Add `pluginIcon.svg`: a simple 40×40 sparkle shape, single colour `#6E7CFF`. Add 16×16 icons `ai-sparkle.svg` and `ai-chat.svg`, plus `_dark` variants with lighter strokes.

**1.9** Add one trivial test (`assertTrue(true)`) so `make test` has something to run.

**Done when:** `make image && make wrapper && make test build verify` succeeds, and `build/distributions/local-ai-toolset-0.1.0.zip` exists.

---

### Step 2 — LLM layer (pure Kotlin, fully unit-tested)

**2.1 `llm/Model.kt`**
```kotlin
enum class ProviderType { OPENAI_COMPAT, ANTHROPIC }
enum class Role { SYSTEM, USER, ASSISTANT }
data class ChatMessage(val role: Role, val content: String)
data class ChatRequest(
    val model: String,
    val system: String?,              // system prompt (kept separately; each client places it correctly)
    val messages: List<ChatMessage>,  // USER/ASSISTANT only
    val temperature: Double? = null,  // null = omit field
    val maxTokens: Int = 1024,
)
data class Endpoint(val type: ProviderType, val baseUrl: String, val apiKey: String?, val extraHeaders: Map<String, String> = emptyMap())
class LlmException(message: String, val status: Int? = null) : Exception(message)
```

**2.2 `llm/LlmClient.kt`**
```kotlin
interface LlmClient {
    /** Streams text deltas. Throws LlmException on HTTP/protocol error. Cancellable via coroutine cancellation. */
    suspend fun stream(req: ChatRequest, onDelta: suspend (String) -> Unit)
    suspend fun listModels(): List<String>
}
object LlmClients {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build()
    fun create(ep: Endpoint): LlmClient = when (ep.type) {
        ProviderType.OPENAI_COMPAT -> OpenAiCompatClient(http, ep)
        ProviderType.ANTHROPIC -> AnthropicClient(http, ep)
    }
}
```
HTTP/1.1 is forced because some local servers handle h2c upgrade badly.

**2.3 `llm/SseReader.kt`**: a pure function plus a streaming helper.
```kotlin
/** One SSE event: `event:` name (nullable) and the joined `data:` payload. */
data class SseEvent(val event: String?, val data: String)

/** Feed lines one at a time; returns a completed event when a blank line terminates it. */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) { if (data.isEmpty() && event == null) return null
            val ev = SseEvent(event, data.toString()); event = null; data.setLength(0); return ev }
        if (line.startsWith(":")) return null                       // comment
        val idx = line.indexOf(':'); val field = if (idx < 0) line else line.substring(0, idx)
        var value = if (idx < 0) "" else line.substring(idx + 1); if (value.startsWith(" ")) value = value.substring(1)
        when (field) { "event" -> event = value; "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) } }
        return null
    }
    fun flush(): SseEvent? = feed("")
}
```
The streaming helper `suspend fun HttpClient.streamSse(request: HttpRequest, onEvent: suspend (SseEvent) -> Boolean)`:
1. `val resp = sendAsync(request, HttpResponse.BodyHandlers.ofLines()).await()` (`kotlinx.coroutines.future.await`).
2. If `resp.statusCode() !in 200..299`: read up to 2000 chars of the body lines and throw `LlmException("HTTP ${code}: ${body}", code)`.
3. `val stream = resp.body()`. Register `coroutineContext.job.invokeOnCompletion { stream.close() }` so cancelling the coroutine closes the connection.
4. Run on `Dispatchers.IO`: iterate `stream.iterator()`. On each line call `ensureActive()`, then `parser.feed(line)?.let { if (!onEvent(it)) return }`. At the end call `parser.flush()?.let { onEvent(it) }`.
5. Also accept servers that send **NDJSON without the `data:` prefix**: if a non-empty line starts with `{`, treat it as `SseEvent(null, line)` immediately.

**2.4 `llm/OpenAiCompatClient.kt`**
- URL: `${baseUrl.trimEnd('/')}/chat/completions`. The base URL **includes** `/v1`.
- Headers: `Content-Type: application/json`, `Accept: text/event-stream`, `Authorization: Bearer <key>` only if the key is non-blank, plus `extraHeaders`.
- Body:
  ```json
  {"model": "...", "stream": true,
   "messages": [{"role":"system","content":"..."}, {"role":"user","content":"..."}, ...],
   "temperature": 0.2,            // only if non-null
   "max_tokens": 1024 }           // use "max_completion_tokens" instead when host == "api.openai.com"
  ```
- Event handling: if `data == "[DONE]"`, stop. Otherwise parse JSON. If an `error` object is present, throw `LlmException(error.message)`. Otherwise take `choices[0].delta.content` (string, may be null or absent) and, if non-empty, call `onDelta`. Ignore `reasoning_content`.
- `listModels()`: `GET ${base}/models` returns `data[].id`, sorted.
- Request timeout: `HttpRequest.newBuilder().timeout(Duration.ofMinutes(5))`.

**2.5 `llm/AnthropicClient.kt`**
- URL: `${baseUrl.trimEnd('/')}/v1/messages`. The base URL does **not** include `/v1`.
- Headers: `x-api-key: <key>`, `anthropic-version: 2023-06-01`, `content-type: application/json`, `accept: text/event-stream`.
- Body: `{"model", "max_tokens" (required), "system" (only if non-null), "messages":[{"role":"user"|"assistant","content":"..."}], "stream": true, "temperature"?}`.
- Events (parse `data` as JSON, switch on `type`):
  - `content_block_delta` with `delta.type == "text_delta"` → `onDelta(delta.text)`
  - `message_stop` → stop
  - `error` → throw `LlmException(error.message)`
  - anything else → ignore (including `ping` and `thinking_delta`)
- `listModels()`: `GET ${base}/v1/models` (same headers) returns `data[].id`.

**2.6 `llm/ProviderPresets.kt`**
```kotlin
data class ProviderPreset(val id: String, val name: String, val type: ProviderType, val baseUrl: String,
                          val defaultModel: String, val keyRequired: Boolean)
object ProviderPresets {
    val all = listOf(
        ProviderPreset("anthropic", "Anthropic",            ProviderType.ANTHROPIC,     "https://api.anthropic.com",                                  "claude-sonnet-5-5", true),
        ProviderPreset("openai",    "OpenAI",               ProviderType.OPENAI_COMPAT, "https://api.openai.com/v1",                                  "",                  true),
        ProviderPreset("ollama",    "Ollama (local)",       ProviderType.OPENAI_COMPAT, "http://localhost:11434/v1",                                  "qwen2.5-coder:7b",  false),
        ProviderPreset("omlx",      "oMLX (Apple Silicon)", ProviderType.OPENAI_COMPAT, "http://localhost:8000/v1",                                   "",                  false),
        ProviderPreset("lmstudio",  "LM Studio",            ProviderType.OPENAI_COMPAT, "http://localhost:1234/v1",                                   "",                  false),
        ProviderPreset("llamacpp",  "llama.cpp server",     ProviderType.OPENAI_COMPAT, "http://localhost:8080/v1",                                   "",                  false),
        ProviderPreset("mlxlm",     "mlx_lm.server",        ProviderType.OPENAI_COMPAT, "http://localhost:8080/v1",                                   "",                  false),
        ProviderPreset("vllm",      "vLLM",                 ProviderType.OPENAI_COMPAT, "http://localhost:8000/v1",                                   "",                  false),
        ProviderPreset("openrouter","OpenRouter",           ProviderType.OPENAI_COMPAT, "https://openrouter.ai/api/v1",                               "",                  true),
        ProviderPreset("gemini",    "Google Gemini",        ProviderType.OPENAI_COMPAT, "https://generativelanguage.googleapis.com/v1beta/openai",    "",                  true),
        ProviderPreset("mistral",   "Mistral",              ProviderType.OPENAI_COMPAT, "https://api.mistral.ai/v1",                                  "",                  true),
        ProviderPreset("deepseek",  "DeepSeek",             ProviderType.OPENAI_COMPAT, "https://api.deepseek.com/v1",                                "deepseek-chat",     true),
        ProviderPreset("custom",    "Custom (OpenAI-compatible)", ProviderType.OPENAI_COMPAT, "http://localhost:8000/v1",                            "",                  false),
    )
}
```
(Groq is deliberately left out.) An empty `defaultModel` means the user picks one with **Fetch models**.

**2.7 Tests**
- `SseParsingTest`: multi-line `data`, `event:` lines, comments, a trailing event without a blank line (flush), and NDJSON lines.
- `ClientsHttpTest`: start `com.sun.net.httpserver.HttpServer` on port 0. Serve canned SSE bodies for `/v1/chat/completions` (OpenAI: three deltas + `[DONE]`) and `/v1/messages` (Anthropic: `message_start`, two `content_block_delta`, `message_stop`). Assert the concatenated text, and that the request body contains `"stream":true`, the system prompt is in the right place, and the headers (`x-api-key`, `anthropic-version`, `Authorization`) are correct. Also test that HTTP 401 throws `LlmException` with status 401. Run with `runBlocking`.

**Done when:** `make test` passes with all of the above.

---

### Step 3 — Configuration (global, project override, resolver, secrets)

**3.1 `config/FeatureId.kt`**
```kotlin
enum class FeatureId(val key: String, val fileName: String, val title: String, val usesModel: Boolean) {
    //               snake_case key   kebab-case file name
    COMMIT_MESSAGE("commit_message", "commit-message", "Commit message", true),
    CHAT          ("chat",           "chat",           "Chat", true),
    ASK_SELECTION ("ask_selection",  "ask-selection",  "Ask about selection", false), // only prefill text; requests go via CHAT config
    EXPLAIN_COMMIT("explain_commit", "explain-commit", "Explain commit", true),
}
```
`key` is used in JSON/XML. `fileName` is used for prompt files. Never use the enum `name` or a camelCase string in any persisted data.

**3.2 `config/AiToolsetSettings.kt`**: an app service that uses the XML serializer-friendly classes below (every field a `var` with a default).
```kotlin
// Every persisted field gets an explicit snake_case name (@get:Attribute / @get:Tag / @get:XCollection(propertyElementName=…)),
// so the XML never contains camelCase keys. Persisted enum-like values are snake_case too ("openai_compat", "anthropic").
@Tag("provider")
class ProviderState {
    @get:Attribute("id") var id = ""
    @get:Attribute("name") var name = ""
    @get:Attribute("type") var type = "openai_compat"          // "openai_compat" | "anthropic"
    @get:Attribute("base_url") var baseUrl = ""
    @get:Attribute("default_model") var defaultModel = ""
    @get:Tag("extra_headers") var extraHeaders = ""          // "K: V" per line
}
@Tag("feature")
class FeatureState {
    @get:Attribute("feature_id") var featureId = ""          // FeatureId.key, e.g. "commit_message"
    @get:Attribute("provider_id") var providerId = ""
    @get:Attribute("model") var model = ""
    @get:Tag("system_prompt") var systemPrompt = ""
    @get:Tag("user_template") var userTemplate = ""
    @get:Attribute("temperature") var temperature = ""       // "" = omit
    @get:Attribute("max_tokens") var maxTokens = 1024
    @get:Attribute("max_context_chars") var maxContextChars = 30000
}
class SettingsState {
    @get:XCollection(propertyElementName = "providers", style = XCollection.Style.v2) var providers: MutableList<ProviderState> = mutableListOf()
    @get:XCollection(propertyElementName = "features", style = XCollection.Style.v2) var features: MutableList<FeatureState> = mutableListOf()
    @get:OptionTag("git_executable") var gitExecutable = "git"
}
```
Map `ProviderType` to and from the snake_case strings with a small helper: `ProviderType.fromKey("anthropic")`, `type.key`. Write a unit test that serializes `SettingsState` with `XmlSerializer.serialize(state)` and asserts that the output contains no camelCase (regex `[a-z][A-Z]`) in element or attribute names.
```kotlin
@Service(Service.Level.APP)
@State(name = "LocalAiToolsetSettings", storages = [Storage("local-ai-toolset.xml")])
class AiToolsetSettings : PersistentStateComponent<SettingsState> {
    private var state = SettingsState()
    override fun getState() = state
    override fun loadState(s: SettingsState) { state = s }
    fun feature(id: FeatureId): FeatureState  // returns existing, or creates from Defaults (step 4) and adds it
    fun provider(id: String): ProviderState?
    companion object { fun get(): AiToolsetSettings = service() }
}
```
Register in plugin.xml: `<applicationService serviceImplementation="...config.AiToolsetSettings"/>` (or rely on `@Service` light-service registration; don't do both).

On first use with an empty providers list, **don't** auto-create any provider. The features' `providerId` stays `""`, and running an action shows "No provider configured" plus Open Settings.

**3.3 `config/Secrets.kt`**
```kotlin
object Secrets {
    private fun attrs(providerId: String) = CredentialAttributes(generateServiceName("Local AI Toolset", providerId))
    fun get(providerId: String): String? = PasswordSafe.instance.getPassword(attrs(providerId))   // call off-EDT
    fun set(providerId: String, key: String?) { PasswordSafe.instance.setPassword(attrs(providerId), key?.takeIf { it.isNotBlank() }) }
}
```
Call `get` **only** inside the background coroutine of an action. Never call it at startup or when the settings page opens. The settings page shows a password field that is empty with the placeholder "•••• stored" if a key exists. To check whether a key exists, read it in a background task when the provider is selected in the UI. A blank field on Apply means "keep the existing key". A "Clear key" link removes it.

**3.4 `config/ProjectOverrides.kt`**: files under `<project.guessProjectDir()>/.ai-toolset/`:
```
.ai-toolset/config.json
.ai-toolset/prompts/<feature.fileName>.system.md     e.g. commit-message.system.md
.ai-toolset/prompts/<feature.fileName>.user.md       e.g. commit-message.user.md
```
`config.json` schema (every field optional, no secrets ever, **all keys snake_case**):
```json
{ "features": { "commit_message": { "provider_id": "ollama", "model": "qwen2.5-coder:7b",
                                     "temperature": 0.2, "max_tokens": 400, "max_context_chars": 30000 } } }
```
Unknown keys (including camelCase variants) are ignored, and the action reports a short warning in the error balloon only if **no** known key was found for a feature entry.
API:
```kotlin
data class FeatureOverride(val providerId: String?, val model: String?, val temperature: Double?, val maxTokens: Int?,
                           val maxContextChars: Int?, val systemPrompt: String?, val userTemplate: String?)
class ProjectOverrides(private val root: Path) {           // pure java.nio, testable
    fun read(f: FeatureId): FeatureOverride?               // null if neither config entry nor prompt files exist
    fun write(f: FeatureId, o: FeatureOverride)            // merge into config.json (pretty-printed, 2 spaces), write prompt files when non-null
    fun remove(f: FeatureId)                               // delete entry + prompt files; delete config.json if "features" empty
    fun hasOverride(f: FeatureId): Boolean
    companion object { fun of(project: Project): ProjectOverrides? = project.guessProjectDir()?.toNioPath()?.let(::ProjectOverrides) }
}
```
Read from disk each time it's used; the files are small, so no cache and no VFS listener. After writing, call `VfsUtil.markDirtyAndRefresh(true, true, true, dir)` so the IDE sees the files. A malformed `config.json` → throw `IllegalStateException("Invalid .ai-toolset/config.json: …")`, which the action reports as an error balloon.

**3.5 `config/FeatureResolver.kt`**
```kotlin
data class EffectiveFeature(val feature: FeatureId, val provider: ProviderState, val endpointType: ProviderType,
    val model: String, val systemPrompt: String, val userTemplate: String, val temperature: Double?,
    val maxTokens: Int, val maxContextChars: Int, val scope: Scope)
enum class Scope { GLOBAL, PROJECT }
object FeatureResolver {
    /** Field-by-field: project override value if non-null/non-blank, else global value. */
    fun resolve(global: FeatureState, override: FeatureOverride?, providers: List<ProviderState>): EffectiveFeature
}
```
Errors (throw `ConfigException(message)`):
- No provider id → "No provider configured for <title>."
- Unknown provider id → "Provider '<id>' (from <scope>) does not exist in Settings."
- Blank model after merge → use `provider.defaultModel`. If that is blank too → "No model set for <title>."

`scope = PROJECT` when the override is non-null.

Also add `fun endpoint(p: ProviderState): Endpoint`, which reads the secret. Call it only off the EDT.

**3.6 Tests** (`FeatureResolverTest`): global only; override of only the model; override of the prompt only via files; unknown provider error; model falls back to the provider default. Test `ProjectOverrides` on a temp dir: write → read round-trip, remove deletes files, malformed JSON throws.

**Done when:** `make test` passes.

---

### Step 4 — Prompts: defaults, template engine, output cleaner

**4.1 `prompt/TemplateEngine.kt`**
- Variable names are snake_case only: regex `[a-z][a-z0-9_]*` (e.g. `{recent_commits}`).
- `{name}` → value from the map. **Unknown** names are left as-is (so literal braces in prompts survive).
- `{?name: ...}` is a conditional block. Its content is kept (and itself rendered) only if `name` is non-blank. To find the closing `}`, count `{`/`}` depth from just after `{?name:`. A missing closing brace leaves the text unchanged.
- After rendering, collapse 3+ consecutive newlines to 2.
- `fun render(template: String, vars: Map<String, String>): String`
- `fun ensureContains(template: String, varName: String, suffix: String): String`: if the template doesn't contain `{varName}`, append `suffix`. Used to append `"\n\nDiff:\n{diff}"` to commit templates that lack it.

**4.2 `prompt/OutputCleaner.kt`**: `fun cleanCommitMessage(raw: String): String`:
1. Remove `<think>…</think>` blocks (DOTALL), plus any unterminated leading `<think>` up to the end.
2. Trim.
3. If the text is wrapped in a fenced block (```` ```lang? … ``` ````), unwrap it.
4. Strip a single pair of wrapping quotes (`"`, `'`, `` ` ``).
5. Strip a leading `Commit message:` label (case-insensitive).
6. Trim trailing whitespace on each line, then trim the whole text.

**4.3 `prompt/Defaults.kt`**: the default `FeatureState` for each feature (`providerId = ""`):

*commit_message* (temperature `""`, max_tokens 400, max_context_chars 30000)
- system:
  ```
  You write git commit messages. Output ONLY the commit message: no explanations, no markdown, no code fences.
  Format: Conventional Commits "<type>(<optional scope>): <subject>".
  Types: feat, fix, refactor, perf, docs, test, build, ci, chore, style.
  Subject: imperative mood, lowercase first word, no trailing period, at most 72 characters.
  Add a body (blank line after the subject, lines wrapped at 72 chars) only if the change is not obvious from the subject: explain what and why, not how. Use "- " bullets for multiple points.
  If the recent commits follow a different convention or language, follow theirs instead.
  ```
- user:
  ```
  Branch: {branch}

  Recent commits:
  {recent_commits}
  {?hint:
  Author's hint (high priority): {hint}
  }
  Changed files:
  {files}

  Diff:
  {diff}
  ```

*chat* (temperature `""`, max_tokens 2048, max_context_chars 60000)
- system:
  ```
  You are a concise senior C/C++ engineer helping inside the CLion IDE.
  Answer directly and briefly. Use fenced code blocks with a language tag for code.
  When context files are provided, refer to them by path and line numbers.
  {?context:

  # Context
  {context}}
  ```
- user: `""` (the chat sends the user's text as-is)

*ask_selection* (no model): userTemplate = `Explain this code and point out any bugs:` (it only pre-fills the chat input).

*explain_commit* (max_tokens 1024, max_context_chars 40000)
- system:
  ```
  Explain a git commit to a developer. Structure:
  1. Intent: one or two sentences.
  2. Key changes: short bullets, referencing files.
  3. Risks or things to double-check, only if any.
  Be concise.
  ```
- user: `{commit}`

**4.4 Tests**: `TemplateEngineTest` (simple substitution, unknown placeholder kept, conditional present/absent, nested braces inside the conditional, `ensureContains`). `OutputCleanerTest` (think tags, fences, quotes, label).

**Done when:** `make test` passes.

---

### Step 5 — Settings UI (`Settings → Tools → Local AI Toolset`)

Register it in plugin.xml as a **project** configurable, so it can edit both the global settings and the project override:
```xml
<projectConfigurable parentId="tools" id="com.undici77.aitoolset.settings"
    displayName="Local AI Toolset" instance="com.undici77.aitoolset.config.ui.AiToolsetConfigurable"/>
```
`AiToolsetConfigurable(project: Project) : Configurable` → a `JBTabbedPane` with two tabs.

**Tab "Providers"** (`ProvidersPanel`)
- On the left, a `JBList<ProviderState>` (working **copies** of the settings) wrapped in `ToolbarDecorator`. **+** opens a popup listing the `ProviderPresets` and adds a copy. The id is the preset id, made unique with a suffix `-2`, `-3`… if it already exists. **−** removes it. Disable removal if a feature uses the provider and show why in a tooltip.
- On the right, a form built with Kotlin UI DSL (`panel { row("Name:") { textField() } … }`), bound to the selected copy:
  - Name, Type (combo box: OpenAI-compatible / Anthropic), Base URL (with a comment: "OpenAI-compatible: include /v1 · Anthropic: without /v1")
  - API key (`JBPasswordField`, see the 3.3 rules)
  - Default model (editable `ComboBox<String>`) + **Fetch models** button (background task `Task.Backgroundable`; fills the combo box; on error shows the message inline in red under the button)
  - Extra headers (a small text area, `Key: Value` per line)
  - **Test connection** button: sends `ChatRequest(model, system = null, messages = [user "Reply with: OK"], maxTokens = 16)` and shows inline "✓ OK (latency ms): <first 40 chars>" or "✗ <error>".
- Pending key changes are held in a `Map<providerId, String?>` and written to `Secrets` on **Apply**.

**Tab "Features"** (`FeaturesPanel`)
- At the top, a `ComboBox<FeatureId>` (feature picker).
- A **Scope** segmented button or radio pair: `Global` | `This project (.ai-toolset/)`. Disable "This project" when `project.isDefault`.
- An info label: "Effective now: Project override" / "Effective now: Global", using `FeatureResolver` scope.
- Fields: Provider (combo box of the provider names + "(none)"), Model (editable combo box; the "…" button fetches models from the selected provider), Temperature (text, blank = provider default), Max tokens (int spinner), Max context chars (int spinner), System prompt (`JBTextArea` 10 rows, monospace, in `JBScrollPane`), User template (6 rows). Under the templates, a help label lists the variables for the feature:
  - commit_message: `{diff} {files} {branch} {recent_commits} {hint}`; conditional `{?hint: …}`
  - chat: `{context}`
  - ask_selection: none (prefill text only; Provider/Model/Temperature/Max fields hidden when `usesModel == false`)
  - explain_commit: `{commit}`
- **Reset to defaults** link (fills the form from `Defaults`, applied only on Apply).
- When scope is Project: a **Remove project override** link (calls `ProjectOverrides.remove` on Apply). Loading in project scope fills fields from the resolved values (override over global). On **Apply** in project scope, write **all** form fields to the override (`write`) and leave global untouched. In global scope, write into `AiToolsetSettings`.
- Switching the feature or scope with unsaved edits keeps the edits in an in-memory map keyed by (feature, scope), so nothing is lost before Apply.

`isModified()` compares working copies with the stored state. `apply()` saves everything. `reset()` reloads.

**Done when:** `make build verify` passes. Manual check on the host (install the zip): add Ollama, Fetch models works, Test connection works, edit a commit prompt in project scope → `.ai-toolset/config.json` (snake_case keys) and `.ai-toolset/prompts/commit-message.system.md` appear in the project.

---

### Step 6 — Core services: scope, notifier, git

**6.1 `core/AiScope.kt`**
```kotlin
@Service(Service.Level.PROJECT)
class AiScope(val project: Project, val cs: CoroutineScope) {
    companion object { fun get(p: Project): AiScope = p.service() }
}
```

**6.2 `core/Notifier.kt`**: `fun error(project: Project, title: String, message: String)` → `NotificationGroupManager.getInstance().getNotificationGroup("AI Toolset").createNotification(title, message, NotificationType.ERROR).addAction(NotificationAction.createSimpleExpiring("Open Settings") { ShowSettingsUtil.getInstance().showSettingsDialog(project, AiToolsetConfigurable::class.java) }).notify(project)`.
Map exceptions to short messages: `ConfigException` → its message. `LlmException` 401/403 → "Authentication failed for <provider>. Check the API key." `ConnectException` → "Cannot reach <baseUrl>. Is the server running?" `HttpTimeoutException` → "Timed out talking to <baseUrl>." Anything else → `e.message`. `CancellationException` is **never** shown (rethrow it).

**6.3 `git/Git.kt`**: runs only off the EDT.
```kotlin
class Git(private val exe: String = AiToolsetSettings.get().state.gitExecutable) {
    fun run(root: Path, vararg args: String, timeoutMs: Int = 15_000): String {
        val cmd = GeneralCommandLine(exe, *args).withWorkingDirectory(root).withCharset(Charsets.UTF_8)
            .withEnvironment("GIT_PAGER", "cat").withEnvironment("LC_ALL", "C")
        val out = ExecUtil.execAndGetOutput(cmd, timeoutMs)
        if (out.exitCode != 0 || out.isTimeout) throw GitException(out.stderr.ifBlank { "git ${args.joinToString(" ")} failed" })
        return out.stdout
    }
    fun branch(root: Path) = runCatching { run(root, "rev-parse", "--abbrev-ref", "HEAD").trim() }.getOrDefault("")
    fun recentSubjects(root: Path, n: Int = 10) = runCatching { run(root, "log", "-n", "$n", "--no-merges", "--pretty=format:- %s") }.getOrDefault("")
    fun hasHead(root: Path) = runCatching { run(root, "rev-parse", "--verify", "HEAD"); true }.getOrDefault(false)
    fun diffAgainstHead(root: Path, relPaths: List<String>): String  // see 6.4
    fun show(root: Path, hash: String) = run(root, "show", "--no-color", "--no-ext-diff", "--stat", "--patch", "-M", hash, timeoutMs = 30_000)
}
```
`diffAgainstHead`: `base = if (hasHead) "HEAD" else "4b825dc642cb6eb9a060e54bf8d69288fbee4904"` (git's empty-tree hash). Run `git diff --no-color --no-ext-diff -M <base> -- <paths…>`. Split paths into batches of 200 to keep the command line short, and concatenate.

**Note:** in IntelliJ's changelist mode, the checked changes are not staged, so diffing the working tree against HEAD is correct. Partially-checked lines (partial commits) produce the diff of the whole file. That's acceptable in v1; mention it in the README.

**6.4 `git/DiffBuilder.kt`** (pure, unit-tested)
```kotlin
data class DiffResult(val diff: String, val files: String, val truncated: Boolean)
object DiffBuilder {
    /** rawDiffs: git diff output (may be several concatenated). untracked: (relPath, contentOrNull). */
    fun build(rawDiffs: String, untracked: List<Pair<String, String?>>, budget: Int, perFileCap: Int = 6000): DiffResult
}
```
Algorithm:
1. Split `rawDiffs` into file chunks at lines starting with `diff --git `.
2. For each untracked file, create a chunk `"new file (untracked): <path>\n" + content.take(perFileCap)`. Skip the content when it is null or looks binary (contains `\u0000`).
3. Ignore the content of noise files and keep only their header: `*.lock`, `package-lock.json`, `pnpm-lock.yaml`, `*.min.js`, `*.svg`, `*.map`. Replace the body with `"[content omitted]"`.
4. Cut each chunk to `perFileCap`, appending `"\n… [truncated N chars]"`.
5. Add chunks in order while the total stays ≤ `budget`. List the remaining files at the end under `"[diff omitted for N more files: a, b, c]"` and set `truncated = true`.
6. `files` = one line per file: `M path` / `A path` / `D path` / `R old -> new`, taken from the chunk headers (`new file mode`, `deleted file mode`, `rename from/to`). Untracked files → `A path`.

Tests: two files within budget; over budget → omitted list; per-file cap; lock file omitted; rename detection; untracked binary skipped.

**Done when:** `make test` passes.

---

### Step 7 — Generate commit message

**7.1 plugin.xml**
```xml
<actions>
  <action id="LocalAiToolset.GenerateCommitMessage"
          class="com.undici77.aitoolset.commit.GenerateCommitMessageAction"
          text="Generate Commit Message (AI)" description="Generate a commit message from the checked changes"
          icon="/icons/ai-sparkle.svg">
    <add-to-group group-id="Vcs.MessageActionGroup" anchor="first"/>
    <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt G"/>
  </action>
  <action id="LocalAiToolset.GenerateCommitMessageWithHint"
          class="com.undici77.aitoolset.commit.GenerateWithHintAction"
          text="Generate Commit Message with Hint (AI)…">
    <add-to-group group-id="Vcs.MessageActionGroup" anchor="after" relative-to-action="LocalAiToolset.GenerateCommitMessage"/>
  </action>
</actions>
```
If `ctrl alt G` conflicts in the default keymap, don't register any shortcut. Users can assign one themselves. To check: in the sandbox, Settings → Keymap → search "ctrl alt G".

If the second action makes the commit toolbar too busy, the implementer may instead show it only when **Alt+click** is pressed on the main action. Simplest version: keep both actions but put the hint action **only** in the `Vcs.MessageActionGroup` overflow. Decide visually during the manual test and note the decision in the CHANGELOG.

**7.2 `commit/CommitMessageService.kt`** (project service)
```kotlin
@Service(Service.Level.PROJECT)
class CommitMessageService(private val project: Project) {
    @Volatile var job: Job? = null; val isRunning get() = job?.isActive == true
    fun cancel() { job?.cancel() }
    fun start(ui: CommitWorkflowUi, hint: String?)
}
```
`start(ui, hint)`:
1. On the EDT, collect: `val changes = ui.getIncludedChanges()`, `val unversioned = ui.getIncludedUnversionedFiles()`, `val previous = ui.commitMessageUi.text`. If both lists are empty, show the error "No changes are checked for commit."
2. `job = AiScope.get(project).cs.launch { … }`. Inside the coroutine:
   - `withContext(Dispatchers.IO)`:
     - Resolve the feature: `FeatureResolver.resolve(settings.feature(COMMIT_MESSAGE), ProjectOverrides.of(project)?.read(COMMIT_MESSAGE), settings.state.providers)`.
     - Group paths by VCS root: for each change, `ChangesUtil.getFilePath(change)`. Then `ProjectLevelVcsManager.getInstance(project).getVcsRootObjectFor(filePath)`, keeping only roots where `vcs.name == "Git"`. If there are none, throw `ConfigException("Generate commit message currently supports Git only.")`.
     - For each root, take the paths relative to it and call `Git.diffAgainstHead`. Untracked: read the content with `VfsUtilCore.loadText` (inside `readAction {}`), or null if the file is larger than 200 KB.
     - Call `DiffBuilder.build(...)` with `budget = feature.maxContextChars`.
     - `branch` and `recent_commits` come from the first root.
     - Template: `TemplateEngine.ensureContains(feature.userTemplate, "diff", "\n\nDiff:\n{diff}")`, then `render` with variables `diff, files, branch, recent_commits, hint`. Render the system prompt with the same variables.
     - `endpoint = FeatureResolver.endpoint(feature.provider)` (reads the secret here).
   - Streaming: keep an accumulator. On each delta, append, then update the UI at most every 60 ms: `withContext(Dispatchers.EDT) { ui.commitMessageUi.text = acc.toString() }`. A sending-side throttle is enough (compare `System.nanoTime()`).
   - Finished: `withContext(Dispatchers.EDT) { ui.commitMessageUi.text = OutputCleaner.cleanCommitMessage(acc.toString()) }`. If the result is blank, restore `previous` and report the error "Model returned an empty message."
   - Cancelled: restore `previous` if nothing was streamed yet, otherwise keep the partial text **cleaned**.
   - Error: restore `previous` and call `Notifier.error`.
3. Wrap the call in `try/finally { job = null }`, and make the action refresh its presentation (`ActivityTracker.getInstance().inc()`).

**7.3 `commit/GenerateCommitMessageAction.kt`**
```kotlin
class GenerateCommitMessageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val p = e.project; val ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        e.presentation.isEnabledAndVisible = p != null && ui != null
        if (p != null && p.service<CommitMessageService>().isRunning) {
            e.presentation.icon = AllIcons.Actions.Suspend; e.presentation.text = "Stop Generating"
        } else { e.presentation.icon = Icons.Sparkle; e.presentation.text = "Generate Commit Message (AI)" }
    }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return; val ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        val svc = p.service<CommitMessageService>()
        if (svc.isRunning) svc.cancel() else svc.start(ui, hint = null)
    }
}
```
`Icons` object: `IconLoader.getIcon("/icons/ai-sparkle.svg", Icons::class.java)` and the same for `ai-chat.svg`.

**7.4 `commit/GenerateWithHintAction.kt`**: the same `update` rules. `actionPerformed` opens `Messages.showInputDialog(project, "What is this change about? (optional)", "Commit Message Hint", null)`; null means the user cancelled. Then call `svc.start(ui, hint)`. *(This is the only dialog in the plugin, and it opens only when the user asks for it.)*

**Done when:** `make build verify` passes. Manual check on the host: with Ollama and Anthropic, checking 2 of 3 changed files streams a message that mentions only those 2. Clicking the button during generation stops it. A project prompt override is used. A server that is down shows one error balloon with "Open Settings".

---

### Step 8 — Chat tool window

**8.1 plugin.xml**
```xml
<toolWindow id="AI Chat" anchor="right" secondary="false" icon="/icons/ai-chat.svg"
            factoryClass="com.undici77.aitoolset.chat.ChatToolWindowFactory" doNotActivateOnStart="true"/>
```
`ChatToolWindowFactory : ToolWindowFactory, DumbAware`. `createToolWindowContent` adds a single content `ChatPanel(project)` with `isCloseable = false`. Add a title-bar action "New Chat" (`AllIcons.General.Add`) via `toolWindow.setTitleActions(...)`, which calls `ChatService.clear()`.

**8.2 `chat/ContextItem.kt`**
```kotlin
data class ContextItem(val file: VirtualFile, val lines: IntRange? /* 1-based inclusive, null = whole file */) {
    val label: String get() = file.name + (lines?.let { ":${it.first}-${it.last}" } ?: "")
}
```

**8.3 `chat/ChatService.kt`** (project service; the single source of truth)
```kotlin
@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) {
    interface Listener { fun contextChanged() {}; fun messageAdded(m: ChatMessage) {}; fun streamDelta(text: String) {}; fun streamEnded(error: String?) {} ; fun prefill(text: String) {} }
    val context = mutableListOf<ContextItem>()        // EDT-only access
    val history = mutableListOf<ChatMessage>()        // USER/ASSISTANT
    var modelOverride: Pair<String /*providerId*/, String /*model*/>? = null   // from the panel's combo box; null = feature config
    private val listeners = ContainerUtil.createLockFreeCopyOnWriteList<Listener>()
    fun addListener(l: Listener, parent: Disposable)
    fun addContext(items: List<ContextItem>)          // de-duplicate by (file, lines); fire contextChanged
    fun removeContext(item: ContextItem)
    fun clear()                                       // cancel the running job, clear history (keep context), fire contextChanged
    fun prefill(text: String)                         // fire prefill
    fun send(userText: String, feature: FeatureId = FeatureId.CHAT, vars: Map<String,String> = emptyMap())
    fun stop()
    val isRunning: Boolean
}
```
`send()`:
1. Add `ChatMessage(USER, userText)` to `history` and fire `messageAdded`.
2. Launch in `AiScope`:
   - Resolve the `feature` config (CHAT or EXPLAIN_COMMIT). If `modelOverride != null` and `feature == CHAT`, replace provider and model with it.
   - Build `{context}` (CHAT only): for each `ContextItem`, `readAction { }` loads the file text and slices the lines. Format:
     ````
     ## <project-relative path>[ (lines a-b)]
     ```<extension>
     <content>
     ```
     ````
     Add items until the total is ≤ `maxContextChars`. Items that don't fit are replaced by the line `[omitted: <label>, too large]`.
   - `system = TemplateEngine.render(feature.systemPrompt, vars + ("context" to ctx))`.
   - Messages = the whole `history`, with the last message's user text rendered through `feature.userTemplate` if that template is non-blank (EXPLAIN_COMMIT uses `{commit}` from `vars`).
   - Stream: forward each delta via `streamDelta`. At the end, add the `ChatMessage(ASSISTANT, acc)` to history and fire `streamEnded(null)`. On error, fire `streamEnded(message)` and **don't** add a balloon: the error shows inline in the chat. Exception: config errors also get a balloon, so the user gets the "Open Settings" link.
3. If a send is already running, ignore the new one (the panel disables the Send button).

History is kept **in memory** only and is lost when the IDE closes. That's fine for v1.

**8.4 `chat/ChatPanel.kt`** (`SimpleToolWindowPanel(vertical = true)`, implements `ChatService.Listener` and `Disposable`; register as a child of the tool window's disposable)
Layout from top to bottom:
1. **Messages**: `JBScrollPane` around a vertical box (`VerticalLayout(JBUI.scale(8))`) of `MessageView`s. Auto-scroll to the bottom while streaming, but only if the user was already at the bottom.
   - While no messages exist, show a centred hint (`StatusText` / `JBLabel`, greyed out): "Ask anything. Add files with right-click → Add to AI Chat, or drag them here."
2. **Context chips row**: a `JPanel(WrapLayout(FlowLayout.LEFT))`. Each chip is a small rounded `JBLabel` with the file icon, `item.label`, and an `×` that calls `removeContext`. The row ends with a `+` link ("Add current file", from `FileEditorManager.selectedEditor`) and a grey label `~N tokens` (sum of the content chars / 4, computed in a background `readAction`). Hide the row's content when the context is empty, except for the `+` link.
3. **Input**: a `JBTextArea` (3–8 rows, grows with the text, line wrap) inside `JBScrollPane`, with the placeholder "Ask… (Ctrl+Enter to send)". Below it, in a row: the model `ComboBox` (entries "<provider name> · <model>" for every provider's default model, plus the chat feature's resolved model selected; picking an entry sets `ChatService.modelOverride`), then a button on the right: **Send**, which turns into **Stop** while running.
   - `Ctrl+Enter` / `Cmd+Enter` sends. `Esc` while running stops.
4. **Drag & drop**: put a `TransferHandler` on the whole panel that accepts `DataFlavor.javaFileListFlavor` (and `FileCopyPasteUtil.isFileListFlavorAvailable`). Convert each file with `LocalFileSystem.getInstance().findFileByNioFile`. Directories expand to their files (non-recursive, max 20, skipping binary file types via `file.fileType.isBinary`).

Listener behaviour:
- `messageAdded(USER)` → append a user `MessageView` (plain text, subtle background `JBUI.CurrentTheme.ActionButton.hoverBackground()`).
- First `streamDelta` → append an assistant `MessageView` in "streaming" mode (plain `JBTextArea`, not editable). Later deltas append to it.
- `streamEnded(null)` → switch that view to its rendered form (`MessageView.render(markdown)`).
- `streamEnded(err)` → render the partial text if there is any, plus a red `JBLabel` with the error and a "Retry" link (resends the last user message).
- `prefill(text)` → set the input text, put the caret at the end, and request focus.

**8.5 `chat/MarkdownLite.kt`** (pure, unit-tested)
```kotlin
sealed interface Segment { data class Text(val md: String) : Segment; data class Code(val lang: String, val code: String) : Segment }
object MarkdownLite {
    fun split(md: String): List<Segment>     // fenced blocks ``` or ~~~ with optional lang; an unterminated fence → Code until end
    fun toHtml(md: String): String           // escape HTML first; then: `code`, **bold**, *italic*, # / ## / ### headings → <b>, lines starting "- " or "* " → <ul><li>, "1. " → <ol><li>, blank line → paragraph break, [text](url) → <a href>
}
```

**8.6 `chat/MessageView.kt`**
- Rendered mode: one component per segment.
  - Text → `JEditorPane("text/html", html)` using `HTMLEditorKitBuilder().withWordWrapViewFactory().build()`, not editable, transparent background, `putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)` so the IDE font is used. Links open with `BrowserUtil.browse`.
  - Code → a panel with a small header row (lang label, and on the right the links **Copy** (`CopyPasteManager.getInstance().setContents(StringSelection(code))`) and **Insert at Caret**), plus a body `EditorTextField(code, project, fileType)` with `setOneLineMode(false)`, `isViewer = true`, and `addSettingsProvider { it.settings.isLineNumbersShown = false; it.setHorizontalScrollbarVisible(true) }`. `fileType = FileTypeManager.getInstance().getFileTypeByExtension(langToExt(lang))` with a small map: `cpp/c++→cpp, c→c, h→h, cmake→cmake, py/python→py, sh/bash→sh, json, yaml/yml, kotlin/kt→kt, java, js, ts, rust/rs, go, xml, md` (default `txt`).
  - **Insert at Caret**: `FileEditorManager.getInstance(project).selectedTextEditor`. If it's null, show an inline message "No editor open". Otherwise `WriteCommandAction.runWriteCommandAction(project, "Insert AI Code", null, { if (sel) replace selection else insertString at caret }, psiFile?)`.

**8.7 Tests**: `MarkdownLiteTest`: text/code split, unterminated fence, HTML escaping (`<script>` stays inert), lists, inline code.

**Done when:** `make test build verify` passes. Manual check: open the tool window, ask a question, and the answer streams. Code blocks render with Copy/Insert. Stop works. The model combo box switches the provider. The window never opens on its own when the IDE starts.

---

### Step 9 — Add to AI Chat, Ask AI about selection

**9.1 `chat/actions/AddToChatAction.kt`** (`DumbAwareAction`, BGT update)
- `update`: visible when `CommonDataKeys.VIRTUAL_FILE_ARRAY` is non-empty.
- `perform`: expand directories (recursive, maximum 50 files, skipping binaries and `.git`/build dirs: `build`, `cmake-build-*`, `out`, `.idea`, `node_modules`). Call `ChatService.addContext(items)`. **Do not** open the tool window. Show a brief status-bar message instead: `StatusBar.Info.set("Added N file(s) to AI Chat", project)`. Directory expansion runs off the EDT if it takes long; use `ReadAction.nonBlocking` or a coroutine.
- plugin.xml groups: `ProjectViewPopupMenu` (anchor last) and `EditorTabPopupMenu` (anchor last). Text: "Add to AI Chat", icon `ai-chat.svg`.

**9.2 `chat/actions/AskAboutSelectionAction.kt`**
- `update`: visible only when `CommonDataKeys.EDITOR` has a selection.
- `perform`: compute 1-based line numbers from the selection start and end (`document.getLineNumber(offset) + 1`). Then `addContext(ContextItem(file, a..b))`. Then `ToolWindowManager.getInstance(project).getToolWindow("AI Chat")?.activate { ChatService.prefill(<effective askSelection userTemplate>) }`. **Doesn't send automatically.**
- plugin.xml: `EditorPopupMenu` (anchor last), text "Ask AI About Selection", icon `ai-chat.svg`.

Note: `ContextItem` with a line range re-reads the file when the message is sent, so it always reflects the current content.

**Done when:** manual check: right-click two files → chips appear with a token estimate. Select code → right-click → the chat opens with the chip and the input pre-filled and focused, and nothing is sent until Ctrl+Enter.

---

### Step 10 — Explain commit (Git log)

**10.1 `vcs/ExplainCommitAction.kt`** (`DumbAwareAction`, `ActionUpdateThread.BGT`)
- `update`: `val sel = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)`. Visible only when exactly one commit is selected (`sel.commits.size == 1`).
- `perform`: `val c = sel.commits.single()` (a `CommitId` with `.hash.asString()` and `.root: VirtualFile`). Activate the "AI Chat" tool window. Then, in a coroutine on IO: `val text = Git().show(c.root.toNioPath(), hash)`, truncated to `maxContextChars` of EXPLAIN_COMMIT with `"\n… [truncated]"`. Then call `ChatService.send(userText = "Explain commit ${hash.take(10)}", feature = EXPLAIN_COMMIT, vars = mapOf("commit" to text))`. In `send` for EXPLAIN_COMMIT, the API message content is the rendered userTemplate (`{commit}`). The **displayed** user message is still the short "Explain commit abc123…". So store the display text in history, and substitute the rendered content when building the API messages for **that** turn. Implement this with a `ChatMessage` field `apiContent: String? = null`, and use `apiContent ?: content` when building requests.
- If the `VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION` / `CommitId` API differs in 2025.3, find the equivalent "selected commits" data key in `com.intellij.vcs.log` and keep the behaviour.
- plugin.xml: `<add-to-group group-id="Vcs.Log.ContextMenu" anchor="last"/>`, text "Explain Commit (AI)", icon `ai-sparkle.svg`.

**Done when:** manual check: in the Git log, right-click a commit → Explain Commit → the chat opens and streams an explanation. Follow-up questions in the same chat work, because the history contains the commit diff via `apiContent`.

---

### Step 11 — Docs, polish, final checks

1. **README.md** (short, mostly tables):
   - What it does: the 5 features, each with its entry point.
   - Setup for each provider: the preset table (base URL, whether a key is needed) plus one-liners:
     - Ollama: `ollama serve` + `ollama pull qwen2.5-coder:7b`
     - oMLX: start the app or server (default `:8000`)
     - LM Studio: enable the server (port 1234)
   - Project overrides: show the `.ai-toolset/` layout and an example `config.json`. Say that it's safe to commit (no secrets) and that keys stay in the IDE password store.
   - Template variables per feature.
   - Privacy: data is sent only when you click, only to the provider you chose.
   - Known limitations: Git only; a partial commit sends the diff of the whole file; chat history isn't persisted.
   - Build: `make image wrapper test build verify`; install the zip via *Settings → Plugins → ⚙ → Install Plugin from Disk*.
2. **CHANGELOG.md**: `0.1.0`, listing the features and the chosen versions of Kotlin, IPGP and Gradle.
3. Review against the hard rules in section 1. Search the code for `PasswordSafe`, which must appear only in `Secrets.kt`, and check that every call site runs off the EDT inside actions. Search for `ProjectActivity`/`StartupActivity`: there must be none.
4. `make test build verify`: everything green, with no verifier compatibility problems or internal-API usages for CLion 2025.3. Fix any **internal** API warnings by switching to public APIs. Deprecation warnings are acceptable but must be listed in the CHANGELOG.

**Done when:** the user has installed the zip and confirmed the manual checks in section 5. **Stop here and ask the user before starting Step 12.**

---

### Step 12 — Signing + JetBrains Marketplace publishing (local, through Docker)

**12.1 Changelog-driven change notes.** Add the plugin `id("org.jetbrains.changelog") version "<latest 2.x>"` to `build.gradle.kts`. Keep `CHANGELOG.md` in the "Keep a Changelog" format (`## [Unreleased]`, `## [0.1.0] - 2026-MM-DD`). Wire it like this:
```kotlin
changelog { groups.empty(); repositoryUrl = "<repo url, when known>" }
intellijPlatform {
    pluginConfiguration {
        changeNotes = providers.gradleProperty("pluginVersion").map { v ->
            with(changelog) { renderItem((getOrNull(v) ?: getUnreleased()).withHeader(false).withEmptySections(false), org.jetbrains.changelog.Changelog.OutputType.HTML) }
        }
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map { readme ->
            // take the text between <!-- plugin-description --> and <!-- plugin-description-end --> and convert to HTML
            org.jetbrains.changelog.markdownToHTML(readme.substringAfter("<!-- plugin-description -->").substringBefore("<!-- plugin-description-end -->"))
        }
    }
}
```
Add these markers around a 3–6 line description at the top of `README.md`. Once this works, remove the `<description>` from `plugin.xml`.

**12.2 Signing certificate** (one time, done by the user, **never committed**):
```bash
mkdir -p ~/.secrets/local-ai-toolset && cd ~/.secrets/local-ai-toolset
openssl genpkey -aes-256-cbc -algorithm RSA -out private-key.pem -pkeyopt rsa_keygen_bits:4096
openssl req -key private-key.pem -new -x509 -days 3650 -out certificate-chain.crt
```
Add `*.pem`, `*.crt`, `.env` and `secrets/` to `.gitignore` and `.dockerignore`.

**12.3 Gradle signing + publishing** (all values from environment variables, never hard-coded):
```kotlin
intellijPlatform {
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // "0.2.0-beta.1" -> channel "beta"; "0.2.0" -> "default"
        channels = providers.gradleProperty("pluginVersion").map { v ->
            listOf(v.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
}
```

**12.4 Docker + Make wiring.** In `docker-compose.yml`, pass the variables through to the `build` service:
```yaml
    environment:
      - CERTIFICATE_CHAIN
      - PRIVATE_KEY
      - PRIVATE_KEY_PASSWORD
      - PUBLISH_TOKEN
```
`Makefile` additions:
```make
SECRETS ?= $(HOME)/.secrets/local-ai-toolset
sign:    ; CERTIFICATE_CHAIN="$$(cat $(SECRETS)/certificate-chain.crt)" PRIVATE_KEY="$$(cat $(SECRETS)/private-key.pem)" \
           PRIVATE_KEY_PASSWORD="$$(cat $(SECRETS)/private-key-password.txt)" $(DC) ./gradlew --no-daemon signPlugin
publish: ; CERTIFICATE_CHAIN="$$(cat $(SECRETS)/certificate-chain.crt)" PRIVATE_KEY="$$(cat $(SECRETS)/private-key.pem)" \
           PRIVATE_KEY_PASSWORD="$$(cat $(SECRETS)/private-key-password.txt)" PUBLISH_TOKEN="$$(cat $(SECRETS)/publish-token.txt)" \
           $(DC) ./gradlew --no-daemon test verifyPlugin publishPlugin
```
`signPlugin` writes `build/distributions/local-ai-toolset-<ver>-signed.zip`.

**12.5 First release (manual steps, done by the user):**
1. Create a JetBrains Marketplace account/vendor ("Undici77") and a permanent token (*My Profile → Tokens*). Save it to `~/.secrets/local-ai-toolset/publish-token.txt`.
2. The **first** upload must go through the web UI (plugins.jetbrains.com → Upload plugin), using the `make sign` output. Choose a licence and category ("Tools Integration"). Visibility can stay *hidden* until review passes.
3. Later versions: bump `pluginVersion` and move the `[Unreleased]` changelog entries under a version header (`./gradlew patchChangelog`), then run `make publish`.

**12.6 Marketplace review checklist** (fix before uploading): English description ≥ 40 characters, no "IntelliJ"/"JetBrains" in the plugin name, `pluginIcon.svg` (+ optional `pluginIcon_dark.svg`) present, vendor set, `sinceBuild` set, Plugin Verifier clean, no bundled Kotlin stdlib.

**Done when:** `make sign` produces a signed zip and `./gradlew verifyPluginSignature` passes (run inside `make shell` with the same environment variables). The first version is uploaded by the user.

---

### Step 13 — CI/CD (GitHub Actions, reusing Docker + Make)

Ask the user first which CI they use. The default is **GitHub Actions**. If they use GitLab CI, translate the same jobs, which only call `make`. Before this step, pin the Docker base image in `docker/Dockerfile` to an exact tag (e.g. `eclipse-temurin:21.0.x_y-jdk-noble`).

**13.1 Configurable Gradle cache for CI.** In `docker-compose.yml`, change the volume to `- ${GRADLE_CACHE:-gradle-cache}:/home/dev/.gradle`. Locally it stays a named volume. In CI, set `GRADLE_CACHE=${{ github.workspace }}/.gradle-docker` (a bind-mounted directory that `actions/cache` can save). Add `.gradle-docker/` to `.gitignore` and `.dockerignore`.

**13.2 `.github/workflows/build.yml`**: on `push` to any branch and on `pull_request`:
```yaml
name: build
on: { push: { branches: ["**"] }, pull_request: {} }
jobs:
  build:
    runs-on: ubuntu-latest
    env: { GRADLE_CACHE: "${{ github.workspace }}/.gradle-docker" }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/cache@v4
        with:
          path: .gradle-docker
          key: gradle-${{ hashFiles('**/*.gradle.kts', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties') }}
          restore-keys: gradle-
      - run: mkdir -p .gradle-docker && export UID GID=$(id -g) && make image test build verify
      - uses: actions/upload-artifact@v4
        with: { name: plugin-zip, path: build/distributions/*.zip }
      - if: always()
        uses: actions/upload-artifact@v4
        with: { name: reports, path: "build/reports/**" }
```
(Pin each action to the latest major at implementation time. Disk space: the CLion download is large. If the runner runs out of space, add a step that frees space by removing `/usr/share/dotnet` and `/opt/ghc` before building.)

**13.3 `.github/workflows/release.yml`**: on push of tags `v*`:
1. Check that the tag matches `pluginVersion` (`test "v$(grep ^pluginVersion= gradle.properties | cut -d= -f2)" = "$GITHUB_REF_NAME"`) and fail otherwise.
2. Restore the same cache, then run `make image test verify`.
3. Publish with the repository secrets `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN` exported as env: `docker compose run --rm build ./gradlew --no-daemon signPlugin publishPlugin`. Don't use `make publish` here, because that target reads files from `~/.secrets`.
4. `gh release create "$GITHUB_REF_NAME" build/distributions/*-signed.zip --notes-file <(./gradlew -q getChangelog --no-header)` (run `getChangelog` inside the container too), with `permissions: contents: write`.

**13.4 Repository settings (done by the user):** add the 4 secrets. Optionally protect the `v*` tags. Add a `README.md` badge for the build workflow.

**Done when:** a push runs `build` green and uploads the zip artifact. Pushing tag `v0.1.1` (after bumping the version) publishes to the Marketplace and creates a GitHub Release with the signed zip.

---

## 5. Verification (end-to-end)

| Check | How |
|---|---|
| Unit tests | `make test`: TemplateEngine, OutputCleaner, SSE parser, both HTTP clients against a fake server, DiffBuilder, FeatureResolver/ProjectOverrides, MarkdownLite |
| Build + compatibility | `make build verify` → zip in `build/distributions/`, Plugin Verifier clean for CLion 2025.3 + latest |
| Manual (host CLion) | Install the zip. **Commit:** check a subset of files → ✨ streams a message that covers only those files. Click again cancels. Hint dialog works. `.ai-toolset/prompts/commit-message.system.md` changes the output. **Chat:** files added via right-click and drag, Ask about selection pre-fills without sending, code-block Copy/Insert works, Stop works, switching models works. **Explain commit:** works from the Git log. **Providers:** Ollama + Anthropic + one more (oMLX or LM Studio) via Test connection. **Non-invasive:** restart the IDE → no tool window opens, no keychain prompt, no network traffic (check with the provider server logs) |
| Errors | Stop the local server → one balloon "Cannot reach … Open Settings". Wrong API key → "Authentication failed". No provider configured → "No provider configured for Commit message" |
| Naming rule | `find . -path ./build -prune -o -path ./.gradle -prune -o -type f -not -name '*.kt' -print | grep -E '/[^/]*[a-z][A-Z][^/]*$'` lists only platform-imposed names (`pluginIcon.svg`, `gradlew.bat` etc.). The XML-serialization unit test finds no camelCase keys. `.ai-toolset/config.json` uses snake_case keys |
| Release (Step 12) | `make sign` → signed zip; `verifyPluginSignature` passes; first upload accepted by Marketplace review |
| CI/CD (Step 13) | `build` workflow green on push; tag `vX.Y.Z` → Marketplace publish + GitHub Release |

## 6. Out of scope (do not implement)
Autocomplete or inline edits, agents and tool calling, RAG or indexing, persisted chat history, PR descriptions, self-review, non-Git VCS.
