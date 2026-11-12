import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
    id("org.jetbrains.changelog") version "2.2.1"
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
        bundledModule("intellij.platform.vcs.log")
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

changelog {
    groups.empty()
    repositoryUrl = "https://github.com/undici77/local-ai-toolset"
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        // Rendered eagerly: capturing the changelog extension in a lazy provider breaks the configuration cache.
        changeNotes = providers.gradleProperty("pluginVersion").get().let { v ->
            with(changelog) {
                renderItem(
                    (getOrNull(v) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    org.jetbrains.changelog.Changelog.OutputType.HTML,
                )
            }
        }
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map { readme ->
            org.jetbrains.changelog.markdownToHTML(
                readme.substringAfter("<!-- plugin-description -->").substringBefore("<!-- plugin-description-end -->").trim()
            )
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
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
    pluginVerification {
        ides {
            create(IntelliJPlatformType.CLion, "2025.3")
        }
    }
}
