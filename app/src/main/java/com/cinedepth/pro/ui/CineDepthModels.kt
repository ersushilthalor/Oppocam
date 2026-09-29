package com.cinedepth.pro.ui

data class BlurPreviewParams(
    val blurStrength: Float = 0.20f,
    val focusDepth: Float = 128f,
    val lensEffect: LensEffect = LensEffect.Classic,
    val edgeSoftness: Float = 0.35f,
    val edgeExpand: Float = 0.22f,
    val edgeRefine: Float = 0.52f,
    val backgroundLight: Float = 0.12f,
    val highlightBoost: Float = 0.20f,
    val blurFalloff: Float = 0.75f,
    val vignetteStrength: Float = 0.0f,
    val flareStrength: Float = 0.05f
)

enum class LensEffect {
    Classic,
    Creamy,
    Bubble,
    Bloom,
    Star,
    Hexagon,
    Anamorphic
}

enum class BokehPreset(
    val label: String,
    val effect: LensEffect
) {
    Classic("Classic", LensEffect.Classic),
    Creamy("Creamy", LensEffect.Creamy),
    Bubble("Bubble", LensEffect.Bubble),
    Bloom("Bloom", LensEffect.Bloom),
    Star("Star", LensEffect.Star),
    Circular("Circular", LensEffect.Hexagon),
    Anamorphic("Anamorphic", LensEffect.Anamorphic)
}

enum class LensProfile(
    val label: String,
    val params: BlurPreviewParams
) {
    Noctilux(
        label = "Noctilux",
        params = BlurPreviewParams(
            blurStrength = 0.42f,
            lensEffect = LensEffect.Creamy,
            edgeSoftness = 0.28f,
            edgeExpand = 0.18f,
            edgeRefine = 0.60f,
            backgroundLight = 0.18f,
            highlightBoost = 0.32f,
            blurFalloff = 0.55f,
            vignetteStrength = 0.20f,
            flareStrength = 0.08f
        )
    ),
    GMaster(
        label = "G-Master",
        params = BlurPreviewParams(
            blurStrength = 0.34f,
            lensEffect = LensEffect.Classic,
            edgeSoftness = 0.40f,
            edgeExpand = 0.24f,
            edgeRefine = 0.65f,
            backgroundLight = 0.10f,
            highlightBoost = 0.15f,
            blurFalloff = 0.68f,
            vignetteStrength = 0.06f,
            flareStrength = 0.03f
        )
    ),
    Helios(
        label = "Helios 44-2",
        params = BlurPreviewParams(
            blurStrength = 0.38f,
            lensEffect = LensEffect.Bubble,
            edgeSoftness = 0.22f,
            edgeExpand = 0.14f,
            edgeRefine = 0.44f,
            backgroundLight = 0.22f,
            highlightBoost = 0.28f,
            blurFalloff = 0.48f,
            vignetteStrength = 0.26f,
            flareStrength = 0.12f
        )
    ),
    CinemaScope(
        label = "CinemaScope",
        params = BlurPreviewParams(
            blurStrength = 0.36f,
            lensEffect = LensEffect.Anamorphic,
            edgeSoftness = 0.32f,
            edgeExpand = 0.20f,
            edgeRefine = 0.55f,
            backgroundLight = 0.14f,
            highlightBoost = 0.30f,
            blurFalloff = 0.62f,
            vignetteStrength = 0.15f,
            flareStrength = 0.18f
        )
    )
}
