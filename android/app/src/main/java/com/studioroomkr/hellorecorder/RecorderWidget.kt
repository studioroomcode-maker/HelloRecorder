package com.studioroomkr.hellorecorder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * 홈화면 위젯 — 한 번 탭으로 녹음 시작/정지 + 현재 상태 표시.
 *
 * 이 클래스(AppWidgetProvider)는 시스템이 APPWIDGET_UPDATE 브로드캐스트를 보내야 하므로
 * 반드시 exported="true" 여야 한다. 그래서 여기서는 **어떤 커스텀 액션도 처리하지 않는다.**
 * 예전에는 ACTION_TOGGLE 을 여기서 받았는데, exported 리시버였던 탓에 아무 권한 없는
 * 제3자 앱이 명시적 브로드캐스트로 사용자의 녹음을 마음대로 켜고 끌 수 있었다.
 *
 * 지금은 위젯 상태에 따라 버튼의 PendingIntent 자체를 다르게 건다:
 *  - Pro 아님  → ProActivity (구매 화면)
 *  - 녹음 중   → WidgetToggleReceiver (exported=false) 로 브로드캐스트 → 정지
 *  - 정지됨    → MainActivity 를 열어 전경에서 시작
 *
 * '시작'만 Activity 를 경유하는 이유: Android 12+ 는 백그라운드에서 포그라운드 서비스를
 * 시작하면 ForegroundServiceStartNotAllowedException 을 던지고, Android 14+ 는
 * microphone 타입 FGS 를 백그라운드에서 아예 시작할 수 없다(while-in-use 권한).
 * 반면 '정지'는 백그라운드에서도 안전하다.
 */
class RecorderWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(context, manager, id)
    }

    companion object {

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RecorderWidget::class.java))
            for (id in ids) updateWidget(context, mgr, id)
        }

        private fun pendingFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            I18n.apply(context)
            // '녹음 중'은 서비스가 실제로 돌 때만 띄운다. 켜 뒀지만 안 도는 상태
            // (Android 14+ 재부팅 후 재개 대기 등)는 따로 보여 줘야 사용자가 속지 않는다.
            val running = RecordingService.isRunning()
            val needsResume = RecordingService.needsResume(context)
            val views = RemoteViews(context.packageName, R.layout.widget_recorder)
            views.setTextViewText(
                R.id.widget_status,
                when {
                    running -> I18n.t("🔴 녹음 중")
                    needsResume -> I18n.t("🟡 탭하여 재개")
                    else -> I18n.t("⚪ 정지됨")
                }
            )
            views.setTextViewText(
                R.id.widget_toggle,
                if (running) I18n.t("■ 정지") else I18n.t("● 시작")
            )

            // requestCode 는 분기마다 다르게 준다. PendingIntent 동등성 판정은 extras 를
            // 무시하므로, 같은 코드를 쓰면 서로 다른 분기가 하나로 뭉개진다.
            val togglePi = when {
                !Prefs.isPro(context) ->
                    PendingIntent.getActivity(
                        context, 2,
                        Intent(context, ProActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        pendingFlags()
                    )

                running ->
                    PendingIntent.getBroadcast(
                        context, 0,
                        Intent(context, WidgetToggleReceiver::class.java)
                            .setAction(WidgetToggleReceiver.ACTION_STOP),
                        pendingFlags()
                    )

                else ->
                    // 정지됨과 '재개 필요' 둘 다 여기로 온다 — 어느 쪽이든 전경에서 시작해야 한다.
                    // 마이크 권한 유무와 무관하게 MainActivity 를 연다. 권한이 없으면
                    // MainActivity 가 권한 요청 흐름을 태우고, 있으면 곧바로 녹음을 시작한다.
                    PendingIntent.getActivity(
                        context, 3,
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra(MainActivity.EXTRA_RESUME_RECORDING, true),
                        pendingFlags()
                    )
            }
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

/**
 * 위젯 '정지' 버튼 전용 리시버. 매니페스트에서 exported="false".
 *
 * PendingIntent 는 우리 앱의 신원으로 전달되므로 대상 리시버가 exported 일 필요가 없다.
 * 덕분에 외부 앱은 이 액션을 흉내 낼 수 없다.
 */
class WidgetToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        RecordingService.stop(context)
        RecorderWidget.updateAll(context)
    }

    companion object {
        const val ACTION_STOP = "com.studioroomkr.hellorecorder.WIDGET_STOP"
    }
}
