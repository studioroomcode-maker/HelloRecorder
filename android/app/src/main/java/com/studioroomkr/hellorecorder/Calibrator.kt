package com.studioroomkr.hellorecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * 주변 소음 자동 보정.
 *
 * 짧게(약 2초) 주변 소리를 들어 노이즈 플로어를 추정하고, 그 위로 여유를 둔 무음 임계값을
 * 추천한다. 저장 오디오는 만들지 않으며(측정만 함), 추천 임계값은 메인 스레드 콜백으로 돌려준다.
 * 추천값 계산 규칙은 RecordingLogic.recommendThreshold 에 위임(단위 테스트 대상).
 */
object Calibrator {
    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val MEASURE_MS = 2_000L   // 측정 시간
    private const val MARGIN = 1.6          // 주변 소음(천장) 위로 둘 여유 배수

    fun hasMicPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 백그라운드에서 측정 후 추천 임계값(Double)을 메인 스레드로 콜백. 실패 시 null. */
    fun calibrate(ctx: Context, onResult: (Double?) -> Unit) {
        Thread {
            val result = try { measure() } catch (_: Throwable) { null }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    @Suppress("MissingPermission")
    private fun measure(): Double? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return null
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 4)   // 약 0.25초 청크
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val pcm = ShortArray(bufSize / 2)
        val rmsList = ArrayList<Double>()
        try {
            rec.startRecording()
            // 마이크 워밍업 첫 청크는 버린다(초기 팝/0 구간 영향 제거)
            rec.read(pcm, 0, pcm.size)
            val end = System.currentTimeMillis() + MEASURE_MS
            while (System.currentTimeMillis() < end) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n > 0) rmsList.add(rms(pcm, n))
            }
        } catch (_: Exception) {
            return null
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return RecordingLogic.recommendThreshold(
            rmsList, MARGIN, Prefs.MIN_THRESHOLD, Prefs.MAX_THRESHOLD
        )
    }

    private fun rms(buf: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / len)
    }
}
