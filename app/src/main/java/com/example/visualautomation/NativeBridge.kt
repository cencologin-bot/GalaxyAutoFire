package com.example.visualautomation

import android.graphics.Bitmap
import java.nio.ByteBuffer

enum class EngineStatus { SEARCHING, TRACKING, LOST, ARRIVED, CALIBRATING }

/** Snapshot of one processed frame. Coordinates are pixels of the captured frame. */
data class EngineState(
    val status: EngineStatus = EngineStatus.SEARCHING,
    val measured: Boolean = false,
    val hasTarget: Boolean = false,
    val left: Float = 0f, val top: Float = 0f, val width: Float = 0f, val height: Float = 0f,
    val confidence: Float = 0f,
    val vx: Float = 0f, val vy: Float = 0f, val speed: Float = 0f,
    val fps: Float = 0f,
    val destX: Float = 0f, val destY: Float = 0f, val destRadius: Float = 0f,
    val inside: Boolean = false,
    val lanternFound: Boolean = false, val lanternX: Float = 0f, val lanternY: Float = 0f,
    val lanternColor: Int = 0,
    val movingTowardLantern: Boolean = false,
    val frameW: Int = 0, val frameH: Int = 0,
    val history: FloatArray = FloatArray(0)
) {
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f

    companion object {
        private const val HEADER = 24
        fun from(a: FloatArray): EngineState {
            val n = a[23].toInt()
            return EngineState(
                status = EngineStatus.values()[a[0].toInt().coerceIn(0, 4)],
                measured = a[1] > 0.5f, hasTarget = a[2] > 0.5f,
                left = a[3], top = a[4], width = a[5], height = a[6],
                confidence = a[7], vx = a[8], vy = a[9], speed = a[10], fps = a[11],
                destX = a[12], destY = a[13], destRadius = a[14], inside = a[15] > 0.5f,
                lanternFound = a[16] > 0.5f, lanternX = a[17], lanternY = a[18],
                lanternColor = a[19].toInt(), movingTowardLantern = a[20] > 0.5f,
                frameW = a[21].toInt(), frameH = a[22].toInt(),
                history = a.copyOfRange(HEADER, HEADER + 2 * n)
            )
        }
    }
}

object NativeBridge {
    init { System.loadLibrary("visual_automation") }

    external fun nativeInit(encodedTemplate: ByteArray): Boolean
    external fun nativeConfigure(
        destX: Float, destY: Float, destRadius: Float,
        searchX: Float, searchY: Float, searchW: Float, searchH: Float,
        lanternX: Float, lanternY: Float, lanternW: Float, lanternH: Float
    )
    external fun nativeSetCalibrating(on: Boolean)
    external fun nativeReset()
    external fun nativeProcess(buffer: ByteBuffer, w: Int, h: Int, rowStride: Int, tsMs: Long): FloatArray?
    external fun nativeProcessBitmap(bitmap: Bitmap, tsMs: Long): FloatArray?
}
