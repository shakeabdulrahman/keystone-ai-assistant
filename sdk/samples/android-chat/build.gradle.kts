import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Demo keys come from sdk/local.properties (gitignored) or environment variables:
//   groq.apiKey=...      groq.model=openai/gpt-oss-120b
//   gemini.apiKey=...    gemini.model=gemini-3.8-flash
// They are compiled into the DEBUG app only, for trying the SDK on your own phone.
// A key inside an APK can be extracted: never ship this, use your own backend instead.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun setting(property: String, env: String): String =
    localProperties.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: ""

fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.github.shakeabdulrahman.keystone.llm.sample.chat"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.shakeabdulrahman.keystone.llm.sample.chat"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            buildConfigField("String", "GROQ_API_KEY", setting("groq.apiKey", "GROQ_API_KEY").quoted())
            buildConfigField("String", "GROQ_MODEL", setting("groq.model", "GROQ_MODEL").quoted())
            buildConfigField("String", "GEMINI_API_KEY", setting("gemini.apiKey", "GEMINI_API_KEY").quoted())
            buildConfigField("String", "GEMINI_MODEL", setting("gemini.model", "GEMINI_MODEL").quoted())
        }
        release {
            // Deliberately empty: a release build must not carry vendor keys.
            buildConfigField("String", "GROQ_API_KEY", "\"\"")
            buildConfigField("String", "GROQ_MODEL", "\"\"")
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            buildConfigField("String", "GEMINI_MODEL", "\"\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":llm-openai"))
    implementation(project(":llm-gemini"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
}
