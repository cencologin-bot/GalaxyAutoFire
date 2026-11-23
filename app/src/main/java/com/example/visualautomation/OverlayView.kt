package com.example.visualautomation

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * Draws the engine state. Used (a) as the non-touchable HUD over the game, (b) as a touchable
 * calibration layer and (c) as the full-screen reference-image test view (frameBitmap set).
 * Strokes are thin and the rectangle is inflated so the overlay barely alters matched pixels.
 */
class OverlayView(context: Context) : View(context) {
    @Volatile var state: EngineState = EngineState()
    var frameBitmap: Bitmap? = null
    /** (normX, normY, isUp) in frame space; when set the view consumes touches. */
    var onTouchNorm: ((Float, Float, Boolean) -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val rect = RectF()

    private fun layoutRect() {
        val b = frameBitmap
        if (b == null) { rect.set(0f, 0f, width.toFloat(), height.toFloat()); return }
        val k = minOf(width / b.width.toFloat(), height / b.height.toFloat())
        val w = b.width * k; val h = b.height * k
        rect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cb = onTouchNorm ?: return false
        layoutRect()
        val nx = ((e.x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val ny = ((e.y - rect.top) / rect.height()).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> cb(nx, ny, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cb(nx, ny, true)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        layoutRect()
        frameBitmap?.let { c.drawBitmap(it, null, rect, null) }
        val s = state
        if (s.frameW == 0 || s.frameH == 0) return
        val sx = rect.width() / s.frameW
        val sy = rect.height() / s.frameH
        fun px(v: Float) = rect.left + v * sx
        fun py(v: Float) = rect.top + v * sy

        val color = when (s.status) {
            EngineStatus.TRACKING -> Color.rgb(60, 200, 255)
            EngineStatus.ARRIVED -> Color.rgb(255, 200, 40)
            EngineStatus.LOST -> Color.rgb(255, 90, 90)
            EngineStatus.CALIBRATING -> Color.rgb(170, 120, 255)
            else -> Color.rgb(170, 170, 170)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d

        p.color = if (s.inside) Color.rgb(255, 200, 40) else Color.argb(220, 35, 220, 115)
        c.drawCircle(px(s.destX), py(s.destY), s.destRadius * sx, p)
        c.drawCircle(px(s.destX), py(s.destY), 2.5f * d, p)

        if (s.lanternFound) {
            p.color = Color.argb(200, 255, 255, 255)
            c.drawCircle(px(s.lanternX), py(s.lanternY), 10f * d, p)
        }

        if (s.hasTarget) {
            p.color = Color.argb(150, 60, 200, 255)
            val h = s.history
            for (i in 2 until h.size step 2)
                c.drawLine(px(h[i - 2]), py(h[i - 1]), px(h[i]), py(h[i + 1]), p)
            val pad = s.width * 0.10f
            p.color = color
            p.pathEffect = if (s.measured) null else DashPathEffect(floatArrayOf(10f * d, 8f * d), 0f)
            c.drawRect(px(s.left - pad), py(s.top - pad), px(s.left + s.width + pad), py(s.top + s.height + pad), p)
            p.pathEffect = null
            c.drawCircle(px(s.centerX), py(s.centerY), 3f * d, p)
        }

        p.style = Paint.Style.FILL; p.color = Color.argb(170, 10, 14, 22)
        val x0 = 8f * d; val y0 = 40f * d
        c.drawRoundRect(RectF(x0, y0, x0 + 200f * d, y0 + 78f * d), 8f * d, 8f * d, p)
        p.color = Color.WHITE; p.textSize = 11f * d
        c.drawText("${s.status}  ${"%.0f".format(s.fps)} FPS", x0 + 8 * d, y0 + 16 * d, p)
        c.drawText("X ${s.centerX.toInt()}  Y ${s.centerY.toInt()}  conf ${(s.confidence * 100).toInt()}%", x0 + 8 * d, y0 + 32 * d, p)
        c.drawText("vel ${s.speed.toInt()} px/s  ${if (s.movingTowardLantern) "→ farol" else "← farol"}", x0 + 8 * d, y0 + 48 * d, p)
        c.drawText("destino: ${if (s.inside) "DENTRO" else "fuera"}", x0 + 8 * d, y0 + 64 * d, p)
    }
}
