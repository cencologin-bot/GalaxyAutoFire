package com.galaxy.autofire

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.autofire.ui.GalaxyApp
import com.galaxy.autofire.ui.GalaxyTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this, CaptureService.startIntent(this, result.resultCode, data),
                )
            }
        }

    // Se continúa con la captura aunque se rechace: el servicio funciona sin ver la notificación.
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            GalaxyTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                GalaxyApp(
                    ui = ui,
                    onToggle = { if (ui.monitor.running) stopMonitoring() else startMonitoring() },
                    onConfig = viewModel::updateConfig,
                    onTiming = viewModel::updateTiming,
                    onReset = viewModel::resetDefaults,
                )
            }
        }
    }

    private fun startMonitoring() {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopMonitoring() {
        stopService(Intent(this, CaptureService::class.java))
    }
}
