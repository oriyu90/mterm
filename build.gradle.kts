// Top-level build file for mterm (Android Linux Workstation Terminal)
// AGP 9.0+ built-in Kotlin: do NOT apply org.jetbrains.kotlin.android (see migrate-to-built-in-kotlin).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.legacy.kapt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
