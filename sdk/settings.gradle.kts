pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // One source of truth for versions: the app's catalog. The SDK and the app it was
    // extracted from stay on the same Kotlin / Ktor / serialization versions.
    versionCatalogs {
        create("libs") { from(files("../mobile/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "keystone-llm"

include(":llm-core")
include(":llm-ktor")
include(":llm-openai")
include(":llm-gemini")
include(":llm-ondevice")
include(":samples:jvm-cli")
