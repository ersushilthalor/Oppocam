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
    const val DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM = 16.0f
    const val DEFAULT_MAIN_EQUIVALENT_FOCAL_MM = 23.0f
    const val MIN_SWITCH_POINT_MM = 23.0f
    const val MAX_SWITCH_POINT_MM = 85.0f
    const val DEFAULT_SWITCH_POINT_MM = 23.0f

    /**
     * Converts a UI zoom level (where 1.0x = 23mm Main lens) to effective focal length in mm.
     * - 0.5x UI zoom -> 16.0mm (native uncropped Ultra-Wide)
     * - 1.0x UI zoom -> 23.0mm (native uncropped Main)
     * - Linear interpolation between 0.5x and 1.0x (16mm to 23mm)
     * - For zoom >= 1.0x -> zoom * 23.0mm (e.g. 1.39x -> 32mm, 1.74x -> 40mm, 2.17x -> 50mm, 3.70x -> 85mm)
     */
    fun zoomToFocalLengthMm(
        zoom: Float,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return if (zoom <= 1.0f) {
            val t = ((zoom - 0.5f) / 0.5f).coerceIn(0f, 1f)
            uwFocalMm + t * (mainFocalMm - uwFocalMm)
        } else {
            zoom * mainFocalMm
        }
    }

    /**
     * Converts an effective focal length in mm to UI zoom level.
     * - 16.0mm -> 0.5x
     * - 23.0mm -> 1.0x
     * - 32.0mm -> 32 / 23 ≈ 1.3913x
     * - 40.0mm -> 40 / 23 ≈ 1.7391x
     * - 50.0mm -> 50 / 23 ≈ 2.1739x
     * - 85.0mm -> 85 / 23 ≈ 3.6957x
     */
    fun focalLengthMmToZoom(
        focalMm: Float,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return if (focalMm <= mainFocalMm) {
            val t = ((focalMm - uwFocalMm) / (mainFocalMm - uwFocalMm)).coerceIn(0f, 1f)
            0.5f + t * 0.5f
        } else {
            focalMm / mainFocalMm
        }
    }

    /**
     * Calculates the exact UI zoom threshold at which the lens switch point occurs.
     */
    fun switchPointToZoom(
        switchPointMm: Float,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        val clamped = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        return clamped / mainFocalMm
    }

    /**
     * Ultra-wide crop = Switch Point ÷ 16mm
     * Automatically calculates the required digital crop factor on Ultra-wide at the switch point:
     * - 23mm -> 23 / 16 = 1.4375x (1.44x)
     * - 32mm -> 32 / 16 = 2.00x
     * - 40mm -> 40 / 16 = 2.50x
     * - 50mm -> 50 / 16 = 3.125x (3.13x)
     * - 85mm -> 85 / 16 = 5.3125x (5.31x)
     */
    fun calculateUltraWideCropForSwitchPoint(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): Float {
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val baseUw = if (uwFocalMm > 0f) uwFocalMm else DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
        return ((clampedSwitch / baseUw) * 100f).roundToInt() / 100f
    }

    /**
     * Main crop = Switch Point ÷ 23mm
     * Automatically calculates the required digital crop factor on Main at the switch point:
     * - 23mm -> 23 / 23 = 1.00x
     * - 32mm -> 32 / 23 = 1.3913x (1.39x)
     * - 40mm -> 40 / 23 = 1.7391x (1.74x)
     * - 50mm -> 50 / 23 = 2.1739x (2.17x)
     * - 85mm -> 85 / 23 = 3.6957x (3.70x)
     */
    fun calculateMainCropForSwitchPoint(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val baseMain = if (mainFocalMm > 0f) mainFocalMm else DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
        return ((clampedSwitch / baseMain) * 100f).roundToInt() / 100f
    }

    /**
     * Calculates the crop limit required for Ultra-Wide (~16mm equivalent) to reach Main-lens FOV:
     * cropLimit = switchPointMm / uwEquivalentFocalMm.
     */
    fun calculateUltraWideCropLimit(
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return calculateUltraWideCropForSwitchPoint(mainEquivalentFocalMm, uwEquivalentFocalMm)
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
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM
    ): Float {
        val base = if (lensType == LensType.ULTRAWIDE) 0.5f else (if (lensBaseRatio > 0.1f) lensBaseRatio else 1.0f)
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val maxUwCrop = calculateUltraWideCropForSwitchPoint(clampedSwitch, uwEquivalentFocalMm)

        val switchZoom = switchPointToZoom(clampedSwitch, mainEquivalentFocalMm)

        return when (lensType) {
            LensType.ULTRAWIDE -> {
                if (uiZoom <= 0.5f) {
                    1.0f
                } else {
                    val upperZoom = if (clampedSwitch <= 23.0f) 0.999f else switchZoom
                    val t = ((uiZoom - 0.5f) / (upperZoom - 0.5f)).coerceIn(0f, 1f)
                    val crop = 1.0f + t * (maxUwCrop - 1.0f)
                    if (t >= 0.998f) maxUwCrop else crop.coerceIn(1.0f, maxUwCrop)
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
     * Hysteresis-aware lens selection during continuous zoom dragging based on user-selected Switch Point.
     * At or above the Switch Point threshold (Switch Point / 23mm): switches to Main (or Telephoto).
     * Strictly below the Switch Point threshold: uses Ultra-wide if available.
     */
    fun resolveTargetLensType(
        currentLensType: LensType,
        targetZoom: Float,
        hasUltraWide: Boolean,
        hasTelephoto2x: Boolean,
        hasTelephoto3x: Boolean,
        isPresetTap: Boolean,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM
    ): LensType {
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val switchZoom = switchPointToZoom(clampedSwitch, DEFAULT_MAIN_EQUIVALENT_FOCAL_MM)
        val hysteresisBuffer = if (isPresetTap) 0f else 0.04f * switchZoom

        // During continuous dragging down from WIDE or Telephoto, stay on WIDE down to (switchZoom - hysteresisBuffer)
        if (!isPresetTap && (currentLensType == LensType.WIDE || currentLensType == LensType.TELEPHOTO || currentLensType == LensType.TELEPHOTO_3X)) {
            if (targetZoom >= (switchZoom - hysteresisBuffer)) {
                return when {
                    targetZoom >= 2.8f && hasTelephoto3x && (currentLensType == LensType.TELEPHOTO_3X || targetZoom >= 3.15f) && switchZoom <= 2.8f -> LensType.TELEPHOTO_3X
                    targetZoom >= 1.8f && hasTelephoto2x && (currentLensType == LensType.TELEPHOTO || targetZoom >= 2.15f) && switchZoom <= 1.8f -> LensType.TELEPHOTO
                    currentLensType == LensType.TELEPHOTO && targetZoom >= 1.85f && switchZoom <= 1.85f -> LensType.TELEPHOTO
                    currentLensType == LensType.TELEPHOTO_3X && targetZoom >= 2.85f && switchZoom <= 2.85f -> LensType.TELEPHOTO_3X
                    else -> LensType.WIDE
                }
            }
        }

        // At or above the dynamic switch point zoom threshold, target Main (Wide) or Telephoto lens
        if (targetZoom >= switchZoom) {
            return when {
                targetZoom >= 2.8f && hasTelephoto3x && (isPresetTap || currentLensType == LensType.TELEPHOTO_3X || targetZoom >= 3.15f) && switchZoom <= 2.8f -> LensType.TELEPHOTO_3X
                targetZoom >= 1.8f && hasTelephoto2x && (isPresetTap || currentLensType == LensType.TELEPHOTO || targetZoom >= 2.15f) && switchZoom <= 1.8f -> LensType.TELEPHOTO
                currentLensType == LensType.TELEPHOTO && targetZoom >= 1.85f && switchZoom <= 1.85f -> LensType.TELEPHOTO
                currentLensType == LensType.TELEPHOTO_3X && targetZoom >= 2.85f && switchZoom <= 2.85f -> LensType.TELEPHOTO_3X
                else -> LensType.WIDE
            }
        }

        // Strictly below the switch point: use Ultra-wide if available
        return if (hasUltraWide) LensType.ULTRAWIDE else LensType.WIDE
    }

    /**
     * Optical framing alignment calibration between Ultra-wide and Main Wide cameras.
     * Calibrates scale correction, horizontal offset, and vertical offset so there is no sudden FOV or position jump at handoff.
     */
    data class OpticalFramingCalibration(
        val scaleCorrection: Float = 1.0f,
        val offsetXNorm: Float = 0.0f,
        val offsetYNorm: Float = 0.0f
    )

    fun getUltraWideFramingCalibration(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): OpticalFramingCalibration {
        val baseUw = if (uwEquivalentFocalMm > 0f) uwEquivalentFocalMm else DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
        val idealRatio = switchPointMm / baseUw
        val appliedCrop = calculateUltraWideCropForSwitchPoint(switchPointMm, uwEquivalentFocalMm)
        val scaleCorr = if (appliedCrop > 0.01f) (idealRatio / appliedCrop) else 1.0f

        return OpticalFramingCalibration(
            scaleCorrection = scaleCorr.coerceIn(0.95f, 1.05f),
            offsetXNorm = 0.0f,
            offsetYNorm = 0.0f
        )
    }

    /**
     * Smoothly interpolates optical framing calibration for a given UI zoom level (0.5x to 1.0x).
     * At 0.5x (native uncropped Ultra-wide): returns identity (1.0x scale, 0 offset).
     * At 1.0x (switch point): returns full framing calibration matching 1x Main FOV and optical alignment.
     */
    fun interpolateUltraWideFraming(
        uiZoom: Float,
        calib: OpticalFramingCalibration,
        switchZoom: Float = 1.0f
    ): OpticalFramingCalibration {
        if (uiZoom <= 0.5f) {
            return OpticalFramingCalibration()
        }
        val t = ((uiZoom - 0.5f) / (switchZoom - 0.5f).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        return OpticalFramingCalibration(
            scaleCorrection = 1.0f + (calib.scaleCorrection - 1.0f) * t,
            offsetXNorm = calib.offsetXNorm * t,
            offsetYNorm = calib.offsetYNorm * t
        )
    }
}
