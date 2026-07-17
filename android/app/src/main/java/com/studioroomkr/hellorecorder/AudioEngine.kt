package com.studioroomkr.hellorecorder

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
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
        // 저장 오디오 럼블 컷(하이패스) 컷오프 — 이 주파수 이하 저역(웅웅거림: 에어컨·바람·진동·취급
        // 소음)을 줄여 목소리를 또렷하게. 모든 녹음에 항상 적용(목소리 강조 여부와 무관).
        private const val RUMBLE_CUT_HZ = 110.0
        // 1시간마다 파일 교체
        private const val HOUR_MS = 60 * 60 * 1000L
        // 배터리 표본 주기 (위치 확인 간격은 Prefs 에서 동적으로 읽음)
        private const val BATT_SAMPLE_MS = 2 * 60 * 1000L
        private const val LOC_MAX_AGE_MS = 10 * 60 * 1000L

        /**
         * 위치 게이팅의 현재 상태. UI 가 읽어 사용자에게 보여 준다.
         * RecordingService.isRunning() 과 같은 이유로 메모리 값이다(엔진이 죽으면 같이 사라진다).
         */
        enum class LocState {
            OFF,            // 위치 기능 꺼짐 — 게이팅 안 함
            ALLOWED,        // 위치 확인됨, 녹음 허용 구역
            BLOCKED_ZONE,   // 위치 확인됨, 사용자가 지정한 대로 녹음 금지 구역
            NO_PERMISSION,  // 위치 권한 없음 → 판정 불가
            NO_FIX,         // 위치를 못 읽거나 너무 오래됨 → 판정 불가
        }

        @Volatile
        var locState: LocState = LocState.OFF
            private set

        // 캡처 중 임계 하향 비율(히스테리시스) — 이어지는 작은 말을 같은 구간으로 잡음.
        // 낮출수록 예민(0.4 = 시작 임계의 40%). 기기 테스트로 조정.
        private const val CONT_RATIO = 0.4
    }

    // 위치 게이팅 결과 (true = 녹음 허용).
    //
    // 판정 불가(권한 없음/위치 모름)일 때는 **막는다**(fail-closed). 예전엔 허용했는데,
    // '이 구역에서만 녹음'을 켠 사용자에게는 정확히 반대 동작이라 단순 오작동이 아니라
    // 프라이버시 문제였다 — 지정한 곳을 벗어나도, 권한을 껐어도 계속 녹음됐다.
    // '이 구역에선 녹음 금지'도 마찬가지로, 모르면 녹음하지 않는 쪽이 안전하다.
    @Volatile private var locationAllowed = true

    private fun sampleBattery() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        Prefs.recordBatterySample(context, level)
    }

    private fun sampleLocation() {
        // 기능이 꺼져 있거나 구역이 하나도 없으면 게이팅할 게 없다 → 그냥 녹음.
        if (!Prefs.isLocationEnabled(context) || Prefs.getZones(context).isEmpty()) {
            locState = LocState.OFF
            locationAllowed = true
            return
        }

        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            // 권한이 회수된 경우. 판정할 수 없으니 막고, UI 가 이유를 보여 준다.
            locState = LocState.NO_PERMISSION
            locationAllowed = false
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            locState = LocState.NO_FIX
            locationAllowed = false
            return
        }
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
            locState = LocState.NO_FIX
            locationAllowed = false
            return
        }
        locationAllowed = Prefs.isLocationAllowed(context, b.latitude, b.longitude)
        locState = if (locationAllowed) LocState.ALLOWED else LocState.BLOCKED_ZONE
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun start() {
        if (running) return
        running = true
        // 첫 판정 전까지는 아직 아무것도 모른다 — 켜 있는 동안만 유효한 값이라 여기서 초기화한다.
        locState = LocState.OFF
        thread = Thread { loop() }.apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(3_000)
        thread = null
        releaseWakeLock()
        locState = LocState.OFF
    }

    // 목소리 강조용 오디오 효과 (NS: 정상 소음 억제 — GTCRN 로드 실패 시 폴백으로만 연결)
    // AGC(자동이득)는 의도적으로 쓰지 않는다: 주 화자가 조용할 때 게인을 끌어올려
    // 멀리 있는 사람 목소리·소음까지 같이 키워 거리감을 없애고 대화를 뭉갠다.
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

    // '사람 목소리 우선'은 무료 개방(핵심 차별점 체험). 정밀 확인(Silero)·목소리 강조·먼 소리 줄이기는 Pro 전용.
    private fun voiceModeOn() = Prefs.isVoiceModeEnabled(context)
    private fun emphasisOn() = Pro.isPro && Prefs.isVoiceEmphasisEnabled(context)
    // '먼 소리 줄이기': 잡음 제거 없이 근접 우선 익스팬더만 적용(원음 질감 유지)
    private fun distanceOn() = Pro.isPro && Prefs.isDistanceReduceEnabled(context)

    @Suppress("MissingPermission")
    private fun createRecord(bufSize: Int): AudioRecord {
        // 근접 우선: 강조 시에도 일반 MIC 를 쓴다. VOICE_RECOGNITION 은 기기 DSP 가 레벨을
        // 평탄화해 거리감(가까운 소리가 더 큼)을 없애고 멀리 있는 목소리까지 끌어올리기 때문.
        val source = MediaRecorder.AudioSource.MIC
        val rec = AudioRecord(source, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize)
        // 마이크 점유·권한 회수·기기별 초기화 실패 시 STATE_INITIALIZED 가 아니다.
        // 검증 없이 startRecording() 하면 IllegalStateException 으로 녹음 스레드가 죽고
        // 서비스만 '녹음 중' 으로 남는다 → 여기서 정리 후 예외를 던져 상위에서 처리한다.
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Exception) {}
            throw IllegalStateException("AudioRecord not initialized (state=${rec.state})")
        }
        attachEffects(rec.audioSessionId)
        return rec
    }

    /**
     * AudioRecord 생성·검증·시작까지 한 번에. 실패하면 자원을 정리하고 예외를 던진다.
     * 호출부는 예외 시 실패를 기록하고 서비스를 안전 종료해 허위 '녹음 중' 알림을 없앤다.
     */
    private fun openRecord(bufSize: Int): AudioRecord {
        val rec = createRecord(bufSize)   // STATE_INITIALIZED 검증 포함(실패 시 throw)
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord failed to start (state=${rec.recordingState})")
            }
        } catch (e: Exception) {
            releaseEffects()
            try { rec.release() } catch (_: Exception) {}
            throw e
        }
        return rec
    }

    /**
     * 목소리 강조일 때 노이즈서프레서를 세션에 연결. AGC는 쓰지 않음.
     * GTCRN(신경망 잡음 제거)이 로드되면 기기 NS는 붙이지 않는다 — NS 가 스펙트럼을 먼저
     * 뭉개면 GTCRN 입력이 학습 분포(원본+잡음)에서 벗어나고, 잡음 제거가 이중으로 걸려
     * 목소리가 뭉개진다(워블). NS 는 GTCRN 로드 실패 시의 폴백으로만 쓴다.
     */
    private fun attachEffects(sessionId: Int) {
        releaseEffects()
        if (!emphasisOn()) return
        if (ensureEnhancer() != null) return
        try {
            if (NoiseSuppressor.isAvailable())
                ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        } catch (_: Exception) {}
    }

    private fun releaseEffects() {
        try { ns?.release() } catch (_: Exception) {}
        ns = null
    }

    /** 통화(셀룰러/VoIP) 중인지 — AudioManager 모드로 판단(권한 불필요). */
    private fun isInCall(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val mode = am.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        // 한 번 read 로 ~0.5초(8000샘플)를 받는다 → CPU 가 깨어나는 횟수를 줄여 절전.
        // (16bit mono: 0.5s = 16000*0.5 샘플 = 16000바이트 = SAMPLE_RATE)
        val bufSize = maxOf(minBuf, SAMPLE_RATE)

        val pcm = ShortArray(bufSize / 2)
        var record = try {
            openRecord(bufSize)
        } catch (e: Exception) {
            // 마이크 초기화/시작 실패 → 실패를 가시화하고 서비스를 안전 종료.
            // (서비스가 '녹음 중' 알림만 띄운 채 실제로는 무음 녹음하는 상태를 방지)
            Prefs.recordEncodeFailure(context)
            RecordingService.stop(context)
            return
        }

        var capture: Capture? = null
        var lastSoundTime = 0L
        var currentHourKey = hourKey()
        var lastBattSample = 0L
        var lastLocSample = 0L
        var wasInCall = false
        var currentEmphasis = emphasisOn()
        val voiceVad = VoiceVad(context)
        var fatal = false

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
                    record = try { openRecord(bufSize) } catch (e: Exception) {
                        Prefs.recordEncodeFailure(context); RecordingService.stop(context); break
                    }
                }

                // 목소리 강조 토글이 바뀌면 소스/효과가 바뀌므로 레코더 재생성
                val emphasis = emphasisOn()
                if (emphasis != currentEmphasis) {
                    currentEmphasis = emphasis
                    if (capture != null) { capture.finish(); capture = null; releaseWakeLock() }
                    try { record.stop(); record.release() } catch (_: Exception) {}
                    record = try { openRecord(bufSize) } catch (e: Exception) {
                        Prefs.recordEncodeFailure(context); RecordingService.stop(context); break
                    }
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
                val baseThreshold = Prefs.getThreshold(context)
                // 히스테리시스: 음성 우선 모드에서 이미 캡처 중이면 임계를 낮춰(40%),
                // 크게 시작한 문장의 뒷부분이 작아져도 끊지 않고 이어 잡는다.
                // 낮춘 임계로 잡히는 소리도 VAD 를 통과해야 하므로(음성만), 선풍기 같은
                // 지속 잡음 때문에 캡처가 무한정 길어지지는 않는다. 음성 우선이 아닐 때는
                // 잡음을 거를 수단이 없어 적용하지 않는다(파일이 한없이 길어지는 것 방지).
                val threshold = if (voiceMode && capture != null)
                    RecordingLogic.continuationThreshold(baseThreshold, true, CONT_RATIO)
                else baseThreshold
                // 음성 우선 모드: 음성 대역(대략 250~3800Hz) 에너지로 판단 → 작은 목소리에
                // 민감하고, 저주파 웅웅거림(차·에어컨)·고주파 히스 같은 비음성 소음은 무시.
                //
                // 배터리: 밴드패스(Butterworth Q=0.707)는 이득이 어디서나 ≤ 1이라 '대역 RMS ≤ 전체 RMS'.
                // 따라서 전체 RMS가 임계 미만이면 대역도 반드시 미만 → 무음 확정. 이때는 비싼
                // 밴드패스/VAD 를 아예 돌리지 않는다(무음 구간이 대부분이라 상시 절전 효과가 큼).
                var detect = rms
                val hasSound = if (voiceMode) {
                    if (rms < threshold) {
                        false   // 전체 에너지부터 임계 미만 → 무음 확정(무거운 검출 생략)
                    } else {
                        // 임계 이상일 때만: 그 에너지가 '음성 대역'인지 확인 →
                        //   1차 WebRTC VAD(가벼움) → 통과 시에만 2차 Silero VAD(정확, 무거움).
                        //   각 단계는 사용 불가하면 통과로 간주(폴백).
                        detect = voiceVad.bandRms(pcm, read)
                        var ok = detect >= threshold
                        if (ok) ok = voiceVad.isSpeechWebRtc(pcm, read) ?: true
                        // Silero 2차 정밀 확인은 Pro 전용(무료는 WebRTC 1차까지만)
                        if (ok && Pro.isPro && Prefs.isSileroEnabled(context)) {
                            ok = voiceVad.isSpeechSilero(pcm, read) ?: true
                        }
                        ok
                    }
                } else {
                    rms >= threshold
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
                    capture.feed(pcm, read, detect, voiceMode && hasSound)
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
                        capture.feed(pcm, read, detect, false)   // 중간의 작은 소리는 보존(음성 아님)
                    }
                    // rms < ZERO_EPS (완전 무음)이면 그 프레임만 건너뜀
                }
            }
        } catch (_: Throwable) {
            // 최후 방어선. 오디오 스레드의 미처리 예외는 기본 핸들러가 프로세스를 통째로 죽인다
            // (CrashLogger 는 기록만 하고 시스템 기본 처리로 넘긴다).
            // 여기서 잡아 실패를 기록하고, 아래에서 서비스를 정상 종료해 '녹음 중' 알림이
            // 거짓말하지 않게 한다.
            Prefs.recordEncodeFailure(context)
            fatal = true
        } finally {
            try { capture?.finish() } catch (_: Throwable) {}
            // record 가 이미 정리됐을 수 있어(재오픈 실패 경로) 개별 try 로 감싼다.
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
            releaseEffects()
            voiceVad.close()
            try { enhancer?.close() } catch (_: Throwable) {}
            enhancer = null; enhancerInit = false
            releaseWakeLock()
        }
        // 치명적 오류로 루프가 끝났다면 서비스를 내려 알림/위젯 상태를 실제와 맞춘다.
        if (fatal) {
            running = false
            RecordingService.stop(context)
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
     * '근접 우선' 다운워드 익스팬더.
     * 신호 포락선(상승 빠름/하강 느림)으로 세기를 추적해, 약한(멀리 있는) 구간의 게인을
     * 낮춰 가까운 목소리가 앞서게 한다. 시간영역 게인만 조절하고 스펙트럼은 건드리지
     * 않으므로 잡음 제거처럼 음색이 변하지 않는다.
     *
     * 뭉개짐(문장 안 볼륨 요동) 방지 설계:
     *  - 포락선 릴리스 ~400ms: 음절 사이·단어 안의 짧은 에너지 골짜기에서 임계 아래로
     *    떨어지지 않는다(예전 ~12.5ms 릴리스가 문장 안 게인 요동의 원인이었다).
     *  - 게인은 복귀(상승)는 빠르게, 감쇠(하강)는 천천히 — 가까운 화자가 다시 말하면
     *    즉시 돌아오고, 먼 소리로의 감쇠는 서서히 걸려 펌핑이 들리지 않는다.
     *  - 임계는 고정값 대신 최근 '가까운 소리' 피크(ref)를 따라가는 적응형 — 기기별
     *    마이크 감도 차이를 흡수한다(하한 MIN_THRESHOLD).
     */
    private class ProximityGate {
        private var env = 0.0
        private var ref = 0.0
        private var gain = 1.0
        fun process(buf: ShortArray, len: Int) {
            for (i in 0 until len) {
                val x = buf[i].toDouble()
                val a = kotlin.math.abs(x)
                // 포락선: 상승은 빠르게(작은 KEEP), 하강은 느리게(큰 KEEP)
                val keep = if (a > env) ATTACK_KEEP else RELEASE_KEEP
                env = a + (env - a) * keep
                // 기준 레벨: 상승은 즉시, 하강은 수십 초 — 최근 가까운 화자의 크기를 기억
                ref = if (env > ref) env else ref * REF_DECAY
                val threshold = RecordingLogic.proximityThreshold(ref, REF_RATIO, MIN_THRESHOLD)
                val target = RecordingLogic.proximityGain(env, threshold, FLOOR)
                gain += (target - gain) * (if (target > gain) GAIN_UP else GAIN_DOWN)
                val y = x * gain
                buf[i] = when {
                    y > 32767.0 -> 32767
                    y < -32768.0 -> -32768
                    else -> Math.round(y).toInt().toShort()
                }
            }
        }
        companion object {
            // 시정수는 16kHz 샘플 기준(keep ≈ exp(-1/(τ·fs))). 상수는 기기 테스트(청취)로 조정.
            //
            // 튜닝 가이드:
            //  - 먼 소리 감쇠가 약하다 → REF_RATIO 를 올린다(0.35→0.45…). 임계가 최근 피크에
            //    더 가까워져 더 많은(덜 약한) 소리까지 감쇠 대상이 된다.
            //  - 감쇠가 과하다(먼 소리가 너무 안 들림) → FLOOR 를 올린다(0.35→0.5…).
            //    감쇠 시작점은 그대로 두고 최대 감쇠 깊이만 얕아져 부작용이 적다.
            //  - 뭉개짐(문장 안 볼륨 요동)이 재발하면 GAIN_DOWN 을 줄이거나 RELEASE_KEEP 을
            //    키워(릴리스 연장) 게인 변화를 더 늦춘다.
            private const val MIN_THRESHOLD = 1000.0  // 적응 임계 하한(조용한 방 과감쇠 방지)
            private const val REF_RATIO = 0.35        // 임계 = 최근 피크의 35%(약 -9dB부터 감쇠 시작)
            private const val REF_DECAY = 0.999997    // ref 하강 ~20초 — 화자 교체에 서서히 적응
            private const val FLOOR = 0.35            // 최대 감쇠(완전 무음 방지 — 약 -9dB)
            private const val ATTACK_KEEP = 0.2       // env 상승은 즉시 추적(온셋 보존)
            private const val RELEASE_KEEP = 0.99984  // env 하강 ~400ms — 음절 골짜기 무시
            private const val GAIN_UP = 0.02          // 게인 복귀 ~3ms
            private const val GAIN_DOWN = 0.0005      // 게인 감쇠 ~125ms — 펌핑 방지
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
        private var spaceFailReported = false   // 공간확보 실패 중복 보고 방지(구간당 1회)
        // 인코더 생성/인코딩이 복구 불가능하게 실패한 구간. 이후 프레임은 조용히 버린다.
        // (다음 소리 구간에서 Capture 가 새로 만들어지므로 녹음 자체는 계속된다.)
        private var sessionFailed = false

        // 활동 프로필(파형·음성 강조용): 인코딩되는 청크마다 1바이트(bit7 음성, bits0-6 음량)
        private val activity = java.io.ByteArrayOutputStream()

        /**
         * 인코딩 대상 프레임 투입. 보류/실시간을 자동 선택.
         * level=음량(RMS), voice=음성 감지 여부 → 사이드카 프로필로 기록(재발견 UX).
         */
        fun feed(pcm: ShortArray, len: Int, level: Double, voice: Boolean) {
            activity.write(packActivity(level, voice))
            if (sessionFailed) return
            if (!deferEnabled || promoted) {
                ensureSession()
                val s = session ?: return
                encodeGuarded { s.encode(pcm, len) }
                return
            }
            // 보류: 프레임 사본 보관(원본 pcm 버퍼는 다음 루프에서 덮어쓰므로 복사 필수)
            pending.add(pcm.copyOf(len))
            pendingSamples += len
            // 기준 길이는 매번 다시 읽어 설정 변경을 즉시 반영.
            // 화면 길이 표시가 초 내림(floor)이라, 'N초 이하로 보이는' 구간(=실제 (N+1)초 미만)은
            // 모두 버린다. 즉 (N+1)초 이상 모인 순간에만 인코딩을 시작해 최소 표시가 (N+1)초가 되게 한다.
            val keepMs = RecordingLogic.minKeepThresholdMs(Prefs.getMinKeepSec(context))
            val pendingMs = pendingSamples * 1000L / SAMPLE_RATE
            if (pendingMs >= keepMs) promote()
        }

        /** 보류 버퍼가 기준을 넘김 → 인코더 생성 후 모아둔 프레임을 흘려보낸다. */
        private fun promote() {
            ensureSession()
            val s = session
            if (s != null) encodeGuarded { for (f in pending) s.encode(f, f.size) }
            pending.clear()
            promoted = true
        }

        /**
         * MediaCodec 은 재생 중 CodecException 을 던질 수 있다(코덱 리셋·리소스 회수 등).
         * 오디오 스레드에서 예외가 새어나가면 기본 예외 핸들러가 **프로세스를 죽인다.**
         * 여기서 잡아 구간만 폐기하고 상시 녹음은 계속되게 한다.
         */
        private inline fun encodeGuarded(block: () -> Unit) {
            try {
                block()
            } catch (_: Throwable) {
                session?.abort()   // 자원 해제 + 깨진 파일 삭제 + 실패 카운터 증가
                session = null
                sessionFailed = true
                releaseWakeLock()
            }
        }

        private fun ensureSession() {
            if (session != null || sessionFailed) return
            // 새 녹음 시작 전 저장공간 확보. 보호 파일 때문에 확보에 실패하면(더 지울 게 없음)
            // 인코딩을 시작하지 않는다 — 시작해도 디스크 풀로 인코딩/먹싱이 실패할 뿐이다.
            // 대신 실패를 가시화하고(구간당 1회), WakeLock 도 잡지 않는다.
            val minFree = Prefs.getMinFreeMb(context) * 1024 * 1024
            if (!Storage.ensureFreeSpace(context, minFree, Prefs.getProtected(context))) {
                if (!spaceFailReported) {
                    Prefs.recordEncodeFailure(context)
                    spaceFailReported = true
                }
                return
            }
            spaceFailReported = false
            acquireWakeLock()
            val target = newFile(hourKey)
            session = try {
                EncoderSession(target)
            } catch (_: Throwable) {
                // 인코더/먹서 생성 실패(디스크 풀, 코덱을 다른 앱이 점유 등).
                // 이 구간은 포기하되 프로세스는 살린다. WakeLock 도 되돌린다.
                try { if (target.exists()) target.delete() } catch (_: Exception) {}
                Prefs.recordEncodeFailure(context)
                releaseWakeLock()
                sessionFailed = true
                null
            }
        }

        /** 구간 종료. 인코딩이 시작됐으면 마무리, 보류만 남았으면(기준 미달) 그냥 폐기. */
        fun finish() {
            val s = session
            // 파일이 실제로 생성된 구간만 사이드카 기록(짧아서 폐기된 구간은 파일도 없음)
            if (s != null && activity.size() > 0) {
                Storage.writeActivityProfile(s.file, activity.toByteArray())
            }
            s?.finish()
            session = null
            pending.clear()
        }

        /** 음량(RMS)+음성여부 → 1바이트. bit7=음성, bits0-6=음량(sqrt 압축 0..127). */
        private fun packActivity(level: Double, voice: Boolean): Int {
            val l = (sqrt((level / 32768.0).coerceIn(0.0, 1.0)) * 127).toInt().coerceIn(0, 127)
            return (if (voice) 0x80 else 0) or l
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

    // 매 버퍼(0.5초)마다 호출되므로 포매터를 한 번만 만들어 재사용(루프 단일 스레드 → 안전).
    private val hourKeyFmt = SimpleDateFormat("yyyyMMdd_HH", Locale.US)
    private val reuseDate = Date()
    private fun hourKey(): String {
        reuseDate.time = System.currentTimeMillis()
        return hourKeyFmt.format(reuseDate)
    }

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
    private inner class EncoderSession(val file: File) {
        private val codec: MediaCodec
        private val muxer: MediaMuxer
        private var trackIndex = -1
        private var muxerStarted = false
        private val bufferInfo = MediaCodec.BufferInfo()
        private var ptsUs = 0L
        // 실제 오디오 샘플이 한 번이라도 파일에 기록됐는지 — 빈/깨진 녹음 판별용.
        private var wroteSample = false

        // 목소리 강조(Pro)가 켜져 있으면 GTCRN 잡음 제거기도 함께 사용(공용 enhancer 를 구간용으로 reset)
        private val denoiser: SpeechEnhancer? =
            if (emphasisOn()) ensureEnhancer()?.also { it.reset() } else null

        // 근접 우선 익스팬더 — 멀리 있는(약한) 소리를 눌러 가까운 목소리를 앞세움.
        // '목소리 강조'(잡음 제거 포함) 또는 '먼 소리 줄이기'(익스팬더 단독, Pro)일 때 적용.
        private val proximityGate: ProximityGate? =
            if (emphasisOn() || distanceOn()) ProximityGate() else null

        // 저장 오디오에 항상 거는 럼블 컷 하이패스(저역 웅웅거림 제거) — 목소리 강조와 무관.
        // 가벼운 저역 컷이라 원본 느낌은 유지하면서 에어컨·진동·취급음 같은 웅웅거림만 줄인다.
        // EncoderSession 은 구간마다 새로 생성되므로 필터 상태도 구간마다 자연 초기화된다.
        private val rumbleCut: Biquad = Biquad.highPass(RUMBLE_CUT_HZ, SAMPLE_RATE.toDouble())
        private var hpBuf = ShortArray(0)   // 필터 출력 재사용 버퍼

        init {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE)
                setInteger(MediaFormat.KEY_BIT_RATE, Prefs.getBitRate(context))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            // 코덱을 start() 한 뒤 MediaMuxer 생성자가 던지면(디스크 풀·경로 오류) 코덱이 샌다.
            // 부분 획득한 자원을 풀고 예외를 그대로 올려보낸다 — 잡는 쪽은 ensureSession().
            var c: MediaCodec? = null
            var m: MediaMuxer? = null
            try {
                c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
                m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (t: Throwable) {
                try { c?.release() } catch (_: Exception) {}
                try { m?.release() } catch (_: Exception) {}
                throw t
            }
            codec = c
            muxer = m
        }

        /**
         * 인코딩 도중 복구 불가능한 오류가 났을 때. finish() 와 달리 muxer.stop() 을 시도하지 않는다
         * (트랙이 시작되지 않았거나 코덱이 이미 죽었을 수 있음). 자원만 풀고 깨진 파일을 지운다.
         */
        fun abort() {
            try { codec.release() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { if (file.exists()) file.delete() } catch (_: Exception) {}
            Prefs.recordEncodeFailure(context)
        }

        fun encode(pcm: ShortArray, len: Int) {
            // 1) 럼블 컷: 저역 웅웅거림 제거(항상). 원본 버퍼는 건드리지 않고 사본에 출력.
            val src = applyRumbleCut(pcm, len)
            // 2) 목소리 강조면 GTCRN 잡음 제거까지(출력은 hop 단위; 빈 결과면 아직 버퍼링 중)
            val d = denoiser
            if (d != null) {
                val out = d.process(src, len)
                if (out.isNotEmpty()) {
                    proximityGate?.process(out, out.size)   // 근접 우선
                    encodeRaw(out, out.size)
                }
            } else {
                proximityGate?.process(src, len)   // '먼 소리 줄이기' 단독(잡음 제거 없이)
                encodeRaw(src, len)
            }
        }

        /** 럼블 컷 하이패스 적용 → 사본(hpBuf) 반환. */
        private fun applyRumbleCut(pcm: ShortArray, len: Int): ShortArray {
            if (hpBuf.size < len) hpBuf = ShortArray(len)
            for (i in 0 until len) {
                val y = rumbleCut.process(pcm[i].toDouble())
                hpBuf[i] = when {
                    y > 32767.0 -> 32767
                    y < -32768.0 -> -32768
                    else -> Math.round(y).toInt().toShort()
                }
            }
            return hpBuf
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
            // 입력 버퍼를 얻을 때까지 재시도한다. 예전엔 10ms 안에 못 얻으면 이 PCM 청크를
            // 조용히 버려, 고부하 기기에서 녹음 일부가 소리 없이 누락됐다. 입력 버퍼가 다 찬
            // 건 대개 출력이 밀렸기 때문이라, drain 으로 출력을 빼내면 버퍼가 풀린다.
            // 반복은 넉넉히 상한(≈2초)만 둔다 — 정상 흐름에선 한두 번에 끝나고, 코덱이 완전히
            // 멈춘 극단적 경우에만 상한에 걸려(그때만 옛 동작처럼 드롭) 녹음 스레드 무한정지를 막는다.
            var attempts = 0
            while (true) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf: ByteBuffer = codec.getInputBuffer(inIndex)!!
                    inBuf.clear()
                    inBuf.put(data)
                    codec.queueInputBuffer(inIndex, 0, data.size, ptsUs, 0)
                    // 16bit mono 기준 샘플당 시간 누적
                    ptsUs += (data.size.toLong() / 2) * 1_000_000L / SAMPLE_RATE
                    return
                }
                if (++attempts >= 200) return   // 코덱 정지 등 극단 상황의 안전 탈출
                drain(false)                     // 출력을 빼내 입력 버퍼를 돌려받고 재시도
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
                            wroteSample = true
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> break // INFO_TRY_AGAIN_LATER
                }
            }
        }

        fun finish() {
            var ok = false
            try {
                // 잡음 제거기에 남은 꼬리 샘플을 마저 인코딩
                denoiser?.let {
                    val tail = it.flush()
                    if (tail.isNotEmpty()) {
                        proximityGate?.process(tail, tail.size)
                        encodeRaw(tail, tail.size)
                    }
                }
                drain(true)
                codec.stop()
                codec.release()
                if (muxerStarted) {
                    muxer.stop()
                }
                muxer.release()
                // 정상 종료: 먹서가 시작됐고 실제 샘플이 하나라도 기록된 경우에만 유효한 파일.
                ok = muxerStarted && wroteSample
            } catch (_: Exception) {
                // 인코딩/먹싱 실패 — 자원을 풀고 아래에서 깨진 파일을 정리한다.
                try { codec.release() } catch (_: Exception) {}
                try { muxer.release() } catch (_: Exception) {}
            }
            // 빈/깨진 녹음 정리: 유효 샘플이 없거나 0바이트 파일이면 삭제하고 실패로 기록한다.
            // (이런 파일은 목록에 '--:--' 로 남거나 재생되지 않아 사용자 신뢰를 깬다.)
            if (!ok || !file.exists() || file.length() == 0L) {
                try { if (file.exists()) file.delete() } catch (_: Exception) {}
                Prefs.recordEncodeFailure(context)
            }
        }
    }
}
