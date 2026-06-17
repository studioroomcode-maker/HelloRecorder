package com.studioroomkr.hellorecorder

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import com.konovalov.vad.silero.VadSilero
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * 절전형 상시 녹음 엔진.
 *
 * 동작 원리:
 *  - AudioRecord 로 16kHz mono PCM 을 항상 가볍게 읽는다 (이 부분은 늘 켜짐, 부하 작음).
 *  - 각 버퍼의 RMS(음량)를 계산해 임계값(THRESHOLD_RMS) 이상이면 "소리 있음".
 *  - 소리가 감지되면 그때서야 WakeLock 을 잡고 MediaCodec(AAC) 인코딩을 돌려 파일에 기록.
 *  - 무음이 '구간 묶기 간격'(Prefs) 이상 지속되면 인코딩을 멈추고 WakeLock 을 풀어 CPU 를 쉰다.
 *  - 1시간(HOUR_MS)마다 파일을 닫고 새 파일을 연다 → 하루 최대 24개.
 *
 * 결과적으로 "소리 나는 구간"만 파일에 이어 붙는다.
 */
class AudioEngine(
    private val context: Context,
    private val powerManager: PowerManager,
) {
    companion object {
        private const val SAMPLE_RATE = 16_000          // 음성용으로 충분, 절전
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // 비트레이트는 Prefs.getBitRate() 로 동적 적용 (품질 선택 UI)
        private const val AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC

        // 음량 임계값/구간 묶기 간격은 Prefs 에서 실시간으로 읽음 (UI 슬라이더로 조정)
        // 구간 묶기 중, RMS 가 이 값 미만이면 '완전 무음'으로 보고 그 프레임만 건너뜀
        private const val ZERO_EPS = 1.0
        // 1시간마다 파일 교체
        private const val HOUR_MS = 60 * 60 * 1000L
        // 배터리 표본 주기 (위치 간격은 Prefs 에서 동적으로 읽음)
        private const val BATT_SAMPLE_MS = 2 * 60 * 1000L
        private const val LOC_MAX_AGE_MS = 10 * 60 * 1000L
    }

    // 위치 게이팅 결과 (true = 녹음 허용). 위치 못 읽으면 허용 유지.
    @Volatile private var locationAllowed = true

    private fun sampleBattery() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        Prefs.recordBatterySample(context, level)
    }

    private fun sampleLocation() {
        if (!Prefs.isLocationEnabled(context)) { locationAllowed = true; return }

        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { locationAllowed = true; return }  // 권한 없으면 게이팅 불가 → 허용

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: Location? = null
        for (p in providers) {
            val loc = try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (loc != null && (best == null || loc.time > best!!.time)) best = loc
        }

        val b = best
        if (b == null || System.currentTimeMillis() - b.time > LOC_MAX_AGE_MS) {
            locationAllowed = true   // 위치 불확실 → 허용(소리 놓치지 않게)
            return
        }
        locationAllowed = Prefs.isLocationAllowed(context, b.latitude, b.longitude)
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(3_000)
        thread = null
        releaseWakeLock()
    }

    // 음성 우선 모드용 오디오 효과 (AGC: 작은 목소리 증폭, NS: 정상 소음 억제)
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null

    // GTCRN 잡음 제거기 (한 번만 로드해 모든 구간이 재사용, 구간마다 reset)
    private var enhancer: SpeechEnhancer? = null
    private var enhancerInit = false
    private fun ensureEnhancer(): SpeechEnhancer? {
        if (!enhancerInit) {
            enhancerInit = true
            enhancer = SpeechEnhancer.create(context)
        }
        return enhancer
    }

    // 음성 기능은 Pro 전용 — 무료에선 항상 꺼진 것으로 취급(설정값과 무관).
    private fun voiceModeOn() = Pro.isPro && Prefs.isVoiceModeEnabled(context)
    private fun emphasisOn() = Pro.isPro && Prefs.isVoiceEmphasisEnabled(context)

    @Suppress("MissingPermission")
    private fun createRecord(bufSize: Int): AudioRecord {
        // 목소리 강조면 음성에 튜닝된 소스(VOICE_RECOGNITION) 사용 → 더 깨끗한 음성 신호
        val source = if (emphasisOn())
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        else
            MediaRecorder.AudioSource.MIC
        val rec = AudioRecord(source, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize)
        attachEffects(rec.audioSessionId)
        return rec
    }

    /** 목소리 강조일 때 AGC·노이즈서프레서를 세션에 연결(있는 기기에서만). */
    private fun attachEffects(sessionId: Int) {
        releaseEffects()
        if (!emphasisOn()) return
        try {
            if (AutomaticGainControl.isAvailable())
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
        } catch (_: Exception) {}
        try {
            if (NoiseSuppressor.isAvailable())
                ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        } catch (_: Exception) {}
    }

    private fun releaseEffects() {
        try { agc?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        agc = null; ns = null
    }

    /** 통화(셀룰러/VoIP) 중인지 — AudioManager 모드로 판단(권한 불필요). */
    private fun isInCall(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val mode = am.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 2) // 약 0.5초 버퍼
        var record = createRecord(bufSize)

        val pcm = ShortArray(bufSize / 2)
        record.startRecording()

        var capture: Capture? = null
        var lastSoundTime = 0L
        var currentHourKey = hourKey()
        var lastBattSample = 0L
        var lastLocSample = 0L
        var wasInCall = false
        var currentEmphasis = emphasisOn()
        val voiceVad = VoiceVad(context)

        try {
            while (running) {
                // 통화 감지: 통화 중엔 마이크를 통화가 점유하므로 인코딩을 멈추고 대기.
                // (주변 녹음은 OS가 막아 무음으로 들어옴)
                if (isInCall()) {
                    if (capture != null) { capture.finish(); capture = null; releaseWakeLock() }
                    Prefs.updateLevel(context, 0.0, false)
                    wasInCall = true
                    try { Thread.sleep(700) } catch (_: InterruptedException) {}
                    continue
                }
                if (wasInCall) {
                    // 통화 종료 → 마이크 재확보를 위해 AudioRecord 를 새로 만들어 확실히 재개
                    wasInCall = false
                    try { record.stop(); record.release() } catch (_: Exception) {}
                    record = createRecord(bufSize)
                    record.startRecording()
                }

                // 목소리 강조 토글이 바뀌면 소스/효과가 바뀌므로 레코더 재생성
                val emphasis = emphasisOn()
                if (emphasis != currentEmphasis) {
                    currentEmphasis = emphasis
                    if (capture != null) { capture.finish(); capture = null; releaseWakeLock() }
                    try { record.stop(); record.release() } catch (_: Exception) {}
                    record = createRecord(bufSize)
                    record.startRecording()
                    voiceVad.reset()
                }
                // 검출(밴드패스+VAD)용 — 실시간 반영, 레코더 재생성 불필요
                val voiceMode = voiceModeOn()

                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue

                val rms = computeRms(pcm, read)
                val now = System.currentTimeMillis()

                // 배터리 잔량 주기 표본 (2분마다) → 일별 소모 누적
                if (now - lastBattSample > BATT_SAMPLE_MS) {
                    lastBattSample = now
                    sampleBattery()
                }

                // 위치 주기 표본 (간격은 설정에서 조절) → 위치 게이팅 갱신
                if (now - lastLocSample > Prefs.getLocationIntervalMs(context)) {
                    lastLocSample = now
                    sampleLocation()
                }

                // 시간대(요일/날짜) 그리고 위치, 둘 다 허용일 때만 인코딩
                val cal = Calendar.getInstance()
                if (!Prefs.isRecordingAllowed(context, cal) || !locationAllowed) {
                    if (capture != null) {
                        capture.finish()
                        capture = null
                        releaseWakeLock()
                    }
                    Prefs.updateLevel(context, 0.0, false)
                    try { Thread.sleep(1_000) } catch (_: InterruptedException) {}
                    continue
                }

                // 매 버퍼마다 최신 임계값을 읽어 슬라이더 조정이 즉시 반영되게 함
                val threshold = Prefs.getThreshold(context)
                // 음성 우선 모드: 음성 대역(대략 250~3800Hz) 에너지로 판단 → 작은 목소리에
                // 민감하고, 저주파 웅웅거림(차·에어컨)·고주파 히스 같은 비음성 소음은 무시.
                val detect = if (voiceMode) voiceVad.bandRms(pcm, read) else rms
                // 2단계 음성 게이팅(배터리 최적화):
                //   바닥선: 대역에너지 ≥ 임계값  →  1차: WebRTC VAD(가벼움, 상시)  →
                //   1차 통과 시에만 2차: Silero VAD(정확, 무거움)로 재확인.
                //   각 단계는 사용 불가하면 통과로 간주(폴백). 무음일 땐 2차가 안 돌아 배터리 절약.
                val hasSound = if (voiceMode) {
                    var ok = detect >= threshold
                    if (ok) ok = voiceVad.isSpeechWebRtc(pcm, read) ?: true
                    if (ok && Prefs.isSileroEnabled(context)) {
                        ok = voiceVad.isSpeechSilero(pcm, read) ?: true
                    }
                    ok
                } else {
                    detect >= threshold
                }
                // 현재 음량/캡처 상태를 UI 가 읽을 수 있게 보고 (트리거 기준과 일치)
                Prefs.updateLevel(context, detect, hasSound)

                // 1시간 경계 넘어가면 기존 세션 닫기 (다음 소리부터 새 파일)
                val hk = hourKey()
                if (hk != currentHourKey) {
                    capture?.finish()
                    capture = null
                    releaseWakeLock()
                    currentHourKey = hk
                }

                val mergeGapMs = Prefs.getMergeGapSec(context) * 1000L
                if (hasSound) {
                    lastSoundTime = now
                    // 소리 시작. Capture 가 '짧은 녹음 자동 삭제' 설정을 보고
                    // 임계값 넘기 전까진 메모리에만 버퍼링(인코딩·WakeLock·파일 생성 보류)한다.
                    if (capture == null) capture = Capture(hk)
                    capture.feed(pcm, read)
                } else if (capture != null) {
                    // 임계값 아래(무음 판정). 캡처가 진행 중이면:
                    //  - '구간 묶기 간격'을 넘었으면 닫아서 분리(파일 수 억제)
                    //  - 간격 안이면 같은 구간에 '이어서' 기록해 중간 소리를 끊지 않고
                    //    연속 재생되게 한다. 단, 볼륨이 사실상 0(완전 무음)인 구간만 건너뛴다.
                    if (now - lastSoundTime >= mergeGapMs) {
                        capture.finish()       // 임계 미달이면 인코딩 없이 버퍼만 폐기
                        capture = null
                        releaseWakeLock()      // CPU 휴식
                    } else if (rms >= ZERO_EPS) {
                        capture.feed(pcm, read)   // 중간의 작은 소리는 보존
                    }
                    // rms < ZERO_EPS (완전 무음)이면 그 프레임만 건너뜀
                }
            }
        } finally {
            capture?.finish()
            record.stop()
            record.release()
            releaseEffects()
            voiceVad.close()
            try { enhancer?.close() } catch (_: Throwable) {}
            enhancer = null; enhancerInit = false
            releaseWakeLock()
        }
    }

    /**
     * 음성 대역 검출기 (의존성 없는 경량 신호처리).
     *
     * 입력 PCM 을 250Hz 하이패스 + 3800Hz 로우패스(=대략적인 음성 대역 통과)로 거른 뒤
     * 그 대역의 RMS 를 돌려준다. 사람 목소리 에너지는 이 대역에 몰려 있으므로:
     *  - 작은 목소리도 대역 에너지로 잡혀 더 잘 감지되고,
     *  - 저주파 진동/웅웅거림과 고주파 히스(비음성)는 걸러져 오탐이 준다.
     * 검출 경로에서만 쓰며, 실제 저장되는 오디오(원본)는 건드리지 않는다.
     */
    private class VoiceVad(private val context: Context) {
        private val hp = Biquad.highPass(250.0, SAMPLE_RATE.toDouble())
        private val lp = Biquad.lowPass(3800.0, SAMPLE_RATE.toDouble())

        // 1차 게이트: WebRTC VAD (네이티브 GMM, 매우 가벼움 → 상시 사용)
        private var webrtcInit = false
        private var webrtc: VadWebRTC? = null
        private val wFrame = ShortArray(WEBRTC_FRAME)   // 320 샘플 = 20ms
        private var wFill = 0

        // 2차 확인: Silero VAD (ONNX 신경망, 더 정확하지만 무거움 → 후보일 때만)
        private var sileroInit = false
        private var silero: VadSilero? = null
        private val sFrame = ShortArray(SILERO_FRAME)   // 512 샘플 ≈ 32ms
        private var sFill = 0

        fun reset() {
            hp.reset(); lp.reset(); wFill = 0; sFill = 0
        }

        /** 음성 대역(약 250~3800Hz) RMS — 작은 목소리 민감, 비음성 대역 소음 거부. */
        fun bandRms(pcm: ShortArray, len: Int): Double {
            if (len <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until len) {
                val y = lp.process(hp.process(pcm[i].toDouble()))
                sum += y * y
            }
            return sqrt(sum / len)
        }

        /**
         * 1차: WebRTC VAD 로 이 버퍼에 음성 후보가 있는지(20ms 프레임 단위, 하나라도 음성이면 true).
         * 라이브러리 못 쓰면 null → 호출 측은 대역 에너지만으로 판단(폴백).
         */
        fun isSpeechWebRtc(pcm: ShortArray, len: Int): Boolean? {
            val v = ensureWebrtc() ?: return null
            var speech = false
            var i = 0
            while (i < len) {
                val n = minOf(WEBRTC_FRAME - wFill, len - i)
                System.arraycopy(pcm, i, wFrame, wFill, n)
                wFill += n; i += n
                if (wFill == WEBRTC_FRAME) {
                    if (try { v.isSpeech(wFrame) } catch (_: Throwable) { false }) speech = true
                    wFill = 0
                }
            }
            return speech
        }

        /**
         * 2차: Silero VAD 로 정밀 확인(32ms 프레임 단위). 1차 통과 시에만 호출해 비용을 묶는다.
         * 라이브러리/모델 로드 실패면 null → 호출 측은 1차 결과만으로 판단(폴백).
         */
        fun isSpeechSilero(pcm: ShortArray, len: Int): Boolean? {
            val v = ensureSilero() ?: return null
            var speech = false
            var i = 0
            while (i < len) {
                val n = minOf(SILERO_FRAME - sFill, len - i)
                System.arraycopy(pcm, i, sFrame, sFill, n)
                sFill += n; i += n
                if (sFill == SILERO_FRAME) {
                    if (try { v.isSpeech(sFrame) } catch (_: Throwable) { false }) speech = true
                    sFill = 0
                }
            }
            return speech
        }

        private fun ensureWebrtc(): VadWebRTC? {
            if (!webrtcInit) {
                webrtcInit = true
                webrtc = try {
                    VadWebRTC(
                        sampleRate = SampleRate.SAMPLE_RATE_16K,
                        frameSize = FrameSize.FRAME_SIZE_320,
                        mode = Mode.AGGRESSIVE,
                        speechDurationMs = 50,
                        silenceDurationMs = 300,
                    )
                } catch (_: Throwable) { null }
            }
            return webrtc
        }

        private fun ensureSilero(): VadSilero? {
            if (!sileroInit) {
                sileroInit = true
                silero = try {
                    VadSilero(
                        context,
                        sampleRate = com.konovalov.vad.silero.config.SampleRate.SAMPLE_RATE_16K,
                        frameSize = com.konovalov.vad.silero.config.FrameSize.FRAME_SIZE_512,
                        mode = com.konovalov.vad.silero.config.Mode.AGGRESSIVE,
                        speechDurationMs = 50,
                        silenceDurationMs = 300,
                    )
                } catch (_: Throwable) { null }   // ONNX/모델 로드 실패 → 폴백
            }
            return silero
        }

        fun close() {
            try { webrtc?.close() } catch (_: Throwable) {}
            try { silero?.close() } catch (_: Throwable) {}
            webrtc = null; silero = null
            webrtcInit = false; sileroInit = false
            wFill = 0; sFill = 0
        }

        companion object {
            private const val WEBRTC_FRAME = 320
            private const val SILERO_FRAME = 512
        }
    }

    /** RBJ 쿡북 2차 IIR(biquad), transposed direct form II. */
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun reset() { z1 = 0.0; z2 = 0.0 }

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            private const val Q = 0.707  // Butterworth (최대 평탄)

            fun highPass(f0: Double, fs: Double): Biquad {
                val w0 = 2.0 * Math.PI * f0 / fs
                val cw = kotlin.math.cos(w0)
                val alpha = kotlin.math.sin(w0) / (2.0 * Q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = ((1.0 + cw) / 2.0) / a0,
                    b1 = (-(1.0 + cw)) / a0,
                    b2 = ((1.0 + cw) / 2.0) / a0,
                    a1 = (-2.0 * cw) / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }

            fun lowPass(f0: Double, fs: Double): Biquad {
                val w0 = 2.0 * Math.PI * f0 / fs
                val cw = kotlin.math.cos(w0)
                val alpha = kotlin.math.sin(w0) / (2.0 * Q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = ((1.0 - cw) / 2.0) / a0,
                    b1 = (1.0 - cw) / a0,
                    b2 = ((1.0 - cw) / 2.0) / a0,
                    a1 = (-2.0 * cw) / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }
        }
    }

    /**
     * 한 '소리 구간'의 캡처를 관리한다.
     *
     * '짧은 녹음 자동 삭제'(Prefs.isMinKeepEnabled)가 켜져 있으면 **지연 인코딩**:
     *  - 소리가 시작돼도 바로 인코딩하지 않고 PCM 을 메모리에 모은다.
     *  - 누적 길이가 설정한 기준(Prefs.getMinKeepSec, 슬라이더로 변경 가능)을 넘는
     *    순간에만 인코더·WakeLock·파일을 만들고, 모아둔 버퍼를 한꺼번에 흘려보낸 뒤
     *    실시간 인코딩으로 전환한다(promote).
     *  - 기준을 넘기 전에 구간이 끝나면(finish) 인코딩·파일 없이 버퍼만 버린다.
     *    → 짧은 잡음은 인코딩 자체를 건너뛰어 배터리/발열/파일 수를 아낀다.
     *
     * 설정이 꺼져 있으면 기존처럼 첫 소리부터 즉시 인코딩한다.
     */
    private inner class Capture(private val hourKey: String) {
        private val deferEnabled = Prefs.isMinKeepEnabled(context)
        private var session: EncoderSession? = null
        // 보류 중인 PCM 프레임 사본과 누적 샘플 수(=길이 계산용)
        private val pending = ArrayList<ShortArray>()
        private var pendingSamples = 0L
        private var promoted = false

        /** 인코딩 대상 프레임 투입. 보류/실시간을 자동 선택. */
        fun feed(pcm: ShortArray, len: Int) {
            if (!deferEnabled || promoted) {
                ensureSession()
                session?.encode(pcm, len)
                return
            }
            // 보류: 프레임 사본 보관(원본 pcm 버퍼는 다음 루프에서 덮어쓰므로 복사 필수)
            pending.add(pcm.copyOf(len))
            pendingSamples += len
            // 기준 길이는 매번 다시 읽어 설정 변경을 즉시 반영.
            // 화면 길이 표시가 초 내림(floor)이라, 'N초 이하로 보이는' 구간(=실제 (N+1)초 미만)은
            // 모두 버린다. 즉 (N+1)초 이상 모인 순간에만 인코딩을 시작해 최소 표시가 (N+1)초가 되게 한다.
            val keepMs = (Prefs.getMinKeepSec(context) + 1) * 1000L
            val pendingMs = pendingSamples * 1000L / SAMPLE_RATE
            if (pendingMs >= keepMs) promote()
        }

        /** 보류 버퍼가 기준을 넘김 → 인코더 생성 후 모아둔 프레임을 흘려보낸다. */
        private fun promote() {
            ensureSession()
            for (f in pending) session?.encode(f, f.size)
            pending.clear()
            promoted = true
        }

        private fun ensureSession() {
            if (session == null) {
                // 새 녹음 시작 전 저장공간 확보 + WakeLock 확보
                val minFree = Prefs.getMinFreeMb(context) * 1024 * 1024
                Storage.ensureFreeSpace(context, minFree, Prefs.getProtected(context))
                acquireWakeLock()
                session = EncoderSession(newFile(hourKey))
            }
        }

        /** 구간 종료. 인코딩이 시작됐으면 마무리, 보류만 남았으면(기준 미달) 그냥 폐기. */
        fun finish() {
            session?.finish()
            session = null
            pending.clear()
        }
    }

    private fun computeRms(buf: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / len)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HelloRecorder::EncodeLock"
            )
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire(HOUR_MS)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun hourKey(): String =
        SimpleDateFormat("yyyyMMdd_HH", Locale.US).format(Date())

    private fun newFile(hourKey: String): File {
        // 오늘 날짜 폴더 안에 생성. 같은 시간대에 끊겼다 다시 시작하면 _2, _3...
        val dir = Storage.dayDir(context)
        var f = File(dir, "$hourKey.m4a")
        while (f.exists()) {
            // 파일명에 인덱스 추가
            val base = hourKey
            var idx = 2
            f = File(dir, "${base}_$idx.m4a")
            while (f.exists()) {
                idx++
                f = File(dir, "${base}_$idx.m4a")
            }
        }
        return f
    }

    /**
     * PCM → AAC 인코딩 후 m4a(MP4 컨테이너)로 저장하는 한 세션.
     */
    private inner class EncoderSession(file: File) {
        private val codec: MediaCodec
        private val muxer: MediaMuxer
        private var trackIndex = -1
        private var muxerStarted = false
        private val bufferInfo = MediaCodec.BufferInfo()
        private var ptsUs = 0L

        // 목소리 강조(Pro)가 켜져 있으면 GTCRN 잡음 제거기도 함께 사용(공용 enhancer 를 구간용으로 reset)
        private val denoiser: SpeechEnhancer? =
            if (emphasisOn()) ensureEnhancer()?.also { it.reset() } else null

        init {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE)
                setInteger(MediaFormat.KEY_BIT_RATE, Prefs.getBitRate(context))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        fun encode(pcm: ShortArray, len: Int) {
            val d = denoiser
            if (d != null) {
                // 잡음 제거 후 인코딩 (출력은 hop 단위로 나옴; 빈 결과면 아직 버퍼링 중)
                val out = d.process(pcm, len)
                if (out.isNotEmpty()) encodeRaw(out, out.size)
            } else {
                encodeRaw(pcm, len)
            }
        }

        private fun encodeRaw(pcm: ShortArray, len: Int) {
            // ShortArray → ByteArray (little endian)
            val bytes = ByteArray(len * 2)
            for (i in 0 until len) {
                bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = (pcm[i].toInt() shr 8 and 0xFF).toByte()
            }
            feed(bytes)
            drain(false)
        }

        private fun feed(data: ByteArray) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inBuf: ByteBuffer = codec.getInputBuffer(inIndex)!!
                inBuf.clear()
                inBuf.put(data)
                codec.queueInputBuffer(inIndex, 0, data.size, ptsUs, 0)
                // 16bit mono 기준 샘플당 시간 누적
                ptsUs += (data.size.toLong() / 2) * 1_000_000L / SAMPLE_RATE
            }
        }

        private fun drain(endOfStream: Boolean) {
            if (endOfStream) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    codec.queueInputBuffer(
                        inIndex, 0, 0, ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
            }
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> break // INFO_TRY_AGAIN_LATER
                }
            }
        }

        fun finish() {
            try {
                // 잡음 제거기에 남은 꼬리 샘플을 마저 인코딩
                denoiser?.let {
                    val tail = it.flush()
                    if (tail.isNotEmpty()) encodeRaw(tail, tail.size)
                }
                drain(true)
                codec.stop()
                codec.release()
                if (muxerStarted) {
                    muxer.stop()
                }
                muxer.release()
            } catch (_: Exception) {
            }
        }
    }
}
