package com.studioroomkr.hellorecorder

import android.content.Context

/**
 * 녹음 프리셋 — 임계값·VAD·병합 간격·짧은 녹음 기준을 상황에 맞게 한 번에 맞춘다.
 *
 * 기능이 많아 기본 사용자가 임계값/VAD/병합 간격/짧은 녹음 기준의 차이를 이해하기 어렵다는
 * 지적에 대응한다. 각 프리셋은 그 상황에서 무난한 값 묶음이고, 적용 후에도 개별 설정은
 * 그대로 손볼 수 있다(프리셋은 잠그지 않는다).
 *
 * 값 근거:
 *  - threshold: 낮을수록 예민(작은 소리도 녹음). 회의·강의는 멀리·작게 말해도 잡게 낮춤.
 *  - voiceMode/silero: 사람 목소리만 골라 트리거. 소음 감시는 목소리 아닌 소리도 잡아야 하니 끔.
 *  - mergeGapSec: 무음이 이만큼 이어지면 새 파일로 분리. 말 사이 침묵이 긴 강의는 길게.
 *  - minKeepSec/enabled: 이 길이 이하 녹음은 버림(잡음 컷). 소음 감시는 짧은 소리도 남겨야 하니 끔.
 *  - emphasis: 저장 오디오를 가공(잡음 억제). 가까이 말하는 개인 메모만 켜서 또렷하게.
 */
object Presets {

    data class Preset(
        val id: String,
        val nameKo: String,
        val nameEn: String,
        val threshold: Double,
        val voiceMode: Boolean,
        val silero: Boolean,
        val mergeGapSec: Int,
        val minKeepEnabled: Boolean,
        val minKeepSec: Int,
        val emphasis: Boolean,
    )

    val ALL = listOf(
        // 회의: 여러 사람이 방 안에서, 조용히 말해도 놓치지 않게. 대화 침묵은 5초까지 한 파일.
        Preset("meeting", "회의", "Meeting",
            threshold = 300.0, voiceMode = true, silero = true,
            mergeGapSec = 5, minKeepEnabled = true, minKeepSec = 2, emphasis = false),
        // 강의: 한 사람이 길게. 요점 사이 침묵이 길어 병합 간격을 넉넉히(10초).
        Preset("lecture", "강의", "Lecture",
            threshold = 350.0, voiceMode = true, silero = true,
            mergeGapSec = 10, minKeepEnabled = true, minKeepSec = 3, emphasis = false),
        // 개인 메모: 기기를 가까이 두고 내 목소리로. 또렷하게 강조, 짧게 끊어 저장.
        Preset("memo", "개인 메모", "Personal memo",
            threshold = 500.0, voiceMode = true, silero = true,
            mergeGapSec = 3, minKeepEnabled = true, minKeepSec = 1, emphasis = true),
        // 소음 감시: 목소리가 아닌 소리(문 여닫힘·기계음 등)도 잡아야 한다. 예민하게, 다 남김.
        Preset("noise", "소음 감시", "Noise watch",
            threshold = 150.0, voiceMode = false, silero = false,
            mergeGapSec = 5, minKeepEnabled = false, minKeepSec = 1, emphasis = false),
    )

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    /** 프리셋 값들을 실제 설정에 적용. 이후 개별 조정은 자유. */
    fun apply(ctx: Context, p: Preset) {
        Prefs.setThreshold(ctx, p.threshold)
        Prefs.setVoiceModeEnabled(ctx, p.voiceMode)
        Prefs.setSileroEnabled(ctx, p.silero)
        Prefs.setMergeGapSec(ctx, p.mergeGapSec)
        Prefs.setMinKeepEnabled(ctx, p.minKeepEnabled)
        Prefs.setMinKeepSec(ctx, p.minKeepSec)
        Prefs.setVoiceEmphasisEnabled(ctx, p.emphasis)
    }

    /** 현재 설정이 어느 프리셋과 정확히 일치하면 그 id, 아니면 null(사용자 지정). */
    fun currentId(ctx: Context): String? {
        val th = Prefs.getThreshold(ctx)
        val vm = Prefs.isVoiceModeEnabled(ctx)
        val si = Prefs.isSileroEnabled(ctx)
        val mg = Prefs.getMergeGapSec(ctx)
        val mke = Prefs.isMinKeepEnabled(ctx)
        val mks = Prefs.getMinKeepSec(ctx)
        val em = Prefs.isVoiceEmphasisEnabled(ctx)
        return ALL.firstOrNull {
            it.threshold == th && it.voiceMode == vm && it.silero == si &&
                it.mergeGapSec == mg && it.minKeepEnabled == mke &&
                it.minKeepSec == mks && it.emphasis == em
        }?.id
    }
}
