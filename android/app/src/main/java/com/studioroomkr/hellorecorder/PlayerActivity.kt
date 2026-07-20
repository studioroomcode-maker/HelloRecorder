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
import androidx.work.WorkInfo
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
    private lateinit var voiceNavLabel: TextView
    private var voiceOnsets: List<Float> = emptyList()   // 발화 시작점(0..1 비율, 오름차순)

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

    /**
     * 전사문이 없을 때 뜨는 '이 녹음 전사하기' 영역.
     *
     * 정규 배치(TranscribeWorker)는 '충전 중 + 배터리 여유 + 2시간 주기'라, 방금 녹음한 것을
     * 지금 검색하고 싶어도 방법이 없었다. 여기서 1건만 즉시 처리한다 — 사용자가 콕 집어
     * 요청한 것이라 배터리 절약 원칙과 부딪히지 않는다.
     *
     * Pro·모델이 없으면 버튼 대신 이유를 적는다. 버튼을 눌렀는데 조용히 아무 일도 없는 게
     * 제일 나쁘다.
     */
    private fun transcribeNowSection(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(Theme.sectionTitle(this@PlayerActivity, "전사문"))
        when {
            !Pro.isPro ->
                addView(Theme.hint(this@PlayerActivity, "말한 내용으로 찾기(자동 전사)는 Pro 전용입니다."))
            !Transcriber.isModelAvailable(this@PlayerActivity) ->
                addView(Theme.hint(this@PlayerActivity, "음성 인식 모델이 아직 없습니다. 설정 → 녹음 감도에서 모델을 먼저 받아 주세요."))
            else -> {
                val status = Theme.hint(this@PlayerActivity, "이 녹음은 아직 전사되지 않았습니다. 지금 만들면 말한 내용으로 찾을 수 있어요.")
                addView(status)
                val btn = Theme.secondaryButton(this@PlayerActivity, "이 녹음 전사하기") {}
                btn.setOnClickListener {
                    btn.isEnabled = false
                    btn.text = I18n.t("전사 중…")
                    status.text = I18n.t("기기 안에서 처리 중입니다. 녹음 길이에 따라 몇 초~몇 분 걸립니다.")
                    TranscribeWorker.runOne(this@PlayerActivity, file)
                }
                addView(btn)
                // 끝나면 화면을 다시 그려 전사문이 보이게 하거나, 안 됐으면 '왜' 안 됐는지 밝힌다.
                // 예전엔 결과를 구분하지 않고 전부 '말소리를 찾지 못했다'로 뭉뚱그려서, 목소리가
                // 또렷한 녹음도 그렇게 표시돼 원인을 짐작할 수 없었다.
                TranscribeWorker.observeOne(this@PlayerActivity, file).observe(this@PlayerActivity) { infos ->
                    val info = infos.firstOrNull { it.state.isFinished } ?: return@observe
                    if (isFinishing) return@observe
                    if (TranscriptStore.readSidecar(file)?.segments?.isNotEmpty() == true) {
                        recreate(); return@observe
                    }
                    btn.isEnabled = true
                    btn.text = I18n.t("이 녹음 전사하기")
                    val detail = info.outputData.getString(TranscribeWorker.KEY_DETAIL).orEmpty()
                    status.text = when (info.outputData.getString(TranscribeWorker.KEY_STATUS)) {
                        "no_speech" ->
                            I18n.t("전사할 말소리를 찾지 못했습니다. 조용한 녹음일 수 있어요.") + "\n($detail)"
                        "load_failed" ->
                            I18n.t("음성 인식 모델을 불러오지 못했습니다. 설정에서 모델을 다시 받아 주세요.") + "\n$detail"
                        "no_model" ->
                            I18n.t("음성 인식 모델이 아직 없습니다. 설정 → 녹음 감도에서 모델을 먼저 받아 주세요.")
                        "not_pro" ->
                            I18n.t("말한 내용으로 찾기(자동 전사)는 Pro 전용입니다.")
                        "missing_file" ->
                            I18n.t("녹음 파일을 찾을 수 없습니다.")
                        else ->
                            I18n.t("전사에 실패했습니다.") + "\n$detail"
                    }
                }
            }
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
        // 목록에서 넘어온 뒤 파일이 지워졌거나(자동 정리·다른 화면에서 삭제) 0바이트로 잘렸으면
        // 파형·재생·잘라내기 어느 것도 의미가 없다. 안내하고 닫는다.
        if (!Player.isPlayable(file)) {
            Toast.makeText(this, I18n.t("재생할 수 없는 파일입니다"), Toast.LENGTH_SHORT).show()
            finish(); return
        }
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
        playCard.addView(Theme.hint(this, "초록 막대 = 말소리 감지 · 막대를 탭하면 그 지점으로 이동"))
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
            Player.toggle(file, onError = { showUnplayable() }) { /* 완료 시 tick 이 알아서 갱신 */ }
        }
        playCard.addView(playBtn)
        root.addView(playCard)

        // 말소리 구간 빠른 이동 — 활동 프로필(.lvl)의 음성 시작점으로 점프
        root.addView(Theme.sectionTitle(this, "말소리 구간"))
        voiceNavLabel = Theme.body(this).apply { setTextColor(Theme.TEXT_MUTED) }
        root.addView(voiceNavLabel)
        val voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        voiceRow.addView(Theme.smallButton(this, "◀ 이전 발화") { jumpVoice(-1) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 8, 8, 8) }
        })
        voiceRow.addView(Theme.smallButton(this, "다음 발화 ▶") { jumpVoice(1) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(8, 8, 0, 8) }
        })
        root.addView(voiceRow)
        loadVoiceOnsets()

        // 전사문이 아직 없으면 이 자리에서 바로 만들 수 있게 한다. 정규 배치는 '충전 중 +
        // 2시간 주기'라, 방금 녹음한 것을 지금 찾고 싶은 흐름이 통째로 막혀 있었다.
        val hasTranscript = TranscriptStore.readSidecar(file)?.segments?.isNotEmpty() == true
        if (!hasTranscript) root.addView(transcribeNowSection())

        // 전사문 (자동 전사 사이드카가 있을 때만) — 문장을 탭하면 그 위치로 이동
        TranscriptStore.readSidecar(file)?.let { t ->
            if (t.segments.isNotEmpty()) {
                root.addView(Theme.sectionTitle(this, "전사문"))
                root.addView(Theme.hint(this, "문장을 탭하면 그 위치부터 재생합니다. 기기 안에서 자동 전사된 내용이라 부정확할 수 있어요."))
                val pad = (6 * resources.displayMetrics.density).toInt()
                t.segments.take(MAX_TRANSCRIPT_ROWS).forEach { seg ->
                    root.addView(Theme.body(this).apply {
                        text = "${fmt(seg.startMs)}  ${seg.text}"
                        setPadding(0, pad, 0, pad)
                        setOnClickListener {
                            if (!Player.isLoaded(file)) Player.toggle(file, onError = { showUnplayable() }) {}
                            Player.seekTo(seg.startMs)
                        }
                    })
                }
                if (t.segments.size > MAX_TRANSCRIPT_ROWS) {
                    root.addView(Theme.hint(this, I18n.f("…외 %d개 문장 (검색으로 찾아보세요)", t.segments.size - MAX_TRANSCRIPT_ROWS)))
                }
            }
        }

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

        // 검색 결과에서 넘어온 경우: 해당 발화 위치부터 바로 재생.
        // 주의: 액티비티 전환 시 이전 화면(파일 목록·이전 플레이어)의 onStop 은 이 화면의
        // onResume *뒤에* 호출되고, 그 onStop 들이 Player.stop() 을 부른다. onCreate 에서
        // 곧장 재생하면 시작하자마자 죽으므로, 이전 화면 정리가 끝난 뒤로 살짝 미룬다.
        val seekMs = intent.getLongExtra(EXTRA_SEEK_MS, -1L)
        if (seekMs >= 0) {
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    Player.toggle(file, onError = { showUnplayable() }) {}
                    Player.seekTo(seekMs)
                }
            }, AUTOPLAY_DELAY_MS)
        }
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
            // 오디오 내용·길이가 바뀌었다 → 옛 오디오에 매인 파생물(파형·전사·검색 인덱스·북마크)을
            // 모두 무효화한다. 그대로 두면 편집 전 음성·타임스탬프가 계속 검색되고, 북마크·전사
            // 위치가 새 오디오와 어긋난다. (파형은 아래 load 가 디코드로 다시 만든다.)
            Storage.invalidateDerived(this, file)
            waveform.load(file)   // 파형 다시 로드
            Toast.makeText(this, I18n.t("덮어쓰기 완료"), Toast.LENGTH_SHORT).show()
        } else {
            tmp.delete()
            Toast.makeText(this, I18n.t("덮어쓰기 실패"), Toast.LENGTH_SHORT).show()
        }
    }

    /** 활동 프로필에서 발화 시작점을 비동기로 읽어 라벨/네비게이션 준비. */
    private fun loadVoiceOnsets() {
        voiceNavLabel.text = I18n.t("말소리 구간 분석 중…")
        Thread {
            val prof = Storage.readActivityProfile(file)
            val list = if (prof != null) Storage.voiceOnsets(prof) else emptyList()
            runOnUiThread {
                voiceOnsets = list
                voiceNavLabel.text = if (list.isNotEmpty()) {
                    I18n.f("말소리 구간 %d개 — 버튼으로 이동", list.size)
                } else {
                    I18n.t("말소리 구간 정보 없음 (이전 녹음/음성 모드 꺼짐)")
                }
            }
        }.start()
    }

    /** dir>0 다음 발화, dir<0 이전 발화로 점프. */
    private fun jumpVoice(dir: Int) {
        if (voiceOnsets.isEmpty()) {
            Toast.makeText(this, I18n.t("말소리 구간 정보 없음 (이전 녹음/음성 모드 꺼짐)"), Toast.LENGTH_SHORT).show()
            return
        }
        if (!Player.isLoaded(file)) Player.toggle(file) {}
        val dur = Player.durationMs()
        if (dur <= 0) return
        val cur = Player.currentPositionMs().toFloat() / dur
        val eps = 0.005f
        val target = if (dir > 0) voiceOnsets.firstOrNull { it > cur + eps }
        else voiceOnsets.lastOrNull { it < cur - eps }
        if (target == null) {
            Toast.makeText(
                this,
                I18n.t(if (dir > 0) "마지막 발화입니다" else "첫 발화입니다"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        Player.seekTo((target * dur).toLong())
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

    /** 손상·삭제된 파일을 재생하려 할 때. 크래시 대신 안내한다. */
    private fun showUnplayable() {
        Toast.makeText(this, I18n.t("재생할 수 없는 파일입니다"), Toast.LENGTH_SHORT).show()
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
        const val EXTRA_SEEK_MS = "extra_seek_ms"   // 검색 결과 → 발화 위치 바로 재생
        private const val MAX_TRANSCRIPT_ROWS = 100 // 전사문 표시 상한(긴 파일 UI 보호)
        private const val AUTOPLAY_DELAY_MS = 600L  // 이전 화면 onStop(Player.stop) 회피용
    }
}
