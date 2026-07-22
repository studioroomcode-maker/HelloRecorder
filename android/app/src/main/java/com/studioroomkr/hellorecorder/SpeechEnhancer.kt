package com.studioroomkr.hellorecorder

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig

/**
 * GTCRN 신경망 잡음 제거 — '목소리 강조'(Pro).
 *
 * ## 왜 sherpa 로 갈아탔나 (2026-07-20)
 * 예전엔 Microsoft onnxruntime-android 의 Java API 로 gtcrn_simple.onnx 를 직접 돌리고
 * STFT/OLA 를 손으로 구현했다. 그런데 STT 를 sherpa 로 옮기면서, 두 라이브러리가 **같은 이름의
 * libonnxruntime.so 를 각자 요구**한다는 게 드러났다. 하나만 패키징할 수 있는데 요구 버전이 다르다:
 *
 *     libonnxruntime4j_jni.so(MS) 가 요구 : VERS_1.22.0
 *     sherpa 의 libonnxruntime.so 가 제공 : VERS_1.24.3
 *
 * ELF 심볼 버전 노드가 어긋나 dlopen 이 실패한다. sherpa 것을 빼면 STT 가 죽고, MS 것을 빼면
 * 이 기능이 죽는 — 어느 쪽도 성립하지 않았다(실제로 목소리 강조가 조용히 꺼져 있었다).
 *
 * sherpa 가 GTCRN 을 내장 지원하므로 그쪽으로 옮겨 **네이티브 런타임을 하나로 통일**했다.
 * 버전 충돌이 구조적으로 사라지고, MS 의존성과 손으로 짠 FFT/OLA 가 통째로 없어진다.
 * 모델은 쓰던 gtcrn_simple.onnx 그대로라 음질 성격은 유지된다.
 *
 * ## 계약
 * 공개 API(reset/process/flush/close)는 예전과 같다 — AudioEngine 은 손대지 않는다.
 * sherpa 는 float([-1,1]) 를 다루므로 경계에서만 int16 과 변환한다.
 * process() 반환 길이가 입력과 다를 수 있는 것(프레임 경계)도 예전과 동일하다.
 */
class SpeechEnhancer private constructor(private val denoiser: OnlineSpeechDenoiser) {

    /** 새 녹음 구간 시작 — 이전 구간의 내부 상태가 새 구간으로 새지 않게 한다. */
    fun reset() {
        try { denoiser.reset() } catch (_: Throwable) {}
    }

    /**
     * 잡음 제거 적용. 아직 프레임이 안 찼으면 빈 배열을 돌려준다(호출부는 이어 붙이면 된다).
     */
    fun process(pcm: ShortArray, len: Int): ShortArray = try {
        val f = FloatArray(len)
        for (i in 0 until len) f[i] = pcm[i] / SCALE
        toShorts(denoiser.run(f, SAMPLE_RATE).samples)
    } catch (_: Throwable) {
        // 한 버퍼가 실패했다고 녹음을 잃지는 않는다 — 이 구간은 원음 그대로 흘린다.
        pcm.copyOf(len)
    }

    /** 구간 끝에서 내부에 남은 꼬리를 뽑아낸다. */
    fun flush(): ShortArray = try {
        toShorts(denoiser.flush().samples)
    } catch (_: Throwable) {
        EMPTY
    }

    fun close() {
        try { denoiser.release() } catch (_: Throwable) {}
    }

    private fun toShorts(samples: FloatArray): ShortArray {
        if (samples.isEmpty()) return EMPTY
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            // 신경망 출력이 [-1,1] 을 살짝 넘을 수 있다 → 클리핑으로 래핑(찢어지는 소리) 방지.
            out[i] = (samples[i] * SCALE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val SCALE = 32768.0f   // int16 ↔ [-1,1] 정규화
        private val EMPTY = ShortArray(0)

        /**
         * 마지막 create() 실패 이유. 예전엔 예외를 통째로 삼켜서, 기능이 꺼진 것과
         * '효과가 약한 것'이 구분되지 않았다 — 실제로 그 탓에 고장을 한참 못 알아챘다.
         */
        @Volatile var lastCreateError: String? = null
            private set

        /** 모델 로드. 실패(에셋 없음·네이티브 오류 등)하면 null. */
        fun create(context: Context): SpeechEnhancer? = try {
            lastCreateError = null
            val config = OnlineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    // assetManager 를 넘기므로 APK 에셋에서 바로 읽는다(파일로 풀 필요 없음).
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = "gtcrn_simple.onnx"),
                    numThreads = 1,   // 절전: 상시 녹음 경로에서 도는 처리다
                    debug = false,
                    provider = "cpu",
                )
            )
            SpeechEnhancer(OnlineSpeechDenoiser(assetManager = context.assets, config = config))
        } catch (t: Throwable) {
            lastCreateError = "${t::class.java.simpleName}: ${t.message}"
            null
        }
    }
}
