# HelloRecorder — 출시 점검 + Data Safety 가이드

---

## A. build.gradle.kts 점검 결과 (현재 파일 기준)

| 항목 | 현재 값 | 판정 |
|---|---|---|
| `namespace` / `applicationId` | `com.studioroomkr.hellorecorder` | ✅ 일치, com.example 아님 |
| `minSdk` | 26 (Android 8.0) | ✅ 적절 (적응형 아이콘·생체인증·FGS 모두 OK) |
| `targetSdk` / `compileSdk` | 36 | ✅ 최신 — Play 최소 요건 충족 (edge-to-edge·FGS 타입 코드로 처리됨) |
| `versionCode` / `versionName` | 2 / "0.6.0" | ✅ 업로드 OK. ⚠️ 이름이 한때 "1.0" 이었다가 0.6.0 으로 내려감 — 스토어에 pre-1.0 으로 보이는 게 의도인지 확인 |
| Java | 11 | ✅ |
| 의존성 | appcompat·work·biometric·osmdroid·billing·onnxruntime·sherpa-onnx·VAD | ✅ |
| 네이티브 16KB 페이지 정렬 | .so 6종 전부 `PT_LOAD align=16384` | ✅ 실측 확인(2026-07-20). Android 15+ 필수 요건 충족 |

### 손볼 점 (선택)
1. **appcompat 중복 선언**: `libs.androidx.appcompat` + `"androidx.appcompat:appcompat:1.7.0"` 둘 다 있음 → 하나만 남겨도 됨(빌드엔 무해, 경고만). 정리하려면 명시 줄(`implementation("androidx.appcompat:appcompat:1.7.0")`) 삭제.
2. **release minify 비활성**(`optimization { enable = false }`): 지금 그대로 OK(안전). 나중에 용량 줄이려 R8 켜면 osmdroid용 keep 규칙이 필요할 수 있음 → v1은 끄고 가는 게 안전.
3. **서명(Signing)**: ✅ **설정 완료 + 가드 추가(2026-07-20)** — `keystore.properties`(git 제외)에서 키를 읽어 자동 서명합니다. 예전엔 이 파일이 없으면 **경고 없이 미서명 AAB 가 성공적으로** 만들어져 Play 업로드 단계에서야 문제를 알았는데, 이제 `verifyReleaseSigning` 이 릴리스 패키징 전에 빌드를 세웁니다(파일 존재·값 누락·`CHANGEME` 미치환·키스토어 실존 4가지 검사). 서명 없이 산출물만 볼 땐 `-PallowUnsignedRelease=true`.
4. **백업 차단**: ✅ **적용 완료** — `allowBackup="false"` + `data_extraction_rules.xml` 전 도메인 제외로 녹음 파일의 클라우드 백업·기기간 전송(Android 12+) 모두 차단.

---

## B. 권한 최종 목록 (Manifest) + Console 처리

| 권한 | 용도 | 처리 |
|---|---|---|
| `RECORD_AUDIO` | 녹음(핵심) | 정상. Data Safety에서 오디오 관련 답변 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | 백그라운드 녹음 유지 | Console에서 **FGS(마이크) 사용 사례 선언** |
| `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` | 위치 기반 녹음(**사용 중에만**) | 백그라운드 위치 제거됨 → **별도 선언 양식·시연영상 불필요** ✅ |
| `POST_NOTIFICATIONS` | FGS 상태 알림 | 정상 |
| `WAKE_LOCK` | 인코딩 중 절전 방지 | 정상 |
| `INTERNET` + `ACCESS_NETWORK_STATE` | 지도 타일(osmdroid) | 정상 (개인정보처리방침에 명시됨) |
| `RECEIVE_BOOT_COMPLETED` | 재부팅 후 녹음 자동 재개 | 정상 |
| ~~`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`~~ | (제거됨) | ✅ **제거 완료** — 권한 위험 해소 |

### ✅ REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — 처리 완료
- 정책상 위험했던 이 권한을 **Manifest에서 제거**했습니다.
- 앱의 "배터리 최적화 제외 설정" 버튼은 이제 특별 권한 없이 시스템
  **배터리 최적화 목록 화면**(`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`)을 열고,
  사용자가 목록에서 직접 제외하도록 동작합니다 → **정책 위험 없음.**

---

## C. AAB 빌드 & 서명 (Play는 AAB 필수)

> 서명 설정은 이미 `build.gradle.kts` 에 들어가 있습니다. **키 파일과 `keystore.properties` 만 만들면**
> release 빌드가 자동 서명됩니다. 비밀번호는 코드에 없고 git 에도 안 올라갑니다.

### C-1. 한 번만: 키스토어 + keystore.properties 만들기
1. 키스토어(.jks) 생성 — Android Studio **Build → Generate Signed Bundle / APK → Android App Bundle**
   에서 *Create new...* 로 만들거나, 명령줄 `keytool` 사용. 권장 위치: `android/keystore/release.jks`
