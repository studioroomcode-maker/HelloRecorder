package com.studioroomkr.hellorecorder

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * STT 모델(Whisper base int8, 총 ~153MB) 인앱 다운로드 관리.
 *
 *  - 소스: 허깅페이스에서 int8 파일 3개(encoder·decoder·tokens)를 개별 다운로드
 *    (아카이브가 아니라 압축 해제 코드가 필요 없고, fp32 원본을 받지 않는다).
 *  - 시스템 DownloadManager 사용: 앱이 종료돼도 시스템이 이어받기·재시도·진행률 알림 처리.
 *  - 원자성: stt-model-tmp/ 에 받고 셋 다 성공·검증됐을 때만 stt-model/ 로 이동.
 *    Transcriber/TranscribeWorker 는 완성된 stt-model/ 만 본다.
 *  - 완료 감지: SttModelReceiver(ACTION_DOWNLOAD_COMPLETE) + MainActivity.onResume 재확인.
 */
object SttModel {

    // Whisper base (다국어, int8).
    //
    // 모델 변천: streaming zipformer → offline zipformer(KsponSpeech) → **whisper base**.
    // KsponSpeech 모델은 깨끗한 방송·대화 음성으로 학습돼, 이 앱의 실제 오디오(원거리·잡음
    // 환경의 상시 녹음)에서 인식률이 크게 떨어졌다. 근접 또렷 발화만 되면 상시 녹음 앱의
    // 의미가 없다. Whisper 는 온갖 잡음 환경 데이터로 학습돼 원거리·잡음에 강건하고,
    // 띄어쓰기까지 해 준다. 실측 비교에서 확연히 나았다.
    // 대가: 다운로드가 76MB → ~153MB, 속도도 느리다(RTF PC 0.15, 폰 ~0.5). 다만 전사는
    // 충전 중 배치라 속도는 문제되지 않는다.
    //
    // Whisper 는 30초를 넘는 입력의 초과분을 버리므로, Transcriber 가 25초 미만 구간으로
    // 잘라 넣는다(SEG_MAX 참고).
    private const val BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/"
    /**
     * 받을 파일 + 무결성 기준(크기·SHA-256). DownloadManager 의 "성공"은 HTTP 전송 완료를
     * 뜻할 뿐, 내용이 올바른지는 보장하지 않는다(프록시 에러페이지·잘린 파일·저장소 교체본이
     * 그대로 통과 가능). 정식 폴더로 옮기기 전에 이 기준으로 검증한다.
     */
    private data class ModelFile(val name: String, val size: Long, val sha256: String)
    // 크기·SHA-256 은 HuggingFace API 실측(LFS oid). tokens 는 oid 가 없어 직접 계산했다.
    private val FILES = listOf(
        ModelFile("base-encoder.int8.onnx", 29_120_534,
            "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
        ModelFile("base-decoder.int8.onnx", 130_672_026,
            "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
        ModelFile("base-tokens.txt", 816_730,
            "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
    )
    const val TOTAL_MB = 153
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
        // 옮기기 전에 버전 표식을 지운다 — 중간에 실패해도 옛 모델과 새 파일이 섞인 폴더가
        // '사용 가능'으로 보이지 않게 한다. 또한 예전 zipformer 파일들이 남아 있을 수 있어
        // 새로 받은 것만 남도록 폴더를 비운다(whisper 는 파일명이 달라 그냥 두면 공존한다).
        Transcriber.modelIdFile(ctx).delete()
        dst.listFiles()?.forEach { it.delete() }
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
