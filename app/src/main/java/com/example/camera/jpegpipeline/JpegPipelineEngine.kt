package com.example.camera.jpegpipeline

import android.graphics.ColorSpace
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.util.Log
import android.util.Rational
import kotlin.math.roundToInt

/**
 * JPEG Pipeline Video Engine
 *
 * CONCEPTUAL ARCHITECTURE:
 * Sensor → Camera ISP / normal photo-style rendering → processed YUV frame → Video Encoder → MP4
 *
 * Strictly adheres to:
 * - NO RAW video, NO LOG video, NO flat profile, NO LUTs
 * - NO computational / multi-frame HDR
 * - NO per-frame JPEG encoding to disk or CPU (uses zero-copy hardware ISP YUV streaming)
 * - Viewfinder preview and hardware video encoder share the exact same ISP rendering path
 *
 * Configures native Camera2 ISP parameters so that each frame emerging from the sensor
 * already has finished phone-camera photo characteristics:
 * - Normal photo exposure & gamma
 * - High-quality lens shading / vignette correction
 * - Hardware noise reduction (single-frame)
 * - Profile-specific ISP sharpening & detail processing
 * - Highlight roll-off and shadow rendering via hardware tonemapping
 * - Controlled color matrix and white balance gains
 */
class JpegPipelineEngine {

    companion object {
        private const val TAG = "JpegPipelineEngine"

        // Cached Tonemap Curves for photo-style rendering
        private val STANDARD_CURVE by lazy { buildTonemapCurve(curvePoints = floatArrayOf(
            0.00f, 0.00f,
            0.10f, 0.05f,
            0.20f, 0.12f,
            0.30f, 0.22f,
            0.40f, 0.33f,
            0.50f, 0.46f,
            0.60f, 0.58f,
            0.70f, 0.70f,
            0.80f, 0.81f,
            0.90f, 0.91f,
            1.00f, 1.00f
        )) }

        // iPhone Style: gradual highlight knee compression & natural skin tone midtones
        private val IPHONE_CURVE by lazy { buildTonemapCurve(curvePoints = floatArrayOf(
            0.00f, 0.00f,
            0.10f, 0.06f,
            0.20f, 0.14f,
            0.30f, 0.24f,
            0.40f, 0.36f,
            0.50f, 0.49f,
            0.60f, 0.61f,
            0.70f, 0.72f,
            0.80f, 0.82f,
            0.90f, 0.90f,
            1.00f, 0.97f
        )) }

        // Samsung Style: punchier S-curve with deep shadows, bright highlights & crisp micro-contrast
        private val SAMSUNG_CURVE by lazy { buildTonemapCurve(curvePoints = floatArrayOf(
            0.00f, 0.00f,
            0.10f, 0.03f,
            0.20f, 0.09f,
            0.30f, 0.18f,
            0.40f, 0.31f,
            0.50f, 0.48f,
            0.60f, 0.64f,
            0.70f, 0.78f,
            0.80f, 0.89f,
            0.90f, 0.96f,
            1.00f, 1.00f
        )) }

        // OPPO Style: lifted shadow detail & smooth creamy highlight falloff
        private val OPPO_CURVE by lazy { buildTonemapCurve(curvePoints = floatArrayOf(
            0.00f, 0.00f,
            0.05f, 0.04f,
            0.15f, 0.12f,
            0.25f, 0.22f,
            0.40f, 0.38f,
            0.50f, 0.50f,
            0.65f, 0.67f,
            0.80f, 0.81f,
            0.90f, 0.91f,
            1.00f, 0.98f
        )) }

        private fun buildTonemapCurve(curvePoints: FloatArray): TonemapCurve {
            val count = curvePoints.size / 2
            val r = FloatArray(curvePoints.size)
            val g = FloatArray(curvePoints.size)
            val b = FloatArray(curvePoints.size)
            System.arraycopy(curvePoints, 0, r, 0, curvePoints.size)
            System.arraycopy(curvePoints, 0, g, 0, curvePoints.size)
            System.arraycopy(curvePoints, 0, b, 0, curvePoints.size)
            return TonemapCurve(r, g, b)
        }
    }

