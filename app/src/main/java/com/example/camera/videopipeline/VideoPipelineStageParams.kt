package com.example.camera.videopipeline

import android.hardware.camera2.params.TonemapCurve
import kotlin.math.pow

/**
 * Independent multi-stage processing configuration for a dedicated Video Pipeline.
 *
 * Defines parameters for all 6 independent processing stages:
 * - Stage 0: Camera2 Sensor & ISP Acquisition (Exposure Headroom, Custom Sensor Tonemap Curve, HAL Edge/NR Bypass)
 * - Stage 1: Sensor Linearization & Chromatic Pre-Adaptation (De-gamma, Exposure Gain, Warmth/Tint Balance)
 * - Stage 2: Multi-Zone Computational HDR & Dynamic Range Compression (Highlight Knee/Shoulder, Shadow Toe, Subject Separation)
 * - Stage 3: 5-Tap Spatial Detail, Micro-Contrast & Unsharp Masking (Luma High-Pass Convolution, Local Clarity, Halo Protection)
 * - Stage 4: 3x3 Chromatic Adaptation Matrix & Memory Color Protection (Gamut Matrix, Melanin/Peach Skin Protection, Sky/Foliage)
 * - Stage 5: Parametric Filmic Contrast & Luma-Weighted Vibrance (S-Curve Slope/Pivot, Vibrance vs Global Saturation)
 * - Stage 6: Display Transfer Encoding & Dynamic Range Clamping (Black Point Pinning, White Point Ceiling, Rec.709 Transfer)
 */
data class VideoPipelineStageParams(
    // Stage 0: Sensor & ISP Acquisition
    val sensorExposureBiasEv: Float = 0.0f,
    val ispTonemapGamma: Float = 1.0f,
    val ispHighlightRollOff: Float = 0.0f,
    val bypassHalEdgeSharpening: Boolean = true,
    val highQualityTemporalDenoise: Boolean = true,

    // Stage 1: Sensor Linearization & Chromatic Pre-Adaptation
    val inputDeGamma: Float = 1.0f,
    val linearExposureGain: Float = 1.0f,
    val warmthShift: Float = 0.0f,
    val tintShift: Float = 0.0f,

    // Stage 2: Multi-Zone Computational HDR & Tone Mapping
    val highlightKneeThreshold: Float = 0.68f,
    val highlightCompressionStrength: Float = 0.30f,
    val shadowToeLimit: Float = 0.35f,
    val shadowLiftStrength: Float = 0.05f,
    val subjectMidtoneCenter: Float = 0.48f,
    val subjectSeparationLift: Float = 0.05f,

    // Stage 3: 5-Tap Spatial Detail, Micro-Contrast & Sharpening
    val spatialSharpnessStrength: Float = 0.25f,
    val localMicroContrastStrength: Float = 0.12f,
    val detailRadiusTexels: Float = 1.25f,
    val haloProtectionLimit: Float = 0.08f,

    // Stage 4: 3x3 Color Science Matrix & Memory Color Protection
    val colorMatrix3x3: FloatArray = floatArrayOf(
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    ),
    val skinToneProtectionStrength: Float = 0.30f,
    val skinWarmthTargetR: Float = 1.015f,
    val skinWarmthTargetG: Float = 1.000f,
    val skinWarmthTargetB: Float = 0.985f,
    val skyBlueRetention: Float = 0.15f,

    // Stage 5: Parametric Filmic Contrast & Luma-Weighted Vibrance
    val filmicContrastSlope: Float = 1.08f,
    val filmicContrastPivot: Float = 0.46f,
    val lumaWeightedVibrance: Float = 0.06f,
    val globalSaturation: Float = 1.05f,

    // Stage 6: Display Transfer & Gamut Calibration
    val blackPointFloor: Float = 0.002f,
    val whitePointCeiling: Float = 0.998f,
    val outputGamma: Float = 1.0f
) {
    /**
     * Builds a dedicated 16-point Camera2 hardware TonemapCurve that bypasses the
     * normal OEM video contrast curve and preserves highlight & shadow latitude
     * for the custom video pipeline's shader stages.
     */
    fun buildCustomIspTonemapCurve(): TonemapCurve {
        val numPoints = 16
        val curvePoints = FloatArray(numPoints * 2)
        for (i in 0 until numPoints) {
            val x = i.toFloat() / (numPoints - 1).toFloat()
            // Apply custom pipeline sensor transfer curve with highlight headroom retention
            val gammaCorrected = x.toDouble().pow(1.0 / ispTonemapGamma.toDouble()).toFloat()
            val y = if (x > highlightKneeThreshold && ispHighlightRollOff > 0f) {
                val excess = (x - highlightKneeThreshold) / (1f - highlightKneeThreshold).coerceAtLeast(0.05f)
                (gammaCorrected - excess * excess * ispHighlightRollOff).coerceIn(0f, 1f)
            } else {
                gammaCorrected.coerceIn(0f, 1f)
            }
            curvePoints[i * 2] = x
            curvePoints[i * 2 + 1] = y
        }
        return TonemapCurve(curvePoints, curvePoints.clone(), curvePoints.clone())
    }
}
