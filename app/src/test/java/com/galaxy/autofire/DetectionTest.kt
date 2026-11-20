package com.galaxy.autofire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DetectionTest {
    private val green = 0x22C55E
    private val red = 0xDC2626
    private val gray = 0x808080
    private val config = DetectionConfig()

    private fun solid(rgb: Int) = LightDetector.analyze(200, 400, config) { _, _ -> rgb }

    @Test fun greenFrameIsGreen() = assertEquals(LightState.GREEN, solid(green).state)

    @Test fun redFrameIsRed() = assertEquals(LightState.RED, solid(red).state)

    @Test fun grayFrameIsUnknown() {
        val r = solid(gray)
        assertEquals(LightState.UNKNOWN, r.state)
        assertEquals(0f, r.greenRatio, 0f)
        assertEquals(0f, r.redRatio, 0f)
    }

    @Test fun colorOutsideRegionIsIgnored() {
        val r = LightDetector.analyze(200, 400, config) { x, _ -> if (x < 20) green else gray }
        assertEquals(LightState.UNKNOWN, r.state)
    }

    @Test fun tinyColoredSpotBelowCoverageIsUnknown() {
        // ~0,25 % de la región en verde: por debajo de la cobertura mínima (2 %).
        val r = LightDetector.analyze(200, 400, config) { x, y -> if (x in 100..104 && y in 195..200) green else gray }
        assertEquals(LightState.UNKNOWN, r.state)
    }

    @Test fun hsvOfPrimaryColors() {
        val hsv = FloatArray(3)
        LightDetector.toHsv(255, 0, 0, hsv); assertEquals(0f, hsv[0], 0.01f)
        LightDetector.toHsv(0, 255, 0, hsv); assertEquals(120f, hsv[0], 0.01f)
        LightDetector.toHsv(0, 0, 255, hsv); assertEquals(240f, hsv[0], 0.01f)
        LightDetector.toHsv(255, 0, 128, hsv); assertTrue(hsv[0] > 300f)
    }

    @Test fun bufferReaderHonoursRowAndPixelStride() {
        val w = 100; val h = 100; val pixelStride = 4; val rowStride = w * pixelStride + 32 // relleno por fila
        val bytes = ByteArray(rowStride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * rowStride + x * pixelStride
            bytes[i] = 0x22; bytes[i + 1] = 0xC5.toByte(); bytes[i + 2] = 0x5E; bytes[i + 3] = 0xFF.toByte() // RGBA verde
        }
        val r = LightDetector.analyze(ByteBuffer.wrap(bytes), w, h, rowStride, pixelStride, config)
        assertEquals(LightState.GREEN, r.state)
    }

    @Test fun stabilizerNeedsConsecutiveFrames() {
        val s = StateStabilizer(confirmFrames = 2)
        assertEquals(LightState.UNKNOWN, s.update(LightState.GREEN)) // 1.º: aún no
        assertEquals(LightState.GREEN, s.update(LightState.GREEN))   // 2.º: confirmado
        assertEquals(LightState.GREEN, s.update(LightState.RED))     // parpadeo aislado
        assertEquals(LightState.GREEN, s.update(LightState.GREEN))   // vuelve: sin cambio
    }

    @Test fun sanitizedKeepsRegionValid() {
        val c = DetectionConfig(left = 0.9f, right = 0.2f, top = -1f, bottom = 5f, minCoverage = 9f).sanitized()
        assertTrue(c.right >= c.left + DetectionConfig.MIN_SPAN)
        assertTrue(c.bottom <= 1f && c.top >= 0f)
        assertEquals(0.5f, c.minCoverage, 0f)
    }

    @Test fun timingsAreClampedAndFixedSize() {
        val t = Timings(listOf(-5, 5000)).sanitized()
        assertEquals(Timings.COUNT, t.values.size)
        assertEquals(0, t.values[0])
        assertEquals(Timings.MAX_MS, t.values[1])
        assertEquals(90, t.values[2])
    }
}
