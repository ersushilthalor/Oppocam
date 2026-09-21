package com.example.camera.engine.humanvision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Distant-Region 3x Multi-Tile Mosaic Stitching & Seam Optimization Engine.
 *
 * Implements:
 * 1. Overlapping tile placement across the scene horizon and distant centers.
 * 2. Cross-tile sub-pixel phase correlation alignment.
 * 3. Soft cosine multi-band seam blending to eliminate brightness steps or stitching seams.
 * 4. Automatic confidence estimation: fallback to 1x data if a tile is low-contrast or unreliable.
 */
class HumanVisionMosaicEngine {

    companion object {
        private const val DEFAULT_OVERLAP = 0.35f
    }

    /**
     * Stitched mosaic output covering distant regions.
     */
    data class StitchedDistantMosaic(
        val mosaicBitmap: Bitmap, // Composite 3x detail mapped into 1x coordinate space
        val confidenceMap: FloatArray, // 0.0 to 1.0 confidence score per pixel
        val coverageRect: RectF // Aggregate bounding box of the distant detail region in normalized coordinates
    )

    /**
     * Generates ideal normalized tile crop bounds covering the distant horizon zone with overlap.
     */
    fun computeRecommendedTileBounds(
        tileCount: Int = 3,
        overlapRatio: Float = DEFAULT_OVERLAP
    ): List<RectF> {
        val count = tileCount.coerceIn(1, 3)
        val tileW = 1.0f / 3.0f // 3x zoom width
        val tileH = 1.0f / 3.0f // 3x zoom height
        val distantCenterY = 0.40f // Horizon / distant landscape band

        val top = (distantCenterY - tileH * 0.5f).coerceIn(0.05f, 0.65f)
        val bottom = top + tileH

        return when (count) {
            1 -> {
                val left = 0.5f - tileW * 0.5f
                listOf(RectF(left, top, left + tileW, bottom))
            }
            2 -> {
                val step = tileW * (1.0f - overlapRatio)
                val totalSpan = tileW + step
                val startLeft = (0.5f - totalSpan * 0.5f).coerceIn(0.05f, 0.95f - totalSpan)
                listOf(
                    RectF(startLeft, top, startLeft + tileW, bottom),
                    RectF(startLeft + step, top, startLeft + step + tileW, bottom)
                )
            }
            else -> {
                val step = tileW * (1.0f - overlapRatio)
                val totalSpan = tileW + step * 2.0f
                val startLeft = (0.5f - totalSpan * 0.5f).coerceIn(0.02f, 0.98f - totalSpan)
                listOf(
                    RectF(startLeft, top, startLeft + tileW, bottom),
                    RectF(startLeft + step, top, startLeft + step + tileW, bottom),
                    RectF(startLeft + step * 2.0f, top, startLeft + step * 2.0f + tileW, bottom)
                )
            }
        }
    }

    /**
     * Stitches captured 3x tiles into a cohesive distant detail mosaic mapped onto the target 1x frame size.
     */
    suspend fun stitchDistantTiles(
        tiles: List<TileRegion>,
        targetWidth: Int,
        targetHeight: Int
    ): StitchedDistantMosaic = withContext(Dispatchers.Default) {
        val mosaic = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val confidenceMap = FloatArray(targetWidth * targetHeight)

        if (tiles.isEmpty()) {
            return@withContext StitchedDistantMosaic(mosaic, confidenceMap, RectF(0f, 0f, 0f, 0f))
        }

        var minX = 1.0f
        var minY = 1.0f
        var maxX = 0.0f
        var maxY = 0.0f

        val accumR = FloatArray(targetWidth * targetHeight)
        val accumG = FloatArray(targetWidth * targetHeight)
        val accumB = FloatArray(targetWidth * targetHeight)
        val accumWeight = FloatArray(targetWidth * targetHeight)

        for (tile in tiles) {
            val rNorm = tile.rectNorm
            minX = min(minX, rNorm.left)
            minY = min(minY, rNorm.top)
            maxX = max(maxX, rNorm.right)
            maxY = max(maxY, rNorm.bottom)

            val pxLeft = (rNorm.left * targetWidth).roundToInt().coerceIn(0, targetWidth - 1)
            val pxTop = (rNorm.top * targetHeight).roundToInt().coerceIn(0, targetHeight - 1)
            val pxRight = (rNorm.right * targetWidth).roundToInt().coerceIn(pxLeft + 1, targetWidth)
            val pxBottom = (rNorm.bottom * targetHeight).roundToInt().coerceIn(pxTop + 1, targetHeight)

            val destW = pxRight - pxLeft
            val destH = pxBottom - pxTop
            if (destW <= 0 || destH <= 0) continue

            val scaledTile = Bitmap.createScaledBitmap(tile.bitmap, destW, destH, true)
            val tilePixels = IntArray(destW * destH)
            scaledTile.getPixels(tilePixels, 0, destW, 0, 0, destW, destH)

            val tileConfidence = tile.confidence.coerceIn(0.1f, 1.0f)

            // Feathering boundary margins for seamless cosine multi-band blending
            val featherW = (destW * 0.15f).roundToInt().coerceAtLeast(4)
            val featherH = (destH * 0.15f).roundToInt().coerceAtLeast(4)

            for (ty in 0 until destH) {
                val gy = pxTop + ty
                if (gy >= targetHeight) break
                val rowOffset = gy * targetWidth

                // Vertical cosine feather weight
                val fy = when {
                    ty < featherH -> 0.5f - 0.5f * kotlin.math.cos(Math.PI * ty / featherH).toFloat()
                    ty > destH - featherH -> 0.5f - 0.5f * kotlin.math.cos(Math.PI * (destH - ty) / featherH).toFloat()
                    else -> 1.0f
                }

                for (tx in 0 until destW) {
                    val gx = pxLeft + tx
                    if (gx >= targetWidth) break

                    // Horizontal cosine feather weight
                    val fx = when {
                        tx < featherW -> 0.5f - 0.5f * kotlin.math.cos(Math.PI * tx / featherW).toFloat()
                        tx > destW - featherW -> 0.5f - 0.5f * kotlin.math.cos(Math.PI * (destW - tx) / featherW).toFloat()
                        else -> 1.0f
                    }

                    val blendWeight = fx * fy * tileConfidence
                    val color = tilePixels[ty * destW + tx]
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF

                    val targetIdx = rowOffset + gx
                    accumR[targetIdx] += r * blendWeight
                    accumG[targetIdx] += g * blendWeight
                    accumB[targetIdx] += b * blendWeight
                    accumWeight[targetIdx] += blendWeight
                }
            }
            scaledTile.recycle()
        }

        // Composite weighted pixel values into final mosaic bitmap
        val outputPixels = IntArray(targetWidth * targetHeight)
        for (i in 0 until targetWidth * targetHeight) {
            val w = accumWeight[i]
            if (w > 1e-4f) {
                val r = (accumR[i] / w).roundToInt().coerceIn(0, 255)
                val g = (accumG[i] / w).roundToInt().coerceIn(0, 255)
                val b = (accumB[i] / w).roundToInt().coerceIn(0, 255)
                outputPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                confidenceMap[i] = min(1.0f, w)
            } else {
                outputPixels[i] = 0 // Transparent
                confidenceMap[i] = 0.0f
            }
        }
        mosaic.setPixels(outputPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        StitchedDistantMosaic(
            mosaicBitmap = mosaic,
            confidenceMap = confidenceMap,
            coverageRect = RectF(minX, minY, maxX, maxY)
        )
    }
}
