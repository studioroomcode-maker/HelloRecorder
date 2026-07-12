package com.studioroomkr.hellorecorder

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * STT 모델(한국어 Zipformer int8, 총 ~133MB) 인앱 다운로드 관리.
 *
 *  - 소스: 허깅페이스 공식 k2-fsa 저장소에서 int8 파일 4개를 개별 다운로드
 *    (아카이브가 아니라 압축 해제 코드가 필요 없고, fp32 등 불필요한 285MB 를 받지 않는다).
 *  - 시스템 DownloadManager 사용: 앱이 종료돼도 시스템이 이어받기·재시도·진행률 알림 처리.
 *  - 원자성: stt-model-tmp/ 에 받고 4개 모두 성공했을 때만 stt-model/ 로 이동.
 *    Transcriber/TranscribeWorker 는 완성된 stt-model/ 만 본다.
 *  - 완료 감지: SttModelReceiver(ACTION_DOWNLOAD_COMPLETE) + MainActivity.onResume 재확인.
 */
object SttModel {

    private const val BASE =
        "https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16/resolve/main/"
    private val FILES = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",   // ~127MB
        "decoder-epoch-99-avg-1.int8.onnx",   // ~2.8MB
        "joiner-epoch-99-avg-1.int8.onnx",    // ~2.6MB
        "tokens.txt",
    )
    const val TOTAL_MB = 133
    private const val TMP_SUBDIR = "stt-model-tmp"

    fun isAvailable(ctx: Context): Boolean = Transcriber.isModelAvailable(ctx)

    fun isDownloading(ctx: Context): Boolean = Prefs.getSttDownloadIds(ctx).isNotEmpty()

    /**
     * 다운로드 시작. allowMetered=false 면 Wi-Fi(비과금 네트워크)에서만 받는다.
     * 이미 진행 중이면 그대로 true. 시작 실패(저장소 접근 불가 등)면 false.
     */
    fun startDownload(ctx: Context, allowMetered: Boolean): Boolean {
        if (isDownloading(ctx)) return true
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        cleanupTmp(ctx)
        val ids = ArrayList<Long>(FILES.size)
        try {
            for (name in FILES) {
                val req = DownloadManager.Request(Uri.parse(BASE + name))
                    .setTitle(I18n.t("음성 인식 모델 다운로드"))
                    .setDescription(name)
                    .setDestinationInExternalFilesDir(ctx, null, "$TMP_SUBDIR/$name")
                    .setAllowedOverMetered(allowMetered)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                ids.add(dm.enqueue(req))
            }
        } catch (_: Exception) {
            // 일부만 등록된 채 실패 → 등록된 것 취소 후 실패 보고
            ids.forEach { try { dm.remove(it) } catch (_: Exception) {} }
            cleanupTmp(ctx)
            return false
        }
        Prefs.setSttDownloadIds(ctx, ids)
        return true
    }

    /** 진행률 0..100 (전체 바이트 기준). 조회 불가 시 0. */
    fun progressPercent(ctx: Context): Int {
        val ids = Prefs.getSttDownloadIds(ctx)
        if (ids.isEmpty()) return 0
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return 0
        var done = 0L
        var total = 0L
        dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray())).use { c ->
            val iDone = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val iTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (c.moveToNext()) {
                done += c.getLong(iDone).coerceAtLeast(0)
                val t = c.getLong(iTotal)
                if (t > 0) total += t
            }
        }
        if (total <= 0) return 0
        return ((done * 100) / total).toInt().coerceIn(0, 100)
    }

    /**
     * 다운로드 상태를 확인해 4개 모두 성공이면 stt-model/ 로 옮기고 true.
     * 하나라도 실패면 전체 취소·정리(다음에 처음부터 다시). 진행 중이면 false.
     */
    fun finalizeIfDone(ctx: Context): Boolean {
        val ids = Prefs.getSttDownloadIds(ctx)
        if (ids.isEmpty()) return isAvailable(ctx)
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false

        var success = 0
        var failed = false
        dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray())).use { c ->
            val iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            var rows = 0
            while (c.moveToNext()) {
                rows++
                when (c.getInt(iStatus)) {
                    DownloadManager.STATUS_SUCCESSFUL -> success++
                    DownloadManager.STATUS_FAILED -> failed = true
                }
            }
            // 사용자가 시스템 알림에서 다운로드를 삭제하면 행 자체가 사라진다 → 실패로 간주
            if (rows < ids.size) failed = true
        }

        if (failed) {
            cancel(ctx)
            return false
        }
        if (success < ids.size) return false   // 아직 진행 중

        // 전부 성공 → tmp 에서 정식 폴더로 이동(같은 볼륨이라 rename 원자적)
        val tmp = File(ctx.getExternalFilesDir(null), TMP_SUBDIR)
        val dst = Transcriber.modelDir(ctx).apply { mkdirs() }
        var ok = true
        for (name in FILES) {
            val src = File(tmp, name)
            val out = File(dst, name)
            if (out.exists()) out.delete()
            if (!src.exists() || !src.renameTo(out)) { ok = false; break }
        }
        Prefs.setSttDownloadIds(ctx, emptyList())
        cleanupTmp(ctx)
        if (!ok) {
            // 이동 실패(비정상) — 불완전한 모델 폴더는 지워 반쪽 상태를 남기지 않는다
            dst.listFiles()?.forEach { it.delete() }
            return false
        }
        return isAvailable(ctx)
    }

    /** 다운로드 취소 + 임시 파일 정리. */
    fun cancel(ctx: Context) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        Prefs.getSttDownloadIds(ctx).forEach { try { dm?.remove(it) } catch (_: Exception) {} }
        Prefs.setSttDownloadIds(ctx, emptyList())
        cleanupTmp(ctx)
    }

    /** 모델 삭제(저장공간 확보용). 진행 중 다운로드도 함께 취소. */
    fun delete(ctx: Context) {
        cancel(ctx)
        Transcriber.modelDir(ctx).listFiles()?.forEach { it.delete() }
    }

    private fun cleanupTmp(ctx: Context) {
        try {
            File(ctx.getExternalFilesDir(null), TMP_SUBDIR).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
