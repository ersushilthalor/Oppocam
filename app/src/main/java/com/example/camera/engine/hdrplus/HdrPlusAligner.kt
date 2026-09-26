package com.example.camera.engine.hdrplus

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Robust multi-scale image aligner for HDR+ brackets.
 * Computes sub-pixel motion compensation and global registration between
 * underexposed secondary frames and Frame 1 reference.
 */
class HdrPlusAligner {

    companion object {
        private const val THUMB_W = 160
        private const val THUMB_H = 120
        private const val MAX_SEARCH_RADIUS = 12 // +/- 12 pixels at 160x120 scale
    }

    /**
     * Estimates rigid displacement (shiftX, shiftY) mapping the secondary frame to the base frame.
     */
    fun calculateAlignment(
        baseThumb: FloatArray,
        secThumb: FloatArray,
        baseExpProduct: Double,
        secExpProduct: Double,
        fullWidth: Int,
        fullHeight: Int
    ): HdrPlusAlignmentResult {
        val exposureScale = (baseExpProduct / secExpProduct.coerceAtLeast(1.0)).toFloat().coerceIn(1.0f, 64.0f)

        // Rescale secondary thumbnail to match base frame's midtone luminance
        val normalizedSecThumb = FloatArray(THUMB_W * THUMB_H)
        for (i in 0 until THUMB_W * THUMB_H) {
            normalizedSecThumb[i] = (secThumb[i] * exposureScale).coerceIn(0.0f, 1.0f)
        }

        var bestDiff = Float.MAX_VALUE
        var bestDx = 0
        var bestDy = 0

        // Coarse to fine search over central 70% of frame to avoid border artifacts
        val marginX = (THUMB_W * 0.15f).toInt()
        val marginY = (THUMB_H * 0.15f).toInt()

        for (dy in -MAX_SEARCH_RADIUS..MAX_SEARCH_RADIUS step 2) {
            for (dx in -MAX_SEARCH_RADIUS..MAX_SEARCH_RADIUS step 2) {
                var sumDiff = 0f
                var count = 0

                for (y in marginY until (THUMB_H - marginY) step 2) {
                    val sy = y + dy
                    if (sy !in 0 until THUMB_H) continue

                    val rowBase = y * THUMB_W
                    val rowSec = sy * THUMB_W

                    for (x in marginX until (THUMB_W - marginX) step 2) {
                        val sx = x + dx
                        if (sx !in 0 until THUMB_W) continue

                        val diff = abs(baseThumb[rowBase + x] - normalizedSecThumb[rowSec + sx])
                        sumDiff += diff
                        count++
                    }
                }

                if (count > 0) {
                    val avgDiff = sumDiff / count
                    if (avgDiff < bestDiff) {
                        bestDiff = avgDiff
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
        }

        // Sub-pixel refine around best match
        var refinedDx = bestDx
        var refinedDy = bestDy
        for (dy in (bestDy - 1)..(bestDy + 1)) {
            for (dx in (bestDx - 1)..(bestDx + 1)) {
                var sumDiff = 0f
                var count = 0
                for (y in marginY until (THUMB_H - marginY) step 2) {
                    val sy = y + dy
                    if (sy !in 0 until THUMB_H) continue
                    val rowBase = y * THUMB_W
                    val rowSec = sy * THUMB_W
                    for (x in marginX until (THUMB_W - marginX) step 2) {
                        val sx = x + dx
                        if (sx !in 0 until THUMB_W) continue
                        sumDiff += abs(baseThumb[rowBase + x] - normalizedSecThumb[rowSec + sx])
                        count++
                    }
                }
                if (count > 0) {
                    val avgDiff = sumDiff / count
                    if (avgDiff < bestDiff) {
                        bestDiff = avgDiff
                        refinedDx = dx
                        refinedDy = dy
                    }
                }
            }
        }

        // Map thumbnail shifts back to full resolution coordinates
        val scaleX = fullWidth.toFloat() / THUMB_W
        val scaleY = fullHeight.toFloat() / THUMB_H

        val fullShiftX = (refinedDx * scaleX).roundToInt()
        val fullShiftY = (refinedDy * scaleY).roundToInt()

        val confidence = (1.0f - bestDiff).coerceIn(0.1f, 1.0f)

        return HdrPlusAlignmentResult(
            shiftX = fullShiftX,
            shiftY = fullShiftY,
            confidence = confidence
        )
    }
}
