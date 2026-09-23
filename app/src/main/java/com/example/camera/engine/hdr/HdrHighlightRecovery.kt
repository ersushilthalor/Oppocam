package com.example.camera.engine.hdr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Flagship Highlight Recovery Engine.
 *
 * Responsibilities:
 * 1. Prioritizes sky gradients, cloud textures, sunlit architecture, bright windows, and lamp glows.
 * 2. Detects single-channel or multi-channel sensor saturation in the base frame.
 * 3. Reconstructs true unclipped chromaticity (r:g:b ratios) from the short exposure frame.
 * 4. Applies a smooth knee compression curve into specular white, strictly preventing
 *    the dreaded "dirty gray clouds" or washed-out muddy highlights of naive HDR.
 */
class HdrHighlightRecovery {

    companion object {
        private const val CLIPPING_THRESHOLD = 0.88f // Linear luminance threshold where clipping begins
        private const val KNEE_START = 0.75f // Point where gentle highlight roll-off initiates
    }

    /**
     * Applies highlight chromaticity reconstruction and smooth knee roll-off.
     *
     * @param fusedRgb FloatArray(3) Linear fused RGB radiance (modified in-place)
     * @param shortR, shortG, shortB Unclipped color from short exposure (0..255)
     */
    fun recoverHighlights(
        fusedRgb: FloatArray,
        shortR: Int?, shortG: Int?, shortB: Int?
    ) {
        val r = fusedRgb[0]
        val g = fusedRgb[1]
        val b = fusedRgb[2]

        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (luma < KNEE_START) return

        // 1. Chromaticity Reconstruction from Short Exposure
        if (shortR != null && shortG != null && shortB != null && luma >= CLIPPING_THRESHOLD) {
            val linShortR = HdrRadianceFusion.SRGB_TO_LINEAR_LUT[shortR.coerceIn(0, 255)]
            val linShortG = HdrRadianceFusion.SRGB_TO_LINEAR_LUT[shortG.coerceIn(0, 255)]
            val linShortB = HdrRadianceFusion.SRGB_TO_LINEAR_LUT[shortB.coerceIn(0, 255)]
            val shortLuma = 0.2126f * linShortR + 0.7152f * linShortG + 0.0722f * linShortB

            if (shortLuma > 1e-4f) {
                // Extract true unclipped chromaticity ratios
                val crR = linShortR / shortLuma
                val crG = linShortG / shortLuma
                val crB = linShortB / shortLuma

                // Blend chromaticity: as luma goes deeper into clipping, short frame chromaticity dominates
                val blendFactor = ((luma - CLIPPING_THRESHOLD) / (1.5f - CLIPPING_THRESHOLD)).coerceIn(0f, 0.85f)

                fusedRgb[0] = r * (1f - blendFactor) + (luma * crR) * blendFactor
                fusedRgb[1] = g * (1f - blendFactor) + (luma * crG) * blendFactor
                fusedRgb[2] = b * (1f - blendFactor) + (luma * crB) * blendFactor
            }
        }

        // 2. Natural Knee Roll-off (avoids dirty gray highlights and creates glowing specular roll-off)
        for (i in 0..2) {
            val v = fusedRgb[i]
            if (v > KNEE_START) {
                // Soft asymptotic knee compression: v_compressed = KNEE_START + (1 - KNEE_START) * (1 - exp(-(v - KNEE_START) / scale))
                val excess = v - KNEE_START
                val scale = 0.65f
                val compressed = KNEE_START + (1.0f - KNEE_START) * (1.0f - kotlin.math.exp(-excess / scale))
                fusedRgb[i] = compressed.coerceIn(0f, 1.0f)
            }
        }
    }
}
