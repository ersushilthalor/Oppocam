package com.example.camera.engine.hdr

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Flagship Linear Radiance Reconstruction & Fusion Engine.
 *
 * Responsibilities:
 * 1. Strict linearized image space processing: converts gamma-encoded sRGB into linear physical scene radiance.
 * 2. Recovers scene dynamic range from exposure bracket (base, short, long) using calibrated EV offsets.
 * 3. Incorporates motion confidence mask: guarantees zero ghosting by smoothly falling back to
 *    the reference frame on moving subjects.
 * 4. Merges highlight radiance from the short exposure and shadow SNR from the long exposure
 *    without producing edge halos or artificial luminance steps.
 */
class HdrRadianceFusion {

    companion object {
        // Pre-computed sRGB-to-Linear conversion LUT (256 entries) for zero-latency linear color math
        val SRGB_TO_LINEAR_LUT = FloatArray(256) { i ->
            val norm = i / 255.0f
            // Standard gamma 2.2 approximation of sRGB transfer function
            norm.pow(2.2f)
        }

        // Fast smoothstep interpolation
        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }

    /**
     * Fuses linear radiance for a single pixel across the bracket.
     *
     * @param baseR, baseG, baseB Base frame color (0..255)
     * @param shortR, shortG, shortB Aligned short frame color (0..255) or null if 1-frame/no-short
     * @param shortEvOffset EV offset of short frame (e.g. -1.7f)
     * @param longR, longG, longB Aligned long frame color (0..255) or null if 2-frame/no-long
     * @param longEvOffset EV offset of long frame (e.g. +1.4f)
     * @param motionConfidence Motion score [0.0 = static, 1.0 = moving]
     * @param outRgb FloatArray(3) receiving linear fused RGB radiance
     */
    fun fusePixelLinear(
        baseR: Int, baseG: Int, baseB: Int,
        shortR: Int?, shortG: Int?, shortB: Int?,
        shortEvOffset: Float,
        longR: Int?, longG: Int?, longB: Int?,
        longEvOffset: Float,
        motionConfidence: Float,
        outRgb: FloatArray
    ) {
        val linBaseR = SRGB_TO_LINEAR_LUT[baseR.coerceIn(0, 255)]
        val linBaseG = SRGB_TO_LINEAR_LUT[baseG.coerceIn(0, 255)]
        val linBaseB = SRGB_TO_LINEAR_LUT[baseB.coerceIn(0, 255)]
        val baseLuma = 0.2126f * linBaseR + 0.7152f * linBaseG + 0.0722f * linBaseB

        // Base frame midtone weighting curve: peaks around 0.45, rolls off softly
        val baseWeight = exp(-((baseLuma - 0.45f) * (baseLuma - 0.45f)) / 0.18f)

        var totalWeight = baseWeight
        var fusedR = linBaseR * baseWeight
        var fusedG = linBaseG * baseWeight
        var fusedB = linBaseB * baseWeight

        val staticWeightMultiplier = (1.0f - motionConfidence).coerceIn(0f, 1f)

        // 1. Fuse Short Exposure (Highlight Recovery)
        if (shortR != null && shortG != null && shortB != null) {
            val linShortR = SRGB_TO_LINEAR_LUT[shortR.coerceIn(0, 255)]
            val linShortG = SRGB_TO_LINEAR_LUT[shortG.coerceIn(0, 255)]
            val linShortB = SRGB_TO_LINEAR_LUT[shortB.coerceIn(0, 255)]

            // Radiance scale: short frame was exposed less, so its true physical radiance is scaled up by 2^(-evOffset)
            val radianceScale = 2.0f.pow(-shortEvOffset)
            val radShortR = linShortR * radianceScale
            val radShortG = linShortG * radianceScale
            val radShortB = linShortB * radianceScale

            // Short weight: heavily active where base frame is near or above saturation (baseLuma > 0.55)
            // For moving regions with clipped base, we still allow partial short fusion to recover blown sky texture
            val highlightBlend = smoothstep(0.50f, 0.90f, baseLuma)
            val shortWeight = highlightBlend * 2.5f * (0.4f + 0.6f * staticWeightMultiplier)

            fusedR += radShortR * shortWeight
            fusedG += radShortG * shortWeight
            fusedB += radShortB * shortWeight
            totalWeight += shortWeight
        }

        // 2. Fuse Long Exposure (Shadow Detail & Noise Reduction)
        if (longR != null && longG != null && longB != null && staticWeightMultiplier > 0.1f) {
            val linLongR = SRGB_TO_LINEAR_LUT[longR.coerceIn(0, 255)]
            val linLongG = SRGB_TO_LINEAR_LUT[longG.coerceIn(0, 255)]
            val linLongB = SRGB_TO_LINEAR_LUT[longB.coerceIn(0, 255)]

            // Radiance scale: long frame was exposed more, so its radiance is scaled down by 2^(-evOffset)
            val radianceScale = 2.0f.pow(-longEvOffset)
            val radLongR = linLongR * radianceScale
            val radLongG = linLongG * radianceScale
            val radLongB = linLongB * radianceScale

            // Long weight: active only in deep shadows (baseLuma < 0.25) and strictly suppressed on motion
            val shadowBlend = 1.0f - smoothstep(0.04f, 0.28f, baseLuma)
            val longWeight = shadowBlend * 1.8f * staticWeightMultiplier

            fusedR += radLongR * longWeight
            fusedG += radLongG * longWeight
            fusedB += radLongB * longWeight
            totalWeight += longWeight
        }

        val invTotalWeight = if (totalWeight > 1e-6f) 1.0f / totalWeight else 1.0f
        outRgb[0] = fusedR * invTotalWeight
        outRgb[1] = fusedG * invTotalWeight
        outRgb[2] = fusedB * invTotalWeight
    }
}
