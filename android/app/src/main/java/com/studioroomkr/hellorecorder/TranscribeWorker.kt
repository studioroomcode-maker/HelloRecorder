package com.studioroomkr.hellorecorder

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 자동 전사 배치 작업 — '충전 중 + 배터리 여유' 일 때만 돈다.
 *
 * 절전 원칙: STT 는 무겁다(모델 로드 ~1.2초 + 네이티브 힙 ~164MB). 그래서 상시 녹음
 * 경로에는 절대 붙이지 않고, 어차피 전원이 꽂힌 시간에 미전사 파일을 몰아서 처리한다.
 * 실측 RTF 0.035(S25U) 기준 10분 예산이면 약 4시간 분량의 오디오를 소화한다 —
 * 하룻밤 충전이면 하루치 녹음 전체가 무리 없이 전사된다.
 *
 * 모델(~127MB)은 {getExternalFilesDir}/stt-model/ 에 있어야 하며, 없으면 조용히 통과.
 */
class TranscribeWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        // Pro 전용 + 설정 켜짐 + 모델 존재. 아니면 아무것도 하지 않는다(성공 처리 — 재시도 불필요).
        if (!Pro.isPro || !Prefs.isTranscribeEnabled(ctx)) return Result.success()
        // 모델 다운로드가 끝나 있는데 아직 설치(이동)가 안 됐으면 여기서 마무리
        // (완료 브로드캐스트를 놓쳐도 충전 시점에 자동 설치되도록 하는 안전망)
        if (SttModel.isDownloading(ctx)) SttModel.finalizeIfDone(ctx)
        if (!Transcriber.isModelAvailable(ctx)) return Result.success()

        // DB 가 비어 있으면(재설치·이관) 사이드카에서 인덱스 복구
        TranscriptStore.rebuildIfEmpty(ctx)

        val transcriber = Transcriber.create(ctx) ?: return Result.success()
        val deadline = SystemClock.elapsedRealtime() + BUDGET_MS
        try {
            val now = System.currentTimeMillis()
            // 최신 파일부터 — 사용자가 가장 먼저 찾을 것은 최근 녹음이다.
            for (file in Storage.listAllFiles(ctx)) {
                if (SystemClock.elapsedRealtime() >= deadline) break   // 나머지는 다음 주기에
                if (isStopped) break                                    // 제약 이탈(충전 해제 등)
                if (!RecordingLogic.needsTranscript(
                        TranscriptStore.hasTranscript(file), file.lastModified(), now, GUARD_MS
                    )
                ) continue
                try {
                    TranscriptStore.write(ctx, file, transcriber.transcribe(file))
                } catch (_: Throwable) {
                    // 깨진 파일/일시 오류 — 이 파일만 건너뛰고 계속. 사이드카를 남기지 않으므로
                    // 다음 주기에 자연 재시도되고, 진짜 깨진 파일은 CleanupWorker 가 정리한다.
                }
            }
        } finally {
            transcriber.close()   // 네이티브 힙(~164MB) 즉시 반납
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "auto_transcribe"
        private const val WORK_NAME_NOW = "auto_transcribe_now"   // 디버그 즉시 실행(주기 작업과 분리)
        // 회당 처리 예산. WorkManager 의 작업 실행 한도(10분) 안에서 여유를 둔다.
        private const val BUDGET_MS = 8 * 60 * 1000L
        // 최근 수정 가드 — 아직 이어서 녹음 중일 수 있는 파일 제외 (빈 파일 정리 가드와 동일 취지)
        private const val GUARD_MS = 2 * 60 * 1000L

        /** 앱 시작 시 호출. 충전 중 + 배터리 여유일 때 2시간 주기로 미전사 파일을 처리. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TranscribeWorker>(
                2, TimeUnit.HOURS
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * 즉시 1회 실행 — 개발 중 확인용(디버그 빌드에서만 노출).
         *
         * 정규 경로는 '충전 중 + 배터리 여유 + 2시간 주기'라 실기기에서 지금 당장 확인하기가
         * 어렵다. 그래서 **제약만 빼고** 같은 워커를 한 번 돌린다. 워커 본체의 게이트
         * (Pro·설정 토글·모델 존재)는 그대로 지나므로 확인하는 경로가 실제 경로와 같다.
         *
         * 정규 주기 작업과 이름을 분리해, 이 실행이 다음 주기 스케줄을 흔들지 않게 한다.
         */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TranscribeWorker>().build()
            )
        }

        /** runNow() 의 진행 상태. 디버그 화면이 완료를 알리는 데 쓴다. */
        fun observeRunNow(context: Context): LiveData<List<WorkInfo>> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(WORK_NAME_NOW)
    }
}
