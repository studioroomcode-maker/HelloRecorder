package com.studioroomkr.hellorecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * AudioEditor.trim 계측 테스트.
 *
 * 핵심 회귀 방지: 잘라낸 파일의 첫 샘플 PTS 가 0 부터 시작하는지. 예전엔 원본 시각을
 * 그대로 써서, 뒷구간을 잘라내면 결과 파일이 startMs 부터 시작해 앞에 지연·무음이 생기고
 * 길이가 어긋났다.
 *
 * MediaCodec/MediaMuxer 가 필요해 기기/에뮬레이터에서만 돈다.
 */
@RunWith(AndroidJUnit4::class)
class AudioEditorTest {

    private fun cache(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.cacheDir, name).also { it.delete() }
    }

    /** durationMs 짜리 사인파 .m4a(AAC/mp4) 생성. */
    private fun writeSine(file: File, sampleRate: Int, durationMs: Int) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val total = sampleRate.toLong() * durationMs / 1000
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
                        val room = buf.remaining() / 2
                        val n = minOf(room.toLong(), total - fed).toInt()
                        val pts = fed * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            for (k in 0 until n) {
                                val t = (fed + k).toDouble() / sampleRate
                                sb.put((sin(2 * PI * 440 * t) * 20000).toInt().toShort())
                            }
                            codec.queueInputBuffer(i, 0, n * 2, pts, 0)
                            fed += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                } else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) muxer.writeSampleData(track, codec.getOutputBuffer(o)!!, info)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    codec.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            codec.stop(); codec.release(); muxer.stop(); muxer.release()
        }
    }

    /** 첫 샘플 PTS 와 마지막 샘플 PTS(us). */
    private fun ptsRange(file: File): Pair<Long, Long> {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        ex.selectTrack(0)
        var first = -1L
        var last = 0L
        while (true) {
            val sz = ex.readSampleData(java.nio.ByteBuffer.allocate(64 * 1024), 0)
            if (sz < 0) break
            val t = ex.sampleTime
            if (first < 0) first = t
            last = t
            ex.advance()
        }
        ex.release()
        return first to last
    }

    @Test
    fun 뒷구간을_잘라내면_결과_PTS가_0부터_시작한다() {
        val src = cache("trim-src.m4a")
        writeSine(src, 44_100, 4_000)          // 4초
        val dst = cache("trim-dst.m4a")

        // 2000~3500ms 구간만 남긴다. 원본 시각을 그대로 두면 첫 PTS 가 ~2_000_000us 가 된다.
        val ok = AudioEditor.trim(src, dst, 2_000, 3_500)
        assertTrue("trim 실패", ok)
        assertTrue("결과 파일 없음", dst.length() > 0)

        val (first, last) = ptsRange(dst)
        // 첫 샘플이 0 근처에서 시작해야 한다(싱크 프레임 정렬로 한 프레임 ~23ms 여유).
        assertTrue("첫 PTS 가 0 부터 시작하지 않음: ${first}us", first in 0..30_000L)
        // 길이는 대략 1.5초(±0.3초). PTS 리베이스가 없으면 first 가 커서 여기서도 티가 난다.
        val durMs = (last - first) / 1000
        assertTrue("잘라낸 길이 이상: ${durMs}ms", durMs in 1_100..1_900)
    }

    @Test
    fun 시작이_0인_구간은_그대로_0부터() {
        val src = cache("trim-src2.m4a")
        writeSine(src, 44_100, 3_000)
        val dst = cache("trim-dst2.m4a")
        assertTrue(AudioEditor.trim(src, dst, 0, 1_500))
        val (first, _) = ptsRange(dst)
        assertTrue("첫 PTS: ${first}us", first in 0..30_000L)
    }
}
