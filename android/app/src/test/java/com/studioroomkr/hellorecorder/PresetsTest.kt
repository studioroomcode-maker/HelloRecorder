package com.studioroomkr.hellorecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프리셋 정의의 정적 불변식 검증(Context 불필요). 값이 유효 범위를 벗어나면 coerce 로
 * 조용히 바뀌어 currentId 가 영영 그 프리셋을 못 알아보게 되므로, 여기서 미리 막는다.
 */
class PresetsTest {

    @Test fun id가_고유하다() {
        val ids = Presets.ALL.map { it.id }
        assertEquals("프리셋 id 중복", ids.size, ids.distinct().size)
    }

    @Test fun 리포트가_요구한_네_프리셋이_있다() {
        val ids = Presets.ALL.map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("meeting", "lecture", "memo", "noise")))
    }

    @Test fun byId_는_있는것만_찾는다() {
        assertNotNull(Presets.byId("meeting"))
        assertNull(Presets.byId("nope"))
    }

    @Test fun 모든_값이_Prefs_유효범위_안이다() {
        for (p in Presets.ALL) {
            assertTrue("${p.id} threshold",
                p.threshold in Prefs.MIN_THRESHOLD..Prefs.MAX_THRESHOLD)
            assertTrue("${p.id} mergeGap",
                p.mergeGapSec in Prefs.MIN_MERGE_GAP_SEC..Prefs.MAX_MERGE_GAP_SEC)
            assertTrue("${p.id} minKeep",
                p.minKeepSec in Prefs.MIN_MIN_KEEP_SEC..Prefs.MAX_MIN_KEEP_SEC)
        }
    }

    @Test fun 소음감시는_목소리필터를_끈다() {
        // 소음 감시는 목소리가 아닌 소리도 잡아야 하므로 voiceMode 가 꺼져 있어야 한다.
        val noise = Presets.byId("noise")!!
        assertTrue(!noise.voiceMode && !noise.minKeepEnabled)
    }
}
