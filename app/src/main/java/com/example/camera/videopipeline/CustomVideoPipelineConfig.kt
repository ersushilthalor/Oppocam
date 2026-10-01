package com.example.camera.videopipeline

import com.example.camera.model.WhiteBalanceMode

/**
 * Complete parameter model for the dedicated "Custom Pipeline" in Video Mode.
 *
 * Based exactly on Cinema Mode Natural Profile:
 * - Rec.2020 Log baseline
 * - Natural, neutral rendering without warm or saturated tint
 * - Clean highlight shoulder compression & shadow toe response
 * - No artificial LUT or filter-based overlay look
 *
 * All settings operate at the genuine sensor ISP acquisition and hardware shader processing level.
 */
data class CustomVideoPipelineConfig(
    // 1. Exposure / Brightness (-2.0 EV to +2.0 EV physical radiance scaling)
    val exposure: Float = 0.0f,

    // 2. Highlight Recovery (0.0 to 1.0: soft-knee highlight reconstruction)
    val highlightRecovery: Float = 0.40f,

    // 3. Shadow Recovery (0.0 to 1.0: intelligent shadow toe expansion)
    val shadowRecovery: Float = 0.35f,

    // 4. Black Level (-0.05 to +0.05: true-black pedestal anchoring)
    val blackLevel: Float = 0.0f,

    // 5. Midtone Control (-1.0 to +1.0: 18% middle gray tonal pivot)
    val midtoneControl: Float = 0.0f,

    // 6. Contrast (-1.0 to +1.0: filmic S-curve slope)
    val contrast: Float = 0.0f,

    // 7. Local Contrast (0.0 to 1.0: spatial neighborhood micro-dynamic ratio)
    val localContrast: Float = 0.15f,

    // 8. Highlight Roll-off (0.0 to 1.0: smooth logarithmic highlight shoulder)
    val highlightRollOff: Float = 0.45f,

    // 9. Shadow Roll-off (0.0 to 1.0: gradual deep shadow roll-off)
    val shadowRollOff: Float = 0.30f,

    // 10. White Balance mode
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,

    // 11. Temperature (-1.0 to +1.0: cool blue to warm amber)
    val temperature: Float = 0.0f,

    // 12. Tint (-1.0 to +1.0: green to magenta)
    val tint: Float = 0.0f,

    // 13. Saturation (0.0 to 2.0: neutral 1.0)
    val saturation: Float = 1.0f,

    // 14. Vibrance (-1.0 to +1.0: smart skin-protective chrominance boosting)
    val vibrance: Float = 0.0f,

    // 15. Color Matrix / Color Transform (0: Rec.2020 Neutral, 1: Natural Cinema, 2: Film DCI-P3, 3: Pure Sensor)
    val colorMatrixPreset: Int = 0,

    // 16. Luma Curve (0: Rec.2020 Natural Log, 1: Gentle Filmic S, 2: Extended Dynamic Range, 3: Lifted Shadows)
    val lumaCurvePreset: Int = 0,

    // 17. Chroma Strength (0.0 to 2.0: Rec.2020 chroma vector scale)
    val chromaStrength: Float = 1.0f,

    // 18. Luma Noise Reduction (0.0 to 1.0: spatial high-frequency noise smoothing)
    val lumaNoiseReduction: Float = 0.25f,

    // 19. Chroma Noise Reduction (0.0 to 1.0: color blotch & chroma variance filtering)
    val chromaNoiseReduction: Float = 0.30f,

    // 20. Temporal Noise Reduction (0.0 to 1.0: Camera2 hardware frame-to-frame denoise)
    val temporalNoiseReduction: Float = 0.50f,

    // 21. Sharpening (0.0 to 1.0: 5-tap high-pass spatial convolution)
    val sharpening: Float = 0.20f,

    // 22. Micro-Contrast / Detail (0.0 to 1.0: localized texture & surface structure enhancement)
    val microContrast: Float = 0.12f,

    // 23. HDR / Tone Mapping Strength (0.0 to 1.0: dynamic range compression ratio)
    val hdrToneMappingStrength: Float = 0.35f,

    // 24. Local Tone Mapping (0.0 to 1.0: adaptive spatial tone mapping vs global response)
    val localToneMapping: Float = 0.20f,

    // 25. Output Gamma (1.8 to 2.6: standard 2.2 display encoding)
    val outputGamma: Float = 2.2f
) {
    /**
     * Returns true if all settings match the pristine default Cinema Mode Natural Profile baseline.
     */
    val isDefault: Boolean
        get() = exposure == 0.0f &&
                highlightRecovery == 0.40f &&
                shadowRecovery == 0.35f &&
                blackLevel == 0.0f &&
                midtoneControl == 0.0f &&
                contrast == 0.0f &&
                localContrast == 0.15f &&
                highlightRollOff == 0.45f &&
                shadowRollOff == 0.30f &&
                whiteBalance == WhiteBalanceMode.AUTO &&
                temperature == 0.0f &&
                tint == 0.0f &&
                saturation == 1.0f &&
                vibrance == 0.0f &&
                colorMatrixPreset == 0 &&
                lumaCurvePreset == 0 &&
                chromaStrength == 1.0f &&
                lumaNoiseReduction == 0.25f &&
                chromaNoiseReduction == 0.30f &&
                temporalNoiseReduction == 0.50f &&
                sharpening == 0.20f &&
                microContrast == 0.12f &&
                hdrToneMappingStrength == 0.35f &&
                localToneMapping == 0.20f &&
                outputGamma == 2.2f

    /**
     * Resolves the 3x3 color transform matrix for the active [colorMatrixPreset].
     */
    fun resolve3x3Matrix(): FloatArray {
        return when (colorMatrixPreset) {
            1 -> floatArrayOf( // Natural Cinema
                1.015f, -0.010f, -0.005f,
                -0.005f, 1.012f, -0.007f,
                -0.008f, -0.006f, 1.014f
            )
            2 -> floatArrayOf( // Film DCI-P3
                1.032f, -0.018f, -0.014f,
                -0.008f, 1.025f, -0.017f,
                -0.015f, -0.012f, 1.027f
            )
            3 -> floatArrayOf( // Pure Sensor
                1.000f, 0.000f, 0.000f,
                0.000f, 1.000f, 0.000f,
                0.000f, 0.000f, 1.000f
            )
            else -> floatArrayOf( // Rec.2020 Neutral Standard
                1.000f, 0.000f, 0.000f,
                0.000f, 1.000f, 0.000f,
                0.000f, 0.000f, 1.000f
            )
        }
    }
}
