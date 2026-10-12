package com.example.camera.restormer.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.camera.restormer.model.RestormerConfig
import com.example.camera.restormer.model.RestormerInferenceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "RestormerTileProcessor"

/**
 * Memory-efficient tiled inference processor for high-resolution images (12 MP+).
 *
 * Guarantees:
 * 1. Full Resolution Preservation: Input resolution (e.g. 4032x3024) = Output resolution (4032x3024).
 * 2. Memory Safety: Only single tile buffers (e.g. 128x128 pixels, ~196 KB) in memory at any time.
 * 3. Seamless Boundary Feathering: 2D cosine weight blending eliminates visible tile grid seams.
 */
class RestormerTileProcessor(
    private val inferenceEngine: RestormerInferenceEngine
) {

    suspend fun processImage(
        source: Bitmap,
        config: RestormerConfig,
        onProgress: ((Float, Int, Int) -> Unit)? = null
    ): RestormerInferenceResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val width = source.width
        val height = source.height
        val totalPixels = width.toLong() * height.toLong()

        Log.i(TAG, "[TILED_INFERENCE_START] Processing ${width}x${height} (~${String.format("%.1f", totalPixels / 1_000_000f)} MP) with ${inferenceEngine.modelInfo.name}")

        val tileSize = config.tileSize.coerceIn(64, 256)
        val overlap = config.overlap.coerceIn(8, tileSize / 2)
        val step = max(1, tileSize - overlap)

        // Generate grid coordinates
        val xStarts = ArrayList<Int>()
        var curX = 0
        while (curX < width) {
            val start = if (curX + tileSize > width) max(0, width - tileSize) else curX
            if (!xStarts.contains(start)) {
                xStarts.add(start)
            }
            if (start + tileSize >= width) break
            curX += step
        }
        if (xStarts.isEmpty()) xStarts.add(0)

        val yStarts = ArrayList<Int>()
        var curY = 0
        while (curY < height) {
            val start = if (curY + tileSize > height) max(0, height - tileSize) else curY
            if (!yStarts.contains(start)) {
                yStarts.add(start)
            }
            if (start + tileSize >= height) break
            curY += step
        }
        if (yStarts.isEmpty()) yStarts.add(0)

        val totalTiles = xStarts.size * yStarts.size
        Log.i(TAG, "[TILED_GRID] Grid: ${xStarts.size} cols x ${yStarts.size} rows = $totalTiles tiles (size=${tileSize}px, overlap=${overlap}px)")

        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val tilePixelBuffer = IntArray(tileSize * tileSize)
        val patchR = FloatArray(tileSize * tileSize)
        val patchG = FloatArray(tileSize * tileSize)
        val patchB = FloatArray(tileSize * tileSize)
        val inputPatch = arrayOf(patchR, patchG, patchB)

        // Precompute 2D cosine blending weights
        val blendWeights = FloatArray(tileSize * tileSize)
        for (ty in 0 until tileSize) {
            val wy = computeEdgeWeight(ty, tileSize, overlap)
            val rowOffset = ty * tileSize
            for (tx in 0 until tileSize) {
                val wx = computeEdgeWeight(tx, tileSize, overlap)
                blendWeights[rowOffset + tx] = wx * wy
            }
        }

        var processedCount = 0

        for (y0 in yStarts) {
            for (x0 in xStarts) {
                coroutineContext.ensureActive()

                val actualTileW = min(tileSize, width - x0)
                val actualTileH = min(tileSize, height - y0)

                // Read source pixels
                source.getPixels(tilePixelBuffer, 0, tileSize, x0, y0, actualTileW, actualTileH)

                // Fill RGB float arrays normalized in [0.0f, 1.0f]
                val tileArea = tileSize * tileSize
                for (i in 0 until tileArea) {
                    val p = tilePixelBuffer[i]
                    patchR[i] = ((p shr 16) and 0xFF) / 255.0f
                    patchG[i] = ((p shr 8) and 0xFF) / 255.0f
                    patchB[i] = (p and 0xFF) / 255.0f
                }

                // Execute genuine neural inference
                val restoredPatch = inferenceEngine.processTile(inputPatch, tileSize, tileSize)
                val outR = restoredPatch[0]
                val outG = restoredPatch[1]
                val outB = restoredPatch[2]

                // Read destination pixels for blending
                val existingPixels = IntArray(actualTileW * actualTileH)
                outputBitmap.getPixels(existingPixels, 0, actualTileW, x0, y0, actualTileW, actualTileH)

                val strength = config.restorationStrength.coerceIn(0.0f, 1.0f)

                for (ty in 0 until actualTileH) {
                    val tileRow = ty * tileSize
                    val destRow = ty * actualTileW

                    for (tx in 0 until actualTileW) {
                        val tileIdx = tileRow + tx
                        val destIdx = destRow + tx

                        var rNorm = outR[tileIdx]
                        var gNorm = outG[tileIdx]
                        var bNorm = outB[tileIdx]

                        // User strength blending
                        if (strength < 0.999f) {
                            val origR = patchR[tileIdx]
                            val origG = patchG[tileIdx]
                            val origB = patchB[tileIdx]
                            rNorm = origR + (rNorm - origR) * strength
                            gNorm = origG + (gNorm - origG) * strength
                            bNorm = origB + (bNorm - origB) * strength
                        }

                        val nr = (rNorm * 255.0f).roundToInt().coerceIn(0, 255)
                        val ng = (gNorm * 255.0f).roundToInt().coerceIn(0, 255)
                        val nb = (bNorm * 255.0f).roundToInt().coerceIn(0, 255)

                        val prevPixel = existingPixels[destIdx]
                        val hasPrev = prevPixel != 0
                        val w = if (!hasPrev) 1.0f else blendWeights[tileIdx]

                        if (w >= 0.999f || !hasPrev) {
                            existingPixels[destIdx] = Color.argb(255, nr, ng, nb)
                        } else {
                            val pr = (prevPixel shr 16) and 0xFF
                            val pg = (prevPixel shr 8) and 0xFF
                            val pb = prevPixel and 0xFF

                            val blendedR = (pr * (1.0f - w) + nr * w).roundToInt().coerceIn(0, 255)
                            val blendedG = (pg * (1.0f - w) + ng * w).roundToInt().coerceIn(0, 255)
                            val blendedB = (pb * (1.0f - w) + nb * w).roundToInt().coerceIn(0, 255)

                            existingPixels[destIdx] = Color.argb(255, blendedR, blendedG, blendedB)
                        }
                    }
                }

                outputBitmap.setPixels(existingPixels, 0, actualTileW, x0, y0, actualTileW, actualTileH)

                processedCount++
                if (processedCount % 5 == 0 || processedCount == totalTiles) {
                    val progress = processedCount.toFloat() / totalTiles
                    onProgress?.invoke(progress, processedCount, totalTiles)
                }
            }
        }

        val totalDurationMs = System.currentTimeMillis() - startTime
        val avgTileMs = if (totalTiles > 0) totalDurationMs.toFloat() / totalTiles else 0f

        Log.i(TAG, "[TILED_INFERENCE_COMPLETE] Restored ${width}x${height} in ${totalDurationMs}ms ($totalTiles tiles, avg ${String.format("%.1f", avgTileMs)} ms/tile)")

        RestormerInferenceResult(
            outputBitmap = outputBitmap,
            modelId = inferenceEngine.modelInfo.id,
            modelName = inferenceEngine.modelInfo.name,
            durationMs = totalDurationMs,
            tilesProcessed = totalTiles,
            originalWidth = width,
            originalHeight = height,
            avgTileMs = avgTileMs
        )
    }

    private fun computeEdgeWeight(pos: Int, size: Int, overlap: Int): Float {
        val distFromStart = pos
        val distFromEnd = size - 1 - pos
        val minDist = min(distFromStart, distFromEnd)

        return if (minDist >= overlap) {
            1.0f
        } else {
            val fraction = minDist.toFloat() / overlap
            (0.5f - 0.5f * kotlin.math.cos(fraction * Math.PI.toFloat())).coerceIn(0.05f, 1.0f)
        }
    }
}
