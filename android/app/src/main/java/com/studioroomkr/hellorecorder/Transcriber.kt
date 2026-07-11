package com.studioroomkr.hellorecorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 온디바이스 한국어 STT 전사기 (sherpa-onnx 스트리밍 Zipformer, int8).
 *
 *  - 모델은 APK 에 번들하지 않고 {getExternalFilesDir}/stt-model/ 에서 찾는다
 *    (encoder/decoder/joiner *.onnx + tokens.txt, int8 우선). 없으면 create() = null.
 *  - 전사는 배치(파일 단위)로만 돈다 — 상시 부하 없음. TranscribeWorker 가 충전 중에만 호출.
 *  - 엔드포인트(무음 경계) 단위로 세그먼트를 끊어 [시작 시각 + 문장] 목록을 만든다.
 *    검색 결과에서 해당 위치로 바로 재생 이동하기 위한 근사 타임스탬프다.
 *  - 실측(갤럭시 S25U): RTF 0.035(1시간 녹음 ≈ 2분), 네이티브 힙 +164MB(전사 중에만).
 *    인스턴스는 여러 파일에 재사용하고, 큐를 다 비우면 close() 로 즉시 놓아준다.
 */
class Transcriber private constructor(private val recognizer: OnlineRecognizer) {

    /** 한 발화 세그먼트. startMs 는 근사(엔드포인트 경계 기준). */
    data class Segment(val startMs: Long, val text: String)

    data class Transcript(val audioMs: Long, val segments: List<Segment>) {
        /** 검색 인덱싱용 전체 텍스트. */
        fun fullText(): String = segments.joinToString(" ") { it.text }
    }

    /** .m4a 한 개 전사. 디코드/추론 실패 시 예외 — 호출부(워커)가 파일 단위로 격리한다. */
    fun transcribe(file: File): Transcript {
        val (samples, sampleRate) = decodeToFloat(file)
        val audioMs = if (sampleRate > 0) samples.size * 1000L / sampleRate else 0L
        val segments = ArrayList<Segment>()

        val stream = recognizer.createStream("")
        try {
            var fed = 0
            var segStart = 0L
            while (fed < samples.size) {
                val n = minOf(CHUNK, samples.size - fed)
                stream.acceptWaveform(samples.copyOfRange(fed, fed + n), sampleRate)
                fed += n
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                if (recognizer.isEndpoint(stream)) {
                    val text = recognizer.getResult(stream).text.trim()
                    if (text.isNotEmpty()) segments.add(Segment(segStart, text))
                    recognizer.reset(stream)
                    segStart = fed * 1000L / sampleRate
                }
            }
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val tail = recognizer.getResult(stream).text.trim()
            if (tail.isNotEmpty()) segments.add(Segment(segStart, tail))
        } finally {
            stream.release()
        }
        return Transcript(audioMs, segments)
    }

    fun close() {
        try { recognizer.release() } catch (_: Throwable) {}
    }

    companion object {
        // 0.5초 단위로 밀어넣는다 — 청크가 너무 작으면 JNI 왕복 오버헤드, 너무 크면
        // 엔드포인트 경계(세그먼트 타임스탬프)가 거칠어진다.
        private const val CHUNK = 8_000
        private const val SAMPLE_RATE = 16_000

        fun modelDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "stt-model")

        data class Model(val encoder: File, val decoder: File, val joiner: File, val tokens: File)

        /** 모델 파일 자동 탐색(파일명이 버전마다 달라 키워드로 찾고 int8 우선). 없으면 null. */
        fun findModel(ctx: Context): Model? {
            val files = modelDir(ctx).listFiles()?.toList() ?: return null
            fun pick(keyword: String): File? =
                files.filter { it.name.endsWith(".onnx") && it.name.contains(keyword) }
                    .sortedByDescending { if (it.name.contains("int8")) 1 else 0 }
                    .firstOrNull()
            val encoder = pick("encoder") ?: return null
            val decoder = pick("decoder") ?: return null
            val joiner = pick("joiner") ?: return null
            val tokens = files.firstOrNull { it.name == "tokens.txt" } ?: return null
            return Model(encoder, decoder, joiner, tokens)
        }

        fun isModelAvailable(ctx: Context): Boolean = findModel(ctx) != null

        /** 전사기 생성. 모델 없음/네이티브 로드 실패 시 null(호출부는 조용히 건너뜀). */
        fun create(ctx: Context): Transcriber? {
            val model = findModel(ctx) ?: return null
            return try {
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = model.encoder.absolutePath,
                            decoder = model.decoder.absolutePath,
                            joiner = model.joiner.absolutePath,
                        ),
                        tokens = model.tokens.absolutePath,
                        numThreads = 2,
                    ),
                    // 무음 경계에서 세그먼트를 끊는다(타임스탬프용). 규칙은 라이브러리 기본값.
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                )
                Transcriber(OnlineRecognizer(config = config))
            } catch (_: Throwable) { null }
        }

        /** .m4a(AAC) → 모노 FloatArray([-1,1]) + 샘플레이트. 스테레오면 다운믹스. */
        private fun decodeToFloat(file: File): Pair<FloatArray, Int> {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    trackIndex = i; format = f; break
                }
            }
            val fmt = format ?: run { extractor.release(); throw IllegalStateException("오디오 트랙 없음") }
            extractor.selectTrack(trackIndex)

            val sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            while (!outEos) {
                if (!inEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(chunk, 0, info.size)
                        pcm.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            codec.stop(); codec.release(); extractor.release()

            val bytes = pcm.toByteArray()
            val shortBuf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val n = shortBuf.remaining()
            val shorts = ShortArray(n); shortBuf.get(shorts)
            val samples = if (channels <= 1) {
                FloatArray(n) { shorts[it] / 32768f }
            } else {
                FloatArray(n / channels) { i ->
                    var sum = 0
                    for (c in 0 until channels) sum += shorts[i * channels + c]
                    (sum / channels) / 32768f
                }
            }
            return samples to sampleRate
        }
    }
}
