// Shared POM metadata for every published module.
//
// This only configures *what* would be published. It does not configure signing, a staging
// repository or credentials, and nothing here uploads anything. `./gradlew publishToMavenLocal`
// is the only publishing task expected to work today. See docs/releasing.md.

configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        // Kotlin Multiplatform names the root publication "kotlinMultiplatform"; the platform
        // publications get suffixes (-android, -jvm, -iosarm64, ...). Prefix all of them.
        artifactId = if (name == "kotlinMultiplatform") {
            "keystone-${project.name}"
        } else {
            "keystone-${project.name}-${name.lowercase()}"
        }
        pom {
            name.set("Keystone LLM: ${project.name}")
            description.set("Kotlin Multiplatform LLM client for Android and iOS: streaming, tools, structured output, failover.")
            url.set("https://github.com/shakeabdulrahman/keystone-ai-assistant")
            // TODO(release): a license must be chosen before any public release. See docs/releasing.md.
            developers {
                developer {
                    id.set("shakeabdulrahman")
                    name.set("Rahman")
                }
            }
            scm {
                url.set("https://github.com/shakeabdulrahman/keystone-ai-assistant")
                connection.set("scm:git:https://github.com/shakeabdulrahman/keystone-ai-assistant.git")
            }
        }
    }
}
