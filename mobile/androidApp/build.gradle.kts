import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Backend URL for debug builds, per machine. Looked up in this order:
//   1. mobile/local.properties   keystone.apiBaseUrl=...   (not committed; best for your own setup)
//   2. a Gradle property          -Pkeystone.apiBaseUrl=...
//   3. default http://10.0.2.2:8000, which is the host machine as seen from the Android emulator.
// Physical phone over USB: set http://localhost:8000 and run `adb reverse tcp:8000 tcp:8000`.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val debugApiBaseUrl: String = localProperties.getProperty("keystone.apiBaseUrl")
    ?: providers.gradleProperty("keystone.apiBaseUrl").orNull
    ?: "http://10.0.2.2:8000"

/** local.properties first, then an environment variable (CI). Never hard-coded here. */
fun setting(property: String, env: String): String? =
    localProperties.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

// Release builds talk to the deployed backend over HTTPS, e.g.
//   keystone.releaseApiBaseUrl=https://keystone-api.onrender.com
val releaseApiBaseUrl: String = setting("keystone.releaseApiBaseUrl", "KEYSTONE_RELEASE_API_BASE_URL")
    ?: "https://api.keystone.invalid"

// Release signing. The keystore and its passwords are secrets: they live outside git
// (local.properties is ignored, *.jks too) or in CI secrets. See docs/deployment.md.
val signingStoreFile: String? = setting("keystone.signing.storeFile", "KEYSTONE_SIGNING_STORE_FILE")

android {
    namespace = "com.keystone.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.keystone.assistant"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        signingStoreFile?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = setting("keystone.signing.storePassword", "KEYSTONE_SIGNING_STORE_PASSWORD")
                keyAlias = setting("keystone.signing.keyAlias", "KEYSTONE_SIGNING_KEY_ALIAS")
                keyPassword = setting("keystone.signing.keyPassword", "KEYSTONE_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            require(releaseApiBaseUrl.startsWith("https://")) { "Release builds must use an https:// API URL" }
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            // Without signing settings the release APK is built unsigned (fine for CI checks).
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    // App Check: Play Integrity proves release builds are genuine; the debug provider lets
    // the emulator and debug builds through with a token registered in the console.
    implementation(libs.firebase.appcheck)
    releaseImplementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
