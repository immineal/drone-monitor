package com.dronemonitor

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity(), SurfaceHolder.Callback, DroneClient.Listener {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var tilt: TextView
    private lateinit var controls: LinearLayout

    private var client: DroneClient? = null
    private var surfaceReady = false
    @Volatile private var streaming = false

    private var videoW = 16
    private var videoH = 9

    private val ui = Handler(Looper.getMainLooper())
    private val hideTilt = Runnable { tilt.animate().alpha(0f).setDuration(400).start() }

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
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            text = "Starting…"
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        controls = buildControls()
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )

        tilt = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(36, 18, 36, 18)
            background = pill(Color.argb(140, 0, 0, 0))
            alpha = 0f
        }
        root.addView(
            tilt,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM
            ).apply { rightMargin = 150; bottomMargin = 48 }
        )

        setContentView(root)
        surfaceView.holder.addCallback(this)
        root.setOnClickListener { applyImmersive() }
    }

    private fun buildControls(): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, 0, 24, 0)
        col.addView(controlButton("▲") { nudge(-15) })   // tilt toward horizon
        col.addView(controlButton("●") { center() })      // recenter (forward)
        col.addView(controlButton("▼") { nudge(15) })     // tilt toward ground
        return col
    }

    private fun controlButton(glyph: String, onTap: () -> Unit): TextView {
        val size = dp(56)
        val tv = TextView(this)
        tv.text = glyph
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        tv.background = pill(Color.argb(90, 20, 24, 30))
        tv.alpha = 0.5f
        val lp = LinearLayout.LayoutParams(size, size)
        lp.topMargin = dp(10)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        tv.layoutParams = lp
        tv.setOnClickListener {
            tv.animate().alpha(1f).setDuration(80).withEndAction {
                tv.animate().alpha(0.5f).setDuration(400).start()
            }.start()
            onTap()
        }
        return tv
    }

    private fun pill(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(28).toFloat()
        d.setColor(color)
        return d
    }

    private fun nudge(delta: Int) {
        val c = client ?: return
        val a = c.nudgeGimbal(delta)
        showTilt(a)
    }

    private fun center() {
        val c = client ?: return
        c.setGimbalAngle(0)
        showTilt(0)
    }

    private fun showTilt(angle: Int) {
        tilt.text = "Tilt ${angle}°"
        tilt.animate().alpha(1f).setDuration(120).start()
        ui.removeCallbacks(hideTilt)
        ui.postDelayed(hideTilt, 1600)
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

    // DroneClient.Listener
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

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
