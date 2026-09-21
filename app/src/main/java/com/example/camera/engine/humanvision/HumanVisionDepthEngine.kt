package com.example.camera.engine.humanvision

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Computational Depth Estimation & 4-Zone Scene Classifier for Human Vision.
 *
 * Implements:
 * 1. Multi-baseline stereo disparity between 0.5x and 1x optical frames.
 * 2. High-frequency Laplacian focus & edge gradient cues.
 * 3. Perspective ground-plane and sky priors.
 * 4. Edge-preserving spatial smoothing for razor-sharp depth boundaries.
 * 5. Soft continuous blending weight maps for NEAR, MID, FAR, and VERY_FAR zones.
 */
class HumanVisionDepthEngine {

    companion object {
        private const val DEPTH_GRID_W = 160
        private const val DEPTH_GRID_H = 120
    }

    /**
     * Estimated depth field and soft zone weight maps.
     */
    data class DepthField(
        val width: Int,
        val height: Int,
        val depthMap: FloatArray, // 0.0 (Near) .. 1.0 (Very Far)
        val zoneIndices: ByteArray, // 0=NEAR, 1=MID, 2=FAR, 3=VERY_FAR
        val nearWeights: FloatArray,
        val midWeights: FloatArray,
        val farWeights: FloatArray,
        val veryFarWeights: FloatArray
    ) {
        fun getDepthAt(normX: Float, normY: Float): Float {
            val x = (normX * (width - 1)).roundToInt().coerceIn(0, width - 1)
            val y = (normY * (height - 1)).roundToInt().coerceIn(0, height - 1)
            return depthMap[y * width + x]
        }

        fun getZoneAt(normX: Float, normY: Float): SceneDepthZone {
            val d = getDepthAt(normX, normY)
            return SceneDepthZone.fromDepth(d)
        }
    }

    /**
     * Estimates dense continuous depth and produces soft zone blending masks.
     */
    suspend fun estimateSceneDepth(
        ultraWideRef: Bitmap?,
        mainFrame: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): DepthField = withContext(Dispatchers.Default) {
        val gridW = DEPTH_GRID_W
        val gridH = DEPTH_GRID_H
        val total = gridW * gridH

        // 1. Create low-resolution working proxies for rapid depth computation
        val mainProxy = Bitmap.createScaledBitmap(mainFrame, gridW, gridH, true)
        val ultraProxy = ultraWideRef?.let { Bitmap.createScaledBitmap(it, gridW, gridH, true) }

        val mainPixels = IntArray(total)
        mainProxy.getPixels(mainPixels, 0, gridW, 0, 0, gridW, gridH)

        val ultraPixels = if (ultraProxy != null) {
            val pixels = IntArray(total)
            ultraProxy.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)
            pixels
        } else null

        // 2. Compute luminance and spatial gradient/Laplacian high-frequency energy
        val luma = FloatArray(total)
        val gradEnergy = FloatArray(total)
        for (y in 0 until gridH) {
            val rowOffset = y * gridW
            for (x in 0 until gridW) {
                val idx = rowOffset + x
                val c = mainPixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                luma[idx] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            }
        }

        for (y in 1 until gridH - 1) {
            val rowOffset = y * gridW
            for (x in 1 until gridW - 1) {
                val idx = rowOffset + x
                val dx = luma[idx + 1] - luma[idx - 1]
                val dy = luma[idx + gridW] - luma[idx - gridW]
                gradEnergy[idx] = sqrt(dx * dx + dy * dy)
            }
        }

