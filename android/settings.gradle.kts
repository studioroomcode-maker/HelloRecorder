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
        // sherpa-onnx(STT) — Maven 배포본이 없어 GitHub 릴리스 자산을 ivy 레이아웃으로 받는다.
        // Gradle 이 받아서 캐시하므로 새 PC·CI·클린 체크아웃에서도 그대로 재현된다.
        // (로컬 app/libs + flatDir 이었을 때는 AAR 이 git 에 없어 클린 체크아웃 빌드가 불가능했다.)
        // 받은 파일의 SHA-256 고정 검증은 app/build.gradle.kts 의 verifySherpaAar 가 한다.
        ivy {
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
            metadataSources { artifact() }   // POM/ivy.xml 이 없는 단일 자산
            content { includeGroup("com.k2fsa") }
        }
    }
}

rootProject.name = "HelloRecorder"
include(":app")
