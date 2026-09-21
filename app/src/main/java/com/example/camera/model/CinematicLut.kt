package com.example.camera.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * Authentic Hollywood-inspired Cinematic Look-Up Table (LUT) Presets for Log & Cinema grading.
 *
 * Implements genuine film-style color grades affecting:
 * - Contrast & S-curve dynamics
 * - Tonal curve shaping (highlight shoulder & shadow toe)
 * - Highlight roll-off (organic compression without harsh clipping)
 * - Shadow rendering (lifted matte vs deep inky blacks)
 * - Saturation & chroma density
 * - Skin tone protection & radiant separation
 * - Color separation across RGB spectrum
 */
enum class CinematicLut(
    val id: String,
    val label: String,
    val description: String,
    val category: String,
    val accentColor: Color,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val highlightRollOff: Float = 0.5f,
    val shadowToe: Float = 0.0f,
    val warmCoolOffset: Float = 0.0f,
    val matrixValues: FloatArray? = null
) {
    // 1. Rec.709 Default Reference Standard
    REC_709(
        id = "rec_709",
        label = "Rec.709 Standard",
        description = "Official ITU-R BT.709 broadcast film standard: pure natural colors, balanced skin tones, reference neutral baseline",
        category = "Standard",
        accentColor = Color(0xFFFFD54F),
        contrast = 1.08f,
        saturation = 1.00f,
        highlightRollOff = 0.55f,
        shadowToe = 0.00f,
        warmCoolOffset = 0.00f,
        matrixValues = floatArrayOf(
            1.00f, 0.00f, 0.00f, 0f, 0f,
            0.00f, 1.00f, 0.00f, 0f, 0f,
            0.00f, 0.00f, 1.00f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 2. Kodak 2383 Print Stock (Hollywood Classic)
    KODAK_2383(
        id = "kodak_2383",
        label = "Kodak 2383 Print",
        description = "Hollywood iconic print stock: deep warm blacks, radiant golden skin tones, cyan sky roll-off, filmic density",
        category = "Film Print",
        accentColor = Color(0xFFFFB300),
        contrast = 1.18f,
        saturation = 1.10f,
        highlightRollOff = 0.70f,
        shadowToe = -0.02f,
        warmCoolOffset = 0.12f,
        matrixValues = floatArrayOf(
            1.14f, 0.01f, -0.04f, 0f, 5f,
            0.01f, 1.05f, -0.02f, 0f, 2f,
            -0.05f, -0.02f, 0.92f, 0f, -3f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 3. Fuji ETERNA (Arthouse / Gentle Film)
    FUJI_ETERNA(
        id = "fuji_eterna",
        label = "Fuji ETERNA",
        description = "Arthouse & indie cinema favorite: gentle organic contrast, soft highlight shoulder, luminous pastel skin tones",
        category = "Film Print",
        accentColor = Color(0xFF81C784),
        contrast = 1.04f,
        saturation = 0.92f,
        highlightRollOff = 0.85f,
        shadowToe = 0.06f,
        warmCoolOffset = -0.02f,
        matrixValues = floatArrayOf(
            1.02f, 0.02f, -0.01f, 0f, 4f,
            0.01f, 1.01f, -0.01f, 0f, 3f,
            -0.02f, 0.01f, 0.98f, 0f, 2f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 4. Teal & Orange (Hollywood Blockbuster)
    TEAL_ORANGE(
        id = "teal_orange",
        label = "Teal & Orange",
        description = "Blockbuster color separation: luminous amber skin tones sculpted against rich deep cyan-teal shadow contrast",
        category = "Hollywood",
        accentColor = Color(0xFF00E5FF),
        contrast = 1.22f,
        saturation = 1.15f,
        highlightRollOff = 0.62f,
        shadowToe = -0.04f,
        warmCoolOffset = 0.08f,
        matrixValues = floatArrayOf(
            1.22f, -0.06f, -0.08f, 0f, 8f,
            -0.03f, 1.08f, 0.03f, 0f, 2f,
            -0.10f, 0.06f, 1.24f, 0f, 6f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 5. Bleach Bypass (Gritty Silver Retention)
    BLEACH_BYPASS(
        id = "bleach_bypass",
        label = "Bleach Bypass",
        description = "Gritty silver retention process: striking high contrast, muted saturation, intense darks, specular punch",
        category = "Dramatic",
        accentColor = Color(0xFFE0E0E0),
        contrast = 1.32f,
        saturation = 0.72f,
        highlightRollOff = 0.40f,
        shadowToe = -0.08f,
        warmCoolOffset = -0.05f,
        matrixValues = floatArrayOf(
            1.24f, 0.01f, -0.02f, 0f, -4f,
            0.01f, 1.18f, 0.01f, 0f, -3f,
            -0.02f, 0.02f, 1.18f, 0f, -3f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 6. Warm Sunset / Golden Hour
    WARM_SUNSET(
        id = "warm_sunset",
        label = "Golden Hour",
        description = "Warm golden sunlight: radiant amber skin glow, creamy highlight shoulder, warm lifted shadow toe",
        category = "Warmth",
        accentColor = Color(0xFFFF7043),
        contrast = 1.14f,
        saturation = 1.12f,
        highlightRollOff = 0.68f,
        shadowToe = 0.04f,
        warmCoolOffset = 0.22f,
        matrixValues = floatArrayOf(
            1.16f, 0.02f, -0.05f, 0f, 6f,
            0.02f, 1.06f, -0.03f, 0f, 3f,
            -0.06f, -0.02f, 0.90f, 0f, -4f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 7. Cool Thriller / Neo-Noir
    COOL_THRILLER(
        id = "cool_thriller",
        label = "Neo Thriller",
        description = "Moody neo-noir thriller: slate-blue shadows, sculpted cheekbones, crisp specular highlights",
        category = "Dramatic",
        accentColor = Color(0xFF4FC3F7),
        contrast = 1.24f,
        saturation = 0.95f,
        highlightRollOff = 0.50f,
        shadowToe = -0.03f,
        warmCoolOffset = -0.16f,
        matrixValues = floatArrayOf(
            0.92f, -0.01f, 0.02f, 0f, -3f,
            -0.02f, 1.02f, 0.03f, 0f, 1f,
            0.02f, 0.05f, 1.18f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 8. Muted Arthouse
    MUTED_FILM(
        id = "muted_film",
        label = "Muted Arthouse",
        description = "Nostalgic vintage film: lifted matte charcoal shadows, desaturated earthy tones, delicate highlight roll-off",
        category = "Vintage",
        accentColor = Color(0xFFB0BEC5),
        contrast = 1.06f,
        saturation = 0.78f,
        highlightRollOff = 0.80f,
        shadowToe = 0.08f,
        warmCoolOffset = -0.04f,
        matrixValues = floatArrayOf(
            0.94f, 0.02f, 0.02f, 0f, 5f,
            0.02f, 0.95f, 0.02f, 0f, 5f,
            0.02f, 0.02f, 0.98f, 0f, 7f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    // 9. Clean Log (Mastering)
    NONE(
        id = "clean_log",
        label = "Clean Log",
        description = "Unmodified sensor curve for pure uncompressed Log mastering or natural video",
        category = "Master",
        accentColor = Color(0xFFCFD8DC),
        contrast = 1.0f,
        saturation = 1.0f,
        highlightRollOff = 0.5f,
        shadowToe = 0.0f,
        warmCoolOffset = 0.0f,
        matrixValues = null
    ),

    // 10. Custom Imported .cube LUT
    CUSTOM(
        id = "custom_cube",
        label = "Custom (.cube)",
        description = "User-imported 3D LUT (.cube file) directly applied into camera sensor pipeline",
        category = "Custom",
        accentColor = Color(0xFFAB47BC),
        contrast = 1.0f,
        saturation = 1.0f,
        highlightRollOff = 0.5f,
        shadowToe = 0.0f,
        warmCoolOffset = 0.0f,
        matrixValues = null
    ),

    // Legacy aliases for backwards compatibility
    FILMIC_NEUTRAL(
        id = "filmic_neutral",
        label = "Rec.709 Standard",
        description = "Official ITU-R BT.709 broadcast standard",
        category = "Standard",
        accentColor = Color(0xFFFFD54F),
        contrast = 1.08f,
        saturation = 1.00f,
        highlightRollOff = 0.55f,
        shadowToe = 0.00f,
        warmCoolOffset = 0.00f,
        matrixValues = null
    ),

    WARM_CINEMA(
        id = "warm_cinema",
        label = "Golden Hour",
        description = "Golden hour warm glow",
        category = "Warmth",
        accentColor = Color(0xFFFF7043),
        contrast = 1.14f,
        saturation = 1.12f,
        highlightRollOff = 0.68f,
        shadowToe = 0.04f,
        warmCoolOffset = 0.22f,
        matrixValues = null
    ),

    COOL_DRAMATIC(
        id = "cool_dramatic",
        label = "Neo Thriller",
        description = "Moody neo-noir thriller",
        category = "Dramatic",
        accentColor = Color(0xFF4FC3F7),
        contrast = 1.24f,
        saturation = 0.95f,
        highlightRollOff = 0.50f,
        shadowToe = -0.03f,
        warmCoolOffset = -0.16f,
        matrixValues = null
    ),

    HIGH_CONTRAST_CINEMA(
        id = "high_contrast_cinema",
        label = "Bleach Bypass",
        description = "Gritty silver retention process",
        category = "Dramatic",
        accentColor = Color(0xFFE0E0E0),
        contrast = 1.32f,
        saturation = 0.72f,
        highlightRollOff = 0.40f,
        shadowToe = -0.08f,
        warmCoolOffset = -0.05f,
        matrixValues = null
    ),

    SOFT_FILM(
        id = "soft_film",
        label = "Fuji ETERNA",
        description = "Soft gentle film emulation",
        category = "Film Print",
        accentColor = Color(0xFF81C784),
        contrast = 1.04f,
        saturation = 0.92f,
        highlightRollOff = 0.85f,
        shadowToe = 0.06f,
        warmCoolOffset = -0.02f,
        matrixValues = null
    );

    /**
     * Primary user-facing presets (excluding internal aliases).
     */
    companion object {
        val displayPresets: List<CinematicLut> = listOf(
            REC_709,
            KODAK_2383,
            FUJI_ETERNA,
            TEAL_ORANGE,
            BLEACH_BYPASS,
            WARM_SUNSET,
            COOL_THRILLER,
            MUTED_FILM,
            NONE,
            CUSTOM
        )
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
