pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // GeckoView release artifacts are published by Mozilla, not Maven Central.
        maven(url = "https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "MinimalBrowser"
include(":app")
