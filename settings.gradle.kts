pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
