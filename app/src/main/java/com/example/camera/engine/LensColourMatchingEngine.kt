package com.example.camera.engine

import android.content.Context
import android.graphics.ColorMatrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Log
import android.util.Range
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

private const val TAG = "LensColourMatching"

/**
 * Lens-Specific Color, White-Balance, Exposure, Brightness, and Noise Matching Engine.
 *
 * Provides seamless 0.5x Ultra-Wide <-> 1.0x Main camera lens switching by minimizing
 * visible color shifts, white balance variations, exposure/brightness jumps, and noise differences.
 *
 * Controlled entirely by the single "Correct Colour Shift" toggle in Settings:
 * - When ON: Enables lens-matching system, applying hardware-calibrated color correction,
 *   exposure synchronization (aperture-aware), white-balance matching, and noise reduction adjustments.
 * - When OFF: Completely disabled. Restores each lens's normal independent Camera2 processing
 *   with zero background processing overhead.
 */
class LensColourMatchingEngine(private val context: Context) {

    private val isEnabledState = AtomicBoolean(true)
    var isEnabled: Boolean
        get() = isEnabledState.get()
        set(value) {
            val changed = isEnabledState.getAndSet(value) != value
            if (changed) {
                if (!value) {
                    clearTelemetry()
                }
                Log.i(TAG, "Correct Colour Shift feature is now ${if (value) "ENABLED" else "DISABLED"}")
            }
        }

    /**
     * Hardware calibration profile derived dynamically from CameraCharacteristics for each lens.
     */
    data class LensCalibrationProfile(
        val cameraId: String,
        val lensType: LensType,
        val nominalAperture: Float,
        val isoRange: Range<Int>?,
        val exposureTimeRange: Range<Long>?,
        val aeCompensationRange: Range<Int>?,
        val aeCompensationStep: Float,
        val availableNoiseReductionModes: List<Int>,
        val availableEdgeModes: List<Int>,
        val colorFilterArrangement: Int?,
        val colorTransform1: ColorSpaceTransform?,
        val forwardMatrix1: ColorSpaceTransform?,
        val calibrationTransform1: ColorSpaceTransform?
    )

    /**
     * Real-time 3A telemetry captured from TotalCaptureResult.
     */
    data class LensTelemetry(
        val lensType: LensType,
        val timestampMs: Long = System.currentTimeMillis(),
        val iso: Int = 100,
        val exposureTimeNs: Long = 33_333_333L,
        val aeCompensation: Int = 0,
        val aeState: Int = CaptureResult.CONTROL_AE_STATE_CONVERGED,
        val awbMode: Int = CaptureResult.CONTROL_AWB_MODE_AUTO,
        val awbState: Int = CaptureResult.CONTROL_AWB_STATE_CONVERGED,
        val rGain: Float = 1.8f,
        val gGain: Float = 1.0f,
        val bGain: Float = 1.6f,
        val colorTransform: FloatArray? = null,
        val estimatedLux: Float = 100f
    )

    private val profiles = ConcurrentHashMap<LensType, LensCalibrationProfile>()
    private val telemetryMap = ConcurrentHashMap<LensType, LensTelemetry>()

    // Short-lived AWB lock counter after lens handoff to eliminate multi-frame flashing
    private val awbTransitionLockFrames = AtomicInteger(0)

