package com.studioroomkr.hellorecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 핵심 녹음 결정 로직(RecordingLogic) 단위 테스트.
 * 게이팅(시간 창)·짧은 녹음 경계·보관 만료·빈 파일 정리 판정의 회귀를 막는다.
 */
class RecordingLogicTest {

    // ── inWindow: 일반 구간 [start, end) ──
    @Test fun inWindow_normal_range_is_half_open() {
        // 09:00~18:00 (540~1080)
        assertTrue(RecordingLogic.inWindow(540, 1080, 540))   // 시작 포함
        assertTrue(RecordingLogic.inWindow(540, 1080, 600))   // 내부
        assertFalse(RecordingLogic.inWindow(540, 1080, 1080)) // 끝 제외
        assertFalse(RecordingLogic.inWindow(540, 1080, 539))  // 시작 직전
        assertFalse(RecordingLogic.inWindow(540, 1080, 1200)) // 이후
    }

    // ── inWindow: start==end → 24시간 ──
    @Test fun inWindow_equal_bounds_is_all_day() {
        assertTrue(RecordingLogic.inWindow(0, 0, 0))
        assertTrue(RecordingLogic.inWindow(0, 0, 720))
        assertTrue(RecordingLogic.inWindow(0, 0, 1439))
        assertTrue(RecordingLogic.inWindow(600, 600, 30))   // 09:60 기준이 아니라 임의 시각도 허용
    }

    // ── inWindow: start>end → 자정 넘김 (예: 22:00~06:00) ──
    @Test fun inWindow_overnight_wraps_midnight() {
        val s = 22 * 60   // 1320
        val e = 6 * 60    // 360
        assertTrue(RecordingLogic.inWindow(s, e, 22 * 60))    // 시작 포함
        assertTrue(RecordingLogic.inWindow(s, e, 23 * 60))    // 자정 전
        assertTrue(RecordingLogic.inWindow(s, e, 0))          // 자정
        assertTrue(RecordingLogic.inWindow(s, e, 5 * 60))     // 새벽
        assertFalse(RecordingLogic.inWindow(s, e, 6 * 60))    // 끝 제외
        assertFalse(RecordingLogic.inWindow(s, e, 12 * 60))   // 한낮(구간 밖)
        assertFalse(RecordingLogic.inWindow(s, e, 21 * 60 + 59)) // 시작 직전
    }

    // ── minKeepThresholdMs: (sec+1)*1000 ──
    @Test fun minKeep_threshold_is_seconds_plus_one() {
        assertEquals(1_000L, RecordingLogic.minKeepThresholdMs(0))
        assertEquals(6_000L, RecordingLogic.minKeepThresholdMs(5))   // 기본값
        assertEquals(61_000L, RecordingLogic.minKeepThresholdMs(60))
    }

    // ── isExpired: 보관 기간 경과 + 보호 ──
    @Test fun isExpired_protected_is_never_expired() {
        val now = 1_000_000_000_000L
        val old = now - 100L * 3600_000L   // 100시간 전
        assertFalse(RecordingLogic.isExpired(old, now, 48, isProtected = true))
    }

    @Test fun isExpired_unprotected_respects_cutoff() {
        val now = 1_000_000_000_000L
        val retention = 48
        val cutoff = now - retention * 3600_000L
        assertTrue(RecordingLogic.isExpired(cutoff - 1, now, retention, false))   // 경계보다 오래됨 → 만료
        assertFalse(RecordingLogic.isExpired(cutoff, now, retention, false))      // 경계 정각 → 아직 (strictly <)
        assertFalse(RecordingLogic.isExpired(cutoff + 1, now, retention, false))  // 최근 → 유지
        assertFalse(RecordingLogic.isExpired(now, now, retention, false))         // 방금 → 유지
    }

