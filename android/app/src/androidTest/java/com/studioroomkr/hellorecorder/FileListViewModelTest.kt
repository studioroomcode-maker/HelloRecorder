package com.studioroomkr.hellorecorder

import android.app.Application
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
 * RecordingsRepository + FileListViewModel 의 표시 모델 계산을 검증한다. MainActivity 에서
 * 빼낸 목록 질의·필터·접힘·선택 로직이 그대로 동작하는지 잠근다(추출 회귀 방지).
 */
@RunWith(AndroidJUnit4::class)
class FileListViewModelTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = ctx.applicationContext as Application
    private lateinit var vm: FileListViewModel
    private val made = ArrayList<File>()

    /** dayDir 는 시각 기반이라, 테스트가 원하는 날짜(yyyyMMdd) 폴더를 직접 만든다. */
    private fun makeFile(dayKey: String, name: String, bytes: Int): File {
        val target = File(File(Storage.currentRootPath(ctx), dayKey).apply { mkdirs() }, name)
        target.writeBytes(ByteArray(bytes))
        made.add(target)
        return target
    }

    @Before fun setup() {
        vm = FileListViewModel(app)
    }

    @After fun cleanup() {
        made.forEach { runCatching { vm.repo.delete(it) } }
    }

    @Test fun 표시모델은_헤더로_시작하고_날짜그룹과_행을_담는다() {
        val day = "20200102"   // 오늘 아님 → 기본 접힘
        makeFile(day, "a.m4a", 100)
        makeFile(day, "b.m4a", 200)

        val items = vm.buildItems(todayKey = "20200103", isPro = true)
        assertTrue("첫 아이템은 Header", items.first() is FileListItem.Header)
        val dayItem = items.filterIsInstance<FileListItem.Day>().firstOrNull { it.dayKey == day }
        assertTrue("날짜 그룹 존재", dayItem != null)
        assertEquals("그룹 카운트", 2, dayItem!!.count)
        // 오늘이 아니라 기본 접힘 → Row 아이템은 없어야
        assertTrue("접힘 기본값이면 Row 없음",
            items.none { it is FileListItem.Row && it.key.contains(day) })
        // 접혀 있어도 선택 대상(shownKeys)에는 들어감
        assertEquals("shownKeys 2개", 2, vm.shownKeys.count { it.contains(day) })
    }

    @Test fun 접힘_토글하면_행이_나타난다() {
        val day = "20200104"
        makeFile(day, "a.m4a", 100)
        vm.buildItems("20200105", true)   // 기본 접힘
        assertTrue(vm.collapsedDays.contains(day))

        vm.toggleCollapse(day)
        val items = vm.buildItems("20200105", true)
        assertTrue("펼치면 Row 등장", items.any { it is FileListItem.Row && it.key.contains(day) })
    }

    @Test fun 검색어로_필터된다() {
        val day = "20200106"
        makeFile(day, "meeting_notes.m4a", 100)
        makeFile(day, "random.m4a", 100)
        vm.buildItems("20200107", true)   // 먼저 접힘 기본값 초기화(비오늘 → 접힘)
        vm.toggleCollapse(day)            // 펼쳐서 Row 보이게

        vm.searchQuery = "meeting"
        val items = vm.buildItems("20200107", true)
        val rows = items.filterIsInstance<FileListItem.Row>().filter { it.key.contains(day) }
        assertEquals("검색 매칭 1개", 1, rows.size)
        assertTrue(rows.first().file.name.contains("meeting"))
    }

    @Test fun 삭제하면_모델에서_빠진다() {
        val day = "20200108"
        val f = makeFile(day, "gone.m4a", 100)
        vm.buildItems("20200109", true)   // 접힘 기본값 초기화
        vm.toggleCollapse(day)            // 펼치기
        assertTrue(vm.buildItems("20200109", true).any { it is FileListItem.Row && it.file == f })

        vm.repo.delete(f)
        assertFalse("삭제 후 모델에서 사라짐",
            vm.buildItems("20200109", true).any { it is FileListItem.Row && it.file == f })
    }

    @Test fun 선택은_사라진_파일을_자동_정리한다() {
        val day = "20200110"
        val f = makeFile(day, "sel.m4a", 100)
        val key = vm.repo.key(f)
        vm.buildItems("20200111", true)
        vm.selectedKeys.add(key)
        assertTrue(vm.selectedKeys.contains(key))

        vm.repo.delete(f)
        vm.buildItems("20200111", true)   // retainAll(shownKeys) 로 정리돼야
        assertFalse("삭제된 파일의 선택은 정리됨", vm.selectedKeys.contains(key))
    }
}
