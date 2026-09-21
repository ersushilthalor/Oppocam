package com.example.camera.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * High-definition Photo Filter presets for real-time live preview & non-destructive photo capture.
 * Separate from native Camera2 hardware pipeline to keep camera execution pristine.
 */
enum class PhotoFilter(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val category: String,
    val swatchColor: Color,
    private val matrixValues: FloatArray? = null
) {
    ORIGINAL(
        id = "original",
        displayName = "Original",
        subtitle = "Clean sensor image",
        category = "Natural",
        swatchColor = Color(0xFFFFD54F),
        matrixValues = null
    ),

    HDR_FILTER(
        id = "hdr_filter",
        displayName = "HDR Look",
        subtitle = "Foliage green pop, shadow lift & skin fidelity",
        category = "HDR",
        swatchColor = Color(0xFF2E7D32),
        matrixValues = floatArrayOf(
            1.08f, 0.02f, -0.02f, 0f, 14f,
            -0.02f, 1.22f, 0.02f, 0f, 12f,
            -0.04f, 0.02f, 1.06f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    CINEMATIC_TEAL(
        id = "cine_teal",
        displayName = "Teal & Gold",
        subtitle = "Modern Hollywood look",
        category = "Cinematic",
        swatchColor = Color(0xFF00E5FF),
        matrixValues = floatArrayOf(
            1.18f, -0.05f, -0.05f, 0f, 16f,
            -0.03f, 1.05f, 0.05f, 0f, 2f,
            -0.10f, 0.05f, 1.22f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    KODAK_PORTRA(
        id = "kodak_portra",
        displayName = "Portra 400",
        subtitle = "Warm pastel skin tones",
        category = "Film",
        swatchColor = Color(0xFFFFB74D),
        matrixValues = floatArrayOf(
            1.12f, 0.04f, -0.04f, 0f, 14f,
            0.02f, 1.06f, -0.02f, 0f, 6f,
            -0.05f, 0.00f, 0.90f, 0f, -6f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    FUJI_VELVIA(
        id = "fuji_velvia",
        displayName = "Velvia 50",
        subtitle = "Vivid landscape punch",
        category = "Film",
        swatchColor = Color(0xFFFF4081),
        matrixValues = floatArrayOf(
            1.22f, -0.04f, -0.02f, 0f, 8f,
            -0.03f, 1.20f, -0.03f, 0f, 6f,
            -0.04f, -0.02f, 1.25f, 0f, 14f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    VINTAGE_70S(
        id = "vintage_70s",
        displayName = "1970 Vintage",
        subtitle = "Warm faded nostalgia",
        category = "Vintage",
        swatchColor = Color(0xFFFF8A65),
        matrixValues = floatArrayOf(
            1.14f, 0.06f, -0.05f, 0f, 18f,
            0.04f, 1.04f, -0.03f, 0f, 8f,
            -0.08f, -0.02f, 0.80f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    POLAROID_WARM(
        id = "polaroid_warm",
        displayName = "Instant Warm",
        subtitle = "Classic instant film look",
        category = "Vintage",
        swatchColor = Color(0xFFFFCC80),
        matrixValues = floatArrayOf(
            1.10f, 0.05f, -0.02f, 0f, 15f,
            0.02f, 1.02f, 0.02f, 0f, 10f,
            -0.04f, 0.00f, 0.88f, 0f, 20f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    NATURAL_ENHANCE(
        id = "natural_enhance",
        displayName = "Natural Glow",
        subtitle = "Luminous delicate polish",
        category = "Natural",
        swatchColor = Color(0xFF81C784),
        matrixValues = floatArrayOf(
            1.05f, 0.02f, 0.00f, 0f, 6f,
            0.01f, 1.05f, 0.01f, 0f, 6f,
            0.00f, 0.01f, 1.04f, 0f, 6f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    GOLDEN_HOUR(
        id = "golden_hour",
        displayName = "Golden Hour",
        subtitle = "Sunset glow & amber warmth",
        category = "Warm",
        swatchColor = Color(0xFFFFB300),
        matrixValues = floatArrayOf(
            1.25f, 0.04f, -0.10f, 0f, 24f,
            0.02f, 1.10f, -0.06f, 0f, 12f,
            -0.12f, -0.04f, 0.74f, 0f, -14f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    COOL_NORDIC(
        id = "cool_nordic",
        displayName = "Cool Nordic",
        subtitle = "Subtle clean blue tones",
        category = "Cool",
        swatchColor = Color(0xFF64B5F6),
        matrixValues = floatArrayOf(
            0.88f, -0.02f, 0.02f, 0f, -8f,
            -0.02f, 0.98f, 0.05f, 0f, 0f,
            0.04f, 0.06f, 1.28f, 0f, 20f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    NOIR_MONOCHROME(
        id = "noir_mono",
        displayName = "Classic Noir",
        subtitle = "Silky contrast monochrome",
        category = "Monochrome",
        swatchColor = Color(0xFFECEFF1),
        matrixValues = floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    HIGH_CONTRAST_BW(
        id = "high_contrast_bw",
        displayName = "Silver B&W",
        subtitle = "Deep inky blacks & highlights",
        category = "Monochrome",
        swatchColor = Color(0xFF90A4AE),
        matrixValues = floatArrayOf(
            0.35f, 0.65f, 0.15f, 0f, -15f,
            0.35f, 0.65f, 0.15f, 0f, -15f,
            0.35f, 0.65f, 0.15f, 0f, -15f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    MUTED_EARTH(
        id = "muted_earth",
        displayName = "Muted Earth",
        subtitle = "Soft desaturated tones",
        category = "Cinematic",
        swatchColor = Color(0xFF8D6E63),
        matrixValues = floatArrayOf(
            0.94f, 0.04f, 0.02f, 0f, 10f,
            0.03f, 0.94f, 0.03f, 0f, 10f,
            0.03f, 0.03f, 0.92f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    PASTEL_DREAM(
        id = "pastel_dream",
        displayName = "Pastel Dream",
        subtitle = "Ethereal highlights & lilac tint",
        category = "Creative",
        swatchColor = Color(0xFFCE93D8),
        matrixValues = floatArrayOf(
            1.08f, 0.04f, 0.02f, 0f, 20f,
            0.02f, 1.05f, 0.04f, 0f, 18f,
            0.04f, 0.02f, 1.12f, 0f, 25f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    CYBER_EMERALD(
        id = "cyber_emerald",
        displayName = "Cyber Emerald",
        subtitle = "Stylized neon emerald greens",
        category = "Creative",
        swatchColor = Color(0xFF00E676),
        matrixValues = floatArrayOf(
            0.88f, 0.02f, -0.02f, 0f, -10f,
            -0.02f, 1.25f, 0.02f, 0f, 14f,
            0.02f, 0.04f, 1.15f, 0f, 16f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    BLEACH_BYPASS(
        id = "bleach_bypass",
        displayName = "Bleach Bypass",
        subtitle = "Harsh high contrast silver",
        category = "Cinematic",
        swatchColor = Color(0xFFB0BEC5),
        matrixValues = floatArrayOf(
            1.22f, 0.10f, 0.10f, 0f, -6f,
            0.10f, 1.22f, 0.10f, 0f, -6f,
            0.10f, 0.10f, 1.22f, 0f, -6f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    SEPIA_NOSTALGIA(
        id = "sepia_nostalgia",
        displayName = "Sepia Nostalgia",
        subtitle = "Warm antique bronze tone",
        category = "Vintage",
        swatchColor = Color(0xFFA1887F),
        matrixValues = floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 10f,
            0.349f, 0.686f, 0.168f, 0f, 5f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    );

    fun toColorFilter(): ColorFilter? {
        val vals = matrixValues ?: return null
        return ColorFilter.colorMatrix(ColorMatrix(vals))
    }

    fun toAndroidColorMatrix(): android.graphics.ColorMatrix? {
        val vals = matrixValues ?: return null
        return android.graphics.ColorMatrix(vals)
    }

    fun getMatrixValues(): FloatArray? = matrixValues
}
