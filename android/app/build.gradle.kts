import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

// 릴리스 서명 정보는 keystore.properties(루트, git 제외)에서 읽는다.
// 파일이 없으면 서명 설정을 건너뛴다(디버그 빌드/키 없는 환경에서도 빌드 가능).
// 형식은 keystore.properties.example 참고.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
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
        versionCode = 2
        versionName = "0.6.0"

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
    signingConfigs {
        // keystore.properties 가 있을 때만 release 서명 설정을 만든다.
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
            // 키가 준비된 경우에만 release 서명을 붙인다(없으면 미서명 — 로컬 빌드용).
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // sherpa-onnx(STT) AAR 이 자체 libonnxruntime.so 를 번들하는데, 앱은 이미
    // com.microsoft.onnxruntime:onnxruntime-android 의 libonnxruntime.so 를 쓴다 → 중복.
    // pickFirst 로 하나만 패키징(둘 다 onnxruntime 빌드라 호환).
    packaging {
        jniLibs {
            pickFirsts += "**/libonnxruntime.so"
        }
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
    implementation("com.android.billingclient:billing:8.0.0")  // Pro 인앱결제
    implementation("com.github.gkonovalov.android-vad:webrtc:2.0.10")  // WebRTC 음성활동검출(VAD) — 1차 게이트
    implementation("com.github.gkonovalov.android-vad:silero:2.0.10")   // Silero VAD(ONNX) — 2차 정밀 확인
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")  // GTCRN 음성향상 직접 추론

    // sherpa-onnx: 온디바이스 한국어 STT(자동 전사 — v2 핵심 기능). 실기기 실측으로 확정
    // (S25U: RTF 0.035, 근접 CER≈0%). AAR 이 ABI당 ~14MB 라 릴리스 크기가 늘지만
    // AAB 분할 전달로 기기당 1개 ABI 만 내려간다. 모델(~127MB)은 번들하지 않고 별도 다운로드.
    //
    // group 을 반드시 채워야 한다. 빈 문자열이면 릴리스 빌드의 lintVital 이
    // GradleDetector 에서 group 을 파일 경로로 변환하다 InvalidPathException 으로 죽어
    // `./gradlew :app:bundleRelease` 자체가 실패한다. flatDir 은 group 을 무시하고
    // 이름·버전·확장자로만 찾으므로, 아무 이름이나 채워도 해석 결과는 같다.
    implementation(group = "sherpa", name = "sherpa-onnx-1.13.3", ext = "aar")
}