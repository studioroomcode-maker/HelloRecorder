# STT 스파이크 (빌드 제외 · 보존용)

온디바이스 한국어 STT 를 실기기에서 실측하려고 만든 계측 도구다. 녹음을 전사해 **RTF**(실시간
대비 처리 속도)와 **CER**(문자 오류율)을 재는 것이 전부이고, 앱 기능이 아니다.

이 도구로 얻은 실측값이 v2 의 STT 엔진 선택 근거다 — S25U 기준 **RTF 0.035, 근접 CER ≈ 0%**
(`android/app/build.gradle.kts` 의 sherpa-onnx 의존성 주석 참고).

## 왜 여기 있나 (빌드에서 뺀 이유)

원래 `android/app/src/debug/` 에 있었고 별도 런처 아이콘까지 달려 있었다. 그런데
디버그 빌드를 설치하면 런처 액티비티가 둘이 되어, Android Studio 의 "Default Activity"
실행이 앱(`SplashActivity`)이 아니라 이 측정 화면을 띄웠다. 앱을 확인하려는 흐름을 매번
방해해서 **소스셋 밖으로 옮겼다**(2026-07-20). 이제 디버그 빌드에도 설치되지 않는다.

지우지 않고 남긴 이유: 엔진 선택이 감이 아니라 실측에 근거했다는 기록이라, 폐기하면
그 근거가 사라진다.

## 다시 쓰려면

1. 두 `.kt` 파일을 `android/app/src/debug/java/com/studioroomkr/hellorecorder/` 로 옮긴다.
2. `android/app/src/debug/AndroidManifest.xml` 를 만든다:

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <manifest xmlns:android="http://schemas.android.com/apk/res/android">
       <application>
           <activity
               android:name=".SttSpikeActivity"
               android:exported="true"
               android:label="STT Spike" />
       </application>
   </manifest>
   ```

   > ⚠️ `LAUNCHER` intent-filter 는 넣지 말 것 — 런처 액티비티가 둘이 되어 위의 문제가 재발한다.

3. 디버그 빌드를 설치한 뒤 **adb 로 명시 실행**한다:

   ```
   adb shell am start -n com.studioroomkr.hellorecorder.debug/com.studioroomkr.hellorecorder.SttSpikeActivity
   ```

## 모델 넣기

스파이크는 앱 외부저장소 경로에서 모델을 읽는다. 디버그 빌드는 `applicationIdSuffix ".debug"`
가 붙으므로 **경로에 `.debug` 가 들어간다**:

```
adb push "models/sherpa-onnx-streaming-zipformer-korean-2024-06-16/." \
  "/storage/emulated/0/Android/data/com.studioroomkr.hellorecorder.debug/files/stt-model/"
```

`*.onnx` 파일들과 `tokens.txt` 가 그 폴더에 바로 있어야 한다(하위 폴더 X).

> 참고: 이건 스파이크 전용 경로다. 실제 앱의 STT 모델은 `SttModel.kt` 가 Hugging Face 에서
> 인앱 다운로드하고 SHA-256 으로 검증한다 — 수동 push 와 무관하다.
