package com.studioroomkr.hellorecorder

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * GTCRN(ONNX) 기반 실시간 음성 향상(잡음 제거).
 *
 *  - 16kHz mono. n_fft=512, hop=256, 윈도우 = sqrt(np.hanning(512)) (분석=합성).
 *  - STFT/iSTFT/overlap-add 는 직접 구현하고, 스펙트럼 향상만 GTCRN 모델이 한다.
 *  - 스트리밍: 캐시 텐서 3개(conv/tra/inter)를 프레임마다 이어준다.
 *  - 한 '녹음 구간'마다 reset() 으로 상태를 초기화하고, 같은 세션(모델)을 재사용한다.
 *  - overlap-add 특성상 출력은 입력보다 hop(256≈16ms)만큼 지연되며, finish 시 flush 로 잔여를 뺀다.
 *
 * 모델/런타임 로드 실패 시 create() 가 null → 호출 측은 잡음제거 없이 진행(폴백).
 * 녹음(인코딩) 중에만 동작하므로 상시 비용은 없다.
 */
class SpeechEnhancer private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {
    // sqrt-Hann (numpy: 분모가 N-1). 모델 학습/익스포트 윈도우와 동일해야 함.
    private val window = DoubleArray(NFFT) { sqrt(0.5 - 0.5 * cos(2.0 * Math.PI * it / (NFFT - 1))) }

    private val fft = Fft(NFFT)

    // STFT 입력 슬라이딩(직전 256 + 신규 256 = 512), iSTFT overlap-add 누적
    private val inBuf = DoubleArray(NFFT)
    private val ola = DoubleArray(NFFT)
    private val hopIn = DoubleArray(HOP)
    private var hopFill = 0

    // FFT 작업 버퍼
    private val re = DoubleArray(NFFT)
    private val im = DoubleArray(NFFT)
    private val mix = FloatArray(BINS * 2)   // (257,2) 평탄화

    // GTCRN 스트리밍 캐시(float32)
    private val convCache = FloatArray(2 * 1 * 16 * 16 * 33)
    private val traCache = FloatArray(2 * 3 * 1 * 1 * 16)
    private val interCache = FloatArray(2 * 1 * 33 * 16)

    /** 새 녹음 구간 시작 시 호출 — 스트리밍 상태/캐시 초기화. */
    fun reset() {
        java.util.Arrays.fill(inBuf, 0.0)
        java.util.Arrays.fill(ola, 0.0)
        hopFill = 0
        java.util.Arrays.fill(convCache, 0f)
        java.util.Arrays.fill(traCache, 0f)
        java.util.Arrays.fill(interCache, 0f)
    }

    /**
     * 임의 길이 PCM 을 잡음제거해 반환. 반환 길이는 hop 의 배수(완성된 프레임만큼).
     * 입력보다 hop 만큼 지연된 결과가 나오며, 마지막 잔여는 flush() 로 뺀다.
     */
    fun process(pcm: ShortArray, len: Int): ShortArray {
        val completedHops = (hopFill + len) / HOP
        if (completedHops == 0) {
            for (i in 0 until len) hopIn[hopFill++] = pcm[i].toDouble()
            return EMPTY
        }
        val out = ShortArray(completedHops * HOP)
        var outPos = 0
        for (i in 0 until len) {
            hopIn[hopFill++] = pcm[i].toDouble()
            if (hopFill == HOP) {
                processHop(out, outPos)
                outPos += HOP
                hopFill = 0
            }
        }
        return out
    }

    /** 구간 끝에서 버퍼에 남은 꼬리를 0 으로 밀어내 마저 출력. */
    fun flush(): ShortArray {
        // 남은 부분 채움(0 패딩) + OLA 잔여 1프레임을 밀어내기 위해 두 번 처리
        val out = ShortArray(2 * HOP)
        var outPos = 0
        // 1) 부분 hop 을 0 으로 채워 한 프레임 완성
        while (hopFill < HOP) hopIn[hopFill++] = 0.0
        processHop(out, outPos); outPos += HOP; hopFill = 0
        // 2) OLA 에 남은 후반부를 밀어내기 위해 0 hop 하나 더
        while (hopFill < HOP) hopIn[hopFill++] = 0.0
        processHop(out, outPos); hopFill = 0
        return out
    }

    private fun processHop(out: ShortArray, outPos: Int) {
        // 슬라이딩 윈도우: [이전 256 | 신규 256]
        System.arraycopy(inBuf, HOP, inBuf, 0, NFFT - HOP)
        for (i in 0 until HOP) inBuf[NFFT - HOP + i] = hopIn[i]

        // STFT: [-1,1] 정규화(모델 학습 스케일) + 윈도우 적용 후 FFT
        for (i in 0 until NFFT) { re[i] = (inBuf[i] / SCALE) * window[i]; im[i] = 0.0 }
        fft.transform(re, im, false)
        for (k in 0 until BINS) { mix[k * 2] = re[k].toFloat(); mix[k * 2 + 1] = im[k].toFloat() }

        // GTCRN 추론 (실패하면 향상 없이 원본 통과)
        val enhanced = runModel()
        if (enhanced != null) {
            // 257 빈 → 512 에르미트 대칭 복원
            for (k in 0 until BINS) { re[k] = enhanced[k * 2].toDouble(); im[k] = enhanced[k * 2 + 1].toDouble() }
            for (k in BINS until NFFT) { re[k] = re[NFFT - k]; im[k] = -im[NFFT - k] }
            fft.transform(re, im, true)   // inverse (1/N 스케일 포함)
        } else {
            // 폴백: 원본 STFT 그대로 역변환 → 사실상 원본 복원
            for (k in BINS until NFFT) { re[k] = re[NFFT - k]; im[k] = -im[NFFT - k] }
            fft.transform(re, im, true)
        }

        // 합성 윈도우 + overlap-add
        for (i in 0 until NFFT) ola[i] += re[i] * window[i]
        // 앞 hop 출력 ([-1,1] → int16 복원)
        for (i in 0 until HOP) {
            val v = ola[i] * SCALE
            out[outPos + i] = when {
                v > 32767.0 -> 32767
                v < -32768.0 -> -32768
                else -> Math.round(v).toInt().toShort()
            }
        }
        // OLA 한 칸 전진
        System.arraycopy(ola, HOP, ola, 0, NFFT - HOP)
        java.util.Arrays.fill(ola, NFFT - HOP, NFFT, 0.0)
    }

