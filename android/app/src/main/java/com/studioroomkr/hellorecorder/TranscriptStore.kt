package com.studioroomkr.hellorecorder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 전사 결과 저장소 = ① .stt.json 사이드카(원본·백업·이식용) + ② SQLite 검색 인덱스.
 *
 * 사이드카가 진실의 원천(source of truth)이다: 인덱스는 사이드카에서 언제든 재구축할 수
 * 있고(rebuildIfEmpty), 파일 삭제 시 사이드카·인덱스를 함께 정리한다(.lvl 패턴과 동일).
 *
 * 검색은 세그먼트 단위 LIKE 매칭이다. FTS 를 쓰지 않는 이유: 한국어 전사는 공백이 거의
 * 없어(예: "놀면뭐하니언제까지재미있을까?") 토크나이저 기반 FTS 로는 부분 문자열 검색이
 * 안 된다. 대신 공백을 제거한 정규화 컬럼(norm)에 LIKE '%질의%' 를 건다 — 행이 세그먼트
 * 단위(짧은 발화)라 수만 행까지는 충분히 빠르고, 질의도 공백 제거로 같은 규칙을 탄다.
 */
object TranscriptStore {

    private const val SIDECAR_EXT = ".stt.json"

    // ─── 사이드카 ───

    fun sidecarFile(m4a: File): File =
        File(m4a.parentFile, m4a.nameWithoutExtension + SIDECAR_EXT)

    /** 전사 완료 여부(빈 전사 포함 — 재시도 방지를 위해 빈 결과도 사이드카를 남긴다). */
    fun hasTranscript(m4a: File): Boolean = sidecarFile(m4a).exists()

    /** 전사 저장: 사이드카 기록 + 인덱스 갱신. 사이드카 실패 시 인덱스도 넣지 않는다. */
    fun write(ctx: Context, m4a: File, transcript: Transcriber.Transcript) {
        val json = JSONObject().apply {
            put("v", 1)
            put("audioMs", transcript.audioMs)
            put("createdMs", System.currentTimeMillis())
            put("segments", JSONArray().apply {
                transcript.segments.forEach { seg ->
                    put(JSONObject().apply { put("s", seg.startMs); put("t", seg.text) })
                }
            })
        }
        try {
            sidecarFile(m4a).writeText(json.toString())
        } catch (_: Exception) { return }
        index(ctx, m4a, transcript)
    }

    /** 사이드카 읽기(플레이어의 전사 표시 등). 없거나 깨졌으면 null. */
    fun readSidecar(m4a: File): Transcriber.Transcript? = try {
        val json = JSONObject(sidecarFile(m4a).readText())
        val arr = json.getJSONArray("segments")
        val segments = ArrayList<Transcriber.Segment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            segments.add(Transcriber.Segment(o.getLong("s"), o.getString("t")))
        }
        Transcriber.Transcript(json.optLong("audioMs"), segments)
    } catch (_: Exception) { null }

    // ─── 검색 인덱스 ───

    /** 검색 결과 한 건 = 세그먼트 하나. key 로 파일 복원, startMs 로 재생 위치 이동. */
    data class Hit(val key: String, val startMs: Long, val text: String, val mtime: Long)

    /** 부분 문자열 검색(공백 무시). 최신 파일 우선. */
    fun search(ctx: Context, query: String, limit: Int = 200): List<Hit> {
        val norm = normalize(query)
        if (norm.isEmpty()) return emptyList()
        val db = helper(ctx).readableDatabase
        val escaped = norm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val hits = ArrayList<Hit>()
        db.rawQuery(
            "SELECT key, start_ms, text, mtime FROM segments " +
                "WHERE norm LIKE ? ESCAPE '\\' ORDER BY mtime DESC, start_ms ASC LIMIT ?",
            arrayOf("%$escaped%", limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                hits.add(Hit(c.getString(0), c.getLong(1), c.getString(2), c.getLong(3)))
            }
        }
        return hits
    }

    /** 인덱스에 전사된 파일 수(설정 화면 상태 표시용). */
    fun indexedFileCount(ctx: Context): Int {
        val db = helper(ctx).readableDatabase
        db.rawQuery("SELECT COUNT(DISTINCT key) FROM segments", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 파일 삭제 경로에서 호출 — 사이드카 + 인덱스 행 제거. */
    fun removeFor(ctx: Context, m4a: File) {
        try { sidecarFile(m4a).delete() } catch (_: Exception) {}
        try {
            helper(ctx).writableDatabase
                .delete("segments", "key = ?", arrayOf(Storage.relativeKey(ctx, m4a)))
        } catch (_: Exception) {}
    }

    /** 짝 잃은 사이드카·인덱스 행 정리(CleanupWorker 안전망). */
    fun sweepOrphans(ctx: Context) {
        // 사이드카: .m4a 없는 .stt.json 삭제
        Storage.listDayDirs(ctx).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(SIDECAR_EXT)) {
                    val base = f.name.removeSuffix(SIDECAR_EXT)
                    if (!File(dir, "$base.m4a").exists()) f.delete()
                }
            }
        }
        // 인덱스: 실제 파일 없는 키 삭제
        try {
            val db = helper(ctx).writableDatabase
            val stale = ArrayList<String>()
            db.rawQuery("SELECT DISTINCT key FROM segments", null).use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0)
                    if (!Storage.fileForKey(ctx, key).exists()) stale.add(key)
                }
            }
            stale.forEach { db.delete("segments", "key = ?", arrayOf(it)) }
        } catch (_: Exception) {}
    }

    /** 인덱스가 비어 있는데 사이드카가 있으면(DB 삭제·이관 등) 사이드카에서 재구축. */
    fun rebuildIfEmpty(ctx: Context) {
        try {
            if (indexedFileCount(ctx) > 0) return
            Storage.listAllFiles(ctx).forEach { m4a ->
                if (hasTranscript(m4a)) readSidecar(m4a)?.let { index(ctx, m4a, it) }
            }
        } catch (_: Exception) {}
    }

    private fun index(ctx: Context, m4a: File, transcript: Transcriber.Transcript) {
        try {
            val db = helper(ctx).writableDatabase
            val key = Storage.relativeKey(ctx, m4a)
            db.beginTransaction()
            try {
                db.delete("segments", "key = ?", arrayOf(key))
                transcript.segments.forEach { seg ->
                    db.insert("segments", null, ContentValues().apply {
                        put("key", key)
                        put("start_ms", seg.startMs)
                        put("text", seg.text)
                        put("norm", normalize(seg.text))
                        put("mtime", m4a.lastModified())
                    })
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (_: Exception) {}
    }

    /** 매칭 정규화: 공백 제거 + 소문자화. 인덱스 컬럼과 질의에 같은 규칙 적용. */
    fun normalize(s: String): String = s.replace(WHITESPACE, "").lowercase()

    private val WHITESPACE = "\\s+".toRegex()

    // ─── DB ───

    @Volatile private var dbHelper: DbHelper? = null

    private fun helper(ctx: Context): DbHelper =
        dbHelper ?: synchronized(this) {
            dbHelper ?: DbHelper(ctx.applicationContext).also { dbHelper = it }
        }

    private class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "transcripts.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE segments (" +
                    "key TEXT NOT NULL, start_ms INTEGER NOT NULL, " +
                    "text TEXT NOT NULL, norm TEXT NOT NULL, mtime INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX idx_segments_key ON segments(key)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 — 아직 마이그레이션 없음. 인덱스는 사이드카에서 재구축 가능하므로
            // 마이그레이션이 복잡해지면 drop+rebuild 전략을 쓴다.
        }
    }
}
