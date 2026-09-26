package com.example.camera.engine

import android.util.SizeF
import com.example.camera.model.LensType
import kotlin.math.*

/**
 * Optical and FOV-calibrated camera lens calculations.
 *
 * Replaces naive focal length ratios with sensor-aware Field Of View (FOV) calibration:
 * - 1.0x is always the calibrated FOV of the primary Main Wide camera.
 * - Ultra-wide optical ratio is derived from actual horizontal FOV.
 * - Minimum Ultra-wide zoom is strictly wider than 1.0x Main.
 * - At 0.9x, Ultra-wide is never more zoomed in than 1.0x Main.
 * - Dynamic crop mapping converts UI zoom to exact physical sensor crop.
 * - Hysteresis thresholds prevent jittery lens switching during continuous user gestures.
 */
object CameraOpticalCalibration {

    /**
     * Calculates horizontal Field of View in degrees from physical sensor width and focal length.
     * hFOV = 2 * atan(sensorWidth / (2 * focalLength)) * (180 / PI)
     */
    fun calculateHorizontalFovDegrees(sensorWidthMm: Float, focalLengthMm: Float): Float {
        if (sensorWidthMm <= 0f || focalLengthMm <= 0f) return 0f
        return (2.0 * atan(sensorWidthMm.toDouble() / (2.0 * focalLengthMm.toDouble())) * (180.0 / Math.PI)).toFloat()
    }

    /**
     * Calculates vertical Field of View in degrees from physical sensor height and focal length.
     */
    fun calculateVerticalFovDegrees(sensorHeightMm: Float, focalLengthMm: Float): Float {
        if (sensorHeightMm <= 0f || focalLengthMm <= 0f) return 0f
        return (2.0 * atan(sensorHeightMm.toDouble() / (2.0 * focalLengthMm.toDouble())) * (180.0 / Math.PI)).toFloat()
    }

    /**
     * Calculates the calibrated optical equivalence zoom ratio of a physical lens relative to the primary 1.0x Main camera.
     *
     * Uses actual sensor-size-aware horizontal Field Of View (hFOV):
     * Zoom Ratio = tan(hFOV_main / 2) / tan(hFOV_lens / 2) = (F_lens * W_main) / (F_main * W_lens)
     *
     * Invariants:
     * - 1.0x always represents the main camera's calibrated FOV.
     * - Ultra-wide's minimum zoom is strictly wider than 1.0x main (< 1.0f).
     * - At 0.9x, Ultra-wide must NEVER look more zoomed-in than 1.0x main.
     */
    fun calculateCalibratedOpticalRatio(
        lensFocalLengthMm: Float,
        lensSensorWidthMm: Float,
        mainFocalLengthMm: Float,
        mainSensorWidthMm: Float,
        lensType: LensType,
        logicalMinZoomRatio: Float? = null
    ): Float {
        if (lensType == LensType.WIDE) return 1.0f

        val hasValidMain = mainSensorWidthMm > 0f && mainFocalLengthMm > 0f
        val hasValidLens = lensSensorWidthMm > 0f && lensFocalLengthMm > 0f

        val fovBasedRatio = if (hasValidMain && hasValidLens) {
            val tanHalfMain = mainSensorWidthMm.toDouble() / (2.0 * mainFocalLengthMm.toDouble())
            val tanHalfLens = lensSensorWidthMm.toDouble() / (2.0 * lensFocalLengthMm.toDouble())
            if (tanHalfLens > 0.0) {
                (tanHalfMain / tanHalfLens).toFloat()
            } else 1.0f
        } else if (mainFocalLengthMm > 0f && lensFocalLengthMm > 0f) {
            lensFocalLengthMm / mainFocalLengthMm
        } else {
            when (lensType) {
                LensType.ULTRAWIDE -> logicalMinZoomRatio ?: 0.5f
                LensType.TELEPHOTO -> 2.0f
                LensType.TELEPHOTO_3X -> 3.0f
                else -> 1.0f
            }
        }

        return when (lensType) {
            LensType.ULTRAWIDE -> {
                val rawRatio = if (logicalMinZoomRatio != null && logicalMinZoomRatio in 0.3f..0.95f) {
                    min(fovBasedRatio, logicalMinZoomRatio)
                } else {
                    fovBasedRatio
                }
                // Ultra-wide must be strictly wider than 1.0x main (< 1.0f)
                val rounded = (rawRatio * 100f).roundToInt() / 100f
                rounded.coerceIn(0.35f, 0.85f)
            }
            LensType.TELEPHOTO -> {
                val rounded = (fovBasedRatio * 10f).roundToInt() / 10f
                rounded.coerceAtLeast(1.8f)
            }
            LensType.TELEPHOTO_3X -> {
                val rounded = (fovBasedRatio * 10f).roundToInt() / 10f
                rounded.coerceAtLeast(2.8f)
            }
            else -> 1.0f
        }
    }

