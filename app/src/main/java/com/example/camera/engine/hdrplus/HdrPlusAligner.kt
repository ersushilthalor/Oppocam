package com.example.camera.engine.hdrplus

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Robust multi-scale image aligner for HDR+ brackets.
 * Computes sub-pixel motion compensation, parabolic refinement, and tile-based
 * local registration between underexposed secondary frames and Frame 1 reference.
 */
class HdrPlusAligner {

    companion object {
        private const val DEFAULT_THUMB_W = 160
        private const val DEFAULT_THUMB_H = 120
        private const val MAX_SEARCH_RADIUS = 12 // +/- 12 pixels at 160x120 scale
        private const val GRID_COLS = 4
        private const val GRID_ROWS = 3
    }

    /**
     * Estimates global and tile-level sub-pixel displacement mapping the secondary frame to the base frame.
     */
    fun calculateAlignment(
        baseThumb: FloatArray,
        secThumb: FloatArray,
        baseExpProduct: Double,
        secExpProduct: Double,
        fullWidth: Int,
        fullHeight: Int
    ): HdrPlusAlignmentResult {
        val totalThumb = min(baseThumb.size, secThumb.size)
        if (totalThumb < 64) {
            return HdrPlusAlignmentResult(0, 0, 1.0f, 0f, 0f)
        }

        val (thumbW, thumbH) = inferThumbDimensions(totalThumb, fullWidth, fullHeight)
        val exposureScale = (baseExpProduct / secExpProduct.coerceAtLeast(1.0)).toFloat().coerceIn(0.25f, 64.0f)

        // Rescale secondary thumbnail to match base frame's linear radiance
        val normalizedSecThumb = FloatArray(thumbW * thumbH)
        for (i in 0 until (thumbW * thumbH)) {
            normalizedSecThumb[i] = (secThumb[i] * exposureScale).coerceIn(0.0f, 1.5f)
        }

        val marginX = (thumbW * 0.12f).toInt().coerceAtLeast(2)
        val marginY = (thumbH * 0.12f).toInt().coerceAtLeast(2)
        val searchRadius = ((MAX_SEARCH_RADIUS * thumbW) / DEFAULT_THUMB_W).coerceIn(6, 24)

        fun evaluateShiftCost(
            dx: Int,
            dy: Int,
            xStart: Int = marginX,
            xEnd: Int = thumbW - marginX,
            yStart: Int = marginY,
            yEnd: Int = thumbH - marginY,
            step: Int = 2
        ): Float {
            var sumCost = 0f
            var validCount = 0
            var fallbackDiff = 0f
            var fallbackCount = 0

            for (y in yStart until yEnd step step) {
                val sy = y + dy
                if (sy !in 1 until (thumbH - 1)) continue
                val rowBase = y * thumbW
                val rowSec = sy * thumbW

                for (x in xStart until xEnd step step) {
                    val sx = x + dx
                    if (sx !in 1 until (thumbW - 1)) continue

                    val bVal = baseThumb[rowBase + x]
                    val sVal = normalizedSecThumb[rowSec + sx]
                    val absDiff = abs(bVal - min(sVal, 1.0f))
                    fallbackDiff += absDiff
                    fallbackCount++

                    // Exclude clipped base pixels where secondary has unclipped brighter detail
                    if (bVal > 0.90f && sVal >= bVal * 0.85f) continue

                    val bGradX = baseThumb[rowBase + x + 1] - baseThumb[rowBase + x - 1]
                    val bGradY = baseThumb[rowBase + thumbW + x] - baseThumb[rowBase - thumbW + x]
                    val sGradX = min(normalizedSecThumb[rowSec + sx + 1], 1.0f) - min(normalizedSecThumb[rowSec + sx - 1], 1.0f)
                    val sGradY = min(normalizedSecThumb[rowSec + thumbW + sx], 1.0f) - min(normalizedSecThumb[rowSec - thumbW + sx], 1.0f)

                    val gradDiff = 0.5f * (abs(bGradX - sGradX) + abs(bGradY - sGradY))
                    sumCost += 0.65f * absDiff + 0.35f * gradDiff
                    validCount++
                }
            }

            return if (validCount >= 16) {
                sumCost / validCount
            } else if (fallbackCount > 0) {
                fallbackDiff / fallbackCount
            } else {
                1.0f
            }
        }

        var bestCost = Float.MAX_VALUE
        var bestDx = 0
        var bestDy = 0

        // 1. Coarse search (step 2)
        for (dy in -searchRadius..searchRadius step 2) {
            for (dx in -searchRadius..searchRadius step 2) {
                // Slight zero-shift prior to avoid drift on flat/uniform regions
                val prior = 0.0004f * (abs(dx) + abs(dy))
                val cost = evaluateShiftCost(dx, dy, step = 2) + prior
                if (cost < bestCost) {
                    bestCost = cost
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        // 2. Fine integer search (step 1 around coarse winner)
        var refinedDx = bestDx
        var refinedDy = bestDy
        bestCost = Float.MAX_VALUE

        for (dy in (bestDy - 2)..(bestDy + 2)) {
            for (dx in (bestDx - 2)..(bestDx + 2)) {
                if (dx !in -searchRadius..searchRadius || dy !in -searchRadius..searchRadius) continue
                val prior = 0.0003f * (abs(dx) + abs(dy))
                val cost = evaluateShiftCost(dx, dy, step = 1) + prior
                if (cost < bestCost) {
                    bestCost = cost
                    refinedDx = dx
                    refinedDy = dy
                }
            }
        }

        // 3. Parabolic 2D sub-pixel refinement on thumbnail
        val costLeft = evaluateShiftCost(refinedDx - 1, refinedDy, step = 1)
        val costRight = evaluateShiftCost(refinedDx + 1, refinedDy, step = 1)
        val costUp = evaluateShiftCost(refinedDx, refinedDy - 1, step = 1)
        val costDown = evaluateShiftCost(refinedDx, refinedDy + 1, step = 1)
        val costCenter = evaluateShiftCost(refinedDx, refinedDy, step = 1)

        val denomX = 2.0f * (costLeft - 2.0f * costCenter + costRight)
        val subOffsetX = if (abs(denomX) > 1e-5f && costCenter <= costLeft && costCenter <= costRight) {
            ((costLeft - costRight) / denomX).coerceIn(-0.5f, 0.5f)
        } else 0.0f

        val denomY = 2.0f * (costUp - 2.0f * costCenter + costDown)
        val subOffsetY = if (abs(denomY) > 1e-5f && costCenter <= costUp && costCenter <= costDown) {
            ((costUp - costDown) / denomY).coerceIn(-0.5f, 0.5f)
        } else 0.0f

        val scaleX = fullWidth.toFloat() / thumbW
        val scaleY = fullHeight.toFloat() / thumbH

        val globalSubpixelX = (refinedDx + subOffsetX) * scaleX
        val globalSubpixelY = (refinedDy + subOffsetY) * scaleY

        // 4. Compute local 4x3 tile sub-pixel shifts anchored around global shift
        val tileShiftsX = FloatArray(GRID_COLS * GRID_ROWS)
        val tileShiftsY = FloatArray(GRID_COLS * GRID_ROWS)
        val tileW = thumbW / GRID_COLS
        val tileH = thumbH / GRID_ROWS

        for (ty in 0 until GRID_ROWS) {
            for (tx in 0 until GRID_COLS) {
                val x0 = (tx * tileW).coerceAtLeast(2)
                val x1 = ((tx + 1) * tileW).coerceAtMost(thumbW - 2)
                val y0 = (ty * tileH).coerceAtLeast(2)
                val y1 = ((ty + 1) * tileH).coerceAtMost(thumbH - 2)

                var bestTileCost = Float.MAX_VALUE
                var tDx = refinedDx
                var tDy = refinedDy

                for (dy in (refinedDy - 1)..(refinedDy + 1)) {
                    for (dx in (refinedDx - 1)..(refinedDx + 1)) {
                        val reg = 0.002f * (abs(dx - refinedDx) + abs(dy - refinedDy))
                        val c = evaluateShiftCost(dx, dy, x0, x1, y0, y1, step = 1) + reg
                        if (c < bestTileCost) {
                            bestTileCost = c
                            tDx = dx
                            tDy = dy
                        }
                    }
                }

                val cL = evaluateShiftCost(tDx - 1, tDy, x0, x1, y0, y1, step = 1)
                val cR = evaluateShiftCost(tDx + 1, tDy, x0, x1, y0, y1, step = 1)
                val cU = evaluateShiftCost(tDx, tDy - 1, x0, x1, y0, y1, step = 1)
                val cD = evaluateShiftCost(tDx, tDy + 1, x0, x1, y0, y1, step = 1)
                val c0 = evaluateShiftCost(tDx, tDy, x0, x1, y0, y1, step = 1)

                val dX = 2.0f * (cL - 2.0f * c0 + cR)
                val sX = if (abs(dX) > 1e-5f && c0 <= cL && c0 <= cR) ((cL - cR) / dX).coerceIn(-0.5f, 0.5f) else 0f
                val dY = 2.0f * (cU - 2.0f * c0 + cD)
                val sY = if (abs(dY) > 1e-5f && c0 <= cU && c0 <= cD) ((cU - cD) / dY).coerceIn(-0.5f, 0.5f) else 0f

                val tileIdx = ty * GRID_COLS + tx
                // Blend local tile estimate with global estimate for stability
                tileShiftsX[tileIdx] = 0.65f * ((tDx + sX) * scaleX) + 0.35f * globalSubpixelX
                tileShiftsY[tileIdx] = 0.65f * ((tDy + sY) * scaleY) + 0.35f * globalSubpixelY
            }
        }

        val confidence = (1.0f - costCenter.coerceIn(0.0f, 0.9f)).coerceIn(0.1f, 1.0f)

        return HdrPlusAlignmentResult(
            shiftX = globalSubpixelX.roundToInt(),
            shiftY = globalSubpixelY.roundToInt(),
            confidence = confidence,
            subpixelShiftX = globalSubpixelX,
            subpixelShiftY = globalSubpixelY,
            tileShiftsX = tileShiftsX,
            tileShiftsY = tileShiftsY,
            gridCols = GRID_COLS,
            gridRows = GRID_ROWS
        )
    }

    /**
     * Refines a coarse/thumbnail alignment directly on the full-resolution linear RGB images
     * to achieve sub-pixel registration accuracy even when full resolution is much larger than the thumbnail.
     */
    fun refineAlignmentFullRes(
        baseImage: HdrPlusDevelopedImage,
        secImage: HdrPlusDevelopedImage,
        coarse: HdrPlusAlignmentResult
    ): HdrPlusAlignmentResult {
        val w = baseImage.width
        val h = baseImage.height
        if (w < 32 || h < 32) return coarse

        val expScale = (baseImage.exposureProduct / secImage.exposureProduct.coerceAtLeast(1.0)).toFloat().coerceIn(0.25f, 64.0f)
        val baseRgb = baseImage.rgbLinear
        val secRgb = secImage.rgbLinear

        // Search +/- 3 full-res pixels around coarse shift across central region
        val initDx = coarse.subpixelShiftX.roundToInt()
        val initDy = coarse.subpixelShiftY.roundToInt()
        val marginX = (w * 0.2f).toInt().coerceAtLeast(4)
        val marginY = (h * 0.2f).toInt().coerceAtLeast(4)
        val stepX = max(1, (w - 2 * marginX) / 48)
        val stepY = max(1, (h - 2 * marginY) / 36)

        fun evalFullResCost(dx: Int, dy: Int): Float {
            var sum = 0f
            var count = 0
            for (y in marginY until (h - marginY) step stepY) {
                val sy = y + dy
                if (sy !in 0 until h) continue
                val bRow = y * w
                val sRow = sy * w
                for (x in marginX until (w - marginX) step stepX) {
                    val sx = x + dx
                    if (sx !in 0 until w) continue
                    val bIdx = (bRow + x) * 3
                    val bG = baseRgb[bIdx + 1]
                    if (bG > 0.88f || bG < 0.01f) continue
                    val sIdx = (sRow + sx) * 3
                    val sG = (secRgb[sIdx + 1] * expScale).coerceAtMost(1.2f)
                    sum += abs(bG - sG)
                    count++
                }
            }
            return if (count >= 16) sum / count else Float.MAX_VALUE
        }

        var bestDx = initDx
        var bestDy = initDy
        var bestCost = evalFullResCost(initDx, initDy)
        if (bestCost == Float.MAX_VALUE) return coarse

        for (dy in (initDy - 3)..(initDy + 3)) {
            for (dx in (initDx - 3)..(initDx + 3)) {
                val c = evalFullResCost(dx, dy) + 0.0002f * (abs(dx - initDx) + abs(dy - initDy))
                if (c < bestCost) {
                    bestCost = c
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        val cL = evalFullResCost(bestDx - 1, bestDy)
        val cR = evalFullResCost(bestDx + 1, bestDy)
        val cU = evalFullResCost(bestDx, bestDy - 1)
        val cD = evalFullResCost(bestDx, bestDy + 1)
        val c0 = evalFullResCost(bestDx, bestDy)

        val dX = 2.0f * (cL - 2.0f * c0 + cR)
        val subX = if (cL < Float.MAX_VALUE && cR < Float.MAX_VALUE && abs(dX) > 1e-5f && c0 <= cL && c0 <= cR) {
            ((cL - cR) / dX).coerceIn(-0.5f, 0.5f)
        } else 0f

        val dY = 2.0f * (cU - 2.0f * c0 + cD)
        val subY = if (cU < Float.MAX_VALUE && cD < Float.MAX_VALUE && abs(dY) > 1e-5f && c0 <= cU && c0 <= cD) {
            ((cU - cD) / dY).coerceIn(-0.5f, 0.5f)
        } else 0f

        val refinedSubX = bestDx + subX
        val refinedSubY = bestDy + subY
        val deltaX = refinedSubX - coarse.subpixelShiftX
        val deltaY = refinedSubY - coarse.subpixelShiftY

        val updatedTilesX = coarse.tileShiftsX?.let { FloatArray(it.size) { i -> it[i] + deltaX } }
        val updatedTilesY = coarse.tileShiftsY?.let { FloatArray(it.size) { i -> it[i] + deltaY } }

        return coarse.copy(
            shiftX = refinedSubX.roundToInt(),
            shiftY = refinedSubY.roundToInt(),
            subpixelShiftX = refinedSubX,
            subpixelShiftY = refinedSubY,
            tileShiftsX = updatedTilesX,
            tileShiftsY = updatedTilesY
        )
    }

    private fun inferThumbDimensions(totalSize: Int, fullW: Int, fullH: Int): Pair<Int, Int> {
        if (totalSize == DEFAULT_THUMB_W * DEFAULT_THUMB_H) {
            return Pair(DEFAULT_THUMB_W, DEFAULT_THUMB_H)
        }
        val aspect = if (fullH > 0) fullW.toFloat() / fullH.toFloat() else 4f / 3f
        val estW = sqrt(totalSize.toFloat() * aspect).roundToInt().coerceAtLeast(8)
        val estH = (totalSize / estW).coerceAtLeast(8)
        return if (estW * estH == totalSize) {
            Pair(estW, estH)
        } else {
            Pair(totalSize, 1)
        }
    }
}
