package com.studioroomkr.hellorecorder

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/**
 * 앱 실행 시 잠깐 보여 주는 스플래시(시작) 화면.
 *  - HR_title.png 를 화면 가득(centerCrop) 표시
 *  - 약 1.3초 뒤 MainActivity 로 전환
 * 테마(Theme.HelloRecorder.Splash)의 windowBackground 가 같은 보라색이라
 * 이미지가 뜨기 전에도 흰 깜빡임 없이 자연스럽게 이어진다.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val image = ImageView(this).apply {
            setImageResource(R.drawable.hr_splash)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        setContentView(image)

        Handler(Looper.getMainLooper()).postDelayed({
            // 최초 사용 동의 전이면 사전 고지 화면으로, 이후엔 메인으로
            val next = if (Prefs.isConsentAccepted(this))
                MainActivity::class.java else ConsentActivity::class.java
            startActivity(Intent(this, next))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, SPLASH_MS)
    }

    private companion object {
        const val SPLASH_MS = 1300L
    }
}
