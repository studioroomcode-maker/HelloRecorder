package com.studioroomkr.hellorecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 기기 재부팅 후 녹음 서비스를 자동으로 다시 시작.
 * 사용자가 마지막에 "녹음 시작"을 눌러둔 경우에만 재시작한다(Prefs 플래그).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Prefs.isRecordingEnabled(context)) {
                RecordingService.start(context)
            }
            CleanupWorker.schedule(context)
        }
    }
}
