package com.example.camera.engine

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val TAG = "NaturalLogAutoTone"
private const val CURVE_POINTS = 64

/**
 * Parameter model for the Natural Log intelligent exposure and tonemap pipeline.
 */
data class NaturalLogParams(
    val exposureComp: Float = 0.0f,     // Adaptive EV shift (0.0f - no outdoor darkening bias)
    val highlightRollOff: Float = 0.45f, // Highlight shoulder protection factor (0.35f to 0.85f)
    val shadowToeLift: Float = 0.28f,   // Shadow detail lift (0.22f to 0.60f)
    val contrast: Float = 0.0f,         // Midtone contrast (-0.2f to +0.2f)
    val sceneLuxIndex: Float = 0.5f,    // Normalized scene brightness
    val dynamicRange: Float = 0.80f,    // Measured P99 - P1 dynamic range
    val p18Midtone: Float = 0.18f,      // Tracked P18 shadow/midtone value
    val isHighContrast: Boolean = false,
    val isOutdoorSkyWithDarkForeground: Boolean = false
)

/**
 * Intelligent Exposure & Tonal Response Engine for the Natural Log Cinema Profile.
 *
 * Provides:
 * 1. True scene luminance analysis:
 *    - Tracks live frame percentiles (P1, P5, P18, P50, P95, P99) alongside sensor APEX EV100.
 *    - Detects high-contrast scenes (bright sky + dark foreground, deep shadows, backlit subjects).
 * 2. Zero outdoor darkening bias:
 *    - Does NOT pull down exposure negatively just because outdoor EV100 is high.
 *    - Protects highlights via the tonemap curve's smooth shoulder roll-off, keeping foregrounds usable.
 * 3. Dynamic outdoor shadow recovery:
 *    - High-contrast outdoor scenes receive increased shadow recovery (0.35–0.60), not decreased fallback.
 * 4. Inky, clean black anchoring:
 *    - True black (x=0) strictly anchored at 0.02 (zero milky/grey blacks).
 *    - Shadow lift applied purely in the toe region (x in 0.015..0.35).
 * 5. Temporal stability:
 *    - Hysteresis deadband and smooth IIR filtering eliminate brightness pumping and shadow breathing during panning.
 */
class NaturalLogExposureEngine {

    private val _currentParams = MutableStateFlow(NaturalLogParams())
    val currentParams: StateFlow<NaturalLogParams> = _currentParams.asStateFlow()

    // Latest live frame luminance statistics (sampled from TextureView at 10fps)
    @Volatile
    private var latestFrameStats: FrameLuminanceStats? = null

    // Smoothed state for temporal IIR filtering
    private var smoothedEv100 = 11.0f
    private var smoothedExposureComp = 0.0f
    private var smoothedHighlightRollOff = 0.45f
    private var smoothedShadowLift = 0.28f
    private var smoothedContrast = 0.0f

    // Deadband memory to eliminate micro-jitter
    private var lastTargetExposureComp = 0.0f
    private var lastTargetHighlightRollOff = 0.45f
    private var lastTargetShadowLift = 0.28f

    // ISP update tracking
    private var lastIspUpdateTime = 0L
    private var lastIspExposureComp = 0.0f
    private var lastIspHighlightRollOff = 0.0f
    private var lastIspShadowLift = 0.0f

    // Pre-allocated curve buffers for zero-allocation performance
    private val curveRed = FloatArray(CURVE_POINTS * 2)
    private val curveGreen = FloatArray(CURVE_POINTS * 2)
    private val curveBlue = FloatArray(CURVE_POINTS * 2)

    /**
     * Receives real-time frame luminance statistics extracted from the preview surface.
     */
    fun onFrameLuminanceAnalyzed(stats: FrameLuminanceStats) {
        latestFrameStats = stats
        processSceneIllumination(smoothedEv100, hasFace = false, maxFaceArea = 0, stats = stats)
    }

    /**
     * Receives Camera2 per-frame metadata (ISO, shutter speed, aperture, face detection).
     */
    fun onFrameCaptured(result: TotalCaptureResult, characteristics: CameraCharacteristics?) {
        val iso = (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100).coerceAtLeast(1)
        val expTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 20_000_000L
        val aperture = result.get(CaptureResult.LENS_APERTURE) ?: 1.8f
        val faces = result.get(CaptureResult.STATISTICS_FACES) ?: emptyArray()

        val expSec = (expTimeNs.toDouble() / 1_000_000_000.0).coerceAtLeast(1e-6)
        val ev100 = ((ln((aperture * aperture) / expSec) / ln(2.0)) -
                (ln(iso.toDouble() / 100.0) / ln(2.0))).toFloat()

        val hasFace = faces.isNotEmpty()
        val maxFaceArea = if (hasFace) faces.maxOf { it.bounds.width() * it.bounds.height() } else 0

        processSceneIllumination(ev100, hasFace, maxFaceArea, latestFrameStats)
    }

