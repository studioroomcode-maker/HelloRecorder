package com.studioroomkr.hellorecorder

import android.content.Context
import java.util.Locale

/**
 * 간단한 다국어 지원(한국어/영어).
 *
 *  - Theme 팩토리들이 텍스트를 t() 로 감싸므로, 정적 라벨은 여기 사전만 채우면 자동 번역된다.
 *  - 숫자가 섞인 동적 문자열은 f("... %d", value) 형태로 호출(템플릿을 사전에 등록).
 *  - 토스트/다이얼로그 등 Theme 를 거치지 않는 곳은 호출부에서 I18n.t()/f() 로 감싼다.
 *
 * 언어는 Prefs 에 저장(미설정 시 기기 언어가 한국어면 KO, 아니면 EN).
 */
object I18n {

    @Volatile
    var en: Boolean = false
        private set

    /** Prefs 의 언어 설정을 읽어 현재 언어 적용. 각 Activity/Service 시작 시 호출. */
    fun apply(ctx: Context) {
        en = Prefs.isEnglish(ctx)
    }

    fun t(s: String): String = if (en) M[s] ?: s else s

    fun f(template: String, vararg args: Any?): String =
        t(template).format(*args)

    private val M: Map<String, String> = mapOf(
        // ── 상단 상태 / 탭 ──
        "녹음 중" to "Recording",
        "소리가 감지될 때만 저장됩니다" to "Saves only when sound is detected",
        "녹음 서비스" to "Recording service",
        "파일" to "Files",
        "설정" to "Settings",
        "● 시작" to "● Start",
        "■ 정지" to "■ Stop",
        "녹음 시작" to "Recording started",
        "녹음 정지" to "Recording stopped",
        "⚪ 정지됨" to "⚪ Stopped",
        "🔴 녹음 중" to "🔴 Recording",
        "🔴 녹음 중 · 음량 %d" to "🔴 Recording · level %d",
        "🟢 대기 중 · 음량 %d" to "🟢 Waiting · level %d",
        "구간: %s ~ %s" to "Section: %s ~ %s",
        "저장됨: %s" to "Saved: %s",

        // ── 섹션 제목 ──
        "녹음 감도 (무음 기준)" to "Sensitivity (silence threshold)",
        "녹음 시간대" to "Recording schedule",
        "위치 기반 녹음" to "Location-based recording",
        "녹음 품질" to "Recording quality",
        "저장공간" to "Storage",
        "배터리 사용량 (일별)" to "Battery usage (daily)",
        "보안 (앱 잠금)" to "Security (app lock)",
        "안정성 (백그라운드 유지)" to "Reliability (background)",
        "녹음 파일" to "Recordings",

        // ── 감도 / 구간 묶기 ──
        "값이 낮을수록 작은 소리도 녹음됩니다. 상단 막대가 기준선을 넘으면 녹음돼요." to
            "Lower = picks up quieter sounds. Records when the top bar crosses the line.",
        "현재 기준: %d" to "Threshold: %d",
        "구간 묶기: 무음 %d초 넘으면 새 파일로 분리" to "Merge gap: split after %ds of silence",
        "길게 잡을수록 짧은 침묵으로 끊긴 구간을 한 파일로 묶어 파일 수가 줄어듭니다. 묶이는 구간의 중간 소리는 끊지 않고 이어서 저장하며, 완전한 무음(볼륨 0)만 건너뜁니다. 짧게 잡으면 잘게 나뉩니다." to
            "Longer = merges segments split by short silences into one file, reducing file count. Sounds within the merged span are kept continuously; only true silence (volume 0) is skipped. Shorter = more files.",

        // ── 스케줄 ──
        "요일·날짜별로 녹음할 시간을 알람처럼 지정합니다. 시작=끝(예: 00:00~00:00)이면 24시간 녹음, 체크 해제하면 그 날은 녹음 안 함." to
            "Set recording times per weekday/date like an alarm. Start=End (e.g. 00:00~00:00) means 24h; uncheck to skip that day.",
        "요일별" to "By weekday",
        "특정 날짜 (요일 설정보다 우선)" to "Specific dates (override weekday)",
        "＋ 날짜 지정 추가" to "+ Add a date",
        "지정한 날짜가 없습니다." to "No dates added.",
        "일요일" to "Sunday", "월요일" to "Monday", "화요일" to "Tuesday",
        "수요일" to "Wednesday", "목요일" to "Thursday", "금요일" to "Friday", "토요일" to "Saturday",

        // ── 위치 ──
        "지정한 장소 반경에 따라 녹음을 켜고 끕니다. 시간대 설정과 둘 다 만족할 때만 녹음돼요. 위치는 앱을 사용 중일 때만 확인합니다(화면이 꺼진 동안에는 적용되지 않음). 위치를 확인 못 하면 녹음은 그대로 유지됩니다(놓침 방지)." to
            "Turns recording on/off by zone radius. Records only when both the schedule and location allow. Location is only checked while the app is in use (not while the screen is off). If location can't be read, recording continues (so nothing is missed).",
        "위치 기반 녹음 사용" to "Enable location-based recording",
        "측위 간격: %s" to "Location interval: %s",
        "짧을수록 위치 변화에 빨리 반응하지만 배터리를 조금 더 씁니다. 권장 1~3분." to
            "Shorter reacts faster to movement but uses a bit more battery. 1–3 min recommended.",
        "구역" to "Zones",
        "현재 위치로 구역 추가" to "Add zone at current location",
        "지도에서 구역 추가" to "Add zone from map",
        "추가된 구역이 없습니다." to "No zones added.",
        "실내·지하는 정확도가 낮으니 150~300m 권장. 여러 구역: ‘포함’ 구역이 하나라도 있으면 그 안에서만 녹음, ‘제외’ 구역 안에서는 항상 녹음 안 함." to
            "Indoors/underground accuracy is low; 150–300m recommended. With multiple zones: if any 'include' zone exists, records only inside it; never records inside an 'exclude' zone.",
        "‘항상 허용’ 위치 설정 열기" to "Open 'Allow all the time' setting",
        "반경: %dm" to "Radius: %dm",
        "이 구역에서만 녹음 (탭해 변경)" to "Record only here (tap to change)",
        "이 구역에선 녹음 금지 (탭해 변경)" to "Don't record here (tap to change)",
        "구역 이름" to "Zone name",
        "구역 %d" to "Zone %d",
        "이름" to "Rename",
        "구역을 추가했습니다" to "Zone added",
        "지도에서 구역을 추가했습니다" to "Zone added from map",
        "현재 위치를 찾는 중…" to "Finding current location…",
        "현재 위치를 가져올 수 없습니다" to "Couldn't get current location",
        "위치 서비스를 켜고 다시 시도하세요" to "Turn on location services and try again",
        "위치 권한을 확인하세요" to "Check location permission",
        "위치 권한이 필요합니다" to "Location permission required",
        "지도를 탭해 구역 중심을 지정하세요." to "Tap the map to set the zone center.",
        "이 위치로 지정" to "Use this location",

        // ── 품질 ──
        "낮음 (16kbps, 최소 용량)" to "Low (16kbps, smallest)",
        "보통 (32kbps, 음성 권장)" to "Normal (32kbps, recommended)",
        "높음 (64kbps, 또렷함)" to "High (64kbps, clearer)",
        "최고 (128kbps, 용량 큼)" to "Best (128kbps, large)",
        "최고 (128kbps, 용량 큼) · Pro" to "Best (128kbps, large) · Pro",
        "현재: %s" to "Current: %s",
        "다음 녹음부터 적용됩니다" to "Applies from the next recording",

        // ── 저장공간 ──
        "파일 저장 위치" to "File save location",
        "기기 내부 (앱 전용·비공개)" to "Internal (app-private)",
        "외부 저장소 (파일 관리자에서 보임)" to "External (visible in file manager)",
        "SD 카드" to "SD card",
        "내부" to "Internal",
        "외부(공유)" to "External (shared)",
        "SD카드" to "SD card",
        "현재: " to "Current: ",
        "‘외부(공유)’로 두면 파일 관리자/USB로 녹음 파일에 바로 접근할 수 있습니다(앱 삭제 시 함께 삭제). 위치를 바꿔도 기존 파일은 자동 이동되지 않습니다." to
            "'External (shared)' lets you access recordings via file manager/USB (deleted with the app). Changing location does not move existing files.",
        "저장 위치 변경됨 (기존 파일은 이동되지 않음)" to "Save location changed (existing files not moved)",
        "자동 삭제 보관 기간: %s" to "Auto-delete after: %s",
        "이 기간이 지난 파일은 자동 삭제됩니다(보호 파일 제외)." to
            "Files older than this are auto-deleted (except kept files).",
        "Pro로 30일까지 보관" to "Keep up to 30 days with Pro",
        "일별 배터리 사용량은 Pro 전용 기능입니다." to "Daily battery usage is a Pro feature.",
        "녹음 시간대 예약은 Pro 전용 기능입니다." to "Recording schedule is a Pro feature.",
        "앱 잠금·프라이버시 모드는 Pro 전용 기능입니다." to "App lock & Privacy mode are Pro features.",
        "프라이버시 모드(화면 캡처 차단)는 Pro 전용 기능입니다." to "Privacy mode (block screen capture) is a Pro feature.",
        "프라이버시 모드는 Pro" to "Privacy mode — Pro",
        "캘린더·정렬·검색은 Pro" to "Calendar, sort & search — Pro",
        "정렬·캘린더 Pro" to "Sort · Calendar — Pro",
        "녹음 사용량 약 %dMB · 기기 남은 공간 약 %dMB" to "Recordings ~%dMB · Free space ~%dMB",
        "최소 확보 공간: %dMB" to "Min free space: %dMB",
        "남은 공간이 이 값보다 적어지면, 보호 안 된 오래된 파일부터 자동으로 비웁니다." to
            "When free space drops below this, oldest unprotected files are cleared automatically.",

        // ── 배터리 ──
        "녹음이 동작하는 동안 기기 배터리 잔량 변화를 표본해 일별 소모(기기 전체 기준)를 추정합니다. 앱별 정확치는 시스템 설정 ‘배터리’에서 확인하세요." to
            "Estimates daily drain (device-wide) by sampling battery level while recording runs. For per-app figures, see system Settings > Battery.",
        "기기 배터리 설정 열기" to "Open device battery settings",
        "새로고침" to "Refresh",
        "아직 기록이 없습니다. 녹음을 켜두면 일별 소모가 쌓입니다." to
            "No data yet. Keep recording on to accumulate daily usage.",
        "배터리 설정을 열 수 없습니다" to "Can't open battery settings",

        // ── 보안 / 안정성 ──
        "앱 잠금 (지문/PIN)" to "App lock (fingerprint/PIN)",
        "켜면 앱에 들어올 때 지문/PIN 인증을 요구합니다." to "Requires fingerprint/PIN to open the app.",
        "배터리 최적화 제외 설정" to "Disable battery optimization",
        "삼성·샤오미·오포 등 일부 기기는 백그라운드 녹음을 강제 종료합니다. 아래 설정을 해두면 녹음이 잘 유지됩니다." to
            "Some devices (Samsung/Xiaomi/OPPO, etc.) force-stop background recording. The settings below keep recording reliable.",
        "백그라운드 실행 / 자동 시작 설정" to "Background / auto-start settings",
        "녹음이 멈춰요? 기기별 설정 보기" to "Recording stops? Device-specific help",
        "기기별 설정 안내" to "Device-specific settings",
        "확인" to "OK",
        "앱 잠금" to "App lock",
        "녹음 기록을 보려면 인증하세요" to "Authenticate to view recordings",
        "프라이버시 모드" to "Privacy mode",
        "켜면 화면 캡처·녹화가 차단되고, 최근 앱 목록에서 화면이 가려지며, 알림에 ‘녹음’ 표시가 숨겨집니다." to
            "Blocks screenshots/screen recording, hides the screen in Recents, and hides 'recording' wording in the notification.",
        "서비스 실행 중" to "Service running",

        // ── 파일 목록 / 일괄 ──
        "전체 보기" to "Show all",
        "전체" to "All",
        "정렬" to "Sort",
        "최신순" to "Newest",
        "오래된순" to "Oldest",
        "이름순" to "Name",
        "용량순" to "Size",
        "길이순" to "Length",
        "카테고리" to "Category",
        "없음" to "None",
        "＋ 새 카테고리" to "+ New category",
        "새 카테고리" to "New category",
        "전체 선택" to "Select all",
        "선택 해제" to "Deselect",
        "선택 삭제" to "Delete selected",
        "선택 보관" to "Keep selected",
        "보관 해제" to "Unkeep",
        "백업 내보내기" to "Export backup",
        "검색 (파일명, 라벨, 날짜)" to "Search (name, label, date)",
        "왼쪽 체크로 선택 → 일괄 삭제/보관. ‘보관’은 자동 삭제·공간확보에서 제외됩니다." to
            "Check on the left to select → bulk delete/keep. 'Keep' is excluded from auto-delete and cleanup.",
        "보관" to "Keep",
        "공유" to "Share",
        "라벨" to "Label",
        "편집" to "Edit",
        "삭제" to "Delete",
        "아직 녹음된 파일이 없습니다." to "No recordings yet.",
        "검색 결과가 없습니다." to "No results.",
        "선택된 파일이 없습니다" to "No files selected",
        "%d개 파일을 삭제할까요?" to "Delete %d files?",
        "%d개 삭제됨" to "%d deleted",
        "%d개 보관됨" to "%d kept",
        "%d개 보관 해제됨" to "%d unkept",
        "라벨 / 메모" to "Label / note",
        "저장" to "Save",
        "취소" to "Cancel",
        "%s 파일을 삭제할까요?" to "Delete %s?",
        "보호된 파일이 없습니다" to "No kept files",
        "시작 %s · %s" to "Started %s · %s",
        "방금 시작" to "just started",
        "%s 경과" to "%s elapsed",
        "방금" to "just now",
        "%d일 %d시간" to "%dd %dh",
        "%d일" to "%dd",
        "%d시간 %d분" to "%dh %dm",
        "%d시간" to "%dh",
        "%d분 %d초" to "%dm %ds",
        "%d분" to "%dm",
        "%d초" to "%ds",

        // ── 플레이어 ──
        "▶ 재생" to "▶ Play",
        "⏸ 정지" to "⏸ Pause",
        "재생 속도" to "Playback speed",
        "북마크" to "Bookmarks",
        "★ 현재 위치 북마크" to "★ Bookmark current position",
        "북마크 없음" to "No bookmarks",
        "구간 잘라내기" to "Trim section",
        "현재 재생 위치를 시작/끝으로 지정해 그 구간만 새 파일로 저장합니다." to
            "Mark current position as start/end and save just that section as a new file.",
        "시작점 지정" to "Set start",
        "끝점 지정" to "Set end",
        "이 구간만 저장" to "Save this section",
        "끝점이 시작점보다 뒤여야 합니다" to "End must be after start",
        "잘라내기 실패" to "Trim failed",
        "구간 저장" to "Save section",
        "이 구간을 어떻게 저장할까요?" to "How do you want to save this section?",
        "새 파일로 저장" to "Save as new file",
        "덮어쓰기" to "Overwrite",
        "덮어쓰기 완료" to "Overwritten",
        "덮어쓰기 실패" to "Overwrite failed",
        "%s배속" to "%sx speed",

        // ── 동의 화면 ──
        "시작하기 전에" to "Before you start",
        "동의하고 시작" to "Agree and start",
        "동의 안 함 (종료)" to "Decline (exit)",
        "위 내용을 확인했으며, 본인의 정당한 용도로만 사용하겠습니다." to
            "I have read the above and will use this app only for my own lawful purposes.",

        // ── 공유 ──
        "녹음 파일 공유" to "Share recording",
        "백업 내보내기 (%d개)" to "Export backup (%d)",

        // ── Pro ──
        "⭐ Pro 사용 중 — 감사합니다!" to "⭐ Pro active — thank you!",
        "⭐ HelloRecorder Pro" to "⭐ HelloRecorder Pro",
        "위치 기반 녹음·위젯·무제한 카테고리·30일 보관 등 모든 기능 잠금 해제." to
            "Unlock everything: location-based recording, widget, unlimited categories, 30-day retention, and more.",
        "Pro 잠금 해제" to "Unlock Pro",
        "Pro 구매" to "Buy Pro",
        "Pro 구매 · %s" to "Buy Pro · %s",
        "구매 복원" to "Restore purchase",
        "닫기" to "Close",
        "한 번 구매로 모든 프리미엄 기능을 평생 사용합니다." to "One purchase unlocks all premium features forever.",
        "Pro 사용 중 — 감사합니다!" to "Pro active — thank you!",
        "구매를 확인하는 중…" to "Checking purchase…",
        "상품 정보를 불러오는 중입니다. 잠시 후 다시 시도하세요." to "Loading product info. Please try again shortly.",
        "• 녹음 시간대 예약 (요일/날짜)" to "• Recording schedule (by day/date)",
        "• 위치 기반 녹음 (다중 구역)" to "• Location-based recording (multiple zones)",
        "• 캘린더 · 정렬 · 검색" to "• Calendar · sort · search",
        "• 캘린더 · 정렬" to "• Calendar · sort",
        "• 앱 잠금 · 프라이버시 모드" to "• App lock · Privacy mode",
        "• 프라이버시 모드 (화면 캡처 차단)" to "• Privacy mode (block screen capture)",
        "• 최고 음질 (128kbps)" to "• Best quality (128kbps)",
        "• 카테고리 무제한" to "• Unlimited categories",
        "• 보관 기간 30일까지" to "• Retention up to 30 days",
        "• 홈 위젯 · 일별 배터리 사용량" to "• Home widget · daily battery usage",
        "• 광고 없음 · 우선 지원" to "• No ads · priority support",

        // ── 언어 ──
        "언어 / Language" to "Language",
    )
}
