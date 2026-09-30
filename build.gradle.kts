buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself ("built-in Kotlin") with the Kotlin
        // Gradle plugin it depends on, 2.2.10 by default. The Compose
        // compiler plugin below is pinned to libs.versions.toml's `kotlin`,
        // so the Kotlin plugin has to be that same version.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