    /**
     * Inspects device HAL characteristics and produces a hardware capability report.
     * Documents which Camera2 ISP parameters the device HAL exposes or locks internally.
     */
    fun detectCapabilities(chars: CameraCharacteristics?): JpegPipelineCapabilities {
        if (chars == null) {
            return JpegPipelineCapabilities(
                isAvailable = true,
                architectureSummary = "Standard Camera2 ISP YUV Stream → Hardware Video Encoder",
                unexposedParametersNotice = "Device characteristics unavailable; applying standard single-frame ISP profile."
            )
        }

        val noiseModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList() ?: emptyList()
        val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList() ?: emptyList()
        val tonemapModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList() ?: emptyList()
        val shadingModes = chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)?.toList() ?: emptyList()
        val requestCaps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val supportsManualPostProcessing = requestCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        val maxCurvePts = chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0

        val unexposedList = mutableListOf<String>()
        if (!tonemapModes.contains(CameraCharacteristics.TONEMAP_MODE_CONTRAST_CURVE)) {
            unexposedList.add("Custom Tonemap Contrast S-Curve (HAL uses internal photo auto-tonemap)")
        }
        if (!supportsManualPostProcessing) {
            unexposedList.add("Direct 3x3 Color Correction Transform Matrix (HAL uses auto color matrix)")
        }
        if (!shadingModes.contains(CameraCharacteristics.SHADING_MODE_HIGH_QUALITY)) {
            unexposedList.add("Dynamic Lens Shading Mode (HAL applies fixed vendor lens calibration)")
        }

        val notice = if (unexposedList.isNotEmpty()) {
            "Note: HAL keeps ${unexposedList.joinToString(", ")} managed by internal photo ISP. The pipeline uses the closest single-frame high-quality ISP modes."
        } else {
            "All Camera2 single-frame photo ISP parameters fully accessible by HAL."
        }

