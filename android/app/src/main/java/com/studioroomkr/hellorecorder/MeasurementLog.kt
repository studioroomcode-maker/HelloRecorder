package com.studioroomkr.hellorecorder

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 측정 로거 — 녹음 세션마다 배터리 감소·구간 수·드롭(누락) 청크·VAD 수락/거부를 기기 안에만
 * 기록하고 CSV 로 내보낸다. 리포트가 요구한 "기기군별 배터리·누락률·VAD 실측"을 실기기에서
 * 수집할 수 있게 하는 계측 도구다(값 자체는 실제 기기에서 돌려야 나온다).
 *
 *  - 기본 꺼짐(설정에서 켬). 꺼져 있으면 훅은 active=false 로 즉시 빠져 부하가 없다.
 *  - 순수 로컬: measurement/sessions.csv 에만 쓰고, 사용자가 명시적으로 공유할 때만 나간다.
 *    개인정보(녹음 내용·위치)는 담지 않고 수치·기기모델·OS 만 담는다.
 */
object MeasurementLog {

    @Volatile private var active = false
    private var startMs = 0L
    private var startBatt = -1

    private val segments = AtomicInteger()   // 캡처 시작(구간) 수
    private val chunksFed = AtomicInteger()  // 인코더에 넣은 청크 수
    private val chunksDropped = AtomicInteger() // 넣지 못하고 버린 청크 수(누락)
    private val vadAccept = AtomicInteger()
    private val vadReject = AtomicInteger()

    fun isEnabled(ctx: Context): Boolean = Prefs.isMeasurementEnabled(ctx)

    /** 세션 시작(서비스 start). battery 는 현재 잔량 %(모르면 -1). */
    fun onSessionStart(ctx: Context, battery: Int) {
        if (!Prefs.isMeasurementEnabled(ctx)) { active = false; return }
        startMs = System.currentTimeMillis()
        startBatt = battery
        segments.set(0); chunksFed.set(0); chunksDropped.set(0)
        vadAccept.set(0); vadReject.set(0)
        active = true
    }

    fun onSegmentStart() { if (active) segments.incrementAndGet() }
    fun onChunkFed() { if (active) chunksFed.incrementAndGet() }
    fun onChunkDropped() { if (active) chunksDropped.incrementAndGet() }
    fun onVad(accepted: Boolean) { if (active) (if (accepted) vadAccept else vadReject).incrementAndGet() }

    /** 세션 종료(서비스 stop). CSV 한 줄을 추가한다. */
    fun onSessionEnd(ctx: Context, battery: Int) {
        if (!active) return
        active = false
        val durSec = (System.currentTimeMillis() - startMs) / 1000
        val drain = if (startBatt in 0..100 && battery in 0..100) startBatt - battery else -1
        val drainPerHr = if (drain >= 0 && durSec > 0) "%.2f".format(drain * 3600.0 / durSec) else ""
        val fed = chunksFed.get()
        val dropped = chunksDropped.get()
        val missPct = if (fed > 0) "%.3f".format(dropped * 100.0 / fed) else ""
        val row = listOf(
            startMs.toString(),
            "${Build.MANUFACTURER} ${Build.MODEL}".replace(",", " "),
            Build.VERSION.SDK_INT.toString(),
            durSec.toString(),
            startBatt.toString(), battery.toString(), drain.toString(), drainPerHr,
            segments.get().toString(), fed.toString(), dropped.toString(), missPct,
            vadAccept.get().toString(), vadReject.get().toString(),
        ).joinToString(",")
        append(ctx, row)
    }

    fun csvFile(ctx: Context): File =
        File(ctx.filesDir, "measurement").apply { mkdirs() }.let { File(it, "sessions.csv") }

    fun hasData(ctx: Context): Boolean = csvFile(ctx).let { it.isFile && it.length() > 0 }

    fun clear(ctx: Context) { try { csvFile(ctx).delete() } catch (_: Exception) {} }

    private const val HEADER =
        "start_ms,device,api,dur_sec,batt_start,batt_end,drain_pct,drain_pct_per_hr," +
            "segments,chunks_fed,chunks_dropped,miss_pct,vad_accept,vad_reject"

    private fun append(ctx: Context, row: String) {
        try {
            val f = csvFile(ctx)
            val header = if (f.exists()) "" else HEADER + "\n"
            f.appendText(header + row + "\n")
        } catch (_: Exception) { /* 측정 실패는 녹음에 영향 주지 않게 무시 */ }
    }
}
