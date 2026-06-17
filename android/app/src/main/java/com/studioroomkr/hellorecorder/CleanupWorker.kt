package com.studioroomkr.hellorecorder

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 보관 기간(기본 48시간, 설정에서 조절) 지난 녹음 파일을 삭제하는 주기 작업.
 *  - 보호 표시된 파일은 삭제하지 않음
 *  - 비어 버린 날짜 폴더는 함께 정리
 * WorkManager 가 기기가 어차피 깨어있을 때 모아서 실행 → 추가 기상 최소화(절전).
 */
class CleanupWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val retentionMs = Prefs.getRetentionHours(ctx) * 60L * 60 * 1000
        val cutoff = System.currentTimeMillis() - retentionMs
        val protectedSet = Prefs.getProtected(ctx)

        for (file in Storage.listAllFiles(ctx)) {
            val key = Storage.relativeKey(ctx, file)
            if (file.lastModified() < cutoff && !protectedSet.contains(key)) {
                file.delete()
            }
        }

        // 빈 날짜 폴더 제거
        Storage.listDayDirs(ctx).forEach { dir ->
            if (dir.listFiles()?.isEmpty() == true) dir.delete()
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "cleanup_old_recordings"

        /** 앱 시작 시 한 번 호출해 6시간 주기 작업 등록 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CleanupWorker>(
                6, TimeUnit.HOURS
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true) // 배터리 낮으면 미루기
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