        // 3. Estimate stereo disparity if dual optical perspectives are present
        val disparityMap = FloatArray(total) { 0.5f }
        if (ultraPixels != null) {
            // Compare center crop of 0.5x with 1x main frame
            val ultraLuma = FloatArray(total)
            for (i in 0 until total) {
                val c = ultraPixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                ultraLuma[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            }

            // Block match along vertical/horizontal optical axis
            val patchRadius = 2
            val maxShift = 8
            for (y in patchRadius until gridH - patchRadius) {
                for (x in patchRadius until gridW - patchRadius) {
                    val centerIdx = y * gridW + x
                    var bestSsd = Float.MAX_VALUE
                    var bestShift = 0

                    for (shift in -maxShift..maxShift) {
                        val sy = (y + shift).coerceIn(patchRadius, gridH - patchRadius - 1)
                        var ssd = 0.0f
                        for (py in -patchRadius..patchRadius) {
                            for (px in -patchRadius..patchRadius) {
                                val mVal = luma[(y + py) * gridW + (x + px)]
                                val uVal = ultraLuma[(sy + py) * gridW + (x + px)]
                                val diff = mVal - uVal
                                ssd += diff * diff
                            }
                        }
                        if (ssd < bestSsd) {
                            bestSsd = ssd
                            bestShift = abs(shift)
                        }
                    }
                    // Higher disparity shift = nearer object; 0 shift = far background
                    disparityMap[centerIdx] = (1.0f - (bestShift.toFloat() / maxShift)).coerceIn(0.0f, 1.0f)
                }
            }
        }

        // 4. Synthesize unified normalized depth map:
        // Ground plane gradient: y / gridH provides natural spatial depth prior
        // Sky detection: bright, low-gradient regions in the upper half are strictly VERY_FAR
        val rawDepth = FloatArray(total)
        for (y in 0 until gridH) {
            val normY = y.toFloat() / (gridH - 1)
            // Vertical perspective prior: bottom = 0.0 (near), top = 0.85 (far)
            val verticalPrior = (1.0f - normY).coerceIn(0.0f, 1.0f)

            for (x in 0 until gridW) {
                val idx = y * gridW + x
                val isSky = normY < 0.40f && luma[idx] > 0.65f && gradEnergy[idx] < 0.08f

                val depthVal = if (isSky) {
                    0.95f // Sky is placed into deep background (VERY_FAR)
                } else if (ultraPixels != null) {
                    // Combine stereo disparity (60%) with vertical prior (40%)
                    0.60f * disparityMap[idx] + 0.40f * verticalPrior
                } else {
                    // Fallback using focus energy and vertical perspective prior
                    val focusCue = (1.0f - (gradEnergy[idx] * 4.0f).coerceIn(0.0f, 0.5f))
                    0.65f * verticalPrior + 0.35f * focusCue
                }
                rawDepth[idx] = depthVal.coerceIn(0.0f, 1.0f)
            }
        }

        // 5. Edge-preserving spatial smoothing filter
        val smoothedDepth = smoothDepthField(rawDepth, luma, gridW, gridH)

        // 6. Up-sample smoothed depth field to target image resolution and compute zone weights
        val upscaledDepth = FloatArray(targetWidth * targetHeight)
        val zoneIndices = ByteArray(targetWidth * targetHeight)
        val nearW = FloatArray(targetWidth * targetHeight)
        val midW = FloatArray(targetWidth * targetHeight)
        val farW = FloatArray(targetWidth * targetHeight)
        val veryFarW = FloatArray(targetWidth * targetHeight)

        val xRatio = (gridW - 1).toFloat() / max(1, targetWidth - 1)
        val yRatio = (gridH - 1).toFloat() / max(1, targetHeight - 1)

        for (ty in 0 until targetHeight) {
            val gy = (ty * yRatio).coerceIn(0.0f, (gridH - 1).toFloat())
            val y0 = gy.toInt()
            val y1 = min(y0 + 1, gridH - 1)
            val yf = gy - y0

            val targetRow = ty * targetWidth

            for (tx in 0 until targetWidth) {
                val gx = (tx * xRatio).coerceIn(0.0f, (gridW - 1).toFloat())
                val x0 = gx.toInt()
                val x1 = min(x0 + 1, gridW - 1)
                val xf = gx - x0

                // Bilinear interpolation of depth field
                val d00 = smoothedDepth[y0 * gridW + x0]
                val d10 = smoothedDepth[y0 * gridW + x1]
                val d01 = smoothedDepth[y1 * gridW + x0]
                val d11 = smoothedDepth[y1 * gridW + x1]

                val d = (d00 * (1f - xf) + d10 * xf) * (1f - yf) + (d01 * (1f - xf) + d11 * xf) * yf
                val targetIdx = targetRow + tx
                upscaledDepth[targetIdx] = d

                // Zone classification
                val zone = SceneDepthZone.fromDepth(d)
                zoneIndices[targetIdx] = zone.ordinal.toByte()

                // Soft continuous blending weights
                val wNear = smoothstep(0.35f, 0.15f, d)
                val wMid = smoothstep(0.15f, 0.35f, d) * smoothstep(0.65f, 0.45f, d)
                val wFar = smoothstep(0.45f, 0.65f, d) * smoothstep(0.90f, 0.75f, d)
                val wVeryFar = smoothstep(0.75f, 0.90f, d)

                val sumW = max(1e-4f, wNear + wMid + wFar + wVeryFar)
                nearW[targetIdx] = wNear / sumW
                midW[targetIdx] = wMid / sumW
                farW[targetIdx] = wFar / sumW
                veryFarW[targetIdx] = wVeryFar / sumW
            }
        }

        // Clean up proxy bitmaps
        mainProxy.recycle()
        ultraProxy?.recycle()

        DepthField(
            width = targetWidth,
            height = targetHeight,
            depthMap = upscaledDepth,
            zoneIndices = zoneIndices,
            nearWeights = nearW,
            midWeights = midW,
            farWeights = farW,
            veryFarWeights = veryFarW
        )
    }

    /**
     * Edge-preserving cross-bilateral filter for guided depth refinement.
     */
    private fun smoothDepthField(
        depth: FloatArray,
        luma: FloatArray,
        w: Int,
        h: Int
    ): FloatArray {
        val result = FloatArray(w * h)
        val radius = 2
        val sigmaSpaceSq = 2.0f * 1.5f * 1.5f
        val sigmaColorSq = 2.0f * 0.12f * 0.12f

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val centerLuma = luma[idx]
                var sumVal = 0.0f
                var sumWeight = 0.0f

                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val nRow = ny * w
                    val distSpaceSq = (dy * dy).toFloat()

                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val nIdx = nRow + nx
                        val dSpaceSq = distSpaceSq + (dx * dx)
                        val dColor = luma[nIdx] - centerLuma
                        val dColorSq = dColor * dColor

                        val weight = kotlin.math.exp(-dSpaceSq / sigmaSpaceSq - dColorSq / sigmaColorSq)
                        sumVal += depth[nIdx] * weight
                        sumWeight += weight
                    }
                }
                result[idx] = if (sumWeight > 0.0f) sumVal / sumWeight else depth[idx]
            }
        }
        return result
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }
}
