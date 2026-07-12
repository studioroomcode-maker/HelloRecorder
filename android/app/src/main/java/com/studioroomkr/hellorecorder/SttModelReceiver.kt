package com.studioroomkr.hellorecorder

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * STT 모델 다운로드 완료 감지. DownloadManager 가 파일 하나가 끝날 때마다 보내는
 * ACTION_DOWNLOAD_COMPLETE 를 받아, 4개가 모두 성공한 시점에 모델을 정식 폴더로 옮긴다.
 * (스푸핑돼도 무해 — 핸들러는 DownloadManager 의 실제 상태만 다시 조회한다.)
 */
class SttModelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        if (!SttModel.isDownloading(context)) return
        if (SttModel.finalizeIfDone(context)) {
            I18n.apply(context)
            Toast.makeText(
                context,
                I18n.t("음성 인식 모델 설치 완료 — 충전 중에 자동 전사가 시작됩니다"),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
