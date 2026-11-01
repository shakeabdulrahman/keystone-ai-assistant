import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

kotlin {
    // Every public declaration needs an explicit visibility and return type, so nothing
    // becomes public API by accident.
    explicitApi()

    jvmToolchain(17)
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        publishLibraryVariants("release")
    }
    // Plain JVM: fast unit tests on any OS, and usable from JVM backends.
    jvm()
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        optIn.add("io.github.shakeabdulrahman.keystone.llm.InternalKeystoneLlmApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            // JsonObject / JsonElement appear in the public API (tool schemas, arguments).
            api(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "io.github.shakeabdulrahman.keystone.llm"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))
