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
 * 2. Uses edge-adaptive thresholding: prevents false motion triggers on high-contrast edges,
 *    while maintaining maximum sensitivity to moving objects (people, vehicles, waving leaves).
 * 3. Builds a smooth continuous Motion Confidence Mask [0.0 = static, 1.0 = dynamic object].
 * 4. Applies spatial feathering to eliminate seam boundaries and double-edge halos.
 */
class HdrMotionDetector {

    companion object {
        const val MASK_GRID_WIDTH = 256
        const val MASK_GRID_HEIGHT = 192
        private const val BASE_MOTION_THRESHOLD = 0.06f // Sensitivity in flat regions
        private const val GRADIENT_ADAPTATION_COEFF = 0.20f // Relaxes threshold at steep edges
    }

    /**
     * Detects motion between the base frame and aligned target frame, producing a smoothed motion mask.
     */
    fun detectMotion(
        refLuma: FloatArray,
        targetLuma: FloatArray,
        targetEvOffset: Float,
        alignment: HdrAlignmentResult,
        gridW: Int = MASK_GRID_WIDTH,
        gridH: Int = MASK_GRID_HEIGHT
    ): HdrMotionMask {
        val mask = FloatArray(gridW * gridH)
        val rawDiff = FloatArray(gridW * gridH)

        // Exposure ratio compensation: target frame is multiplied by 2^(-evOffset) to bring to base scale
        val exposureScale = 2.0f.pow(-targetEvOffset)

        val shiftX = alignment.shiftX * (gridW.toFloat() / 4000f) // Normalized to grid
        val shiftY = alignment.shiftY * (gridH.toFloat() / 3000f)

        // 1. Compute pixel-wise difference on aligned grid
        for (y in 1 until (gridH - 1)) {
            val ty = (y + shiftY.toInt()).coerceIn(0, gridH - 1)
            val refRow = y * gridW
            val tgtRow = ty * gridW

            for (x in 1 until (gridW - 1)) {
                val tx = (x + shiftX.toInt()).coerceIn(0, gridW - 1)

                val refVal = refLuma[refRow + x]
                val tgtVal = targetLuma[tgtRow + tx] * exposureScale

                // Gradient magnitude on reference frame (Sobel-like difference)
                val gradX = abs(refLuma[refRow + x + 1] - refLuma[refRow + x - 1]) * 0.5f
                val gradY = abs(refLuma[(y + 1) * gridW + x] - refLuma[(y - 1) * gridW + x]) * 0.5f
                val gradMag = gradX + gradY

                // Adaptive threshold: slightly higher in high-contrast textures, lower in smooth regions
                val threshold = BASE_MOTION_THRESHOLD + GRADIENT_ADAPTATION_COEFF * gradMag

                val diff = abs(refVal - tgtVal)
                val motionScore = if (diff > threshold) {
                    ((diff - threshold) / (threshold * 1.5f)).coerceIn(0f, 1f)
                } else {
                    0f
                }

                rawDiff[refRow + x] = motionScore
            }
        }

        // 2. Spatial 3x3 Feathering & Dilation (prevents ghost seams and edge halos around moving objects)
        for (y in 1 until (gridH - 1)) {
            val row = y * gridW
            for (x in 1 until (gridW - 1)) {
                // 3x3 weighted box filter with central dilation
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
                // Blend maximum with average for conservative ghost suppression (prefers single frame on movement)
                mask[row + x] = (0.6f * maxVal + 0.4f * avg).coerceIn(0f, 1f)
            }
        }

        return HdrMotionMask(gridW, gridH, mask)
    }
}
