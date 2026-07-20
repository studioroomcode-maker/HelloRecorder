package com.studioroomkr.hellorecorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Debug
import android.os.SystemClock
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
 * 온디바이스 한국어 STT 타당성 스파이크 (debug 빌드 전용, 릴리스 미포함).
 *
 * 목적: 앱이 실제로 저장하는 .m4a(16kHz 모노 AAC)를 sherpa-onnx 의 한국어 스트리밍
 * Zipformer 트랜스듀서로 배치 전사하고, 정확도(전사문)·속도(RTF)·메모리를 실측한다.
 *
 * 모델은 APK 에 번들하지 않는다. 기기의 다음 경로에 풀어 넣는다:
 *   {getExternalFilesDir}/stt-model/  (encoder/decoder/joiner *.onnx + tokens.txt)
 * 예) sherpa-onnx-streaming-zipformer-korean-2024-06-16 압축 해제 결과.
 */
object SttSpike {

    data class Model(val encoder: File, val decoder: File, val joiner: File, val tokens: File)

    data class Result(
        val transcript: String,
        val audioMs: Long,        // 오디오 길이
        val decodeMs: Long,       // 전사(디코딩) 소요
        val modelLoadMs: Long,    // 모델 로드 소요
        val rtf: Double,          // decodeMs / audioMs (<1 이면 실시간보다 빠름)
        val nativeHeapDeltaKb: Long, // 모델 로드+추론 동안 네이티브 힙 증가
        val sampleCount: Int,
        val sampleRate: Int,
    )

    fun modelDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "stt-model")

    /** 모델 파일 자동 탐색(파일명이 버전마다 달라 키워드로 찾고 int8 을 우선). 없으면 null. */
    fun findModel(ctx: Context): Model? {
        val dir = modelDir(ctx)
        val files = dir.listFiles()?.toList() ?: return null
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

    /** .m4a 한 개를 전사하고 측정값을 돌려준다. 모델 없으면 IllegalStateException. */
    fun transcribe(ctx: Context, file: File): Result {
        val model = findModel(ctx)
            ?: throw IllegalStateException("모델 없음: ${modelDir(ctx).absolutePath} 에 풀어 넣으세요")

        val nativeBefore = Debug.getNativeHeapAllocatedSize()

        val tLoad0 = SystemClock.elapsedRealtime()
        val recognizer = buildRecognizer(model)
        val modelLoadMs = SystemClock.elapsedRealtime() - tLoad0

        val (samples, sampleRate) = decodeToFloat(file)
        val audioMs = if (sampleRate > 0) samples.size * 1000L / sampleRate else 0L

        val t0 = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream("")
        stream.acceptWaveform(samples, sampleRate)
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val transcript = recognizer.getResult(stream).text
        val decodeMs = SystemClock.elapsedRealtime() - t0

        val nativeDeltaKb = (Debug.getNativeHeapAllocatedSize() - nativeBefore) / 1024

        stream.release()
        recognizer.release()

        val rtf = if (audioMs > 0) decodeMs.toDouble() / audioMs else 0.0
        return Result(
            transcript = transcript,
            audioMs = audioMs,
            decodeMs = decodeMs,
            modelLoadMs = modelLoadMs,
            rtf = rtf,
            nativeHeapDeltaKb = nativeDeltaKb,
            sampleCount = samples.size,
            sampleRate = sampleRate,
        )
    }

    private fun buildRecognizer(model: Model): OnlineRecognizer {
        val transducer = OnlineTransducerModelConfig(
            encoder = model.encoder.absolutePath,
            decoder = model.decoder.absolutePath,
            joiner = model.joiner.absolutePath,
        )
        val modelConfig = OnlineModelConfig(
            transducer = transducer,
            tokens = model.tokens.absolutePath,
            numThreads = 2,
        )
        val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
        val config = OnlineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            enableEndpoint = false,           // 배치 전사: 엔드포인트 분할 끔
            decodingMethod = "greedy_search",
        )
        // assetManager 는 기본 null → 파일 경로에서 로드
        return OnlineRecognizer(config = config)
    }

    /** .m4a(AAC) → 16kHz 가정 모노 FloatArray([-1,1]) + 실제 샘플레이트. 스테레오면 다운믹스. */
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

    /** 문자 단위 CER(공백 제거 후 Levenshtein / 기준 문자수). 참조문이 비면 -1. */
    fun cer(reference: String, hypothesis: String): Double {
        val r = reference.replace("\\s".toRegex(), "")
        val h = hypothesis.replace("\\s".toRegex(), "")
        if (r.isEmpty()) return -1.0
        val dp = IntArray(h.length + 1) { it }
        for (i in 1..r.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..h.length) {
                val tmp = dp[j]
                dp[j] = if (r[i - 1] == h[j - 1]) prev
                else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return dp[h.length].toDouble() / r.length
    }
}
