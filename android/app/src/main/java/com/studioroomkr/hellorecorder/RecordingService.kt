package com.studioroomkr.hellorecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 녹음을 백그라운드에서 계속 살아있게 하는 포그라운드 서비스.
 * 안드로이드는 백그라운드 마이크 사용 시 반드시 포그라운드 서비스 + 알림을 요구한다.
 */
class RecordingService : Service() {

    private var engine: AudioEngine? = null

    override fun onCreate() {
        super.onCreate()
        I18n.apply(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            // 백그라운드 시작 제한 등으로 포그라운드 시작 실패 시 안전하게 종료
            Prefs.setRecordingEnabled(this, false)
            Prefs.setRecordingStartedAt(this, 0L)
            RecorderWidget.updateAll(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (engine == null) {
            // 실제로 녹음(모니터링)이 시작되는 시점 기록
            Prefs.setRecordingStartedAt(this, System.currentTimeMillis())
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            engine = AudioEngine(this, pm).also { it.start() }
        }
        RecorderWidget.updateAll(this)
        // 시스템이 죽여도 다시 시작
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        // 정책(감시앱) 준수: 프라이버시 모드여도 '녹음 중'임을 알림에서 숨기지 않는다.
        // 프라이버시 모드는 화면 캡처 차단·최근앱 가림만 담당하며, 지속 알림은 항상 녹음을 명시한다.
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(I18n.t("녹음 중"))
            .setContentText(I18n.t("소리가 감지될 때만 저장됩니다"))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ : 마이크 타입 명시 (위치는 '사용 중에만'이라 FGS 타입 불필요)
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, I18n.t("녹음 서비스"),
                NotificationManager.IMPORTANCE_LOW // 소리/진동 없이 조용히
            )
            mgr.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIF_ID = 1001

        /**
         * 녹음 서비스 시작. **전경(Activity)에서 호출해야 한다.**
         *
         * Android 12+ 는 백그라운드에서 startForegroundService() 를 부르면 호출 지점에서
         * ForegroundServiceStartNotAllowedException 을 던진다. onStartCommand 의 try/catch 는
         * 서비스 안쪽이라 여기까지 오지 못하므로, 호출 지점에서도 직접 잡아야 프로세스가 죽지 않는다.
         * 실패하면 '녹음 켜짐' 플래그를 되돌려 UI/위젯이 거짓 상태를 보여주지 않게 한다.
         */
        fun start(context: Context) {
            Prefs.setRecordingEnabled(context, true)
            val intent = Intent(context, RecordingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                Prefs.setRecordingEnabled(context, false)
                Prefs.setRecordingStartedAt(context, 0L)
            }
            RecorderWidget.updateAll(context)
        }

        fun stop(context: Context) {
            Prefs.setRecordingEnabled(context, false)
            Prefs.setRecordingStartedAt(context, 0L)
            context.stopService(Intent(context, RecordingService::class.java))
            RecorderWidget.updateAll(context)
        }
    }
}
