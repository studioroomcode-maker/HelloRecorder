package com.studioroomkr.hellorecorder

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat

/**
 * Spotify 풍 다크 디자인 시스템.
 *
 *  - 거의 검정에 가까운 배경(#121212~#1f1f1f) — UI 는 어둠 속으로 가라앉고 콘텐츠가 주인공.
 *  - 키 컬러 인디고(#5a61e0)는 "기능적 강조"에만 — 재생/주요 액션/활성 상태.
 *  - 알약(pill) 버튼, 흰/실버 텍스트, 둥근 카드, 묵직한 그림자.
 *
 * 화면 코드(MainActivity/PlayerActivity)는 여기 팩토리만 호출해서
 * 일관된 룩을 얻는다.
 */
object Theme {

    // ───────────────────────── 색상 토큰 ─────────────────────────
    val BG = Color.parseColor("#121212")          // Level 0 — 페이지 최하단
    val SURFACE = Color.parseColor("#181818")     // Level 1 — 카드/컨테이너
    val SURFACE_ALT = Color.parseColor("#1f1f1f") // 버튼/입력 등 인터랙티브 표면
    val CARD = Color.parseColor("#202020")        // 살짝 떠 보이는 카드

    val ACCENT = Color.parseColor("#5a61e0")      // 키 컬러(기능적 강조)
    val ACCENT_DARK = Color.parseColor("#4750d8")

    val TEXT = Color.parseColor("#ffffff")        // 기본 텍스트
    val TEXT_MUTED = Color.parseColor("#b3b3b3")  // 보조/비활성 텍스트
    val TEXT_FAINT = Color.parseColor("#7c7c7c")  // 옅은 힌트

    val BORDER = Color.parseColor("#4d4d4d")
    val DIVIDER = Color.parseColor("#2a2a2a")
    val NEGATIVE = Color.parseColor("#f3727f")    // 오류/삭제

    // ───────────────────────── 단위 ─────────────────────────
    private fun Context.dp(v: Float): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    // ───────────────────────── 배경/루트 ─────────────────────────

    /** 화면 전체를 near-black 으로 깔아 준다. */
    fun applyScreen(root: View) {
        root.setBackgroundColor(BG)
    }

    // ───────────────────────── 버튼 ─────────────────────────

