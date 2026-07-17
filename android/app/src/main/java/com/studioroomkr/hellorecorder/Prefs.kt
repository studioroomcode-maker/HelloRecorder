package com.studioroomkr.hellorecorder

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import java.util.Calendar

/**
 * 앱 설정과 상태 저장 (SharedPreferences).
 *  - threshold        : 무음 임계값 (슬라이더)
 *  - protected files  : 자동 삭제 제외 (상대경로 키)
 *  - hours            : 요일별 녹음 허용 시간대
 *  - recordingEnabled : 사용자가 켜 두려는 **의도**. "지금 녹음 중"이 아니다 —
 *    실제 동작 여부는 RecordingService.isRunning() 이 진실이다(둘은 어긋날 수 있다).
 *  - bitRate          : 녹음 품질
 *  - appLock          : 앱 진입 잠금 사용 여부
 *  - labels           : 파일별 라벨/메모
 *  - 녹음 상태(음량)   : 엔진 → UI 실시간 표시용
 */
object Prefs {
    private const val FILE = "hello_recorder_prefs"
    // 옛 파일명 — 앱 이름 변경(AlwaysRecorder → HelloRecorder) 전 설정. 1회 이전 후 비움.
    private const val OLD_FILE = "always_recorder_prefs"
    private const val KEY_MIGRATED = "_migrated_from_always_recorder"
    private const val KEY_THRESHOLD = "threshold_rms"
    private const val KEY_PROTECTED = "protected_files"
    private const val KEY_RECORDING_ENABLED = "recording_enabled"
    private const val KEY_RECORDING_STARTED_AT = "recording_started_at"
    private const val KEY_BITRATE = "bit_rate"
    private const val KEY_APP_LOCK = "app_lock"
    private const val KEY_PRIVACY = "privacy_mode"
    private const val KEY_CONSENT = "consent_accepted"
    private const val KEY_LANG = "app_lang"   // "", "ko", "en"
    private const val KEY_PRO = "pro_unlocked"
    private const val KEY_MIN_FREE_MB = "min_free_mb"
    private const val KEY_STORAGE_LOC = "storage_location"
    private const val KEY_RETENTION_HOURS = "retention_hours"
    private const val KEY_MIN_KEEP_ENABLED = "min_keep_enabled"
    private const val KEY_MIN_KEEP_SEC = "min_keep_sec"
    private const val KEY_VOICE_MODE = "voice_mode"
    private const val KEY_SILERO = "silero_confirm"
    private const val KEY_VOICE_EMPHASIS = "voice_emphasis"
    private const val KEY_DISTANCE_REDUCE = "distance_reduce"
    private const val KEY_TRANSCRIBE = "auto_transcribe"
    private const val KEY_STT_DL_IDS = "stt_dl_ids"   // 진행 중인 모델 다운로드 ID(쉼표 구분)
    // 알람식 스케줄: 요일별(dow_0..6) + 특정 날짜(date_yyyymmdd) 오버라이드
    //   각 항목: _en(켜짐), _s(시작 분, 0~1439), _e(끝 분, 0~1439)
    //   시작==끝 → 24시간 녹음 / 시작<끝 → [s,e) / 시작>끝 → 자정 넘김
    private const val DOW_PREFIX = "dow_"
    private const val DATE_PREFIX = "date_"
    private const val KEY_OVERRIDE_DATES = "override_dates"
    // 라벨 prefix
    private const val LABEL_PREFIX = "label_"
    private const val BOOKMARK_PREFIX = "bookmark_"
    // 정렬
    private const val KEY_SORT = "file_sort"
    // 카테고리(폴더 정리)
    private const val CAT_PREFIX = "cat_"          // cat_{key} -> 카테고리명
    private const val KEY_CATEGORIES = "categories" // 카테고리명 집합
    // 녹음 상태 (엔진이 갱신)
    private const val KEY_CUR_LEVEL = "cur_level"
    private const val KEY_IS_CAPTURING = "is_capturing"
    // 인코딩 실패(빈/깨진 녹음) 누적 — 사용자 가시화용
    private const val KEY_ENCODE_FAIL_COUNT = "encode_fail_count"
    private const val KEY_ENCODE_FAIL_LAST = "encode_fail_last"   // ms
    // 일별 배터리 소모 로그
    private const val BATT_PREFIX = "batt_"            // batt_yyyymmdd -> 누적 소모 %(Float)
    private const val KEY_BATT_LAST_LEVEL = "batt_last_level"
    private const val KEY_BATT_DAYS = "batt_days"      // 기록된 날짜 집합
    private const val BATT_MAX_DAYS = 31
    // 위치 기반 녹음
    private const val KEY_LOC_ENABLED = "loc_enabled"
    private const val KEY_LOC_RADIUS = "loc_radius"    // 새 구역 기본 반경 m
    private const val KEY_LOC_MODE = "loc_mode"        // 새 구역 기본 모드
    private const val KEY_LOC_INTERVAL_SEC = "loc_interval_sec"
    private const val KEY_LOC_ZONES = "loc_zones"      // 구역 StringSet
    private const val KEY_LOC_NEXT_ID = "loc_next_id"
    private const val KEY_LOC_ZONES_MIGRATED = "loc_zones_migrated"
    // 단일 중심 시절 키(마이그레이션용)
    private const val KEY_LOC_HAS_CENTER = "loc_has_center"
    private const val KEY_LOC_LAT = "loc_lat"
    private const val KEY_LOC_LNG = "loc_lng"

