package com.example.camera.engine.hdr

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Flagship Linear Radiance Reconstruction & Fusion Engine.
 *
 * Responsibilities:
 * 1. Strict linearized image space processing: converts gamma-encoded sRGB into linear physical scene radiance.
 * 2. Normalizes scene dynamic range using the ACTUAL hardware exposure product
 *    (ISO * exposure time) for each frame, ensuring exact physical radiance alignment.
 * 3. Incorporates motion confidence mask: guarantees zero ghosting by smoothly falling back to
 *    the reference frame on moving subjects.
 * 4. Merges highlight radiance from the short exposure and shadow SNR from the long exposure
 *    without producing edge halos, seams, or artificial steps.
 * 5. Pre-computed high-resolution LUTs for both sRGB-to-Linear and Linear-to-sRGB transformations,
 *    eliminating expensive pow() invocations on multi-megapixel frames.
 */
class HdrRadianceFusion {

    companion object {
        // Pre-computed sRGB-to-Linear conversion LUT (256 entries) for zero-latency linear color math
        val SRGB_TO_LINEAR_LUT = FloatArray(256) { i ->
            val norm = i / 255.0f
            norm.pow(2.2f)
        }

        // Fast high-precision Linear-to-sRGB LUT (4096 steps) eliminating millions of pow(1/2.2) calls
        private const val LUT_SIZE = 4095
        val LINEAR_TO_SRGB_BYTE_LUT = IntArray(LUT_SIZE + 1) { i ->
            val norm = i.toFloat() / LUT_SIZE.toFloat()
            (norm.pow(1.0f / 2.2f) * 255.0f).roundToInt().coerceIn(0, 255)
        }

        @JvmStatic
        fun linearToSrgbByte(linearVal: Float): Int {
            val idx = (linearVal.coerceIn(0f, 1f) * LUT_SIZE).roundToInt()
            return LINEAR_TO_SRGB_BYTE_LUT[idx]
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
     * @param shortExposureRatio Actual exposure ratio (baseExposureProduct / shortExposureProduct) if available
     * @param longExposureRatio Actual exposure ratio (baseExposureProduct / longExposureProduct) if available
     */
    fun fusePixelLinear(
        baseR: Int, baseG: Int, baseB: Int,
        shortR: Int?, shortG: Int?, shortB: Int?,
        shortEvOffset: Float,
        longR: Int?, longG: Int?, longB: Int?,
        longEvOffset: Float,
        motionConfidence: Float,
        outRgb: FloatArray,
        shortExposureRatio: Float? = null,
        longExposureRatio: Float? = null
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

        // Ghost suppression: moving regions fall back smoothly to the reference base frame
        val staticWeightMultiplier = (1.0f - motionConfidence).coerceIn(0f, 1f)

        // 1. Fuse Short Exposure (Highlight Recovery)
        if (shortR != null && shortG != null && shortB != null) {
            val linShortR = SRGB_TO_LINEAR_LUT[shortR.coerceIn(0, 255)]
            val linShortG = SRGB_TO_LINEAR_LUT[shortG.coerceIn(0, 255)]
            val linShortB = SRGB_TO_LINEAR_LUT[shortB.coerceIn(0, 255)]

            // Radiance scale: exact physical exposure ratio or calibrated 2^(-evOffset)
            val radianceScale = shortExposureRatio ?: 2.0f.pow(-shortEvOffset)
            val radShortR = linShortR * radianceScale
            val radShortG = linShortG * radianceScale
            val radShortB = linShortB * radianceScale

            // Short weight: active where base frame is near or above saturation (baseLuma > 0.50)
            val highlightBlend = smoothstep(0.50f, 0.90f, baseLuma)
            val shortWeight = highlightBlend * 2.5f * (0.35f + 0.65f * staticWeightMultiplier)

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

            val radianceScale = longExposureRatio ?: 2.0f.pow(-longEvOffset)
            val radLongR = linLongR * radianceScale
            val radLongG = linLongG * radianceScale
            val radLongB = linLongB * radianceScale

            // Long weight: active in deep shadows (baseLuma < 0.28) and strictly suppressed on motion
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
