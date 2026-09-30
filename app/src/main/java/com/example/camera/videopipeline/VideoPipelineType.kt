package com.example.camera.videopipeline

/**
 * Supported dedicated video processing pipelines.
 * Each pipeline provides an independent, full-chain computational processing system
 * that operates on both the live viewfinder and final recorded video files.
 */
enum class VideoPipelineType(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String
) {
    /**
     * Standard default video pipeline without specialized computational re-grading.
     */
    NORMAL(
        id = "normal",
        title = "Normal",
        subtitle = "Sensor Standard",
        description = "Default standard video pipeline with unadulterated sensor output and manual adjustments."
    ),

    /**
     * Apple Smart HDR style computational video pipeline.
     * Natural colors, subject/foreground separation, specular highlight retention,
     * realistic skin tones, detailed shadows, and premium dynamic range.
     */
    IPHONE(
        id = "iphone",
        title = "iPhone",
        subtitle = "Natural HDR & Depth",
        description = "Apple computational video: organic warmth, realistic subject separation, specular highlight retention & deep rich shadows."
    ),

    /**
     * Samsung Super HDR / Vivid Pro video pipeline.
     * Punchy vibrant colors, lifted shadows, bright midtones, controlled color separation,
     * and strong local contrast.
     */
    SAMSUNG(
        id = "samsung",
        title = "Samsung",
        subtitle = "Vivid Super HDR",
        description = "Samsung flagship video: punchy vibrant colors, lifted shadows, bright midtones & controlled color separation."
    ),

    /**
     * Vivo Zeiss-inspired Ultra HDR video pipeline.
     * True-to-life rich colors, controlled highlights, clean deep shadows,
     * strong micro-contrast/fine texture, and flagship low-light rendering.
     */
    VIVO(
        id = "vivo",
        title = "Vivo",
        subtitle = "Ultra HDR & Micro-Contrast",
        description = "Vivo Zeiss flagship video: rich natural colors, strong micro-contrast, controlled highlights & deep clean shadows."
    );

    companion object {
        fun fromId(id: String?): VideoPipelineType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NORMAL
    }
}
