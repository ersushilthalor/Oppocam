package com.example.camera.hdr.metering

import android.util.Range
import com.example.camera.hdr.model.HdrExposurePair
import com.example.camera.hdr.model.HdrExposureStrength
import com.example.camera.hdr.model.SceneAnalysisMetrics
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Continuous real-time lightweight scene metering and dual-exposure optimization engine.
 * Analyzes downsampled sensor/preview luminance to compute:
 * - 64-bin histogram
 * - Average & median luminance
 * - Highlight clipping percentage (>92% max luminance)
 * - Shadow underexposure percentage (<8% max luminance)
 * - Estimated dynamic range in EV stops
 * - Smallest optimal exposure separation delta (EV) based on scene needs
 */
class HdrSceneMeteringEngine {

    private var previousHistogram: IntArray? = null
    private var previousLuminance: Float = 0.5f
    private var sceneChangeConfidence: Float = 0f

    // Damped exposure pair to prevent jitter
    private var lastCalculatedPair: HdrExposurePair? = null

    /**
     * Analyzes a downsampled grayscale/luminance buffer (e.g. 64x64 or 32x32 samples).
     *
     * @param luminanceSamples Normalized luminance values [0.0f, 1.0f]
     * @param baseExposureNs Current auto-exposure shutter speed in nanoseconds
     * @param baseIso Current auto-exposure sensitivity
     * @param exposureRangeNs Valid shutter range of camera sensor
     * @param isoRange Valid ISO range of camera sensor
     * @param strength User-selected HDR exposure strength preference
     * @return Pair of SceneAnalysisMetrics and the computed optimal HdrExposurePair
     */
    fun analyzeScene(
        luminanceSamples: FloatArray,
        baseExposureNs: Long,
        baseIso: Int,
        exposureRangeNs: Range<Long>,
        isoRange: Range<Int>,
        strength: HdrExposureStrength = HdrExposureStrength.AUTO
    ): Pair<SceneAnalysisMetrics, HdrExposurePair> {
        val sampleCount = luminanceSamples.size
        if (sampleCount == 0) {
            val fallbackMetrics = SceneAnalysisMetrics()
            val fallbackPair = createFallbackPair(baseExposureNs, baseIso, exposureRangeNs, isoRange)
            return Pair(fallbackMetrics, fallbackPair)
        }

        // 1. Histogram (64 bins) and Accumulators
        val histogram = IntArray(64)
        var sumLuminance = 0f
        var highlightClippingCount = 0
        var shadowUnderexposureCount = 0
        var extremeHighlightCount = 0
        var extremeShadowCount = 0

        for (i in 0 until sampleCount) {
            val lum = luminanceSamples[i].coerceIn(0f, 1f)
            sumLuminance += lum

            val binIndex = (lum * 63f).toInt().coerceIn(0, 63)
            histogram[binIndex]++

            if (lum > 0.92f) highlightClippingCount++
            if (lum < 0.08f) shadowUnderexposureCount++
            if (lum > 0.98f) extremeHighlightCount++
            if (lum < 0.02f) extremeShadowCount++
        }

        val avgLuminance = sumLuminance / sampleCount
        val highlightClippingPct = (highlightClippingCount.toFloat() / sampleCount) * 100f
        val shadowUnderexposurePct = (shadowUnderexposureCount.toFloat() / sampleCount) * 100f
        val extremeHighlightPct = (extremeHighlightCount.toFloat() / sampleCount) * 100f
        val extremeShadowPct = (extremeShadowCount.toFloat() / sampleCount) * 100f

        // 2. Median Luminance via Cumulative Histogram
        val halfCount = sampleCount / 2
        var accum = 0
        var medianBin = 32
        for (b in 0 until 64) {
            accum += histogram[b]
            if (accum >= halfCount) {
                medianBin = b
                break
            }
        }
        val medianLuminance = medianBin / 63f

        // 3. Dynamic Range Estimation (in EV)
        // Find 2nd percentile (shadow threshold) and 98th percentile (highlight threshold)
        val p2Count = (sampleCount * 0.02f).toInt()
        val p98Count = (sampleCount * 0.98f).toInt()
        var p2Lum = 0.02f
        var p98Lum = 0.98f

        var pAccum = 0
        for (b in 0 until 64) {
            pAccum += histogram[b]
            if (pAccum >= p2Count && p2Lum == 0.02f) {
                p2Lum = (b / 63f).coerceAtLeast(0.005f)
            }
            if (pAccum >= p98Count) {
                p98Lum = (b / 63f).coerceAtLeast(p2Lum + 0.01f)
                break
            }
        }

        // Ratio of 98th to 2nd percentile in log2 provides scene EV spread
        val rawDynamicRangeRatio = (p98Lum / p2Lum).coerceAtLeast(1.0f)
        val estimatedDynamicRangeEv = (ln(rawDynamicRangeRatio) / ln(2.0)).toFloat().coerceIn(2.0f, 14.0f)

        // 4. Lightweight Scene-Change Detection
        var histDiff = 0f
        previousHistogram?.let { prev ->
            for (b in 0 until 64) {
                val diff = kotlin.math.abs(histogram[b] - prev[b])
                histDiff += diff.toFloat() / sampleCount
            }
        }
        val lumDiff = kotlin.math.abs(avgLuminance - previousLuminance)
        val isSignificantChange = (histDiff > 0.40f || lumDiff > 0.25f)

        previousHistogram = histogram.clone()
        previousLuminance = avgLuminance

        val metrics = SceneAnalysisMetrics(
            overallLuminance = avgLuminance,
            medianLuminance = medianLuminance,
            highlightClippingPercent = highlightClippingPct,
            shadowUnderexposurePercent = shadowUnderexposurePct,
            estimatedDynamicRangeEv = estimatedDynamicRangeEv,
            extremeHighlightPercent = extremeHighlightPct,
            extremeShadowPercent = extremeShadowPct,
            histogram = histogram,
            significantChangeDetected = isSignificantChange
        )

        // 5. Adaptive Exposure Delta Calculation:
        // Use smallest exposure difference that provides useful HDR information.
        // Increase gap only when scene requires it.
        val optimalEvDelta = calculateAdaptiveEvDelta(
            metrics = metrics,
            strength = strength
        )

        val exposurePair = computeOptimalExposurePair(
            baseExposureNs = baseExposureNs,
            baseIso = baseIso,
            targetEvDelta = optimalEvDelta,
            exposureRangeNs = exposureRangeNs,
            isoRange = isoRange,
            metrics = metrics
        )

        lastCalculatedPair = exposurePair
        return Pair(metrics, exposurePair)
    }

