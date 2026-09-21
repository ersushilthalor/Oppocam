package com.example.camera.engine

import android.graphics.Bitmap
import android.util.Log

/**
 * Statistics model representing real scene luminance distribution extracted from live frames.
 */
data class FrameLuminanceStats(
    val p1: Float = 0.02f,
    val p5: Float = 0.06f,
    val p18: Float = 0.18f,
    val p50: Float = 0.38f,
    val p95: Float = 0.85f,
    val p99: Float = 0.95f,
    val meanLuma: Float = 0.35f,
    val dynamicRange: Float = 0.80f,
    val isHighContrast: Boolean = false,
    val isOutdoorSkyWithDarkForeground: Boolean = false
)

/**
 * Lightweight, zero-allocation frame luminance and histogram analyzer.
 * Samples a downscaled preview frame (32x24 = 768 pixels) at 10fps to extract exact
 * luminance percentiles (P1, P5, P18, P50, P95, P99) and scene dynamic contrast.
 */
object FrameLuminanceAnalyzer {
    private const val TAG = "FrameLumaAnalyzer"
    const val SAMPLE_WIDTH = 32
    const val SAMPLE_HEIGHT = 24
    const val TOTAL_PIXELS = SAMPLE_WIDTH * SAMPLE_HEIGHT // 768 pixels

    // Preallocated buffers to ensure zero GC allocations during continuous analysis
    private val pixelBuffer = IntArray(TOTAL_PIXELS)
    private val lumaBuffer = FloatArray(TOTAL_PIXELS)

    @Synchronized
    fun analyzeBitmap(bitmap: Bitmap): FrameLuminanceStats? {
        if (bitmap.isRecycled) return null
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        return try {
            if (w == SAMPLE_WIDTH && h == SAMPLE_HEIGHT) {
                bitmap.getPixels(pixelBuffer, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
            } else {
                val scaled = Bitmap.createScaledBitmap(bitmap, SAMPLE_WIDTH, SAMPLE_HEIGHT, false)
                scaled.getPixels(pixelBuffer, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
                if (scaled != bitmap) {
                    scaled.recycle()
                }
            }

            var sumLuma = 0f
            for (i in 0 until TOTAL_PIXELS) {
                val color = pixelBuffer[i]
                val r = ((color shr 16) and 0xFF) / 255.0f
                val g = ((color shr 8) and 0xFF) / 255.0f
                val b = (color and 0xFF) / 255.0f
                // Rec.709 perceived luminance
                val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
                lumaBuffer[i] = y
                sumLuma += y
            }

            // Sort luminance values to extract percentiles
            lumaBuffer.sort()

            val p1 = lumaBuffer[(TOTAL_PIXELS * 0.01f).toInt().coerceIn(0, TOTAL_PIXELS - 1)]
            val p5 = lumaBuffer[(TOTAL_PIXELS * 0.05f).toInt().coerceIn(0, TOTAL_PIXELS - 1)]
            val p18 = lumaBuffer[(TOTAL_PIXELS * 0.18f).toInt().coerceIn(0, TOTAL_PIXELS - 1)]
            val p50 = lumaBuffer[(TOTAL_PIXELS * 0.50f).toInt().coerceIn(0, TOTAL_PIXELS - 1)]
            val p95 = lumaBuffer[(TOTAL_PIXELS * 0.95f).toInt().coerceIn(0, TOTAL_PIXELS - 1)]
            val p99 = lumaBuffer[(TOTAL_PIXELS * 0.99f).toInt().coerceIn(0, TOTAL_PIXELS - 1)]

            val meanLuma = sumLuma / TOTAL_PIXELS.toFloat()
            val dynamicRange = (p99 - p1).coerceIn(0f, 1f)

            // High contrast scene detection:
            // High highlight pressure (P95/P99) combined with deep shadows (P5/P18)
            val isHighContrast = (p95 > 0.82f && p18 < 0.28f) ||
                    (p99 > 0.90f && p5 < 0.15f) ||
                    (dynamicRange > 0.72f)

            // Bright outdoor sky with dark foreground:
            val isOutdoorSkyWithDarkForeground = (p95 > 0.85f && p50 < 0.36f) ||
                    (p99 > 0.92f && p18 < 0.22f)

            FrameLuminanceStats(
                p1 = p1,
                p5 = p5,
                p18 = p18,
                p50 = p50,
                p95 = p95,
                p99 = p99,
                meanLuma = meanLuma,
                dynamicRange = dynamicRange,
                isHighContrast = isHighContrast,
                isOutdoorSkyWithDarkForeground = isOutdoorSkyWithDarkForeground
            )
        } catch (e: Exception) {
            Log.w(TAG, "Frame luminance analysis failed", e)
            null
        }
    }
}