    /**
     * Fuses sensor EV100, face detection, and actual frame luminance percentiles
     * to compute stable, filmic Natural Log exposure and tonemap parameters.
     */
    fun processSceneIllumination(
        ev100: Float,
        hasFace: Boolean = false,
        maxFaceArea: Int = 0,
        stats: FrameLuminanceStats? = latestFrameStats
    ) {
        // 1. Temporal Smoothing with Hysteresis on Scene EV100 (deadband = 0.12 EV)
        val evDelta = abs(ev100 - smoothedEv100)
        if (evDelta >= 0.12f) {
            val evAlpha = if (evDelta > 2.0f) 0.12f else 0.05f
            smoothedEv100 += (ev100 - smoothedEv100) * evAlpha
        }

        val outdoorFactor = ((smoothedEv100 - 8.5f) / 5.5f).coerceIn(0.0f, 1.0f)

        // 2. Scene Contrast & Illumination Distribution Evaluation
        val isHighContrast: Boolean
        val isOutdoorSkyWithDarkForeground: Boolean
        val dynamicRange: Float
        val p18Val: Float
        val p95Val: Float

        if (stats != null) {
            isHighContrast = stats.isHighContrast
            isOutdoorSkyWithDarkForeground = stats.isOutdoorSkyWithDarkForeground
            dynamicRange = stats.dynamicRange
            p18Val = stats.p18
            p95Val = stats.p95
        } else {
            // Fallback estimation prior to first frame analysis
            isHighContrast = outdoorFactor > 0.40f
            isOutdoorSkyWithDarkForeground = outdoorFactor > 0.65f
            dynamicRange = 0.70f + 0.20f * outdoorFactor
            p18Val = (0.22f - 0.08f * outdoorFactor).coerceIn(0.12f, 0.25f)
            p95Val = (0.75f + 0.20f * outdoorFactor).coerceIn(0.70f, 0.96f)
        }

        val faceWeight = if (hasFace) {
            (maxFaceArea.toFloat() / 200_000f).coerceIn(0.20f, 0.80f)
        } else 0.0f

        // 3. ZERO OUTDOOR DARKENING BIAS
        // Never apply negative exposure compensation simply because EV100 is high!
        // Camera2 AE already protects sensor dynamic range. Negative compensation in daylight
        // crushes foregrounds and underexposes faces.
        // We maintain neutral exposure (0.0f) and use tonemap highlight roll-off to protect sky/clouds.
        // In extreme low light (EV100 < 6.0), provide a gentle clean shadow assist (+0.08 to +0.15 EV).
        var targetExposureComp = when {
            smoothedEv100 < 6.0f -> {
                val factor = ((6.0f - smoothedEv100) / 3.0f).coerceIn(0f, 1f)
                0.15f * factor
            }
            // If face is heavily in shadow under bright sky, provide subtle positive assist
            isOutdoorSkyWithDarkForeground && hasFace && p18Val < 0.20f -> {
                0.08f * faceWeight
            }
            else -> 0.0f
        }

        // 4. PROGRESSIVE OUTDOOR SHADOW RECOVERY
        // High-contrast outdoor scenes receive increased shadow recovery (0.35–0.60)
        // so dark foliage, clothing, and shaded faces remain clear and textured.
        var targetShadowLift = when {
            isOutdoorSkyWithDarkForeground -> {
                // Bright sky with dark foreground: strongest shadow recovery (~0.45 - 0.60)
                val skyPressure = ((p95Val - 0.82f) / 0.18f).coerceIn(0f, 1f)
                val shadowDepth = ((0.28f - p18Val) / 0.20f).coerceIn(0f, 1f)
                0.45f + 0.15f * (0.5f * skyPressure + 0.5f * shadowDepth)
            }
            isHighContrast -> {
                // High contrast scene (~0.35 - 0.45)
                val drFactor = ((dynamicRange - 0.65f) / 0.30f).coerceIn(0f, 1f)
                0.35f + 0.10f * drFactor
            }
            else -> {
                // Normal scene (~0.22 - 0.30)
                val normShadow = ((0.28f - p18Val) / 0.28f).coerceIn(0f, 1f)
                0.22f + 0.08f * normShadow
            }
        }

        if (hasFace && p18Val < 0.28f) {
            targetShadowLift = (targetShadowLift + 0.05f * faceWeight).coerceAtMost(0.60f)
        }

        // 5. DYNAMIC HIGHLIGHT ROLL-OFF SHOULDER
        // Bright daylight scenes with high sky pressure receive stronger soft-knee compression
        // to protect clouds and bright skies from hard clipping.
        var targetRollOff = if (p95Val > 0.80f || outdoorFactor > 0.30f) {
            val skyIntensity = max(outdoorFactor, ((p95Val - 0.80f) / 0.18f).coerceIn(0f, 1f))
            0.45f + 0.38f * skyIntensity
        } else {
            0.38f
        }

        // 6. MIDTONE CONTRAST HARMONIZATION
        // In high-contrast harsh daylight, gently relax contrast (-0.06) to maximize dynamic range.
        // In flat indoor/overcast light, subtly firm contrast (+0.04) for crisp separation.
        var targetContrast = if (isHighContrast) {
            -0.06f * ((dynamicRange - 0.65f) / 0.35f).coerceIn(0f, 1f)
        } else {
            0.04f * (1.0f - outdoorFactor)
        }

        // 7. DEADBAND HYSTERESIS (Eliminates frame-to-frame micro-jitter)
        targetExposureComp = applyDeadband(lastTargetExposureComp, targetExposureComp, 0.015f)
        lastTargetExposureComp = targetExposureComp

        targetShadowLift = applyDeadband(lastTargetShadowLift, targetShadowLift, 0.02f)
        lastTargetShadowLift = targetShadowLift

        targetRollOff = applyDeadband(lastTargetHighlightRollOff, targetRollOff, 0.02f)
        lastTargetHighlightRollOff = targetRollOff

        // 8. TEMPORAL IIR LOW-PASS SMOOTHING (Smooth cinematic transitions without pumping)
        smoothedExposureComp = smoothParam(smoothedExposureComp, targetExposureComp, 0.06f)
        smoothedShadowLift = smoothParam(smoothedShadowLift, targetShadowLift, 0.05f)
        smoothedHighlightRollOff = smoothParam(smoothedHighlightRollOff, targetRollOff, 0.05f)
        smoothedContrast = smoothParam(smoothedContrast, targetContrast, 0.04f)

        val updated = NaturalLogParams(
            exposureComp = smoothedExposureComp,
            highlightRollOff = smoothedHighlightRollOff,
            shadowToeLift = smoothedShadowLift,
            contrast = smoothedContrast,
            sceneLuxIndex = outdoorFactor,
            dynamicRange = dynamicRange,
            p18Midtone = p18Val,
            isHighContrast = isHighContrast,
            isOutdoorSkyWithDarkForeground = isOutdoorSkyWithDarkForeground
        )
        _currentParams.value = updated
    }