    /** GTCRN 한 프레임 추론. enh(257*2) 반환, 실패 시 null. */
    private fun runModel(): FloatArray? {
        // 네이티브 리소스(텐서·Result)는 매 프레임 생성된다. session.run() 또는 출력 접근에서
        // 예외가 나도 누수되지 않도록 finally 에서 항상 닫는다(이전엔 성공 경로에서만 닫았음).
        var mixT: OnnxTensor? = null
        var convT: OnnxTensor? = null
        var traT: OnnxTensor? = null
        var interT: OnnxTensor? = null
        var res: OrtSession.Result? = null
        return try {
            mixT = OnnxTensor.createTensor(env, FloatBuffer.wrap(mix), longArrayOf(1, BINS.toLong(), 1, 2))
            convT = OnnxTensor.createTensor(env, FloatBuffer.wrap(convCache), longArrayOf(2, 1, 16, 16, 33))
            traT = OnnxTensor.createTensor(env, FloatBuffer.wrap(traCache), longArrayOf(2, 3, 1, 1, 16))
            interT = OnnxTensor.createTensor(env, FloatBuffer.wrap(interCache), longArrayOf(2, 1, 33, 16))
            val inputs = mapOf(
                "mix" to mixT, "conv_cache" to convT,
                "tra_cache" to traT, "inter_cache" to interT,
            )
            res = session.run(inputs)
            val enh = FloatArray(BINS * 2)
            (res.get("enh").get() as OnnxTensor).floatBuffer.get(enh)
            (res.get("conv_cache_out").get() as OnnxTensor).floatBuffer.get(convCache)
            (res.get("tra_cache_out").get() as OnnxTensor).floatBuffer.get(traCache)
            (res.get("inter_cache_out").get() as OnnxTensor).floatBuffer.get(interCache)
            enh
        } catch (_: Throwable) {
            null
        } finally {
            try { res?.close() } catch (_: Throwable) {}
            try { mixT?.close() } catch (_: Throwable) {}
            try { convT?.close() } catch (_: Throwable) {}
            try { traT?.close() } catch (_: Throwable) {}
            try { interT?.close() } catch (_: Throwable) {}
        }
    }

    fun close() {
        try { session.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val NFFT = 512
        private const val HOP = 256
        private const val BINS = 257   // NFFT/2 + 1
        private const val SCALE = 32768.0   // int16 ↔ [-1,1] 정규화
        private val EMPTY = ShortArray(0)

        /** 모델 로드. 실패(에셋 없음/ONNX 오류 등)하면 null. */
        fun create(context: Context): SpeechEnhancer? = try {
            val bytes = context.assets.open("gtcrn_simple.onnx").use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)   // 절전: 단일 스레드
            }
            val session = env.createSession(bytes, opts)
            SpeechEnhancer(env, session)
        } catch (_: Throwable) { null }
    }

    /** 2의 거듭제곱 크기 복소 FFT (반복형 Cooley-Tukey, in-place). */
    private class Fft(private val n: Int) {
        private val rev = IntArray(n)

        init {
            var log = 0
            while ((1 shl log) < n) log++
            for (i in 0 until n) {
                var x = i; var r = 0
                for (b in 0 until log) { r = (r shl 1) or (x and 1); x = x shr 1 }
                rev[i] = r
            }
        }

        fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = 2.0 * Math.PI / len * if (inverse) 1.0 else -1.0
                val wlenRe = cos(ang); val wlenIm = kotlin.math.sin(ang)
                var i = 0
                while (i < n) {
                    var wRe = 1.0; var wIm = 0.0
                    val half = len / 2
                    for (k in 0 until half) {
                        val aRe = re[i + k]; val aIm = im[i + k]
                        val bRe0 = re[i + k + half]; val bIm0 = im[i + k + half]
                        val bRe = bRe0 * wRe - bIm0 * wIm
                        val bIm = bRe0 * wIm + bIm0 * wRe
                        re[i + k] = aRe + bRe; im[i + k] = aIm + bIm
                        re[i + k + half] = aRe - bRe; im[i + k + half] = aIm - bIm
                        val nwRe = wRe * wlenRe - wIm * wlenIm
                        wIm = wRe * wlenIm + wIm * wlenRe; wRe = nwRe
                    }
                    i += len
                }
                len = len shl 1
            }
            if (inverse) {
                val inv = 1.0 / n
                for (i in 0 until n) { re[i] *= inv; im[i] *= inv }
            }
        }
    }
}
