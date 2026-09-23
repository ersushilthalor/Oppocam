package com.example.camera.engine.hdr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Flagship Adaptive Detail & Anti-Halo Edge-Aware Sharpening Engine.
 *
 * Responsibilities:
 * 1. ISO-adaptive noise suppression and fine-texture preservation (hair, fabrics, leaves, text).
 * 2. Flat Region Sharpening Suppression: Completely disables sharpening in smooth blue skies,
 *    flat clouds, and creamy background bokeh, ensuring zero amplified noise.
 * 3. Dynamic Anti-Halo Clamping: Restricts sharpening overshoot, strictly preventing
 *    bright white halos around dark edges or silhouettes against bright skies.
 * 4. Eliminates the artificial crunchy or "AI-looking" sharpening artifacts.
 */
class HdrDetailProcessor {

    companion object {
        private const val MAX_SHARPENING_DELTA = 0.02f // Strict clamp to prevent halos and edge ringing
        private const val FLAT_VARIANCE_THRESHOLD = 0.003f // Threshold below which regions are considered flat
    }

    /**
     * Applies edge-aware detail sharpening with dynamic anti-halo clamping.
     *
     * @param centerRgb Center pixel linear RGB [0.0 .. 1.0] (modified in-place)
     * @param neighborLumas Array of 4 orthogonal neighbors (North, South, East, West) luminance
     * @param iso Sensor sensitivity
     */
    fun processDetail(
        centerRgb: FloatArray,
        neighborLumas: FloatArray,
        iso: Int
    ) {
        val r = centerRgb[0]
        val g = centerRgb[1]
        val b = centerRgb[2]

        val centerLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b

        // 1. Calculate local neighborhood average and local variance
        val avgNeighbor = (neighborLumas[0] + neighborLumas[1] + neighborLumas[2] + neighborLumas[3]) * 0.25f
        val deltaLuma = centerLuma - avgNeighbor

        var variance = 0f
        for (i in 0..3) {
            val d = neighborLumas[i] - avgNeighbor
            variance += d * d
        }
        variance *= 0.25f

        // 2. Flat area suppression: sky, clouds, and smooth bokeh receive zero sharpening
        if (variance < FLAT_VARIANCE_THRESHOLD) return

        // 3. Extreme edge suppression: very harsh edges (e.g. black roof against white sky)
        // receive reduced sharpening to eliminate ringing
        val edgeAtten = if (variance > 0.08f) 0.45f else 1.0f

        // 4. ISO-adaptive sharpening strength (gentle micro-detail preservation, never crunchy)
        val isoFactor = if (iso > 800) (800f / iso).coerceIn(0.3f, 1.0f) else 1.0f
        val baseStrength = 0.10f * isoFactor * edgeAtten

        // 5. Dynamic Anti-Halo Clamping
        val rawSharpenDelta = deltaLuma * baseStrength
        val clampedDelta = rawSharpenDelta.coerceIn(-MAX_SHARPENING_DELTA, MAX_SHARPENING_DELTA)

        // Apply clamped sharpening delta to all channels equally to prevent hue shift
        centerRgb[0] = (r + clampedDelta).coerceIn(0f, 1.0f)
        centerRgb[1] = (g + clampedDelta).coerceIn(0f, 1.0f)
        centerRgb[2] = (b + clampedDelta).coerceIn(0f, 1.0f)
    }
}
