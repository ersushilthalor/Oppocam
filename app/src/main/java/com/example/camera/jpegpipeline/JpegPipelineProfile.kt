package com.example.camera.jpegpipeline

/**
 * Rendering profiles for JPEG Pipeline Video.
 *
 * NOTE: These are Camera2/ISP single-frame processing profiles, NOT LUTs or post-filters.
 * Each profile tunes native Camera2 ISP parameters (tonemapping, exposure compensation,
 * edge sharpening, noise reduction, color matrix and white balance gains) so that every
 * frame is rendered with a finished phone-camera photo look directly in the YUV pipeline
 * before entering the video encoder.
 */
enum class JpegPipelineProfile(
    val id: String,
    val title: String,
    val shortLabel: String,
    val subtitle: String,
    val description: String,
    val targetExposureCompensationEv: Float,
    val edgeSharpeningModeDescription: String,
    val tonemapCurveStyle: String,
    val colorCharacterDescription: String
) {
    STANDARD(
        id = "standard_jpeg",
        title = "JPEG Pipeline Style",
        shortLabel = "Standard",
        subtitle = "Normal photo-style ISP rendering",
        description = "Balanced photographic rendering with natural contrast, standard sRGB color matrix, optical lens shading correction, and balanced ISP noise reduction.",
        targetExposureCompensationEv = 0.0f,
        edgeSharpeningModeDescription = "Standard ISP high-quality edge sharpening",
        tonemapCurveStyle = "Standard sRGB 2.2 photo gamma with natural roll-off",
        colorCharacterDescription = "True-to-life sRGB camera matrix & neutral color balance"
    ),

    IPHONE(
        id = "iphone_style",
        title = "iPhone Style",
        shortLabel = "iPhone",
        subtitle = "Natural color & controlled highlights",
        description = "Natural color, controlled highlights, realistic contrast, natural sharpening and skin tones with gradual highlight knee compression.",
        targetExposureCompensationEv = -0.15f,
        edgeSharpeningModeDescription = "Natural edge definition without halo artifacts",
        tonemapCurveStyle = "Gradual highlight knee compression & natural midtone gradation",
        colorCharacterDescription = "Natural skin-tone preservation with neutral, authentic hues"
    ),

    SAMSUNG(
        id = "samsung_style",
        title = "Samsung Style",
        shortLabel = "Samsung",
        subtitle = "Stronger detail & punchier contrast",
        description = "Stronger detail, slightly punchier color/contrast, controlled highlights and stronger sharpening with crisp micro-contrast.",
        targetExposureCompensationEv = 0.0f,
        edgeSharpeningModeDescription = "Stronger ISP sharpening with enhanced micro-detail",
        tonemapCurveStyle = "Punchier S-curve contrast with deep shadows & crisp dynamic range",
        colorCharacterDescription = "Vibrant primary colors, punchy skies and lush greens"
    ),

    OPPO(
        id = "oppo_style",
        title = "OPPO Style",
        shortLabel = "OPPO",
        subtitle = "Natural warm rendering & shadow detail",
        description = "Natural/warm rendering, smooth highlights, good shadow detail and moderate sharpening with flattering warm portrait tones.",
        targetExposureCompensationEv = 0.15f,
        edgeSharpeningModeDescription = "Moderate smooth sharpening tailored for flattering skin textures",
        tonemapCurveStyle = "Lifted shadow detail with smooth, creamy highlight falloff",
        colorCharacterDescription = "Flattering warm golden complexion with pleasing skin-tones"
    )
}
