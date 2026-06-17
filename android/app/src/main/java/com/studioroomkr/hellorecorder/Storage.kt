package com.studioroomkr.hellorecorder

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 녹음 파일 저장 구조를 한 곳에서 관리.
 *
 * 구조: filesDir/recordings/yyyyMMdd/yyyyMMdd_HH(_n).m4a
 *   recordings/
 *     20260616/
 *       20260616_06.m4a
 *       20260616_14.m4a
 *       20260616_14_2.m4a
 *     20260617/
 *       ...
 *
 * 날짜별 하위 폴더로 묶어 목록/삭제/탐색을 깔끔하게 한다.
 */
object Storage {

    /** 설정된 저장 위치의 베이스 디렉터리. 외부/SD 가 없으면 내부로 폴백. */
    private fun baseDir(ctx: Context): File = when (Prefs.getStorageLocation(ctx)) {
        Prefs.STORAGE_EXTERNAL -> ctx.getExternalFilesDir(null) ?: ctx.filesDir
        Prefs.STORAGE_SD -> {
            val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
            dirs.getOrNull(1) ?: dirs.getOrNull(0) ?: ctx.filesDir
        }
        else -> ctx.filesDir
    }

    private fun rootDir(ctx: Context): File =
        File(baseDir(ctx), "recordings").apply { mkdirs() }

    /** 현재 저장 위치의 실제 경로(표시용). */
    fun currentRootPath(ctx: Context): String = rootDir(ctx).absolutePath

    /** SD 카드(보조 외부 저장소)가 있는지. */
    fun hasSdCard(ctx: Context): Boolean =
        ctx.getExternalFilesDirs(null).filterNotNull().size > 1

    /** 주어진 시각의 날짜 폴더 (없으면 생성) */
    fun dayDir(ctx: Context, time: Long = System.currentTimeMillis()): File {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(time))
        return File(rootDir(ctx), day).apply { mkdirs() }
    }

    /** 모든 날짜 폴더 (최신순) */
    fun listDayDirs(ctx: Context): List<File> =
        rootDir(ctx).listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /** 전체 녹음 파일 (최신순) */
    fun listAllFiles(ctx: Context): List<File> =
        listDayDirs(ctx).flatMap { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".m4a") } ?: emptyList()
        }.sortedByDescending { it.lastModified() }

    /** 보호 식별용 상대 키: "20260616/20260616_14.m4a" */
    fun relativeKey(ctx: Context, file: File): String {
        val root = rootDir(ctx).absolutePath
        return file.absolutePath.removePrefix("$root/")
    }

    /** 내부저장소 남은 공간 (바이트) */
    fun freeBytes(ctx: Context): Long = rootDir(ctx).usableSpace

    /** 녹음 폴더가 차지하는 총 용량 (바이트) */
    fun usedBytes(ctx: Context): Long =
        listAllFiles(ctx).sumOf { it.length() }

    /**
     * 남은 공간이 minFreeBytes 미만이면, 보호되지 않은 가장 오래된 파일부터
     * 지워서 공간을 확보한다. 확보 후에도 부족하면 false (더 지울 게 없음).
     */
    fun ensureFreeSpace(ctx: Context, minFreeBytes: Long, protectedKeys: Set<String>): Boolean {
        if (freeBytes(ctx) >= minFreeBytes) return true
        // 오래된 순 (보호 제외)
        val candidates = listAllFiles(ctx)
            .filter { !protectedKeys.contains(relativeKey(ctx, it)) }
            .sortedBy { it.lastModified() }
        for (f in candidates) {
            f.delete()
            if (freeBytes(ctx) >= minFreeBytes) return true
        }
        return freeBytes(ctx) >= minFreeBytes
    }
}
