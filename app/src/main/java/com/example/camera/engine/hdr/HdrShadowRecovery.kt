package com.example.camera.engine.hdr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Flagship Shadow Recovery Engine.
 *
 * Responsibilities:
 * 1. Recovers dark areas without creating the typical washed-out, lifted, fake HDR "gray shadows" look.
 * 2. Strict Black Point Preservation: true blacks (0.00 .. 0.02) remain deep, rich, and anchored.
 * 3. Applies a targeted toe contrast curve to mid-shadows (0.03 .. 0.20), revealing rich textures
 *    in hair, fabrics, dark foliage, and night architecture.
 * 4. Preserves authentic shadow contrast and realistic optical falloff.
 */
class HdrShadowRecovery {

    companion object {
        private const val SHADOW_ZONE_LIMIT = 0.22f
        private const val BLACK_POINT_ANCHOR = 0.015f
    }

    /**
     * Applies photographic shadow recovery to linear RGB values in-place.
     */
    fun recoverShadows(
        fusedRgb: FloatArray,
        shadowLiftAmount: Float = 1.15f
    ) {
        val r = fusedRgb[0]
        val g = fusedRgb[1]
        val b = fusedRgb[2]

        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (luma <= BLACK_POINT_ANCHOR || luma >= SHADOW_ZONE_LIMIT) return

        // Smooth toe curve: zero modification at BLACK_POINT_ANCHOR, peaks around luma = 0.08, rolls off to 0 at SHADOW_ZONE_LIMIT
        val normShadow = (luma - BLACK_POINT_ANCHOR) / (SHADOW_ZONE_LIMIT - BLACK_POINT_ANCHOR)
        val toeWeight = kotlin.math.sin(normShadow * Math.PI.toFloat()) // 0 at edges, 1.0 at center

        // Controlled shadow lift factor
        val lift = 1.0f + (shadowLiftAmount - 1.0f) * toeWeight * 0.40f

        val newLuma = luma * lift
        val lumaRatio = newLuma / luma

        // Scale RGB channels while preserving chromaticity
        fusedRgb[0] = (r * lumaRatio).coerceIn(0f, 1.0f)
        fusedRgb[1] = (g * lumaRatio).coerceIn(0f, 1.0f)
        fusedRgb[2] = (b * lumaRatio).coerceIn(0f, 1.0f)
    }
}
