package com.studioroomkr.hellorecorder

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pro 잠금 해제(구매) 화면.
 *  - Pro 혜택 안내 + 가격 + 구매/복원 버튼
 *  - 구매 성공(Pro.onChanged) 시 화면 갱신/종료
 */
class ProActivity : AppCompatActivity() {

    private lateinit var buyBtn: android.widget.Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.apply(this)
        Pro.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        Theme.applyScreen(root)

        root.addView(Theme.sectionTitle(this, "HelloRecorder Pro"))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(Theme.hint(this, "한 번 구매로 모든 프리미엄 기능을 평생 사용합니다."))
        for (line in benefits()) {
            body.addView(Theme.body(this, line).apply { setPadding(0, dp(6), 0, dp(6)) })
        }
        scroll.addView(body)
        root.addView(scroll)

        statusText = Theme.body(this).apply { setTextColor(Theme.TEXT_MUTED); gravity = Gravity.CENTER }
        root.addView(statusText)

        buyBtn = Theme.primaryButton(this, "Pro 구매") { startPurchase() }
        root.addView(buyBtn)
        root.addView(Theme.outlineButton(this, "구매 복원") {
            Pro.restore()
            Toast.makeText(this, I18n.t("구매를 확인하는 중…"), Toast.LENGTH_SHORT).show()
        })
        root.addView(Theme.secondaryButton(this, "닫기") { finish() })

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + dp(8), v.paddingRight, bars.bottom + dp(8))
            insets
        }

        Pro.onChanged = { runOnUiThread { refreshUi() } }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        Pro.onChanged = null
        super.onDestroy()
    }

    private fun startPurchase() {
        if (Pro.isPro) { finish(); return }
        if (!Pro.purchase(this)) {
            Toast.makeText(this, I18n.t("상품 정보를 불러오는 중입니다. 잠시 후 다시 시도하세요."), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshUi() {
        if (Pro.isPro) {
            statusText.text = I18n.t("Pro 사용 중 — 감사합니다!")
            buyBtn.text = I18n.t("닫기")
        } else {
            val price = Pro.priceText()
            statusText.text = ""
            buyBtn.text = if (price.isNotEmpty()) I18n.f("Pro 구매 · %s", price) else I18n.t("Pro 구매")
        }
    }

    private fun benefits(): List<String> = listOf(
        "• 정밀 음성 확인 · 목소리 강조 (신경망)",
        "• 먼 소리 줄이기 (근접 우선)",
        "• 녹음 시간대 예약 (요일/날짜)",
        "• 위치 기반 녹음 (다중 구역)",
        "• 캘린더 · 정렬",
        "• 프라이버시 모드 (화면 캡처 차단)",
        "• 최고 음질 (128kbps)",
        "• 카테고리 무제한",
        "• 보관 기간 30일까지",
        "• 홈 위젯 · 일별 배터리 사용량",
        "• 광고 없음 · 우선 지원",
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
