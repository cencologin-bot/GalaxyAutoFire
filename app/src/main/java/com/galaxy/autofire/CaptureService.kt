package com.galaxy.autofire

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closing = AtomicBoolean(false)
    private val stabilizer = StateStabilizer(confirmFrames = 2)

    @Volatile private var config = DetectionConfig()
    private var lastFrameMs = 0L
    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    private var fps = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY // ya hay una sesión activa

        ensureChannel()
        startAsForeground() // debe ir antes de getMediaProjection (Android 14+)

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.projectionData()
        if (code != Activity.RESULT_OK || data == null) {
            fail("Permiso de captura no concedido.")
            return START_NOT_STICKY
        }
        try {
            beginCapture(code, data)
        } catch (e: Exception) { // SecurityException, IllegalStateException…
            fail("No se pudo iniciar la captura: ${e.message ?: e.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(code: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val proj = manager.getMediaProjection(code, data)
        val thread = HandlerThread("GalaxyCapture").also { it.start() }
        val handler = Handler(thread.looper)
        worker = thread

        // Android 14+: el callback debe registrarse antes de crear el VirtualDisplay.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() = shutdown()
        }
        proj.registerCallback(callback, handler)
        projectionCallback = callback
        projection = proj

        val (w, h) = captureSize()
        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener({ onFrame(it) }, handler)
        reader = imageReader
        display = proj.createVirtualDisplay(
            "GalaxyAutoFireCapture", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler,
        )

        scope.launch { SettingsRepository(applicationContext).config.collect { config = it } }
        stabilizer.reset()
        MonitorBus.update { MonitorSnapshot(running = true) }
    }

    private fun onFrame(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMs < MIN_INTERVAL_MS) return
            lastFrameMs = now

            val plane = image.planes[0]
            val raw = LightDetector.analyze(
                plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride, config,
            )
            val stable = stabilizer.update(raw.state)

            fpsFrames++
            if (now - fpsWindowStart >= 1000) {
                fps = fpsFrames
                fpsFrames = 0
                fpsWindowStart = now
            }
            MonitorBus.update {
                it.copy(
                    running = true, state = stable, greenRatio = raw.greenRatio,
                    redRatio = raw.redRatio, samples = raw.samples, fps = fps, error = null,
                )
            }
        } finally {
            image.close()
        }
    }

    /** Tamaño real de pantalla, reducido a la mitad: menos CPU y la región es normalizada. */
    private fun captureSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
        fun even(v: Int) = ((v * CAPTURE_SCALE).toInt() and 1.inv()).coerceAtLeast(2)
        return even(w) to even(h)
    }

    private fun fail(message: String) {
        MonitorBus.update { it.copy(running = false, state = null, fps = 0, error = message) }
        shutdown(keepError = true)
    }

    private fun shutdown(keepError: Boolean = false) {
        if (!closing.compareAndSet(false, true)) return // idempotente: onStop → shutdown → stop() → onStop…
        scope.cancel()
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.let { p ->
            projectionCallback?.let(p::unregisterCallback)
            p.stop()
        }
        projection = null
        projectionCallback = null
        worker?.quitSafely()
        worker = null
        MonitorBus.update {
            if (keepError) it.copy(running = false, state = null, fps = 0)
            else it.copy(running = false, state = null, greenRatio = 0f, redRatio = 0f, fps = 0)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Monitor de pantalla", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Galaxy Auto Fire")
            .setContentText("Monitor de pantalla activo")
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun Intent.projectionData(): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") getParcelableExtra<Intent>(EXTRA_DATA)
        }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "com.galaxy.autofire.STOP"
        private const val EXTRA_CODE = "resultCode"
        private const val EXTRA_DATA = "resultData"
        private const val CAPTURE_SCALE = 0.5f
        private const val MIN_INTERVAL_MS = 33L // ~30 análisis/s como máximo

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
    }
}
