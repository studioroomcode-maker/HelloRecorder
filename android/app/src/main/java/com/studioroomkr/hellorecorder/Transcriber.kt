package com.studioroomkr.hellorecorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.VisibleForTesting
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.nio.ByteOrder

/**
 * 온디바이스 한국어 STT 전사기 (sherpa-onnx **오프라인** Zipformer, int8).
 *
 *  - 모델은 APK 에 번들하지 않고 {getExternalFilesDir}/stt-model/ 에서 찾는다
 *    (encoder/decoder/joiner *.onnx + tokens.txt, int8 우선). 없으면 create() = null.
 *  - 전사는 배치(파일 단위)로만 돈다 — 상시 부하 없음. TranscribeWorker 가 충전 중에만 호출.
 *
 * ## 왜 오프라인 모델인가
 * 예전엔 streaming Zipformer 를 썼다. 스트리밍 모델은 "지금까지 들은 것"만으로 즉시 답을
 * 내야 해서 뒤 문맥을 보지 못한다 — 실시간 자막엔 필수지만, 이 앱의 전사는 **이미 다 녹음된
 * 파일**을 충전 중에 처리하는 배치 작업이라 그 정확도 손해를 감수할 이유가 없었다.
 * 오프라인 모델은 구간 전체를 보고 결정한다. 덤으로 encoder 가 더 작다(127MB → 70.8MB).
 *
 * ## 메모리 (중요)
 * 오프라인 인식기는 "한 번에 넣은 만큼"을 통째로 들고 추론한다. 1시간 녹음을 통으로 넣으면
 * FloatArray 만 230MB 라 예전 스트리밍 구현이 굳이 흘려보내기 방식을 썼다. 그래서 여기서는
 * **무음 경계로 잘라 구간 단위로** 인식한다. 버퍼는 SEG_MAX(30초) 하나를 재사용하므로
 * 파일 길이와 무관하게 메모리가 일정하다(약 1.9MB + 모델 네이티브 힙).
 *
 * 구간 경계가 곧 세그먼트 타임스탬프가 된다 — 검색 결과에서 그 위치로 재생 이동하는 데 쓴다.
 */
class Transcriber private constructor(private val recognizer: OfflineRecognizer) {

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
        val seg = FloatArray(SEG_MAX)      // 구간 버퍼 하나를 끝까지 재사용
        var segLen = 0                     // 현재 구간에 쌓인 샘플 수
        var quietRun = 0                   // 구간 끝에 이어진 조용한 샘플 수
        var total = 0L                     // 지금까지 읽은 총 샘플(타임스탬프 기준)
        var segStart = 0L                  // 현재 구간의 시작 시각(ms)
        var rate = SAMPLE_RATE

        /** 쌓인 구간을 인식해 세그먼트로 붙인다. 빈 결과는 버린다. */
        fun flush() {
            if (segLen == 0) return
            // 무음만 모인 구간은 인식에 넣어봐야 시간만 쓴다.
            if (segLen > quietRun) {
                val stream = recognizer.createStream()
                try {
                    // rate 가 featConfig 와 달라도 sherpa 가 내부에서 리샘플한다.
                    stream.acceptWaveform(seg.copyOf(segLen), rate)
                    recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text.trim()
                    if (text.isNotEmpty()) segments.add(Segment(segStart, text))
                } finally {
                    stream.release()
                }
            }
            segLen = 0
            quietRun = 0
        }

        decodeStreaming(file) { chunk, count, sampleRate ->
            rate = sampleRate
            var off = 0
            while (off < count) {
                if (segLen == 0) segStart = if (rate > 0) total * 1000L / rate else 0L
                val n = minOf(SEG_MAX - segLen, count - off)
                // 조용한 꼬리 길이를 갱신 — 무음이 충분히 이어지면 거기서 끊는다.
                for (i in 0 until n) {
                    val v = chunk[off + i]
                    if (v > -SILENCE_AMP && v < SILENCE_AMP) quietRun++ else quietRun = 0
                }
                System.arraycopy(chunk, off, seg, segLen, n)
                segLen += n
                off += n
                total += n

                val quietMs = if (rate > 0) quietRun * 1000L / rate else 0L
                val segMs = if (rate > 0) segLen * 1000L / rate else 0L
                // 문장 중간에 자르면 문맥이 끊기므로 무음 경계를 우선한다. 다만 이 앱의 녹음은
                // 애초에 소리 있을 때만 저장돼서 무음이 잘 안 나올 수 있다 → SEG_MAX 로 강제 분할.
                if (segLen == SEG_MAX || (quietMs >= SILENCE_MS && segMs >= SEG_MIN_MS)) flush()
            }
        }
        flush()

