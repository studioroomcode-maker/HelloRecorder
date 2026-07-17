package com.studioroomkr.hellorecorder

/**
 * 녹음 동작의 **순수 결정 로직** (안드로이드 비의존).
 *
 * 스케줄 게이팅·짧은 녹음 경계·보관 만료·빈 파일 정리 판정을 한곳에 모아,
 * Context/파일시스템 없이 단위 테스트할 수 있게 한다. Prefs·Storage·CleanupWorker·
 * AudioEngine 이 이 함수들에 위임하므로, 동작 규칙의 단일 출처가 된다.
 */
object RecordingLogic {

    /** 빈 파일 정리 안전장치: 최근 이 시간(ms) 내 수정된 파일은 녹음 중일 수 있어 건드리지 않는다. */
    const val EMPTY_GUARD_MS = 120_000L

    /**
     * 분 단위 녹음 창 [startMin, endMin) 안에 minute 가 드는지.
     *  - startMin == endMin → 24시간(항상 허용)
     *  - startMin <  endMin → [startMin, endMin)
     *  - startMin >  endMin → 자정을 넘는 야간 구간
     */
    fun inWindow(startMin: Int, endMin: Int, minute: Int): Boolean {
        if (startMin == endMin) return true
        return if (startMin < endMin) minute in startMin until endMin
        else minute >= startMin || minute < endMin
    }

    /**
     * '짧은 녹음' 경계(ms). 화면 길이 표시가 초 내림(floor)이라, 'N초 이하로 보이는' 구간을
     * 모두 버리려면 (N+1)초 이상부터 저장/보존해야 한다. 지연 인코딩 promote 와 기존 짧은
     * 녹음 정리가 같은 기준을 쓰도록 단일화.
     */
    fun minKeepThresholdMs(minKeepSec: Int): Long = (minKeepSec + 1) * 1000L

    /**
     * 보관 기간 경과로 자동 삭제 대상인지. 보호(보관) 파일은 항상 false.
     */
    fun isExpired(lastModifiedMs: Long, nowMs: Long, retentionHours: Int, isProtected: Boolean): Boolean {
        if (isProtected) return false
        val cutoff = nowMs - retentionHours * 3600_000L
        return lastModifiedMs < cutoff
    }

    /**
     * 빈(0바이트)·버려진 파일 정리 대상인지. 녹음 중인 파일을 지우지 않도록 최근
     * EMPTY_GUARD_MS 내 수정된 파일은 제외한다.
     */
    fun isEmptyAbandoned(lengthBytes: Long, lastModifiedMs: Long, nowMs: Long): Boolean =
        lengthBytes == 0L && nowMs - lastModifiedMs > EMPTY_GUARD_MS

    /**
     * 측정한 주변 소음 RMS 표본들로부터 추천 무음 임계값을 계산.
     * 잡음 천장(90백분위 — 순간적인 한 번의 소리에 휘둘리지 않게)에 margin 여유를 곱하고
     * [min, max]로 제한한다. 표본이 2개 미만이면 신뢰할 수 없어 null.
     */
    fun recommendThreshold(samples: List<Double>, margin: Double, min: Double, max: Double): Double? {
        if (samples.size < 2) return null
        val sorted = samples.sorted()
        val idx = (sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)
        val ceiling = sorted[idx]
        return (ceiling * margin).coerceIn(min, max)
    }

    /**
     * '근접 우선' 다운워드 익스팬더 게인. 신호 포락선 env 가 threshold 이상이면 그대로(1.0),
     * 그 아래(멀리 있거나 약한 소리)면 비례해 줄이되 floor 밑으로는 내리지 않는다.
     * AGC(약한 소리를 키움)의 반대 — 가까운 내 목소리가 멀리 있는 소리에 밀리지 않게 한다.
     */
    fun proximityGain(env: Double, threshold: Double, floor: Double): Double {
        if (threshold <= 0.0) return 1.0
        if (env >= threshold) return 1.0
        return (env / threshold).coerceIn(floor, 1.0)
    }

    /**
     * '근접 우선' 적응 임계값. 최근 '가까운 소리'의 포락선 피크(ref)를 따라가 기기별
     * 마이크 감도 차이(같은 거리라도 절대 레벨이 다름)를 흡수한다. 조용한 환경에서
     * 임계가 0 으로 무너져 감쇠가 전혀 안 걸리는 것을 막기 위해 하한을 둔다.
     */
    fun proximityThreshold(ref: Double, ratio: Double, minThreshold: Double): Double =
        maxOf(minThreshold, ref * ratio)

    /**
     * 자동 전사 대상인지. 이미 전사물이 있으면 제외하고, 최근 guardMs 안에 수정된 파일은
     * 아직 녹음(추가 기록) 중일 수 있어 제외한다 — 다음 주기에서 자연히 잡힌다.
     */
    fun needsTranscript(hasTranscript: Boolean, lastModifiedMs: Long, nowMs: Long, guardMs: Long): Boolean =
        !hasTranscript && nowMs - lastModifiedMs >= guardMs

    /**
     * 감지 임계 히스테리시스: 이미 녹음(캡처) 중이면 임계를 낮춰, 크게 시작한 문장의
     * 뒷부분이 작아져도 같은 구간으로 이어 잡는다(문장 중간 끊김 방지).
     * 시작 임계는 그대로라 대기 상태의 절전(무음이면 무거운 검출 생략)은 변하지 않는다.
     */
    fun continuationThreshold(base: Double, capturing: Boolean, ratio: Double): Double =
        if (capturing) base * ratio else base
}
