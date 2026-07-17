package com.studioroomkr.hellorecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Share.uriFor 가 FileProvider 루트에 설정된 파일과, 루트 밖(SD카드 격) 파일 모두에 대해
 * URI 를 돌려주는지 검증. 예전엔 SD카드 저장 위치의 파일을 공유하면 getUriForFile 이
 * IllegalArgumentException 을 던져 앱이 죽었다. 이제 그런 파일은 캐시로 복사해 공유한다.
 *
 * 에뮬레이터엔 보조 외부 저장소(SD카드)가 없어, "루트 밖 파일"은 FileProvider 어느 루트도
 * 덮지 않는 경로(codeCacheDir 하위)로 대신 재현한다 — 폴백 발동 조건은 동일하다.
 */
@RunWith(AndroidJUnit4::class)
class ShareUriTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 설정된_루트의_파일은_그대로_URI() {
        val dir = Storage.dayDir(ctx)   // recordings/ 아래 — file_paths 에 설정됨
        val f = File(dir, "share_ok_${System.nanoTime()}.m4a").apply { writeBytes(ByteArray(64)) }
        try {
            val uri = Share.uriFor(ctx, f)
            assertNotNull("설정된 루트인데 URI 실패", uri)
        } finally {
            Storage.deleteRecording(ctx, f)
        }
    }

    @Test
    fun 루트_밖_파일은_캐시로_복사돼_URI를_받는다() {
        // 어떤 FileProvider 루트도 덮지 않는 경로(SD카드 상황 대역).
        val outside = File(ctx.codeCacheDir, "outside_${System.nanoTime()}.m4a")
            .apply { writeBytes(ByteArray(128) { 7 }) }
        try {
            val uri = Share.uriFor(ctx, outside)
            assertNotNull("루트 밖 파일 폴백 실패 — SD카드 공유가 여전히 깨짐", uri)
            // 캐시로 복사됐는지 확인
            val copy = File(File(ctx.cacheDir, "share"), outside.name)
            assertTrue("공유 캐시로 복사되지 않음", copy.exists())
            assertTrue("복사본 내용 손상", copy.readBytes().all { it == 7.toByte() })
        } finally {
            outside.delete()
            File(File(ctx.cacheDir, "share"), outside.name).delete()
        }
    }
}
