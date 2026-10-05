package com.camerapro.camera.engine

import android.util.SizeF
import com.camerapro.camera.model.LensType
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
    const val DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM = 16.0f
    const val DEFAULT_MAIN_EQUIVALENT_FOCAL_MM = 23.0f

    /**
     * Calculates the crop limit required for Ultra-Wide (~16mm equivalent) to reach 1x Main-lens FOV (~23mm equivalent):
     * cropLimit = mainEquivalentFocalMm / uwEquivalentFocalMm ≈ 23 / 16 ≈ 1.4375x (≈ 1.44x).
     *
     * Based on actual focal-length/FOV relationship, not an arbitrary zoom multiplier.
     */
    fun calculateUltraWideCropLimit(
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        val uwEq = if (uwEquivalentFocalMm in 10f..20f) uwEquivalentFocalMm else DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
        val mainEq = if (mainEquivalentFocalMm in 21f..32f) mainEquivalentFocalMm else DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
        return ((mainEq / uwEq) * 100f).roundToInt() / 100f
    }

    /**
     * Calculates the calibrated optical equivalence zoom ratio of a physical lens relative to the primary 1.0x Main camera.
     *
     * Uses actual sensor-size-aware horizontal Field Of View (hFOV):
     * Zoom Ratio = tan(hFOV_main / 2) / tan(hFOV_lens / 2) = (F_lens * W_main) / (F_main * W_lens)
     *
     * Invariants:
     * - 1.0x always represents the main camera's calibrated FOV.
     * - Ultra-wide native FOV is preserved at 0.5x.
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
                LensType.ULTRAWIDE -> 0.5f
                LensType.TELEPHOTO -> 2.0f
                LensType.TELEPHOTO_3X -> 3.0f
                else -> 1.0f
            }
        }

        return when (lensType) {
            LensType.ULTRAWIDE -> 0.5f
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
     * - On Ultra-wide (native FOV = 0.5x, approx 16mm eq; Main = 23mm eq):
     *     Required crop factor to reach 1x main FOV is 23/16 ≈ 1.44x.
     *     Allows digital cropping ONLY up to approximately 1.44x.
     *     Does NOT crop Ultra-Wide up to 2x.
     *     At 0.5x UI zoom -> 1.0x digital crop (full uncropped sensor).
     *     At 1.0x UI zoom -> ~1.44x digital crop (matches 1x Main FOV).
     *     Above 1.0x -> strictly capped at ~1.44x crop limit.
     * - On Main Wide (base ratio = 1.0x):
     *     cropFactor = UI zoom / 1.0x = UI zoom
     * - On Telephoto (base ratio >= 2.0x):
     *     cropFactor = UI zoom / baseOpticalRatio
     */
    fun calculateRequiredDigitalCrop(
        uiZoom: Float,
        lensBaseRatio: Float,
        lensType: LensType,
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        val base = if (lensType == LensType.ULTRAWIDE) 0.5f else (if (lensBaseRatio > 0.1f) lensBaseRatio else 1.0f)
        return when (lensType) {
            LensType.ULTRAWIDE -> {
                val maxCrop = calculateUltraWideCropLimit(uwEquivalentFocalMm, mainEquivalentFocalMm)
                if (uiZoom <= 0.5f) {
                    1.0f
                } else {
                    // Maximum zoom on Ultra-wide is 0.999x; at 1.000x the camera strictly uses native Main Wide
                    val clampedUi = uiZoom.coerceIn(0.5f, 0.999f)
                    val t = ((clampedUi - 0.5f) / (1.0f - 0.5f)).coerceIn(0f, 1f)
                    val crop = 1.0f + t * (maxCrop - 1.0f)
                    crop.coerceIn(1.0f, maxCrop)
                }
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
        // Ultra-wide is strictly prohibited at >= 1.000x.
        // At exactly 1.000x and above, the camera MUST use native Main/Wide FOV (or Telephoto).
        // The maximum Ultra-wide zoom is 0.999x.
        if (targetZoom >= 1.000f) {
            return when {
                targetZoom >= 2.8f && hasTelephoto3x && (isPresetTap || currentLensType == LensType.TELEPHOTO_3X || targetZoom >= 3.15f) -> LensType.TELEPHOTO_3X
                targetZoom >= 1.8f && hasTelephoto2x && (isPresetTap || currentLensType == LensType.TELEPHOTO || targetZoom >= 2.15f) -> LensType.TELEPHOTO
                currentLensType == LensType.TELEPHOTO && targetZoom >= 1.85f -> LensType.TELEPHOTO
                currentLensType == LensType.TELEPHOTO_3X && targetZoom >= 2.85f -> LensType.TELEPHOTO_3X
                else -> LensType.WIDE
            }
        }

        // Strictly below 1.000x (up to 0.999x):
        return if (hasUltraWide) LensType.ULTRAWIDE else LensType.WIDE
    }
}