        return JpegPipelineCapabilities(
            isAvailable = true,
            isHardwareNoiseReductionSupported = noiseModes.contains(CameraCharacteristics.NOISE_REDUCTION_MODE_HIGH_QUALITY) || noiseModes.isNotEmpty(),
            supportedNoiseReductionModes = noiseModes,
            isHardwareEdgeModeSupported = edgeModes.contains(CameraCharacteristics.EDGE_MODE_HIGH_QUALITY) || edgeModes.isNotEmpty(),
            supportedEdgeModes = edgeModes,
            isTonemapCurveSupported = tonemapModes.contains(CameraCharacteristics.TONEMAP_MODE_CONTRAST_CURVE) && maxCurvePts >= 16,
            supportedTonemapModes = tonemapModes,
            isColorCorrectionTransformSupported = supportsManualPostProcessing,
            isShadingModeSupported = shadingModes.contains(CameraCharacteristics.SHADING_MODE_HIGH_QUALITY) || shadingModes.isNotEmpty(),
            supportedShadingModes = shadingModes,
            isHotPixelModeSupported = true,
            maxTonemapCurvePoints = maxCurvePts,
            architectureSummary = "Sensor → Hardware ISP (Photo Rendering) → Single-Frame YUV → Video Encoder → MP4",
            unexposedParametersNotice = notice
        )
    }

    /**
     * Applies the requested JPEG Pipeline rendering profile to the Camera2 CaptureRequest.Builder.
     *
     * This method is called identically for:
     * 1. Viewfinder preview repeating requests
     * 2. Video recording repeating requests
     *
     * Guaranteed architecture:
     * - Single-frame photo-style ISP rendering in memory
     * - NO RAW video, NO LOG gamma, NO multi-frame HDR
     * - Feeds finished YUV frames straight into the video encoder
     */
    fun applyJpegPipelineSettings(
        builder: CaptureRequest.Builder,
        profile: JpegPipelineProfile,
        chars: CameraCharacteristics?
    ) {
        try {
            // 1. Single-Frame Normal Auto-Exposure (NEVER multi-frame or computational HDR)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            // Fine-tuned exposure compensation for profile highlight/shadow characteristics
            if (chars != null) {
                val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 3)
                val stepFloat = step.toFloat()
                if (stepFloat > 0f) {
                    val biasIndex = (profile.targetExposureCompensationEv / stepFloat).roundToInt()
                    val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    if (range != null && biasIndex != 0) {
                        val clamped = biasIndex.coerceIn(range.lower, range.upper)
                        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped)
                    }
                }
            }

            // 2. Hardware Noise Reduction (Photo-grade single-frame filtering)
            val availableNrModes = chars?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
            if (availableNrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            } else if (availableNrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            }

            // 3. Hardware Sharpening & Detail Processing (Edge enhancement per profile)
            val availableEdgeModes = chars?.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
            when (profile) {
                JpegPipelineProfile.SAMSUNG -> {
                    // Samsung Style: Stronger detail & sharper edge definition
                    if (availableEdgeModes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
                        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    }
                }
                JpegPipelineProfile.IPHONE -> {
                    // iPhone Style: Natural sharpening without excessive ringing or halos
                    if (availableEdgeModes.contains(CaptureRequest.EDGE_MODE_FAST)) {
                        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                    } else if (availableEdgeModes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
                        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    }
                }
                JpegPipelineProfile.OPPO -> {
                    // OPPO Style: Moderate smoothing sharpening for pleasing skin textures
                    if (availableEdgeModes.contains(CaptureRequest.EDGE_MODE_FAST)) {
                        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                    } else if (availableEdgeModes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
                        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    }
                }
                JpegPipelineProfile.STANDARD -> {
                    if (availableEdgeModes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
                        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    }
                }
            }

            // 4. Optical Lens Shading & Vignette Correction
            val availableShadingModes = chars?.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: intArrayOf()
            if (availableShadingModes.contains(CaptureRequest.SHADING_MODE_HIGH_QUALITY)) {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
            }

            // 5. Hot Pixel & Aberration Correction
            val hotPixelModes = chars?.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: intArrayOf()
            if (hotPixelModes.contains(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)) {
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
            }
            val aberrationModes = chars?.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: intArrayOf()
            if (aberrationModes.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)) {
                builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            }

            // 6. Highlight Roll-off, Contrast & Tonemapping
            val tonemapModes = chars?.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
            val maxCurvePoints = chars?.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0

            if (tonemapModes.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) && maxCurvePoints >= 16) {
                val curve = when (profile) {
                    JpegPipelineProfile.STANDARD -> STANDARD_CURVE
                    JpegPipelineProfile.IPHONE -> IPHONE_CURVE
                    JpegPipelineProfile.SAMSUNG -> SAMSUNG_CURVE
                    JpegPipelineProfile.OPPO -> OPPO_CURVE
                }
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                builder.set(CaptureRequest.TONEMAP_CURVE, curve)
            } else {
                // If custom contrast curve is locked by HAL, enforce high-quality photo tonemap mode
                if (tonemapModes.contains(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)) {
                    builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
                } else if (tonemapModes.contains(CaptureRequest.TONEMAP_MODE_FAST)) {
                    builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
                }
            }

            // 7. Color Correction Matrix & Channel Gains
            val reqCaps = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val supportsColorTransform = reqCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
            if (supportsColorTransform) {
                when (profile) {
                    JpegPipelineProfile.OPPO -> {
                        // OPPO Style: Gentle warm golden shift (flattering warm portrait skin tones)
                        val warmGains = RggbChannelVector(1.06f, 1.00f, 1.00f, 0.94f)
                        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, warmGains)
                    }
                    JpegPipelineProfile.SAMSUNG -> {
                        // Samsung Style: Punchier color saturation
                        val vibrantGains = RggbChannelVector(1.03f, 1.00f, 1.00f, 1.04f)
                        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, vibrantGains)
                    }
                    JpegPipelineProfile.IPHONE -> {
                        // iPhone Style: Balanced natural neutral gains
                        val neutralGains = RggbChannelVector(1.00f, 1.00f, 1.00f, 1.00f)
                        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, neutralGains)
                    }
                    JpegPipelineProfile.STANDARD -> {
                        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                    }
                }
            } else {
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
            }

            // Ensure no effect filter is overlaid
            builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)

        } catch (e: Exception) {
            Log.w(TAG, "Error applying JPEG pipeline settings: ${e.message}", e)
        }
    }
}
