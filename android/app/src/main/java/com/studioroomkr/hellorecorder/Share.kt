package com.studioroomkr.hellorecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 녹음 파일을 다른 앱(카톡, 드라이브, 메일 등)으로 공유.
 * FileProvider 로 파일에 임시 읽기 권한을 부여한다.
 */
object Share {

    /**
     * FileProvider URI. 설정된 루트(내부·기본 외부) 밖의 파일 — SD카드(보조 외부 저장소) —
     * 이면 getUriForFile 이 IllegalArgumentException 을 던진다. 그땐 캐시로 복사해 공유한다.
     * 예전엔 SD카드 저장 위치에서 공유·백업하면 이 예외로 앱이 죽었다. null 이면 실패.
     */
    @androidx.annotation.VisibleForTesting
    internal fun uriFor(ctx: Context, file: File): Uri? {
        val authority = "${ctx.packageName}.fileprovider"
        return try {
            FileProvider.getUriForFile(ctx, authority, file)
        } catch (_: IllegalArgumentException) {
            try {
                val shareDir = File(ctx.cacheDir, "share").apply { mkdirs() }
                val copy = File(shareDir, file.name)
                file.copyTo(copy, overwrite = true)
                FileProvider.getUriForFile(ctx, authority, copy)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun shareFile(ctx: Context, file: File) {
        val uri = uriFor(ctx, file) ?: run {
            Toast.makeText(ctx, I18n.t("파일을 공유할 수 없습니다"), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(intent, I18n.t("녹음 파일 공유")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * 여러 파일을 한 번에 내보내기 (드라이브 백업 등).
     * 공유 시트에서 구글 드라이브 등을 선택하면 일괄 업로드된다.
     */
    fun shareMultiple(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        if (files.size == 1) { shareFile(ctx, files[0]); return }
        val uris = ArrayList<Uri>()
        for (f in files) uriFor(ctx, f)?.let { uris.add(it) }
        if (uris.isEmpty()) {
            Toast.makeText(ctx, I18n.t("파일을 공유할 수 없습니다"), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/mp4"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(intent, I18n.f("백업 내보내기 (%d개)", files.size)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * 온디바이스 오류 로그(.txt)를 사용자가 직접 공유할 때 사용. 텍스트 MIME 으로 내보낸다.
     * 사용자가 이 동작을 명시적으로 실행할 때만 로그가 기기 밖으로 나간다.
     */
    fun shareLogFiles(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList<Uri>()
        for (f in files) uriFor(ctx, f)?.let { uris.add(it) }
        if (uris.isEmpty()) {
            Toast.makeText(ctx, I18n.t("파일을 공유할 수 없습니다"), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        ctx.startActivity(
            Intent.createChooser(intent, I18n.t("오류 로그 공유")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