    const val LOC_MODE_ONLY_HERE = 0   // 이 구역에서만 녹음
    const val LOC_MODE_NOT_HERE = 1    // 이 구역에선 녹음 금지
    const val DEFAULT_LOC_RADIUS = 200
    const val MIN_LOC_RADIUS = 50
    const val MAX_LOC_RADIUS = 2000
    const val DEFAULT_LOC_INTERVAL_SEC = 120
    const val MIN_LOC_INTERVAL_SEC = 30
    const val MAX_LOC_INTERVAL_SEC = 1800
    private const val Z_SEP = "\u001F"   // fields separator (control char, not used in names)

    const val DEFAULT_THRESHOLD = 500.0
    const val MIN_THRESHOLD = 50.0
    const val MAX_THRESHOLD = 3000.0
    const val DEFAULT_BITRATE = 32_000
    const val DEFAULT_MIN_FREE_MB = 300L
    const val DEFAULT_RETENTION_HOURS = 48
    const val MIN_RETENTION_HOURS = 1       // 최소 1시간
    const val MAX_RETENTION_HOURS = 720     // 최대 30일
    const val DEFAULT_MERGE_GAP_SEC = 5     // 무음 N초 넘으면 새 파일로 분리
    const val MIN_MERGE_GAP_SEC = 1
    const val MAX_MERGE_GAP_SEC = 120
    // 짧은 녹음 자동 삭제 (잡음 컷): 켜면 이 길이 이하의 녹음은 저장하지 않음. 기본 켜짐(5초).
    const val DEFAULT_MIN_KEEP_SEC = 5
    const val MIN_MIN_KEEP_SEC = 1
    const val MAX_MIN_KEEP_SEC = 60

    // 저장 위치
    const val STORAGE_INTERNAL = 0   // 기기 내부(앱 전용, 비공개)
    const val STORAGE_EXTERNAL = 1   // 외부 저장소(앱 전용 폴더, 파일 관리자에서 보임)
    const val STORAGE_SD = 2         // SD 카드(있을 때)

