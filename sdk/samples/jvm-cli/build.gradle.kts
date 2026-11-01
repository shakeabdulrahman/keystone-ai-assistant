plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// A runnable JVM sample. It reads keys from environment variables only.
kotlin {
    jvmToolchain(17)
    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        mainRun { mainClass.set("io.github.shakeabdulrahman.keystone.llm.sample.MainKt") }
    }
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":llm-openai"))
            implementation(project(":llm-gemini"))
        }
    }
}
