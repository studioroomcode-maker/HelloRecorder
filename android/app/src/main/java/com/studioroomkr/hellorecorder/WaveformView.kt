package com.studioroomkr.hellorecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min

/**
 * 녹음 파일의 진폭을 막대 파형으로 그리고, 탭/드래그로 위치 탐색하는 뷰.
 *  - load(file): 백그라운드에서 AAC 디코드 → 진폭 배열 추출 후 그림
 *  - setProgress(0..1): 재생 진행 표시(재생된 부분은 키 컬러)
 *  - onSeek: 사용자가 파형을 탭/드래그하면 0..1 위치 콜백
 */
class WaveformView(context: Context) : View(context) {

    private var amps: FloatArray = FloatArray(0)
    private var progress: Float = 0f
    var onSeek: ((Float) -> Unit)? = null

    private val playedPaint = Paint().apply { color = Theme.ACCENT; isAntiAlias = true }
    private val unplayedPaint = Paint().apply { color = Theme.BORDER; isAntiAlias = true }

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    fun load(file: File) {
        amps = FloatArray(0)
        invalidate()
        thread {
            val a = try {
                extract(file.absolutePath, BARS)
            } catch (_: Exception) {
                FloatArray(0)
            }
            post { amps = a; invalidate() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val n = amps.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val barW = w / n
        val gap = barW * 0.35f
        val radius = barW * 0.3f
        val playedTo = (progress * n).toInt()
        for (i in 0 until n) {
            val amp = amps[i].coerceIn(0.04f, 1f)
            val bh = amp * h
            val top = (h - bh) / 2f
            val left = i * barW + gap / 2f
            val right = (i + 1) * barW - gap / 2f
            canvas.drawRoundRect(
                left, top, right, top + bh, radius, radius,
                if (i <= playedTo) playedPaint else unplayedPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (width > 0) {
                    val frac = (event.x / width).coerceIn(0f, 1f)
                    progress = frac
                    invalidate()
                    onSeek?.invoke(frac)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val BARS = 160
        private const val SAMPLES_PER_PEAK = 1024

        /** 오디오 파일을 디코드해 [bars] 개의 정규화된 진폭(0..1)으로 반환. */
        fun extract(path: String, bars: Int): FloatArray {
            val extractor = MediaExtractor()
            extractor.setDataSource(path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) { extractor.release(); return FloatArray(0) }
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val rawPeaks = ArrayList<Float>()
            var windowMax = 0f
            var windowCount = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sz = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        val shortCount = info.size / 2
                        val shorts = ShortArray(shortCount)
                        outBuf.asShortBuffer().get(shorts, 0, min(shortCount, shorts.size))
                        var j = 0
                        while (j < shortCount) {
                            val v = abs(shorts[j].toInt()) / 32768f
                            if (v > windowMax) windowMax = v
                            windowCount++
                            if (windowCount >= SAMPLES_PER_PEAK) {
                                rawPeaks.add(windowMax); windowMax = 0f; windowCount = 0
                            }
                            j++
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (windowCount > 0) rawPeaks.add(windowMax)
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()

            if (rawPeaks.isEmpty()) return FloatArray(0)

            val out = FloatArray(bars)
            val ratio = rawPeaks.size.toFloat() / bars
            var maxAmp = 0f
            for (b in 0 until bars) {
                val from = (b * ratio).toInt()
                val to = ((b + 1) * ratio).toInt().coerceAtMost(rawPeaks.size)
                var m = 0f
                var k = from
                while (k < to) { if (rawPeaks[k] > m) m = rawPeaks[k]; k++ }
                out[b] = m
                if (m > maxAmp) maxAmp = m
            }
            if (maxAmp > 0f) for (b in out.indices) out[b] = out[b] / maxAmp
            return out
        }
    }
}
