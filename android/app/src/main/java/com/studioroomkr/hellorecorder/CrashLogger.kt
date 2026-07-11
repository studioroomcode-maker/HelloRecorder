package com.studioroomkr.hellorecorder

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 온디바이스 크래시 로거.
 *
 * 잡히지 않은 예외(앱 강제 종료의 원인)를 **기기 내부(filesDir/crash_logs)에만** 저장한다.
 * 외부 서버로 전송하지 않으므로 이 앱의 '온디바이스' 프라이버시 약속을 그대로 지킨다.
 * 사용자가 설정에서 직접 '오류 로그 공유'를 누를 때만 파일이 기기 밖으로 나간다.
 *
 * 기존 기본 핸들러를 체이닝하므로, 우리가 기록을 남긴 뒤 시스템의 크래시 처리(종료 등)는
 * 평소대로 이어진다.
 */
object CrashLogger {
    private const val DIR = "crash_logs"
    private const val MAX_FILES = 20   // 최근 20건만 보관(오래된 것부터 정리)

    @Volatile private var installed = false
    private lateinit var appCtx: Context

    /** 앱 시작 시 1회 호출(Application.onCreate). 이후 모든 스레드의 미처리 예외를 기록. */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        appCtx = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { write(thread, throwable) } catch (_: Throwable) { /* 로깅 실패는 무시 */ }
            prev?.uncaughtException(thread, throwable)   // 시스템 기본 처리로 이어감
        }
    }

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun write(thread: Thread, throwable: Throwable) {
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(now)
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append("시간: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)).append('\n')
            append("앱 버전: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("기기: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("안드로이드: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("스레드: ").append(thread.name).append('\n')
            append("──────────\n")
            append(sw.toString())
        }
        File(dir(appCtx), "crash_$stamp.txt").writeText(text)
        prune(appCtx)
    }

    /** 최근 MAX_FILES 건만 남기고 오래된 기록 삭제. */
    private fun prune(ctx: Context) {
        val files = dir(ctx).listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.name }.drop(MAX_FILES).forEach { it.delete() }
    }

    /** 저장된 크래시 로그 파일(최신순). */
    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun count(ctx: Context): Int = list(ctx).size

    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
}
