package com.studioroomkr.hellorecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Presets.apply → currentId 왕복 검증. 프리셋을 적용하면 그 설정이 실제 Prefs 에 반영되고,
 * currentId 가 다시 그 프리셋을 알아봐야 한다(설정 화면의 '●' 표시 근거).
 */
@RunWith(AndroidJUnit4::class)
class PresetsApplyTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    // 테스트가 기기의 실제 설정을 건드리므로 원래 값을 백업했다가 되돌린다.
    private var backup: Presets.Preset? = null

    @Before fun save() {
        backup = Presets.Preset(
            "backup", "", "",
            Prefs.getThreshold(ctx), Prefs.isVoiceModeEnabled(ctx), Prefs.isSileroEnabled(ctx),
            Prefs.getMergeGapSec(ctx), Prefs.isMinKeepEnabled(ctx), Prefs.getMinKeepSec(ctx),
            Prefs.isVoiceEmphasisEnabled(ctx)
        )
    }

    @After fun restore() { backup?.let { Presets.apply(ctx, it) } }

    @Test fun 각_프리셋_적용후_currentId가_그_프리셋을_가리킨다() {
        for (p in Presets.ALL) {
            Presets.apply(ctx, p)
            assertEquals("‘${p.id}’ 적용 후 currentId 불일치", p.id, Presets.currentId(ctx))
        }
    }

    @Test fun 적용값이_실제_Prefs에_반영된다() {
        val noise = Presets.byId("noise")!!
        Presets.apply(ctx, noise)
        assertEquals(noise.threshold, Prefs.getThreshold(ctx), 0.001)
        assertEquals(noise.voiceMode, Prefs.isVoiceModeEnabled(ctx))
        assertEquals(noise.mergeGapSec, Prefs.getMergeGapSec(ctx))
        assertEquals(noise.minKeepEnabled, Prefs.isMinKeepEnabled(ctx))
    }
}
