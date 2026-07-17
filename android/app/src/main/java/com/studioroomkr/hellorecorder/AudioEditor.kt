package com.studioroomkr.hellorecorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * m4a/AAC 파일을 재인코딩 없이 구간만 잘라 새 파일로 저장.
 * MediaExtractor 로 읽고 MediaMuxer 로 다시 쓰므로 음질 손실이 없다.
 *
 * 정밀한 파형 편집(페이드, 합치기 등)은 FFmpeg 영역이라 다루지 않는다.
 * 여기서는 가장 흔한 "앞뒤 잘라 원하는 구간만 남기기"를 지원한다.
 */
object AudioEditor {

    /**
     * @param src       원본 파일
     * @param dst       저장할 새 파일
     * @param startMs   잘라낼 구간 시작(ms)
     * @param endMs     잘라낼 구간 끝(ms)
     * @return 성공 여부
     */
    fun trim(src: File, dst: File, startMs: Long, endMs: Long): Boolean {
        if (startMs >= endMs) return false
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(src.absolutePath)

            // 오디오 트랙 찾기
            var audioTrack = -1
            var format: android.media.MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) return false

            extractor.selectTrack(audioTrack)
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dstTrack = muxer.addTrack(format)
            muxer.start()

            // 시작 지점으로 이동
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val maxChunk = 256 * 1024
            val buffer = ByteBuffer.allocate(maxChunk)
            val info = MediaCodec.BufferInfo()
            val endUs = endMs * 1000

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break

                info.offset = 0
                info.size = size
                info.presentationTimeUs = sampleTimeUs
                // extractor 의 SAMPLE_FLAG_* 와 muxer 가 기대하는 BUFFER_FLAG_* 는 서로 다른
                // 상수 체계다. 지금은 값이 우연히 겹쳐 무해했지만, sync 여부만 명시적으로 옮긴다.
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(dstTrack, buffer, info)
                extractor.advance()
            }
            return true
        } catch (e: Exception) {
            dst.delete()
            return false
        } finally {
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
