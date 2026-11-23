package com.example.visualautomation

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Floating, draggable control panel shown over the game (service window) or the reference image. */
class ControlPanel(ctx: Context, showSim: Boolean) : LinearLayout(ctx) {
    var onToggle: (() -> Unit)? = null
    var onCalibrate: (() -> Unit)? = null
    var onRadius: ((Int) -> Unit)? = null
    var onSim: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 10 * d }
    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
    private fun button(label: String, action: () -> Unit) = TextView(context).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setPadding(dp(11), dp(6), dp(11), dp(6)); background = pill(Color.rgb(36, 48, 73))
        setOnClickListener { action() }
    }

    private val title = TextView(ctx)
    private val info = TextView(ctx)
    private val btnRun = button("▶") { onToggle?.invoke() }
    private val btnCal = button("🎯") { onCalibrate?.invoke() }
    private val btnMinus = button("◎−") { onRadius?.invoke(-1) }
    private val btnPlus = button("◎+") { onRadius?.invoke(1) }
    private val btnSim = button("⇆") { onSim?.invoke() }
    private val btnClose = button("✕") { onClose?.invoke() }
    private var lx = 0f
    private var ly = 0f

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.argb(228, 16, 22, 34)); cornerRadius = 14 * d; setStroke(dp(1), Color.rgb(60, 75, 100))
        }
        title.apply {
            text = "⠿  VisualAutomation · solo reconocimiento"; textSize = 11f
            setTextColor(Color.rgb(143, 160, 184)); setPadding(0, 0, 0, dp(4))
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lx = e.rawX; ly = e.rawY }
                    MotionEvent.ACTION_MOVE -> { onDrag?.invoke(e.rawX - lx, e.rawY - ly); lx = e.rawX; ly = e.rawY }
                }
                true
            }
        }
        info.apply { text = "En espera · pulsa ▶"; textSize = 12f; setTextColor(Color.rgb(120, 220, 255)); setPadding(0, 0, 0, dp(6)) }
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            addView(btnRun, lp()); addView(btnCal, lp()); addView(btnMinus, lp()); addView(btnPlus, lp())
            if (showSim) addView(btnSim, lp())
            addView(btnClose, lp())
        }
        btnMinus.visibility = View.GONE; btnPlus.visibility = View.GONE
        addView(title); addView(info); addView(row)
    }

    fun setRunning(on: Boolean) {
        btnRun.text = if (on) "■" else "▶"
        btnRun.background = pill(if (on) Color.rgb(140, 45, 45) else Color.rgb(31, 111, 74))
    }

    fun setCalibrating(on: Boolean) {
        btnCal.background = pill(if (on) Color.rgb(106, 74, 160) else Color.rgb(36, 48, 73))
        val v = if (on) View.VISIBLE else View.GONE
        btnMinus.visibility = v; btnPlus.visibility = v
    }

    fun setSim(on: Boolean) { btnSim.alpha = if (on) 1f else 0.45f }
    fun setInfo(t: String) { info.text = t }
}
