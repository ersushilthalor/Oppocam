package com.example.camera.engine.hdrplus

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import com.example.camera.engine.FrameLuminanceStats
import com.example.camera.model.HardwareCapabilities
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Predicts and precomputes 3-Frame Exposure Fusion bracket exposures continuously in the background
 * based on live viewfinder luminance analysis (sky, clouds, bright highlights, deep shadows) and sensor dynamic range.
 *
 * The 3 frames consist of:
 * 1. Normal Exposure (0 EV) - Reference base for midtones, skin tones, and alignment
 * 2. Underexposed Frame (-2.0 EV) - Recovers clipped highlights, sky gradients, and specular details
 * 3. Overexposed Frame (+2.0 EV) - Lifts deep shadows, reveals dark textures, and improves shadow SNR
 */
class HdrPlusPredictor {

    @Volatile
    private var cachedPrediction: HdrPlusPrediction = createDefaultPrediction()

    /**
     * Gets the latest instantaneous precomputed exposure prediction for 3-Frame Exposure Fusion.
     */
    fun getLatestPrediction(frameCount: HdrPlusFrameCount = HdrPlusFrameCount.THREE_FRAMES): HdrPlusPrediction {
        return cachedPrediction
    }

    /**
     * Analyzes live scene brightness, active capture parameters, and hardware limits to update predictions.
     */
    fun updatePrediction(
        stats: FrameLuminanceStats?,
        lastResult: CaptureResult?,
        caps: HardwareCapabilities,
        frameCount: HdrPlusFrameCount = HdrPlusFrameCount.THREE_FRAMES,
        userSelectedIso: Int? = null,
        userSelectedExposureTimeNs: Long? = null,
        userAeCompensation: Int = 0
    ) {
        val baseExposureTimeNs = userSelectedExposureTimeNs
            ?: lastResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ?: 33_333_333L // 1/30s default

        val baseIso = userSelectedIso
            ?: lastResult?.get(CaptureResult.SENSOR_SENSITIVITY)
            ?: 100

        val minExpNs = caps.minExposureTimeNs.coerceAtLeast(10_000L) // 0.01ms min
        val maxExpNs = caps.maxExposureTimeNs.coerceAtMost(10_000_000_000L)
        val minIso = caps.minIso.coerceAtLeast(50)
        val maxIso = caps.maxIso.coerceAtLeast(3200)

        // 1. Detect bright sky, clouds, and highlight pressure
        val p95 = stats?.p95 ?: 0.82f
        val p99 = stats?.p99 ?: 0.92f
        val dynamicRange = stats?.dynamicRange ?: 0.70f
        val isOutdoorSky = stats?.isOutdoorSkyWithDarkForeground ?: false
        val hasClippedHighlights = p99 > 0.90f || (p95 > 0.85f && dynamicRange > 0.65f)

        // 2. Determine target EV offsets for underexposed and overexposed frames
        val underEvOffset: Float = when {
            isOutdoorSky || p99 > 0.94f -> -2.33f
            p95 > 0.88f -> -2.0f
            hasClippedHighlights -> -1.85f
            else -> -1.67f
        }

        val overEvOffset: Float = when {
            dynamicRange > 0.75f || (stats?.p5 ?: 0.15f) < 0.08f -> 2.0f
            dynamicRange > 0.60f -> 1.75f
            else -> 1.5f
        }

        // 3. Compute Frame 1: Normal Reference Exposure (0 EV - Base AE)
        val normalSpec = HdrPlusExposureSpec(
            role = HdrPlusRole.NORMAL_EXPOSURE,
            exposureTimeNs = baseExposureTimeNs.coerceIn(minExpNs, maxExpNs),
            iso = baseIso.coerceIn(minIso, maxIso),
            evDelta = 0.0f,
            aeCompIndex = userAeCompensation
        )

        // 4. Compute Frame 2: Underexposed Frame (-2 EV for Highlights)
        val underRatio = 2.0.pow(underEvOffset.toDouble()).toFloat() // e.g. 0.25x for -2 EV
        val (underExpNs, underIso) = computeUnderExposurePair(
            targetRatio = underRatio,
            baseExpNs = baseExposureTimeNs,
            baseIso = baseIso,
            minExpNs = minExpNs,
            maxExpNs = maxExpNs,
            minIso = minIso,
            maxIso = maxIso
        )
        val underAeComp = calculateAeCompensationIndex(underEvOffset, caps)

        val underSpec = HdrPlusExposureSpec(
            role = HdrPlusRole.UNDER_EXPOSED,
            exposureTimeNs = underExpNs,
            iso = underIso,
            evDelta = underEvOffset,
            aeCompIndex = underAeComp
        )

        // 5. Compute Frame 3: Overexposed Frame (+2 EV for Shadows)
        val overRatio = 2.0.pow(overEvOffset.toDouble()).toFloat() // e.g. 4.0x for +2 EV
        val (overExpNs, overIso) = computeOverExposurePair(
            targetRatio = overRatio,
            baseExpNs = baseExposureTimeNs,
            baseIso = baseIso,
            minExpNs = minExpNs,
            maxExpNs = maxExpNs,
            minIso = minIso,
            maxIso = maxIso
        )
        val overAeComp = calculateAeCompensationIndex(overEvOffset, caps)

        val overSpec = HdrPlusExposureSpec(
            role = HdrPlusRole.OVER_EXPOSED,
            exposureTimeNs = overExpNs,
            iso = overIso,
            evDelta = overEvOffset,
            aeCompIndex = overAeComp
        )

        val specs = listOf(normalSpec, underSpec, overSpec)

        val summary = "3-Frame Exposure Fusion | Under: ${"%.1f".format(underEvOffset)}EV | Base: 0.0EV | Over: +${"%.1f".format(overEvOffset)}EV"

        cachedPrediction = HdrPlusPrediction(
            specs = specs,
            highlightPressure = p99,
            sceneDynamicRange = dynamicRange,
            hasClippedHighlights = hasClippedHighlights,
            isOutdoorSkyDetected = isOutdoorSky,
            summary = summary
        )
    }

