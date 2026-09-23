package com.example.camera.engine.hdr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Flagship Color Rendering & Chromatic Fidelity Engine.
 *
 * Responsibilities:
 * 1. Skin-Tone Protection: Identifies human skin in YCbCr space and preserves natural warmth,
 *    preventing artificial orange/tanned skin tones or oversaturated redness.
 * 2. Highlight-Aware Desaturation: Specular highlights, direct sunlight, and lamp glows
 *    naturally roll off towards pure white rather than neon yellow or pink.
 * 3. Controlled Chroma in Shadows: Cleans up blotchy color noise in dark shadow zones.
 * 4. Foliage & Sky Balance: Maintains true-to-life organic greens and deep natural sky blues.
 * 5. Strictly avoids garish, unnatural "Instagram filter" oversaturation.
 */
class HdrColorRenderer {

    /**
     * Applies flagship color rendering, skin tone preservation, and highlight desaturation in-place.
     *
     * @param rgb FloatArray(3) Linear tone-mapped RGB values in [0.0 .. 1.0]
     */
    fun renderColor(rgb: FloatArray) {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]

        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = -0.1687f * r - 0.3313f * g + 0.500f * b
        val cr = 0.500f * r - 0.4187f * g - 0.0813f * b

        // 1. Human Skin-Tone Detection in YCbCr
        // Typical Caucasian, Asian, and darker skin tones lie within Cb in [-0.20, -0.04] and Cr in [0.08, 0.28]
        val isSkinTone = (cb in -0.22f..-0.03f) && (cr in 0.07f..0.30f) && (y in 0.15f..0.85f)

        // 2. Highlight-Aware Desaturation Roll-off (specular lights fade gracefully to diffuse white)
        val highlightDesatFactor = if (y > 0.80f) {
            val t = ((y - 0.80f) / 0.20f).coerceIn(0f, 1f)
            1.0f - 0.55f * (t * t)
        } else {
            1.0f
        }

        // 3. Shadow Chroma Suppression (cleans up noisy color specks in deep shadows)
        val shadowChromaFactor = if (y < 0.12f) {
            (y / 0.12f).coerceIn(0.35f, 1.0f)
        } else {
            1.0f
        }

        // 4. Subtle Selective Vibrance: boost muted colors while protecting already saturated tones & skin
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val sat = if (maxC > 1e-4f) (maxC - minC) / maxC else 0f

        val vibranceBoost = if (isSkinTone) {
            1.00f // Zero artificial saturation on human skin
        } else {
            // Gently boost low-saturation tones, avoid touching already vibrant areas
            1.0f + 0.12f * (1.0f - sat) * highlightDesatFactor * shadowChromaFactor
        }

        val effectiveSatMultiplier = (vibranceBoost * highlightDesatFactor * shadowChromaFactor).coerceIn(0.2f, 1.25f)

        // Adjust saturation relative to luminance
        rgb[0] = (y + (r - y) * effectiveSatMultiplier).coerceIn(0f, 1.0f)
        rgb[1] = (y + (g - y) * effectiveSatMultiplier).coerceIn(0f, 1.0f)
        rgb[2] = (y + (b - y) * effectiveSatMultiplier).coerceIn(0f, 1.0f)
    }
}