    private fun prefs(ctx: Context): SharedPreferences {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_MIGRATED, false)) migrateFromOld(ctx, p)
        return p
    }

    /**
     * 옛 파일(always_recorder_prefs)의 설정을 새 파일로 1회 복사.
     * 마커로 가드해 이후엔 건너뛰고, 복사 후 옛 파일은 비운다.
     */
    private fun migrateFromOld(ctx: Context, newPrefs: SharedPreferences) {
        val old = ctx.getSharedPreferences(OLD_FILE, Context.MODE_PRIVATE)
        val editor = newPrefs.edit()
        for ((k, v) in old.all) {
            when (v) {
                is String -> editor.putString(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Float -> editor.putFloat(k, v)
                is Boolean -> editor.putBoolean(k, v)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(k, v as Set<String>)
                }
            }
        }
        // apply() 는 메모리값을 즉시 반영하므로 같은 프로세스 내 재이전을 막는다.
        editor.putBoolean(KEY_MIGRATED, true).apply()
        if (old.all.isNotEmpty()) old.edit().clear().apply()
    }

    // ---- 무음 임계값 ----
    fun getThreshold(ctx: Context): Double =
        prefs(ctx).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD.toFloat()).toDouble()

    fun setThreshold(ctx: Context, value: Double) {
        prefs(ctx).edit().putFloat(KEY_THRESHOLD, value.toFloat()).apply()
    }

    // ---- 보호 파일 (상대경로 키) ----
    fun getProtected(ctx: Context): MutableSet<String> =
        HashSet(prefs(ctx).getStringSet(KEY_PROTECTED, emptySet()) ?: emptySet())

    fun isProtected(ctx: Context, key: String): Boolean =
        getProtected(ctx).contains(key)

    fun setProtected(ctx: Context, key: String, protectedOn: Boolean) {
        val set = getProtected(ctx)
        if (protectedOn) set.add(key) else set.remove(key)
        prefs(ctx).edit().putStringSet(KEY_PROTECTED, set).apply()
    }

    // ---- 알람식 녹음 스케줄 (요일별 + 날짜별) ----

    /** 하루치 녹음 창. startMin/endMin 은 0~1439 (분). 시작==끝 이면 24시간. */
    data class DaySchedule(val enabled: Boolean, val startMin: Int, val endMin: Int) {
        /** 24시간 녹음(시작==끝)인지. */
        val isAllDay: Boolean get() = enabled && startMin == endMin
    }

    // 요일: dow 0=일 … 6=토 (Calendar.DAY_OF_WEEK - 1)
    fun getDaySchedule(ctx: Context, dow0: Int): DaySchedule {
        val p = prefs(ctx)
        return DaySchedule(
            p.getBoolean("$DOW_PREFIX${dow0}_en", true),
            p.getInt("$DOW_PREFIX${dow0}_s", 0),
            p.getInt("$DOW_PREFIX${dow0}_e", 0),
        )
    }

    fun setDaySchedule(ctx: Context, dow0: Int, s: DaySchedule) {
        prefs(ctx).edit()
            .putBoolean("$DOW_PREFIX${dow0}_en", s.enabled)
            .putInt("$DOW_PREFIX${dow0}_s", s.startMin)
            .putInt("$DOW_PREFIX${dow0}_e", s.endMin)
            .apply()
    }

    // 특정 날짜 오버라이드 (yyyymmdd). 있으면 요일 설정보다 우선.
    fun getOverrideDates(ctx: Context): List<String> =
        (prefs(ctx).getStringSet(KEY_OVERRIDE_DATES, emptySet()) ?: emptySet()).sorted()

    fun getDateOverride(ctx: Context, dateKey: String): DaySchedule? {
        val set = prefs(ctx).getStringSet(KEY_OVERRIDE_DATES, emptySet()) ?: emptySet()
        if (!set.contains(dateKey)) return null
        val p = prefs(ctx)
        return DaySchedule(
            p.getBoolean("$DATE_PREFIX${dateKey}_en", true),
            p.getInt("$DATE_PREFIX${dateKey}_s", 0),
            p.getInt("$DATE_PREFIX${dateKey}_e", 0),
        )
    }

    fun setDateOverride(ctx: Context, dateKey: String, s: DaySchedule) {
        val set = HashSet(prefs(ctx).getStringSet(KEY_OVERRIDE_DATES, emptySet()) ?: emptySet())
        set.add(dateKey)
        prefs(ctx).edit()
            .putStringSet(KEY_OVERRIDE_DATES, set)
            .putBoolean("$DATE_PREFIX${dateKey}_en", s.enabled)
            .putInt("$DATE_PREFIX${dateKey}_s", s.startMin)
            .putInt("$DATE_PREFIX${dateKey}_e", s.endMin)
            .apply()
    }

    fun removeDateOverride(ctx: Context, dateKey: String) {
        val set = HashSet(prefs(ctx).getStringSet(KEY_OVERRIDE_DATES, emptySet()) ?: emptySet())
        set.remove(dateKey)
        prefs(ctx).edit()
            .putStringSet(KEY_OVERRIDE_DATES, set)
            .remove("$DATE_PREFIX${dateKey}_en")
            .remove("$DATE_PREFIX${dateKey}_s")
            .remove("$DATE_PREFIX${dateKey}_e")
            .apply()
    }

    /** 지금(cal)이 녹음 허용 시간대인지 — 엔진이 호출. */
    fun isRecordingAllowed(ctx: Context, cal: Calendar): Boolean {
        val dateKey = "%04d%02d%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        val sched = getDateOverride(ctx, dateKey)
            ?: getDaySchedule(ctx, cal.get(Calendar.DAY_OF_WEEK) - 1)
        if (!sched.enabled) return false
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return inWindow(sched.startMin, sched.endMin, minute)
    }

    private fun inWindow(startMin: Int, endMin: Int, minute: Int): Boolean =
        RecordingLogic.inWindow(startMin, endMin, minute)

    // ---- 녹음 켜짐 '의도' (실제 동작 여부는 RecordingService.isRunning()) ----
    fun isRecordingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RECORDING_ENABLED, false)

    fun setRecordingEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RECORDING_ENABLED, enabled).apply()
    }

    // 녹음(모니터링)이 실제로 시작된 시각(ms). 0 이면 미동작.
    fun getRecordingStartedAt(ctx: Context): Long =
        prefs(ctx).getLong(KEY_RECORDING_STARTED_AT, 0L)

    fun setRecordingStartedAt(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY_RECORDING_STARTED_AT, ms).apply()
    }

    // ---- 녹음 품질 (비트레이트) ----
    fun getBitRate(ctx: Context): Int =
        prefs(ctx).getInt(KEY_BITRATE, DEFAULT_BITRATE)

    fun setBitRate(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_BITRATE, value).apply()
    }

    // ---- Pro 잠금 해제 (인앱결제 결과 캐시) ----
    fun isPro(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PRO, false)
    fun setPro(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PRO, on).apply()
    }

    // ---- 언어 ("" = 기기 언어 따름, "ko", "en") ----
    fun getLanguage(ctx: Context): String =
        prefs(ctx).getString(KEY_LANG, "") ?: ""

    fun setLanguage(ctx: Context, lang: String) {
        prefs(ctx).edit().putString(KEY_LANG, lang).apply()
    }

    fun isEnglish(ctx: Context): Boolean {
        val l = getLanguage(ctx)
        return if (l.isEmpty())
            !java.util.Locale.getDefault().language.startsWith("ko")
        else l == "en"
    }

    // ---- 최초 사용 동의(사전 고지) ----
    fun isConsentAccepted(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CONSENT, false)

    fun setConsentAccepted(ctx: Context, accepted: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CONSENT, accepted).apply()
    }

    // ---- 앱 잠금 ----
    fun isAppLockEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_APP_LOCK, false)

    fun setAppLockEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_APP_LOCK, on).apply()
    }

    // ---- 프라이버시 모드 (화면 캡처 차단·최근앱 가림·알림 숨김) ----
    fun isPrivacyMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PRIVACY, false)

    fun setPrivacyMode(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PRIVACY, on).apply()
    }

    // ---- 저장공간 안전장치 (최소 확보 MB) ----
    fun getMinFreeMb(ctx: Context): Long =
        prefs(ctx).getLong(KEY_MIN_FREE_MB, DEFAULT_MIN_FREE_MB)

    fun setMinFreeMb(ctx: Context, mb: Long) {
        prefs(ctx).edit().putLong(KEY_MIN_FREE_MB, mb).apply()
    }

    // ---- 저장 위치 ----
    fun getStorageLocation(ctx: Context): Int =
        prefs(ctx).getInt(KEY_STORAGE_LOC, STORAGE_INTERNAL)

    fun setStorageLocation(ctx: Context, loc: Int) {
        prefs(ctx).edit().putInt(KEY_STORAGE_LOC, loc).apply()
    }

    // ---- 구간 묶기 간격 (무음 N초 넘으면 새 파일로 분리) ----
    fun getMergeGapSec(ctx: Context): Int =
        prefs(ctx).getInt("merge_gap_sec", DEFAULT_MERGE_GAP_SEC)
            .coerceIn(MIN_MERGE_GAP_SEC, MAX_MERGE_GAP_SEC)

    fun setMergeGapSec(ctx: Context, sec: Int) {
        prefs(ctx).edit().putInt(
            "merge_gap_sec", sec.coerceIn(MIN_MERGE_GAP_SEC, MAX_MERGE_GAP_SEC)
        ).apply()
    }

    // ---- 짧은 녹음 자동 삭제 (잡음 컷) ----
    // 켜짐일 때만 동작. 이 길이(초) 이하로 녹음된 파일은 저장하지 않고 버린다.
    fun isMinKeepEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MIN_KEEP_ENABLED, true)

    fun setMinKeepEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MIN_KEEP_ENABLED, on).apply()
    }

    fun getMinKeepSec(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MIN_KEEP_SEC, DEFAULT_MIN_KEEP_SEC)
            .coerceIn(MIN_MIN_KEEP_SEC, MAX_MIN_KEEP_SEC)

    fun setMinKeepSec(ctx: Context, sec: Int) {
        prefs(ctx).edit().putInt(
            KEY_MIN_KEEP_SEC, sec.coerceIn(MIN_MIN_KEEP_SEC, MAX_MIN_KEEP_SEC)
        ).apply()
    }

    // ---- 사람 목소리 우선 모드 (음성 인식 기반 트리거) ----
    // 켜면 음성 대역 검출 + VAD 로 녹음 트리거를 판단해,
    // 작은 목소리도 더 잘 잡고 비음성 소음은 덜 잡는다. (저장 음질은 안 건드림)
    fun isVoiceModeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VOICE_MODE, false)

    fun setVoiceModeEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VOICE_MODE, on).apply()
    }

    // 2차 정밀 확인(Silero VAD). 음성 우선 모드의 하위 옵션. 기본 켜짐.
    // WebRTC(1차)가 음성 후보를 잡았을 때만 돌아 배터리 부담을 줄인다.
    fun isSileroEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SILERO, true)

    fun setSileroEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SILERO, on).apply()
    }

    // 목소리 강조 (켜면: 노이즈억제·음성튜닝 마이크 + GTCRN 잡음 제거를 함께 적용. AGC 미사용).
    // 끄면 원본 그대로 녹음. 저장 오디오를 가공하므로 기본 꺼짐.
    fun isVoiceEmphasisEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VOICE_EMPHASIS, false)

    fun setVoiceEmphasisEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VOICE_EMPHASIS, on).apply()
    }

    // 먼 소리 줄이기 (Pro). 잡음 제거 없이 근접 우선 익스팬더만 저장 오디오에 적용 —
    // 원음 질감은 유지하면서 멀리 있는(약한) 소리만 낮춘다. 저장 오디오를 가공하므로 기본 꺼짐.
    fun isDistanceReduceEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DISTANCE_REDUCE, false)

    fun setDistanceReduceEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DISTANCE_REDUCE, on).apply()
    }

    // 자동 전사 (Pro). 충전 중에만 배치로 녹음을 텍스트로 바꿔 검색을 가능하게 한다.
    // STT 모델(별도 다운로드) 이 있어야 실제로 동작. 기본 꺼짐.
    fun isTranscribeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TRANSCRIBE, false)

    fun setTranscribeEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TRANSCRIBE, on).apply()
    }

    // 진행 중인 STT 모델 다운로드(DownloadManager) ID 목록. 비어 있으면 다운로드 없음.
    fun getSttDownloadIds(ctx: Context): List<Long> =
        prefs(ctx).getString(KEY_STT_DL_IDS, "")!!
            .split(',').mapNotNull { it.trim().toLongOrNull() }

    fun setSttDownloadIds(ctx: Context, ids: List<Long>) {
        prefs(ctx).edit().putString(KEY_STT_DL_IDS, ids.joinToString(",")).apply()
    }

    // ---- 자동 삭제 보관 기간 (시간) ----
    fun getRetentionHours(ctx: Context): Int =
        prefs(ctx).getInt(KEY_RETENTION_HOURS, DEFAULT_RETENTION_HOURS)
            .coerceIn(MIN_RETENTION_HOURS, MAX_RETENTION_HOURS)

    fun setRetentionHours(ctx: Context, hours: Int) {
        prefs(ctx).edit()
            .putInt(KEY_RETENTION_HOURS, hours.coerceIn(MIN_RETENTION_HOURS, MAX_RETENTION_HOURS))
            .apply()
    }

    // ---- 정렬 ----
    const val SORT_NEW = 0    // 최신순
    const val SORT_OLD = 1    // 오래된순
    const val SORT_NAME = 2   // 이름순
    const val SORT_SIZE = 3   // 용량 큰순
    const val SORT_DUR = 4    // 길이 긴순

    fun getSortMode(ctx: Context): Int = prefs(ctx).getInt(KEY_SORT, SORT_NEW)
    fun setSortMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_SORT, mode).apply()
    }

    // ---- 카테고리(폴더 정리) ----
    fun getCategories(ctx: Context): List<String> =
        (prefs(ctx).getStringSet(KEY_CATEGORIES, emptySet()) ?: emptySet()).sorted()

    fun addCategory(ctx: Context, name: String) {
        if (name.isBlank()) return
        val set = HashSet(prefs(ctx).getStringSet(KEY_CATEGORIES, emptySet()) ?: emptySet())
        set.add(name.trim())
        prefs(ctx).edit().putStringSet(KEY_CATEGORIES, set).apply()
    }

    fun getCategory(ctx: Context, key: String): String =
        prefs(ctx).getString(CAT_PREFIX + key, "") ?: ""

    fun setCategory(ctx: Context, key: String, name: String) {
        val e = prefs(ctx).edit()
        if (name.isBlank()) e.remove(CAT_PREFIX + key) else {
            e.putString(CAT_PREFIX + key, name.trim())
            addCategory(ctx, name)
        }
        e.apply()
    }

    // ---- 파일 라벨/메모 ----
    fun getLabel(ctx: Context, key: String): String =
        prefs(ctx).getString(LABEL_PREFIX + key, "") ?: ""

    fun setLabel(ctx: Context, key: String, label: String) {
        prefs(ctx).edit().putString(LABEL_PREFIX + key, label).apply()
    }

    fun removeLabel(ctx: Context, key: String) {
        prefs(ctx).edit().remove(LABEL_PREFIX + key).apply()
    }

    // ---- 북마크 (파일별 중요 지점, 밀리초 목록) ----
    // 저장 형식: "12000,45000,90000" (쉼표 구분 ms)
    fun getBookmarks(ctx: Context, key: String): List<Long> {
        val raw = prefs(ctx).getString(BOOKMARK_PREFIX + key, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { it.toLongOrNull() }.sorted()
    }

    fun addBookmark(ctx: Context, key: String, ms: Long) {
        val list = getBookmarks(ctx, key).toMutableList()
        list.add(ms)
        saveBookmarks(ctx, key, list)
    }

    fun removeBookmark(ctx: Context, key: String, ms: Long) {
        val list = getBookmarks(ctx, key).toMutableList()
        list.remove(ms)
        saveBookmarks(ctx, key, list)
    }

    private fun saveBookmarks(ctx: Context, key: String, list: List<Long>) {
        val raw = list.distinct().sorted().joinToString(",")
        prefs(ctx).edit().putString(BOOKMARK_PREFIX + key, raw).apply()
    }

    fun clearBookmarks(ctx: Context, key: String) {
        prefs(ctx).edit().remove(BOOKMARK_PREFIX + key).apply()
    }

    /**
     * 파일 하나에 딸린 모든 파일별 메타데이터를 한 번에 제거(보호·라벨·북마크·카테고리).
     * 삭제 경로가 이것 하나만 부르면 되도록 모아 둔다 — 예전엔 호출부마다 일부만 지워
     * 삭제된 파일의 북마크·카테고리가 SharedPreferences 에 영구히 남았다.
     * (전사 사이드카·검색 인덱스·.lvl 은 파일 사이드카라 Storage.deleteRecording 이 처리.)
     */
    fun clearFileMeta(ctx: Context, key: String) {
        val set = getProtected(ctx)
        set.remove(key)
        prefs(ctx).edit()
            .putStringSet(KEY_PROTECTED, set)
            .remove(LABEL_PREFIX + key)
            .remove(BOOKMARK_PREFIX + key)
            .remove(CAT_PREFIX + key)
            .apply()
    }

    // ---- 녹음 상태 (엔진 → UI) ----
    fun updateLevel(ctx: Context, level: Double, capturing: Boolean) {
        prefs(ctx).edit()
            .putFloat(KEY_CUR_LEVEL, level.toFloat())
            .putBoolean(KEY_IS_CAPTURING, capturing)
            .apply()
    }

    fun getCurrentLevel(ctx: Context): Double =
        prefs(ctx).getFloat(KEY_CUR_LEVEL, 0f).toDouble()

    fun isCapturing(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_IS_CAPTURING, false)

    // ---- 인코딩 실패(빈/깨진 녹음) 기록 — 가시화용 ----
    // 엔진이 인코딩/먹싱에 실패하거나 0바이트 파일을 만들면 호출한다. 엔진(백그라운드
    // 스레드)과 UI 스레드가 함께 접근할 수 있어 read-modify-write 경합을 막으려 동기화.
    @Synchronized
    fun recordEncodeFailure(ctx: Context) {
        val p = prefs(ctx)
        p.edit()
            .putInt(KEY_ENCODE_FAIL_COUNT, p.getInt(KEY_ENCODE_FAIL_COUNT, 0) + 1)
            .putLong(KEY_ENCODE_FAIL_LAST, System.currentTimeMillis())
            .apply()
    }

    fun getEncodeFailCount(ctx: Context): Int =
        prefs(ctx).getInt(KEY_ENCODE_FAIL_COUNT, 0)

    fun getEncodeFailLast(ctx: Context): Long =
        prefs(ctx).getLong(KEY_ENCODE_FAIL_LAST, 0L)

    /** 사용자가 경고를 확인하면 호출(누적 초기화). */
    @Synchronized
    fun clearEncodeFailures(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_ENCODE_FAIL_COUNT)
            .remove(KEY_ENCODE_FAIL_LAST)
            .apply()
    }

    // ---- 일별 배터리 소모 (기기 전체 기준 추정) ----
    //
    // 안드로이드는 앱별 일별 배터리 정확치를 공개 API 로 주지 않는다.
    // 그래서 배터리 잔량(%)을 주기적으로 표본하고, 잔량이 줄어든 만큼을
    // 그 날짜에 누적한다(충전으로 올라가는 구간은 무시). 즉 "기기 전체"
    // 소모이며, 주로 녹음이 켜져 동작하는 동안 표본된다.

    /** 배터리 잔량(0~100)을 표본해 오늘 날짜에 소모분을 누적. */
    fun recordBatterySample(ctx: Context, level: Int) {
        if (level !in 0..100) return
        val p = prefs(ctx)
        val last = p.getInt(KEY_BATT_LAST_LEVEL, -1)
        val editor = p.edit()
        if (last in 0..100 && level < last) {
            val dk = todayKey()
            val cur = p.getFloat("$BATT_PREFIX$dk", 0f)
            editor.putFloat("$BATT_PREFIX$dk", cur + (last - level))

            val days = HashSet(p.getStringSet(KEY_BATT_DAYS, emptySet()) ?: emptySet())
            days.add(dk)
            if (days.size > BATT_MAX_DAYS) {
                val drop = days.sorted().take(days.size - BATT_MAX_DAYS)
                drop.forEach { d -> editor.remove("$BATT_PREFIX$d"); days.remove(d) }
            }
            editor.putStringSet(KEY_BATT_DAYS, days)
        }
        editor.putInt(KEY_BATT_LAST_LEVEL, level).apply()
    }

    /** 최근순 (yyyymmdd, 소모 %) 목록. */
    fun getBatteryDays(ctx: Context): List<Pair<String, Float>> {
        val p = prefs(ctx)
        val days = (p.getStringSet(KEY_BATT_DAYS, emptySet()) ?: emptySet()).sortedDescending()
        return days.map { it to p.getFloat("$BATT_PREFIX$it", 0f) }
    }

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return "%04d%02d%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    // ---- 위치 기반 녹음 ----

    fun isLocationEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LOC_ENABLED, false)

    fun setLocationEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOC_ENABLED, on).apply()
    }

    fun getLocationMode(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LOC_MODE, LOC_MODE_ONLY_HERE)

    fun setLocationMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_LOC_MODE, mode).apply()
    }

    fun getLocationRadius(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LOC_RADIUS, DEFAULT_LOC_RADIUS)
            .coerceIn(MIN_LOC_RADIUS, MAX_LOC_RADIUS)

    fun setLocationRadius(ctx: Context, m: Int) {
        prefs(ctx).edit()
            .putInt(KEY_LOC_RADIUS, m.coerceIn(MIN_LOC_RADIUS, MAX_LOC_RADIUS)).apply()
    }

    // 측위 간격
    fun getLocationIntervalSec(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LOC_INTERVAL_SEC, DEFAULT_LOC_INTERVAL_SEC)
            .coerceIn(MIN_LOC_INTERVAL_SEC, MAX_LOC_INTERVAL_SEC)

    fun setLocationIntervalSec(ctx: Context, sec: Int) {
        prefs(ctx).edit().putInt(
            KEY_LOC_INTERVAL_SEC, sec.coerceIn(MIN_LOC_INTERVAL_SEC, MAX_LOC_INTERVAL_SEC)
        ).apply()
    }

    fun getLocationIntervalMs(ctx: Context): Long = getLocationIntervalSec(ctx) * 1000L

    // ---- 다중 구역 ----

    /** 한 녹음 구역. mode: ONLY_HERE(포함) / NOT_HERE(제외). */
    data class Zone(
        val id: Int, val name: String,
        val lat: Double, val lng: Double,
        val radius: Int, val mode: Int,
    )

    private fun encodeZone(z: Zone): String = listOf(
        z.id.toString(),
        z.name.replace(Z_SEP, " "),
        java.lang.Double.doubleToRawLongBits(z.lat).toString(),
        java.lang.Double.doubleToRawLongBits(z.lng).toString(),
        z.radius.toString(),
        z.mode.toString(),
    ).joinToString(Z_SEP)

    private fun decodeZone(s: String): Zone? {
        val a = s.split(Z_SEP)
        if (a.size < 6) return null
        return try {
            Zone(
                a[0].toInt(), a[1],
                java.lang.Double.longBitsToDouble(a[2].toLong()),
                java.lang.Double.longBitsToDouble(a[3].toLong()),
                a[4].toInt(), a[5].toInt(),
            )
        } catch (_: Exception) { null }
    }

    fun getZones(ctx: Context): List<Zone> {
        migrateOldCenter(ctx)
        val set = prefs(ctx).getStringSet(KEY_LOC_ZONES, emptySet()) ?: emptySet()
        return set.mapNotNull { decodeZone(it) }.sortedBy { it.id }
    }

    private fun saveZones(ctx: Context, zones: List<Zone>) {
        prefs(ctx).edit()
            .putStringSet(KEY_LOC_ZONES, zones.map { encodeZone(it) }.toHashSet())
            .apply()
    }

    fun addZone(ctx: Context, name: String, lat: Double, lng: Double, radius: Int, mode: Int): Zone {
        val id = prefs(ctx).getInt(KEY_LOC_NEXT_ID, 1)
        val z = Zone(id, name, lat, lng, radius, mode)
        val zones = getZones(ctx).toMutableList().apply { add(z) }
        prefs(ctx).edit().putInt(KEY_LOC_NEXT_ID, id + 1).apply()
        saveZones(ctx, zones)
        return z
    }

    fun updateZone(ctx: Context, z: Zone) {
        saveZones(ctx, getZones(ctx).map { if (it.id == z.id) z else it })
    }

    fun removeZone(ctx: Context, id: Int) {
        saveZones(ctx, getZones(ctx).filter { it.id != id })
    }

    /** 옛 단일 중심 설정을 구역 1개로 1회 이전. */
    private fun migrateOldCenter(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_LOC_ZONES_MIGRATED, false)) return
        val editor = p.edit()
        if (p.getBoolean(KEY_LOC_HAS_CENTER, false)) {
            val lat = java.lang.Double.longBitsToDouble(p.getLong(KEY_LOC_LAT, 0))
            val lng = java.lang.Double.longBitsToDouble(p.getLong(KEY_LOC_LNG, 0))
            val id = p.getInt(KEY_LOC_NEXT_ID, 1)
            val radius = p.getInt(KEY_LOC_RADIUS, DEFAULT_LOC_RADIUS)
            val mode = p.getInt(KEY_LOC_MODE, LOC_MODE_ONLY_HERE)
            val set = HashSet(p.getStringSet(KEY_LOC_ZONES, emptySet()) ?: emptySet())
            set.add(encodeZone(Zone(id, "구역 1", lat, lng, radius, mode)))
            editor.putStringSet(KEY_LOC_ZONES, set).putInt(KEY_LOC_NEXT_ID, id + 1)
        }
        editor.putBoolean(KEY_LOC_ZONES_MIGRATED, true).apply()
    }

    /**
     * 현재 좌표가 녹음 허용 위치인지 (다중 구역).
     *  - 위치 기능 off 또는 구역 없음 → 허용
     *  - 제외(NOT_HERE) 구역 안이면 → 차단(제외가 우선)
     *  - 포함(ONLY_HERE) 구역이 하나라도 있으면 → 그 중 한 곳 안에 있어야 허용
     *  - 포함 구역이 없으면(제외만) → 허용
     * (위치를 못 읽는 상황은 호출 측에서 '허용'으로 처리)
     */
    fun isLocationAllowed(ctx: Context, lat: Double, lng: Double): Boolean {
        if (!isLocationEnabled(ctx)) return true
        val zones = getZones(ctx)
        if (zones.isEmpty()) return true
        if (zones.any { it.mode == LOC_MODE_NOT_HERE && within(it, lat, lng) }) return false
        val inclusion = zones.filter { it.mode == LOC_MODE_ONLY_HERE }
        if (inclusion.isEmpty()) return true
        return inclusion.any { within(it, lat, lng) }
    }

    private fun within(z: Zone, lat: Double, lng: Double): Boolean {
        val r = FloatArray(1)
        Location.distanceBetween(lat, lng, z.lat, z.lng, r)
        return r[0] <= z.radius
    }
}
