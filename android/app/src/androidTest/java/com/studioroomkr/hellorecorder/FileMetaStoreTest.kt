package com.studioroomkr.hellorecorder

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FileMetaStore(파일별 메타 SQLite 저장소)와 Prefs 위임의 왕복·삭제·마이그레이션 검증.
 * 메타 저장의 백엔드가 SharedPreferences → SQLite 로 바뀌었으므로, 기존 API 동작이 그대로인지
 * (보관·라벨·카테고리·북마크 읽고 쓰기, clearFileMeta 로 한 번에 삭제)를 잠근다.
 */
@RunWith(AndroidJUnit4::class)
class FileMetaStoreTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = ArrayList<String>()

    private fun k(name: String) = "test/${name}_${System.nanoTime()}".also { keys.add(it) }

    @After fun cleanup() { keys.forEach { FileMetaStore.clearFileMeta(ctx, it) } }

    @Test fun 보관_라벨_카테고리_북마크_왕복() {
        val key = k("rt")
        // 기본값
        assertFalse(Prefs.isProtected(ctx, key))
        assertEquals("", Prefs.getLabel(ctx, key))
        assertEquals("", Prefs.getCategory(ctx, key))
        assertTrue(Prefs.getBookmarks(ctx, key).isEmpty())

        Prefs.setProtected(ctx, key, true)
        Prefs.setLabel(ctx, key, "중요 회의")
        Prefs.setCategory(ctx, key, "회의")
        Prefs.addBookmark(ctx, key, 12_000)
        Prefs.addBookmark(ctx, key, 3_000)

        assertTrue(Prefs.isProtected(ctx, key))
        assertEquals("중요 회의", Prefs.getLabel(ctx, key))
        assertEquals("회의", Prefs.getCategory(ctx, key))
        assertEquals(listOf(3_000L, 12_000L), Prefs.getBookmarks(ctx, key))   // 정렬됨
        assertTrue("getProtected 에 포함", Prefs.getProtected(ctx).contains(key))
        assertTrue("카테고리명 집합에 등록", Prefs.getCategories(ctx).contains("회의"))
    }

    @Test fun 북마크_추가_제거() {
        val key = k("bm")
        Prefs.addBookmark(ctx, key, 1000)
        Prefs.addBookmark(ctx, key, 2000)
        Prefs.removeBookmark(ctx, key, 1000)
        assertEquals(listOf(2000L), Prefs.getBookmarks(ctx, key))
    }

    @Test fun clearFileMeta_는_한번에_모두_지운다() {
        val key = k("clr")
        Prefs.setProtected(ctx, key, true)
        Prefs.setLabel(ctx, key, "라벨")
        Prefs.setCategory(ctx, key, "cat")
        Prefs.addBookmark(ctx, key, 5000)

        Prefs.clearFileMeta(ctx, key)

        assertFalse(Prefs.isProtected(ctx, key))
        assertEquals("", Prefs.getLabel(ctx, key))
        assertEquals("", Prefs.getCategory(ctx, key))
        assertTrue(Prefs.getBookmarks(ctx, key).isEmpty())
        assertFalse("getProtected 에서도 빠짐", Prefs.getProtected(ctx).contains(key))
    }

    @Test fun 보관_해제하고_라벨_지우면_흔적이_남지_않는다() {
        val key = k("empty")
        Prefs.setProtected(ctx, key, true)
        Prefs.setProtected(ctx, key, false)
        Prefs.setLabel(ctx, key, "x")
        Prefs.removeLabel(ctx, key)
        // 전부 기본값이면 행이 없어야(빈 행 누적 방지) — getProtected 목록에 안 나타남
        assertFalse(Prefs.getProtected(ctx).contains(key))
    }

    @Test fun 빈_라벨_설정은_행을_만들지_않는다() {
        val key = k("blank")
        Prefs.setCategory(ctx, key, "")   // 빈 배정
        assertEquals("", Prefs.getCategory(ctx, key))
        assertFalse(Prefs.getProtected(ctx).contains(key))
    }

    @Test fun 마이그레이션은_옛_prefs_메타를_DB로_옮긴다() {
        // 실제 메타는 앱 첫 접근 때 이미 마이그레이션돼 옛 키가 제거된 상태라, 여기서 주입한
        // 테스트 키만 잡힌다(실데이터 영향 없음). 마커를 false 로 되돌려 마이그레이션을 재실행.
        val sp = ctx.getSharedPreferences("hello_recorder_prefs", Context.MODE_PRIVATE)
        val fk = "migtest/file_${System.nanoTime()}"
        keys.add(fk)
        sp.edit().apply {
            putBoolean("_migrated_from_always_recorder", true) // 옛 파일 마이그레이션은 건너뛰게
            putBoolean("meta_migrated_to_db", false)           // 메타 마이그레이션 재실행
            putStringSet("protected_files", setOf(fk))
            putString("label_$fk", "옛 라벨")
            putString("bookmark_$fk", "1000,2000")
            putString("cat_$fk", "옛카테고리")
        }.commit()

        val captured = HashMap<String, FileMetaStore.Meta>()
        Prefs.migrateFileMetaIfNeeded(ctx) { rows -> captured.putAll(rows) }

        val m = captured[fk]
        assertNotNull("마이그레이션이 이 키를 옮겨야 함", m)
        assertTrue(m!!.protectedOn)
        assertEquals("옛 라벨", m.label)
        assertEquals("옛카테고리", m.category)
        assertEquals("1000,2000", m.bookmarks)
        // 옛 키 제거 + 마커 설정
        assertFalse("옛 라벨 키 제거됨", sp.contains("label_$fk"))
        assertFalse("옛 북마크 키 제거됨", sp.contains("bookmark_$fk"))
        assertTrue("마이그레이션 마커 설정됨", sp.getBoolean("meta_migrated_to_db", false))
    }
}
