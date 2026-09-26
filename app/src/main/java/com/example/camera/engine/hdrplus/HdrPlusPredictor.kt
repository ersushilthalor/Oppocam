package com.example.camera.engine.hdrplus

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import com.example.camera.engine.FrameLuminanceStats
import com.example.camera.model.HardwareCapabilities
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Predicts and precomputes secondary RAW bracket exposures continuously in the background
 * based on live viewfinder luminance analysis (sky, clouds, bright highlights) and sensor dynamic range.
 */
class HdrPlusPredictor {

    @Volatile
    private var cachedPrediction: HdrPlusPrediction = createDefaultPrediction()

    /**
     * Gets the latest instantaneous precomputed exposure prediction.
     */
    fun getLatestPrediction(frameCount: HdrPlusFrameCount): HdrPlusPrediction {
        val base = cachedPrediction
        return if (base.specs.size == frameCount.count) {
            base
        } else {
            // Re-adapt to requested frame count immediately
            adaptPredictionToFrameCount(base, frameCount)
        }
    }

    /**
     * Analyzes live scene brightness, active capture parameters, and hardware limits to update predictions.
     */
    fun updatePrediction(
        stats: FrameLuminanceStats?,
        lastResult: CaptureResult?,
        caps: HardwareCapabilities,
        frameCount: HdrPlusFrameCount,
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

        // 2. Determine target EV offsets for secondary frames
        val sec1EvOffset: Float = when {
            isOutdoorSky || p99 > 0.94f -> -2.0f
            p95 > 0.88f -> -1.67f
            hasClippedHighlights -> -1.5f
            else -> -1.33f
        }

        val sec2EvOffset: Float = when {
            isOutdoorSky || p99 > 0.94f -> -3.67f
            p95 > 0.88f -> -3.33f
            else -> -3.0f
        }

        // 3. Compute Frame 1 (User's primary exposure - 100% UNCHANGED)
        val frame1Spec = HdrPlusExposureSpec(
            role = HdrPlusRole.BASE_PRIMARY,
            exposureTimeNs = baseExposureTimeNs.coerceIn(minExpNs, maxExpNs),
            iso = baseIso.coerceIn(minIso, maxIso),
            evDelta = 0.0f,
            aeCompIndex = userAeCompensation
        )

        // 4. Compute Secondary Frame 1 (Moderate Highlight Recovery)
        val sec1Ratio = 2.0.pow(sec1EvOffset.toDouble()).toFloat() // e.g. 0.25x for -2 EV
        val (sec1ExpNs, sec1Iso) = computeExposurePair(
            targetRatio = sec1Ratio,
            baseExpNs = baseExposureTimeNs,
            baseIso = baseIso,
            minExpNs = minExpNs,
            maxExpNs = maxExpNs,
            minIso = minIso,
            maxIso = maxIso
        )
        val sec1AeComp = calculateAeCompensationIndex(sec1EvOffset, caps)

        val frame2Spec = HdrPlusExposureSpec(
            role = HdrPlusRole.SECONDARY_MODERATE_HIGHLIGHT,
            exposureTimeNs = sec1ExpNs,
            iso = sec1Iso,
            evDelta = sec1EvOffset,
            aeCompIndex = sec1AeComp
        )

        val specs = mutableListOf(frame1Spec, frame2Spec)

        // 5. If 3-frame mode: compute Secondary Frame 2 (Extreme Highlight Recovery, strictly darker than Frame 2)
        if (frameCount == HdrPlusFrameCount.THREE_FRAMES) {
            val sec2Ratio = 2.0.pow(sec2EvOffset.toDouble()).toFloat() // e.g. 0.08x for -3.67 EV
            var (sec2ExpNs, sec2Iso) = computeExposurePair(
                targetRatio = sec2Ratio,
                baseExpNs = baseExposureTimeNs,
                baseIso = baseIso,
                minExpNs = minExpNs,
                maxExpNs = maxExpNs,
                minIso = minIso,
                maxIso = maxIso
            )

            // Guarantee Frame 3 is strictly darker than Frame 2
            val sec1Product = sec1ExpNs.toDouble() * sec1Iso.toDouble()
            var sec2Product = sec2ExpNs.toDouble() * sec2Iso.toDouble()
            if (sec2Product >= sec1Product) {
                sec2ExpNs = (sec1ExpNs * 0.45).toLong().coerceIn(minExpNs, maxExpNs)
                sec2Iso = (sec1Iso * 0.8).toInt().coerceIn(minIso, maxIso)
            }

            val sec2AeComp = calculateAeCompensationIndex(sec2EvOffset, caps)

            val frame3Spec = HdrPlusExposureSpec(
                role = HdrPlusRole.SECONDARY_EXTREME_HIGHLIGHT,
                exposureTimeNs = sec2ExpNs,
                iso = sec2Iso,
                evDelta = sec2EvOffset,
                aeCompIndex = sec2AeComp
            )
            specs.add(frame3Spec)
        }

        val summary = "HDR+ ${frameCount.count}F | Sec1: ${"%.1f".format(sec1EvOffset)}EV" +
                (if (frameCount == HdrPlusFrameCount.THREE_FRAMES) " | Sec2: ${"%.1f".format(sec2EvOffset)}EV" else "")

        cachedPrediction = HdrPlusPrediction(
            specs = specs,
            highlightPressure = p99,
            sceneDynamicRange = dynamicRange,
            hasClippedHighlights = hasClippedHighlights,
            isOutdoorSkyDetected = isOutdoorSky,
            summary = summary
        )
    }

