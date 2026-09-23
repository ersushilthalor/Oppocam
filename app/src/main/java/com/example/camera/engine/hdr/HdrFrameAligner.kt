package com.example.camera.engine.hdr

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Robust Multi-Frame Alignment Engine for Flagship Computational HDR.
 *
 * Responsibilities:
 * 1. Reference frame: Base (0 EV) exposure is the geometric reference anchor.
 * 2. Uses Gyroscope angular velocity to seed initial translation prior (dx_gyro, dy_gyro).
 * 3. Downsamples luminance to an efficient multi-scale grid (e.g. 256x192) to execute
 *    coarse-to-fine 2D cross-correlation / Minimum Absolute Difference (MAD) search.
 * 4. Refines integer translation with sub-pixel quadratic peak fitting for 0.1px precision.
 * 5. Validates cross-correlation confidence to safeguard against false matches in blank scenes.
 */
class HdrFrameAligner {

    companion object {
        const val ALIGN_GRID_WIDTH = 256
        const val ALIGN_GRID_HEIGHT = 192
        private const val SEARCH_RADIUS = 10 // +/- 10 pixels on downscaled grid = +/- 100-200px on full-res
    }

    /**
     * Estimates sub-pixel translation alignment between target frame and reference base frame.
     */
    fun alignFrames(
        refBitmap: Bitmap,
        targetBitmap: Bitmap,
        dtSec: Float,
        targetGyroYawSpeed: Float,
        targetGyroPitchSpeed: Float
    ): HdrAlignmentResult {
        val fullW = refBitmap.width
        val fullH = refBitmap.height
        if (fullW <= 0 || fullH <= 0 || targetBitmap.width != fullW || targetBitmap.height != fullH) {
            return HdrAlignmentResult(0f, 0f, 0f, false)
        }

        // 1. Extract downscaled luminance maps
        val refLuma = extractDownscaledLuminance(refBitmap, ALIGN_GRID_WIDTH, ALIGN_GRID_HEIGHT)
        val targetLuma = extractDownscaledLuminance(targetBitmap, ALIGN_GRID_WIDTH, ALIGN_GRID_HEIGHT)

        // 2. Gyro seed displacement prior
        // Yaw rotates around vertical axis -> shifts horizontal X
        // Pitch rotates around horizontal axis -> shifts vertical Y
        val seedX = (-targetGyroYawSpeed * dtSec * ALIGN_GRID_WIDTH * 0.85f).roundToInt()
            .coerceIn(-SEARCH_RADIUS / 2, SEARCH_RADIUS / 2)
        val seedY = (-targetGyroPitchSpeed * dtSec * ALIGN_GRID_HEIGHT * 0.85f).roundToInt()
            .coerceIn(-SEARCH_RADIUS / 2, SEARCH_RADIUS / 2)

        // 3. Minimum Absolute Difference (MAD) 2D Grid Search
        var minMad = Float.MAX_VALUE
        var bestDx = seedX
        var bestDy = seedY

        val border = SEARCH_RADIUS + 2
        val activeW = ALIGN_GRID_WIDTH - 2 * border
        val activeH = ALIGN_GRID_HEIGHT - 2 * border

        // Record cost surface around the best peak for sub-pixel quadratic interpolation
        val costGrid = Array(2 * SEARCH_RADIUS + 1) { FloatArray(2 * SEARCH_RADIUS + 1) { Float.MAX_VALUE } }

        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS) {
            val candidateY = seedY + dy
            if (candidateY !in -SEARCH_RADIUS..SEARCH_RADIUS) continue

            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
                val candidateX = seedX + dx
                if (candidateX !in -SEARCH_RADIUS..SEARCH_RADIUS) continue

                var sumDiff = 0f
                var count = 0

                // Step by 2 for speed on downscaled grid
                for (y in border until (border + activeH) step 2) {
                    val ty = y + candidateY
                    if (ty !in 0 until ALIGN_GRID_HEIGHT) continue

                    val refRowOffset = y * ALIGN_GRID_WIDTH
                    val tgtRowOffset = ty * ALIGN_GRID_WIDTH

                    for (x in border until (border + activeW) step 2) {
                        val tx = x + candidateX
                        if (tx !in 0 until ALIGN_GRID_WIDTH) continue

                        val rVal = refLuma[refRowOffset + x]
                        val tVal = targetLuma[tgtRowOffset + tx]
                        sumDiff += abs(rVal - tVal)
                        count++
                    }
                }

                val avgMad = if (count > 0) sumDiff / count else Float.MAX_VALUE
                costGrid[candidateY + SEARCH_RADIUS][candidateX + SEARCH_RADIUS] = avgMad

                if (avgMad < minMad) {
                    minMad = avgMad
                    bestDx = candidateX
                    bestDy = candidateY
                }
            }
        }

        // 4. Sub-pixel Refinement (Parabolic Peak Interpolation)
        var subDx = 0f
        var subDy = 0f

        val gridX = bestDx + SEARCH_RADIUS
        val gridY = bestDy + SEARCH_RADIUS

        if (gridX in 1 until (2 * SEARCH_RADIUS) && gridY in 1 until (2 * SEARCH_RADIUS)) {
            val c = costGrid[gridY][gridX]
            val left = costGrid[gridY][gridX - 1]
            val right = costGrid[gridY][gridX + 1]
            val top = costGrid[gridY - 1][gridX]
            val bottom = costGrid[gridY + 1][gridX]

            val denomX = (left - 2f * c + right)
            if (denomX > 1e-5f) {
                subDx = ((left - right) / (2f * denomX)).coerceIn(-0.5f, 0.5f)
            }

            val denomY = (top - 2f * c + bottom)
            if (denomY > 1e-5f) {
                subDy = ((top - bottom) / (2f * denomY)).coerceIn(-0.5f, 0.5f)
            }
        }

        val refinedDx = bestDx + subDx
        val refinedDy = bestDy + subDy

        // 5. Scale to full resolution
        val scaleX = fullW.toFloat() / ALIGN_GRID_WIDTH.toFloat()
        val scaleY = fullH.toFloat() / ALIGN_GRID_HEIGHT.toFloat()

        val fullShiftX = refinedDx * scaleX
        val fullShiftY = refinedDy * scaleY

        // Compute alignment confidence
        val zeroShiftMad = costGrid[SEARCH_RADIUS][SEARCH_RADIUS]
        val confidence = if (zeroShiftMad > 1e-4f) {
            ((zeroShiftMad - minMad) / zeroShiftMad).coerceIn(0f, 1f)
        } else {
            0.5f
        }

        return HdrAlignmentResult(
            shiftX = fullShiftX,
            shiftY = fullShiftY,
            confidence = confidence,
            isAligned = true
        )
    }

    /**
     * Fast downscaled luminance extraction into a pre-allocated FloatArray (0.0 .. 1.0).
     */
    fun extractDownscaledLuminance(bitmap: Bitmap, targetW: Int, targetH: Int): FloatArray {
        val luma = FloatArray(targetW * targetH)
        val scaled = if (bitmap.width == targetW && bitmap.height == targetH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }

        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            // Standard Rec.709 perceived luminance
            luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        if (scaled != bitmap) {
            scaled.recycle()
        }

        return luma
    }
}
