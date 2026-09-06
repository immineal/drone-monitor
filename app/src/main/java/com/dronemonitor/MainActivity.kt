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

    private var client: DroneClient? = null
    private var surfaceReady = false
    @Volatile private var streaming = false
    private var recording = false

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

        addGimbalControls()
        addCaptureControls()

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
                resizeSurface()
            }
        }
    }

    override fun onTelemetry(t: VisonTelemetry.Telemetry) {
        runOnUiThread {
            if (!t.hasData && t.rssiDbm == 0) return@runOnUiThread
            val sb = StringBuilder()
            if (t.hasData) {
                if (t.lowBattery) sb.append("LOW BATT   ")
                sb.append(
                    String.format(
                        "BAT %.1fV   SAT %d   ALT %.1fm   DST %.0fm   SPD %.1fm/s",
                        t.batteryVolts, t.satellites, t.heightM, t.distanceM, t.hSpeed
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