    /**
     * Calculates the required digital crop factor for a given UI zoom level on the active lens.
     *
     * UI zoom → physical lens → required digital crop:
     * - On Ultra-wide (base ratio < 1.0x):
     *     cropFactor = UI zoom / baseOpticalRatio
     *     At 0.5x (if base=0.5x) -> cropFactor = 1.0x (full uncropped sensor)
     *     At 0.7x -> cropFactor = 1.4x (small crop)
     *     At 0.9x -> cropFactor = 1.8x (< 2.0x, so strictly wider than 1.0x Main)
     *     At 1.0x -> cropFactor = 2.0x (matches 1.0x Main FOV exactly)
     * - On Main Wide (base ratio = 1.0x):
     *     cropFactor = UI zoom / 1.0x = UI zoom
     * - On Telephoto (base ratio >= 2.0x):
     *     cropFactor = UI zoom / baseOpticalRatio
     */
    fun calculateRequiredDigitalCrop(
        uiZoom: Float,
        lensBaseRatio: Float,
        lensType: LensType
    ): Float {
        val base = if (lensBaseRatio > 0.1f) lensBaseRatio else 1.0f
        return when (lensType) {
            LensType.ULTRAWIDE -> {
                (uiZoom / base).coerceAtLeast(1.0f)
            }
            LensType.WIDE -> {
                uiZoom.coerceAtLeast(1.0f)
            }
            LensType.TELEPHOTO, LensType.TELEPHOTO_3X -> {
                (uiZoom / base).coerceAtLeast(1.0f)
            }
            else -> {
                uiZoom.coerceAtLeast(1.0f)
            }
        }
    }

    /**
     * Hysteresis-aware lens selection during continuous zoom dragging.
     * Prevents rapid back-and-forth lens switching when dragging near boundary thresholds.
     */
    fun resolveTargetLensType(
        currentLensType: LensType,
        targetZoom: Float,
        hasUltraWide: Boolean,
        hasTelephoto2x: Boolean,
        hasTelephoto3x: Boolean,
        isPresetTap: Boolean
    ): LensType {
        if (isPresetTap) {
            return when {
                targetZoom < 0.95f && hasUltraWide -> LensType.ULTRAWIDE
                targetZoom >= 2.8f && hasTelephoto3x -> LensType.TELEPHOTO_3X
                targetZoom >= 1.8f && hasTelephoto2x -> LensType.TELEPHOTO
                else -> LensType.WIDE
            }
        }

        // Continuous dragging with hysteresis thresholds:
        return when (currentLensType) {
            LensType.ULTRAWIDE -> {
                // Transition cleanly at 1.0x where Ultra-Wide FOV matches uncropped 1x Main
                if (targetZoom >= 1.0f) LensType.WIDE else LensType.ULTRAWIDE
            }
            LensType.WIDE -> {
                when {
                    // Transition to Ultra-Wide below 1.0x since physical Main sensor cannot zoom wider than 1x
                    targetZoom < 1.0f && hasUltraWide -> LensType.ULTRAWIDE
                    // Main stays active up to 2.15x before switching to 2x Tele
                    targetZoom >= 2.15f && hasTelephoto2x -> LensType.TELEPHOTO
                    // Main stays active up to 3.15x before switching to 3x Tele
                    targetZoom >= 3.15f && hasTelephoto3x && !hasTelephoto2x -> LensType.TELEPHOTO_3X
                    else -> LensType.WIDE
                }
            }
            LensType.TELEPHOTO -> {
                when {
                    // Tele stays active down to 1.85x before falling back to Main
                    targetZoom < 1.85f -> LensType.WIDE
                    targetZoom >= 3.15f && hasTelephoto3x -> LensType.TELEPHOTO_3X
                    else -> LensType.TELEPHOTO
                }
            }
            LensType.TELEPHOTO_3X -> {
                // 3x Tele stays active down to 2.85x
                if (targetZoom < 2.85f) {
                    if (hasTelephoto2x && targetZoom >= 1.85f) LensType.TELEPHOTO else LensType.WIDE
                } else {
                    LensType.TELEPHOTO_3X
                }
            }
            else -> currentLensType
        }
    }
}
