package com.studioroomkr.hellorecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Storage.deleteRecording / invalidateDerived 가 오디오에 딸린 모든 파생물을 정말 함께
 * 정리하는지 검증. 예전엔 삭제 경로마다 일부만 지워, 삭제된 파일의 전사가 검색에 계속
 * 잡히고 북마크·카테고리가 SharedPreferences 에 영구히 남았다.
 */
@RunWith(AndroidJUnit4::class)
class DeleteConsolidationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var m4a: File
    private lateinit var key: String

    /** 오디오 + .lvl + 전사(사이드카+인덱스) + 메타(보호/라벨/북마크/카테고리)를 모두 만든다. */
    @Before
    fun setup() {
        val dir = Storage.dayDir(ctx)
        m4a = File(dir, "deltest_${System.nanoTime()}.m4a").apply { writeBytes(ByteArray(1024)) }
        key = Storage.relativeKey(ctx, m4a)

        Storage.writeActivityProfile(m4a, ByteArray(8) { 0x80.toByte() })
        TranscriptStore.write(
            ctx, m4a,
            Transcriber.Transcript(3_000, listOf(Transcriber.Segment(500, "테스트발화내용")))
        )
        Prefs.setProtected(ctx, key, true)
        Prefs.addBookmark(ctx, key, 1_500)
        Prefs.setCategory(ctx, key, "회의")

        // 사전조건: 모든 파생물이 존재
        assertTrue("audio", m4a.exists())
        assertTrue(".lvl", Storage.profileFile(m4a).exists())
        assertTrue(".stt.json", TranscriptStore.sidecarFile(m4a).exists())
        assertTrue("검색 인덱스", TranscriptStore.search(ctx, "테스트발화").any { it.key == key })
        assertTrue("보호", Prefs.isProtected(ctx, key))
        assertEquals("북마크", 1, Prefs.getBookmarks(ctx, key).size)
        assertEquals("카테고리", "회의", Prefs.getCategory(ctx, key))
    }

    @After
    fun cleanup() {
        if (::m4a.isInitialized) Storage.deleteRecording(ctx, m4a)
    }

    @Test
    fun deleteRecording_모든_파생물을_함께_제거한다() {
        assertTrue(Storage.deleteRecording(ctx, m4a))

        assertFalse("audio 남음", m4a.exists())
        assertFalse(".lvl 남음", Storage.profileFile(m4a).exists())
        assertFalse(".stt.json 남음", TranscriptStore.sidecarFile(m4a).exists())
        // 핵심 회귀: 삭제된 파일이 검색에 계속 잡히던 문제
        assertTrue("삭제됐는데 검색에 잡힘",
            TranscriptStore.search(ctx, "테스트발화").none { it.key == key })
        assertFalse("보호 플래그 남음", Prefs.isProtected(ctx, key))
        assertTrue("북마크 남음", Prefs.getBookmarks(ctx, key).isEmpty())
        assertEquals("카테고리 남음", "", Prefs.getCategory(ctx, key))
    }

    @Test
    fun invalidateDerived_전사와_북마크는_지우되_오디오는_남긴다() {
        // 편집 덮어쓰기 시나리오: 오디오 파일은 새 내용으로 교체돼 남아 있고, 옛 파생물만 무효화
        Storage.invalidateDerived(ctx, m4a)

        assertTrue("오디오는 남아야 함", m4a.exists())
        assertFalse(".lvl 은 무효화(재생성됨)", Storage.profileFile(m4a).exists())
        assertTrue("옛 전사가 검색에 남음",
            TranscriptStore.search(ctx, "테스트발화").none { it.key == key })
        assertTrue("옛 북마크 남음", Prefs.getBookmarks(ctx, key).isEmpty())
        assertFalse(".stt.json 남음", TranscriptStore.sidecarFile(m4a).exists())
    }
}
