package com.studioroomkr.hellorecorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.VisibleForTesting
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
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

    /**
     * .m4a 한 개 전사. 디코드/추론 실패 시 예외 — 호출부(워커)가 파일 단위로 격리한다.
     *
     * 디코더가 뱉는 대로 바로 recognizer 에 흘려보내고 전체 PCM 은 들고 있지 않는다.
     * 메모리는 파일 길이와 무관하게 일정하다(0.5초 버퍼 하나).
     */
    fun transcribe(file: File): Transcript {
        val segments = ArrayList<Segment>()
        val pending = FloatArray(CHUNK)
        var filled = 0
        var fed = 0L          // recognizer 에 넣은 총 샘플 수(타임스탬프 기준)
        var segStart = 0L
        var rate = SAMPLE_RATE

        val stream = recognizer.createStream("")
        try {
            // rate 가 featConfig 와 달라도 sherpa 가 내부에서 리샘플한다.
            fun feed(samples: FloatArray) {
                stream.acceptWaveform(samples, rate)
                fed += samples.size
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                if (recognizer.isEndpoint(stream)) {
                    val text = recognizer.getResult(stream).text.trim()
                    if (text.isNotEmpty()) segments.add(Segment(segStart, text))
                    recognizer.reset(stream)
                    segStart = if (rate > 0) fed * 1000L / rate else 0L
                }
            }

            decodeStreaming(file) { chunk, count, sampleRate ->
                rate = sampleRate
                // 디코더 출력은 청크가 잘아서(AAC 프레임 ~20ms) CHUNK 로 모아서 넣는다.
                var off = 0
                while (off < count) {
                    val n = minOf(CHUNK - filled, count - off)
                    System.arraycopy(chunk, off, pending, filled, n)
                    filled += n
                    off += n
                    if (filled == CHUNK) {
                        feed(pending)
                        filled = 0
                    }
                }
            }
            if (filled > 0) feed(pending.copyOf(filled))

            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val tail = recognizer.getResult(stream).text.trim()
            if (tail.isNotEmpty()) segments.add(Segment(segStart, tail))
        } finally {
            stream.release()
        }
        val audioMs = if (rate > 0) fed * 1000L / rate else 0L
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

        /**
         * 마지막 create() 실패 이유. 예전엔 예외를 통째로 삼켜서, 모델 파일은 있는데 인식기
         * 생성이 실패하면 '전사했는데 말소리가 없었다'와 구분이 안 됐다(실제로 그렇게 오진했다).
         * isModelAvailable() 은 파일 존재만 보므로 이 경로를 걸러주지 못한다.
         */
        @Volatile var lastCreateError: String? = null
            private set

        /** 전사기 생성. 모델 없음/네이티브 로드 실패 시 null(이유는 lastCreateError). */
        fun create(ctx: Context): Transcriber? {
            lastCreateError = null
            val model = findModel(ctx)
            if (model == null) {
                lastCreateError = "모델 파일을 찾지 못했습니다 (${modelDir(ctx).absolutePath})"
                return null
            }
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
                    // greedy 는 매 스텝 1등만 남겨 앞선 오인식을 되돌리지 못한다. 빔 탐색은
                    // 후보를 여러 개 끌고 가며 뒤 문맥으로 고를 수 있어 한국어처럼 어미가
                    // 뒤에 붙는 언어에서 특히 유리하다. 느려지지만 실측 RTF 0.035 라
                    // 배치 전사에는 여유가 충분하다(충전 중에만 도는 작업이다).
                    decodingMethod = "modified_beam_search",
                    maxActivePaths = 4,
                )
                Transcriber(OnlineRecognizer(config = config))
            } catch (t: Throwable) {
                lastCreateError = "인식기 생성 실패: ${t::class.java.simpleName}: ${t.message}"
                null
            }
        }

        /**
         * .m4a(AAC) → 모노 float([-1,1]) 청크를 디코드되는 대로 sink 로 흘려보낸다.
         * sink(버퍼, 유효 샘플 수, 샘플레이트) — 버퍼는 재사용하므로 붙들지 말 것.
         *
         * 전체 PCM 을 모아두지 않는다. 1시간·16kHz 모노면 FloatArray 만 230MB 고, 예전엔
         * 여기에 ByteArrayOutputStream·toByteArray()·ShortArray 사본과 모델의 네이티브
         * 힙(~164MB)이 겹쳐 순간 수백 MB 였다. 앱이 1시간짜리 파일을 만드는 이상
         * 이건 예외 상황이 아니라 정상 경로다.
         *
         * internal 인 이유: 전사 모델 없이도 디코드 경로만 계측 테스트로 확인하기 위해서다
         * (TranscriberDecodeTest). 앱 코드에서는 transcribe() 로만 쓴다.
         */
        @VisibleForTesting
        internal fun decodeStreaming(file: File, sink: (FloatArray, Int, Int) -> Unit) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                        trackIndex = i; format = f; break
                    }
                }
                val fmt = format ?: throw IllegalStateException("오디오 트랙 없음")
                extractor.selectTrack(trackIndex)

                var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

                val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                try {
                    codec.configure(fmt, null, null, 0)
                    codec.start()

                    val info = MediaCodec.BufferInfo()
                    var inEos = false
                    var outEos = false
                    var shorts = ShortArray(0)
                    var mono = FloatArray(0)

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
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // 디코더가 실제로 내보내는 형식이 컨테이너 헤더와 다를 수 있다.
                            val out = codec.outputFormat
                            sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else if (outIdx >= 0) {
                            if (info.size > 0 && channels > 0) {
                                val n = info.size / 2
                                if (shorts.size < n) shorts = ShortArray(n)
                                val outBuf = codec.getOutputBuffer(outIdx)!!
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, n)

                                val frames = n / channels
                                if (mono.size < frames) mono = FloatArray(frames)
                                if (channels == 1) {
                                    for (i in 0 until frames) mono[i] = shorts[i] / 32768f
                                } else {
                                    for (i in 0 until frames) {
                                        var sum = 0
                                        for (c in 0 until channels) sum += shorts[i * channels + c]
                                        mono[i] = (sum / channels) / 32768f
                                    }
                                }
                                sink(mono, frames, sampleRate)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                } finally {
                    codec.release()
                }
            } finally {
                extractor.release()
            }
        }
    }
}