    /**
     * Inspects and registers sensor characteristics for a given lens.
     */
    fun registerLensCharacteristics(lens: LensInfo, chars: CameraCharacteristics?) {
        if (chars == null) return
        try {
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            val nominalAperture = if (apertures != null && apertures.isNotEmpty()) {
                apertures[0]
            } else {
                if (lens.lensType == LensType.ULTRAWIDE) 2.2f else 1.8f
            }

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val aeStepRational = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val aeStep = aeStepRational?.toFloat() ?: 0.3333f

            val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList() ?: emptyList()
            val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList() ?: emptyList()
            val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)

            val calib1 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)
            val colorTrans1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
            val fwdMat1 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)

            val profile = LensCalibrationProfile(
                cameraId = lens.cameraId,
                lensType = lens.lensType,
                nominalAperture = nominalAperture,
                isoRange = isoRange,
                exposureTimeRange = exposureRange,
                aeCompensationRange = aeCompRange,
                aeCompensationStep = if (aeStep > 0f) aeStep else 0.3333f,
                availableNoiseReductionModes = nrModes,
                availableEdgeModes = edgeModes,
                colorFilterArrangement = cfa,
                colorTransform1 = colorTrans1,
                forwardMatrix1 = fwdMat1,
                calibrationTransform1 = calib1
            )
            profiles[lens.lensType] = profile
            Log.d(TAG, "Registered calibration profile for ${lens.lensType} (ID=${lens.cameraId}, aperture=f/$nominalAperture, step=$aeStep)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed registering lens characteristics for ${lens.lensType}", t)
        }
    }

    /**
     * Updates real-time 3A telemetry on every frame completed by the active capture session.
     * When Correct Colour Shift is OFF, this returns immediately without any processing.
     */
    fun onCaptureFrame(result: TotalCaptureResult, lens: LensInfo?) {
        if (!isEnabled || lens == null) return
        val lensType = lens.lensType
        if (lensType != LensType.WIDE && lensType != LensType.ULTRAWIDE) return

        try {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val expTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_333_333L
            val aeComp = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) ?: 0
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: CaptureResult.CONTROL_AE_STATE_CONVERGED
            val awbMode = result.get(CaptureResult.CONTROL_AWB_MODE) ?: CaptureResult.CONTROL_AWB_MODE_AUTO
            val awbState = result.get(CaptureResult.CONTROL_AWB_STATE) ?: CaptureResult.CONTROL_AWB_STATE_CONVERGED

            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val rawR = gains?.red ?: 1.8f
            val rawG = if (gains != null) (gains.greenEven + gains.greenOdd) * 0.5f else 1.0f
            val rawB = gains?.blue ?: 1.6f

            val colorTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { transform ->
                FloatArray(9) { i ->
                    val r = transform.getElement(i % 3, i / 3)
                    r.toFloat()
                }
            }

            // Estimate scene lux from ISO and exposure time
            val aperture = profiles[lensType]?.nominalAperture ?: (if (lensType == LensType.ULTRAWIDE) 2.2f else 1.8f)
            val expTimeSec = expTimeNs.toDouble() / 1_000_000_000.0
            val estLux = if (iso > 0 && expTimeSec > 0) {
                ((250.0 * aperture * aperture) / (iso * expTimeSec)).toFloat().coerceIn(0.1f, 100_000f)
            } else 100f

            // Smooth telemetry using Exponential Moving Average to prevent flicker
            val prev = telemetryMap[lensType]
            val smoothedR = if (prev != null) prev.rGain * 0.75f + rawR * 0.25f else rawR
            val smoothedG = if (prev != null) prev.gGain * 0.75f + rawG * 0.25f else rawG
            val smoothedB = if (prev != null) prev.bGain * 0.75f + rawB * 0.25f else rawB
            val smoothedLux = if (prev != null) prev.estimatedLux * 0.8f + estLux * 0.2f else estLux

            telemetryMap[lensType] = LensTelemetry(
                lensType = lensType,
                timestampMs = System.currentTimeMillis(),
                iso = iso,
                exposureTimeNs = expTimeNs,
                aeCompensation = aeComp,
                aeState = aeState,
                awbMode = awbMode,
                awbState = awbState,
                rGain = smoothedR,
                gGain = smoothedG,
                bGain = smoothedB,
                colorTransform = colorTransform,
                estimatedLux = smoothedLux
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Error tracking capture frame telemetry", t)
        }
    }

    /**
     * Calculates the physical light gathering ratio between two camera lenses based on their actual apertures.
     * Area is inversely proportional to f-number squared: Light Gathering ~ 1 / (N^2).
     * Ultra-wide (f/2.2) receives less photon density than Main (f/1.8), requiring (2.2 / 1.8)^2 ≈ 1.49x exposure.
     */
    fun getApertureLightRatio(targetLensType: LensType, sourceLensType: LensType): Float {
        val targetProfile = profiles[targetLensType]
        val sourceProfile = profiles[sourceLensType]

        val targetAp = targetProfile?.nominalAperture ?: (if (targetLensType == LensType.ULTRAWIDE) 2.2f else 1.8f)
        val sourceAp = sourceProfile?.nominalAperture ?: (if (sourceLensType == LensType.ULTRAWIDE) 2.2f else 1.8f)

        if (targetAp <= 0f || sourceAp <= 0f) return 1.0f
        return (targetAp / sourceAp).pow(2)
    }

    /**
     * Prepares the target camera's CaptureRequest.Builder before or during lens switching,
     * harmonizing exposure, white-balance, and noise reduction across sensors.
     */
    fun prepareTargetCaptureRequest(
        targetBuilder: CaptureRequest.Builder,
        targetLens: LensInfo,
        sourceLens: LensInfo?
    ) {
        if (!isEnabled || sourceLens == null || targetLens.lensType == sourceLens.lensType) return
        val targetType = targetLens.lensType
        val sourceType = sourceLens.lensType
        if ((targetType != LensType.WIDE && targetType != LensType.ULTRAWIDE) ||
            (sourceType != LensType.WIDE && sourceType != LensType.ULTRAWIDE)
        ) return

        val sourceTelemetry = telemetryMap[sourceType]
        val targetProfile = profiles[targetType]

        try {
            // 1. Exposure and Brightness Matching:
            // Calculate required light compensation based on actual hardware apertures
            val lightRatio = getApertureLightRatio(targetType, sourceType)
            val evDiffStops = log2(lightRatio)

            if (targetProfile != null && abs(evDiffStops) > 0.05f) {
                val step = targetProfile.aeCompensationStep
                val baseComp = sourceTelemetry?.aeCompensation ?: 0
                val deltaSteps = (evDiffStops / step).roundToInt()
                val targetComp = baseComp + deltaSteps
                val clampedComp = targetProfile.aeCompensationRange?.let { range ->
                    targetComp.coerceIn(range.lower, range.upper)
                } ?: targetComp

                targetBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedComp)
                Log.d(TAG, "[EXPOSURE_MATCH] $sourceType -> $targetType: compensated EV steps from $baseComp to $clampedComp (aperture ratio=$lightRatio)")
            }

            // 2. White Balance & Color Response Matching:
            if (sourceTelemetry != null) {
                targetBuilder.set(CaptureRequest.CONTROL_AWB_MODE, sourceTelemetry.awbMode)

                // Match white balance gains when device allows manual/locked color correction
                if (sourceTelemetry.rGain > 0f && sourceTelemetry.bGain > 0f) {
                    val rggb = RggbChannelVector(
                        sourceTelemetry.rGain,
                        sourceTelemetry.gGain,
                        sourceTelemetry.gGain,
                        sourceTelemetry.bGain
                    )
                    try {
                        targetBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggb)
                    } catch (_: Throwable) {}
                }

                // Temporary transition lock for 2 frames to prevent visible AWB flash during handoff
                awbTransitionLockFrames.set(2)
                try {
                    targetBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                } catch (_: Throwable) {}
            }

            // 3. Noise Matching:
            applyNoiseMatchingToBuilder(targetBuilder, targetType, sourceTelemetry?.estimatedLux ?: 100f)

        } catch (t: Throwable) {
            Log.w(TAG, "Failed preparing target capture request for ${targetLens.lensType}", t)
        }
    }

    /**
     * Applies continuous synchronized matching to standby or active preview request builders.
     */
    fun applySynchronizedMatching(
        builder: CaptureRequest.Builder,
        targetLens: LensInfo,
        activeLens: LensInfo?
    ) {
        if (!isEnabled || activeLens == null || targetLens.lensType == activeLens.lensType) return
        val targetType = targetLens.lensType
        val activeType = activeLens.lensType
        if ((targetType != LensType.WIDE && targetType != LensType.ULTRAWIDE) ||
            (activeType != LensType.WIDE && activeType != LensType.ULTRAWIDE)
        ) return

        val activeTelemetry = telemetryMap[activeType] ?: return
        val targetProfile = profiles[targetType]

        try {
            // Synchronize Exposure:
            val lightRatio = getApertureLightRatio(targetType, activeType)
            val evDiffStops = log2(lightRatio)
            if (targetProfile != null && abs(evDiffStops) > 0.05f) {
                val step = targetProfile.aeCompensationStep
                val baseComp = activeTelemetry.aeCompensation
                val deltaSteps = (evDiffStops / step).roundToInt()
                val targetComp = baseComp + deltaSteps
                val clampedComp = targetProfile.aeCompensationRange?.let { range ->
                    targetComp.coerceIn(range.lower, range.upper)
                } ?: targetComp
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedComp)
            }

            // Synchronize White Balance:
            builder.set(CaptureRequest.CONTROL_AWB_MODE, activeTelemetry.awbMode)
            if (activeTelemetry.rGain > 0f && activeTelemetry.bGain > 0f) {
                try {
                    builder.set(
                        CaptureRequest.COLOR_CORRECTION_GAINS,
                        RggbChannelVector(
                            activeTelemetry.rGain,
                            activeTelemetry.gGain,
                            activeTelemetry.gGain,
                            activeTelemetry.bGain
                        )
                    )
                } catch (_: Throwable) {}
            }

            // Handle transition lock countdown
            if (awbTransitionLockFrames.get() > 0) {
                val remaining = awbTransitionLockFrames.decrementAndGet()
                if (remaining <= 0) {
                    try {
                        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                    } catch (_: Throwable) {}
                }
            }

            // Apply lens-specific noise matching
            applyNoiseMatchingToBuilder(builder, targetType, activeTelemetry.estimatedLux)

        } catch (t: Throwable) {
            Log.w(TAG, "Error applying synchronized matching to ${targetLens.lensType}", t)
        }
    }

    /**
     * Configures lens-specific noise reduction and edge sharpening to balance noise perception.
     * Ultra-wide lenses have smaller sensors/pixels and exhibit higher noise in low/medium light;
     * applying tailored noise reduction mode prevents coarse noise jumps without softening fine detail.
     */
    private fun applyNoiseMatchingToBuilder(
        builder: CaptureRequest.Builder,
        lensType: LensType,
        estimatedLux: Float
    ) {
        val profile = profiles[lensType] ?: return
        val supportedNr = profile.availableNoiseReductionModes
        val supportedEdge = profile.availableEdgeModes

        val isLowOrIndoorLight = estimatedLux < 150f

        if (lensType == LensType.ULTRAWIDE) {
            if (isLowOrIndoorLight) {
                // In low/indoor light, Ultra-Wide uses HIGH_QUALITY noise reduction to suppress chroma/luma grain
                if (supportedNr.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                } else if (supportedNr.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)) {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                }
                // Avoid harsh sharpening on noisy pixels
                if (supportedEdge.contains(CaptureRequest.EDGE_MODE_FAST)) {
                    builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                }
            } else {
                // In bright daylight, keep standard balanced noise reduction
                if (supportedNr.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)) {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                }
            }
        } else {
            // Main camera has inherently higher SNR and larger physical sensor area
            if (supportedNr.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            }
        }
    }

    /**
     * Computes the dynamic render-level ColorMatrix applied to the viewfinder TextureView
     * to eliminate residual color temperature, tint, saturation, and contrast differences.
     *
     * Returns null when:
     * - "Correct Colour Shift" is OFF.
     * - Telemetry is not yet converged.
     * - The lens does not require cross-lens matching.
     */
    fun computeRenderColorMatrix(displayedLensType: LensType): ColorMatrix? {
        if (!isEnabled) return null
        if (displayedLensType != LensType.WIDE && displayedLensType != LensType.ULTRAWIDE) return null

        val mainTelemetry = telemetryMap[LensType.WIDE]
        val uwTelemetry = telemetryMap[LensType.ULTRAWIDE]

        if (mainTelemetry == null || uwTelemetry == null) return null

        // Calculate relative white-balance channel ratios between the two sensors
        val ratioR = (mainTelemetry.rGain / uwTelemetry.rGain).coerceIn(0.70f, 1.45f)
        val ratioG = (mainTelemetry.gGain / uwTelemetry.gGain).coerceIn(0.80f, 1.25f)
        val ratioB = (mainTelemetry.bGain / uwTelemetry.bGain).coerceIn(0.70f, 1.45f)

        // When viewing Ultra-Wide: adjust toward Main's converged color temperature and tint
        // When viewing Main: adjust toward identity or balanced reference
        val (scaleR, scaleG, scaleB) = if (displayedLensType == LensType.ULTRAWIDE) {
            Triple(ratioR, ratioG, ratioB)
        } else {
            Triple(1.0f, 1.0f, 1.0f)
        }

        // Slight contrast and gamma equalization for flare differences in wide angle optics
        val matrix = ColorMatrix()
        if (displayedLensType == LensType.ULTRAWIDE) {
            val contrast = 1.03f
            val translate = (1f - contrast) * 128f

            val colorArray = floatArrayOf(
                scaleR * contrast, 0f, 0f, 0f, translate,
                0f, scaleG * contrast, 0f, 0f, translate,
                0f, 0f, scaleB * contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
            matrix.set(colorArray)
        } else {
            return null // Main camera serves as golden baseline
        }

        return matrix
    }

    /**
     * Clears all stored telemetry when feature is disabled or camera is closed.
     */
    fun clearTelemetry() {
        telemetryMap.clear()
        awbTransitionLockFrames.set(0)
    }
}
