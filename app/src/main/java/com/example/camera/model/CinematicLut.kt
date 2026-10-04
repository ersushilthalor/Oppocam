package com.example.camera.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * Authentic Cinematic Look-Up Table (LUT) Presets for Cinema & Video grading.
 *
 * Implements genuine film-style color grades affecting:
 * - Colour (channel cross-talk, chromatic split, and gamut separation)
 * - Contrast & S-curve dynamics
 * - Blacks (deep darks anchoring vs lifted matte blacks)
 * - Shadows (lower tonal range sculpting)
 * - Midtones (skin-tone fidelity & dynamic separation)
 * - Highlights (shoulder curve sculpting)
 * - Whites (specular roll-off & highlight retention)
 * - Highlight & Shadow roll-off (organic transition without harsh clipping)
 * - Saturation & Vibrance (chromatic density with skin protection)
 * - Overall Tonal Curve
 */
enum class CinematicLut(
    val id: String,
    val label: String,
    val description: String,
    val category: String,
    val accentColor: Color,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val vibrance: Float = 0.0f,
    val blacksToe: Float = 0.0f,
    val shadowToe: Float = 0.0f,
    val midtonesGain: Float = 1.0f,
    val highlightsGain: Float = 1.0f,
    val whitesGain: Float = 1.0f,
    val highlightRollOff: Float = 0.5f,
    val shadowRollOff: Float = 0.0f,
    val warmCoolOffset: Float = 0.0f,
    val matrixValues: FloatArray? = null
) {
    // 1. OFF - Unprocessed sensor pass-through
    OFF(
        id = "off",
        label = "Off",
        description = "Neutral sensor pass-through with no LUT grading applied",
        category = "Master",
        accentColor = Color(0xFF9E9E9E),
        contrast = 1.0f,
        saturation = 1.0f,
        vibrance = 0.0f,
        blacksToe = 0.0f,
        shadowToe = 0.0f,
        midtonesGain = 1.0f,
        highlightsGain = 1.0f,
        whitesGain = 1.0f,
        highlightRollOff = 0.5f,
        shadowRollOff = 0.0f,
        warmCoolOffset = 0.0f,
        matrixValues = null
    ),

    // 2. STANDARD - Refined ITU-R BT.709 Broadcast Film Standard
    STANDARD(
        id = "standard",
        label = "Standard",
        description = "Reference film print: natural skin-tone fidelity, open organic shadows, anchored inky blacks, and smooth highlight roll-off",
        category = "Standard",
        accentColor = Color(0xFFFFD54F),
        contrast = 1.10f,
        saturation = 1.04f,
        vibrance = 0.06f,
        blacksToe = -0.015f,
        shadowToe = 0.02f,
        midtonesGain = 1.03f,
        highlightsGain = 1.01f,
        whitesGain = 0.98f,
        highlightRollOff = 0.65f,
        shadowRollOff = 0.03f,
        warmCoolOffset = 0.02f,
        matrixValues = floatArrayOf(
            1.03f, 0.00f, -0.02f, 0f, 2f,
            -0.01f, 1.02f, -0.01f, 0f, 1f,
            -0.02f, -0.01f, 1.01f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 3. BLOCKBUSTER - Hollywood 35mm Motion Picture Look
    BLOCKBUSTER(
        id = "blockbuster",
        label = "Blockbuster",
        description = "Hollywood cinema: bold punchy S-curve, deep inky blacks, teal-cyan shadows, luminous warm amber skin tones, and golden highlight roll-off",
        category = "Hollywood",
        accentColor = Color(0xFF00E5FF),
        contrast = 1.25f,
        saturation = 1.15f,
        vibrance = 0.18f,
        blacksToe = -0.045f,
        shadowToe = -0.03f,
        midtonesGain = 1.08f,
        highlightsGain = 1.04f,
        whitesGain = 0.95f,
        highlightRollOff = 0.74f,
        shadowRollOff = -0.02f,
        warmCoolOffset = 0.06f,
        matrixValues = floatArrayOf(
            1.22f, -0.06f, -0.08f, 0f, 7f,
            -0.03f, 1.08f, 0.02f, 0f, 2f,
            -0.10f, 0.05f, 1.24f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 4. THRILLER - Moody Neo-Noir & Suspense
    THRILLER(
        id = "thriller",
        label = "Thriller",
        description = "Neo-noir suspense: tense micro-contrast, cold slate-blue shadows, deep darks, clinical desaturation, and piercing crisp specular highlights",
        category = "Dramatic",
        accentColor = Color(0xFF4FC3F7),
        contrast = 1.28f,
        saturation = 0.80f,
        vibrance = -0.10f,
        blacksToe = -0.055f,
        shadowToe = -0.04f,
        midtonesGain = 0.95f,
        highlightsGain = 1.05f,
        whitesGain = 1.03f,
        highlightRollOff = 0.46f,
        shadowRollOff = -0.04f,
        warmCoolOffset = -0.18f,
        matrixValues = floatArrayOf(
            0.90f, -0.02f, 0.02f, 0f, -4f,
            -0.03f, 0.98f, 0.04f, 0f, 0f,
            0.03f, 0.06f, 1.20f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 5. WEDDING - Fine-Art Romance & Editorial
    WEDDING(
        id = "wedding",
        label = "Wedding",
        description = "Fine-art romance: luminous pastel tones, lifted velvety charcoal blacks, glowing shadow toe, creamy skin midtones, and dreamy soft highlight shoulder",
        category = "Fine Art",
        accentColor = Color(0xFFF48FB1),
        contrast = 1.04f,
        saturation = 1.06f,
        vibrance = 0.12f,
        blacksToe = 0.04f,
        shadowToe = 0.07f,
        midtonesGain = 1.10f,
        highlightsGain = 1.02f,
        whitesGain = 0.96f,
        highlightRollOff = 0.86f,
        shadowRollOff = 0.06f,
        warmCoolOffset = 0.14f,
        matrixValues = floatArrayOf(
            1.12f, 0.02f, -0.04f, 0f, 6f,
            0.01f, 1.04f, -0.02f, 0f, 3f,
            -0.04f, 0.00f, 0.94f, 0f, -2f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 6. CUSTOM - User-imported .cube 3D LUT
    CUSTOM(
        id = "custom_cube",
        label = "Custom (.cube)",
        description = "User-imported 3D LUT (.cube file) directly applied into camera sensor pipeline",
        category = "Custom",
        accentColor = Color(0xFFAB47BC),
        contrast = 1.0f,
        saturation = 1.0f,
        vibrance = 0.0f,
        blacksToe = 0.0f,
        shadowToe = 0.0f,
        midtonesGain = 1.0f,
        highlightsGain = 1.0f,
        whitesGain = 1.0f,
        highlightRollOff = 0.5f,
        shadowRollOff = 0.0f,
        warmCoolOffset = 0.0f,
        matrixValues = null
    );

    val isOff: Boolean get() = this == OFF
    val isCustom: Boolean get() = this == CUSTOM
    val isVibrantGreenLut: Boolean get() = false

    val blacks: Float get() = blacksToe
    val shadows: Float get() = shadowToe
    val midtones: Float get() = midtonesGain
    val highlights: Float get() = highlightsGain
    val whites: Float get() = whitesGain
    val shadowRolloff: Float get() = shadowRollOff

    companion object {
        // Backwards compatibility mappings
        val NONE: CinematicLut get() = OFF
        val REC_709: CinematicLut get() = STANDARD
        val TEAL_ORANGE: CinematicLut get() = BLOCKBUSTER
        val COOL_THRILLER: CinematicLut get() = THRILLER
        val WARM_SUNSET: CinematicLut get() = WEDDING
        val KODAK_2383: CinematicLut get() = BLOCKBUSTER
        val FUJI_ETERNA: CinematicLut get() = WEDDING
        val BLEACH_BYPASS: CinematicLut get() = THRILLER
        val MUTED_FILM: CinematicLut get() = THRILLER
        val VIBRANT_GREEN: CinematicLut get() = STANDARD
        val PUNCHY_GREEN: CinematicLut get() = STANDARD
        val FILMIC_NEUTRAL: CinematicLut get() = STANDARD
        val WARM_CINEMA: CinematicLut get() = WEDDING
        val COOL_DRAMATIC: CinematicLut get() = THRILLER
        val HIGH_CONTRAST_CINEMA: CinematicLut get() = THRILLER
        val SOFT_FILM: CinematicLut get() = WEDDING

        val displayPresets: List<CinematicLut> = listOf(
            OFF,
            STANDARD,
            BLOCKBUSTER,
            THRILLER,
            WEDDING,
            CUSTOM
        )

        fun fromIdOrName(identifier: String?): CinematicLut {
            if (identifier.isNullOrBlank()) return OFF
            return when (identifier.uppercase().trim()) {
                "OFF", "NONE", "CLEAN_LOG" -> OFF
                "STANDARD", "REC_709", "REC709", "FILMIC_NEUTRAL", "VIBRANT_GREEN", "PUNCHY_GREEN" -> STANDARD
                "BLOCKBUSTER", "TEAL_ORANGE", "KODAK_2383" -> BLOCKBUSTER
                "THRILLER", "COOL_THRILLER", "COOL_DRAMATIC", "BLEACH_BYPASS", "HIGH_CONTRAST_CINEMA", "MUTED_FILM" -> THRILLER
                "WEDDING", "WARM_SUNSET", "WARM_CINEMA", "FUJI_ETERNA", "SOFT_FILM" -> WEDDING
                "CUSTOM", "CUSTOM_CUBE" -> CUSTOM
                else -> values().find { it.name.equals(identifier, ignoreCase = true) || it.id.equals(identifier, ignoreCase = true) } ?: OFF
            }
        }
    }

    /**
     * Generates a Compose [ColorFilter] for real-time live viewfinder monitoring.
     */
    fun toColorFilter(): ColorFilter? {
        val vals = matrixValues ?: return null
        return ColorFilter.colorMatrix(ColorMatrix(vals))
    }

    /**
     * Generates an Android [android.graphics.ColorMatrix] for image post-processing & viewfinder layer.
     */
    fun toAndroidColorMatrix(): android.graphics.ColorMatrix? {
        val vals = matrixValues ?: return null
        return android.graphics.ColorMatrix(vals)
    }
}