    /**
     * Calculates the minimum EV separation needed based on scene metrics.
     */
    private fun calculateAdaptiveEvDelta(
        metrics: SceneAnalysisMetrics,
        strength: HdrExposureStrength
    ): Float {
        // Base delta derived from scene dynamic range and clipping
        val baseDelta = when {
            metrics.estimatedDynamicRangeEv <= 5.5f && metrics.highlightClippingPercent < 1.0f -> 1.0f
            metrics.estimatedDynamicRangeEv <= 7.0f -> 1.4f
            metrics.estimatedDynamicRangeEv <= 9.0f -> 2.0f
            metrics.estimatedDynamicRangeEv <= 11.0f -> 2.7f
            else -> 3.4f
        }

        // Additional boost if high contrast clipping is present
        var bonus = 0f
        if (metrics.highlightClippingPercent > 3.0f) bonus += 0.4f
        if (metrics.shadowUnderexposurePercent > 8.0f) bonus += 0.3f
        if (metrics.extremeHighlightPercent > 1.0f) bonus += 0.5f

        val calculated = baseDelta + bonus

        return when (strength) {
            HdrExposureStrength.AUTO -> calculated.coerceIn(strength.minEvDelta, strength.maxEvDelta)
            HdrExposureStrength.MILD -> calculated.coerceIn(strength.minEvDelta, strength.maxEvDelta)
            HdrExposureStrength.STANDARD -> (calculated * 1.1f).coerceIn(strength.minEvDelta, strength.maxEvDelta)
            HdrExposureStrength.HIGH -> (calculated * 1.3f).coerceIn(strength.minEvDelta, strength.maxEvDelta)
            HdrExposureStrength.MAXIMUM -> (calculated * 1.5f).coerceIn(strength.minEvDelta, strength.maxEvDelta)
        }
    }