    private fun computeUnderExposurePair(
        targetRatio: Float,
        baseExpNs: Long,
        baseIso: Int,
        minExpNs: Long,
        maxExpNs: Long,
        minIso: Int,
        maxIso: Int
    ): Pair<Long, Int> {
        // First try reducing exposure time to preserve low sensor read noise
        val idealExpNs = (baseExpNs * targetRatio).toLong()
        return if (idealExpNs >= minExpNs) {
            Pair(idealExpNs.coerceIn(minExpNs, maxExpNs), baseIso.coerceIn(minIso, maxIso))
        } else {
            // If shutter hits hardware min floor, scale down ISO
            val remainingRatio = idealExpNs.toDouble() / minExpNs.toDouble()
            val idealIso = (baseIso * remainingRatio).toInt().coerceIn(minIso, maxIso)
            Pair(minExpNs, idealIso)
        }
    }

    private fun computeOverExposurePair(
        targetRatio: Float,
        baseExpNs: Long,
        baseIso: Int,
        minExpNs: Long,
        maxExpNs: Long,
        minIso: Int,
        maxIso: Int
    ): Pair<Long, Int> {
        // Limit handheld overexposed shutter to 66ms (1/15s) to avoid motion blur, boost ISO if needed
        val maxHandheldExpNs = 66_666_666L.coerceAtMost(maxExpNs)
        val idealExpNs = (baseExpNs * targetRatio).toLong()

        return if (idealExpNs <= maxHandheldExpNs) {
            Pair(idealExpNs.coerceIn(minExpNs, maxExpNs), baseIso.coerceIn(minIso, maxIso))
        } else {
            val usedExpNs = maxHandheldExpNs.coerceAtLeast(baseExpNs)
            val neededIsoMultiplier = (baseExpNs.toDouble() * targetRatio) / usedExpNs.toDouble()
            val idealIso = (baseIso * neededIsoMultiplier).toInt().coerceIn(minIso, maxIso)
            Pair(usedExpNs, idealIso)
        }
    }

    private fun calculateAeCompensationIndex(evOffset: Float, caps: HardwareCapabilities): Int {
        val step = if (caps.exposureCompensationStep > 0.001f) caps.exposureCompensationStep else 0.333f
        val idx = (evOffset / step).roundToInt()
        return idx.coerceIn(caps.minExposureCompensation, caps.maxExposureCompensation)
    }

    private fun createDefaultPrediction(frameCount: HdrPlusFrameCount = HdrPlusFrameCount.THREE_FRAMES): HdrPlusPrediction {
        val frameNormal = HdrPlusExposureSpec(
            role = HdrPlusRole.NORMAL_EXPOSURE,
            exposureTimeNs = 33_333_333L,
            iso = 100,
            evDelta = 0.0f
        )
        val frameUnder = HdrPlusExposureSpec(
            role = HdrPlusRole.UNDER_EXPOSED,
            exposureTimeNs = 8_333_333L,
            iso = 100,
            evDelta = -2.0f
        )
        val frameOver = HdrPlusExposureSpec(
            role = HdrPlusRole.OVER_EXPOSED,
            exposureTimeNs = 66_666_666L,
            iso = 200,
            evDelta = 2.0f
        )
        val specs = listOf(frameNormal, frameUnder, frameOver)
        return HdrPlusPrediction(
            specs = specs,
            summary = "3-Frame Exposure Fusion Default (-2EV / 0EV / +2EV)"
        )
    }
}
