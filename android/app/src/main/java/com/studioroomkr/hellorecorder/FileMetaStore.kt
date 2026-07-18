package com.studioroomkr.hellorecorder

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * 파일별 메타데이터(보관·라벨·카테고리·북마크) 저장소 = SQLite 한 테이블 + 인메모리 캐시.
 *
 * 예전엔 SharedPreferences 에 파일 키마다 label_<key>·bookmark_<key>·cat_<key> 로 흩어져
 * 쌓였고, 삭제 때 일부만 지워져 짝 잃은 키가 영구히 남거나 마이그레이션이 어려웠다. 이제
 * 파일 하나 = 한 행이라 삭제가 한 번에 끝나고(delete row), 구조적으로 orphan 이 쌓이지 않는다.
 *
 * TranscriptStore 와 같은 SQLiteOpenHelper 패턴을 쓴다(코드베이스 일관성 — Room/KSP 미도입).
 * 읽기는 인메모리 캐시로 처리해 SharedPreferences 수준의 속도를 유지하고, 쓰기는 캐시+DB
 * write-through. Prefs 의 메타 메서드들이 이 저장소로 위임하므로 호출부는 그대로다.
 */
object FileMetaStore {

    /** 파일 한 개의 메타. bookmarks 는 "12000,45000" CSV(ms). */
    data class Meta(
        val protectedOn: Boolean = false,
        val label: String = "",
        val category: String = "",
        val bookmarks: String = "",
    ) {
        fun isEmpty() = !protectedOn && label.isEmpty() && category.isEmpty() && bookmarks.isEmpty()
    }

    private val cache = ConcurrentHashMap<String, Meta>()

    @Volatile private var loaded = false
    @Volatile private var dbHelper: DbHelper? = null

    private fun helper(ctx: Context): DbHelper =
        dbHelper ?: synchronized(this) {
            dbHelper ?: DbHelper(ctx.applicationContext).also { dbHelper = it }
        }

    /** 첫 접근 시 SharedPreferences → DB 1회 마이그레이션 + 캐시 로드. 이후엔 캐시만. */
    /**
     * DB 로드 + 1회 마이그레이션을 미리 돌려 둔다(앱 시작 시 백그라운드에서 호출).
     * 목록의 첫 메타 접근이 메인 스레드에서 무거운 로드를 유발하지 않게 한다.
     */
    fun warmUp(ctx: Context) {
        try { ensureLoaded(ctx) } catch (_: Exception) {}
    }

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val db = helper(ctx).writableDatabase
            Prefs.migrateFileMetaIfNeeded(ctx) { rows ->
                // 마이그레이션: 옛 prefs 값들을 한 트랜잭션으로 넣는다.
                db.beginTransaction()
                try {
                    for ((key, m) in rows) upsertDb(db, key, m)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            db.query("file_meta", null, null, null, null, null, null).use { c ->
                val iKey = c.getColumnIndexOrThrow("key")
                val iProt = c.getColumnIndexOrThrow("protected")
                val iLabel = c.getColumnIndexOrThrow("label")
                val iCat = c.getColumnIndexOrThrow("category")
                val iBm = c.getColumnIndexOrThrow("bookmarks")
                while (c.moveToNext()) {
                    cache[c.getString(iKey)] = Meta(
                        c.getInt(iProt) != 0, c.getString(iLabel),
                        c.getString(iCat), c.getString(iBm)
                    )
                }
            }
            loaded = true
        }
    }

    private fun get(ctx: Context, key: String): Meta {
        ensureLoaded(ctx)
        return cache[key] ?: Meta()
    }

    /** 캐시+DB 갱신. 결과가 전부 기본값이면 행을 지운다(빈 행 누적 방지). */
    private fun put(ctx: Context, key: String, m: Meta) {
        ensureLoaded(ctx)
        val db = helper(ctx).writableDatabase
        if (m.isEmpty()) {
            cache.remove(key)
            db.delete("file_meta", "key = ?", arrayOf(key))
        } else {
            cache[key] = m
            upsertDb(db, key, m)
        }
    }

    private fun upsertDb(db: SQLiteDatabase, key: String, m: Meta) {
        val cv = android.content.ContentValues().apply {
            put("key", key)
            put("protected", if (m.protectedOn) 1 else 0)
            put("label", m.label)
            put("category", m.category)
            put("bookmarks", m.bookmarks)
        }
        db.insertWithOnConflict("file_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ── 보관(protected) ──

    fun isProtected(ctx: Context, key: String): Boolean = get(ctx, key).protectedOn

    fun getProtected(ctx: Context): MutableSet<String> {
        ensureLoaded(ctx)
        return cache.entries.filter { it.value.protectedOn }.mapTo(HashSet()) { it.key }
    }

    fun setProtected(ctx: Context, key: String, on: Boolean) {
        put(ctx, key, get(ctx, key).copy(protectedOn = on))
    }

    // ── 라벨 ──

    fun getLabel(ctx: Context, key: String): String = get(ctx, key).label

    fun setLabel(ctx: Context, key: String, label: String) {
        put(ctx, key, get(ctx, key).copy(label = label))
    }

    fun removeLabel(ctx: Context, key: String) {
        put(ctx, key, get(ctx, key).copy(label = ""))
    }

    // ── 카테고리(파일별 배정) ──

    fun getCategory(ctx: Context, key: String): String = get(ctx, key).category

    fun setCategory(ctx: Context, key: String, name: String) {
        put(ctx, key, get(ctx, key).copy(category = name))
    }

    // ── 북마크 ──

    fun getBookmarks(ctx: Context, key: String): List<Long> {
        val raw = get(ctx, key).bookmarks
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { it.toLongOrNull() }.sorted()
    }

    fun setBookmarks(ctx: Context, key: String, list: List<Long>) {
        val raw = list.distinct().sorted().joinToString(",")
        put(ctx, key, get(ctx, key).copy(bookmarks = raw))
    }

    fun clearBookmarks(ctx: Context, key: String) {
        put(ctx, key, get(ctx, key).copy(bookmarks = ""))
    }

    // ── 일괄 삭제 ──

    /** 파일 하나에 딸린 모든 메타를 제거(행 삭제). */
    fun clearFileMeta(ctx: Context, key: String) {
        ensureLoaded(ctx)
        cache.remove(key)
        helper(ctx).writableDatabase.delete("file_meta", "key = ?", arrayOf(key))
    }

    private class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "file_meta.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE file_meta (" +
                    "key TEXT PRIMARY KEY NOT NULL, " +
                    "protected INTEGER NOT NULL DEFAULT 0, " +
                    "label TEXT NOT NULL DEFAULT '', " +
                    "category TEXT NOT NULL DEFAULT '', " +
                    "bookmarks TEXT NOT NULL DEFAULT '')"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 — 아직 스키마 변경 없음.
        }
    }
}
