package com.example.camera.engine

import android.graphics.ColorMatrix
import android.hardware.camera2.CameraCharacteristics
import com.example.camera.model.VideoAdjustments
import kotlin.math.pow

/**
 * High-performance Video Adjustments Pipeline for Normal Video Mode.
 * Unifies the parameter mathematical transformation across live viewfinder preview
 * and hardware video recording export.
 */
object VideoAdjustmentsPipeline {

    /**
     * Computes the 4x5 Android ColorMatrix for live preview and video transcoding.
     * Returns null if all adjustments are at default (0).
     */
    fun computeColorMatrix(adjustments: VideoAdjustments?): ColorMatrix? {
        if (adjustments == null || adjustments.isDefault) return null

        val masterMatrix = ColorMatrix()
        var hasTransform = false

        // 1. Exposure & Tonality Transform
        val exp = adjustments.exposure
        val tonality = adjustments.tonality
        if (exp != 0.0f || tonality != 0f) {
            // Exposure factor: 2^(EV)
            val expGain = 2.0f.pow(exp * 0.45f) // Smooth visual calibration
            val toneShift = (tonality / 100f) * 25.0f

            val expMat = ColorMatrix(floatArrayOf(
                expGain, 0f, 0f, 0f, toneShift,
                0f, expGain, 0f, 0f, toneShift,
                0f, 0f, expGain, 0f, toneShift,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(expMat)
            hasTransform = true
        }

        // 2. Contrast & Curve Transform
        val contrast = adjustments.contrast
        val curveBlacks = adjustments.curveBlacks
        val curveWhites = adjustments.curveWhites
        val curveMidtones = adjustments.curveMidtones
        if (contrast != 0f || curveBlacks != 0f || curveWhites != 0f || curveMidtones != 0f) {
            val c = 1.0f + (contrast / 100f) * 0.65f
            val offset = 128f * (1.0f - c) + (curveWhites - curveBlacks) * 0.25f + (curveMidtones * 0.2f)

            val contrastMat = ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, offset,
                0f, c, 0f, 0f, offset,
                0f, 0f, c, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(contrastMat)
            hasTransform = true
        }

        // 3. Highlights & Shadows Tone Mapping
        val highlights = adjustments.highlights
        val shadows = adjustments.shadows
        val curveHighlights = adjustments.curveHighlights
        val curveShadows = adjustments.curveShadows
        val totalHighlights = highlights + curveHighlights
        val totalShadows = shadows + curveShadows
        if (totalHighlights != 0f || totalShadows != 0f) {
            val highScale = 1.0f - (totalHighlights / 100f) * 0.20f
            val shadowLift = (totalShadows / 100f) * 28.0f

            val hlMat = ColorMatrix(floatArrayOf(
                highScale, 0f, 0f, 0f, shadowLift,
                0f, highScale, 0f, 0f, shadowLift,
                0f, 0f, highScale, 0f, shadowLift,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(hlMat)
            hasTransform = true
        }

        // 4. White Balance: Temperature & Tint
        val temp = adjustments.temperature
        val tint = adjustments.tint
        if (temp != 0f || tint != 0f) {
            // Temperature: warm boosts R and decreases B; cool boosts B and decreases R
            val tFactor = temp / 100f
            val rTemp = 1.0f + tFactor * 0.22f
            val bTemp = 1.0f - tFactor * 0.22f

            // Tint: green boosts G and lowers R/B; magenta lowers G and boosts R/B
            val tintFactor = tint / 100f
            val gTint = 1.0f - tintFactor * 0.18f
            val rTint = 1.0f + tintFactor * 0.09f
            val bTint = 1.0f + tintFactor * 0.09f

            val rScale = rTemp * rTint
            val gScale = gTint
            val bScale = bTemp * bTint

            val wbMat = ColorMatrix(floatArrayOf(
                rScale, 0f, 0f, 0f, 0f,
                0f, gScale, 0f, 0f, 0f,
                0f, 0f, bScale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(wbMat)
            hasTransform = true
        }

        // 5. Saturation & Vibrance
        val sat = adjustments.saturation
        val vib = adjustments.colorVibrance
        val totalSat = sat + (vib * 0.65f)
        if (totalSat != 0f) {
            val s = (1.0f + (totalSat / 100f)).coerceAtLeast(0f)
            val satMat = ColorMatrix().apply { setSaturation(s) }
            masterMatrix.postConcat(satMat)
            hasTransform = true
        }

        // 6. Advanced Color Balance (R-C, G-M, B-Y)
        val balR = adjustments.colorBalanceR
        val balG = adjustments.colorBalanceG
        val balB = adjustments.colorBalanceB
        if (balR != 0f || balG != 0f || balB != 0f) {
            val rShift = (balR / 100f) * 20.0f
            val gShift = (balG / 100f) * 20.0f
            val bShift = (balB / 100f) * 20.0f

            val balMat = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, rShift,
                0f, 1f, 0f, 0f, gShift,
                0f, 0f, 1f, 0f, bShift,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(balMat)
            hasTransform = true
        }

        // 7. Light FX & Texture subtle tonal influence
        val halation = adjustments.textureHalation
        val softLight = adjustments.lightFxSoftLight
        if (halation > 0f || softLight > 0f) {
            val rGlow = (halation / 100f) * 8.0f
            val softGlow = (softLight / 100f) * 10.0f

            val fxMat = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, rGlow + softGlow,
                0f, 1f, 0f, 0f, softGlow * 0.7f,
                0f, 0f, 1f, 0f, softGlow * 0.5f,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(fxMat)
            hasTransform = true
        }

        return if (hasTransform) masterMatrix else null
    }

    /**
     * Checks if any spatial effect (Vignette, Grain, Light FX, Sharpness) is active.
     */
    fun hasSpatialEffects(adjustments: VideoAdjustments?): Boolean {
        if (adjustments == null) return false
        return adjustments.vignette > 0f ||
                adjustments.grain > 0f ||
                adjustments.textureFilmGrain > 0f ||
                adjustments.lightFxFlash > 0f ||
                adjustments.lightFxBloom > 0f ||
                adjustments.lightFxSoftLight > 0f ||
                adjustments.textureHalation > 0f ||
                adjustments.clarity != 0f ||
                adjustments.sharpness > 0f
    }
}
