package com.studioroomkr.hellorecorder

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/**
 * 홈화면 위젯 — 한 번 탭으로 녹음 시작/정지 + 현재 상태 표시.
 *  - 토글 버튼: 녹음 중이면 정지, 아니면 시작(마이크 권한 없으면 앱을 열어 권한 요청)
 *  - 상태 텍스트 탭: 앱 열기
 * 녹음 상태가 바뀌면 RecordingService 가 updateAll() 로 위젯을 갱신한다.
 */
class RecorderWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            if (!Prefs.isPro(context)) {
                // 홈 위젯은 Pro 전용 → 구매 화면 열기
                context.startActivity(
                    Intent(context, ProActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            if (Prefs.isRecordingEnabled(context)) {
                RecordingService.stop(context)
            } else if (hasMicPermission(context)) {
                RecordingService.start(context)
            } else {
                // 권한이 없으면 앱을 열어 권한 요청 흐름으로
                context.startActivity(
                    Intent(context, SplashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            updateAll(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.studioroomkr.hellorecorder.WIDGET_TOGGLE"

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RecorderWidget::class.java))
            for (id in ids) updateWidget(context, mgr, id)
        }

        private fun hasMicPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        private fun pendingFlags(): Int {
            var f = PendingIntent.FLAG_UPDATE_CURRENT
            f = f or PendingIntent.FLAG_IMMUTABLE
            return f
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            I18n.apply(context)
            val enabled = Prefs.isRecordingEnabled(context)
            val views = RemoteViews(context.packageName, R.layout.widget_recorder)
            views.setTextViewText(
                R.id.widget_status,
                if (enabled) I18n.t("🔴 녹음 중") else I18n.t("⚪ 정지됨")
            )
            views.setTextViewText(
                R.id.widget_toggle,
                if (enabled) I18n.t("■ 정지") else I18n.t("● 시작")
            )

            val togglePi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, RecorderWidget::class.java).setAction(ACTION_TOGGLE),
                pendingFlags()
            )
            views.setOnClickPendingIntent(R.id.widget_toggle, togglePi)

            val openPi = PendingIntent.getActivity(
                context, 1,
                Intent(context, SplashActivity::class.java),
                pendingFlags()
            )
            views.setOnClickPendingIntent(R.id.widget_status, openPi)

            mgr.updateAppWidget(id, views)
        }
    }
}
