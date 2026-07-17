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

/**
 * MeasurementLog 세션 수명주기 → CSV 기록 검증. 훅(구간·청크·VAD·드롭)이 세션당 한 행으로
 * 집계되는지, 꺼져 있으면 아무것도 안 남기는지 잠근다.
 */
@RunWith(AndroidJUnit4::class)
class MeasurementLogTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var wasEnabled = false

    @Before fun setup() {
        wasEnabled = Prefs.isMeasurementEnabled(ctx)
        MeasurementLog.clear(ctx)
    }

    @After fun teardown() {
        MeasurementLog.clear(ctx)
        Prefs.setMeasurementEnabled(ctx, wasEnabled)
    }

    @Test fun 켜진_세션은_한_행을_남기고_카운터를_집계한다() {
        Prefs.setMeasurementEnabled(ctx, true)
        MeasurementLog.onSessionStart(ctx, 80)
        MeasurementLog.onSegmentStart()
        MeasurementLog.onSegmentStart()
        repeat(100) { MeasurementLog.onChunkFed() }
        MeasurementLog.onChunkDropped()
        MeasurementLog.onVad(true); MeasurementLog.onVad(true); MeasurementLog.onVad(false)
        MeasurementLog.onSessionEnd(ctx, 78)

        val lines = MeasurementLog.csvFile(ctx).readLines()
        assertEquals("헤더 + 한 행", 2, lines.size)
        assertTrue("헤더 컬럼", lines[0].startsWith("start_ms,device,api"))
        val cols = lines[1].split(",")
        // device 에 콤마가 섞이면 컬럼이 밀리므로, MeasurementLog 가 콤마를 공백으로 치환하는지도 확인
        assertEquals("컬럼 수(14)", 14, cols.size)
        // batt_start=80, batt_end=78, drain=2, segments=2, fed=100, dropped=1, accept=2, reject=1
        assertEquals("80", cols[4])
        assertEquals("78", cols[5])
        assertEquals("2", cols[6])          // drain_pct
        assertEquals("2", cols[8])          // segments
        assertEquals("100", cols[9])        // chunks_fed
        assertEquals("1", cols[10])         // chunks_dropped
        assertEquals("2", cols[12])         // vad_accept
        assertEquals("1", cols[13])         // vad_reject
    }

    @Test fun 꺼진_세션은_아무것도_남기지_않는다() {
        Prefs.setMeasurementEnabled(ctx, false)
        MeasurementLog.onSessionStart(ctx, 90)
        MeasurementLog.onSegmentStart()
        MeasurementLog.onChunkDropped()
        MeasurementLog.onSessionEnd(ctx, 88)
        assertFalse("꺼져 있으면 CSV 없음", MeasurementLog.hasData(ctx))
    }

    @Test fun 두_세션은_두_행이_된다() {
        Prefs.setMeasurementEnabled(ctx, true)
        MeasurementLog.onSessionStart(ctx, 50); MeasurementLog.onSessionEnd(ctx, 49)
        MeasurementLog.onSessionStart(ctx, 49); MeasurementLog.onSessionEnd(ctx, 47)
        assertEquals("헤더 + 2행", 3, MeasurementLog.csvFile(ctx).readLines().size)
    }
}
