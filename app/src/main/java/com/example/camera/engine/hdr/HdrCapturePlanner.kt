package com.example.camera.engine.hdr

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import com.example.camera.engine.FrameLuminanceStats
import com.example.camera.engine.GyroStabilizationEngine
import com.example.camera.model.FlashMode
import kotlin.math.roundToInt

/**
 * Intelligent Exposure Bracket & Multi-Frame Capture Planner for Flagship Computational HDR.
 *
 * Responsibilities:
 * 1. Analyzes real-time scene histogram, percentile luminance (P1, P5, P95, P99), dynamic range,
 *    sensor ISO, exposure time, flash state, and gyro angular velocity.
 * 2. Deterministically decides bracket strategy:
 *    - SINGLE_FRAME: Flat daylight, overcast scene, flash active, or fast handheld motion.
 *    - TWO_FRAME_HIGHLIGHT: Bright sky, bright windows, lamps with normal midtones/shadows.
 *    - THREE_FRAME_FULL: Extreme dynamic range (backlit portrait, sunrise/sunset, deep shadow + bright sky).
 * 3. Maps exposure offsets (e.g. -1.7 EV, 0.0 EV, +1.4 EV) to exact HAL exposure compensation steps.
 */
class HdrCapturePlanner {

    companion object {
        private const val DEFAULT_SHORT_EV = -1.7f
        private const val DEFAULT_LONG_EV = +1.4f
        private const val MAX_HANDHELD_SHUTTER_NS = 66_666_666L // 1/15s
        private const val MOTION_RMS_THRESHOLD = 0.38f // rad/s shake threshold
    }

    fun planCapture(
        chars: CameraCharacteristics?,
        lastResult: TotalCaptureResult?,
        flashMode: FlashMode,
        stats: FrameLuminanceStats?,
        gyroEngine: GyroStabilizationEngine?
    ): HdrCapturePlan {
        val compRange = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-6, 6)
        val compStepRational = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val compStep = if (compStepRational != null && compStepRational.denominator != 0) {
            compStepRational.numerator.toFloat() / compStepRational.denominator.toFloat()
        } else {
            0.3333f
        }

        val expTimeNs = lastResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_333_333L
        val iso = lastResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val aeState = lastResult?.get(CaptureResult.CONTROL_AE_STATE)

        // 1. Flash Override: Flash illuminates the scene with artificial light; multi-exposure flash HDR is prohibited
        val isFlashActive = flashMode == FlashMode.ON ||
                flashMode == FlashMode.TORCH ||
                (flashMode == FlashMode.AUTO && aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)

        if (isFlashActive) {
            return HdrCapturePlan(
                bracketType = HdrBracketType.SINGLE_FRAME,
                specs = listOf(HdrExposureSpec(FrameRole.REFERENCE_BASE, 0f, 0, true)),
                reason = "Flash active - single exposure fill",
                estimatedIso = iso,
                estimatedExpTimeNs = expTimeNs
            )
        }

        // 2. Gyroscope Handshake & High-Motion Safety Check
        val rmsMotion = gyroEngine?.getRecentRmsMotion() ?: 0f
        if (rmsMotion > MOTION_RMS_THRESHOLD) {
            return HdrCapturePlan(
                bracketType = HdrBracketType.SINGLE_FRAME,
                specs = listOf(HdrExposureSpec(FrameRole.REFERENCE_BASE, 0f, 0, true)),
                reason = "High device motion ($rmsMotion rad/s) - single frame to prevent motion blur",
                estimatedIso = iso,
                estimatedExpTimeNs = expTimeNs
            )
        }

        // 3. Shutter Speed Check: If base exposure is very slow (> 66ms / 1/15s), capturing long bracket causes severe blur
        val isVerySlowShutter = expTimeNs > MAX_HANDHELD_SHUTTER_NS

