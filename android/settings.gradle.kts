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
        // STT 스파이크용 로컬 AAR(sherpa-onnx) — debug 빌드 전용. app/libs 의 .aar 만 해석.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "HelloRecorder"
include(":app")
