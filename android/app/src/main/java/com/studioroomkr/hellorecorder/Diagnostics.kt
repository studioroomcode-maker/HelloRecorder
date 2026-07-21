package com.studioroomkr.hellorecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 진단 요약 — 앱/기기 상태를 사람이 읽을 수 있는 한 장으로 모은다.
 *
 * 목적: OEM 별 문제 신고·인수인계 때, 어떤 설정·권한·저장 상태에서 문제가 났는지 한 번에
 * 파악하게 한다. 순수 로컬 정보만 담고 녹음 내용·위치 좌표 등 민감 정보는 넣지 않는다
 * (기기 모델·OS·설정값·개수만). 사용자가 명시적으로 공유할 때만 기기 밖으로 나간다.
 */
object Diagnostics {

    private fun perm(ctx: Context, p: String): String =
        if (ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED)
            "허용" else "거부"

    private fun yesNo(b: Boolean) = if (b) "예" else "아니오"

    private fun mib(bytes: Long) = "%.0f MB".format(bytes / (1024.0 * 1024.0))

    /** 진단 텍스트 한 장. I18n 을 타지 않고 라벨을 그대로 둔다(신고·인수인계용 원문 고정). */
    fun report(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== HelloRecorder 진단 ===")
        sb.appendLine("앱 버전: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("기기: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        sb.appendLine("[녹음 상태]")
        sb.appendLine("- 켜둠(의도): ${yesNo(Prefs.isRecordingEnabled(ctx))}")
        sb.appendLine("- 실제 동작 중: ${yesNo(RecordingService.isRunning())}")
        sb.appendLine("- 위치 게이팅: ${AudioEngine.locState}")
        sb.appendLine()

        sb.appendLine("[권한]")
        sb.appendLine("- 마이크: ${perm(ctx, Manifest.permission.RECORD_AUDIO)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sb.appendLine("- 알림: ${perm(ctx, Manifest.permission.POST_NOTIFICATIONS)}")
        }
        sb.appendLine("- 위치(정밀): ${perm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)}")
        sb.appendLine()

        sb.appendLine("[저장]")
        sb.appendLine("- 위치: ${Storage.currentRootPath(ctx)}")
        sb.appendLine("- 여유 공간: ${mib(Storage.freeBytes(ctx))}")
        sb.appendLine("- SD카드 있음: ${yesNo(Storage.hasSdCard(ctx))}")
        sb.appendLine("- 보관 기간: ${Prefs.getRetentionHours(ctx)}시간")
        sb.appendLine()

        sb.appendLine("[설정]")
        sb.appendLine("- 프리셋: ${Presets.currentId(ctx) ?: "사용자 지정"}")
        sb.appendLine("- 무음 기준: ${Prefs.getThreshold(ctx).toInt()}")
        sb.appendLine("- 목소리 우선: ${yesNo(Prefs.isVoiceModeEnabled(ctx))} / Silero: ${yesNo(Prefs.isSileroEnabled(ctx))}")
        sb.appendLine("- 구간 분리 간격: ${Prefs.getMergeGapSec(ctx)}초")
        sb.appendLine("- 짧은 녹음 컷: ${yesNo(Prefs.isMinKeepEnabled(ctx))} (${Prefs.getMinKeepSec(ctx)}초)")
        sb.appendLine("- 앱 잠금: ${yesNo(Prefs.isAppLockEnabled(ctx))}")
        sb.appendLine()

        sb.appendLine("[전사(STT)]")
        sb.appendLine("- 자동 전사: ${yesNo(Prefs.isTranscribeEnabled(ctx))}")
        sb.appendLine("- 모델 설치됨: ${yesNo(SttModel.isAvailable(ctx))}")
        sb.appendLine("- 다운로드 중: ${yesNo(SttModel.isDownloading(ctx))}")
        sb.appendLine("- 검색 인덱스 파일 수: ${indexedCount(ctx)}")
        sb.appendLine()

        sb.appendLine("[신뢰성]")
        sb.appendLine("- 인코딩 실패 누적: ${Prefs.getEncodeFailCount(ctx)}")
        sb.appendLine("- 저장된 크래시 로그: ${CrashLogger.count(ctx)}개")
        return sb.toString()
    }

    /**
     * 음성 엔진이 실제로 뜨는지 한 번 만들어 보고 결과를 적는다. 실패면 이유까지.
     *
     * ⚠️ report() 와 분리한 이유: 이 점검은 ONNX 인식기를 실제로 **생성**하므로 무겁다
     * (STT 모델은 로드에 수백 ms + 네이티브 힙). report() 는 앱 시작 시 설정 화면을 그리며
     * 즉시 호출되는데, 거기에 이 무거운 초기화를 끼워 넣었다가 앱 시작이 막혔다.
     * 그래서 사용자가 '음성 엔진 점검' 버튼을 누를 때만, 백그라운드 스레드에서 돌린다.
     */
    fun engineReport(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("[음성 엔진 실제 로드]")
        sb.appendLine("- 목소리 강조(GTCRN): ${enhancerStatus(ctx)}")
        sb.appendLine("- 전사기(STT): ${transcriberStatus(ctx)}")
        return sb.toString()
    }

    private fun enhancerStatus(ctx: Context): String {
        val e = SpeechEnhancer.create(ctx)
        return if (e != null) {
            e.close()
            "정상"
        } else {
            "실패 — ${SpeechEnhancer.lastCreateError ?: "원인 불명"}"
        }
    }

    private fun transcriberStatus(ctx: Context): String {
        if (!SttModel.isAvailable(ctx)) return "모델 없음(미설치)"
        val t = Transcriber.create(ctx)
        return if (t != null) {
            t.close()
            "정상"
        } else {
            "실패 — ${Transcriber.lastCreateError ?: "원인 불명"}"
        }
    }

    private fun indexedCount(ctx: Context): Int =
        try { TranscriptStore.indexedFileCount(ctx) } catch (_: Exception) { -1 }
}