        val audioMs = if (rate > 0) total * 1000L / rate else 0L
        return Transcript(audioMs, segments)
    }

    fun close() {
        try { recognizer.release() } catch (_: Throwable) {}
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

        // 한 번에 인식기에 넣는 최대 길이. Whisper 는 30초를 넘으면 초과분을 조용히 버리므로
        // 반드시 그 아래여야 한다 — 25초로 여유를 둔다. 파일이 아무리 길어도 메모리는 이
        // 버퍼 하나(약 1.6MB)로 고정된다.
        private const val SEG_MAX = 25 * SAMPLE_RATE
        // 이보다 짧으면 무음이 와도 끊지 않는다 — 한두 음절만 담긴 조각은 인식률이 나쁘다.
        private const val SEG_MIN_MS = 1_000L
        // 이만큼 조용하면 문장이 끝난 것으로 보고 끊는다.
        private const val SILENCE_MS = 400L
        // 무음 판정 진폭(-1..1 정규화 기준). 녹음 자체가 소리 있을 때만 저장되므로 낮게 잡는다.
        private const val SILENCE_AMP = 0.01f

        fun modelDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "stt-model")

        // Whisper 는 encoder+decoder 만 쓴다(transducer 의 joiner 가 없다).
        data class Model(val encoder: File, val decoder: File, val tokens: File)

        /**
         * 설치된 모델이 이 코드가 기대하는 그 모델인지 표시하는 파일.
         *
         * STT 모델을 여러 번 바꿔 왔는데(streaming zipformer → offline zipformer → whisper)
         * 파일명이 겹치거나 구조가 달라, 이름만 보면 예전에 받아둔 모델이 그대로 통과해 엉뚱한
         * 인식기에 물린다. 표식이 없거나 다르면 '모델 없음'으로 보고 다시 받게 한다.
         */
        const val MODEL_ID = "whisper-base-multilingual-int8"
        fun modelIdFile(ctx: Context): File = File(modelDir(ctx), ".model-id")

        /** 모델 파일 자동 탐색(int8 우선). 표식·필수 파일이 없으면 null. */
        fun findModel(ctx: Context): Model? {
            if (modelIdFile(ctx).takeIf { it.isFile }?.readText()?.trim() != MODEL_ID) return null
            val files = modelDir(ctx).listFiles()?.toList() ?: return null
            fun pick(keyword: String): File? =
                files.filter { it.name.endsWith(".onnx") && it.name.contains(keyword) }
                    .sortedByDescending { if (it.name.contains("int8")) 1 else 0 }
                    .firstOrNull()
            val encoder = pick("encoder") ?: return null
            val decoder = pick("decoder") ?: return null
            // whisper 토큰 파일명은 base-tokens.txt (모델마다 접두사가 다를 수 있어 접미사로 찾는다).
            val tokens = files.firstOrNull { it.name.endsWith("tokens.txt") } ?: return null
            return Model(encoder, decoder, tokens)
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
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = model.encoder.absolutePath,
                            decoder = model.decoder.absolutePath,
                            language = "ko",        // 한국어 고정(자동 감지보다 빠르고 안정적)
                            task = "transcribe",    // 번역 아님, 그대로 받아쓰기
                        ),
                        tokens = model.tokens.absolutePath,
                        numThreads = 2,
                        modelType = "whisper",
                    ),
                    // whisper 는 자기 디코더로 문장을 생성한다 — transducer 의 beam search 파라미터가
                    // 아니라 greedy 를 쓴다(실측 결과도 greedy 로 충분).
                    decodingMethod = "greedy_search",
                )
                Transcriber(OfflineRecognizer(assetManager = null, config = config))
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
