pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // WebRTC VAD (gkonovalov/android-vad) — JitPack 전용. 해당 그룹만 허용.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.gkonovalov.android-vad") }
        }
    }
}

rootProject.name = "HelloRecorder"
include(":app")
