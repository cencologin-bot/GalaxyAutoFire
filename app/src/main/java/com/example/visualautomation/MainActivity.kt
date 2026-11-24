package com.example.visualautomation

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.widget.*
import android.view.Gravity
import android.view.ViewGroup
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

class MainActivity : Activity() {
    private lateinit var calibrationView: CalibrationView
    private lateinit var coordinates: TextView
    private lateinit var radiusLabel: TextView
    private lateinit var liveInfo: TextView
    private lateinit var startStop: Button
    private val reqCapture = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.rgb(19, 25, 36))
        }

        val title = TextView(this).apply {
            text = "VisualAutomation"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Calibración visual · modo de diagnóstico"
            textSize = 14f
            setTextColor(Color.LTGRAY)
        }
        root.addView(subtitle)

        calibrationView = CalibrationView(this)
        root.addView(calibrationView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        coordinates = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 12, 0, 4)
        }
        root.addView(coordinates)

        radiusLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        root.addView(radiusLabel)

        val radius = SeekBar(this).apply {
            max = 240
            progress = 55
        }
        root.addView(radius)

        val reset = Button(this).apply {
            text = "Restablecer calibración"
            setOnClickListener {
                calibrationView.setCalibration(0.35f, 0.776f, 0.07f)
                radius.progress = 55
                updateLabels()
            }
        }
        root.addView(reset)

        val save = Button(this).apply {
            text = "Guardar calibración"
            setOnClickListener {
                val (x, y, r) = calibrationView.calibration()
                getSharedPreferences("visual_automation", Context.MODE_PRIVATE)
                    .edit().putFloat("dest_x", x).putFloat("dest_y", y)
                    .putFloat("dest_radius", r).apply()
                EngineConfig.apply(this@MainActivity)
                NativeBridge.nativeSetCalibrating(false)
                Toast.makeText(this@MainActivity, "Calibración guardada", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(save)

        val test = Button(this).apply {
            text = "Probar detección en la imagen"
            setOnClickListener { testOnPreview() }
        }
        root.addView(test)

        val full = Button(this).apply {
            text = "Test a pantalla completa (imagen de referencia)"
            setOnClickListener { startActivity(Intent(this@MainActivity, ReferenceTestActivity::class.java)) }
        }
        root.addView(full)

        liveInfo = TextView(this).apply {
            setTextColor(Color.rgb(120, 220, 255)); textSize = 13f
            text = "Motor: SEARCHING"
        }
        root.addView(liveInfo)

        startStop = Button(this).apply {
            text = "Iniciar análisis visual"
            setOnClickListener { if (ScreenCaptureService.running) stopAnalysis() else startAnalysis() }
        }
        root.addView(startStop)

        val note = TextView(this).apply {
            text = "Solo reconocimiento y calibración. No ejecuta pulsaciones ni acciones en el juego."
            textSize = 12f
            setTextColor(Color.rgb(255, 205, 110))
            setPadding(0, 8, 0, 0)
        }
        root.addView(note)

        calibrationView.onCalibrationChanged = { _, _, _ ->
            NativeBridge.nativeSetCalibrating(true)   // state CALIBRATING until saved
            updateLabels()
        }
        radius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val (x, y, _) = calibrationView.calibration()
                val r = (progress.coerceAtLeast(10) / 1000f).coerceIn(0.01f, 0.25f)
                calibrationView.setCalibration(x, y, r)
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val prefs = getSharedPreferences("visual_automation", Context.MODE_PRIVATE)
        calibrationView.setCalibration(
            prefs.getFloat("dest_x", 0.35f),
            prefs.getFloat("dest_y", 0.776f),
            prefs.getFloat("dest_radius", 0.07f)
        )
        radius.progress = (prefs.getFloat("dest_radius", 0.07f) * 1000).toInt()
        setContentView(root)
        updateLabels()
    }

    private fun updateLabels() {
        val (x, y, r) = calibrationView.calibration()
        coordinates.text = "Centro normalizado: X ${"%.3f".format(x)} · Y ${"%.3f".format(y)}"
        radiusLabel.text = "Radio de tolerancia: ${"%.1f".format(r * 100)}% de la pantalla"
    }

    // ---- static test on the bundled screenshot ---------------------------------------------
    private fun testOnPreview() {
        if (!EngineConfig.ensureTemplate(this)) { toast("No se pudo cargar la plantilla"); return }
        val (x, y, r) = calibrationView.calibration()
        getSharedPreferences(EngineConfig.PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("dest_x", x).putFloat("dest_y", y).putFloat("dest_radius", r).apply()
        EngineConfig.apply(this)
        NativeBridge.nativeSetCalibrating(false)
        NativeBridge.nativeReset()
        val bmp = BitmapFactory.decodeResource(resources, R.drawable.game_preview)
            .copy(Bitmap.Config.ARGB_8888, false)
        var st = EngineState()
        for (i in 0 until 6) {   // a few frames: scale scan, lock, 3-frame arrival confirmation
            NativeBridge.nativeProcessBitmap(bmp, 1000L + i * 33L)?.let { st = EngineState.from(it) }
        }
        calibrationView.detection = st
        liveInfo.text = describe(st)
    }

    // ---- live capture ---------------------------------------------------------------------------
    private fun startAnalysis() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Concede el permiso de superposición y vuelve a pulsar Iniciar")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 43)
        }
        val (x, y, r) = calibrationView.calibration()
        getSharedPreferences(EngineConfig.PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("dest_x", x).putFloat("dest_y", y).putFloat("dest_radius", r).apply()
        @Suppress("DEPRECATION")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        startActivityForResult(captureIntent, reqCapture)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_DATA, data))
            startStop.text = "Detener análisis visual"
            toast("Abre el juego y pulsa ▶ en el panel flotante para iniciar el test")
            moveTaskToBack(true)
        }
    }

    private fun stopAnalysis() {
        startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
        startStop.text = "Iniciar análisis visual"
    }

    override fun onResume() {
        super.onResume()
        startStop.text = if (ScreenCaptureService.running) "Detener análisis visual" else "Iniciar análisis visual"
        var last = 0L
        ScreenCaptureService.listener = { st ->
            val now = SystemClock.elapsedRealtime()
            if (now - last > 200) { last = now; runOnUiThread { liveInfo.text = describe(st) } }
        }
    }

    override fun onPause() { ScreenCaptureService.listener = null; super.onPause() }

    private fun describe(s: EngineState) =
        "${s.status} · X ${s.centerX.toInt()} Y ${s.centerY.toInt()} · conf ${(s.confidence * 100).toInt()}% · " +
        "${s.speed.toInt()} px/s · ${"%.0f".format(s.fps)} FPS"

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
