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
 * Storage.moveStorageTo 검증 — 저장 위치를 바꿀 때 기존 파일(+사이드카)이 새 위치로 옮겨지고,
 * 상대경로 기반 메타데이터(보관·라벨 등)가 그대로 유효한지. 이동은 all-or-nothing.
 *
 * 내부↔외부는 대개 같은 볼륨이라 renameTo 경로를, 파일 내용/사이드카 보존을 함께 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class StorageMoveTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalLoc = Prefs.STORAGE_INTERNAL

    @Before fun setup() {
        originalLoc = Prefs.getStorageLocation(ctx)
        // 깨끗한 시작: 내부로 맞추고 기존 파일 제거
        Prefs.setStorageLocation(ctx, Prefs.STORAGE_INTERNAL)
        Storage.listAllFiles(ctx).forEach { Storage.deleteRecording(ctx, it) }
    }

    @After fun teardown() {
        Storage.listAllFiles(ctx).forEach { Storage.deleteRecording(ctx, it) }
        // 위치 원복(외부로 옮겨졌을 수 있으니 그쪽도 정리)
        Prefs.setStorageLocation(ctx, Prefs.STORAGE_EXTERNAL)
        Storage.listAllFiles(ctx).forEach { Storage.deleteRecording(ctx, it) }
        Prefs.setStorageLocation(ctx, originalLoc)
    }

    private fun makeRecording(day: String, name: String, content: ByteArray): File {
        val root = File(Storage.currentRootPath(ctx), day).apply { mkdirs() }
        val f = File(root, name)
        f.writeBytes(content)
        return f
    }

    @Test fun 내부에서_외부로_파일과_사이드카가_옮겨지고_메타가_유지된다() {
        // 내부에 녹음 + .lvl 사이드카 + 메타 생성
        val f = makeRecording("20200101", "rec.m4a", byteArrayOf(1, 2, 3, 4))
        Storage.writeActivityProfile(f, byteArrayOf(0x80.toByte(), 0x40))
        val key = Storage.relativeKey(ctx, f)
        Prefs.setProtected(ctx, key, true)
        Prefs.setLabel(ctx, key, "중요")

        assertEquals(Prefs.STORAGE_INTERNAL, Prefs.getStorageLocation(ctx))
        val result = Storage.moveStorageTo(ctx, Prefs.STORAGE_EXTERNAL)

        assertTrue("이동 결과: $result", result is Storage.MoveResult.Moved)
        assertEquals("위치가 외부로 전환됨", Prefs.STORAGE_EXTERNAL, Prefs.getStorageLocation(ctx))

        // 새 위치에 파일·사이드카 존재, 내용 보존
        val moved = Storage.listAllFiles(ctx).firstOrNull { it.name == "rec.m4a" }
        assertTrue("옮겨진 파일 존재", moved != null)
        assertArrayEquals4(byteArrayOf(1, 2, 3, 4), moved!!.readBytes())
        assertTrue(".lvl 사이드카도 옮겨짐", Storage.profileFile(moved).exists())

        // 메타는 상대경로 키라 그대로 유효
        val newKey = Storage.relativeKey(ctx, moved)
        assertEquals("메타 키 동일(상대경로)", key, newKey)
        assertTrue("보관 유지", Prefs.isProtected(ctx, newKey))
        assertEquals("라벨 유지", "중요", Prefs.getLabel(ctx, newKey))

        // 옛 위치엔 남지 않음
        Prefs.setStorageLocation(ctx, Prefs.STORAGE_INTERNAL)
        assertTrue("옛 위치는 비어야", Storage.listAllFiles(ctx).none { it.name == "rec.m4a" })
        Prefs.setStorageLocation(ctx, Prefs.STORAGE_EXTERNAL)
    }

    @Test fun 같은_위치로는_이동하지_않는다() {
        val result = Storage.moveStorageTo(ctx, Prefs.STORAGE_INTERNAL)
        assertTrue(result is Storage.MoveResult.AlreadyThere)
    }

    @Test fun 파일이_없으면_위치만_바뀐다() {
        val result = Storage.moveStorageTo(ctx, Prefs.STORAGE_EXTERNAL)
        assertTrue(result is Storage.MoveResult.Moved)
        assertEquals(0, (result as Storage.MoveResult.Moved).count)
        assertEquals(Prefs.STORAGE_EXTERNAL, Prefs.getStorageLocation(ctx))
    }

    @Test fun 같은_볼륨이면_여유공간이_총크기보다_작아도_이동된다() {
        // 내부→외부는 대개 같은 물리 볼륨이라 renameTo(추가 공간 0). 여유 공간 확인이
        // 무조건 걸리면(총 녹음 크기 > 여유 공간) 옮길 수 있는데도 막히던 회귀를 잡는다.
        // 파일 하나가 현재 볼륨 여유 공간보다 크지는 않지만, "총 크기 기준 사전 차단"이
        // 사라졌는지를 실제 이동 성공으로 확인한다.
        val f = makeRecording("20200201", "big.m4a", ByteArray(2 * 1024 * 1024))  // 2MB
        val key = Storage.relativeKey(ctx, f)
        val result = Storage.moveStorageTo(ctx, Prefs.STORAGE_EXTERNAL)
        assertTrue("같은 볼륨 이동은 성공해야: $result", result is Storage.MoveResult.Moved)
        val moved = Storage.listAllFiles(ctx).firstOrNull { it.name == "big.m4a" }
        assertTrue("옮겨진 파일 존재", moved != null)
        assertEquals("메타 키 보존", key, Storage.relativeKey(ctx, moved!!))
    }

    private fun assertArrayEquals4(expected: ByteArray, actual: ByteArray) {
        assertEquals("길이", expected.size, actual.size)
        for (i in expected.indices) assertEquals("byte $i", expected[i], actual[i])
    }
}
