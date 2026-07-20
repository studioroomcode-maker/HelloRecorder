package com.studioroomkr.hellorecorder

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * STT 모델(한국어 Zipformer int8, 총 ~76MB) 인앱 다운로드 관리.
 *
 *  - 소스: 허깅페이스 공식 k2-fsa 저장소에서 int8 파일 4개를 개별 다운로드
 *    (아카이브가 아니라 압축 해제 코드가 필요 없고, fp32 등 불필요한 285MB 를 받지 않는다).
 *  - 시스템 DownloadManager 사용: 앱이 종료돼도 시스템이 이어받기·재시도·진행률 알림 처리.
 *  - 원자성: stt-model-tmp/ 에 받고 4개 모두 성공했을 때만 stt-model/ 로 이동.
 *    Transcriber/TranscribeWorker 는 완성된 stt-model/ 만 본다.
 *  - 완료 감지: SttModelReceiver(ACTION_DOWNLOAD_COMPLETE) + MainActivity.onResume 재확인.
 */
object SttModel {

    // 오프라인(비스트리밍) 한국어 zipformer — KsponSpeech 학습.
    //
    // 예전엔 streaming-zipformer 를 썼는데, 스트리밍 모델은 "지금까지 들은 것"만으로 즉시
    // 답을 내야 해서 뒤 문맥을 못 본다. 실시간 자막엔 필수지만 이 앱의 전사는 이미 다 녹음된
    // 파일을 충전 중에 배치로 처리하는 작업이라, 그 정확도 손해를 감수할 이유가 없었다.
    // 오프라인 모델은 발화 전체를 보고 결정한다 → 더 정확하고, 덤으로 encoder 가 더 작다
    // (127MB → 70.8MB, 총 다운로드 133MB → 76MB).
    private const val BASE =
        "https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24/resolve/main/"
    /**
     * 받을 파일 + 무결성 기준(크기·SHA-256). DownloadManager 의 "성공"은 HTTP 전송 완료를
     * 뜻할 뿐, 내용이 올바른지는 보장하지 않는다(프록시 에러페이지·잘린 파일·저장소 교체본이
     * 그대로 통과 가능). 정식 폴더로 옮기기 전에 이 기준으로 검증한다.
     * 값은 허깅페이스 k2-fsa 저장소 실측(LFS oid = 파일 SHA-256, tokens.txt 는 직접 계산).
     */
    private data class ModelFile(val name: String, val size: Long, val sha256: String)
    private val FILES = listOf(
        ModelFile("encoder-epoch-99-avg-1.int8.onnx", 70_784_728,
            "8b196d723421a0513c98ec25da2c43420c029e817f5e4a90b29ff80291c0af2b"),
        ModelFile("decoder-epoch-99-avg-1.int8.onnx", 2_844_692,
            "2cc8c04ea080a657c18ebc59702e6b049cef08163eba5d68ac5bf707925cb0fb"),
        ModelFile("joiner-epoch-99-avg-1.int8.onnx", 2_581_421,
            "eb654db1ea2cc9d63474855f65958b6059084692a9f2eb4f3812aceb1e416a20"),
        // 스트리밍 모델과 내용이 완전히 같다(같은 KsponSpeech BPE 사전) — 해시 실측 확인.
        ModelFile("tokens.txt", 60_246,
            "016bdf0965029263b7ad01b742366ee542ef0bef38261510e8176ff6f2e9e668"),
    )
    const val TOTAL_MB = 76
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
            for (mf in FILES) {
                val name = mf.name
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

        val tmp = File(ctx.getExternalFilesDir(null), TMP_SUBDIR)

        // 이동 전에 무결성 검증. 하나라도 크기·해시가 어긋나면 손상·변조로 보고 전부 폐기해
        // 다음에 처음부터 다시 받게 한다. 반쪽·손상 모델이 정식 폴더로 승격되는 걸 막는다.
        for (mf in FILES) {
            val src = File(tmp, mf.name)
            if (!src.exists() || src.length() != mf.size || sha256(src) != mf.sha256) {
                cancel(ctx)   // ids 비우고 tmp 정리
                return false
            }
        }

        // 검증 통과 → tmp 에서 정식 폴더로 이동(같은 볼륨이라 rename 원자적)
        val dst = Transcriber.modelDir(ctx).apply { mkdirs() }
        // 옛 모델(streaming)과 파일명이 같으므로, 옮기기 전에 표식을 지워 중간에 실패해도
        // 두 모델이 섞인 폴더가 '사용 가능'으로 보이지 않게 한다.
        Transcriber.modelIdFile(ctx).delete()
        var ok = true
        for (mf in FILES) {
            val out = File(dst, mf.name)
            if (out.exists()) out.delete()
            if (!File(tmp, mf.name).renameTo(out)) { ok = false; break }
        }
        // 전부 옮겨진 뒤에만 표식을 남긴다 — 이게 있어야 findModel 이 통과시킨다.
        if (ok) runCatching { Transcriber.modelIdFile(ctx).writeText(Transcriber.MODEL_ID) }
            .onFailure { ok = false }
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

    /** 파일 SHA-256(소문자 hex). 스트리밍으로 읽어 127MB 도 메모리에 통째로 안 올린다. */
    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
