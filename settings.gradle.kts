pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // Where Gradle finds a JDK to download. gradle/gradle-daemon-jvm.properties
    // pins the JDK the build runs on (17) and lists where to get it, so a
    // machine without it (one with only a newer JDK, say) gets it on its own;
    // `./gradlew updateDaemonJvm --jvm-version=17` writes that file, and
    // needs this to find the addresses.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor publishes here, not to Maven Central.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "CyTube"
include(":app")
