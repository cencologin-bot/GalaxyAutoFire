package com.example.visualautomation

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreground service: MediaProjection capture -> native OpenCV engine -> overlay.
 * A floating control panel sits over the game; capture starts paused and the test begins with the panel's play button.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "va_capture"
        private const val MAX_CAPTURE_WIDTH = 720

        @Volatile var running = false
        @Volatile var listener: ((EngineState) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var overlay: OverlayView? = null
    private var calibView: OverlayView? = null
    private var panel: ControlPanel? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var paused = true
    @Volatile private var calibrating = false
    private var lastUi = 0L
    private var lastDiag = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startInForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        startCapture(code, data)
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Análisis visual", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("VisualAutomation")
            .setContentText("Panel flotante activo (solo reconocimiento)")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Detener", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun startCapture(code: Int, data: Intent) {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 1) Floating UI first, so the person always sees the panel (and any error) over the game.
        showOverlay()
        showPanel()
        if (panel == null) {
            Toast.makeText(this, "Falta el permiso de superposición sobre otras apps", Toast.LENGTH_LONG).show()
        }
        try {
            // 2) Native engine (fails here if libopencv_java4.so / libvisual_automation.so are not packaged).
            check(EngineConfig.ensureTemplate(this)) { "no se pudo cargar la plantilla" }
            EngineConfig.apply(this)
            NativeBridge.nativeSetCalibrating(false)
            NativeBridge.nativeReset()

            // 3) Screen capture.
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(code, data).also {
                it.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { stopSelf() }
                }, main)
            }
            val real = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(real)
            val k = min(1f, MAX_CAPTURE_WIDTH / real.widthPixels.toFloat())
            val w = (real.widthPixels * k).roundToInt()
            val h = (real.heightPixels * k).roundToInt()

            thread = HandlerThread("va-vision").also { it.start() }
            val handler = Handler(thread!!.looper)
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            display = projection!!.createVirtualDisplay("va-capture", w, h, (real.densityDpi * k).toInt().coerceAtLeast(72),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)

            reader!!.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val nowD = SystemClock.elapsedRealtime()
                    if (nowD - lastDiag > 700) { lastDiag = nowD; runCatching { captureDiag(img) } }
                    if (paused && !calibrating) return@setOnImageAvailableListener   // idle: no processing
                    val plane = img.planes[0]
                    val raw = NativeBridge.nativeProcess(plane.buffer, img.width, img.height,
                        plane.rowStride, SystemClock.elapsedRealtime())
                    if (raw != null) {
                        val st = EngineState.from(raw)
                        overlay?.let { it.state = st; it.postInvalidate() }
                        calibView?.let { it.state = st; it.postInvalidate() }
                        listener?.invoke(st)
                        if (ArrivalActions.onState(applicationContext, st))
                            main.post { panel?.setNote("✔ Llegada confirmada · acción: ${ArrivalActions.LABELS[ArrivalActions.selected(applicationContext)]}") }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi > 200) {
                            lastUi = now
                            main.post { panel?.setInfo("${st.status} · ${(st.confidence * 100).toInt()}% · ${st.speed.toInt()} px/s · ${"%.0f".format(st.fps)} FPS") }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("VisualAutomation", "frame processing failed", t)
                    main.post { panel?.setInfo("Error de proceso: ${t.javaClass.simpleName} ${t.message ?: ""}") }
                    paused = true
                } finally { img.close() }
            }, handler)
            running = true
            panel?.setInfo("Listo · abre el juego y pulsa ▶")
        } catch (t: Throwable) {
            Log.e("VisualAutomation", "capture start failed", t)
            val msg = "Error: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
            panel?.setInfo(msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Shows what the engine actually receives and warns if the frame is black (secure/blocked content). */
    private fun captureDiag(img: Image) {
        val plane = img.planes[0]
        val rowPx = plane.rowStride / plane.pixelStride
        val full = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        val tw = 140
        val th = (img.height * tw / img.width.toFloat()).roundToInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(Bitmap.createBitmap(full, 0, 0, img.width, img.height), tw, th, true)
        full.recycle()
        val px = IntArray(tw * th)
        thumb.getPixels(px, 0, tw, 0, 0, tw, th)
        var sum = 0L
        for (c in px) sum += (((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)) / 3
        val mean = (sum / px.size).toInt()
        val note = if (mean < 6) "⚠ Captura NEGRA: la pantalla del juego está bloqueada o no se está capturando"
                   else "Captura de pantalla OK · ${img.width}×${img.height} · brillo $mean"
        main.post { panel?.setPreview(thumb); panel?.setNote(note) }
    }

    private fun lp(touchable: Boolean, wrap: Boolean = false) = WindowManager.LayoutParams(
        if (wrap) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
        if (wrap) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT)

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        overlay = OverlayView(this).also { wm.addView(it, lp(touchable = false)) }
    }

    private fun showPanel() {
        if (!Settings.canDrawOverlays(this)) return
        val d = resources.displayMetrics.density
        val params = lp(touchable = true, wrap = true).apply {
            gravity = Gravity.TOP or Gravity.START; x = (12 * d).toInt(); y = (160 * d).toInt()
        }
        panelLp = params
        panel = ControlPanel(this, false).also { p ->
            p.onDrag = { dx, dy -> params.x += dx.toInt(); params.y += dy.toInt(); wm.updateViewLayout(p, params) }
            p.onToggle = { togglePlay() }
            p.onCalibrate = { setCalibration(!calibrating) }
            p.onRadius = { dir -> EngineConfig.adjustRadius(this, dir) }
            p.onClose = { stopSelf() }
            p.setActionLabels(ArrivalActions.LABELS, ArrivalActions.selected(this))
            p.onAction = { i -> ArrivalActions.select(this, i); p.setActionLabels(ArrivalActions.LABELS, i) }
            wm.addView(p, params)
        }
    }

    private fun togglePlay() {
        paused = !paused
        panel?.setRunning(!paused)
        if (paused) {
            overlay?.let { it.state = EngineState(); it.postInvalidate() }
            panel?.setInfo("En pausa · pulsa ▶")
        } else {
            if (calibrating) setCalibration(false)
            NativeBridge.nativeSetCalibrating(false)
            NativeBridge.nativeReset()
            ArrivalActions.reset()
            panel?.setInfo("Buscando la estrella plateada…")
        }
    }

    private fun setCalibration(on: Boolean) {
        if (on == calibrating) return
        calibrating = on
        panel?.setCalibrating(on)
        if (on) {
            NativeBridge.nativeSetCalibrating(true)
            panel?.setInfo("Toca la pantalla para mover el destino · ◎ radio")
            calibView = OverlayView(this).also { v ->
                v.state = overlay?.state ?: EngineState()
                v.onTouchNorm = { nx, ny, _ -> EngineConfig.setDestination(this, nx, ny) }
                wm.addView(v, lp(touchable = true))
            }
            panel?.let { p ->                        // keep the panel above the calibration layer
                runCatching { wm.removeView(p) }
                wm.addView(p, panelLp)
            }
        } else {
            calibView?.let { runCatching { wm.removeView(it) } }
            calibView = null
            NativeBridge.nativeSetCalibrating(false)
            EngineConfig.apply(this)
            if (!paused) NativeBridge.nativeReset()
        }
    }

    override fun onDestroy() {
        running = false
        reader?.setOnImageAvailableListener(null, null)
        display?.release(); reader?.close()
        projection?.stop()
        listOfNotNull(overlay, calibView, panel).forEach { runCatching { wm.removeView(it) } }
        thread?.quitSafely()
        NativeBridge.nativeSetCalibrating(false)
        listener?.invoke(EngineState())
        super.onDestroy()
    }
}
