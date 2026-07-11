package com.studioroomkr.hellorecorder

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 녹음 파일을 다른 앱(카톡, 드라이브, 메일 등)으로 공유.
 * FileProvider 로 내부저장소 파일에 임시 읽기 권한을 부여한다.
 */
object Share {
    fun shareFile(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file
        )
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
        val uris = ArrayList<android.net.Uri>()
        for (f in files) {
            uris.add(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f))
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
        val uris = ArrayList<android.net.Uri>()
        for (f in files) {
            uris.add(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f))
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