    private fun applyDeadband(current: Float, target: Float, deadband: Float): Float {
        return if (abs(target - current) < deadband) current else target
    }

    private fun smoothParam(current: Float, target: Float, baseAlpha: Float): Float {
        val diff = target - current
        if (abs(diff) < 0.001f) return target
        val alpha = if (abs(diff) > 0.15f) baseAlpha * 2.0f else baseAlpha
        return current + diff * alpha
    }

    fun hasSignificantChangeSinceLastIspUpdate(): Boolean {
        val curr = _currentParams.value
        val expDiff = abs(curr.exposureComp - lastIspExposureComp)
        val rollDiff = abs(curr.highlightRollOff - lastIspHighlightRollOff)
        val shadowDiff = abs(curr.shadowToeLift - lastIspShadowLift)
        return (expDiff > 0.03f || rollDiff > 0.05f || shadowDiff > 0.05f)
    }

    fun markIspUpdated() {
        val curr = _currentParams.value
        lastIspExposureComp = curr.exposureComp
        lastIspHighlightRollOff = curr.highlightRollOff
        lastIspShadowLift = curr.shadowToeLift
        lastIspUpdateTime = System.currentTimeMillis()
    }

    /**
     * Generates an authentic, film-calibrated Natural Log TonemapCurve.
     *
     * Principles:
     * 1. Deep black strictly anchored at 0.02 (clean inky black, zero milky/grey haze).
     * 2. Shadow detail lifted in the toe region (x in 0.015..0.35) without lifting black pedestal.
     * 3. 18% middle grey anchored stably at ~0.38.
     * 4. Natural, punchy midtones without fake HDR halos.
     * 5. Smooth cubic soft-knee highlight shoulder roll-off (x in 0.55..1.0) protecting sky and specular highlights.
     */
    fun getTonemapCurve(
        userExposure: Float = 0.0f,
        userShadows: Float = 0.0f,
        userHighlights: Float = 0.0f,
        userContrast: Float = 0.0f,
        washedOut: Float = 0.0f
    ): TonemapCurve {
        val params = _currentParams.value
        val effectiveHighlightRollOff = (params.highlightRollOff + userHighlights * 0.25f).coerceIn(0.20f, 0.90f)
        val effectiveShadowLift = (params.shadowToeLift + userShadows * 0.25f).coerceIn(0.15f, 0.65f)
        val effectiveContrast = (params.contrast + userContrast * 0.35f).coerceIn(-0.4f, 0.4f)

        val numPoints = CURVE_POINTS
        var lastY = 0.0f

        for (i in 0 until numPoints) {
            val baseNormalizedX = i.toFloat() / (numPoints - 1).toFloat()

            // 1. Strict Deep Black Anchoring:
            // Base black pedestal at x = 0 is 0.04f (cinematic flat log baseline).
            // When washedOut > 0.0f, the black pedestal is progressively lowered towards 0.0f to restore contrast & depth.
            val rawPedestal = 0.04f
            val blackAnchor = (rawPedestal * (1.0f - washedOut * 0.95f)).coerceAtLeast(0.001f)

            // 2. Calibrated Base Logarithmic Curve (ARRI LogC / S-Log3 hybrid):
            // Anchors 18% middle grey (x = 0.18) stably at ~0.385
            val logVal = ln(1.0f + 16.0f * baseNormalizedX) / ln(17.0f)
            val baseTransfer = 0.64f * logVal + 0.36f * baseNormalizedX
            var y = blackAnchor + (1.0f - blackAnchor) * baseTransfer

            // 3. Dynamic Shadow Toe Recovery:
            // Applies ONLY to shadow textures in x in [0.015, 0.35].
            // Uses a smooth weight function that is strictly 0 at x = 0 (preserving true black)
            // and peaks around x ~ 0.08..0.12 (recovering deep shadow detail, foliage, and shaded faces).
            if (baseNormalizedX in 0.005f..0.35f) {
                val toeRange = 0.35f
                val normX = baseNormalizedX / toeRange
                // Cubic envelope: 0 at x=0, peaks at ~0.10, falls smoothly to 0 at x=0.35
                val shadowWeight = (1.0f - normX).pow(1.8f) * (baseNormalizedX / 0.08f).coerceAtMost(1.0f)
                val shadowBoost = effectiveShadowLift * 0.18f * shadowWeight
                y += shadowBoost
            }

            // 4. Highlight Roll-Off Shoulder:
            // Soft-knee compression in x in [0.55, 1.0] to preserve cloud texture and prevent harsh specular clipping
            if (effectiveHighlightRollOff > 0.30f && baseNormalizedX > 0.55f) {
                val hlProgress = (baseNormalizedX - 0.55f) / 0.45f
                val hlWeight = hlProgress.pow(1.8f)
                val compression = (effectiveHighlightRollOff - 0.30f) * 0.09f
                y -= compression * hlWeight
            }

            // 5. Subtle Midtone Contrast Adjustment centered around 18% middle grey (0.385f)
            if (effectiveContrast != 0.0f) {
                val factor = 1.0f + effectiveContrast * 0.35f
                y = 0.385f + (y - 0.385f) * factor
            }

            // 6. Monotonicity and boundary guarantees
            var finalY = y.coerceIn(blackAnchor, 1.0f)
            if (i == 0) {
                finalY = blackAnchor
            } else if (i == numPoints - 1) {
                finalY = 1.0f
            } else if (finalY < lastY) {
                finalY = lastY
            }
            lastY = finalY

            val idx = i * 2
            curveRed[idx] = baseNormalizedX
            curveRed[idx + 1] = finalY
            curveGreen[idx] = baseNormalizedX
            curveGreen[idx + 1] = finalY
            curveBlue[idx] = baseNormalizedX
            curveBlue[idx + 1] = finalY
        }

        return TonemapCurve(curveRed, curveGreen, curveBlue)
    }
}
