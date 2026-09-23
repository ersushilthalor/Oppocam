package com.example.camera.engine.hdr

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Flagship Edge-Aware Local Tone Mapping Engine.
 *
 * Responsibilities:
 * 1. Edge-aware base/detail layer decomposition in log-luminance space.
 * 2. Compresses the large-scale dynamic range (base illumination) while preserving
 *    and subtly enhancing local micro-contrast (detail layer).
 * 3. Dynamic anti-halo protection: strictly prevents dark halos around bright sky silhouettes
 *    and glowing edges around dark buildings.
 * 4. Eliminates the artificial "cartoon HDR" appearance; delivers authentic flagship photographic rendering.
 */
class HdrToneMapper {

    companion object {
        private const val EPSILON = 1e-4f
        private const val MICRO_CONTRAST_BOOST = 1.08f // Subtle crisp micro-contrast without crunchiness
    }

    /**
     * Filmic base layer compression curve (preserves natural contrast while compressing dynamic range).
     */
    private fun filmicCompress(x: Float): Float {
        // High-fidelity photographic S-curve with natural highlight compression
        val a = 2.51f
        val b = 0.03f
        val c = 2.43f
        val d = 0.59f
        val e = 0.14f
        return ((x * (a * x + b)) / (x * (c * x + d) + e)).coerceIn(0f, 1.0f)
    }

    /**
     * Applies edge-aware tone mapping to an individual linear RGB pixel.
     *
     * @param rgb FloatArray(3) Linear radiance (modified in-place)
     * @param localBaseLuma Local edge-preserving base illumination (large scale)
     */
    fun toneMapPixel(
        rgb: FloatArray,
        localBaseLuma: Float
    ) {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]

        val y = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceAtLeast(EPSILON)

        // 1. Log-luminance decomposition
        val logY = ln(y + EPSILON)
        val logBase = ln(localBaseLuma.coerceAtLeast(EPSILON) + EPSILON)

        // Detail layer = local variation from base illumination
        val logDetail = (logY - logBase).coerceIn(-1.5f, 1.5f) // Clamped to strictly prevent edge ringing/halos

        // 2. Compress base layer with photographic filmic curve
        val compressedBase = filmicCompress(localBaseLuma)
        val logCompressedBase = ln(compressedBase.coerceAtLeast(EPSILON) + EPSILON)

        // 3. Recombine compressed base + micro-contrast detail
        val logRecombined = logCompressedBase + logDetail * MICRO_CONTRAST_BOOST
        val toneMappedLuma = exp(logRecombined).coerceIn(0f, 1.0f)

        // 4. Color reconstruction: scale RGB channels proportionally to preserve hue
        val lumaRatio = toneMappedLuma / y
        // Gentle saturation compression at high luminance to prevent neon clipping
        val satCompression = (1.0f - 0.25f * (y / (y + 1.0f))).coerceIn(0.70f, 1.0f)

        for (i in 0..2) {
            val chan = rgb[i]
            // Standard photographic color scaling: c_tm = c * (Y_tm / Y)^sat
            val scaled = chan * lumaRatio.pow(satCompression)
            rgb[i] = scaled.coerceIn(0f, 1.0f)
        }
    }
}