    /** 키 컬러 알약 — 재생/주요 액션 전용. 흰 글씨로 또렷하게. */
    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit): Button =
        pill(ctx, text, fill = ACCENT, textColor = TEXT, bold = true).apply {
            setOnClickListener { onClick() }
            withMargin(ctx, this)
        }

    /** 다크 알약 — 보조 액션. */
    fun secondaryButton(ctx: Context, text: String, onClick: () -> Unit): Button =
        pill(ctx, text, fill = SURFACE_ALT, textColor = TEXT, bold = true).apply {
            setOnClickListener { onClick() }
            withMargin(ctx, this)
        }

    /** 외곽선 알약 — 설정/덜 중요한 액션. */
    fun outlineButton(ctx: Context, text: String, onClick: () -> Unit): Button =
        pill(ctx, text, fill = Color.TRANSPARENT, textColor = TEXT, bold = false, strokeColor = BORDER).apply {
            setOnClickListener { onClick() }
            withMargin(ctx, this)
        }

    /** 작은 알약 — 목록 행의 공유/라벨/삭제 같은 인라인 액션. */
    fun smallButton(ctx: Context, text: String, danger: Boolean = false, onClick: () -> Unit): Button {
        val b = pill(
            ctx, text,
            fill = SURFACE_ALT,
            textColor = if (danger) NEGATIVE else TEXT_MUTED,
            bold = true,
            small = true,
        )
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.setMargins(0, ctx.dp(2f), ctx.dp(8f), ctx.dp(2f))
        b.layoutParams = lp
        return b
    }

    private fun pill(
        ctx: Context,
        text: String,
        fill: Int,
        textColor: Int,
        bold: Boolean,
        strokeColor: Int? = null,
        small: Boolean = false,
    ): Button = Button(ctx).apply {
        this.text = I18n.t(text)
        isAllCaps = false
        setTextColor(textColor)
        textSize = if (small) 13f else 15f
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        stateListAnimator = null               // 기본 머티리얼 그림자 제거(우리가 평평한 알약을 원함)
        elevation = 0f
        val padH = ctx.dp(if (small) 18f else 24f)
        val padV = ctx.dp(if (small) 9f else 13f)
        setPadding(padH, padV, padH, padV)
        background = pillBackground(ctx, fill, strokeColor)
    }

    private fun pillBackground(ctx: Context, fill: Int, strokeColor: Int?): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = ctx.dp(999f).toFloat()  // 완전한 알약
            setColor(fill)
            if (strokeColor != null) setStroke(ctx.dp(1f), strokeColor)
        }
        val ripple = ColorStateList.valueOf(Color.parseColor("#33ffffff"))
        return RippleDrawable(ripple, shape, shape)
    }

    /** 원형 버튼(재생/정지 등 아이콘 컨트롤). 키 컬러 채움 + 흰 글리프. 작고 간결하게. */
    fun circleButton(ctx: Context, glyph: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = glyph
            isAllCaps = false
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            stateListAnimator = null
            elevation = 0f
            val size = ctx.dp(38f)
            val oval = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(if (primary) ACCENT else SURFACE_ALT)
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33ffffff")), oval, oval)
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(0, ctx.dp(2f), ctx.dp(10f), ctx.dp(2f))
            layoutParams = lp
            setPadding(0, 0, 0, 0)
        }

    private fun withMargin(ctx: Context, v: View) {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.setMargins(0, ctx.dp(4f), 0, ctx.dp(8f))
        v.layoutParams = lp
    }

    // ───────────────────────── 텍스트 ─────────────────────────

    /** 섹션 제목 — 24px 굵게, 흰색. */
    fun sectionTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = I18n.t(text)
        textSize = 22f
        setTextColor(TEXT)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, ctx.dp(28f), 0, ctx.dp(10f))
    }

    /** 본문/라벨 텍스트(흰색). */
    fun body(ctx: Context, text: String = ""): TextView = TextView(ctx).apply {
        this.text = I18n.t(text)
        textSize = 14f
        setTextColor(TEXT)
        setPadding(0, ctx.dp(3f), 0, ctx.dp(3f))
    }

    /** 옅은 힌트 텍스트(실버). */
    fun hint(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = I18n.t(text)
        textSize = 12f
        setTextColor(TEXT_MUTED)
        setPadding(0, ctx.dp(4f), 0, ctx.dp(14f))
    }

    // ───────────────────────── 컨테이너 ─────────────────────────

    /** 둥근 다크 카드(#181818). 떠 보이는 그림자 약간. */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(10f).toFloat()
            setColor(SURFACE)
        }
        val pad = ctx.dp(14f)
        setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.setMargins(0, ctx.dp(6f), 0, ctx.dp(8f))
        layoutParams = lp
        elevation = ctx.dp(6f).toFloat()
    }

    /** 거의 보이지 않는 옅은 구분선. */
    fun divider(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1f))
        setBackgroundColor(DIVIDER)
    }

    // ───────────────────────── 입력 컨트롤 ─────────────────────────

    /** 알약 모양 검색/텍스트 입력. */
    fun editText(ctx: Context, hint: String): EditText = EditText(ctx).apply {
        this.hint = I18n.t(hint)
        setTextColor(TEXT)
        setHintTextColor(TEXT_FAINT)
        textSize = 14f
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(999f).toFloat()
            setColor(SURFACE_ALT)
        }
        val padH = ctx.dp(20f)
        val padV = ctx.dp(12f)
        setPadding(padH, padV, padH, padV)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.setMargins(0, ctx.dp(4f), 0, ctx.dp(8f))
        layoutParams = lp
    }

    /** 그린 틴트 체크박스 + 흰 라벨. */
    fun checkBox(ctx: Context, text: String): CheckBox = CheckBox(ctx).apply {
        this.text = I18n.t(text)
        setTextColor(TEXT)
        textSize = 14f
        buttonTintList = ColorStateList.valueOf(ACCENT)
        setPadding(ctx.dp(6f), ctx.dp(6f), 0, ctx.dp(6f))
    }

    /** 그린 틴트 슬라이더. */
    fun seekBar(ctx: Context): SeekBar = SeekBar(ctx).apply {
        progressTintList = ColorStateList.valueOf(ACCENT)
        thumbTintList = ColorStateList.valueOf(ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(BORDER)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.setMargins(0, ctx.dp(2f), 0, ctx.dp(6f))
        layoutParams = lp
    }

    /** 그린 틴트 수평 진행 막대(음량 미터). */
    fun progressBar(ctx: Context): ProgressBar =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(BORDER)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ctx.dp(8f),
            )
            lp.setMargins(0, ctx.dp(4f), 0, ctx.dp(4f))
            layoutParams = lp
        }

    // ───────────────────────── 라벨용 작은 유틸 ─────────────────────────

    /** 날짜 헤더(실버, 작게). */
    fun dateHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(TEXT_MUTED)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(ctx.dp(2f), ctx.dp(20f), 0, ctx.dp(8f))
    }

    /** 작은 굵은 소제목(섹션 내부 그룹 제목). */
    fun subHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = I18n.t(text)
        textSize = 14f
        setTextColor(TEXT)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, ctx.dp(14f), 0, ctx.dp(8f))
    }

    /** 시간 칩 — "06:00" 같은 알약. 탭하면 시간 선택. */
    fun timeChip(ctx: Context, text: String, onClick: () -> Unit): Button =
        pill(ctx, text, fill = SURFACE_ALT, textColor = TEXT, bold = true, small = true).apply {
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, ctx.dp(4f), ctx.dp(6f), ctx.dp(4f)) }
        }

    /** 알약 버튼의 채움색을 바꾼다(토글 버튼 상태 표시용). */
    fun setPillColor(ctx: Context, btn: Button, fill: Int) {
        val shape = GradientDrawable().apply {
            cornerRadius = ctx.dp(999f).toFloat()
            setColor(fill)
        }
        btn.background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33ffffff")), shape, shape)
    }

    /** 아이콘만 있는 작은 버튼(공유 등). 탭 영역 확보 + 틴트. */
    fun iconButton(ctx: Context, iconRes: Int, tint: Int = TEXT_MUTED, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            val d = ContextCompat.getDrawable(ctx, iconRes)?.mutate()
            val px = ctx.dp(20f)
            d?.setBounds(0, 0, px, px)
            setCompoundDrawables(d, null, null, null)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(tint))
            val pad = ctx.dp(8f)
            setPadding(pad, pad, pad, pad)
            val mask = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(Color.WHITE) }
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33ffffff")), null, mask)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 텍스트/버튼 앞에 단색 아이콘(벡터)을 붙이고 지정 색으로 틴트. */
    fun setLeadingIcon(ctx: Context, view: TextView, iconRes: Int, tint: Int, sizeDp: Int = 18) {
        val d = ContextCompat.getDrawable(ctx, iconRes)?.mutate() ?: return
        val px = ctx.dp(sizeDp.toFloat())
        d.setBounds(0, 0, px, px)
        view.setCompoundDrawables(d, null, null, null)
        view.compoundDrawablePadding = ctx.dp(8f)
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(tint))
    }

    /** 탭 버튼(알약). 선택 상태는 styleTab 으로 갱신. */
    fun tab(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, ctx.dp(10f), 0, ctx.dp(10f))
    }

    fun styleTab(ctx: Context, tv: TextView, selected: Boolean) {
        tv.setTextColor(if (selected) TEXT else TEXT_MUTED)
        tv.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(999f).toFloat()
            setColor(if (selected) ACCENT else SURFACE_ALT)
        }
    }

    /**
     * 접을 수 있는 섹션. 헤더(▸/▾ + 제목)를 [parent] 에 붙이고,
     * 내용을 담을 컨테이너를 반환한다. 호출 측은 반환된 컨테이너에 뷰를 추가.
     */
    fun section(ctx: Context, parent: LinearLayout, title: String, expanded: Boolean): LinearLayout {
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, ctx.dp(8f))
        }
        val tTitle = I18n.t(title)
        val header = TextView(ctx).apply {
            text = (if (expanded) "▾  " else "▸  ") + tTitle
            textSize = 17f
            setTextColor(TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, ctx.dp(18f), 0, ctx.dp(12f))
            setOnClickListener {
                val show = content.visibility != View.VISIBLE
                content.visibility = if (show) View.VISIBLE else View.GONE
                text = (if (show) "▾  " else "▸  ") + tTitle
            }
        }
        parent.addView(header)
        parent.addView(content)
        return content
    }
}
