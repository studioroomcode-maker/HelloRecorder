package com.studioroomkr.hellorecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 기기 재부팅 후 녹음 서비스를 자동으로 다시 시작.
 * 사용자가 마지막에 "녹음 시작"을 눌러둔 경우에만 재시작한다(Prefs 플래그).
 *
 * Android 14+(targetSdk 34+)에서는 부팅 브로드캐스트에서 마이크 포그라운드 서비스를
 * 직접 시작할 수 없다(RECORD_AUDIO 는 while-in-use 권한). 직접 시작을 시도하면 거부되어
 * RecordingService 가 recordingEnabled 플래그를 꺼버려 사용자의 '켜둠' 의도가 사라진다.
 * 따라서 이 버전 이상에서는 플래그를 그대로 둔 채 '탭하여 재개' 알림을 띄우고,
 * 사용자가 탭하면 전경(Activity)에서 안전하게 재개한다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.isRecordingEnabled(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 재부팅으로 녹음이 끊겼다 — 재개 전까지는 '동작 중'이 아니다. 부팅 전의
                // 시작 시각을 남겨 두면 있지도 않은 녹음의 경과 시간이 표시된다.
                Prefs.setRecordingStartedAt(context, 0L)
                postResumeNotification(context)
                // 알림 권한이 없으면 이 알림은 안 보인다. 그래도 위젯·앱 화면이
                // '탭하여 재개'를 보여 주므로 재개 경로가 완전히 막히지는 않는다.
                RecorderWidget.updateAll(context)
            } else {
                RecordingService.start(context)
            }
        }
        CleanupWorker.schedule(context)
        TranscribeWorker.schedule(context)
    }

    /** 부팅 후 재개를 유도하는 알림. 탭하면 MainActivity 가 전경에서 녹음을 다시 시작한다. */
    private fun postResumeNotification(ctx: Context) {
        I18n.apply(ctx)
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, I18n.t("녹음 재개"),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val tap = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_RESUME_RECORDING, true)
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(I18n.t("녹음 재개"))
            .setContentText(I18n.t("탭하여 녹음을 다시 시작하세요"))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        mgr.notify(NOTIF_ID, n)
    }

    companion object {
        private const val CHANNEL_ID = "resume_channel"
        const val NOTIF_ID = 1002
    }
}
