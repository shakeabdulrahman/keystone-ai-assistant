plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Coordinates are a release decision; see docs/releasing.md before publishing anything.
allprojects {
    group = "io.github.shakeabdulrahman"
    version = providers.gradleProperty("keystoneLlmVersion").get()
}