        // 4. Scene Dynamic Range & Histogram Analysis
        val p99 = stats?.p99 ?: 0.90f
        val p95 = stats?.p95 ?: 0.82f
        val p5 = stats?.p5 ?: 0.08f
        val p1 = stats?.p1 ?: 0.02f
        val dynamicRange = stats?.dynamicRange ?: (p99 - p1)
        val isHighContrast = stats?.isHighContrast == true
        val isOutdoorSky = stats?.isOutdoorSkyWithDarkForeground == true

        // Blown highlights detection (sky clipping, light sources, bright windows)
        val hasBlownHighlights = p99 >= 0.91f || (p95 >= 0.86f && p5 <= 0.22f) || isOutdoorSky

        // Deep shadow detection (dark subjects, backlit rooms, deep foliage)
        val hasDeepShadows = p5 <= 0.09f && p95 >= 0.65f

        // Convert EV to AE compensation indices
        fun evToIndex(ev: Float): Int {
            if (compStep <= 0f) return 0
            val steps = (ev / compStep).roundToInt()
            return if (compRange.lower <= compRange.upper) {
                steps.coerceIn(compRange.lower, compRange.upper)
            } else {
                0
            }
        }

        val baseSpec = HdrExposureSpec(FrameRole.REFERENCE_BASE, 0f, 0, true)

        // 5. Dynamic Decision Matrix
        return when {
            // Extreme dynamic range: bright sky/sun + deep shadows, with fast enough shutter
            hasBlownHighlights && hasDeepShadows && !isVerySlowShutter && (dynamicRange >= 0.70f || isOutdoorSky || isHighContrast) -> {
                val shortIndex = evToIndex(DEFAULT_SHORT_EV)
                val longIndex = evToIndex(DEFAULT_LONG_EV)
                val shortSpec = HdrExposureSpec(FrameRole.SHORT_HIGHLIGHT, DEFAULT_SHORT_EV, shortIndex, false)
                val longSpec = HdrExposureSpec(FrameRole.LONG_SHADOW, DEFAULT_LONG_EV, longIndex, false)

                HdrCapturePlan(
                    bracketType = HdrBracketType.THREE_FRAME_FULL,
                    specs = listOf(baseSpec, shortSpec, longSpec),
                    reason = "High dynamic range scene (DR=${String.format("%.2f", dynamicRange)}, highlights & shadows) -> 3-frame HDR",
                    sceneDynamicRange = dynamicRange,
                    hasBlownHighlights = true,
                    hasDeepShadows = true,
                    estimatedIso = iso,
                    estimatedExpTimeNs = expTimeNs
                )
            }

            // Moderate dynamic range: highlight blowout risk (bright sky, windows, specular reflections)
            hasBlownHighlights -> {
                val shortIndex = evToIndex(DEFAULT_SHORT_EV)
                val shortSpec = HdrExposureSpec(FrameRole.SHORT_HIGHLIGHT, DEFAULT_SHORT_EV, shortIndex, false)

                HdrCapturePlan(
                    bracketType = HdrBracketType.TWO_FRAME_HIGHLIGHT,
                    specs = listOf(baseSpec, shortSpec),
                    reason = "Highlight protection (P99=${String.format("%.2f", p99)}) -> 2-frame HDR",
                    sceneDynamicRange = dynamicRange,
                    hasBlownHighlights = true,
                    hasDeepShadows = false,
                    estimatedIso = iso,
                    estimatedExpTimeNs = expTimeNs
                )
            }

            // Low contrast or uniform lighting (overcast daylight, flat indoor lighting, ordinary landscape)
            else -> {
                HdrCapturePlan(
                    bracketType = HdrBracketType.SINGLE_FRAME,
                    specs = listOf(baseSpec),
                    reason = "Uniform natural lighting (DR=${String.format("%.2f", dynamicRange)}) -> single frame pristine capture",
                    sceneDynamicRange = dynamicRange,
                    hasBlownHighlights = false,
                    hasDeepShadows = false,
                    estimatedIso = iso,
                    estimatedExpTimeNs = expTimeNs
                )
            }
        }
    }
}
