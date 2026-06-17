package com.studioroomkr.hellorecorder

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import java.io.File

/**
 * 한 번에 하나의 파일만 재생하는 플레이어.
 *  - 재생 속도 조절(0.5~2.0배)
 *  - 현재 위치/전체 길이 조회 (진행바·북마크용)
 *  - 특정 위치로 이동(seek), 북마크 점프
 */
object Player {
    private var mp: MediaPlayer? = null
    private var currentPath: String? = null
    private var speed: Float = 1.0f

    /** 재생 시작/정지 토글. 시작하면 true */
    fun toggle(file: File, onComplete: () -> Unit): Boolean {
        if (currentPath == file.absolutePath && mp?.isPlaying == true) {
            stop()
            return false
        }
        stop()
        mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                stop()
                onComplete()
            }
            prepare()
            applySpeed(this)
            start()
        }
        currentPath = file.absolutePath
        return true
    }

    /** 재생 속도 설정 (0.5, 1.0, 1.5, 2.0 등). 재생 중이면 즉시 반영 */
    fun setSpeed(value: Float) {
        speed = value
        mp?.let { applySpeed(it) }
    }

    fun getSpeed(): Float = speed

    private fun applySpeed(player: MediaPlayer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val wasPlaying = player.isPlaying
                player.playbackParams = PlaybackParams().setSpeed(speed)
                if (!wasPlaying) player.pause() // setSpeed 가 재생을 시작시키는 것 방지
            } catch (_: Exception) {
            }
        }
    }

    fun seekTo(ms: Long) {
        try { mp?.seekTo(ms.toInt()) } catch (_: Exception) {}
    }

    fun currentPositionMs(): Long = try { mp?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }

    fun durationMs(): Long = try { mp?.duration?.toLong() ?: 0L } catch (_: Exception) { 0L }

    fun stop() {
        try {
            mp?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }
        mp = null
        currentPath = null
    }

    fun isPlaying(file: File): Boolean =
        currentPath == file.absolutePath && mp?.isPlaying == true

    /** 현재 이 파일이 로드되어 있는지 (재생/일시정지 무관) */
    fun isLoaded(file: File): Boolean = currentPath == file.absolutePath
}
