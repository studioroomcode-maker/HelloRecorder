package com.studioroomkr.hellorecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * 지도(osmdroid/OpenStreetMap)에서 탭해 구역 중심을 지정하는 화면.
 *  - 지도를 탭하면 그 지점에 핀 + 반경 원을 표시
 *  - 하단 슬라이더로 반경 조절
 *  - "이 위치로 지정"을 누르면 (lat, lng, radius)를 결과로 반환
 *
 * Google Play Services / API 키가 필요 없다. 타일 로딩에 인터넷만 사용.
 */
class MapPickerActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var marker: Marker
    private lateinit var circle: Polygon

    private var selected: GeoPoint = GeoPoint(37.5665, 126.9780) // 기본: 서울시청
    private var radius: Int = Prefs.DEFAULT_LOC_RADIUS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.apply(this)

        // osmdroid 설정(반드시 MapView 생성 전에)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        // 초기값: 호출 측에서 넘긴 좌표 → 없으면 현재 위치 → 없으면 기본
        val inLat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val inLng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        radius = intent.getIntExtra(EXTRA_RADIUS, Prefs.DEFAULT_LOC_RADIUS)
        if (!inLat.isNaN() && !inLng.isNaN()) {
            selected = GeoPoint(inLat, inLng)
        } else {
            currentLocation()?.let { selected = it }
        }

        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
        }

        // 지도
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(selected)
        }
        map.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        root.addView(map)

        // 반경 원 + 핀
        circle = Polygon().apply {
            fillPaint.color = (0x335A61E0).toInt()      // 반투명 인디고
            outlinePaint.color = Color.parseColor("#5A61E0")
            outlinePaint.strokeWidth = 4f
        }
        map.overlays.add(circle)

        marker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = false
        }
        map.overlays.add(marker)

        // 지도 탭 → 중심 이동
        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                selected = p
                redraw()
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        map.overlays.add(0, events)

        // 컨트롤 패널
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.SURFACE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        panel.addView(Theme.body(this, "지도를 탭해 구역 중심을 지정하세요."))

        val radiusLabel = Theme.body(this)
        fun refreshRadius() { radiusLabel.text = I18n.f("반경: %dm", radius) }
        panel.addView(radiusLabel)
        panel.addView(Theme.seekBar(this).apply {
            max = Prefs.MAX_LOC_RADIUS - Prefs.MIN_LOC_RADIUS
            progress = (radius - Prefs.MIN_LOC_RADIUS)
                .coerceIn(0, Prefs.MAX_LOC_RADIUS - Prefs.MIN_LOC_RADIUS)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    radius = Prefs.MIN_LOC_RADIUS + p
                    refreshRadius(); redraw()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        refreshRadius()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(Theme.secondaryButton(this, "취소") { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, dp(2), dp(6), dp(2)) }
        })
        row.addView(Theme.primaryButton(this, "이 위치로 지정") { confirm() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(6), dp(2), 0, dp(2)) }
        })
        panel.addView(row)
        root.addView(panel)

        setContentView(root)

        // 내비바/상태바 영역 패딩
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        redraw()
    }

    private fun redraw() {
        marker.position = selected
        circle.points = Polygon.pointsAsCircle(selected, radius.toDouble())
        map.invalidate()
    }

    private fun confirm() {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_LAT, selected.latitude)
                .putExtra(EXTRA_LNG, selected.longitude)
                .putExtra(EXTRA_RADIUS, radius)
        )
        finish()
    }

    private fun currentLocation(): GeoPoint? {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: android.location.Location? = null
        for (p in providers) {
            val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
            if (loc != null && (best == null || loc.time > best!!.time)) best = loc
        }
        return best?.let { GeoPoint(it.latitude, it.longitude) }
    }

    override fun onResume() {
        super.onResume()
        if (::map.isInitialized) map.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::map.isInitialized) map.onPause()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_RADIUS = "radius"
    }
}
