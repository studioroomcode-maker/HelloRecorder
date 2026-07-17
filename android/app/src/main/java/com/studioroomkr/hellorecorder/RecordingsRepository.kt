package com.studioroomkr.hellorecorder

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 녹음 데이터 접근 계층. 파일 목록·정렬·필터·삭제·메타데이터(길이·활동 태그)·전사 검색을
 * 한곳에 모은다. MainActivity 곳곳에 흩어져 있던 데이터 로직을 이 seam 뒤로 옮겨,
 * UI 코드와 데이터 접근을 분리하고 단위/계측 테스트가 가능하게 한다.
 *
 * 뷰·액티비티를 참조하지 않는다(applicationContext 만 보관) — 화면 수명과 무관하게 재사용된다.
 * 실제 저장·삭제·전사 인덱스는 Storage/Prefs/TranscriptStore 가 담당하고, 여기서는 그것들을
 * 조합해 "목록 화면이 필요로 하는 질의"를 제공한다.
 */
class RecordingsRepository(context: Context) {

    private val appCtx = context.applicationContext

    // 파일 하나당 길이(ms)·표시문자열·활동 태그를 캐시한다. 키는 경로+수정시각이라
    // 같은 이름으로 내용이 바뀌면(편집 덮어쓰기) 자동으로 무효화된다.
    private val durationMsCache = ConcurrentHashMap<String, Long>()
    private val durationTextCache = ConcurrentHashMap<String, String>()
    private val tagCache = ConcurrentHashMap<String, String>()

    private fun ck(f: File): String = "${f.absolutePath}:${f.lastModified()}"

    fun key(f: File): String = Storage.relativeKey(appCtx, f)

    // ── 목록 질의 ──

    fun listDayDirs(): List<File> = Storage.listDayDirs(appCtx)

    /** 한 날짜 폴더에서 필터(검색·카테고리)·정렬을 적용한 .m4a 목록. */
    fun filesForDay(dayDir: File, query: String, category: String?, isPro: Boolean): List<File> {
        val raw = dayDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".m4a") }
            ?.filter { matches(it, dayDir.name, query) }
            ?.filter { category == null || Prefs.getCategory(appCtx, key(it)) == category }
            ?: emptyList()
        return sort(raw, isPro)
    }

    /** 검색어 매칭(파일명·라벨·날짜·길이). 빈 질의면 전체 통과. */
    fun matches(f: File, dayName: String, query: String): Boolean {
        if (query.isEmpty()) return true
        val q = query.lowercase()
        val label = Prefs.getLabel(appCtx, key(f))
        return f.name.lowercase().contains(q) ||
            label.lowercase().contains(q) ||
            dayName.contains(q) ||
            durationText(f).contains(q)   // 길이(예: "1:23")로도 검색
    }

    /** 정렬. 무료는 최신순 고정. */
    fun sort(files: List<File>, isPro: Boolean): List<File> {
        val mode = if (isPro) Prefs.getSortMode(appCtx) else Prefs.SORT_NEW
        return when (mode) {
            Prefs.SORT_OLD -> files.sortedBy { it.lastModified() }
            Prefs.SORT_NAME -> files.sortedBy { it.name }
            Prefs.SORT_SIZE -> files.sortedByDescending { it.length() }
            Prefs.SORT_DUR -> files.sortedByDescending { durationMs(it) }
            else -> files.sortedByDescending { it.lastModified() }
        }
    }

    // ── 메타데이터(블로킹 — 호출부가 백그라운드에서 부른다) ──

    /** 파일 길이(ms). 한 번의 메타데이터 읽기로 ms·표시문자열 캐시를 함께 채운다. */
    fun durationMs(f: File): Long {
        val c = ck(f)
        durationMsCache[c]?.let { return it }
        // setDataSource 가 던지면(손상·삭제 파일) release() 가 건너뛰어져 네이티브 객체가 샌다.
        // finally 로 반드시 해제한다.
        val mmr = MediaMetadataRetriever()
        val ms = try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
        durationMsCache[c] = ms
        durationTextCache[c] =
            if (ms > 0) { val s = ms / 1000; "%d:%02d".format(s / 60, s % 60) } else "--:--"
        return ms
    }

    /** 표시용 길이 문자열. 캐시에 없으면 읽어서 채운다(블로킹). */
    fun durationText(f: File): String {
        durationTextCache[ck(f)]?.let { return it }
        durationMs(f)
        return durationTextCache[ck(f)] ?: "--:--"
    }

    /** 캐시에 있는 길이 문자열만(블로킹 없이). 없으면 null → 호출부가 "…" 로 먼저 그린다. */
    fun cachedDurationText(f: File): String? = durationTextCache[ck(f)]

    /**
     * .lvl 활동 프로필을 집계한 짧은 태그(예: "대화 약 3분", "대부분 무음"). 블로킹.
     * 프로필 없음(구버전) 또는 소리는 있으나 음성 미분석이면 "" (오해 방지).
     */
    fun activityTag(f: File): String {
        val c = ck(f)
        tagCache[c]?.let { return it }
        val prof = Storage.readActivityProfile(f)
        val tag = if (prof == null || prof.voice.isEmpty()) {
            ""
        } else {
            val n = prof.voice.size
            val voiceCount = prof.voice.count { it }
            val loudCount = prof.levels.count { it >= 0.12f }
            when {
                voiceCount > 0 -> {
                    val durMs = durationMsCache[c] ?: durationMs(f)
                    val base = if (durMs > 0) durMs else n * 500L
                    val speechSec = (base / 1000.0 * voiceCount / n).toLong().coerceAtLeast(1)
                    if (speechSec >= 60) I18n.f("대화 약 %d분", speechSec / 60)
                    else I18n.f("대화 약 %d초", speechSec)
                }
                loudCount.toFloat() / n < 0.05f -> I18n.t("대부분 무음")
                else -> ""
            }
        }
        tagCache[c] = tag
        return tag
    }

    /** 캐시에 있는 활동 태그만(블로킹 없이). */
    fun cachedTag(f: File): String? = tagCache[ck(f)]

    // ── 변경 ──

    /** 오디오+사이드카+전사+메타를 한 번에 삭제. */
    fun delete(f: File): Boolean = Storage.deleteRecording(appCtx, f)

    // ── 카테고리·전사 검색 ──

    fun categories(): List<String> = Prefs.getCategories(appCtx)

    fun searchTranscripts(query: String, limit: Int): List<TranscriptStore.Hit> =
        try { TranscriptStore.search(appCtx, query, limit) } catch (_: Exception) { emptyList() }
}
