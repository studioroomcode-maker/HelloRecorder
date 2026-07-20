import java.util.Properties
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

// 릴리스 서명 정보는 keystore.properties(루트, git 제외)에서 읽는다.
// 형식은 keystore.properties.example 참고.
//
// 설정이 불완전하면 서명 설정을 만들지 않고, 릴리스 패키징 단계에서 verifyReleaseSigning 이
// 빌드를 세운다. 예전엔 조용히 미서명 AAB 가 나왔는데 Play 가 이를 거부해서, 업로드 직전에야
// 문제를 알게 됐다 — 그래서 빌드 시점에 실패시킨다.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

// 서명 없이 릴리스 산출물을 만들고 싶을 때만(로컬 크기 확인 등):
//   ./gradlew :app:bundleRelease -PallowUnsignedRelease=true
val allowUnsignedRelease = providers.gradleProperty("allowUnsignedRelease")
    .map { it.toBoolean() }.getOrElse(false)

val SIGNING_KEYS = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

// 서명 설정이 실제로 쓸 수 있는 상태인지 확인하고, 아니면 사람이 읽을 이유를 돌려준다.
val signingProblem: String? = when {
    !keystorePropsFile.exists() ->
        "keystore.properties 가 없다 (${keystorePropsFile.path})\n" +
            "  keystore.properties.example 를 같은 폴더에 keystore.properties 로 복사한 뒤 값을 채운다."

    SIGNING_KEYS.any { keystoreProps.getProperty(it).isNullOrBlank() } ->
        "keystore.properties 에 값이 빠졌다: " +
            SIGNING_KEYS.filter { keystoreProps.getProperty(it).isNullOrBlank() }.joinToString()

    SIGNING_KEYS.any { keystoreProps.getProperty(it).contains("CHANGEME") } ->
        "keystore.properties 가 아직 템플릿 상태다 (CHANGEME 미치환): " +
            SIGNING_KEYS.filter { keystoreProps.getProperty(it).contains("CHANGEME") }.joinToString()

    !rootProject.file(keystoreProps.getProperty("storeFile")).exists() ->
        "storeFile 이 가리키는 키스토어가 없다: " +
            rootProject.file(keystoreProps.getProperty("storeFile")).path + "\n" +
            "  경로는 android/ 기준 상대경로다(저장소 루트의 키는 ../keystore/... )."

    else -> null
}

val signingReady = signingProblem == null

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

/**
 * sherpa AAR 안의 libonnxruntime.so 만 뽑아 jniLibs 레이아웃(<abi>/lib.so)으로 펼친다.
 * 저장소에 25MB 바이너리를 넣지 않으려고 빌드 시점에 AAR 에서 꺼낸다.
 */
abstract class ExtractSherpaOrtTask : DefaultTask() {
    @get:InputFiles abstract val aar: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun extract() {
        fs.copy {
            from(archives.zipTree(aar.singleFile)) {
                include("jni/**/libonnxruntime.so")
                eachFile { path = path.removePrefix("jni/") }   // jni/arm64-v8a/x.so → arm64-v8a/x.so
            }
            into(outputDir)
            includeEmptyDirs = false
        }
    }
}

val extractSherpaOrt = tasks.register<ExtractSherpaOrtTask>("extractSherpaOrt") {
    description = "sherpa AAR 에서 libonnxruntime.so 추출(Microsoft 것 대신 이게 패키징되도록)"
    // 이름으로 거는 이유: verifySherpaAar 는 이 파일 아래쪽에서 등록된다(문자열이면 지연 해석).
    dependsOn("verifySherpaAar")   // 체크섬 확인을 통과한 AAR 만 푼다
    aar.from(sherpaAar)
}

