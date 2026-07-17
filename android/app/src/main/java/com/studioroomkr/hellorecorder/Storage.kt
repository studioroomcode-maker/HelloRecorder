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

    /** 주어진 저장 위치의 베이스 디렉터리. 외부/SD 가 없으면 내부로 폴백. */
    private fun baseDirFor(ctx: Context, location: Int): File = when (location) {
        Prefs.STORAGE_EXTERNAL -> ctx.getExternalFilesDir(null) ?: ctx.filesDir
        Prefs.STORAGE_SD -> {
            val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
            dirs.getOrNull(1) ?: dirs.getOrNull(0) ?: ctx.filesDir
        }
        else -> ctx.filesDir
    }

    /** 주어진 저장 위치의 recordings 루트(없으면 생성). */
    private fun rootDirFor(ctx: Context, location: Int): File =
        File(baseDirFor(ctx, location), "recordings").apply { mkdirs() }

    private fun rootDir(ctx: Context): File = rootDirFor(ctx, Prefs.getStorageLocation(ctx))

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

    /** relativeKey 의 역변환 — 검색 인덱스 등 키로 저장된 항목에서 파일 복원. */
    fun fileForKey(ctx: Context, key: String): File = File(rootDir(ctx), key)

    /** 내부저장소 남은 공간 (바이트) */
    fun freeBytes(ctx: Context): Long = rootDir(ctx).usableSpace

    /** 녹음 폴더가 차지하는 총 용량 (바이트) */
    fun usedBytes(ctx: Context): Long =
        listAllFiles(ctx).sumOf { it.length() }

    /**
     * 녹음 하나를 **완전히** 삭제한다 — 오디오(.m4a) + 활동 프로필(.lvl) + 전사 사이드카/검색
     * 인덱스(.stt.json + DB) + 파일별 Prefs 메타(보호·라벨·북마크·카테고리).
     *
     * 모든 삭제 경로(수동 삭제·짧은 녹음 정리·공간 확보·편집 덮어쓰기·보관기간 만료)가
     * 반드시 이 한 함수만 거치게 한다. 예전엔 경로마다 일부만 지워, 삭제된 파일의 전사가
     * 검색에 계속 잡히거나 북마크·카테고리가 영구히 남았다. transcript 를 false 로 주면
     * 전사만 남기는데(편집 후 재전사 예정 등), 그때도 옛 전사·북마크는 무효화해야 한다.
     *
     * @return .m4a 삭제 성공 여부.
     */
    fun deleteRecording(ctx: Context, file: File, transcript: Boolean = true): Boolean {
        val key = relativeKey(ctx, file)
        val deleted = file.delete()
        profileFile(file).delete()
        if (transcript) TranscriptStore.removeFor(ctx, file)
        Prefs.clearFileMeta(ctx, key)
        return deleted
    }

    /**
     * 파일 내용이 편집으로 바뀌었을 때(잘라내기 덮어쓰기 등) 옛 오디오에 매인 파생물을
     * 무효화한다 — 전사 세그먼트·북마크는 옛 타임스탬프라 새 오디오와 어긋난다. 파형(.lvl)은
     * 호출부가 다시 로드하며 재생성하므로 여기서 지운다. 오디오 파일 자체는 건드리지 않는다.
     */
    fun invalidateDerived(ctx: Context, file: File) {
        val key = relativeKey(ctx, file)
        profileFile(file).delete()
        TranscriptStore.removeFor(ctx, file)
        Prefs.clearBookmarks(ctx, key)
    }

    /**
     * 0바이트(빈) 녹음 파일을 정리하고 지운 개수를 돌려준다.
     * 인코딩/먹싱 실패로 남은 깨진 파일이 목록에 '--:--' 로 보이는 것을 막는다.
     * 녹음 중인 파일을 건드리지 않도록, 최근 2분 내 수정된 파일은 건너뛴다(안전장치).
     */
    fun cleanupEmptyFiles(ctx: Context): Int {
        val now = System.currentTimeMillis()
        var removed = 0
        for (f in listAllFiles(ctx)) {
            if (RecordingLogic.isEmptyAbandoned(f.length(), f.lastModified(), now)) {
                if (deleteRecording(ctx, f)) removed++
            }
        }
        return removed
    }

    // ─── 활동 프로필(파형·음성 강조용 사이드카) ───
    // 녹음 중 엔진이 청크(~0.5초)마다 음량+음성여부를 1바이트(bit7=음성, bits0-6=음량 0..127)로
    // 기록한다. 플레이어는 이 사이드카로 디코드 없이 파형을 그리고 말소리 구간을 색으로 강조한다.
    private const val PROFILE_EXT = ".lvl"
    private val PROFILE_MAGIC =
        byteArrayOf('H'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

    /** .m4a 에 대응하는 활동 프로필 사이드카 경로. */
    fun profileFile(m4a: File): File = File(m4a.parentFile, m4a.nameWithoutExtension + PROFILE_EXT)

    /** 활동 프로필 저장(엔진이 구간 종료 시 호출). */
    fun writeActivityProfile(m4a: File, bytes: ByteArray) {
        try {
            profileFile(m4a).outputStream().use { it.write(PROFILE_MAGIC); it.write(bytes) }
        } catch (_: Exception) {}
    }

    /** 디코드된 활동 프로필. levels 0..1, voice = 음성 감지 여부. */
    class ActivityProfile(val levels: FloatArray, val voice: BooleanArray)

    /** 사이드카 읽기. 없거나 손상되면 null → 호출부는 오디오 디코드로 폴백. */
    fun readActivityProfile(m4a: File): ActivityProfile? {
        val f = profileFile(m4a)
        if (!f.isFile) return null
        val raw = try { f.readBytes() } catch (_: Exception) { return null }
        if (raw.size <= PROFILE_MAGIC.size) return null
        for (i in PROFILE_MAGIC.indices) if (raw[i] != PROFILE_MAGIC[i]) return null
        val n = raw.size - PROFILE_MAGIC.size
        val levels = FloatArray(n)
        val voice = BooleanArray(n)
        for (i in 0 until n) {
            val b = raw[PROFILE_MAGIC.size + i].toInt() and 0xFF
            voice[i] = (b and 0x80) != 0
            levels[i] = (b and 0x7F) / 127f
        }
        return ActivityProfile(levels, voice)
    }

    /**
     * 활동 프로필에서 '발화 시작점' 위치(0..1 비율, 오름차순)를 도출한다.
     * 음성 비트가 무음→음성으로 바뀌는 지점이 온셋. 깜빡임 방지를 위해 직전에 일정
     * 청크 수 이상 무음이 있었을 때만 새 온셋으로 센다(minSilenceChunks, 1청크≈0.5초).
     */
    fun voiceOnsets(p: ActivityProfile, minSilenceChunks: Int = 2): List<Float> {
        val n = p.voice.size
        if (n == 0) return emptyList()
        val out = ArrayList<Float>()
        var silenceRun = minSilenceChunks   // 시작은 무음으로 간주 → 첫 음성도 온셋
        for (i in 0 until n) {
            if (p.voice[i]) {
                if (silenceRun >= minSilenceChunks) out.add(i.toFloat() / n)
                silenceRun = 0
            } else {
                silenceRun++
            }
        }
        return out
    }

    /** 짝 잃은 사이드카(.m4a 가 사라진 .lvl) 정리 — 모든 삭제 경로를 못 탄 경우의 안전망. */
    fun sweepOrphanProfiles(ctx: Context) {
        listDayDirs(ctx).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(PROFILE_EXT) &&
                    !File(dir, f.nameWithoutExtension + ".m4a").exists()
                ) f.delete()
            }
        }
    }

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
            deleteRecording(ctx, f)
            if (freeBytes(ctx) >= minFreeBytes) return true
        }
        return freeBytes(ctx) >= minFreeBytes
    }

    // ── 저장 위치 이동 ──

    sealed class MoveResult {
        object AlreadyThere : MoveResult()
        data class NotEnoughSpace(val need: Long, val free: Long) : MoveResult()
        data class Failed(val reason: String) : MoveResult()
        data class Moved(val count: Int) : MoveResult()
    }

    /**
     * 현재 저장 위치의 모든 녹음(+.lvl/.stt.json 사이드카)을 target 위치로 옮긴다.
     *
     * **all-or-nothing**: 전부 옮겨진 뒤에만 저장 위치 설정을 바꾼다. 도중에 하나라도 실패하면
     * 이미 옮긴 것을 되돌려(원위치) 원본을 그대로 남긴다 — 데이터 손실·반쪽 상태를 막는다.
     * 같은 볼륨이면 renameTo(즉시), 다른 볼륨(SD 등)이면 copy 후 원본 삭제. 다른 볼륨으로 옮길
     * 땐 대상 여유 공간을 먼저 확인한다.
     *
     * 파일이 클 수 있으니 **백그라운드 스레드에서 호출**할 것(메인 스레드 금지).
     */
    fun moveStorageTo(ctx: Context, targetLoc: Int): MoveResult {
        val fromLoc = Prefs.getStorageLocation(ctx)
        if (fromLoc == targetLoc) return MoveResult.AlreadyThere
        val fromRoot = rootDirFor(ctx, fromLoc)
        val toRoot = rootDirFor(ctx, targetLoc)
        if (fromRoot.absolutePath == toRoot.absolutePath) {
            // 같은 실제 경로(외부 없음→내부 폴백 등) — 위치 설정만 바꾼다.
            Prefs.setStorageLocation(ctx, targetLoc)
            return MoveResult.AlreadyThere
        }

        // 옮길 파일 수집(사이드카 포함). 상대경로(day/name)를 보존한다.
        data class Item(val src: File, val rel: String)
        val items = ArrayList<Item>()
        fromRoot.listFiles()?.filter { it.isDirectory }?.forEach { dayDir ->
            dayDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                items.add(Item(f, "${dayDir.name}/${f.name}"))
            }
        }
        if (items.isEmpty()) {
            Prefs.setStorageLocation(ctx, targetLoc)
            return MoveResult.Moved(0)
        }

        // 다른 볼륨 copy 대비 여유 공간 확인.
        val totalSize = items.sumOf { it.src.length() }
        if (toRoot.usableSpace < totalSize) {
            return MoveResult.NotEnoughSpace(totalSize, toRoot.usableSpace)
        }

        val renamed = ArrayList<Pair<File, File>>()  // (src, dst) — src 는 이미 사라짐(롤백 시 되돌림)
        val copied = ArrayList<Pair<File, File>>()    // (src, dst) — src 존재(성공 시 삭제)
        for (it in items) {
            val dst = File(toRoot, it.rel)
            dst.parentFile?.mkdirs()
            val renameOk = try { it.src.renameTo(dst) } catch (_: Exception) { false }
            if (renameOk) {
                renamed.add(it.src to dst)
            } else {
                val copyOk = try { it.src.copyTo(dst, overwrite = true); true } catch (_: Exception) { false }
                if (copyOk) {
                    copied.add(it.src to dst)
                } else {
                    // 실패 → 롤백: 지금까지 rename 한 건 되돌리고, copy 한 dst 는 지운다.
                    renamed.forEach { (s, d) -> try { d.renameTo(s) } catch (_: Exception) {} }
                    copied.forEach { (_, d) -> try { d.delete() } catch (_: Exception) {} }
                    // 대상 루트의 빈 잔재 정리
                    toRoot.listFiles()?.filter { it.isDirectory }?.forEach { if (it.list()?.isEmpty() == true) it.delete() }
                    return MoveResult.Failed("파일 이동 실패: ${it.rel}")
                }
            }
        }

        // 전부 성공 → 위치 전환 + copy 원본 삭제 + 빈 날짜 폴더 정리.
        Prefs.setStorageLocation(ctx, targetLoc)
        copied.forEach { (s, _) -> try { s.delete() } catch (_: Exception) {} }
        fromRoot.listFiles()?.filter { it.isDirectory }?.forEach {
            if (it.list()?.isEmpty() == true) it.delete()
        }
        return MoveResult.Moved(items.size)
    }
}
