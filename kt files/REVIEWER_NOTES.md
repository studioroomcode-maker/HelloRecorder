# Google Play 심사 노트 (Reviewer Notes) 초안

> Play Console > 앱 콘텐츠 / 앱 액세스(App access) 및 리뷰 제출 시 "리뷰어에게 남기는 메모"에
> 넣을 내용. 배경 마이크·포그라운드 서비스 사용의 **정당성**을 심사자가 한눈에 이해하도록
> 정리했습니다. 한국어/영어 중 심사 언어에 맞게(보통 영어) 넣으세요.
>
> 목적: 이 앱이 스토커웨어/감시 앱이 아니라, 사용자가 **자신의** 회의·강의·메모를 녹음하는
> 개인 도구임을 증명하는 것. 요청 시 데모 영상 링크도 함께 제공하면 좋습니다.

---

## 🇺🇸 English (권장)

```
Summary
HelloRecorder is a personal voice recorder for the user's OWN meetings, lectures, interviews,
and memos. It is NOT a surveillance/monitoring tool and contains no covert-recording features.

Why background microphone / foreground service
- The core feature is hands-free, sound-activated recording of the user's own environment
  (e.g., a long meeting or lecture) without keeping the screen on. This requires the microphone
  while the app is backgrounded, via a foreground service of type "microphone".
- Recording is user-initiated only: it never starts on its own. The user must explicitly press
  Start (or set a schedule they configured).

Transparency & anti-abuse safeguards (please verify during review)
- A persistent foreground-service notification ALWAYS shows "Recording" while active. It cannot
  be hidden — even the optional "Privacy Mode" (which only blocks screenshots / Recents preview)
  keeps the recording notification visible.
- Android's system microphone indicator (green dot) is shown and is not suppressed.
- First-run consent screen explains what is recorded, that it can run in the background with a
  persistent notification, and includes a legal notice: recording others without consent may be
  unlawful, and the app must not be used to secretly monitor or eavesdrop on others.
- No app-icon disguise, no hidden launch, no remote/covert activation, no option to hide the
  recording notification. These anti-stalkerware properties are intentional.

Privacy / data handling
- All recordings, transcripts, and settings are stored ONLY on the device. The developer runs no
  server and never uploads recordings or transcripts.
- Optional features use third-party networking only for what they need (map tiles from
  OpenStreetMap, a one-time on-device speech model download from Hugging Face, Google Play
  Billing). No recording content is transmitted. This matches the Data Safety form and the
  privacy policy.

Permissions
- RECORD_AUDIO + FOREGROUND_SERVICE_MICROPHONE: sound-activated recording (core feature).
- POST_NOTIFICATIONS: the persistent "Recording" status notification.
- ACCESS_FINE/COARSE_LOCATION (optional, while-in-use only): the optional "location-based
  recording" feature; if location can't be determined, the app does NOT record (fail-closed).
- RECEIVE_BOOT_COMPLETED: offer to resume recording after reboot only if the user had it on.

Test account / access
- No login required. All features are usable without an account. (Debug builds unlock Pro
  features for testing; the production build uses Google Play Billing for the one-time Pro
  purchase.)
```

---

## 🇰🇷 한국어 (참고)

```
요약
HelloRecorder는 사용자 본인의 회의·강의·인터뷰·메모를 녹음하기 위한 개인용 음성 녹음기입니다.
감시/모니터링 도구가 아니며, 몰래 녹음하는 기능은 일절 없습니다.

배경 마이크 / 포그라운드 서비스가 필요한 이유
- 핵심 기능은 화면을 켜 두지 않고도 본인 주변(예: 긴 회의·강의)을 소리 감지 기반으로 자동
  녹음하는 것입니다. 이를 위해 앱이 백그라운드일 때 마이크가 필요하며, "microphone" 타입
  포그라운드 서비스로 구현했습니다.
- 녹음은 사용자가 직접 시작해야만 동작합니다. 스스로 켜지지 않습니다.

투명성·오남용 방지 장치 (심사 중 확인 요망)
- 녹음 중에는 "녹음 중" 포그라운드 알림이 항상 표시되며 숨길 수 없습니다. 선택 기능인
  '프라이버시 모드'(화면 캡처·최근앱 미리보기 차단만 담당)에서도 이 알림은 계속 보입니다.
- Android 시스템 마이크 표시(초록 점)를 가리지 않습니다.
- 최초 실행 동의 화면에서 무엇을 녹음하는지, 백그라운드 상시 알림 동작, 그리고 "동의 없는
  타인 녹음은 불법일 수 있으며 몰래 감시·도청 용도로 쓰면 안 된다"는 법적 고지를 제시합니다.
- 아이콘 위장·숨김 실행·원격/무단 활성화·알림 숨김 옵션이 전혀 없습니다(의도적).

개인정보 처리
- 녹음·전사·설정은 전부 기기 안에만 저장됩니다. 개발자 서버가 없고 녹음·전사를 업로드하지
  않습니다. 선택 기능(지도 타일·1회 음성모델 다운로드·Play 결제)만 필요한 범위에서 제3자와
  통신하며 녹음 내용은 전송되지 않습니다. Data Safety·개인정보처리방침과 일치합니다.

권한
- RECORD_AUDIO + FOREGROUND_SERVICE_MICROPHONE: 소리 감지 녹음(핵심).
- POST_NOTIFICATIONS: "녹음 중" 상태 알림.
- 위치(선택, 사용 중에만): 위치 기반 녹음. 위치를 확인 못 하면 녹음하지 않음(fail-closed).
- RECEIVE_BOOT_COMPLETED: 사용자가 켜 뒀던 경우에만 재부팅 후 재개 안내.

테스트 계정 / 접근
- 로그인 불필요. 계정 없이 모든 기능 사용 가능. (디버그 빌드는 테스트용으로 Pro 해제, 프로덕션은
  Google Play 결제로 1회성 Pro 구매.)
```

---

## Play Console 관련 체크(코드 아님, 콘솔 작업)

- [ ] **포그라운드 서비스 선언**: 앱 콘텐츠 > 포그라운드 서비스에서 `microphone` 타입 사용 근거
      제출(위 "배경 마이크가 필요한 이유"를 요약해 입력).
- [ ] **권한 선언**: RECORD_AUDIO 배경 사용에 대한 소명(위 권한 설명 활용).
- [ ] **Data Safety 폼**: 녹음/전사는 기기 내 저장(수집 아님) — 온디바이스임을 정확히 표시.
      SDK 로 인한 기기 밖 통신(지도·모델·결제)은 각 항목대로 신고.
- [ ] **개인정보처리방침 URL**: `docs/privacy-policy.html` 을 GitHub Pages 로 게시하고 그 주소 입력.
      - 저장소 Settings → Pages → Source: `main` 브랜치 `/docs` 폴더 → Save
      - 게시 주소: `https://studioroomcode-maker.github.io/HelloRecorder/privacy-policy.html`
      - `/docs` 소스는 그 폴더만 서빙하므로 저장소의 나머지가 새로 노출되지 않는다.
      - ⚠️ 이 파일이 main 에 있어야 게시된다(v2 브랜치에만 있으면 404).
- [ ] **콘텐츠 등급 설문**: 전체 이용가.
- [ ] **타깃 대상**: 만 13세 이상, 아동 대상 아님.
- [ ] (권장) **데모 영상**: Start 를 눌러 녹음 시작 → 상단 "녹음 중" 알림이 뜨는 30초 영상을
      찍어 링크로 첨부하면 배경 마이크 정당성 소명이 빨라집니다.
