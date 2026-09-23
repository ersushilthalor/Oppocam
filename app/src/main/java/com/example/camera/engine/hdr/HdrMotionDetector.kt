package com.example.camera.engine.hdr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Flagship Ghost & Motion Detection Engine.
 *
 * Responsibilities:
 * 1. Computes exposure-normalized pixel residuals between aligned secondary frames and reference base frame.
 * 2. Uses the ACTUAL frame dimensions (fullW, fullH) to accurately scale alignment coordinates onto the motion grid.
 * 3. Uses edge-adaptive thresholding: avoids false motion triggers on high-contrast edges while detecting
 *    moving subjects (people, vehicles, tree branches, camera shake).
 * 4. Applies spatial feathering and dilation: moving areas smoothly fall back to the reference base frame,
 *    preventing ghosting, seams, and corrupted double-edges completely.
 */
class HdrMotionDetector {

    companion object {
        const val MASK_GRID_WIDTH = 256
        const val MASK_GRID_HEIGHT = 192
        private const val BASE_MOTION_THRESHOLD = 0.055f // Sensitivity in flat/moderate regions
        private const val GRADIENT_ADAPTATION_COEFF = 0.22f // Relaxes threshold at steep edges
    }

    /**
     * Detects motion between the base frame and aligned target frame.
     * Accurately scales alignment displacements using the actual input resolution fullW x fullH.
     */
    fun detectMotion(
        refLuma: FloatArray,
        targetLuma: FloatArray,
        targetEvOffset: Float,
        alignment: HdrAlignmentResult,
        fullW: Int = 4000,
        fullH: Int = 3000,
        gridW: Int = MASK_GRID_WIDTH,
        gridH: Int = MASK_GRID_HEIGHT,
        exposureScaleRatio: Float? = null
    ): HdrMotionMask {
        val mask = FloatArray(gridW * gridH)
        val rawDiff = FloatArray(gridW * gridH)

        // Exposure compensation: normalize target frame to base scale
        val exposureScale = exposureScaleRatio ?: 2.0f.pow(-targetEvOffset)

        // Correct X/Y alignment scaling using actual input resolution (fullW, fullH)
        val safeW = fullW.coerceAtLeast(gridW).toFloat()
        val safeH = fullH.coerceAtLeast(gridH).toFloat()

        // 1. Compute pixel-wise difference on aligned grid
        for (y in 1 until (gridH - 1)) {
            val normY = y.toFloat() / (gridH - 1).toFloat()
            val refRow = y * gridW

            for (x in 1 until (gridW - 1)) {
                val normX = x.toFloat() / (gridW - 1).toFloat()

                // Total displacement in full-res pixels -> converted to motion grid units
                val (dispX, dispY) = alignment.getTotalDisplacement(normX, normY)
                val gridDispX = dispX * (gridW.toFloat() / safeW)
                val gridDispY = dispY * (gridH.toFloat() / safeH)

                val tx = (x - gridDispX).toInt()
                val ty = (y - gridDispY).toInt()

                // If warped coordinate lands outside frame boundary, treat as boundary/moving (fall back to base)
                if (tx !in 0 until gridW || ty !in 0 until gridH) {
                    rawDiff[refRow + x] = 1.0f
                    continue
                }

                val refVal = refLuma[refRow + x]
                val tgtVal = targetLuma[ty * gridW + tx] * exposureScale

                // Gradient magnitude on reference frame (Sobel-like difference)
                val gradX = abs(refLuma[refRow + x + 1] - refLuma[refRow + x - 1]) * 0.5f
                val gradY = abs(refLuma[(y + 1) * gridW + x] - refLuma[(y - 1) * gridW + x]) * 0.5f
                val gradMag = gradX + gradY

                // Adaptive threshold: relaxed on contrast edges, strict in flat areas
                val threshold = BASE_MOTION_THRESHOLD + GRADIENT_ADAPTATION_COEFF * gradMag

                val diff = abs(refVal - tgtVal)
                val motionScore = if (diff > threshold) {
                    ((diff - threshold) / (threshold * 1.4f)).coerceIn(0f, 1f)
                } else {
                    0f
                }

                rawDiff[refRow + x] = motionScore
            }
        }

        // 2. Spatial Feathering & Dilation (ensures ghost suppression completely covers edges of moving subjects)
        for (y in 1 until (gridH - 1)) {
            val row = y * gridW
            for (x in 1 until (gridW - 1)) {
                var sum = 0f
                var maxVal = 0f

                for (dy in -1..1) {
                    val nRow = (y + dy) * gridW
                    for (dx in -1..1) {
                        val v = rawDiff[nRow + x + dx]
                        sum += v
                        if (v > maxVal) maxVal = v
                    }
                }

                val avg = sum / 9.0f
                // Conservative blend favoring base frame fallback whenever movement is detected
                mask[row + x] = (0.7f * maxVal + 0.3f * avg).coerceIn(0f, 1f)
            }
        }

        return HdrMotionMask(gridW, gridH, mask)
    }
}
