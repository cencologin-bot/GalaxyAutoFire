package com.galaxy.autofire

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

enum class LightState { GREEN, RED, UNKNOWN }

/** Región normalizada (0..1) y umbrales del detector. */
data class DetectionConfig(
    val left: Float = 0.35f,
    val top: Float = 0.25f,
    val right: Float = 0.65f,
    val bottom: Float = 0.75f,
    val minSaturation: Float = 0.35f,
    val minValue: Float = 0.35f,
    /** Fracción mínima de la región (sobre todas las muestras) que debe tener el color. */
    val minCoverage: Float = 0.02f,
) {
    fun sanitized(): DetectionConfig {
        val l = left.coerceIn(0f, 1f - MIN_SPAN)
        val t = top.coerceIn(0f, 1f - MIN_SPAN)
        return copy(
            left = l,
            top = t,
            right = right.coerceIn(l + MIN_SPAN, 1f),
            bottom = bottom.coerceIn(t + MIN_SPAN, 1f),
            minSaturation = minSaturation.coerceIn(0f, 1f),
            minValue = minValue.coerceIn(0f, 1f),
            minCoverage = minCoverage.coerceIn(0.01f, 0.5f),
        )
    }

    companion object {
        const val MIN_SPAN = 0.05f
    }
}

data class DetectionResult(
    val state: LightState,
    val greenRatio: Float,
    val redRatio: Float,
    val samples: Int,
)

object LightDetector {
    private const val GRID = 64
    private const val GREEN_MIN = 75f
    private const val GREEN_MAX = 165f
    private const val RED_LOW = 18f
    private const val RED_HIGH = 345f

    /**
     * Lee directamente el buffer RGBA_8888 de ImageReader (orden de bytes R, G, B, A),
     * respetando rowStride y pixelStride. No crea Bitmaps.
     */
    fun analyze(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        config: DetectionConfig,
    ): DetectionResult {
        val limit = buffer.limit()
        return analyze(width, height, config) { x, y ->
            val i = y * rowStride + x * pixelStride
            if (i + 2 >= limit) {
                0
            } else {
                ((buffer.get(i).toInt() and 0xFF) shl 16) or
                    ((buffer.get(i + 1).toInt() and 0xFF) shl 8) or
                    (buffer.get(i + 2).toInt() and 0xFF)
            }
        }
    }

    /** Núcleo puro (sin Android): `rgbAt` devuelve 0xRRGGBB. Testeable en JVM. */
    fun analyze(width: Int, height: Int, config: DetectionConfig, rgbAt: (Int, Int) -> Int): DetectionResult {
        val c = config.sanitized()
        val x0 = (width * c.left).toInt().coerceIn(0, width - 1)
        val y0 = (height * c.top).toInt().coerceIn(0, height - 1)
        val x1 = (width * c.right).toInt().coerceIn(x0 + 1, width)
        val y1 = (height * c.bottom).toInt().coerceIn(y0 + 1, height)
        val stepX = max(1, (x1 - x0) / GRID)
        val stepY = max(1, (y1 - y0) / GRID)

        var green = 0
        var red = 0
        var total = 0
        val hsv = FloatArray(3)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                total++
                val rgb = rgbAt(x, y)
                toHsv((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, hsv)
                if (hsv[1] >= c.minSaturation && hsv[2] >= c.minValue) {
                    val h = hsv[0]
                    if (h in GREEN_MIN..GREEN_MAX) green++ else if (h <= RED_LOW || h >= RED_HIGH) red++
                }
                x += stepX
            }
            y += stepY
        }

        val gr = if (total == 0) 0f else green.toFloat() / total
        val rr = if (total == 0) 0f else red.toFloat() / total
        val state = when {
            gr >= c.minCoverage && gr > rr -> LightState.GREEN
            rr >= c.minCoverage && rr > gr -> LightState.RED
            else -> LightState.UNKNOWN
        }
        return DetectionResult(state, gr, rr, total)
    }

    /** RGB (0..255) → HSV: h en grados [0,360), s y v en [0,1]. */
    internal fun toHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        out[2] = maxC / 255f
        out[1] = if (maxC == 0) 0f else delta.toFloat() / maxC
        out[0] = if (delta == 0) {
            0f
        } else {
            val sector = when (maxC) {
                r -> ((g - b).toFloat() / delta) % 6f
                g -> (b - r).toFloat() / delta + 2f
                else -> (r - g).toFloat() / delta + 4f
            }
            val h = sector * 60f
            if (h < 0f) h + 360f else h
        }
    }
}

/** Evita parpadeo: un estado nuevo debe repetirse `confirmFrames` fotogramas seguidos. */
class StateStabilizer(private val confirmFrames: Int = 2) {
    private var current = LightState.UNKNOWN
    private var candidate = LightState.UNKNOWN
    private var count = 0

    fun update(raw: LightState): LightState {
        if (raw == current) {
            candidate = raw
            count = 0
            return current
        }
        if (raw == candidate) count++ else {
            candidate = raw
            count = 1
        }
        if (count >= confirmFrames) {
            current = candidate
            count = 0
        }
        return current
    }

    fun reset() {
        current = LightState.UNKNOWN
        candidate = LightState.UNKNOWN
        count = 0
    }
}
