package com.studioroomkr.hellorecorder

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 최초 1회 보여 주는 "사전 고지(Prominent Disclosure) + 동의" 화면.
 *
 * 구글 플레이 정책상 마이크/위치 같은 민감 데이터를 쓰기 전에는 무엇을 어떻게
 * 수집·사용하는지 분명히 알리고 사용자의 동의를 받아야 한다. 이 화면이 그 역할.
 *  - 동의 → 플래그 저장 후 MainActivity
 *  - 동의 안 함 → 앱 종료
 */
class ConsentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.apply(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        Theme.applyScreen(root)

        root.addView(Theme.sectionTitle(this, "시작하기 전에"))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(disclosure())
        scroll.addView(body)
        root.addView(scroll)

        root.addView(Theme.body(this, "위 내용을 확인했으며, 본인의 정당한 용도로만 사용하겠습니다.").apply {
            setPadding(0, dp(12), 0, dp(8))
        })

        root.addView(Theme.primaryButton(this, "동의하고 시작") {
            Prefs.setConsentAccepted(this, true)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })
        root.addView(Theme.outlineButton(this, "동의 안 함 (종료)") { finishAffinity() })

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + dp(8), v.paddingRight, bars.bottom + dp(8))
            insets
        }
    }

    private fun disclosure(): TextView = TextView(this).apply {
        textSize = 14f
        setTextColor(Theme.TEXT)
        setLineSpacing(0f, 1.2f)
        text = (if (I18n.en) DISCLOSURE_EN else DISCLOSURE_KO).trimIndent()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val DISCLOSURE_KO = """
            HelloRecorder는 다음 데이터를 사용합니다.

            • 마이크(녹음): 소리가 감지될 때 음성을 녹음합니다. 백그라운드(화면이 꺼져 있거나 다른 앱 사용 중)에서도 동작할 수 있으며, 녹음 중에는 상단 알림이 계속 표시됩니다.

            • 위치(선택): ‘위치 기반 녹음’을 켠 경우에만, 앱을 사용하는 동안 지정한 장소 안/밖을 판단하기 위해 기기 위치를 사용합니다(사용 중에만, 백그라운드 위치는 쓰지 않음). 이 기능을 쓰지 않으면 위치는 수집되지 않습니다.

            데이터 보관 방식
            • 녹음 파일, 전사(받아쓰기) 결과, 녹음 장소 설정, 모든 설정은 이 기기 안에만 저장됩니다. 전사도 기기 안에서 처리합니다. 개발자는 서버를 운영하지 않으며, 이 데이터를 어디로도 업로드하지 않습니다.
            • 녹음 파일은 설정한 보관 기간이 지나면 자동 삭제됩니다(보관 표시 파일 제외). 직접 삭제도 가능합니다.

            네트워크 통신 (선택 기능을 쓸 때만)
            녹음 내용이 나가는 것은 아니지만, 아래 기능은 제3자 서비스와 통신합니다.
            • 지도로 장소 지정: OpenStreetMap 에서 지도 타일을 받아옵니다(보고 있는 지도 영역이 전달됩니다).
            • 말한 내용 검색용 STT 모델 내려받기: Hugging Face 에서 모델 파일(약 133MB)을 받습니다.
            • Pro 구매·복원: Google Play 결제를 이용합니다.

            중요 — 법적 책임
            • 상대방의 동의 없는 통화·대화 녹음은 지역에 따라 불법일 수 있습니다.
            • 이 앱은 본인의 회의·강의·메모 등 정당한 용도를 위한 것입니다. 타인을 몰래 감시·도청하는 용도로 사용하지 마세요. 사용에 따른 법적 책임은 사용자 본인에게 있습니다.
        """

        const val DISCLOSURE_EN = """
            HelloRecorder uses the following data.

            • Microphone (recording): Records audio when sound is detected. It can run in the background (screen off or while using other apps), and a persistent notification is shown while recording.

            • Location (optional): Only when 'Location-based recording' is on, your device location is used while you are using the app to decide whether you're inside/outside a set zone (while-in-use only; background location is not used). Location is not collected if you don't use this feature.

            How your data is stored
            • Recordings, transcripts, your recording-location settings, and all other settings are stored only on this device. Transcription runs on-device. The developer runs no server and never uploads this data anywhere.
            • Recordings are auto-deleted after the retention period you set (except kept files). You can also delete them manually.

            Network use (only for the optional features below)
            Your recordings never leave the device, but these features do talk to third-party services.
            • Picking a place on the map: map tiles are fetched from OpenStreetMap (the map area you're viewing is sent).
            • Downloading the speech-search model: the model file (~133MB) is fetched from Hugging Face.
            • Buying or restoring Pro: handled by Google Play billing.

            Important — Legal responsibility
            • Recording calls/conversations without consent may be illegal depending on your region.
            • This app is for your own lawful uses such as meetings, lectures, and memos. Do not use it to secretly monitor or eavesdrop on others. You are solely responsible for your use.
        """
    }
}
