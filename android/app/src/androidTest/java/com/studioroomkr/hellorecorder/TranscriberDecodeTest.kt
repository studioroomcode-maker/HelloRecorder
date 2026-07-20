package com.studioroomkr.hellorecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Transcriber 의 디코드 경로(decodeStreaming) 계측 테스트.
 *
 * 전사기 자체는 STT 모델(~76MB 별도 다운로드)이 있어야 만들어지므로, 모델 없이도 돌릴 수 있는
 * 디코드 단계만 검증한다. 여기가 예전에 파일 전체를 메모리에 올리던 자리라 회귀가 제일 아프다.
 *
 * MediaCodec/MediaMuxer 가 필요해 JVM 단위 테스트로는 못 하고 기기/에뮬레이터에서만 돈다.
 */
@RunWith(AndroidJUnit4::class)
class TranscriberDecodeTest {

    private fun tempFile(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.cacheDir, name).also { it.delete() }
    }

    /** 디코드해서 (총 프레임 수, 최대 진폭, 샘플레이트, sink 호출 횟수, 최대 청크 크기)를 얻는다. */
    private data class Decoded(
        val frames: Long, val peak: Float, val rate: Int, val calls: Int, val maxChunk: Int
    )

    private fun decode(file: File): Decoded {
        var frames = 0L
        var peak = 0f
        var rate = 0
        var calls = 0
        var maxChunk = 0
        Transcriber.decodeStreaming(file) { buf, count, sampleRate ->
            assertTrue("sink 는 유효 샘플만 넘겨야 한다", count > 0)
            assertTrue("count 가 버퍼를 넘었다", count <= buf.size)
            frames += count
            rate = sampleRate
            calls++
            if (count > maxChunk) maxChunk = count
            for (i in 0 until count) {
                val a = abs(buf[i])
                if (a > peak) peak = a
            }
        }
        return Decoded(frames, peak, rate, calls, maxChunk)
    }

    /**
     * 사인파 .m4a 생성. AAC 인코더 → MediaMuxer 로 앱이 만드는 파일과 같은 형식(AAC-LC/mp4).
     * 모든 채널에 같은 신호를 넣으므로, 다운믹스가 맞으면 채널 수와 무관하게 진폭이 같아야 한다.
     */
    private fun writeSineM4a(
        file: File, sampleRate: Int, channels: Int, durationMs: Int, freq: Double, amp: Short
    ): Long {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val totalFrames = sampleRate.toLong() * durationMs / 1000
        var fed = 0L
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        var inEos = false
        var outEos = false

        try {
            while (!outEos) {
                if (!inEos) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val room = buf.remaining() / 2 / channels
                        val n = minOf(room.toLong(), totalFrames - fed).toInt()
                        val pts = fed * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            for (k in 0 until n) {
                                val t = (fed + k).toDouble() / sampleRate
                                val s = (sin(2 * PI * freq * t) * amp).toInt().toShort()
                                for (c in 0 until channels) sb.put(s)
                            }
                            codec.queueInputBuffer(i, 0, n * 2 * channels, pts, 0)
                            fed += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                } else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        muxer.writeSampleData(track, codec.getOutputBuffer(o)!!, info)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    codec.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            codec.stop(); codec.release()
            muxer.stop(); muxer.release()
        }
        return totalFrames
    }

    @Test
    fun 모노_16k_를_디코드하면_길이와_신호가_보존된다() {
        val file = tempFile("sine-mono.m4a")
        val expected = writeSineM4a(file, 16_000, 1, 3_000, 440.0, 20_000)
        val d = decode(file)

        assertEquals("샘플레이트", 16_000, d.rate)
        // AAC 는 프라이밍/패딩으로 앞뒤가 조금 늘어난다. 길이가 통째로 어긋나는 것만 잡는다.
        assertTrue("디코드 길이 ${d.frames}, 기대 $expected 근처", d.frames >= expected - 2048)
        assertTrue("디코드 길이 ${d.frames}, 기대 $expected 근처", d.frames <= expected + 4096)
        // 20000/32768 ≈ 0.61. 스케일(1/32768)이 틀리면 여기서 걸린다.
        assertTrue("진폭 ${d.peak}", d.peak in 0.45f..0.75f)
        assertTrue("스트리밍이면 sink 가 여러 번 불려야 한다 (${d.calls}회)", d.calls > 10)
    }

    /**
     * 앱은 모노로만 녹음하므로(AudioEngine.CHANNEL_IN) 실파일엔 안 나오는 방어 경로지만,
     * 틀려도 조용히 틀리기 때문에 확인해 둔다. 44.1kHz 인 이유는 에뮬레이터 AAC 인코더가
     * 16kHz 스테레오 픽스처를 못 만들어서다(실기기 제약이 아니라 픽스처 쪽 사정).
     */
    @Test
    fun 스테레오는_모노로_다운믹스된다() {
        val file = tempFile("sine-stereo.m4a")
        val expected = writeSineM4a(file, 44_100, 2, 2_000, 440.0, 20_000)
        val d = decode(file)

        assertEquals("샘플레이트", 44_100, d.rate)
        // 프레임 수는 채널 수로 나뉜 값이어야 한다 — 안 나누면 2배로 나온다.
        assertTrue("다운믹스 후 프레임 ${d.frames}, 기대 $expected 근처", d.frames >= expected - 2048)
        assertTrue("다운믹스 후 프레임 ${d.frames}, 기대 $expected 근처", d.frames <= expected + 4096)
        // 두 채널이 같은 신호 → 평균도 같은 진폭. 더하기만 하면 여기서 클리핑으로 걸린다.
        assertTrue("다운믹스 진폭 ${d.peak}", d.peak in 0.45f..0.75f)
    }

    /**
     * 이 테스트가 이 파일의 존재 이유다. 예전 구현은 파일 전체를 FloatArray 로 올려서
     * 긴 녹음이면 OOM 이 났다. 길이를 4배로 늘려도 힙 사용이 그만큼 늘지 않아야 한다.
     */
    @Test
    fun 파일이_길어져도_힙이_길이에_비례해_늘지_않는다() {
        fun peakHeapFor(durationMs: Int): Long {
            val file = tempFile("sine-$durationMs.m4a")
            writeSineM4a(file, 16_000, 1, durationMs, 440.0, 20_000)
            val rt = Runtime.getRuntime()
            System.gc(); Thread.sleep(150)
            val before = rt.totalMemory() - rt.freeMemory()
            var peak = 0L
            Transcriber.decodeStreaming(file) { _, _, _ ->
                val used = rt.totalMemory() - rt.freeMemory() - before
                if (used > peak) peak = used
            }
            file.delete()
            return peak
        }

        val short = peakHeapFor(5_000)
        val long = peakHeapFor(20_000)   // 4배 길이

        // 전체 적재였다면 20초 = 16k*20*4B = 1.28MB 의 FloatArray 하나만 해도 선형으로 늘고,
        // 예전처럼 중간 사본까지 겹치면 몇 배가 됐다. 스트리밍이면 길이와 무관하게 평평하다.
        val slack = 512 * 1024L
        assertTrue(
            "길이 4배에 힙이 따라 늘었다 — 스트리밍이 깨졌다 (5초 ${short}B, 20초 ${long}B)",
            long <= short + slack
        )
    }
}
