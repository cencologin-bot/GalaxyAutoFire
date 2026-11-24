package com.example.visualautomation

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class CalibrationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val image = BitmapFactory.decodeResource(resources, R.drawable.game_preview)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var centerXn = 0.35f
    private var centerYn = 0.776f
    private var radiusN = 0.07f
    private var dragging = false

    /** Last detection run on the static preview (coordinates in bitmap pixels). */
    var detection: EngineState? = null
        set(v) { field = v; invalidate() }

    var onCalibrationChanged: ((Float, Float, Float) -> Unit)? = null

    fun setCalibration(x: Float, y: Float, radius: Float) {
        centerXn = x.coerceIn(0f, 1f)
        centerYn = y.coerceIn(0f, 1f)
        radiusN = radius.coerceIn(0.01f, 0.25f)
        invalidate()
    }

    fun calibration() = Triple(centerXn, centerYn, radiusN)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        val dst = RectF(left, top, left + dw, top + dh)

        paint.color = Color.WHITE
        canvas.drawColor(Color.rgb(25, 31, 42))
        canvas.drawBitmap(image, null, dst, paint)

        val cx = left + centerXn * dw
        val cy = top + centerYn * dh
        val r = radiusN * min(dw, dh)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.rgb(35, 220, 115)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.drawLine(cx - 18, cy, cx + 18, cy, paint)
        canvas.drawLine(cx, cy - 18, cx, cy + 18, paint)

        detection?.takeIf { it.hasTarget && it.frameW > 0 }?.let { d ->
            val k = dw / d.frameW
            paint.style = Paint.Style.STROKE
            paint.color = if (d.status == EngineStatus.ARRIVED) Color.rgb(255, 200, 40) else Color.rgb(60, 200, 255)
            canvas.drawRect(left + d.left * k, top + d.top * k,
                left + (d.left + d.width) * k, top + (d.top + d.height) * k, paint)
            canvas.drawCircle(left + d.centerX * k, top + d.centerY * k, 5f, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(210, 0, 0, 0)
        canvas.drawRoundRect(RectF(16f, 16f, 300f, 64f), 12f, 12f, paint)
        paint.color = Color.WHITE
        paint.textSize = 28f
        canvas.drawText("Destino fijo · arrastrá el punto", 28f, 48f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                centerXn = ((event.x - left) / dw).coerceIn(0f, 1f)
                centerYn = ((event.y - top) / dh).coerceIn(0f, 1f)
                dragging = true
                onCalibrationChanged?.invoke(centerXn, centerYn, radiusN)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
