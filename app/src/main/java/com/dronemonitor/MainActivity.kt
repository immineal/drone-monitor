package com.dronemonitor

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity(), SurfaceHolder.Callback, DroneClient.Listener {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var toast: TextView
    private lateinit var recordButton: TextView
    private lateinit var telemetryBar: TextView
    private lateinit var batteryView: TextView
    private var batteryPulse: android.animation.ObjectAnimator? = null
    private lateinit var settingsPanel: LinearLayout
    private lateinit var settingsInfo: TextView
    private lateinit var cameraRow: TextView

    private var client: DroneClient? = null
    private var surfaceReady = false
    @Volatile private var streaming = false
    private var recording = false
    private var mirrorOn = false
    private var resText = ""
    private var lastTelemetry: VisonTelemetry.Telemetry? = null

    private var videoW = 16
    private var videoH = 9

    private val ui = Handler(Looper.getMainLooper())
    private val hideToast = Runnable { toast.animate().alpha(0f).setDuration(400).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(44, 28, 44, 28)
            background = pill(Color.argb(150, 0, 0, 0))
            text = "Starting…"
        }
        root.addView(status, centered())

        telemetryBar = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = pill(Color.argb(120, 0, 0, 0))
            visibility = View.GONE
        }
        root.addView(
            telemetryBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP
            ).apply { topMargin = dp(10) }
        )

        batteryView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = pill(Color.argb(150, 0, 0, 0))
            visibility = View.GONE
        }
        root.addView(
            batteryView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP
            ).apply { leftMargin = dp(18); topMargin = dp(12) }
        )

        addGimbalControls()
        addCaptureControls()
        addSettings()

        toast = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(36, 18, 36, 18)
            background = pill(Color.argb(150, 0, 0, 0))
            alpha = 0f
        }
        root.addView(
            toast,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply { bottomMargin = dp(40) }
        )

        setContentView(root)
        surfaceView.holder.addCallback(this)
    }

    private fun centered() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER
    )

    // ---- gimbal (right edge, press and hold) ------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addGimbalControls() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val up = roundButton("▲")
        val down = roundButton("▼")
        up.setOnTouchListener { v, e -> handleTilt(v, e, true); true }
        down.setOnTouchListener { v, e -> handleTilt(v, e, false); true }
        col.addView(up)
        col.addView(down, LinearLayout.LayoutParams(dp(56), dp(56)).apply { topMargin = dp(14) })

        root.addView(
            col,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = dp(18) }
        )
    }

    private fun handleTilt(v: View, e: MotionEvent, up: Boolean) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                v.alpha = 1f
                client?.gimbalPress(up)
                showToast(if (up) "Tilting up" else "Tilting down")
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.alpha = 0.5f
                client?.gimbalRelease()
            }
        }
    }

    // ---- capture (left edge) ----------------------------------------------

    private fun addCaptureControls() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val photo = roundButton("○")
        photo.setOnClickListener {
            client?.takePhoto()
            flash()
            showToast("Photo → SD card")
        }
        recordButton = roundButton("●")
        recordButton.setTextColor(Color.rgb(255, 80, 80))
        recordButton.setOnClickListener { toggleRecord() }

        col.addView(photo)
        col.addView(recordButton, LinearLayout.LayoutParams(dp(56), dp(56)).apply { topMargin = dp(14) })

        root.addView(
            col,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply { leftMargin = dp(18) }
        )
    }

    private fun toggleRecord() {
        val c = client ?: return
        recording = !recording
        if (recording) {
            c.startRecord()
            recordButton.text = "■"
            recordButton.background = pill(Color.argb(180, 200, 40, 40))
            showToast("Recording to SD card")
        } else {
            c.stopRecord()
            recordButton.text = "●"
            recordButton.background = pill(Color.argb(90, 20, 24, 30))
            showToast("Recording stopped")
        }
    }

    // ---- settings panel (gear, top-right) ---------------------------------

    private fun addSettings() {
        val gear = roundButton("⚙")
        root.addView(
            gear,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.TOP)
                .apply { rightMargin = dp(18); topMargin = dp(14) }
        )
        gear.setOnClickListener { toggleSettings() }

        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = pill(Color.argb(230, 16, 20, 26))
            visibility = View.GONE
        }
        settingsPanel.addView(TextView(this).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(4), 0, 0, dp(10))
        })
        cameraRow = settingRow("Camera: main lens") {
            val second = client?.switchCamera() ?: false
            cameraRow.text = "Camera: " + if (second) "second lens" else "main lens"
            showToast("Switched to " + if (second) "second lens" else "main lens")
        }
        settingsPanel.addView(cameraRow)
        settingsPanel.addView(settingRow("Image: normal") {
            mirrorOn = !mirrorOn
            client?.setMirror(mirrorOn)
            (it as TextView).text = "Image: " + if (mirrorOn) "mirrored" else "normal"
        })
        settingsInfo = TextView(this).apply {
            setTextColor(Color.argb(210, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(4), dp(12), dp(4), dp(6))
        }
        settingsPanel.addView(settingsInfo)
        settingsPanel.addView(settingRow("Close") { toggleSettings() })

        root.addView(
            settingsPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP
            ).apply { rightMargin = dp(18); topMargin = dp(80) }
        )
    }

    private fun settingRow(label: String, onClick: (View) -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.setPadding(dp(14), dp(12), dp(14), dp(12))
        tv.background = pill(Color.argb(60, 255, 255, 255))
        tv.layoutParams = LinearLayout.LayoutParams(dp(230), LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        tv.setOnClickListener { onClick(tv) }
        return tv
    }

    private fun toggleSettings() {
        val show = settingsPanel.visibility != View.VISIBLE
        settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) updateSettingsInfo()
    }

    private fun updateSettingsInfo() {
        val sb = StringBuilder()
        if (resText.isNotEmpty()) sb.append("Resolution: ").append(resText).append('\n')
        lastTelemetry?.let { t ->
            if (t.visonCode >= 0) sb.append("Drone code: ").append(t.visonCode).append('\n')
            if (t.hasData) sb.append(String.format("Battery: %.1f V\n", t.batteryVolts))
            if (t.rssiDbm != 0) sb.append("Link: ").append(t.rssiDbm).append(" dBm")
        }
        settingsInfo.text = sb.toString().trim()
    }

    private fun flash() {
        val f = View(this)
        f.setBackgroundColor(Color.WHITE)
        root.addView(f, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        f.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(f) }.start()
    }

    // ---- shared UI helpers ------------------------------------------------

    private fun roundButton(glyph: String): TextView {
        val tv = TextView(this)
        tv.text = glyph
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        tv.background = pill(Color.argb(90, 20, 24, 30))
        tv.alpha = 0.5f
        tv.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        return tv
    }

    private fun pill(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(28).toFloat()
        d.setColor(color)
        return d
    }

    private fun showToast(text: String) {
        toast.text = text
        toast.animate().alpha(1f).setDuration(120).start()
        ui.removeCallbacks(hideToast)
        ui.postDelayed(hideToast, 1400)
    }

    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun resizeSurface() {
        val availW = root.width
        val availH = root.height
        if (availW == 0 || availH == 0) return
        val scale = minOf(availW.toFloat() / videoW, availH.toFloat() / videoH)
        val w = (videoW * scale).toInt()
        val h = (videoH * scale).toInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        maybeStart()
    }

    override fun onPause() {
        super.onPause()
        stopClient()
    }

    private fun maybeStart() {
        if (!surfaceReady || client != null) return
        streaming = false
        val c = DroneClient(this, surfaceView.holder.surface, this)
        client = c
        c.start()
    }

    private fun stopClient() {
        client?.stop()
        client = null
        streaming = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        maybeStart()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        resizeSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopClient()
    }

    override fun onStatus(text: String) {
        runOnUiThread {
            if (streaming) return@runOnUiThread
            status.visibility = View.VISIBLE
            status.text = text
        }
    }

    override fun onFirstFrame() {
        runOnUiThread {
            streaming = true
            status.visibility = View.GONE
        }
    }

    override fun onResolution(width: Int, height: Int) {
        runOnUiThread {
            if (width > 0 && height > 0) {
                videoW = width
                videoH = height
                resText = "${width}×${height}"
                resizeSurface()
            }
        }
    }

    override fun onTelemetry(t: VisonTelemetry.Telemetry) {
        runOnUiThread {
            lastTelemetry = t
            if (settingsPanel.visibility == View.VISIBLE) updateSettingsInfo()
            if (t.hasData) updateBattery(t.batteryVolts, t.lowBattery)
            if (!t.hasData && t.rssiDbm == 0) return@runOnUiThread
            val sb = StringBuilder()
            if (t.hasData) {
                sb.append(
                    String.format(
                        "SAT %d   ALT %.1fm   DST %.0fm   SPD %.1fm/s",
                        t.satellites, t.heightM, t.distanceM, t.hSpeed
                    )
                )
            }
            if (t.rssiDbm != 0) {
                if (sb.isNotEmpty()) sb.append("   ")
                sb.append("LINK ${t.rssiDbm}dBm")
            }
            telemetryBar.text = sb.toString()
            telemetryBar.visibility = View.VISIBLE
        }
    }

    // 2S LiPo (full 8.4V): green ok, amber = head back, red = land now, pulsing when critical.
    private fun updateBattery(v: Float, lowFlag: Boolean) {
        if (v <= 0f) { batteryView.visibility = View.GONE; stopBatteryPulse(); return }
        batteryView.visibility = View.VISIBLE
        val bg: Int; val label: String; val critical: Boolean
        when {
            lowFlag || v < 6.6f -> { bg = Color.rgb(200, 20, 20); label = "CRITICAL — LAND"; critical = true }
            v < 7.0f -> { bg = Color.rgb(200, 45, 45); label = "LAND NOW"; critical = true }
            v < 7.2f -> { bg = Color.rgb(205, 110, 20); label = "LAND SOON"; critical = false }
            v < 7.4f -> { bg = Color.rgb(198, 165, 20); label = "HEAD BACK"; critical = false }
            else -> { bg = Color.rgb(30, 135, 60); label = ""; critical = false }
        }
        batteryView.text = if (label.isEmpty()) String.format("%.1f V", v) else String.format("%.1f V  ·  %s", v, label)
        batteryView.background = pill(Color.argb(235, Color.red(bg), Color.green(bg), Color.blue(bg)))
        if (critical) startBatteryPulse() else stopBatteryPulse()
    }

    private fun startBatteryPulse() {
        if (batteryPulse?.isStarted == true) return
        batteryPulse = android.animation.ObjectAnimator.ofFloat(batteryView, "alpha", 1f, 0.35f).apply {
            duration = 450
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopBatteryPulse() {
        batteryPulse?.cancel()
        batteryPulse = null
        batteryView.alpha = 1f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
