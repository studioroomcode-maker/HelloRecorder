package com.studioroomkr.hellorecorder

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 온디바이스 STT 스파이크 실행 화면 (debug 빌드 전용 런처).
 * 가장 최근 .m4a 를 sherpa-onnx 한국어 Zipformer 로 전사하고 전사문·RTF·메모리를 표시한다.
 * 참조문을 넣으면 CER 도 계산한다. 실측 수치를 캡처해 보고하는 용도.
 */
class SttSpikeActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var referenceEt: EditText
    private lateinit var resultView: TextView
    private lateinit var runButton: Button
    private var lastTranscript: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "STT 스파이크 (한국어 온디바이스 전사)"
            textSize = 18f
        })

        statusView = TextView(this).apply { setPadding(0, dp(12), 0, dp(12)) }
        root.addView(statusView)

        runButton = Button(this).apply {
            text = "최근 녹음 전사 & 측정"
            setOnClickListener { runSpike() }
        }
        root.addView(runButton)

        root.addView(TextView(this).apply {
            text = "참조문(정답) — 넣으면 CER 계산:"
            setPadding(0, dp(16), 0, dp(4))
        })
        referenceEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            hint = "녹음의 실제 발화 내용을 붙여넣으세요 (선택)"
        }
        root.addView(referenceEt)

        val recompute = Button(this).apply {
            text = "CER 다시 계산(참조문 변경 후)"
            setOnClickListener { recomputeCer() }
        }
        root.addView(recompute)

        resultView = TextView(this).apply {
            setPadding(0, dp(16), 0, 0)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(resultView)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun refreshStatus() {
        val dir = SttSpike.modelDir(this)
        val model = SttSpike.findModel(this)
        statusView.text = if (model != null) {
            "모델 OK\n  ${model.encoder.name}\n  ${model.decoder.name}\n  ${model.joiner.name}\n  ${model.tokens.name}"
        } else {
            "모델 없음 → 아래 경로에 풀어 넣으세요:\n${dir.absolutePath}\n\n" +
                "adb push <model-dir>/* \"${dir.absolutePath}/\"\n" +
                "(예: sherpa-onnx-streaming-zipformer-korean-2024-06-16 의 *.onnx + tokens.txt)"
        }
        runButton.isEnabled = model != null
    }

    private fun latestRecording(): File? =
        Storage.listAllFiles(this).maxByOrNull { it.lastModified() }

    private fun runSpike() {
        val file = latestRecording()
        if (file == null) {
            Toast.makeText(this, "녹음 파일이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        runButton.isEnabled = false
        resultView.text = "전사 중… (${file.name})"
        Thread {
            val out: String = try {
                val r = SttSpike.transcribe(this, file)
                lastTranscript = r.transcript
                buildString {
                    appendLine("파일: ${file.name}")
                    appendLine("오디오 길이: ${r.audioMs} ms (${r.sampleCount} 샘플 @ ${r.sampleRate}Hz)")
                    appendLine("모델 로드: ${r.modelLoadMs} ms")
                    appendLine("전사 소요: ${r.decodeMs} ms")
                    appendLine("RTF: ${"%.3f".format(r.rtf)}  (<1 = 실시간보다 빠름)")
                    appendLine("네이티브 힙 증가: ${r.nativeHeapDeltaKb} KB")
                    appendLine()
                    appendLine("=== 전사 결과 ===")
                    appendLine(r.transcript.ifBlank { "(빈 결과)" })
                    append(cerLine())
                }
            } catch (e: Throwable) {
                "실패: ${e.message}\n\n${android.util.Log.getStackTraceString(e)}"
            }
            runOnUiThread {
                resultView.text = out
                runButton.isEnabled = true
            }
        }.start()
    }

    private fun recomputeCer() {
        if (lastTranscript.isBlank()) {
            Toast.makeText(this, "먼저 전사를 실행하세요", Toast.LENGTH_SHORT).show()
            return
        }
        resultView.text = buildString {
            appendLine("=== 전사 결과 ===")
            appendLine(lastTranscript)
            append(cerLine())
        }
    }

    private fun cerLine(): String {
        val ref = referenceEt.text?.toString()?.trim().orEmpty()
        if (ref.isEmpty()) return "\n(참조문을 넣으면 CER 계산)"
        val cer = SttSpike.cer(ref, lastTranscript)
        return "\nCER: ${"%.2f".format(cer * 100)}%  (참조 ${ref.length}자 기준, 공백 제외)"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
