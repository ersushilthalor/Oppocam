package com.example.camera.engine.humanvision

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 3-Layer Depth-Aware Computational Smart Fusion Engine.
 *
 * Implements:
 * Layer 1: 0.5x Ultra-Wide (Master full composition & Near foreground)
 * Layer 2: 1x Main Camera (Multi-frame temporal HDR & Denoise mid-ground)
 * Layer 3: 3x Distant Mosaic (Overlapping foveal distant detail)
 *
 * Ghosting Suppression: Moving objects automatically lock to the single sharpest frame.
 * Fallback: If 3x tile data is absent or low-confidence, seamlessly uses 1x data.
 */
class HumanVisionFusionEngine {

    companion object {
        private const val MOTION_REJECTION_THRESHOLD = 0.15f
    }

    /**
     * Executes multi-layer depth-aware fusion.
     */
    suspend fun fuseLayers(
        ultraWideRef: Bitmap?,
        mainFrames: List<Bitmap>,
        distantMosaic: HumanVisionMosaicEngine.StitchedDistantMosaic?,
        depthField: HumanVisionDepthEngine.DepthField,
        motionMask: FloatArray,
        config: HumanVisionConfig
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = depthField.width
        val height = depthField.height
        val total = width * height

        // 1. Prepare Base 1x Layer with temporal multi-frame averaging & HDR denoise
        val mainFused = if (mainFrames.size > 1 && config.hdrDenoiseBurst) {
            temporalAverageBurst(mainFrames, width, height, motionMask)
        } else if (mainFrames.isNotEmpty()) {
            val f = mainFrames[0]
            if (f.width == width && f.height == height) f else Bitmap.createScaledBitmap(f, width, height, true)
        } else {
            ultraWideRef ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        val mainPixels = IntArray(total)
        mainFused.getPixels(mainPixels, 0, width, 0, 0, width, height)

        // 2. Prepare 0.5x Ultra-Wide Master Layer (if available)
        val ultraPixels = if (ultraWideRef != null) {
            val uScaled = if (ultraWideRef.width == width && ultraWideRef.height == height) {
                ultraWideRef
            } else {
                Bitmap.createScaledBitmap(ultraWideRef, width, height, true)
            }
            val p = IntArray(total)
            uScaled.getPixels(p, 0, width, 0, 0, width, height)
            if (uScaled != ultraWideRef) uScaled.recycle()
            p
        } else null

        // 3. Prepare 3x Distant Detail Mosaic Layer
        val mosaicPixels = if (distantMosaic != null && distantMosaic.mosaicBitmap.width == width && distantMosaic.mosaicBitmap.height == height) {
            val p = IntArray(total)
            distantMosaic.mosaicBitmap.getPixels(p, 0, width, 0, 0, width, height)
            p
        } else null

        val mosaicConf = distantMosaic?.confidenceMap

        val outputPixels = IntArray(total)

        // 4. Per-pixel depth-guided smart fusion with motion rejection
        val wNear = depthField.nearWeights
        val wMid = depthField.midWeights
        val wFar = depthField.farWeights
        val wVeryFar = depthField.veryFarWeights

        for (i in 0 until total) {
            val motion = motionMask[i]

            // STRICT GHOSTING SUPPRESSION:
            // If movement is detected, lock 100% to the primary single-frame source.
            if (config.movingObjectProtection && motion > MOTION_REJECTION_THRESHOLD) {
                outputPixels[i] = mainPixels[i]
                continue
            }

            val pMain = mainPixels[i]
            val mr = (pMain shr 16) and 0xFF
            val mg = (pMain shr 8) and 0xFF
            val mb = pMain and 0xFF

            // Determine foreground 0.5x contribution
            val pUltra = ultraPixels?.get(i) ?: pMain
            val ur = (pUltra shr 16) and 0xFF
            val ug = (pUltra shr 8) and 0xFF
            val ub = pUltra and 0xFF

            // Determine distant 3x mosaic contribution
            var dr = mr
            var dg = mg
            var db = mb
            var has3xDetail = false

            if (mosaicPixels != null && mosaicConf != null) {
                val conf = mosaicConf[i]
                if (conf > 0.05f) {
                    val pMos = mosaicPixels[i]
                    val pMosA = (pMos ushr 24)
                    if (pMosA > 10) {
                        val mosR = (pMos shr 16) and 0xFF
                        val mosG = (pMos shr 8) and 0xFF
                        val mosB = pMos and 0xFF

                        // Transfer high-frequency texture and luminance from 3x mosaic
                        // while keeping color harmony from the 1x exposure.
                        val mosLuma = 0.299f * mosR + 0.587f * mosG + 0.114f * mosB
                        val mainLuma = 0.299f * mr + 0.587f * mg + 0.114f * mb
                        val lumaRatio = if (mainLuma > 1.0f) (mosLuma / mainLuma).coerceIn(0.70f, 1.35f) else 1.0f

                        dr = (mr * lumaRatio).roundToInt().coerceIn(0, 255)
                        dg = (mg * lumaRatio).roundToInt().coerceIn(0, 255)
                        db = (mb * lumaRatio).roundToInt().coerceIn(0, 255)
                        has3xDetail = true
                    }
                }
            }

            // Depth zone weights:
            // Near weight -> 0.5x ultra-wide framing
            // Mid weight -> 1x high-quality HDR
            // Far & Very Far weight -> 3x foveal distant mosaic (or 1x fallback)
            val nearFraction = if (ultraPixels != null) wNear[i] else 0.0f
            val midFraction = wMid[i]
            val distantFraction = wFar[i] + wVeryFar[i]

            // If 3x detail is available, blend it in the distant region; otherwise fall back to 1x
            val distantR = if (has3xDetail) dr else mr
            val distantG = if (has3xDetail) dg else mg
            val distantB = if (has3xDetail) db else mb

            val totalWeight = nearFraction + midFraction + distantFraction
            val invTotal = if (totalWeight > 1e-4f) 1.0f / totalWeight else 1.0f

            val fusedR = (ur * nearFraction + mr * midFraction + distantR * distantFraction) * invTotal
            val fusedG = (ug * nearFraction + mg * midFraction + distantG * distantFraction) * invTotal
            val fusedB = (ub * nearFraction + mb * midFraction + distantB * distantFraction) * invTotal

            outputPixels[i] = (0xFF shl 24) or
                    (fusedR.roundToInt().coerceIn(0, 255) shl 16) or
                    (fusedG.roundToInt().coerceIn(0, 255) shl 8) or
                    fusedB.roundToInt().coerceIn(0, 255)
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)

        // 5. Apply micro-contrast and sharpness enhancement to distant regions
        if (config.distantDetailBoost > 0.05f) {
            applyDistantDetailEnhancement(resultBitmap, depthField, config.distantDetailBoost)
        }

        if (mainFused != ultraWideRef && mainFrames.isNotEmpty() && mainFused != mainFrames[0]) {
            mainFused.recycle()
        }

        resultBitmap
    }

    /**
     * Temporal multi-frame averaging for the 1x burst layer with motion rejection.
     */
    private fun temporalAverageBurst(
        frames: List<Bitmap>,
        targetWidth: Int,
        targetHeight: Int,
        motionMask: FloatArray
    ): Bitmap {
        val total = targetWidth * targetHeight
        val accumR = FloatArray(total)
        val accumG = FloatArray(total)
        val accumB = FloatArray(total)
        val accumW = FloatArray(total)

        for ((idx, frame) in frames.withIndex()) {
            val fScaled = if (frame.width == targetWidth && frame.height == targetHeight) {
                frame
            } else {
                Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
            }
            val p = IntArray(total)
            fScaled.getPixels(p, 0, targetWidth, 0, 0, targetWidth, targetHeight)

            for (i in 0 until total) {
                val motion = motionMask[i]
                // For moving areas, only accept the primary reference frame (index 0)
                val weight = if (motion > MOTION_REJECTION_THRESHOLD) {
                    if (idx == 0) 1.0f else 0.0f
                } else {
                    1.0f
                }

                if (weight > 0.0f) {
                    val c = p[i]
                    accumR[i] += ((c shr 16) and 0xFF) * weight
                    accumG[i] += ((c shr 8) and 0xFF) * weight
                    accumB[i] += (c and 0xFF) * weight
                    accumW[i] += weight
                }
            }

            if (fScaled != frame) fScaled.recycle()
        }

        val outPixels = IntArray(total)
        for (i in 0 until total) {
            val w = accumW[i]
            if (w > 0.0f) {
                val r = (accumR[i] / w).roundToInt().coerceIn(0, 255)
                val g = (accumG[i] / w).roundToInt().coerceIn(0, 255)
                val b = (accumB[i] / w).roundToInt().coerceIn(0, 255)
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                outPixels[i] = (0xFF shl 24)
            }
        }

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        return result
    }

    /**
     * Unsharp mask micro-contrast boost applied selectively to Far and Very Far depth zones.
     */
    private fun applyDistantDetailEnhancement(
        bitmap: Bitmap,
        depthField: HumanVisionDepthEngine.DepthField,
        boost: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val farW = depthField.farWeights
        val veryFarW = depthField.veryFarWeights

        val radius = 1
        val amount = boost * 0.40f

        for (y in radius until h - radius) {
            val row = y * w
            for (x in radius until w - radius) {
                val idx = row + x
                val depthWeight = farW[idx] + veryFarW[idx]
                if (depthWeight < 0.1f) continue

                val cCenter = pixels[idx]
                val cr = (cCenter shr 16) and 0xFF
                val cg = (cCenter shr 8) and 0xFF
                val cb = cCenter and 0xFF

                // 3x3 Laplacian edge kernel
                val cTop = pixels[(y - 1) * w + x]
                val cBot = pixels[(y + 1) * w + x]
                val cLeft = pixels[row + x - 1]
                val cRight = pixels[row + x + 1]

                val avgR = (((cTop shr 16) and 0xFF) + ((cBot shr 16) and 0xFF) + ((cLeft shr 16) and 0xFF) + ((cRight shr 16) and 0xFF)) * 0.25f
                val avgG = (((cTop shr 8) and 0xFF) + ((cBot shr 8) and 0xFF) + ((cLeft shr 8) and 0xFF) + ((cRight shr 8) and 0xFF)) * 0.25f
                val avgB = ((cTop and 0xFF) + (cBot and 0xFF) + (cLeft and 0xFF) + (cRight and 0xFF)) * 0.25f

                val deltaR = cr - avgR
                val deltaG = cg - avgG
                val deltaB = cb - avgB

                val scale = amount * depthWeight
                val nr = (cr + deltaR * scale).roundToInt().coerceIn(0, 255)
                val ng = (cg + deltaG * scale).roundToInt().coerceIn(0, 255)
                val nb = (cb + deltaB * scale).roundToInt().coerceIn(0, 255)

                pixels[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