    private fun computeExposurePair(
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

    private fun calculateAeCompensationIndex(evOffset: Float, caps: HardwareCapabilities): Int {
        val step = if (caps.exposureCompensationStep > 0.001f) caps.exposureCompensationStep else 0.333f
        val idx = (evOffset / step).roundToInt()
        return idx.coerceIn(caps.minExposureCompensation, caps.maxExposureCompensation)
    }

    private fun adaptPredictionToFrameCount(
        prediction: HdrPlusPrediction,
        frameCount: HdrPlusFrameCount
    ): HdrPlusPrediction {
        if (prediction.specs.isEmpty()) return createDefaultPrediction(frameCount)
        val base = prediction.specs.first()
        val sec1 = prediction.specs.getOrNull(1) ?: HdrPlusExposureSpec(
            role = HdrPlusRole.SECONDARY_MODERATE_HIGHLIGHT,
            exposureTimeNs = (base.exposureTimeNs * 0.25).toLong(),
            iso = base.iso,
            evDelta = -2.0f
        )

        val newSpecs = mutableListOf(base, sec1)
        if (frameCount == HdrPlusFrameCount.THREE_FRAMES) {
            val sec2 = prediction.specs.getOrNull(2) ?: HdrPlusExposureSpec(
                role = HdrPlusRole.SECONDARY_EXTREME_HIGHLIGHT,
                exposureTimeNs = (sec1.exposureTimeNs * 0.35).toLong(),
                iso = sec1.iso,
                evDelta = -3.5f
            )
            newSpecs.add(sec2)
        }

        return prediction.copy(specs = newSpecs)
    }

    private fun createDefaultPrediction(frameCount: HdrPlusFrameCount = HdrPlusFrameCount.TWO_FRAMES): HdrPlusPrediction {
        val frame1 = HdrPlusExposureSpec(
            role = HdrPlusRole.BASE_PRIMARY,
            exposureTimeNs = 33_333_333L,
            iso = 100,
            evDelta = 0.0f
        )
        val frame2 = HdrPlusExposureSpec(
            role = HdrPlusRole.SECONDARY_MODERATE_HIGHLIGHT,
            exposureTimeNs = 8_333_333L,
            iso = 100,
            evDelta = -2.0f
        )
        val specs = mutableListOf(frame1, frame2)
        if (frameCount == HdrPlusFrameCount.THREE_FRAMES) {
            val frame3 = HdrPlusExposureSpec(
                role = HdrPlusRole.SECONDARY_EXTREME_HIGHLIGHT,
                exposureTimeNs = 2_500_000L,
                iso = 100,
                evDelta = -3.7f
            )
            specs.add(frame3)
        }
        return HdrPlusPrediction(
            specs = specs,
            summary = "HDR+ default prediction (${frameCount.count} frames)"
        )
    }
}