    /**
     * Determines concrete (shutter speed, ISO) pairs for:
     * - Short Exposure A: preserves highlights
     * - Long Exposure B: preserves shadows
     * Prioritizes shutter speed adjustments before ISO to minimize sensor noise.
     */
    private fun computeOptimalExposurePair(
        baseExposureNs: Long,
        baseIso: Int,
        targetEvDelta: Float,
        exposureRangeNs: Range<Long>,
        isoRange: Range<Int>,
        metrics: SceneAnalysisMetrics
    ): HdrExposurePair {
        // Short exposure shifts downwards by half to two-thirds of EV delta
        // Long exposure shifts upwards
        val highlightBias = if (metrics.highlightClippingPercent > metrics.shadowUnderexposurePercent) 0.6f else 0.45f
        val shortEvShift = targetEvDelta * highlightBias
        val longEvShift = targetEvDelta * (1.0f - highlightBias)

        val shortMultiplier = (0.5).pow(shortEvShift.toDouble()).toFloat()
        val longMultiplier = (2.0).pow(longEvShift.toDouble()).toFloat()

        // Short Exposure calculation (prefer lowering exposure time first)
        var shortExposureNs = (baseExposureNs * shortMultiplier).toLong().coerceIn(exposureRangeNs.lower, exposureRangeNs.upper)
        var shortIso = baseIso
        if (shortExposureNs <= exposureRangeNs.lower) {
            // Need further attenuation via lower ISO
            val remainingRatio = (baseExposureNs * shortMultiplier) / exposureRangeNs.lower.toFloat()
            shortIso = (baseIso * remainingRatio).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
        }

        // Long Exposure calculation (prefer increasing exposure time first, capped at 1/60s for 60fps capture)
        // 1/60s = ~16,666,666 ns
        val maxShutterFor60FpsNs = minOf(16_666_666L, exposureRangeNs.upper)
        var longExposureNs = (baseExposureNs * longMultiplier).toLong().coerceIn(exposureRangeNs.lower, maxShutterFor60FpsNs)
        var longIso = baseIso

        if ((baseExposureNs * longMultiplier) > maxShutterFor60FpsNs) {
            // Shutter speed capped by frame rate -> boost ISO for long exposure
            val neededBoost = (baseExposureNs * longMultiplier) / maxShutterFor60FpsNs.toFloat()
            longIso = (baseIso * neededBoost).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
        }

        // Compute actual realized EV delta
        val actualShortLumFactor = (shortExposureNs.toDouble() * shortIso)
        val actualLongLumFactor = (longExposureNs.toDouble() * longIso)
        val realizedEvDelta = if (actualShortLumFactor > 0) {
            (ln(actualLongLumFactor / actualShortLumFactor) / ln(2.0)).toFloat().coerceIn(0.5f, 6.0f)
        } else {
            targetEvDelta
        }

        return HdrExposurePair(
            shortExposureNs = shortExposureNs,
            shortIso = shortIso,
            longExposureNs = longExposureNs,
            longIso = longIso,
            evDelta = realizedEvDelta,
            confidence = 1.0f,
            sceneDynamicRangeEv = metrics.estimatedDynamicRangeEv
        )
    }

    private fun createFallbackPair(
        baseExposureNs: Long,
        baseIso: Int,
        exposureRangeNs: Range<Long>,
        isoRange: Range<Int>
    ): HdrExposurePair {
        val shortNs = (baseExposureNs * 0.35f).toLong().coerceIn(exposureRangeNs.lower, exposureRangeNs.upper)
        val longNs = minOf((baseExposureNs * 1.8f).toLong(), 16_666_666L).coerceIn(exposureRangeNs.lower, exposureRangeNs.upper)
        return HdrExposurePair(
            shortExposureNs = shortNs,
            shortIso = baseIso,
            longExposureNs = longNs,
            longIso = (baseIso * 1.2f).toInt().coerceIn(isoRange.lower, isoRange.upper),
            evDelta = 2.0f
        )
    }
}