// AGP 9 는 SourceSet API 에 Provider 를 못 넣게 한다(생성물인지 정적 파일인지 구분 불가).
// 생성 디렉터리는 Variant API 로 붙인다.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            extractSherpaOrt, ExtractSherpaOrtTask::outputDir
        )
    }
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
            // 네이티브 심볼을 AAB 에 함께 올리는 설정. 다만 지금은 **실효가 거의 없다**:
            // 이 앱의 .so 는 전부 AAR 프리빌트인데 6개 중 5개(libonnxruntime, sherpa 3종,
            // libvad_jni)가 업스트림에서 이미 스트립돼 추출할 심볼 테이블이 없다. 실제로
            // AAB 의 BUNDLE-METADATA 에 debugsymbols 항목이 생기지 않는 것을 확인했다(2026-07-20).
            // → ONNX/sherpa 경로의 네이티브 크래시는 여전히 Play Console 에서 주소 덤프로만 보인다.
            // 설정을 남겨 두는 이유: 업스트림이 심볼 포함 빌드를 내거나 자체 네이티브 코드를
            // 추가하면 그때부터 자동으로 반영된다. 지금 당장의 개선으로 착각하지 말 것.
            debugSymbolLevel = "SYMBOL_TABLE"
        }
    }

    buildFeatures {
        buildConfig = true   // BuildConfig.DEBUG 사용 (디버그 빌드 자동 Pro)
        resValues = true     // 변형별 app_name (디버그는 이름을 달리해 런처에서 구분)
    }
    signingConfigs {
        // 설정이 온전할 때만 release 서명 설정을 만든다(값이 비었거나 CHANGEME 면 만들지 않는다).
        if (signingReady) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            // 디버그 빌드는 BuildConfig.DEBUG 로 Pro 가 자동 해제된다(Pro.kt:49). 릴리스와
            // 같은 applicationId 를 쓰면 흘러나간 디버그 APK 가 '완전 해제판'으로 설치될 수
            // 있어 패키지를 분리한다. 서명키가 달라 Play 설치본을 덮어쓰지는 못했지만,
            // 애초에 공존하게 두는 편이 안전하다.
            // 부작용: 디버그 빌드에서는 Play 결제가 동작하지 않는다(패키지 불일치). 어차피
            // 디버그는 Pro 가 자동 해제라 결제 경로를 타지 않으므로 실사용에 문제없다.
            applicationIdSuffix = ".debug"
            // 패키지가 갈리면서 런처에 아이콘이 둘 생기는데, 이름까지 같으면 어느 쪽이
            // 디버그인지 알 수 없다(실제로 "전사 설치하면 앱이 하나 더 생긴다"로 오해했다).
            resValue("string", "app_name", "HelloRecorder 디버그")
        }
        release {
            resValue("string", "app_name", "HelloRecorder")
            optimization {
                enable = false
            }
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // sherpa-onnx(STT) AAR 과 com.microsoft.onnxruntime:onnxruntime-android 가 **같은 이름**의
    // libonnxruntime.so 를 각각 담고 있어 하나만 패키징해야 한다.
    //
    // 예전 주석은 "둘 다 onnxruntime 빌드라 호환"이라며 pickFirst 에 맡겼는데, 그 가정이 틀렸다.
    // 실제로 Microsoft 빌드(18,214,224 B)가 선택됐고 sherpa JNI 는 자기 빌드(25,831,632 B)에만
    // 링크되므로 기기에서 이렇게 죽었다:
    //   dlopen failed: cannot locate symbol "OrtGetApiBase" referenced by "libsherpa-onnx-jni.so"
    //   → OnlineRecognizer.<clinit> 실패 → NoClassDefFoundError → STT 가 통째로 동작 불능.
    // 컴파일은 통과하고 .so 도 APK 에 다 들어 있어서, 실기기에서 돌려보기 전까지 드러나지 않았다.
    //
    // 그래서 sherpa 의 libonnxruntime.so 를 생성 jniLibs 디렉터리로 꺼내 우선순위를 확정한다
    // (위 androidComponents 블록). pickFirst 는 남은 중복을 걷어내는 용도로만 남긴다.
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")  // 파일 목록 뷰 재활용
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")  // 목록 상태 ViewModel
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

abstract class VerifyReleaseSigningTask : DefaultTask() {
    @get:Input @get:Optional abstract val problem: Property<String>

    @TaskAction
    fun verify() {
        val why = problem.orNull ?: return
        throw GradleException(
            "릴리스 서명 설정이 준비되지 않았다 — 이대로면 미서명 AAB 가 나오고 Play 가 업로드를 거부한다.\n" +
                "  $why\n" +
                "별칭이 기억나지 않으면: keytool -list -v -keystore <키스토어 경로>\n" +
                "서명 없이 산출물만 확인하려면: ./gradlew :app:bundleRelease -PallowUnsignedRelease=true"
        )
    }
}

// 릴리스 패키징(AAB·APK) 직전에 서명 설정을 확인한다. 검증 자체는 산출물을 만들지 않으므로
// 디버그 빌드나 test/lint 에는 걸리지 않는다.
val verifyReleaseSigning = tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
    description = "릴리스 서명 설정(keystore.properties)이 온전한지 확인"
    problem.set(if (allowUnsignedRelease) null else signingProblem)
}

tasks.matching { it.name == "packageReleaseBundle" || it.name == "packageRelease" }
    .configureEach { dependsOn(verifyReleaseSigning) }