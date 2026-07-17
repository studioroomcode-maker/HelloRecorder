package com.studioroomkr.hellorecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.BatteryManager
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var fileListContainer: LinearLayout
    private lateinit var thresholdLabel: TextView
    private var sensitivitySeek: android.widget.SeekBar? = null
    private lateinit var levelBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var startTimeText: TextView
    private lateinit var recordToggleBtn: Button
    private var lastToggleEnabled: Boolean? = null
    private lateinit var contentRoot: View
    private var warningCard: View? = null
    private var warningText: TextView? = null
    private var batteryContainer: LinearLayout? = null
    private var crashDiagBox: LinearLayout? = null
    private var locationZonesContainer: LinearLayout? = null
    private var pendingLocationAction: (() -> Unit)? = null

    private var searchQuery: String = ""
    private var unlocked = false
    // 잠금 인증 다이얼로그가 떠 있는 동안 onResume 이 다시 불려도 중복 프롬프트를 막는다.
    private var authInProgress = false
    private var lastBattSampleTs = 0L
    private val selectedKeys = HashSet<String>()
    private val shownKeys = ArrayList<String>()
    private val durationCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var weekStripContainer: LinearLayout? = null
    private var weekAnchorMillis: Long = System.currentTimeMillis()
    private var selectedDateKey: String? = null   // null = 전체
    private var selectedCategory: String? = null  // null = 전체 카테고리
    private var categoryChips: LinearLayout? = null
    private var transcriptHits: LinearLayout? = null   // 내용(전사) 검색 결과 블록
    private var sortButton: Button? = null
    private val durationMsCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val tagCache = java.util.concurrent.ConcurrentHashMap<String, String>()   // 활동 태그(.lvl 집계)
    // 파일 길이(메타데이터) 비동기 로딩 — 메인스레드를 막지 않게 백그라운드에서 읽어 행을 갱신
    private val metaExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // 목록을 다시 그릴 때마다 증가 — 비동기 콜백이 옛 목록의 뷰를 갱신하지 않게 가드
    private var listGeneration = 0
    // 목록 인라인 재생
    private var playingKey: String? = null
    private var playingFile: File? = null
    private var playProgressBar: ProgressBar? = null
    private var playButton: android.widget.Button? = null
    private var seekTracking = false
    private val playTick = object : Runnable {
        override fun run() {
            val bar = playProgressBar
            val pf = playingFile
            if (bar != null && pf != null) {
                val pos = Player.currentPositionMs()
                val dur = Player.durationMs().coerceAtLeast(1)
                bar.max = dur.toInt()
                if (!seekTracking) bar.progress = pos.toInt()  // 드래그 중엔 덮어쓰지 않음
                playButton?.text = if (Player.isPlaying(pf)) "■" else "▶"
            }
            uiHandler.postDelayed(this, 300)
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val levelTick = object : Runnable {
        override fun run() {
            updateStatus()
            sampleBatteryThrottled()
            uiHandler.postDelayed(this, 300)
        }
    }

    /**
     * 녹음 권한 요청 결과.
     *
     * 마이크와 알림을 따로 판정한다. 녹음에 반드시 필요한 것은 마이크뿐이고,
     * 알림 권한이 없어도 포그라운드 서비스는 동작한다(상태 알림만 표시되지 않음).
     * 예전처럼 result.values.all{} 로 묶으면 알림만 거부해도 녹음이 조용히 시작되지 않아,
     * 사용자에겐 '버튼이 먹통'으로 보인다.
     */
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val micGranted = result[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission()
            if (!micGranted) {
                // 거부 직후 rationale 이 false 면 '다시 묻지 않음' → 설정으로 보내야 한다.
                if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(this, I18n.t("마이크 권한이 필요합니다"), Toast.LENGTH_SHORT).show()
                } else {
                    showPermissionSettingsDialog()
                }
                return@registerForActivityResult
            }
            startRecording()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                result[Manifest.permission.POST_NOTIFICATIONS] == false
            ) {
                Toast.makeText(
                    this,
                    I18n.t("알림 권한이 없어 녹음 중 상태 알림이 표시되지 않습니다"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 마이크 권한이 영구 거부된 상태 — 앱 안에서는 더 물어볼 수 없으므로 설정 화면으로 안내. */
    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(I18n.t("마이크 권한이 필요합니다"))
            .setMessage(I18n.t("녹음하려면 설정에서 마이크 권한을 허용해 주세요."))
            .setPositiveButton(I18n.t("설정 열기")) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            }
            .setNegativeButton(I18n.t("취소"), null)
            .show()
    }

    private val calibPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) runCalibration()
            else Toast.makeText(this, I18n.t("마이크 권한이 필요합니다"), Toast.LENGTH_SHORT).show()
        }

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                pendingLocationAction?.invoke()
            } else {
                Toast.makeText(this, I18n.t("위치 권한이 필요합니다"), Toast.LENGTH_SHORT).show()
            }
            pendingLocationAction = null
        }

    private val mapPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val lat = data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, Double.NaN)
            val lng = data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, Double.NaN)
            val radius = data.getIntExtra(MapPickerActivity.EXTRA_RADIUS, Prefs.DEFAULT_LOC_RADIUS)
            if (!lat.isNaN() && !lng.isNaN()) {
                val n = Prefs.getZones(this).size + 1
                Prefs.addZone(this, I18n.f("구역 %d", n), lat, lng, radius, Prefs.getLocationMode(this))
                refreshZones()
                Toast.makeText(this, I18n.t("지도에서 구역을 추가했습니다"), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.apply(this)
        Pro.init(this)
        Pro.onChanged = { runOnUiThread { recreate() } }   // 구매 반영
        if (Prefs.isPrivacyMode(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        Storage.cleanupEmptyFiles(this)   // 빈/깨진 녹음 파일 먼저 정리
        buildUi()
        CleanupWorker.schedule(this)
        TranscribeWorker.schedule(this)   // 자동 전사(충전 중 배치) — 설정 꺼짐이면 워커가 즉시 통과

        if (Prefs.isAppLockEnabled(this)) {
            // 잠금 상태로 시작. 실제 인증은 onResume 이 한다 — 백그라운드 복귀 재잠금과
            // 같은 경로를 타게 해, 최초 진입과 복귀가 어긋나지 않는다.
            contentRoot.visibility = View.GONE
        } else {
            unlocked = true
        }

        handleResumeRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleResumeRequest(intent)
    }

    /**
     * 부팅 후 '녹음 재개' 알림에서 진입한 경우 — 전경(Activity)이라 마이크 FGS 시작이 허용된다.
     * 권한이 회수됐을 수도 있으므로 일반 시작 경로와 동일하게 권한 확인 후 시작한다.
     */
    private fun handleResumeRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RESUME_RECORDING, false) != true) return
        intent.removeExtra(EXTRA_RESUME_RECORDING)
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .cancel(BootReceiver.NOTIF_ID)
        requestPermsThenStart()
    }

    // ───────────────────────── UI 골격 ─────────────────────────

    private fun buildUi() {
        // 바깥 컨테이너: [상단 고정 상태바] + [스크롤되는 메뉴]
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Theme.applyScreen(outer)
        contentRoot = outer

        // 1) 항상 보이는 상단 상태바 (녹음 중/정지 + 음량 + 시작/정지)
        val topWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        topWrap.addView(buildStatusBar())
        topWrap.addView(buildWarningCard())
        outer.addView(topWrap)

        // 2) 탭 (파일 / 설정) — 밑줄 인디케이터 스타일(시작/정지 버튼과 시각적으로 구분)
        val tabFiles = makeTab("파일", R.drawable.ic_folder)
        val tabSettings = makeTab("설정", R.drawable.ic_settings)
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        tabRow.addView(tabFiles)
        tabRow.addView(tabSettings)
        outer.addView(tabRow)
        // 탭바 하단 구분선
        outer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                .apply { setMargins(dp(16), 0, dp(16), dp(4)) }
            setBackgroundColor(Theme.DIVIDER)
        })

        // 3) 설정 탭 내용
        val settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        buildProSection(settingsContent)           // 0. Pro
        buildSensitivitySection(settingsContent)   // 1. 녹음 감도
        buildScheduleSection(settingsContent)      // 2. 녹음 시간대
        buildStorageSection(settingsContent)       // 3. 저장공간
        buildStabilitySection(settingsContent)     // 4. 안정성(백그라운드)
        buildQualitySection(settingsContent)       // 5. 녹음 품질
        buildSecuritySection(settingsContent)      // 6. 보안
        buildBatterySection(settingsContent)       // 7. 배터리 사용량
        buildLocationSection(settingsContent)      // 8. 위치 기반 녹음
        buildLanguageSection(settingsContent)      // 9. 언어(맨 아래)
        buildAppInfoSection(settingsContent)        // 10. 프로그램 정보(맨 아래)

        // 저작권
        settingsContent.addView(TextView(this).apply {
            text = "© 2026 Studioroom. All rights reserved."
            textSize = 12f
            setTextColor(Theme.TEXT_FAINT)
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(8))
        })

        // 4) 파일 탭 내용
        val filesContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        buildFilesSection(filesContent)

        val tabHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabHost.addView(settingsContent)
        tabHost.addView(filesContent)

        fun selectTab(files: Boolean) {
            settingsContent.visibility = if (files) View.GONE else View.VISIBLE
            filesContent.visibility = if (files) View.VISIBLE else View.GONE
            styleTab(tabSettings, !files)
            styleTab(tabFiles, files)
            if (files) refreshFileList()
        }
        tabSettings.setOnClickListener { selectTab(false) }
        tabFiles.setOnClickListener { selectTab(true) }
        selectTab(true)   // 파일 탭을 먼저 보여줌

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(tabHost)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        outer.addView(scroll)

        setContentView(outer)

        // 3) 시스템 바(상태바/내비바) + 키보드(IME) 영역만큼 패딩 → 위/아래 잘림 방지.
        // targetSdk 35+ 엣지투엣지에선 manifest 의 adjustResize 만으로는 부족하고
        // IME 인셋을 직접 반영해야 키보드가 검색창을 가리지 않는다(화면이 줄며 스크롤 유지).
        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }

        refreshFileList()
        updateStatus()
    }

    private fun buildStatusBar(): View {
        val card = Theme.card(this)
        statusText = TextView(this).apply {
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Theme.TEXT)
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(statusText)

        startTimeText = TextView(this).apply {
            textSize = 13f
            setTextColor(Theme.TEXT_MUTED)
            setPadding(0, 0, 0, dp(8))
            visibility = View.GONE
        }
        Theme.setLeadingIcon(this, startTimeText, R.drawable.ic_clock, Theme.TEXT_MUTED, 14)
        card.addView(startTimeText)

        levelBar = Theme.progressBar(this).apply { max = Prefs.MAX_THRESHOLD.toInt() }
        card.addView(levelBar)

        // 시작/정지 토글 버튼 (하나로 합침)
        recordToggleBtn = Theme.primaryButton(this, "● 시작") {
            // 희망(Prefs)이 아니라 실제 동작 여부로 분기해야 한다. 켜 뒀지만 안 도는 상태에서
            // 희망을 보면 '정지'로 잘못 분기해, 재개하려는 탭이 도리어 꺼 버린다.
            if (RecordingService.isRunning()) {
                RecordingService.stop(this@MainActivity)
                Toast.makeText(this@MainActivity, I18n.t("녹음 정지"), Toast.LENGTH_SHORT).show()
                updateStatus()
            } else {
                requestPermsThenStart()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, dp(2)) }
        }
        card.addView(recordToggleBtn)
        return card
    }

    /**
     * 빈/깨진 녹음(인코딩 실패)이 누적됐을 때 상단에 보여 주는 경고 배너.
     * 기본은 숨김이며 updateStatus()가 실패 카운트를 보고 표시/숨김을 갱신한다.
     * '확인'을 누르면 누적을 비우고 배너를 닫는다.
     */
    private fun buildWarningCard(): View {
        val card = Theme.card(this)
        card.visibility = View.GONE
        warningCard = card
        warningText = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Theme.NEGATIVE)
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(warningText)
        card.addView(Theme.outlineButton(this, "확인") {
            Prefs.clearEncodeFailures(this)
            refreshWarningCard()
        })
        return card
    }

    private fun refreshWarningCard() {
        val card = warningCard ?: return
        val n = Prefs.getEncodeFailCount(this)
        if (n > 0) {
            warningText?.text = I18n.f(
                "⚠ 최근 녹음 %d건이 저장되지 않았습니다. 기기 호환성 문제일 수 있으니 ‘안정성’ 설정에서 배터리 최적화를 꺼 보세요.",
                n
            )
            card.visibility = View.VISIBLE
        } else {
            card.visibility = View.GONE
        }
    }

    // ───────────────────────── 섹션: Pro ─────────────────────────

    private var proDisplayed = false
    private val FREE_CATEGORY_LIMIT = 2
    private val FREE_RETENTION_MAX_HOURS = 72   // 무료 최대 3일

    private fun buildProSection(parent: LinearLayout) {
        proDisplayed = Pro.isPro
        val card = Theme.card(this)
        if (Pro.isPro) {
            card.addView(Theme.body(this, "⭐ Pro 사용 중 — 감사합니다!").apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
        } else {
            card.addView(Theme.body(this, "⭐ HelloRecorder Pro").apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = 16f
            })
            card.addView(Theme.hint(this, "위치 기반 녹음·위젯·무제한 카테고리·30일 보관 등 모든 기능 잠금 해제."))
            card.addView(Theme.primaryButton(this, "Pro 잠금 해제") {
                startActivity(Intent(this, ProActivity::class.java))
            })
        }
        parent.addView(card)
    }

    /** Pro 전용 기능 게이트: Pro 면 실행, 아니면 구매 화면으로. */
    private fun requirePro(action: () -> Unit) {
        if (Pro.isPro) action()
        else startActivity(Intent(this, ProActivity::class.java))
    }

    // ───────────────────────── 섹션: 언어 ─────────────────────────

    private fun buildLanguageSection(parent: LinearLayout) {
        val c = Theme.section(this, parent, "언어 / Language", expanded = false)
        val cur = if (Prefs.isEnglish(this)) "English" else "한국어"
        val label = Theme.body(this).apply { text = (if (I18n.en) "Current: " else "현재: ") + cur }
        c.addView(label)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun setLang(lang: String) {
            Prefs.setLanguage(this, lang)
            I18n.apply(this)
            recreate()
        }
        row.addView(Theme.secondaryButton(this, "한국어") { setLang("ko") }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, dp(4), dp(6), dp(8)) }
        })
        row.addView(Theme.secondaryButton(this, "English") { setLang("en") }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(6), dp(4), 0, dp(8)) }
        })
        c.addView(row)
    }

    // ───────────────────────── 섹션: 프로그램 정보 ─────────────────────────

    private fun buildAppInfoSection(parent: LinearLayout) {
        val c = Theme.section(this, parent, "프로그램 정보", expanded = false)
        c.addView(Theme.body(this, "HelloRecorder (절전형 상시 녹음)"))
        c.addView(Theme.body(this).apply {
            text = I18n.f("버전 %s", "v" + BuildConfig.VERSION_NAME)
        })
        c.addView(Theme.hint(this, "만든 곳: Studioroom · 문의: contact@studioroomkr.com"))
    }

    // ───────────────────────── 섹션: 녹음 감도 ─────────────────────────

    private fun buildSensitivitySection(parent: LinearLayout) {
        val c = Theme.section(this, parent, "녹음 감도 (무음 기준)", expanded = false)
        thresholdLabel = Theme.body(this)
        c.addView(thresholdLabel)
        val cur = Prefs.getThreshold(this)
        val seek = Theme.seekBar(this).apply {
            max = (Prefs.MAX_THRESHOLD - Prefs.MIN_THRESHOLD).toInt()
            progress = (cur - Prefs.MIN_THRESHOLD).toInt()
            setOnSeekBarChangeListener(simpleSeek { p ->
                val v = Prefs.MIN_THRESHOLD + p
                Prefs.setThreshold(this@MainActivity, v)
                updateThresholdLabel(v)
            })
        }
        sensitivitySeek = seek
        c.addView(seek)
        updateThresholdLabel(cur)
        c.addView(Theme.hint(this, "값이 낮을수록 작은 소리도 녹음됩니다. 상단 막대가 기준선을 넘으면 녹음돼요."))

        // 주변 소음 자동 보정 — 노이즈 플로어를 측정해 무음 기준을 자동으로 맞춤
        c.addView(Theme.secondaryButton(this, "주변 소음 자동 맞춤") { startCalibration() })
        c.addView(Theme.hint(this, "조용히 한 뒤 누르면 약 2초간 주변 소음을 측정해 무음 기준을 자동으로 맞춥니다. 녹음 중이면 잠시 끄고 측정하세요."))

        // ── 음성 기능 ──
        // '사람 목소리 우선'은 무료 개방(앱 핵심 차별점 체험). 신경망 정밀화(Silero·목소리 강조)는 Pro.
        c.addView(Theme.checkBox(this, "사람 목소리 우선 (음성 인식)").apply {
            isChecked = Prefs.isVoiceModeEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setVoiceModeEnabled(this@MainActivity, on)
            }
        })
        c.addView(Theme.hint(this, "사람 목소리만 골라 녹음합니다. 작은 목소리도 잘 잡고 잡음은 걸러져요. 저장되는 소리(음질)는 바뀌지 않습니다."))

        if (Pro.isPro) {
            // 2차 정밀 확인 (Silero VAD) — 음성 우선 모드의 하위 옵션
            c.addView(Theme.checkBox(this, "정밀 음성 확인 (Silero)").apply {
                isChecked = Prefs.isSileroEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, on ->
                    Prefs.setSileroEnabled(this@MainActivity, on)
                }
            })
            c.addView(Theme.hint(this, "‘사람 목소리 우선’의 판단을 신경망으로 한 번 더 확인해 오녹음을 줄입니다. 배터리를 아끼려면 끄세요. 음질은 바뀌지 않습니다."))

            // 목소리 강조 — 노이즈억제·음성튜닝 마이크 + GTCRN 잡음제거를 함께 적용 (AGC 미사용)
            c.addView(Theme.checkBox(this, "목소리 강조").apply {
                isChecked = Prefs.isVoiceEmphasisEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, on ->
                    Prefs.setVoiceEmphasisEnabled(this@MainActivity, on)
                }
            })
            c.addView(Theme.hint(this, "가까운 내 목소리를 앞세우고 잡음·멀리 있는 소리를 줄입니다(신경망 잡음 제거 + 근접 우선). 끄면 거의 원본 그대로 녹음돼요(웅웅거림을 줄이는 가벼운 럼블 컷은 항상 적용). 변경은 녹음을 껐다 켜야 반영됩니다."))

            // 먼 소리 줄이기 — 잡음 제거 없이 근접 우선 익스팬더만 (원음 질감 유지)
            c.addView(Theme.checkBox(this, "먼 소리 줄이기").apply {
                isChecked = Prefs.isDistanceReduceEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, on ->
                    Prefs.setDistanceReduceEnabled(this@MainActivity, on)
                }
            })
            c.addView(Theme.hint(this, "잡음 제거 없이 멀리 있는(약한) 소리만 자연스럽게 낮춥니다. 원음 질감이 그대로라 ‘목소리 강조’가 부담스러우면 이 옵션만 켜 보세요. ‘사람 목소리 우선’과 함께 쓸 수 있으며, 다음 녹음 구간부터 적용됩니다."))

            // 자동 전사(v2) — 충전 중에만 녹음을 텍스트로 변환, 온디바이스(외부 전송 없음)
            c.addView(Theme.checkBox(this, "자동 전사 (충전 중)").apply {
                isChecked = Prefs.isTranscribeEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, on ->
                    Prefs.setTranscribeEnabled(this@MainActivity, on)
                }
            })
            when {
                Transcriber.isModelAvailable(this) -> {
                    c.addView(Theme.hint(this, I18n.f("녹음을 기기 안에서 텍스트로 바꿔 나중에 말로 찾을 수 있게 합니다(외부 전송 없음). 충전 중 + 배터리 여유일 때만 돌아 배터리를 쓰지 않습니다. 지금까지 전사된 파일: %d개", TranscriptStore.indexedFileCount(this))))
                }
                SttModel.isDownloading(this) -> {
                    c.addView(Theme.hint(this, I18n.f("음성 인식 모델 다운로드 중… %d%% (진행률은 알림에서도 보여요). 완료되면 자동으로 설치됩니다.", SttModel.progressPercent(this))))
                    c.addView(Theme.outlineButton(this, "모델 다운로드 취소") {
                        SttModel.cancel(this)
                        recreate()
                    })
                }
                else -> {
                    c.addView(Theme.hint(this, I18n.f("말한 내용으로 녹음을 검색하려면 한국어 음성 인식 모델(약 %dMB)이 필요합니다. 한 번만 받으면 이후엔 인터넷 없이 기기 안에서만 동작합니다.", SttModel.TOTAL_MB)))
                    c.addView(Theme.secondaryButton(this, I18n.f("음성 인식 모델 다운로드 (약 %dMB)", SttModel.TOTAL_MB)) {
                        showSttDownloadDialog()
                    })
                }
            }
        } else {
            c.addView(Theme.hint(this, "정밀 음성 확인(Silero)·목소리 강조(신경망 잡음 제거)·먼 소리 줄이기는 Pro 전용입니다. ‘사람 목소리 우선’은 무료로 쓸 수 있어요."))
            c.addView(Theme.outlineButton(this, "정밀 확인·목소리 강조는 Pro") {
                startActivity(Intent(this, ProActivity::class.java))
            })
        }

        // 구간 묶기 간격 (파일이 너무 잘게 쪼개지는 것 방지)
        val gapLabel = Theme.body(this)
        fun refreshGap() {
            val g = Prefs.getMergeGapSec(this)
            gapLabel.text = I18n.f("구간 묶기: 무음 %d초 넘으면 새 파일로 분리", g)
        }
        c.addView(gapLabel)
        c.addView(Theme.seekBar(this).apply {
            max = Prefs.MAX_MERGE_GAP_SEC - Prefs.MIN_MERGE_GAP_SEC
            progress = (Prefs.getMergeGapSec(this@MainActivity) - Prefs.MIN_MERGE_GAP_SEC)
                .coerceIn(0, Prefs.MAX_MERGE_GAP_SEC - Prefs.MIN_MERGE_GAP_SEC)
            setOnSeekBarChangeListener(simpleSeek { p ->
                Prefs.setMergeGapSec(this@MainActivity, Prefs.MIN_MERGE_GAP_SEC + p)
                refreshGap()
            })
        })
        refreshGap()
        c.addView(Theme.hint(this, "길게 잡을수록 짧은 침묵으로 끊긴 구간을 한 파일로 묶어 파일 수가 줄어듭니다. 묶이는 구간의 중간 소리는 끊지 않고 이어서 저장하며, 완전한 무음(볼륨 0)만 건너뜁니다. 짧게 잡으면 잘게 나뉩니다."))

        // 짧은 녹음 자동 삭제 (잡음 컷)
        c.addView(Theme.checkBox(this, "짧은 녹음 자동 삭제").apply {
            isChecked = Prefs.isMinKeepEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setMinKeepEnabled(this@MainActivity, on)
            }
        })
        val minKeepLabel = Theme.body(this)
        fun refreshMinKeep() {
            minKeepLabel.text = I18n.f("기준 길이: %d초 이하 녹음은 저장 안 함", Prefs.getMinKeepSec(this))
        }
        c.addView(minKeepLabel)
        c.addView(Theme.seekBar(this).apply {
            max = Prefs.MAX_MIN_KEEP_SEC - Prefs.MIN_MIN_KEEP_SEC
            progress = (Prefs.getMinKeepSec(this@MainActivity) - Prefs.MIN_MIN_KEEP_SEC)
                .coerceIn(0, Prefs.MAX_MIN_KEEP_SEC - Prefs.MIN_MIN_KEEP_SEC)
            setOnSeekBarChangeListener(simpleSeek { p ->
                Prefs.setMinKeepSec(this@MainActivity, Prefs.MIN_MIN_KEEP_SEC + p)
                refreshMinKeep()
            })
        })
        refreshMinKeep()
        c.addView(Theme.hint(this, "설정한 길이 이하의 짧은 녹음(기침·문 닫는 소리 같은 잡음)을 저장하지 않습니다. 화면에 ‘N초’로 보이는 것까지(예: 5초면 0:05) 모두 버리고, 그보다 긴 것만 저장합니다. 기준을 넘기 전까진 인코딩을 보류했다가 넘는 순간에만 저장하므로 배터리·발열도 줄어듭니다. 기본은 켜짐(5초)이며, 끄면 짧은 녹음도 모두 즉시 저장합니다."))
        // 기존에 저장된 짧은 파일 일괄 정리 (이 설정은 새 녹음에만 적용되므로 옛 파일은 따로 청소)
        c.addView(Theme.outlineButton(this, "기존 짧은 녹음 정리") { cleanupShortRecordings() })
        c.addView(Theme.hint(this, "지금까지 저장된 파일 중 위 기준보다 짧은 녹음을 한 번에 삭제합니다(보관 표시한 파일은 제외). 짧은 녹음 자동 삭제는 새로 녹음되는 파일에만 적용되므로, 이전 파일은 이 버튼으로 정리하세요."))
    }

    // ───────────────────────── 섹션: 녹음 시간대 (알람식) ─────────────────────────

    private val dayNames = listOf("일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일")
    private val dayShortEn = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    private fun buildScheduleSection(parent: LinearLayout) {
        val c = Theme.section(this, parent, "녹음 시간대", expanded = false)
        if (!Pro.isPro) {
            c.addView(Theme.hint(this, "녹음 시간대 예약은 Pro 전용 기능입니다."))
            c.addView(Theme.primaryButton(this, "Pro 잠금 해제") { startActivity(Intent(this, ProActivity::class.java)) })
            return
        }
        c.addView(Theme.hint(this, "요일·날짜별로 녹음할 시간을 알람처럼 지정합니다. 시작=끝(예: 00:00~00:00)이면 24시간 녹음, 체크 해제하면 그 날은 녹음 안 함."))

        // 요일별
        c.addView(Theme.subHeader(this, "요일별"))
        for (d in 0..6) c.addView(buildDayRow(d))

        // 날짜별 (요일보다 우선)
        c.addView(Theme.divider(this).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(16), 0, dp(8))
        })
        c.addView(Theme.subHeader(this, "특정 날짜 (요일 설정보다 우선)"))
        val dateBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(Theme.secondaryButton(this, "＋ 날짜 지정 추가") {
            pickDate { dk ->
                Prefs.setDateOverride(this, dk, Prefs.DaySchedule(true, 0, 0))
                refreshDateOverrides(dateBox)
            }
        })
        c.addView(dateBox)
        refreshDateOverrides(dateBox)
    }

    private fun buildDayRow(d: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        var sched = Prefs.getDaySchedule(this, d)
        val startBtn = Theme.timeChip(this, fmtMin(sched.startMin)) {}
        val endBtn = Theme.timeChip(this, fmtMin(sched.endMin)) {}

        val cb = Theme.checkBox(this, if (I18n.en) dayShortEn[d] else dayNames[d].take(1)).apply {
            isChecked = sched.enabled
            layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnCheckedChangeListener { _, on ->
                sched = sched.copy(enabled = on)
                Prefs.setDaySchedule(this@MainActivity, d, sched)
            }
        }
        startBtn.setOnClickListener {
            openTimePicker(sched.startMin) { m ->
                sched = sched.copy(startMin = m)
                Prefs.setDaySchedule(this, d, sched)
                startBtn.text = fmtMin(m)
            }
        }
        endBtn.setOnClickListener {
            openTimePicker(sched.endMin) { m ->
                sched = sched.copy(endMin = m)
                Prefs.setDaySchedule(this, d, sched)
                endBtn.text = fmtMin(m)
            }
        }
        row.addView(cb)
        row.addView(startBtn)
        row.addView(TextView(this).apply {
            text = "~"; setTextColor(Theme.TEXT_MUTED); setPadding(dp(4), 0, dp(4), 0)
        })
        row.addView(endBtn)
        return row
    }

    private fun refreshDateOverrides(box: LinearLayout) {
        box.removeAllViews()
        val dates = Prefs.getOverrideDates(this)
        if (dates.isEmpty()) {
            box.addView(Theme.hint(this, "지정한 날짜가 없습니다."))
            return
        }
        for (dk in dates) box.addView(buildDateRow(dk, box))
    }

    private fun buildDateRow(dk: String, box: LinearLayout): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        var sched = Prefs.getDateOverride(this, dk) ?: Prefs.DaySchedule(true, 0, 0)
        val startBtn = Theme.timeChip(this, fmtMin(sched.startMin)) {}
        val endBtn = Theme.timeChip(this, fmtMin(sched.endMin)) {}

        val cb = Theme.checkBox(this, prettyDate(dk)).apply {
            isChecked = sched.enabled
            layoutParams = LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnCheckedChangeListener { _, on ->
                sched = sched.copy(enabled = on)
                Prefs.setDateOverride(this@MainActivity, dk, sched)
            }
        }
        startBtn.setOnClickListener {
            openTimePicker(sched.startMin) { m ->
                sched = sched.copy(startMin = m)
                Prefs.setDateOverride(this, dk, sched)
                startBtn.text = fmtMin(m)
            }
        }
        endBtn.setOnClickListener {
            openTimePicker(sched.endMin) { m ->
                sched = sched.copy(endMin = m)
                Prefs.setDateOverride(this, dk, sched)
                endBtn.text = fmtMin(m)
            }
        }
        row.addView(cb)
        row.addView(startBtn)
        row.addView(TextView(this).apply {
            text = "~"; setTextColor(Theme.TEXT_MUTED); setPadding(dp(4), 0, dp(4), 0)
        })
        row.addView(endBtn)
        row.addView(Theme.smallButton(this, "삭제", danger = true) {
            Prefs.removeDateOverride(this, dk)
            refreshDateOverrides(box)
        })
        return row
    }

    private fun openTimePicker(initMin: Int, onSet: (Int) -> Unit) {
        val h = (initMin / 60).coerceIn(0, 23)
        val m = initMin % 60
        TimePickerDialog(this, { _, hh, mm -> onSet(hh * 60 + mm) }, h, m, true).show()
    }

    private fun pickDate(onSet: (String) -> Unit) {
        val c = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, mo, d -> onSet("%04d%02d%02d".format(y, mo + 1, d)) },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun fmtMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    private fun prettyDate(dk: String): String =
        if (dk.length == 8) "${dk.substring(4, 6)}.${dk.substring(6, 8)}" else dk

    private fun fmtRetention(h: Int): String {
        val days = h / 24
        val rem = h % 24
        return when {
            h < 24 -> I18n.f("%d시간", h)
            rem == 0 -> I18n.f("%d일", days)
            else -> I18n.f("%d일 %d시간", days, rem)
        }
    }

    // ───────────────────────── 섹션: 위치 기반 녹음 ─────────────────────────

    private fun buildLocationSection(parent: LinearLayout) {
        val c = Theme.section(this, parent, "위치 기반 녹음", expanded = false)
        c.addView(Theme.hint(this, "지정한 장소 반경에 따라 녹음을 켜고 끕니다. 시간대 설정과 둘 다 만족할 때만 녹음돼요. 위치를 확인할 수 없으면(권한 없음·실내 등) 녹음하지 않습니다 — 지정한 곳 밖에서 녹음되지 않게 하는 쪽을 택했습니다."))
        c.addView(Theme.hint(this, "⚠ 보조 기능입니다. 위치는 앱을 쓰는 동안에만 확인할 수 있어, 화면을 끄고 한참 지나면 위치를 알 수 없게 되고 그동안은 녹음이 멈춥니다. 늘 켜 두는 상시 녹음에는 이 기능을 쓰지 마세요."))

        c.addView(Theme.checkBox(this, "위치 기반 녹음 사용").apply {
            isChecked = Prefs.isLocationEnabled(this@MainActivity)
            setOnCheckedChangeListener { btn, on ->
                if (on && !Pro.isPro) {
                    btn.isChecked = false   // Pro 전용 → 되돌리고 구매 화면
                    startActivity(Intent(this@MainActivity, ProActivity::class.java))
                } else if (on && !hasLocationPermission()) {
                    // 권한 없이 켜면 판정 불가로 녹음이 전부 막힌다. 켜기 전에 권한부터 받는다.
                    btn.isChecked = false
                    pendingLocationAction = {
                        Prefs.setLocationEnabled(this@MainActivity, true)
                        btn.isChecked = true
                    }
                    locationPermLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                } else {
                    Prefs.setLocationEnabled(this@MainActivity, on)
                }
            }
        })

        // 위치 확인 간격 — 실제로 재측위하는 게 아니라, 시스템이 마지막으로 알고 있는 위치를
        // 다시 읽는 주기다. '측위 간격'이라고 하면 이 주기마다 새로 측위하는 것처럼 읽힌다.
        val intervalLabel = Theme.body(this)
        fun refreshInterval() {
            intervalLabel.text = I18n.f("위치 확인 간격: %s", fmtInterval(Prefs.getLocationIntervalSec(this)))
        }
        c.addView(intervalLabel)
        c.addView(Theme.seekBar(this).apply {
            max = Prefs.MAX_LOC_INTERVAL_SEC - Prefs.MIN_LOC_INTERVAL_SEC
            progress = (Prefs.getLocationIntervalSec(this@MainActivity) - Prefs.MIN_LOC_INTERVAL_SEC)
                .coerceIn(0, Prefs.MAX_LOC_INTERVAL_SEC - Prefs.MIN_LOC_INTERVAL_SEC)
            setOnSeekBarChangeListener(simpleSeek { p ->
                Prefs.setLocationIntervalSec(this@MainActivity, Prefs.MIN_LOC_INTERVAL_SEC + p)
                refreshInterval()
            })
        })
        refreshInterval()
        c.addView(Theme.hint(this, "짧을수록 위치 변화에 빨리 반응하지만 배터리를 조금 더 씁니다. 권장 1~3분. (앱이 직접 측위하지는 않고, 시스템이 마지막으로 알고 있는 위치를 이 주기로 다시 읽습니다.)"))

        // 구역 목록
        c.addView(Theme.subHeader(this, "구역"))
        c.addView(Theme.primaryButton(this, "현재 위치로 구역 추가") { requestLocThenAddZone() }
            .also { Theme.setLeadingIcon(this, it, R.drawable.ic_location, Theme.TEXT) })
        c.addView(Theme.secondaryButton(this, "지도에서 구역 추가") { openMapPicker() }
            .also { Theme.setLeadingIcon(this, it, R.drawable.ic_map, Theme.TEXT) })
        val zonesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        locationZonesContainer = zonesBox
        c.addView(zonesBox)
        refreshZones()

        c.addView(Theme.hint(this, "실내·지하는 정확도가 낮으니 150~300m 권장. 여러 구역: ‘포함’ 구역이 하나라도 있으면 그 안에서만 녹음, ‘제외’ 구역 안에서는 항상 녹음 안 함."))
    }

    private fun fmtInterval(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return when {
            sec < 60 -> I18n.f("%d초", sec)
            s == 0 -> I18n.f("%d분", m)
            else -> I18n.f("%d분 %d초", m, s)
        }
    }

    private fun refreshZones() {
        val box = locationZonesContainer ?: return
        box.removeAllViews()
        val zones = Prefs.getZones(this)
        if (zones.isEmpty()) {
            box.addView(Theme.hint(this, "추가된 구역이 없습니다."))
            return
        }
        for (z in zones) box.addView(buildZoneCard(z))
    }

    private fun buildZoneCard(zone: Prefs.Zone): View {
        var z = zone
        val card = Theme.card(this)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameView = TextView(this).apply {
            text = z.name
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Theme.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(nameView)
        top.addView(Theme.smallButton(this, "이름") {
            renameZone(z) { updated -> z = updated; nameView.text = updated.name }
        })
        top.addView(Theme.smallButton(this, "삭제", danger = true) {
            Prefs.removeZone(this, z.id)
            refreshZones()
        })
        card.addView(top)

        card.addView(Theme.body(this, "중심: %.5f, %.5f".format(z.lat, z.lng)).apply {
            textSize = 12f; setTextColor(Theme.TEXT_MUTED)
        })

        // 모드 토글
        val modeBtn = Theme.secondaryButton(this, zoneModeText(z.mode)) {}
        modeBtn.setOnClickListener {
            val nm = if (z.mode == Prefs.LOC_MODE_ONLY_HERE)
                Prefs.LOC_MODE_NOT_HERE else Prefs.LOC_MODE_ONLY_HERE
            z = z.copy(mode = nm)
            Prefs.updateZone(this, z)
            modeBtn.text = zoneModeText(nm)
        }
        card.addView(modeBtn)

        // 반경
        val rLabel = Theme.body(this)
        fun refreshR() { rLabel.text = I18n.f("반경: %dm", z.radius) }
        card.addView(rLabel)
        card.addView(Theme.seekBar(this).apply {
            max = Prefs.MAX_LOC_RADIUS - Prefs.MIN_LOC_RADIUS
            progress = (z.radius - Prefs.MIN_LOC_RADIUS)
                .coerceIn(0, Prefs.MAX_LOC_RADIUS - Prefs.MIN_LOC_RADIUS)
            setOnSeekBarChangeListener(simpleSeek { p ->
                z = z.copy(radius = Prefs.MIN_LOC_RADIUS + p)
                Prefs.updateZone(this@MainActivity, z)
                refreshR()
            })
        })
        refreshR()
        return card
    }

    private fun zoneModeText(mode: Int): String =
        if (mode == Prefs.LOC_MODE_ONLY_HERE) "이 구역에서만 녹음 (탭해 변경)"
        else "이 구역에선 녹음 금지 (탭해 변경)"

    private fun renameZone(z: Prefs.Zone, onDone: (Prefs.Zone) -> Unit) {
        val input = EditText(this).apply { setText(z.name) }
        AlertDialog.Builder(this)
            .setTitle(I18n.t("구역 이름"))
            .setView(input)
            .setPositiveButton(I18n.t("저장")) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { z.name }
                val updated = z.copy(name = name)
                Prefs.updateZone(this, updated)
                onDone(updated)
            }
            .setNegativeButton(I18n.t("취소"), null)
            .show()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestLocThenAddZone() {
        if (hasLocationPermission()) {
            addZoneFromCurrent()
        } else {
            pendingLocationAction = { addZoneFromCurrent() }
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    private fun openMapPicker() {
        val intent = Intent(this, MapPickerActivity::class.java)
        // 지도를 현재 위치 근처에서 열 수 있게 마지막 위치를 넘김(권한 있을 때)
        if (hasLocationPermission()) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val best = lm?.let { bestLastKnown(it) }
            if (best != null) {
                intent.putExtra(MapPickerActivity.EXTRA_LAT, best.latitude)
                intent.putExtra(MapPickerActivity.EXTRA_LNG, best.longitude)
            }
        }
        mapPickerLauncher.launch(intent)
    }

    private fun addZoneFromCurrent() {
        captureLocation { loc ->
            val n = Prefs.getZones(this).size + 1
            Prefs.addZone(
                this, I18n.f("구역 %d", n), loc.latitude, loc.longitude,
                Prefs.getLocationRadius(this), Prefs.getLocationMode(this)
            )
            refreshZones()
            Toast.makeText(this, I18n.t("구역을 추가했습니다"), Toast.LENGTH_SHORT).show()
        }
    }

    // 권한은 hasLocationPermission() 으로 먼저 확인하고, 만약을 대비해 SecurityException 도
    // 잡는다. lint 는 커스텀 헬퍼를 권한 체크로 인식하지 못해 오탐하므로 억제한다.
    @SuppressLint("MissingPermission")
    private fun captureLocation(onResult: (Location) -> Unit) {
        if (!hasLocationPermission()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val best = bestLastKnown(lm)
        if (best != null) { onResult(best); return }
        Toast.makeText(this, I18n.t("현재 위치를 찾는 중…"), Toast.LENGTH_SHORT).show()
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            Toast.makeText(this, I18n.t("위치 서비스를 켜고 다시 시도하세요"), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(location: Location) { onResult(location) }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                @Deprecated("deprecated")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            }, mainLooper)
        } catch (_: SecurityException) {
            Toast.makeText(this, I18n.t("위치 권한을 확인하세요"), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, I18n.t("현재 위치를 가져올 수 없습니다"), Toast.LENGTH_SHORT).show()
        }
    }

    // 호출부(captureLocation/openMapPicker)가 hasLocationPermission() 으로 가드하며,
    // 여기서도 예외를 모두 잡는다. lint 오탐 억제.
    @SuppressLint("MissingPermission")
    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: Location? = null
        for (p in providers) {
            val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
            if (loc != null && (best == null || loc.time > best!!.time)) best = loc
        }
        return best
    }

    // ───────────────────────── 섹션: 녹음 품질 ─────────────────────────

    private fun buildQualitySection(parent: LinearLayout) {
        val c = Theme.section(this, parent, "녹음 품질", expanded = false)
        val options = listOf(
            "낮음 (16kbps, 최소 용량)" to 16_000,
            "보통 (32kbps, 음성 권장)" to 32_000,
            "높음 (64kbps, 또렷함)" to 64_000,
            "최고 (128kbps, 용량 큼) · Pro" to 128_000,
        )
        // 무료가 최고(128k)로 저장돼 있었다면 높음으로 낮춤
        if (!Pro.isPro && Prefs.getBitRate(this) > 64_000) Prefs.setBitRate(this, 64_000)
        val label = Theme.body(this)
        fun refresh() {
            val name = options.firstOrNull { it.second == Prefs.getBitRate(this) }?.first
                ?: "${Prefs.getBitRate(this) / 1000}kbps"
            label.text = I18n.f("현재: %s", I18n.t(name))
        }
        c.addView(label)
        options.forEach { (name, rate) ->
            c.addView(Theme.secondaryButton(this, name) {
                if (rate == 128_000 && !Pro.isPro) {
                    startActivity(Intent(this@MainActivity, ProActivity::class.java))
                } else {
                    Prefs.setBitRate(this@MainActivity, rate)
                    refresh()
                    Toast.makeText(this@MainActivity, I18n.t("다음 녹음부터 적용됩니다"), Toast.LENGTH_SHORT).show()
                }
            })
        }
        refresh()
    }

    // ───────────────────────── 섹션: 저장공간 ─────────────────────────

    private fun buildStorageSection(parent: LinearLayout) {
        val c = Theme.section(this, parent, "저장공간", expanded = false)

        // 저장 위치
        c.addView(Theme.subHeader(this, "파일 저장 위치"))
        val locLabel = Theme.body(this)
        val pathLabel = Theme.body(this).apply { textSize = 11f; setTextColor(Theme.TEXT_FAINT) }
        fun refreshLoc() {
            locLabel.text = I18n.t("현재: ") + I18n.t(when (Prefs.getStorageLocation(this)) {
                Prefs.STORAGE_EXTERNAL -> "외부 저장소 (파일 관리자에서 보임)"
                Prefs.STORAGE_SD -> "SD 카드"
                else -> "기기 내부 (앱 전용·비공개)"
            })
            pathLabel.text = Storage.currentRootPath(this)
        }
        c.addView(locLabel)
        val locRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun changeLoc(loc: Int) {
            Prefs.setStorageLocation(this, loc)
            refreshLoc()
            refreshFileList()
            Toast.makeText(this, I18n.t("저장 위치 변경됨 (기존 파일은 이동되지 않음)"), Toast.LENGTH_SHORT).show()
        }
        locRow.addView(Theme.smallButton(this, "내부") { changeLoc(Prefs.STORAGE_INTERNAL) })
        locRow.addView(Theme.smallButton(this, "외부(공유)") { changeLoc(Prefs.STORAGE_EXTERNAL) })
        if (Storage.hasSdCard(this)) {
            locRow.addView(Theme.smallButton(this, "SD카드") { changeLoc(Prefs.STORAGE_SD) })
        }
        c.addView(locRow)
        c.addView(pathLabel)
        refreshLoc()
        c.addView(Theme.hint(this, "‘외부(공유)’로 두면 파일 관리자/USB로 녹음 파일에 바로 접근할 수 있습니다(앱 삭제 시 함께 삭제). 위치를 바꿔도 기존 파일은 자동 이동되지 않습니다."))

        c.addView(Theme.divider(this).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(12), 0, dp(8))
        })

        // 자동 삭제 보관 기간
        val retLabel = Theme.body(this)
        fun refreshRet() {
            val h = Prefs.getRetentionHours(this)
            retLabel.text = I18n.f("자동 삭제 보관 기간: %s", fmtRetention(h))
        }
        c.addView(retLabel)
        // 무료는 보관기간 상한(3일), Pro는 30일까지
        val retCap = if (Pro.isPro) Prefs.MAX_RETENTION_HOURS else FREE_RETENTION_MAX_HOURS
        if (!Pro.isPro && Prefs.getRetentionHours(this) > retCap) {
            Prefs.setRetentionHours(this, retCap)   // 이전 Pro 값 클램프
        }
        c.addView(Theme.seekBar(this).apply {
            max = retCap - Prefs.MIN_RETENTION_HOURS
            progress = (Prefs.getRetentionHours(this@MainActivity) - Prefs.MIN_RETENTION_HOURS)
                .coerceIn(0, retCap - Prefs.MIN_RETENTION_HOURS)
            setOnSeekBarChangeListener(simpleSeek { p ->
                Prefs.setRetentionHours(this@MainActivity, Prefs.MIN_RETENTION_HOURS + p)
                refreshRet()
            })
        })
        refreshRet()
        c.addView(Theme.hint(this, "이 기간이 지난 파일은 자동 삭제됩니다(보호 파일 제외)."))
        if (!Pro.isPro) {
            c.addView(Theme.outlineButton(this, "Pro로 30일까지 보관") {
                startActivity(Intent(this, ProActivity::class.java))
            })
        }

        c.addView(Theme.divider(this).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(12), 0, dp(8))
        })

        val info = Theme.body(this)
        fun refreshInfo() {
            val usedMb = Storage.usedBytes(this) / (1024 * 1024)
            val freeMb = Storage.freeBytes(this) / (1024 * 1024)
            info.text = I18n.f("녹음 사용량 약 %dMB · 기기 남은 공간 약 %dMB", usedMb, freeMb)
        }
        c.addView(info)
        refreshInfo()

        val minLabel = Theme.body(this)
        fun refreshMin() { minLabel.text = I18n.f("최소 확보 공간: %dMB", Prefs.getMinFreeMb(this)) }
        c.addView(minLabel)
        c.addView(Theme.seekBar(this).apply {
            max = 1900 // 100~2000MB
            progress = (Prefs.getMinFreeMb(this@MainActivity) - 100).toInt().coerceIn(0, 1900)
            setOnSeekBarChangeListener(simpleSeek { p ->
                Prefs.setMinFreeMb(this@MainActivity, (100 + p).toLong())
                refreshMin()
            })
        })
        refreshMin()
        c.addView(Theme.hint(this, "남은 공간이 이 값보다 적어지면, 보호 안 된 오래된 파일부터 자동으로 비웁니다."))
    }

    // ───────────────────────── 섹션: 배터리 사용량 ─────────────────────────

    private fun buildBatterySection(parent: LinearLayout) {
        val c = Theme.section(this, parent, "배터리 사용량 (일별)", expanded = false)
        if (!Pro.isPro) {
            c.addView(Theme.hint(this, "일별 배터리 사용량은 Pro 전용 기능입니다."))
            c.addView(Theme.primaryButton(this, "Pro 잠금 해제") {
                startActivity(Intent(this, ProActivity::class.java))
            })
            return
        }
        c.addView(Theme.hint(this, "녹음이 동작하는 동안 기기 배터리 잔량 변화를 표본해 일별 소모(기기 전체 기준)를 추정합니다. 앱별 정확치는 시스템 설정 ‘배터리’에서 확인하세요."))
        c.addView(Theme.outlineButton(this, "기기 배터리 설정 열기") { openBatterySettings() }
            .also { Theme.setLeadingIcon(this, it, R.drawable.ic_battery, Theme.TEXT) })
        c.addView(Theme.secondaryButton(this, "새로고침") { refreshBattery() })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        batteryContainer = box
        c.addView(box)
        refreshBattery()
    }

    private fun refreshBattery() {
        val box = batteryContainer ?: return
        box.removeAllViews()
        val days = Prefs.getBatteryDays(this)
        if (days.isEmpty()) {
            box.addView(Theme.hint(this, "아직 기록이 없습니다. 녹음을 켜두면 일별 소모가 쌓입니다."))
            return
        }
        for ((dk, pct) in days) {
            val pretty = if (dk.length == 8)
                "${dk.substring(0, 4)}.${dk.substring(4, 6)}.${dk.substring(6, 8)}" else dk
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = pretty
                textSize = 14f
                setTextColor(Theme.TEXT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                Theme.setLeadingIcon(this@MainActivity, this, R.drawable.ic_calendar, Theme.TEXT_MUTED, 15)
            })
            row.addView(TextView(this).apply {
                text = "−${"%.0f".format(pct)}%"
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (pct >= 20f) Theme.NEGATIVE else Theme.ACCENT)
            })
            box.addView(row)
            box.addView(Theme.divider(this))
        }
    }

    private fun openBatterySettings() {
        // 기기 배터리 사용량 화면 → 안 되면 앱 정보 화면(거기서 배터리 항목)
        try {
            startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(this, I18n.t("배터리 설정을 열 수 없습니다"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sampleBatteryThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastBattSampleTs < 30_000) return
        lastBattSampleTs = now
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        Prefs.recordBatterySample(this, level)
    }

    // ───────────────────────── 섹션: 보안 ─────────────────────────

    private fun buildSecuritySection(parent: LinearLayout) {
        val c = Theme.section(this, parent, "보안 (앱 잠금)", expanded = false)
        // 앱 잠금 — 무료
        c.addView(Theme.checkBox(this, "앱 잠금 (지문/PIN)").apply {
            isChecked = Prefs.isAppLockEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAppLockEnabled(this@MainActivity, checked)
            }
        })
        c.addView(Theme.hint(this, "켜면 앱에 들어올 때 지문/PIN 인증을 요구합니다."))

        // 프라이버시 모드(화면 캡처 차단) — Pro 전용
        if (Pro.isPro) {
            c.addView(Theme.checkBox(this, "프라이버시 모드").apply {
                isChecked = Prefs.isPrivacyMode(this@MainActivity)
                setOnCheckedChangeListener { _, checked ->
                    Prefs.setPrivacyMode(this@MainActivity, checked)
                    recreate()  // 화면 보안 플래그 즉시 반영
                }
            })
            c.addView(Theme.hint(this, "켜면 화면 캡처·녹화가 차단되고, 최근 앱 목록에서 화면이 가려집니다. (녹음 중 알림은 정책상 항상 표시됩니다.)"))
        } else {
            c.addView(Theme.hint(this, "프라이버시 모드(화면 캡처 차단)는 Pro 전용 기능입니다."))
            c.addView(Theme.outlineButton(this, "프라이버시 모드는 Pro") { startActivity(Intent(this, ProActivity::class.java)) })
        }
    }

    // ───────────────────────── 섹션: 안정성 ─────────────────────────

    private fun buildStabilitySection(parent: LinearLayout) {
        val c = Theme.section(this, parent, "안정성 (백그라운드 유지)", expanded = false)
        c.addView(Theme.hint(this, "삼성·샤오미·오포 등 일부 기기는 백그라운드 녹음을 강제 종료합니다. 아래 설정을 해두면 녹음이 잘 유지됩니다."))
        c.addView(Theme.outlineButton(this, "배터리 최적화 제외 설정") { requestIgnoreBatteryOptimization() })
        c.addView(Theme.outlineButton(this, "백그라운드 실행 / 자동 시작 설정") { openAutoStartSettings() })
        c.addView(Theme.secondaryButton(this, "녹음이 멈춰요? 기기별 설정 보기") { showBackgroundHelpDialog() })

        // 오류 진단 (온디바이스 크래시 로그 — 외부로 전송하지 않음)
        c.addView(Theme.divider(this).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(12), 0, dp(8))
        })
        c.addView(Theme.subHeader(this, "오류 진단"))
        c.addView(Theme.hint(this, "앱이 예기치 않게 종료되면 그 원인 기록을 기기 안에만 저장합니다(외부로 전송하지 않음). 문제가 있을 때 아래에서 기록을 공유해 알려 주세요."))
        val crashBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        crashDiagBox = crashBox
        c.addView(crashBox)
        refreshCrashDiag()
    }

    private fun refreshCrashDiag() {
        val box = crashDiagBox ?: return
        box.removeAllViews()
        val n = CrashLogger.count(this)
        if (n == 0) {
            box.addView(Theme.hint(this, "기록된 오류가 없습니다."))
            return
        }
        box.addView(Theme.body(this).apply {
            text = I18n.f("기록된 오류 %d건", n)
            setTextColor(Theme.NEGATIVE)
        })
        box.addView(Theme.outlineButton(this, "오류 로그 공유") {
            Share.shareLogFiles(this, CrashLogger.list(this))
        })
        box.addView(Theme.smallButton(this, "오류 로그 지우기", danger = true) {
            CrashLogger.clear(this)
            refreshCrashDiag()
            Toast.makeText(this, I18n.t("오류 로그를 지웠습니다"), Toast.LENGTH_SHORT).show()
        })
    }

    private fun openAutoStartSettings() {
        val m = android.os.Build.MANUFACTURER.lowercase()
        val candidates = mutableListOf<ComponentName>()
        when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                candidates.add(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            m.contains("oppo") || m.contains("realme") -> {
                candidates.add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                candidates.add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
                candidates.add(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
            }
            m.contains("vivo") ->
                candidates.add(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
            m.contains("huawei") || m.contains("honor") -> {
                candidates.add(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                candidates.add(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
            }
        }
        for (cn in candidates) {
            try {
                startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* 다음 후보 시도 */ }
        }
        // 폴백: 앱 정보 화면(여기서 배터리·자동시작 진입)
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            Toast.makeText(this, I18n.t("설정을 열 수 없습니다"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBackgroundHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(I18n.t("기기별 설정 안내"))
            .setMessage(oemGuide())
            .setPositiveButton(I18n.t("확인"), null)
            .show()
    }

    private fun oemGuide(): String {
        val m = android.os.Build.MANUFACTURER.lowercase()
        val en = I18n.en
        return when {
            m.contains("samsung") -> if (en)
                "Samsung\n\n• Settings → Battery → Background usage limits → make sure HelloRecorder is NOT in 'Sleeping/Deep sleeping apps'.\n• Settings → Apps → HelloRecorder → Battery → Unrestricted.\n• In Recents, tap the app icon → Keep open (lock)."
            else
                "삼성\n\n• 설정 → 배터리 → 백그라운드 사용 제한 → 이 앱이 ‘절전/딥슬립 앱’ 목록에 없는지 확인.\n• 설정 → 앱 → HelloRecorder → 배터리 → ‘제한 없음’.\n• 최근 앱에서 앱 아이콘 탭 → ‘이 앱 잠금’."
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> if (en)
                "Xiaomi/Redmi/POCO (MIUI/HyperOS)\n\n• Security → Permissions → Autostart → enable HelloRecorder.\n• Settings → Battery → App battery saver → HelloRecorder → No restrictions.\n• In Recents, swipe down on the app → Lock."
            else
                "샤오미/레드미/포코 (MIUI/HyperOS)\n\n• 보안 → 권한 → 자동 시작(Autostart)에서 이 앱 허용.\n• 설정 → 배터리 → 앱 배터리 절약 → 이 앱 → ‘제한 없음’.\n• 최근 앱에서 앱을 아래로 당겨 ‘잠금’."
            m.contains("oppo") || m.contains("realme") -> if (en)
                "OPPO/realme (ColorOS)\n\n• Settings → Battery → App battery management → HelloRecorder → allow Background running & Auto-launch."
            else
                "오포/리얼미 (ColorOS)\n\n• 설정 → 배터리 → 앱 배터리 관리 → 이 앱 → 백그라운드 실행·자동 시작 허용."
            m.contains("vivo") -> if (en)
                "vivo (Funtouch/OriginOS)\n\n• Settings → Battery → Background power consumption management → allow HelloRecorder.\n• i Manager → Autostart manager → enable."
            else
                "비보 (Funtouch/OriginOS)\n\n• 설정 → 배터리 → 백그라운드 전력 소모 관리 → 이 앱 허용.\n• i Manager → 자동 시작 관리 → 켜기."
            m.contains("huawei") || m.contains("honor") -> if (en)
                "Huawei/Honor\n\n• Settings → Battery → App launch → HelloRecorder → Manage manually → turn ON Auto-launch, Secondary launch, Run in background."
            else
                "화웨이/아너\n\n• 설정 → 배터리 → 앱 시작 관리 → 이 앱 → ‘수동 관리’ → 자동 시작·보조 활성화·백그라운드 실행 모두 켜기."
            m.contains("oneplus") -> if (en)
                "OnePlus (OxygenOS)\n\n• Settings → Battery → Battery optimization → HelloRecorder → Don't optimize.\n• Turn off Advanced/Deep optimization."
            else
                "원플러스 (OxygenOS)\n\n• 설정 → 배터리 → 배터리 최적화 → 이 앱 → ‘최적화 안 함’.\n• ‘고급/딥 최적화’ 끄기."
            else -> if (en)
                "General\n\n• Settings → Apps → HelloRecorder → Battery → Unrestricted.\n• Disable battery optimization for this app.\n• If available, allow Auto-start and lock the app in Recents."
            else
                "일반\n\n• 설정 → 앱 → HelloRecorder → 배터리 → ‘제한 없음’.\n• 이 앱의 배터리 최적화 끄기.\n• 자동 시작 옵션이 있으면 허용하고, 최근 앱에서 앱을 잠그세요."
        }
    }

    // ───────────────────────── 섹션: 녹음 파일 ─────────────────────────

    private fun buildFilesSection(parent: LinearLayout) {
        parent.addView(Theme.sectionTitle(this, "파일"))

        // 주간 달력 스트립 (날짜별 빠른 탐색) — Pro 전용
        if (Pro.isPro) {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            weekStripContainer = strip
            parent.addView(strip)
            buildWeekStrip()
        } else {
            weekStripContainer = null
        }

        // 선택 도구막대 (좁은 화면 대비 가로 스크롤)
        val selRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selRow.addView(Theme.smallButton(this, "전체 선택") { selectAllShown() })
        selRow.addView(Theme.smallButton(this, "선택 해제") { clearSelection() })
        selRow.addView(Theme.smallButton(this, "선택 삭제", danger = true) { deleteSelected() })
        selRow.addView(Theme.smallButton(this, "선택 보관") { protectSelected(true) })
        selRow.addView(Theme.smallButton(this, "보관 해제") { protectSelected(false) })
        parent.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(selRow)
            setPadding(0, 0, 0, dp(4))
        })

        val rowTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowTop.addView(Theme.secondaryButton(this, "새로고침") { refreshFileList() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, dp(4), dp(6), dp(8)) }
        })
        rowTop.addView(Theme.outlineButton(this, "백업 내보내기") { exportProtected() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(6), dp(4), 0, dp(8)) }
            Theme.setLeadingIcon(this@MainActivity, this, R.drawable.ic_upload, Theme.TEXT, 16)
        })
        parent.addView(rowTop)

        // 정렬(Pro) + 검색(무료) 한 줄 — 무료는 최신순 고정 + 검색 사용 가능, 캘린더/정렬은 Pro
        searchQuery = ""
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (Pro.isPro) {
            sortButton = Theme.smallButton(this, sortLabel()) { showSortDialog() }
            searchRow.addView(sortButton)
        } else {
            sortButton = null
            // 무료: 정렬 자리에 Pro 안내(정렬·캘린더), 검색은 그대로 사용
            searchRow.addView(Theme.smallButton(this, "정렬·캘린더 Pro") {
                startActivity(Intent(this, ProActivity::class.java))
            })
        }
        val searchEt = Theme.editText(this, "검색 (파일명, 라벨, 날짜, 내용)").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(6), dp(4), 0, dp(8)) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    refreshFileList()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        searchRow.addView(searchEt)
        parent.addView(searchRow)

        // 카테고리 필터 칩
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        categoryChips = chips
        parent.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
            setPadding(0, 0, 0, dp(4))
        })
        refreshCategoryChips()

        // 내용(전사) 검색 결과 — 검색어 입력 시 세그먼트 히트를 파일 목록 위에 표시
        transcriptHits = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(transcriptHits)

        fileListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(fileListContainer)
    }

    /** 전사 인덱스에서 검색어와 맞는 발화 세그먼트를 찾아 표시. 탭하면 그 위치로 재생 이동. */
    private fun refreshTranscriptHits() {
        val c = transcriptHits ?: return
        c.removeAllViews()
        val q = searchQuery
        if (q.length < 2) return   // 한 글자는 잡음 매칭이 너무 많음
        val hits = try { TranscriptStore.search(this, q, 30) } catch (_: Exception) { emptyList() }
        if (hits.isEmpty()) return

        fun fmtMs(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
        c.addView(Theme.dateHeader(this, I18n.f("🔎 내용 검색 (%d)", hits.size)))
        for (h in hits) {
            val f = Storage.fileForKey(this, h.key)
            if (!f.exists()) continue
            val day = h.key.substringBefore('/')
            val pretty = if (day.length == 8) "${day.substring(4, 6)}.${day.substring(6, 8)}" else day
            c.addView(Theme.body(this).apply {
                text = "$pretty · ${fmtMs(h.startMs)} — ${h.text}"
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_PATH, f.absolutePath)
                            .putExtra(PlayerActivity.EXTRA_SEEK_MS, h.startMs)
                    )
                }
            })
        }
        c.addView(Theme.hint(this, "결과를 탭하면 그 발화 위치부터 재생됩니다. (자동 전사된 파일에서만 검색)"))
    }

    // ── 정렬 ──

    private fun sortLabel(): String {
        val name = when (Prefs.getSortMode(this)) {
            Prefs.SORT_OLD -> "오래된순"
            Prefs.SORT_NAME -> "이름순"
            Prefs.SORT_SIZE -> "용량순"
            Prefs.SORT_DUR -> "길이순"
            else -> "최신순"
        }
        return I18n.t("정렬") + ": " + I18n.t(name)
    }

    private fun showSortDialog() {
        val modes = listOf(
            Prefs.SORT_NEW to "최신순", Prefs.SORT_OLD to "오래된순",
            Prefs.SORT_NAME to "이름순", Prefs.SORT_SIZE to "용량순", Prefs.SORT_DUR to "길이순",
        )
        val labels = modes.map { I18n.t(it.second) }.toTypedArray()
        val cur = modes.indexOfFirst { it.first == Prefs.getSortMode(this) }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(I18n.t("정렬"))
            .setSingleChoiceItems(labels, cur) { d, which ->
                Prefs.setSortMode(this, modes[which].first)
                sortButton?.text = sortLabel()
                refreshFileList()
                d.dismiss()
            }
            .setNegativeButton(I18n.t("취소"), null)
            .show()
    }

    private fun ckOf(f: File): String = "${f.absolutePath}:${f.lastModified()}"

    /** 파일 길이(ms). 한 번의 메타데이터 읽기로 ms·표시문자열 캐시를 함께 채운다(블로킹). */
    private fun durationMs(f: File): Long {
        val ck = ckOf(f)
        durationMsCache[ck]?.let { return it }
        // setDataSource 가 던지면(손상·삭제 파일) release() 가 건너뛰어져 네이티브 객체가 샌다.
        // finally 로 반드시 해제한다.
        val mmr = android.media.MediaMetadataRetriever()
        val ms = try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
        finally { try { mmr.release() } catch (_: Exception) {} }
        durationMsCache[ck] = ms
        durationCache[ck] = if (ms > 0) { val s = ms / 1000; "%d:%02d".format(s / 60, s % 60) } else "--:--"
        return ms
    }

    /**
     * .lvl 활동 프로필을 집계한 짧은 태그(예: "대화 약 3분", "대부분 무음"). 블로킹.
     * 프로필 없음(구버전) 또는 소리는 있으나 음성 미분석(음성 모드 꺼짐)이면 "" (오해 방지).
     */
    private fun activityTag(f: File): String {
        val ck = ckOf(f)
        tagCache[ck]?.let { return it }
        val prof = Storage.readActivityProfile(f)
        val tag = if (prof == null || prof.voice.isEmpty()) {
            ""
        } else {
            val n = prof.voice.size
            val voiceCount = prof.voice.count { it }
            val loudCount = prof.levels.count { it >= 0.12f }
            when {
                voiceCount > 0 -> {
                    val durMs = durationMsCache[ck] ?: durationMs(f)
                    val base = if (durMs > 0) durMs else n * 500L
                    val speechSec = (base / 1000.0 * voiceCount / n).toLong().coerceAtLeast(1)
                    if (speechSec >= 60) I18n.f("대화 약 %d분", speechSec / 60)
                    else I18n.f("대화 약 %d초", speechSec)
                }
                loudCount.toFloat() / n < 0.05f -> I18n.t("대부분 무음")
                else -> ""
            }
        }
        tagCache[ck] = tag
        return tag
    }

    private fun applySort(files: List<File>): List<File> {
        val mode = if (Pro.isPro) Prefs.getSortMode(this) else Prefs.SORT_NEW
        return when (mode) {
            Prefs.SORT_OLD -> files.sortedBy { it.lastModified() }
            Prefs.SORT_NAME -> files.sortedBy { it.name }
            Prefs.SORT_SIZE -> files.sortedByDescending { it.length() }
            Prefs.SORT_DUR -> files.sortedByDescending { durationMs(it) }
            else -> files.sortedByDescending { it.lastModified() }
        }
    }

    // ── 카테고리(폴더 정리) ──

    private fun refreshCategoryChips() {
        val box = categoryChips ?: return
        box.removeAllViews()
        val cats = Prefs.getCategories(this)
        if (cats.isEmpty()) return   // 카테고리 없으면 칩 숨김
        fun chip(label: String, value: String?) {
            val selected = selectedCategory == value
            box.addView(Theme.smallButton(this, label) {
                selectedCategory = value
                refreshCategoryChips()
                refreshFileList()
            }.apply { if (selected) Theme.setPillColor(this@MainActivity, this, Theme.ACCENT) })
        }
        chip(I18n.t("전체"), null)
        for (c in cats) chip(c, c)
    }

    private fun showCategoryDialog(key: String) {
        val cats = Prefs.getCategories(this)
        val items = (listOf(I18n.t("없음")) + cats + listOf(I18n.t("＋ 새 카테고리"))).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(I18n.t("카테고리"))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { Prefs.setCategory(this, key, ""); afterCategoryChange() }
                    items.size - 1 -> promptNewCategory(key)
                    else -> { Prefs.setCategory(this, key, cats[which - 1]); afterCategoryChange() }
                }
            }
            .show()
    }

    private fun promptNewCategory(key: String) {
        // 무료는 카테고리 2개까지, 그 이상은 Pro
        if (!Pro.isPro && Prefs.getCategories(this).size >= FREE_CATEGORY_LIMIT) {
            startActivity(Intent(this, ProActivity::class.java)); return
        }
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(I18n.t("새 카테고리"))
            .setView(input)
            .setPositiveButton(I18n.t("저장")) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { Prefs.setCategory(this, key, name); afterCategoryChange() }
            }
            .setNegativeButton(I18n.t("취소"), null)
            .show()
    }

    private fun afterCategoryChange() {
        refreshCategoryChips()
        refreshFileList()
    }

    // ── 주간 달력 스트립 ──

    private fun recordedDayKeys(): Set<String> =
        Storage.listDayDirs(this)
            .filter { dir -> dir.listFiles()?.any { it.isFile && it.name.endsWith(".m4a") } == true }
            .map { it.name }.toSet()

    private fun buildWeekStrip() {
        val box = weekStripContainer ?: return
        box.removeAllViews()
        val recorded = recordedDayKeys()
        val weekdays = if (I18n.en) listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        else listOf("일", "월", "화", "수", "목", "금", "토")

        val cal = Calendar.getInstance().apply {
            timeInMillis = weekAnchorMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) cal.add(Calendar.DAY_OF_MONTH, -1)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(stripArrow("‹") { weekAnchorMillis -= 7L * 86400_000L; buildWeekStrip() }
            .apply { contentDescription = I18n.t("이전 주") })
        for (i in 0..6) {
            val dayCal = cal.clone() as Calendar
            dayCal.add(Calendar.DAY_OF_MONTH, i)
            val dk = "%04d%02d%02d".format(
                dayCal.get(Calendar.YEAR), dayCal.get(Calendar.MONTH) + 1, dayCal.get(Calendar.DAY_OF_MONTH)
            )
            val dow = dayCal.get(Calendar.DAY_OF_WEEK) - 1
            val cell = buildDayCell(
                weekdays[dow], dayCal.get(Calendar.DAY_OF_MONTH), dow,
                hasRec = recorded.contains(dk), selected = dk == selectedDateKey
            ) {
                selectedDateKey = if (selectedDateKey == dk) null else dk
                refreshFileList()
            }
            cell.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(cell)
        }
        row.addView(stripArrow("›") { weekAnchorMillis += 7L * 86400_000L; buildWeekStrip() }
            .apply { contentDescription = I18n.t("다음 주") })
        box.addView(row)

        if (selectedDateKey != null) {
            box.addView(Theme.smallButton(this, "전체 보기") { selectedDateKey = null; refreshFileList() })
        }
    }

    private fun stripArrow(glyph: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = glyph
        textSize = 22f
        setTextColor(Theme.TEXT_MUTED)
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dayOfWeekColor(dow: Int, default: Int): Int = when (dow) {
        0 -> android.graphics.Color.parseColor("#FF6B6B")  // 일요일 빨강
        6 -> android.graphics.Color.parseColor("#5B9BFF")  // 토요일 파랑
        else -> default
    }

    private fun buildDayCell(
        weekday: String, day: Int, dow: Int, hasRec: Boolean, selected: Boolean, onClick: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        isClickable = true
        setOnClickListener { onClick() }
        contentDescription = "$weekday $day" + (if (hasRec) ", " + I18n.t("녹음 있음") else "")
        addView(TextView(this@MainActivity).apply {
            text = weekday
            textSize = 11f
            setTextColor(dayOfWeekColor(dow, Theme.TEXT_MUTED))
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = day.toString()
            textSize = 14f
            gravity = Gravity.CENTER
            val sz = dp(30)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(0, dp(3), 0, dp(3)) }
            if (selected) {
                // 선택된 날은 키 컬러 원 + 흰 숫자
                setTextColor(Theme.TEXT)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.OVAL)
                    setColor(Theme.ACCENT)
                }
            } else {
                setTextColor(dayOfWeekColor(dow, Theme.TEXT))
            }
        })
        // 녹음 있는 날 점
        addView(View(this@MainActivity).apply {
            val ds = dp(5)
            layoutParams = LinearLayout.LayoutParams(ds, ds)
            background = android.graphics.drawable.GradientDrawable().apply {
                setShape(android.graphics.drawable.GradientDrawable.OVAL)
                setColor(if (hasRec) Theme.ACCENT else android.graphics.Color.TRANSPARENT)
            }
        })
    }

    private fun refreshFileList() {
        if (!::fileListContainer.isInitialized) return
        listGeneration++   // 진행 중인 비동기 길이 로딩이 옛 뷰를 갱신하지 않게
        buildWeekStrip()   // 달력 점 갱신
        fileListContainer.removeAllViews()
        shownKeys.clear()
        refreshTranscriptHits()   // 내용(전사) 검색 결과도 함께 갱신
        val dayDirs = Storage.listDayDirs(this)
        var shown = 0
        val cal = Calendar.getInstance()
        val todayKey = "%04d%02d%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )

        for (dayDir in dayDirs) {
            val d = dayDir.name
            if (selectedDateKey != null && d != selectedDateKey) continue
            val raw = dayDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".m4a") }
                ?.filter { matchesSearch(it, dayDir.name) }
                ?.filter {
                    selectedCategory == null ||
                        Prefs.getCategory(this, Storage.relativeKey(this, it)) == selectedCategory
                } ?: emptyList()
            val files = applySort(raw)
            if (files.isEmpty()) continue

            val pretty = if (d.length == 8)
                "${d.substring(0, 4)}.${d.substring(4, 6)}.${d.substring(6, 8)}" else d

            // 그 날짜의 파일들을 한 그룹으로 묶어, 날짜 헤더 탭으로 접기/펼치기.
            // 기본: (필터 없음) 오늘만 펼침 / (날짜 선택 시) 그 날 펼침.
            val dayGroup = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (selectedDateKey != null || d == todayKey) View.VISIBLE else View.GONE
            }
            for (f in files) {
                shownKeys.add(Storage.relativeKey(this, f))
                dayGroup.addView(buildFileRow(f))
                shown++
            }
            val header = Theme.dateHeader(this, "$pretty  (${files.size})")
            fun applyArrow() {
                val collapsed = dayGroup.visibility != View.VISIBLE
                Theme.setLeadingIcon(this, header, R.drawable.ic_calendar, Theme.TEXT_MUTED, 15)
                header.text = (if (collapsed) "▸ " else "▾ ") + "$pretty  (${files.size})"
            }
            header.setOnClickListener {
                dayGroup.visibility = if (dayGroup.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                applyArrow()
            }
            applyArrow()

            // 날짜 헤더 행: [그날 전체 선택] + [날짜 헤더(탭=접기/펼치기)] + [그날 전체 보관]
            val dayKeys = files.map { Storage.relativeKey(this, it) }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(Theme.checkBox(this, "").apply {
                contentDescription = I18n.t("이 날짜 전체 선택")
                // 그날 파일이 모두 선택돼 있으면 체크 상태로 표시
                isChecked = dayKeys.isNotEmpty() && selectedKeys.containsAll(dayKeys)
                setOnClickListener {
                    if (isChecked) selectedKeys.addAll(dayKeys)
                    else selectedKeys.removeAll(dayKeys.toSet())
                    refreshFileList()
                }
            })
            header.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            headerRow.addView(header)
            // 그날 전체 보관(자동 삭제 제외) 토글 — 모두 보관돼 있으면 체크 상태
            headerRow.addView(Theme.checkBox(this, "보관").apply {
                isChecked = dayKeys.isNotEmpty() && dayKeys.all { Prefs.isProtected(this@MainActivity, it) }
                setOnClickListener {
                    val on = isChecked
                    for (k in dayKeys) Prefs.setProtected(this@MainActivity, k, on)
                    refreshFileList()
                    Toast.makeText(
                        this@MainActivity,
                        if (on) I18n.f("%d개 보관됨", dayKeys.size) else I18n.f("%d개 보관 해제됨", dayKeys.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
            fileListContainer.addView(headerRow)
            fileListContainer.addView(dayGroup)
        }
        // 더 이상 보이지 않는 선택 항목 정리
        selectedKeys.retainAll(shownKeys.toSet())
        if (shown == 0) {
            fileListContainer.addView(Theme.body(this).apply {
                text = if (searchQuery.isEmpty()) "아직 녹음된 파일이 없습니다."
                else "검색 결과가 없습니다."
                setTextColor(Theme.TEXT_MUTED)
            })
        }
    }

    // ── 선택/일괄 동작 ──

    private fun selectAllShown() {
        selectedKeys.addAll(shownKeys)
        refreshFileList()
    }

    private fun clearSelection() {
        selectedKeys.clear()
        refreshFileList()
    }

    private fun selectedFiles(): List<File> =
        Storage.listAllFiles(this).filter { selectedKeys.contains(Storage.relativeKey(this, it)) }

    private fun deleteSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, I18n.t("선택된 파일이 없습니다"), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(I18n.t("선택 삭제"))
            .setMessage(I18n.f("%d개 파일을 삭제할까요?", files.size))
            .setPositiveButton(I18n.t("삭제")) { _, _ ->
                stopInlinePlay()
                for (f in files) Storage.deleteRecording(this, f)
                selectedKeys.clear()
                refreshFileList()
                Toast.makeText(this, I18n.f("%d개 삭제됨", files.size), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(I18n.t("취소"), null)
            .show()
    }

    /** 기존에 저장된 짧은 녹음(기준보다 짧은 파일)을 한 번에 삭제. 보관 파일·길이 미상 파일은 제외. */
    private fun cleanupShortRecordings() {
        // A 와 동일 기준: 화면에 'N초'로 보이는 것까지(=실제 (N+1)초 미만) 짧은 것으로 본다.
        val cutoffMs = RecordingLogic.minKeepThresholdMs(Prefs.getMinKeepSec(this))
        val candidates = Storage.listAllFiles(this).filter { f ->
            val key = Storage.relativeKey(this, f)
            if (Prefs.isProtected(this, key)) return@filter false
            val d = durationMs(f)
            d in 1 until cutoffMs   // 0(=길이 못 읽음/녹음 중)은 건드리지 않음
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, I18n.t("정리할 짧은 녹음이 없습니다"), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(I18n.t("기존 짧은 녹음 정리"))
            .setMessage(I18n.f("기준보다 짧은 녹음 %d개를 삭제할까요? (보관 파일 제외)", candidates.size))
            .setPositiveButton(I18n.t("삭제")) { _, _ ->
                stopInlinePlay()
                for (f in candidates) Storage.deleteRecording(this, f)
                refreshFileList()
                Toast.makeText(this, I18n.f("%d개 삭제됨", candidates.size), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(I18n.t("취소"), null)
            .show()
    }

    private fun protectSelected(on: Boolean) {
        val files = selectedFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, I18n.t("선택된 파일이 없습니다"), Toast.LENGTH_SHORT).show()
            return
        }
        for (f in files) Prefs.setProtected(this, Storage.relativeKey(this, f), on)
        refreshFileList()
        Toast.makeText(this, if (on) I18n.f("%d개 보관됨", files.size) else I18n.f("%d개 보관 해제됨", files.size), Toast.LENGTH_SHORT).show()
    }

    /** 표시용 길이 문자열(블로킹). durationMs 가 두 캐시를 모두 채운다. */
    private fun fmtDuration(f: File): String {
        durationCache[ckOf(f)]?.let { return it }
        durationMs(f)
        return durationCache[ckOf(f)] ?: "--:--"
    }

    private fun matchesSearch(f: File, dayName: String): Boolean {
        if (searchQuery.isEmpty()) return true
        val key = Storage.relativeKey(this, f)
        val label = Prefs.getLabel(this, key)
        val q = searchQuery.lowercase()
        return f.name.lowercase().contains(q) ||
                label.lowercase().contains(q) ||
                dayName.contains(q) ||
                fmtDuration(f).contains(q)   // 길이(예: "1:23")로도 검색
    }

    private fun buildFileRow(f: File): View {
        val key = Storage.relativeKey(this, f)
        val col = Theme.card(this)

        // 윗줄: 선택 + 재생 + 정보 + 보호
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val selectBox = Theme.checkBox(this, "").apply {
            contentDescription = I18n.t("선택")
            isChecked = selectedKeys.contains(key)
            setOnCheckedChangeListener { _, c ->
                if (c) selectedKeys.add(key) else selectedKeys.remove(key)
            }
        }
        // 타임라인 게이지 (이 파일 재생 중에만 보임) — 드래그/탭으로 구간 탐색
        val gauge = Theme.seekBar(this).apply {
            contentDescription = I18n.t("재생 위치")
            visibility = if (key == playingKey) View.VISIBLE else View.GONE
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && playingKey == key) Player.seekTo(p.toLong())
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { seekTracking = true }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { seekTracking = false }
            })
        }
        var playBtn: Button? = null
        playBtn = Theme.circleButton(
            this,
            if (key == playingKey && Player.isPlaying(f)) "■" else "▶",
            primary = true
        ) {
            togglePlayInline(f, key, playBtn!!, gauge)
        }.apply { contentDescription = I18n.t("재생/정지") }
        if (key == playingKey) {           // 재생 중 행이 다시 그려진 경우 참조 갱신
            playButton = playBtn
            playProgressBar = gauge
            playingFile = f
        }
        // 아랫줄(공유/라벨/편집/삭제) — 기본 숨김, 이름 탭하면 토글
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
            visibility = View.GONE
        }
        val info = TextView(this).apply {
            textSize = 13f
            setTextColor(Theme.TEXT)
            setPadding(12, dp(6), 8, dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 길이(메타데이터)는 비동기로 채운다 — 캐시에 없으면 '…' 로 먼저 그리고
        // 백그라운드에서 읽어 해당 행만 갱신(메인스레드 MediaMetadataRetriever 제거 → 잰크/ANR 방지).
        fun composeInfo(durClock: String) {
            val sizeKb = f.length() / 1024
            val time = DateFormat.format("HH:mm", f.lastModified())
            val label = Prefs.getLabel(this@MainActivity, key)
            val labelLine = if (label.isNotEmpty()) "\n# $label" else ""
            val bm = Prefs.getBookmarks(this@MainActivity, key).size
            val bmLine = if (bm > 0) "  ★$bm" else ""
            val cat = Prefs.getCategory(this@MainActivity, key)
            val catTag = if (cat.isNotEmpty()) "  [$cat]" else ""
            val tag = tagCache[ckOf(f)] ?: ""
            val tagLine = if (tag.isNotEmpty()) "  · $tag" else ""
            info.text = "${f.name}\n$durClock · ${sizeKb}KB · $time$tagLine$bmLine$catTag$labelLine"
        }
        val ck = ckOf(f)
        val cachedDur = durationCache[ck]
        composeInfo(cachedDur ?: "…")
        if ((cachedDur == null || tagCache[ck] == null) && !metaExecutor.isShutdown) {
            val gen = listGeneration
            metaExecutor.execute {
                durationMs(f)     // 백그라운드에서 메타데이터 읽기(두 캐시 채움)
                activityTag(f)    // .lvl 집계 태그도 함께 채움
                val clock = durationCache[ck] ?: "--:--"
                uiHandler.post { if (gen == listGeneration) composeInfo(clock) }
            }
        }
        val shareBtn = Theme.iconButton(this, R.drawable.ic_share) { Share.shareFile(this, f) }
            .apply { contentDescription = I18n.t("공유") }
        val protectBox = Theme.checkBox(this, "보관").apply {
            isChecked = Prefs.isProtected(this@MainActivity, key)
            setOnCheckedChangeListener { _, c -> Prefs.setProtected(this@MainActivity, key, c) }
        }
        top.addView(selectBox); top.addView(playBtn); top.addView(info); top.addView(shareBtn); top.addView(protectBox)
        // 행 전체(재생/체크 제외)를 탭하면 공유/라벨/편집/삭제 토글.
        // 체크박스·재생 버튼은 자기 클릭을 소비하므로 토글되지 않음.
        top.setOnClickListener {
            bottom.visibility = if (bottom.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        col.addView(top)
        col.addView(gauge)

        bottom.addView(Theme.smallButton(this, "공유") { Share.shareFile(this, f) })
        bottom.addView(Theme.smallButton(this, "라벨") { showLabelDialog(key) })
        bottom.addView(Theme.smallButton(this, "카테고리") { showCategoryDialog(key) })
        bottom.addView(Theme.smallButton(this, "편집") {
            startActivity(
                Intent(this@MainActivity, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_PATH, f.absolutePath)
            )
        })
        bottom.addView(Theme.smallButton(this, "삭제", danger = true) { confirmDelete(f, key) })
        col.addView(bottom)
        return col
    }

    // ── 목록 인라인 재생 ──

    private fun togglePlayInline(f: File, key: String, btn: Button, gauge: ProgressBar) {
        // 다른 파일이 재생 중이면 멈추고 그 행 UI 초기화
        if (playingKey != null && playingKey != key) {
            Player.stop()
            playProgressBar?.visibility = View.GONE
            playButton?.text = "▶"
        }
        playingKey = key
        playingFile = f
        playButton = btn
        playProgressBar = gauge
        gauge.visibility = View.VISIBLE
        val started = Player.toggle(
            f,
            onError = {
                // 손상·삭제된 파일: 크래시 대신 안내하고 행 UI 를 원상복구
                Toast.makeText(this, I18n.t("재생할 수 없는 파일입니다"), Toast.LENGTH_SHORT).show()
                stopInlinePlay()
            }
        ) {
            // 재생 자연 종료 시
            gauge.progress = 0
            btn.text = "▶"
        }
        if (!started) return
        uiHandler.removeCallbacks(playTick)
        uiHandler.post(playTick)
    }

    private fun stopInlinePlay() {
        // 이 화면이 시작한 인라인 재생만 멈춘다. PlayerActivity 로 넘어갈 때 onStop 이
        // 무조건 Player.stop() 을 부르면, 검색 결과 탭 → 발화 위치 자동 재생이
        // (PlayerActivity.onCreate 직후에 오는 MainActivity.onStop 에서) 바로 죽는다.
        val f = playingFile
        if (f != null && Player.isLoaded(f)) Player.stop()
        uiHandler.removeCallbacks(playTick)
        playProgressBar?.visibility = View.GONE
        playButton?.text = "▶"
        playingKey = null
        playingFile = null
        playButton = null
        playProgressBar = null
    }

    private fun showLabelDialog(key: String) {
        val input = EditText(this).apply { setText(Prefs.getLabel(this@MainActivity, key)) }
        AlertDialog.Builder(this)
            .setTitle(I18n.t("라벨 / 메모"))
            .setView(input)
            .setPositiveButton(I18n.t("저장")) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) Prefs.removeLabel(this, key) else Prefs.setLabel(this, key, text)
                refreshFileList()
            }
            .setNegativeButton(I18n.t("취소"), null)
            .show()
    }

    private fun confirmDelete(f: File, key: String) {
        AlertDialog.Builder(this)
            .setTitle(I18n.t("삭제"))
            .setMessage(I18n.f("%s 파일을 삭제할까요?", f.name))
            .setPositiveButton(I18n.t("삭제")) { _, _ ->
                stopInlinePlay()
                Storage.deleteRecording(this, f)
                refreshFileList()
            }
            .setNegativeButton(I18n.t("취소"), null)
            .show()
    }

    // ───────────────────────── 상단 상태 갱신 ─────────────────────────

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val level = Prefs.getCurrentLevel(this)
        val capturing = Prefs.isCapturing(this)
        // 희망(켜 둠)과 사실(실제로 도는 중)을 나눠서 본다 — 둘이 어긋나는 구간이 실제로 있다.
        val desired = Prefs.isRecordingEnabled(this)
        val running = RecordingService.isRunning()
        levelBar.progress = level.toInt().coerceIn(0, Prefs.MAX_THRESHOLD.toInt())
        when {
            !desired -> {
                statusText.text = I18n.t("⚪ 정지됨")
                statusText.setTextColor(Theme.TEXT_MUTED)
            }
            !running -> {
                // 켜 두긴 했는데 서비스가 안 돈다(재부팅 후 재개 대기 등). 예전엔 이 상태에서도
                // '녹음 중'으로 보여, 아무것도 녹음되지 않는 걸 사용자가 알 수 없었다.
                statusText.text = I18n.t("🟡 멈춤 · 아래를 눌러 재개하세요")
                statusText.setTextColor(Theme.TEXT_MUTED)
            }
            // 위치 게이팅이 막고 있는 중이면 그 사실을 먼저 알린다. 조용히 안 담기는 게
            // 제일 나쁘다 — 특히 판정 불가로 막힌 경우는 사용자가 손쓸 수 있어야 한다.
            AudioEngine.locState == AudioEngine.Companion.LocState.NO_PERMISSION -> {
                statusText.text = I18n.t("🟡 위치 권한이 없어 녹음 안 함 · 설정에서 허용하세요")
                statusText.setTextColor(Theme.TEXT_MUTED)
            }
            AudioEngine.locState == AudioEngine.Companion.LocState.NO_FIX -> {
                statusText.text = I18n.t("🟡 위치를 확인할 수 없어 녹음 안 함")
                statusText.setTextColor(Theme.TEXT_MUTED)
            }
            AudioEngine.locState == AudioEngine.Companion.LocState.BLOCKED_ZONE -> {
                statusText.text = I18n.t("🟡 지정한 장소 조건이라 녹음 안 함")
                statusText.setTextColor(Theme.TEXT_MUTED)
            }
            capturing -> {
                statusText.text = I18n.f("🔴 녹음 중 · 음량 %d", level.toInt())
                statusText.setTextColor(Theme.TEXT)
            }
            else -> {
                statusText.text = I18n.f("🟢 대기 중 · 음량 %d", level.toInt())
                statusText.setTextColor(Theme.TEXT)
            }
        }

        // 시작/정지 토글 버튼: 실제로 도는 중이면 '정지'(빨강), 아니면 '시작'(키 컬러)
        if (::recordToggleBtn.isInitialized && lastToggleEnabled != running) {
            lastToggleEnabled = running
            recordToggleBtn.text = if (running) I18n.t("■ 정지") else I18n.t("● 시작")
            Theme.setPillColor(this, recordToggleBtn, if (running) Theme.NEGATIVE else Theme.ACCENT)
        }

        // 시작 시각 + 경과 시간 — 실제로 도는 중일 때만. 예전엔 재부팅 후 남아 있던
        // 옛 startedAt 으로 있지도 않은 녹음의 경과 시간을 표시했다.
        val startedAt = Prefs.getRecordingStartedAt(this)
        if (running && startedAt > 0) {
            val start = DateFormat.format(if (I18n.en) "MMM d, HH:mm" else "M월 d일 HH:mm", startedAt)
            val ms = System.currentTimeMillis() - startedAt
            val tail = if (ms < 60_000) I18n.t("방금 시작") else I18n.f("%s 경과", fmtElapsed(ms))
            startTimeText.text = I18n.f("시작 %s · %s", start, tail)
            startTimeText.visibility = View.VISIBLE
        } else {
            startTimeText.visibility = View.GONE
        }

        // 빈/깨진 녹음 경고 배너 갱신 (실패가 있으면 표시)
        refreshWarningCard()
    }

    private fun fmtElapsed(ms: Long): String {
        if (ms < 60_000) return I18n.t("방금")
        val totalMin = ms / 60_000
        val d = totalMin / 1440
        val h = (totalMin % 1440) / 60
        val m = totalMin % 60
        return when {
            d > 0 -> I18n.f("%d일 %d시간", d, h)
            h > 0 -> I18n.f("%d시간 %d분", h, m)
            else -> I18n.f("%d분", m)
        }
    }

    // ───────────────────────── 앱 잠금 ─────────────────────────

    /** 인증 통과(또는 잠글 수단 없음). 내용을 드러내고, onResume 이 못 돌린 갱신을 시작한다. */
    private fun onUnlocked() {
        unlocked = true
        if (::contentRoot.isInitialized) contentRoot.visibility = View.VISIBLE
        // onResume 에서 잠금 때문에 건너뛴 레벨틱·배터리 갱신을 여기서 킨다.
        uiHandler.post(levelTick)
        refreshBattery()
    }

    private fun promptUnlock() {
        val canAuth = BiometricManager.from(this)
            .canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // 생체·기기 자격증명이 아예 없으면 잠글 수단이 없다 → 그냥 연다.
            onUnlocked()
            return
        }
        authInProgress = true
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authInProgress = false
                    onUnlocked()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    authInProgress = false
                    finish()
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(I18n.t("앱 잠금"))
                .setSubtitle(I18n.t("녹음 기록을 보려면 인증하세요"))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
        )
    }

    // ───────────────────────── 공통 ─────────────────────────

    override fun onResume() {
        super.onResume()
        // 잠금이 켜져 있고 아직 안 풀렸으면(최초 진입·백그라운드 복귀) 인증을 요구한다.
        // 인증 전에는 아래 레벨틱·배터리·Pro 갱신을 돌리지 않는다(내용 노출 방지).
        if (Prefs.isAppLockEnabled(this) && !unlocked) {
            if (!authInProgress) promptUnlock()
            return
        }
        if (unlocked) {
            uiHandler.post(levelTick)
            refreshBattery()
        }
        // Pro 화면에서 구매 후 돌아오면 반영
        Pro.onChanged = { runOnUiThread { recreate() } }
        if (proDisplayed != Pro.isPro) recreate()
        // STT 모델 다운로드가 백그라운드에서 끝났으면 설치·화면 갱신
        // (Receiver 를 놓친 경우의 안전망 — ids 없으면 즉시 리턴이라 비용 없음)
        if (SttModel.isDownloading(this) && SttModel.finalizeIfDone(this)) recreate()
    }

    /** STT 모델 다운로드 확인 다이얼로그 — 네트워크(Wi-Fi 전용/모바일 허용) 선택. */
    private fun showSttDownloadDialog() {
        AlertDialog.Builder(this)
            .setTitle(I18n.t("음성 인식 모델 다운로드"))
            .setMessage(I18n.f("약 %dMB 를 내려받습니다. 한 번만 받으면 이후엔 인터넷 없이 기기 안에서만 동작합니다. 어떤 네트워크로 받을까요?", SttModel.TOTAL_MB))
            .setPositiveButton(I18n.t("Wi-Fi에서만")) { _, _ -> startSttDownload(false) }
            .setNeutralButton(I18n.t("모바일 데이터 허용")) { _, _ -> startSttDownload(true) }
            .setNegativeButton(I18n.t("취소"), null)
            .show()
    }

    private fun startSttDownload(allowMetered: Boolean) {
        if (SttModel.startDownload(this, allowMetered)) {
            Toast.makeText(this, I18n.t("다운로드를 시작했습니다. 진행률은 알림에서 확인하세요."), Toast.LENGTH_LONG).show()
            recreate()   // 설정 블록을 '다운로드 중' 상태로 갱신
        } else {
            Toast.makeText(this, I18n.t("다운로드를 시작하지 못했습니다. 저장공간·네트워크를 확인해주세요."), Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(levelTick)
    }

    override fun onStop() {
        super.onStop()
        stopInlinePlay()
        // 백그라운드로 나가면 다시 잠근다. 예전엔 onCreate 에서만 잠가, 앱을 홈으로 보냈다가
        // 되돌아오면(액티비티가 살아 있는 한) 재인증 없이 목록이 그대로 보였다.
        if (Prefs.isAppLockEnabled(this) && !authInProgress) {
            unlocked = false
            if (::contentRoot.isInitialized) contentRoot.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        metaExecutor.shutdownNow()   // 비동기 길이 로딩 스레드 정리
        // Pro 는 프로세스 수명 싱글턴이다. 여기서 끊지 않으면 파괴된 Activity 가
        // onChanged 람다(this 캡처)에 붙들려 recreate() 때마다 샌다.
        Pro.onChanged = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // 밑줄 인디케이터 탭 (아이콘+라벨을 한 묶음으로 가운데 정렬)
    private fun makeTab(label: String, iconRes: Int): LinearLayout {
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            // 라벨 텍스트가 같은 행에 있으므로 아이콘은 장식 — 스크린리더에서 건너뜀
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                .apply { setMargins(0, 0, dp(6), 0) }
        }
        val tv = TextView(this).apply {
            text = I18n.t(label)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            addView(icon)
            addView(tv)
        }
        val underline = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(labelRow)
            addView(underline)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private fun styleTab(tab: LinearLayout, selected: Boolean) {
        val color = if (selected) Theme.ACCENT else Theme.TEXT_MUTED
        val labelRow = tab.getChildAt(0) as LinearLayout
        (labelRow.getChildAt(0) as ImageView).imageTintList =
            android.content.res.ColorStateList.valueOf(color)
        (labelRow.getChildAt(1) as TextView).setTextColor(color)
        tab.getChildAt(1).setBackgroundColor(if (selected) Theme.ACCENT else android.graphics.Color.TRANSPARENT)
    }

    private fun updateThresholdLabel(value: Double) {
        thresholdLabel.text = I18n.f("현재 기준: %d", value.toInt())
    }

    // ── 주변 소음 자동 보정 ──

    private fun startCalibration() {
        if (Calibrator.hasMicPermission(this)) runCalibration()
        else calibPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun runCalibration() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(I18n.t("주변 소음 측정 중"))
            .setMessage(I18n.t("약 2초간 조용히 해주세요…"))
            .setCancelable(false)
            .show()
        Calibrator.calibrate(this) { recommended ->
            dialog.dismiss()
            if (recommended == null) {
                Toast.makeText(this, I18n.t("측정 실패 — 녹음을 잠시 끄고 다시 시도하세요"), Toast.LENGTH_LONG).show()
                return@calibrate
            }
            Prefs.setThreshold(this, recommended)
            sensitivitySeek?.progress = (recommended - Prefs.MIN_THRESHOLD).toInt()
                .coerceIn(0, (Prefs.MAX_THRESHOLD - Prefs.MIN_THRESHOLD).toInt())
            updateThresholdLabel(recommended)
            Toast.makeText(this, I18n.f("자동 기준 설정: %d", recommended.toInt()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun simpleSeek(onProgress: (Int) -> Unit) =
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) = onProgress(p)
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        }

    private fun requestPermsThenStart() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun startRecording() {
        RecordingService.start(this)
        updateStatus()
        Toast.makeText(this, I18n.t("녹음 시작"), Toast.LENGTH_SHORT).show()
    }

    private fun exportProtected() {
        val protectedKeys = Prefs.getProtected(this)
        val files = Storage.listAllFiles(this)
            .filter { protectedKeys.contains(Storage.relativeKey(this, it)) }
        if (files.isEmpty()) {
            Toast.makeText(this, I18n.t("보호된 파일이 없습니다"), Toast.LENGTH_SHORT).show()
            return
        }
        Share.shareMultiple(this, files)
    }

    private fun requestIgnoreBatteryOptimization() {
        // 정책 안전: 특별 권한 없이 시스템 '배터리 최적화' 목록 화면을 연다.
        // (사용자가 목록에서 이 앱을 찾아 직접 제외)
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(this, I18n.t("설정을 열 수 없습니다"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        /** 부팅 후 '녹음 재개' 알림이 MainActivity 를 열 때 붙이는 플래그. */
        const val EXTRA_RESUME_RECORDING = "resume_recording"
    }
}
