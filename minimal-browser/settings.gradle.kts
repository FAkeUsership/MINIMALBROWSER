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
        // Mozilla GeckoView is published here.
        maven { url = uri("https://maven.mozilla.org/maven2") }
    }
}

rootProject.name = "MinimalBrowser"
include(":app")
