import java.util.Properties
import java.io.FileInputStream
import java.security.MessageDigest

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

// ---- sherpa-onnx AAR 좌표 ----
// 저장소는 settings.gradle.kts 의 ivy(GitHub 릴리스). group 은 Maven 좌표가 아니라
// 그 저장소를 겨냥한 이름이다(자산 경로는 module·revision·ext 로만 만들어진다).
// 해시는 v1.13.3 공식 자산 실측값(57,044,841 bytes) — 자산이 바뀌면 빌드가 서도록 고정한다.
val SHERPA_GROUP = "com.k2fsa"
val SHERPA_MODULE = "sherpa-onnx"
val SHERPA_VERSION = "1.13.3"
val SHERPA_SHA256 = "243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6"

// 체크섬 검증용 해석 전용 configuration.
val sherpaAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
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
    // settings.gradle.kts 의 ivy 저장소(GitHub 릴리스)에서 받는다. @aar 로 확장자를 못박아야
    // 메타데이터 없는 단일 자산으로 해석된다.
    implementation("$SHERPA_GROUP:$SHERPA_MODULE:$SHERPA_VERSION@aar")

    // 위 implementation 과 같은 자산을 가리키는 해석 전용 사본 — verifySherpaAar 가
    // 체크섬을 확인할 파일을 얻기 위한 것이다(패키징에는 영향 없음).
    sherpaAar("$SHERPA_GROUP:$SHERPA_MODULE:$SHERPA_VERSION@aar")
}

abstract class VerifyChecksumTask : DefaultTask() {
    @get:InputFiles abstract val artifact: ConfigurableFileCollection
    @get:Input abstract val sha256: Property<String>

    @TaskAction
    fun verify() {
        val file = artifact.singleFile
        val expected = sha256.get().lowercase()
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw GradleException(
                "sherpa-onnx AAR 체크섬 불일치 — 릴리스 자산이 바뀌었거나 받다가 깨졌다.\n" +
                    "  파일: ${file.path}\n  기대: $expected\n  실제: $actual\n" +
                    "자산이 정당하게 갱신된 것이라면 내용을 확인한 뒤 SHERPA_SHA256 을 갱신한다."
            )
        }
    }
}

// 받아온 AAR 이 실측으로 검증했던 그 파일이 맞는지 확인한다. GitHub 릴리스 자산은 태그가
// 같아도 교체될 수 있어 버전 고정만으로는 재현성이 보장되지 않는다.
val verifySherpaAar = tasks.register<VerifyChecksumTask>("verifySherpaAar") {
    description = "받아온 sherpa-onnx AAR 의 SHA-256 을 고정값과 대조"
    artifact.from(sherpaAar)
    sha256.set(SHERPA_SHA256)
}

tasks.named("preBuild") { dependsOn(verifySherpaAar) }