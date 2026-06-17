package com.studioroomkr.hellorecorder

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 한 파일의 재생 화면.
 *  - 재생/정지, 진행바(seek)
 *  - 재생 속도 (0.5 / 1.0 / 1.5 / 2.0)
 *  - 북마크 추가/점프/삭제
 *  - 구간 잘라내기(trim) → 새 파일 저장
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var key: String

    private lateinit var seekBar: SeekBar
    private lateinit var waveform: WaveformView
    private lateinit var timeText: TextView
    private lateinit var playBtn: Button
    private lateinit var bookmarkContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val pos = Player.currentPositionMs()
            val dur = Player.durationMs().coerceAtLeast(1)
            seekBar.max = dur.toInt()
            seekBar.progress = pos.toInt()
            waveform.setProgress(pos.toFloat() / dur)
            timeText.text = "${fmt(pos)} / ${fmt(dur)}"
            playBtn.text = if (Player.isPlaying(file)) I18n.t("⏸ 정지") else I18n.t("▶ 재생")
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.apply(this)
        if (Prefs.isPrivacyMode(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) { finish(); return }
        file = File(path)
        key = Storage.relativeKey(this, file)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 80, 50, 50)
        }
        Theme.applyScreen(root)

        root.addView(Theme.sectionTitle(this, file.name).apply { textSize = 20f })

        // 재생 카드 — 시간/파형/진행바/재생 버튼
        val playCard = Theme.card(this)
        timeText = Theme.body(this)
        playCard.addView(timeText)

        // 파형 (탭/드래그로 위치 탐색)
        waveform = WaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (84 * resources.displayMetrics.density).toInt()
            ).apply { setMargins(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt()) }
            onSeek = { frac ->
                if (!Player.isLoaded(file)) Player.toggle(file) {}
                val dur = Player.durationMs()
                if (dur > 0) Player.seekTo((frac * dur).toLong())
            }
        }
        playCard.addView(waveform)
        waveform.load(file)

        seekBar = Theme.seekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) Player.seekTo(p.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        playCard.addView(seekBar)

        playBtn = Theme.primaryButton(this, "▶ 재생") {
            Player.toggle(file) { /* 완료 시 tick 이 알아서 갱신 */ }
        }
        playCard.addView(playBtn)
        root.addView(playCard)

        // 재생 속도
        root.addView(Theme.sectionTitle(this, "재생 속도"))
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { s ->
            speedRow.addView(Theme.smallButton(this, "${s}x") {
                Player.setSpeed(s)
                Toast.makeText(this@PlayerActivity, I18n.f("%s배속", s), Toast.LENGTH_SHORT).show()
            })
        }
        root.addView(speedRow)

        // 북마크
        root.addView(Theme.sectionTitle(this, "북마크"))
        root.addView(Theme.secondaryButton(this, "★ 현재 위치 북마크") {
            Prefs.addBookmark(this@PlayerActivity, key, Player.currentPositionMs())
            refreshBookmarks()
        })
        bookmarkContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bookmarkContainer)

        // 편집 (구간 잘라내기)
        root.addView(Theme.sectionTitle(this, "구간 잘라내기"))
        root.addView(Theme.hint(this, "현재 재생 위치를 시작/끝으로 지정해 그 구간만 새 파일로 저장합니다."))
        var trimStart = 0L
        var trimEnd = 0L
        val trimLabel = Theme.body(this)
        fun refreshTrim() {
            trimLabel.text = I18n.f("구간: %s ~ %s", fmt(trimStart), fmt(trimEnd))
        }
        val trimRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        trimRow.addView(Theme.smallButton(this, "시작점 지정") {
            trimStart = Player.currentPositionMs(); refreshTrim()
        })
        trimRow.addView(Theme.smallButton(this, "끝점 지정") {
            trimEnd = Player.currentPositionMs(); refreshTrim()
        })
        root.addView(trimRow)
        root.addView(trimLabel)
        refreshTrim()
        root.addView(Theme.secondaryButton(this, "이 구간만 저장") { doTrim(trimStart, trimEnd) })

        // 삭제/공유
        root.addView(Theme.outlineButton(this, "공유") { Share.shareFile(this@PlayerActivity, file) })

        val scroll = ScrollView(this).apply {
            Theme.applyScreen(this)
            addView(root)
        }
        setContentView(scroll)
        // 상태바/내비바 영역만큼 패딩 → 제목이 가려지거나 아래가 잘리지 않게
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        refreshBookmarks()
    }

    private fun doTrim(startMs: Long, endMs: Long) {
        if (startMs >= endMs) {
            Toast.makeText(this, I18n.t("끝점이 시작점보다 뒤여야 합니다"), Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(I18n.t("구간 저장"))
            .setMessage(I18n.t("이 구간을 어떻게 저장할까요?"))
            .setPositiveButton(I18n.t("새 파일로 저장")) { _, _ -> trimToNew(startMs, endMs) }
            .setNeutralButton(I18n.t("덮어쓰기")) { _, _ -> trimOverwrite(startMs, endMs) }
            .setNegativeButton(I18n.t("취소"), null)
            .show()
    }

    private fun trimToNew(startMs: Long, endMs: Long) {
        var out = File(file.parentFile, file.nameWithoutExtension + "_trim.m4a")
        var i = 2
        while (out.exists()) { out = File(file.parentFile, file.nameWithoutExtension + "_trim$i.m4a"); i++ }
        val ok = AudioEditor.trim(file, out, startMs, endMs)
        Toast.makeText(
            this,
            if (ok) I18n.f("저장됨: %s", out.name) else I18n.t("잘라내기 실패"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun trimOverwrite(startMs: Long, endMs: Long) {
        val tmp = File(file.parentFile, file.nameWithoutExtension + "_tmp.m4a")
        if (tmp.exists()) tmp.delete()
        val ok = AudioEditor.trim(file, tmp, startMs, endMs)
        if (!ok) {
            tmp.delete()
            Toast.makeText(this, I18n.t("잘라내기 실패"), Toast.LENGTH_SHORT).show()
            return
        }
        Player.stop()  // 원본을 교체하기 전에 재생 중지
        if (file.delete() && tmp.renameTo(file)) {
            waveform.load(file)   // 파형 다시 로드
            Toast.makeText(this, I18n.t("덮어쓰기 완료"), Toast.LENGTH_SHORT).show()
        } else {
            tmp.delete()
            Toast.makeText(this, I18n.t("덮어쓰기 실패"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshBookmarks() {
        bookmarkContainer.removeAllViews()
        val marks = Prefs.getBookmarks(this, key)
        if (marks.isEmpty()) {
            bookmarkContainer.addView(Theme.body(this).apply {
                text = I18n.t("북마크 없음"); setTextColor(Theme.TEXT_MUTED)
            })
            return
        }
        for (m in marks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(Theme.secondaryButton(this, "▶ ${fmt(m)}") {
                if (!Player.isLoaded(file)) Player.toggle(file) {}
                Player.seekTo(m)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(0, 8, 16, 8) }
            })
            row.addView(Theme.smallButton(this, "삭제", danger = true) {
                Prefs.removeBookmark(this@PlayerActivity, key, m); refreshBookmarks()
            })
            bookmarkContainer.addView(row)
        }
    }

    private fun fmt(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }
    override fun onStop() { super.onStop(); Player.stop() }

    companion object {
        const val EXTRA_PATH = "extra_path"
    }
}
