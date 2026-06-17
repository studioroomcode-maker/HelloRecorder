plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.studioroomkr.hellorecorder"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.studioroomkr.hellorecorder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 실기기 ABI만 포함 (ONNX Runtime 네이티브 라이브러리가 커서 x86 계열 제외 → APK 대폭 감소).
        // Play 출시는 App Bundle(.aab)로 기기별 분할 전달하면 사용자 다운로드는 더 작아진다.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        buildConfig = true   // BuildConfig.DEBUG 사용 (디버그 빌드 자동 Pro)
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.android.billingclient:billing:6.1.0")  // Pro 인앱결제
    implementation("com.github.gkonovalov.android-vad:webrtc:2.0.10")  // WebRTC 음성활동검출(VAD) — 1차 게이트
    implementation("com.github.gkonovalov.android-vad:silero:2.0.10")   // Silero VAD(ONNX) — 2차 정밀 확인
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")  // GTCRN 음성향상 직접 추론
}