    // ── isEmptyAbandoned: 0바이트 + 2분 가드 ──
    @Test fun isEmptyAbandoned_deletes_old_zero_byte_only() {
        val now = 1_000_000_000_000L
        val guard = RecordingLogic.EMPTY_GUARD_MS
        // 0바이트 & 가드보다 오래됨 → 정리 대상
        assertTrue(RecordingLogic.isEmptyAbandoned(0L, now - guard - 1, now))
        // 0바이트지만 최근(녹음 중일 수 있음) → 보호
        assertFalse(RecordingLogic.isEmptyAbandoned(0L, now - 1_000L, now))
        // 0바이트 & 경계 정각 → 보호 (strictly >)
        assertFalse(RecordingLogic.isEmptyAbandoned(0L, now - guard, now))
        // 내용 있는 파일 → 오래돼도 정리 안 함
        assertFalse(RecordingLogic.isEmptyAbandoned(1L, now - guard - 10_000L, now))
    }

    // ── recommendThreshold: 90백분위 × margin, [min,max] 제한 ──
    @Test fun recommend_uses_p90_times_margin() {
        // 10개 표본의 90백분위(index 9) = 100.0 → 100*1.6 = 160, [50,3000] 내
        val samples = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)
        assertEquals(160.0, RecordingLogic.recommendThreshold(samples, 1.6, 50.0, 3000.0)!!, 0.001)
    }

    @Test fun recommend_clamps_to_max() {
        val samples = listOf(4000.0, 4000.0, 4000.0)   // p90*margin 이 상한 초과
        assertEquals(3000.0, RecordingLogic.recommendThreshold(samples, 1.6, 50.0, 3000.0)!!, 0.001)
    }

    @Test fun recommend_clamps_to_min() {
        val samples = listOf(5.0, 6.0, 7.0)   // 매우 조용 → 하한 미만 → 50 으로 끌어올림
        assertEquals(50.0, RecordingLogic.recommendThreshold(samples, 1.6, 50.0, 3000.0)!!, 0.001)
    }

    @Test fun recommend_needs_at_least_two_samples() {
        assertEquals(null, RecordingLogic.recommendThreshold(listOf(100.0), 1.6, 50.0, 3000.0))
        assertEquals(null, RecordingLogic.recommendThreshold(emptyList(), 1.6, 50.0, 3000.0))
    }

    // ── proximityGain: 근접 우선 다운워드 익스팬더 ──
    @Test fun proximity_keeps_near_attenuates_far() {
        val th = 2000.0
        val floor = 0.35
        assertEquals(1.0, RecordingLogic.proximityGain(3000.0, th, floor), 1e-9)  // 가까움 → 그대로
        assertEquals(1.0, RecordingLogic.proximityGain(2000.0, th, floor), 1e-9)  // 경계 → 그대로
        assertEquals(0.5, RecordingLogic.proximityGain(1000.0, th, floor), 1e-9)  // 중간 → 비례 감쇠
        assertEquals(floor, RecordingLogic.proximityGain(100.0, th, floor), 1e-9) // 아주 멀리 → floor 까지만
        assertEquals(1.0, RecordingLogic.proximityGain(500.0, 0.0, floor), 1e-9)  // threshold 0 가드
    }

    // ── proximityThreshold: 적응 임계(최근 가까운 소리 피크 추종 + 하한) ──
    @Test fun proximity_threshold_follows_recent_peak() {
        // 큰 목소리(피크 10000) 기준 → 임계는 그 35%
        assertEquals(3500.0, RecordingLogic.proximityThreshold(10000.0, 0.35, 1000.0), 1e-9)
        // 조용한 환경(피크가 낮음) → 하한으로 고정해 과감쇠 방지
        assertEquals(1000.0, RecordingLogic.proximityThreshold(500.0, 0.35, 1000.0), 1e-9)
        assertEquals(1000.0, RecordingLogic.proximityThreshold(0.0, 0.35, 1000.0), 1e-9)
    }
}
