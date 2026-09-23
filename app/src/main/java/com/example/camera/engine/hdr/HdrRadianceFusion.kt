package com.example.camera.engine.hdr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Natural Smartphone-Style Computational HDR Fusion Engine.
 *
 * Designed specifically for ISP-processed camera frames:
 * 1. Reference Frame Preservation: The base frame (0 EV) defines the natural scene exposure,
 *    white balance, skin tones, and tone curve. Midtones are preserved with 100% fidelity.
 * 2. Conservative Highlight Recovery: The short exposure frame (-EV) is ONLY blended into
 *    regions where the base frame approaches or enters clipping (highlights > 82% luminance).
 *    Instead of physical radiance multiplication (which causes color ratio explosions, purple fringes,
 *    and black edge artifacts), it seamlessly maps unclipped short-frame detail and chromaticity.
 * 3. Conservative Shadow Noise Reduction: Long exposure (+EV) is gently applied only in deep shadows
 *    (luminance < 18%) when static, without lifting the black point.
 * 4. Border Safety Margin: Edge feathering smoothly tapers secondary frame weights to 0 at image boundaries,
 *    completely eliminating edge tearing, purple borders, and black margins.
 */
class HdrRadianceFusion {

    companion object {
        // Fast smoothstep interpolation
        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private const val LUT_SIZE = 4095
        val LINEAR_TO_SRGB_BYTE_LUT = IntArray(LUT_SIZE + 1) { i ->
            val norm = i.toFloat() / LUT_SIZE.toFloat()
            (norm * 255.0f).roundToInt().coerceIn(0, 255)
        }

        @JvmStatic
        fun linearToSrgbByte(linearVal: Float): Int {
            val idx = (linearVal.coerceIn(0f, 1f) * LUT_SIZE).roundToInt()
            return LINEAR_TO_SRGB_BYTE_LUT[idx]
        }
    }

    /**
     * Fuses an ISP-processed pixel conservatively.
     * Preserves natural ISP white balance, exposure, and tone curve.
     *
     * @param edgeFeather Spatial feathering [0.0 at frame border, 1.0 inside safe margin]
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
        longExposureRatio: Float? = null,
        edgeFeather: Float = 1.0f
    ) {
        val bR = baseR.coerceIn(0, 255) / 255.0f
        val bG = baseG.coerceIn(0, 255) / 255.0f
        val bB = baseB.coerceIn(0, 255) / 255.0f
        val baseLuma = 0.2126f * bR + 0.7152f * bG + 0.0722f * bB
        val maxChannel = max(bR, max(bG, bB))

        // Default: 100% pristine reference frame
        var finalR = bR
        var finalG = bG
        var finalB = bB

        val staticFactor = (1.0f - motionConfidence).coerceIn(0f, 1f) * edgeFeather.coerceIn(0f, 1f)

        // 1. Conservative Highlight Recovery from Short Exposure
        // ONLY active where base frame is near or in saturation (maxChannel > 0.82)
        if (shortR != null && shortG != null && shortB != null && staticFactor > 0.15f && maxChannel > 0.82f) {
            val sR = shortR.coerceIn(0, 255) / 255.0f
            val sG = shortG.coerceIn(0, 255) / 255.0f
            val sB = shortB.coerceIn(0, 255) / 255.0f
            val shortLuma = 0.2126f * sR + 0.7152f * sG + 0.0722f * sB

            // Blend weight ramps up smoothly as base frame approaches clipping (0.82 -> 0.98)
            val highlightBlend = smoothstep(0.82f, 0.98f, maxChannel) * staticFactor

            if (highlightBlend > 0.01f) {
                // Short frame highlight normalization:
                // Rather than multiplying by 4x or 8x (which destroys color balance and causes purple fringing),
                // scale the unclipped short frame detail so its knee smoothly connects with the base threshold.
                // This recovers cloud textures and sky blue without purple halos or tone mismatch.
                val kneeScale = 0.82f / max(0.20f, shortLuma)
                val targetR = (sR * kneeScale).coerceIn(0f, 1f)
                val targetG = (sG * kneeScale).coerceIn(0f, 1f)
                val targetB = (sB * kneeScale).coerceIn(0f, 1f)

                // Blend chromaticity & detail smoothly into the clipped highlight
                finalR = bR * (1.0f - highlightBlend) + targetR * highlightBlend
                finalG = bG * (1.0f - highlightBlend) + targetG * highlightBlend
                finalB = bB * (1.0f - highlightBlend) + targetB * highlightBlend
            }
        }

        // 2. Conservative Shadow Noise Reduction from Long Exposure
        // ONLY active in deep shadows (baseLuma < 0.18) on strictly static pixels
        if (longR != null && longG != null && longB != null && staticFactor > 0.35f && baseLuma in 0.02f..0.18f) {
            val lR = longR.coerceIn(0, 255) / 255.0f
            val lG = longG.coerceIn(0, 255) / 255.0f
            val lB = longB.coerceIn(0, 255) / 255.0f

            // Shadow weight: gentle blend (up to 25%) to suppress shadow noise without lifting black level
            val shadowBlend = (1.0f - smoothstep(0.02f, 0.18f, baseLuma)) * 0.25f * staticFactor
            if (shadowBlend > 0.01f) {
                finalR = finalR * (1.0f - shadowBlend) + (lR * 0.5f).coerceIn(0f, 1f) * shadowBlend
                finalG = finalG * (1.0f - shadowBlend) + (lG * 0.5f).coerceIn(0f, 1f) * shadowBlend
                finalB = finalB * (1.0f - shadowBlend) + (lB * 0.5f).coerceIn(0f, 1f) * shadowBlend
            }
        }

        outRgb[0] = finalR.coerceIn(0f, 1f)
        outRgb[1] = finalG.coerceIn(0f, 1f)
        outRgb[2] = finalB.coerceIn(0f, 1f)
    }
}

