package com.example.visualautomation

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happens when the engine confirms ARRIVED. The last entry is a reserved slot for the custom
 * action that will be defined later; today only visual / alert / log actions exist.
 */
object ArrivalActions {
    val LABELS = listOf(
        "Solo visual",
        "Aviso: vibración + sonido",
        "Registrar evento en archivo",
        "Acción personalizada (próximamente)"
    )
    private const val PREF = "arrival_action"
    private var prev = EngineStatus.SEARCHING
    private var lastFire = 0L

    fun selected(ctx: Context): Int =
        ctx.getSharedPreferences(EngineConfig.PREFS, Context.MODE_PRIVATE).getInt(PREF, 1)

    fun select(ctx: Context, index: Int) {
        ctx.getSharedPreferences(EngineConfig.PREFS, Context.MODE_PRIVATE).edit().putInt(PREF, index).apply()
    }

    fun reset() { prev = EngineStatus.SEARCHING }

    /** Call for every processed frame. Returns true on the frame where an arrival triggered the action. */
    fun onState(ctx: Context, st: EngineState): Boolean {
        val arrivedNow = st.status == EngineStatus.ARRIVED && prev != EngineStatus.ARRIVED
        prev = st.status
        if (!arrivedNow) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastFire < 800) return false
        lastFire = now
        when (selected(ctx)) {
            1 -> alert(ctx)
            2 -> log(ctx, st)
        }
        return true
    }

    private fun alert(ctx: Context) {
        runCatching {
            ctx.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 500)
        }
    }

    private fun log(ctx: Context, st: EngineState) {
        runCatching {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            File(dir, "events.log").appendText(
                "$ts,ARRIVED,x=${st.centerX.toInt()},y=${st.centerY.toInt()},conf=${"%.2f".format(st.confidence)}\n")
        }
    }
}
