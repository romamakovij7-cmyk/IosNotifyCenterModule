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
        // Official Xposed API repository (compile-only dependency).
        maven(url = "https://api.xposed.info/")
    }
}

rootProject.name = "IOSNotificationShade"
include(":app")