2. **비밀번호·키 별칭 안전 보관** (분실 시 앱 업데이트 영구 불가)
3. `android/keystore.properties.example` 를 **`android/keystore.properties`** 로 복사 후 실제 값 입력:
   ```
   storeFile=keystore/release.jks   # android/ 기준 상대경로
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```
   ※ `keystore.properties` 와 `*.jks` 는 `.gitignore` 로 제외됨 — **절대 커밋 금지**.

### C-2. 빌드
- Android Studio 마법사로 만들거나, 명령줄: `./gradlew :app:bundleRelease`
- 서명 확인: `./gradlew :app:signingReport`
- `keystore.properties` 가 없으면 서명 설정은 자동으로 건너뜀(미서명 빌드) — 디버그 작업엔 영향 없음.

### C-3. 업로드
3. **Play App Signing** 권장(업로드 키만 관리, 앱 서명키는 Google이 보관)
4. 생성된 `.aab`(`android/app/build/outputs/bundle/release/`) 를 Play Console에 업로드

---

## D. Data Safety(데이터 보안) 양식 작성 가이드

> ⚠️ **2026-07-20 정정.** 예전엔 "어떤 데이터도 기기 밖으로 나가지 않으니 전부 '수집 안 함'"으로
> 안내했으나 사실과 달랐습니다. 지도 화면이 **현재 위치로 시야를 맞춘 뒤** 그 영역 타일을
> `openstreetmap.org` 에 요청하므로(`MapPickerActivity.kt:61`), **대략적 위치는 기기 밖으로 나갑니다.**
> 상세 답변은 `PLAY_INTERNAL_TESTING_SUBMISSION.md` 2-1 이 기준입니다(이 문서는 요약).

### 1) "앱이 필수 사용자 데이터를 수집하거나 공유합니까?" → **예(Yes)**
- 신고 항목은 **딱 하나**: **위치 → 대략적 위치**, 수집=예 / 공유=아니요 / 목적=앱 기능 /
  **선택(Optional)** / 전송 중 암호화=예.
- 나머지는 전부 **수집 안 함**입니다: 녹음 오디오·전사·검색 색인·설정·크래시 로그 모두 기기 내부에만
  저장되고 외부 전송이 0입니다. 저장된 구역 좌표 자체도 전송하지 않습니다.
- 사용자가 **공유 시트로 직접 내보내는 것**은 Google 정책상 ‘공유(share)’에 포함되지 않음(사용자 행위).

### 2) 지도(osmdroid) — 왜 '수집'인지
- 지도 화면을 열면 **OpenStreetMap 타일 서버**에서 지도 이미지를 받아옵니다.
- 전송되는 건 "보고 있는 지도 영역의 타일 좌표"인데, 지도가 **현재 위치 기준으로 열리므로**
  결과적으로 사용자의 대략적 위치가 제3자에게 전달됩니다. 녹음 데이터는 포함되지 않습니다.
- 심사자가 타일 트래픽을 보고 '수집 안 함' 답변과 모순을 잡으면 **데이터 보안 불일치 제재**로
  이어집니다(단순 거절보다 무겁습니다). 정직하게 신고하는 편이 훨씬 안전합니다.

### 3) 보안 관행 섹션
- **전송 중 암호화**: **예** — OSM 타일은 HTTPS 로 고정돼 있습니다(커밋 65ce570).
- **사용자가 데이터 삭제 요청 가능**: **예** — 앱 내에서 파일 직접 삭제 + 앱 삭제 시 전부 제거.

### 4) Console이 "오디오"를 물으면
- 오디오는 **"수집 안 함(Not collected)"** — 기기 밖 전송이 없으므로 정확합니다.
- 위치만 위 1) 대로 신고합니다.

### 5) 함께 제출할 것
- **개인정보처리방침 URL** — 게시본은 `docs/privacy-policy.html`, GitHub Pages(`main` 브랜치 `/docs`).
  주소: `https://studioroomcode-maker.github.io/HelloRecorder/privacy-policy.html`
- **앱 콘텐츠 > 데이터 보안** 양식 제출
- **권한 사용 설명**(필요 시): 마이크=녹음, 위치=사용 중 구역 판단, 인터넷=지도 타일

---

## 한 줄 요약
- build.gradle.kts: **문제 없음**. 서명 설정 완료 — **키 파일 + `keystore.properties` 만 만들면** 끝(위 C).
- 백업/기기간 전송 차단 완료(`allowBackup="false"` + data_extraction_rules).
- 위치를 사용중에만으로 낮춰 **백그라운드 위치 심사 단계가 사라졌습니다**(선언 양식·시연영상 불필요).
- Data Safety 는 **"대략적 위치 1건만 수집(선택·앱 기능)"**, 나머지는 전부 수집 안 함으로 갑니다(위 D).
- 남은 할 일(코드 외): **스크린샷(폰 ≥2장)**, **Pages 활성화**, Console **Data Safety·콘텐츠 등급·FGS(마이크) 사용 사례** 양식.
- 남은 변수: 상시 녹음에 대한 정책 스캔.
