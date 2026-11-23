package com.example.visualautomation

import android.app.Activity
import android.graphics.*
import android.os.*
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Full-screen test on the bundled reference screenshot, with the control panel floating over it.
 * "Simular desplazamiento" scrolls the barrel block horizontally (wrapping) so the star moves.
 */
class ReferenceTestActivity : Activity() {
    private lateinit var view: OverlayView
    private lateinit var panel: ControlPanel
    private lateinit var base: Bitmap
    private val bufs = arrayOfNulls<Bitmap>(2)
    private var flip = 0
    @Volatile private var running = false
    @Volatile private var calib = false
    @Volatile private var sim = true
    private var off = 0f
    private var lastTs = 0L
    private var lastUi = 0L
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler

    // Barrel block of the reference image (pixels of 691x1536) that scrolls and wraps.
    private val win = Rect(96, 935, 496, 1260)
    private val speedPxS = 90f

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        base = BitmapFactory.decodeResource(resources, R.drawable.game_preview).copy(Bitmap.Config.ARGB_8888, false)
        EngineConfig.ensureTemplate(this)
        EngineConfig.apply(this)
        NativeBridge.nativeSetCalibrating(false)
        NativeBridge.nativeReset()

        view = OverlayView(this).apply {
            frameBitmap = base
            onTouchNorm = { nx, ny, _ -> if (calib) EngineConfig.setDestination(this@ReferenceTestActivity, nx, ny) }
        }
        panel = ControlPanel(this, true).apply {
            setSim(sim)
            onDrag = { dx, dy -> translationX += dx; translationY += dy }
            onToggle = { toggleRun() }
            onCalibrate = { toggleCalibration() }
            onRadius = { dir -> EngineConfig.adjustRadius(this@ReferenceTestActivity, dir) }
            onSim = { sim = !sim; setSim(sim) }
            onClose = { finish() }
        }
        val d = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = (12 * d).toInt(); topMargin = (150 * d).toInt()
            })
        }
        setContentView(root)

        thread = HandlerThread("va-ref-test").also { it.start() }
        worker = Handler(thread.looper)
        worker.post(loop)
    }

    private fun toggleRun() {
        running = !running
        panel.setRunning(running)
        if (running) {
            calib = false; panel.setCalibrating(false)
            NativeBridge.nativeSetCalibrating(false); NativeBridge.nativeReset()
            off = 0f; lastTs = SystemClock.elapsedRealtime()
        } else {
            view.frameBitmap = base; view.state = EngineState(); view.invalidate()
        }
    }

    private fun toggleCalibration() {
        calib = !calib
        panel.setCalibrating(calib)
        NativeBridge.nativeSetCalibrating(calib)
        if (calib) {
            running = false; panel.setRunning(false); off = 0f
            showCalibHint()
        } else {
            NativeBridge.nativeReset()
            if (!running) { view.state = EngineState(); view.invalidate() }
        }
    }

    private fun showCalibHint() { panel.setInfo("Toca la imagen para mover el destino · ◎ ajusta el radio") }

    private fun render(): Bitmap {
        val b = bufs[flip] ?: base.copy(Bitmap.Config.ARGB_8888, true).also { bufs[flip] = it }
        flip = 1 - flip
        val c = Canvas(b)
        c.drawBitmap(base, 0f, 0f, null)
        if (sim && running && off > 0f) {
            val o = off.toInt()
            c.save(); c.clipRect(win)
            c.drawBitmap(base, win, Rect(win.left + o, win.top, win.right + o, win.bottom), null)
            c.drawBitmap(base, win, Rect(win.left + o - win.width(), win.top, win.right + o - win.width(), win.bottom), null)
            c.restore()
        }
        return b
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running && !calib) { worker.postDelayed(this, 80); return }
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - lastTs).coerceAtLeast(0)) / 1000f
            lastTs = now
            if (sim && running) off = (off + speedPxS * dt) % win.width()
            val frame = render()
            val st = NativeBridge.nativeProcessBitmap(frame, now)?.let { EngineState.from(it) }
            runOnUiThread {
                view.frameBitmap = frame
                if (st != null) {
                    view.state = st
                    if (now - lastUi > 200 && !calib) {
                        lastUi = now
                        panel.setInfo("${st.status} · ${(st.confidence * 100).toInt()}% · ${st.speed.toInt()} px/s · ${"%.0f".format(st.fps)} FPS")
                    }
                }
                view.invalidate()
            }
            worker.postDelayed(this, 33)
        }
    }

    override fun onDestroy() {
        running = false; calib = false
        worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
        NativeBridge.nativeSetCalibrating(false)
        super.onDestroy()
    }
}
