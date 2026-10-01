package com.example.camera.videopipeline

import com.example.camera.model.WhiteBalanceMode

/**
 * Complete processing-level parameter model for the unified "Custom Pipeline".
 *
 * Base: Exact Cinema Mode Natural Profile (Rec.2020 Log baseline, neutral clean rendering,
 * no artificial LUT or overlay filter look).
 *
 * Every control operates at the genuine image processing level (hardware ISP capture request
 * and real-time GPU compute shader).
 */
data class CustomVideoPipelineConfig(
    // 1. Exposure / overall brightness (-2.0 EV to +2.0 EV physical radiance scaling)
    val exposure: Float = 0.0f,

    // 2. Highlight recovery (0.0 to 1.0: soft-knee highlight reconstruction)
    val highlightRecovery: Float = 0.40f,

    // 3. Shadow recovery (0.0 to 1.0: intelligent shadow toe expansion)
    val shadowRecovery: Float = 0.35f,

    // 4. Black level (-0.05 to +0.05: true-black pedestal anchoring)
    val blackLevel: Float = 0.0f,

    // 5. Midtone control (-1.0 to +1.0: 18% middle gray tonal pivot)
    val midtoneControl: Float = 0.0f,

    // 6. Contrast (-1.0 to +1.0: filmic S-curve slope)
    val contrast: Float = 0.0f,

    // 7. Local contrast (0.0 to 1.0: spatial neighborhood micro-dynamic ratio)
    val localContrast: Float = 0.15f,

    // 8. Dynamic range / tone mapping (0.0 to 1.0: global dynamic range expansion)
    val dynamicRangeToneMapping: Float = 0.35f,

    // 9. Highlight roll-off (0.0 to 1.0: smooth logarithmic highlight shoulder)
    val highlightRollOff: Float = 0.45f,

    // 10. Shadow roll-off (0.0 to 1.0: gradual deep shadow toe roll-off)
    val shadowRollOff: Float = 0.30f,

    // 11. White balance mode
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,

    // 12. Temperature (-1.0 to +1.0: cool blue to warm amber)
    val temperature: Float = 0.0f,

    // 13. Tint (-1.0 to +1.0: green to magenta)
    val tint: Float = 0.0f,

    // 14. Saturation (0.0 to 2.0: neutral 1.0)
    val saturation: Float = 1.0f,

    // 15. Vibrance (-1.0 to +1.0: smart skin-protective chrominance boosting)
    val vibrance: Float = 0.0f,

    // 16. Color matrix / color transform preset (0: Rec.2020 Neutral, 1: Natural Cinema, 2: Film DCI-P3, 3: Pure Sensor)
    val colorMatrixPreset: Int = 0,

    // 17. RGB channel gain: Red (0.5 to 1.5)
    val redGain: Float = 1.0f,

    // 18. RGB channel gain: Green (0.5 to 1.5)
    val greenGain: Float = 1.0f,

    // 19. RGB channel gain: Blue (0.5 to 1.5)
    val blueGain: Float = 1.0f,

    // 20. Luma curve preset (0: Rec.2020 Natural Log, 1: Gentle Filmic S, 2: Extended Dynamic Range, 3: Lifted Shadows)
    val lumaCurvePreset: Int = 0,

    // 21. RGB curves: Red channel curve deviation (-1.0 to +1.0)
    val redCurveStrength: Float = 0.0f,

    // 22. RGB curves: Green channel curve deviation (-1.0 to +1.0)
    val greenCurveStrength: Float = 0.0f,

    // 23. RGB curves: Blue channel curve deviation (-1.0 to +1.0)
    val blueCurveStrength: Float = 0.0f,

    // 24. Chroma strength (0.0 to 2.0: Rec.2020 chroma vector scale)
    val chromaStrength: Float = 1.0f,

    // 25. Chroma noise reduction (0.0 to 1.0: color blotch & chroma variance filtering)
    val chromaNoiseReduction: Float = 0.30f,

    // 26. Luma noise reduction (0.0 to 1.0: spatial high-frequency noise smoothing)
    val lumaNoiseReduction: Float = 0.25f,

    // 27. Temporal noise reduction (0.0 to 1.0: hardware frame-to-frame denoise)
    val temporalNoiseReduction: Float = 0.50f,

    // 28. Spatial noise reduction (0.0 to 1.0: edge-preserving bilateral denoise)
    val spatialNoiseReduction: Float = 0.30f,

    // 29. Sharpening (0.0 to 1.0: 5-tap high-pass spatial convolution)
    val sharpening: Float = 0.20f,

    // 30. Micro-contrast (0.0 to 1.0: localized texture & surface structure enhancement)
    val microContrast: Float = 0.12f,

    // 31. Texture / detail (0.0 to 1.0: sub-pixel micro-texture preservation)
    val textureDetail: Float = 0.15f,

    // 32. Debanding (0.0 to 1.0: gradient banding suppression & high-bit dithering)
    val debanding: Float = 0.20f,

    // 33. Demosaic / detail processing (0.0 to 1.0: Bayer artifact suppression)
    val demosaicDetailProcessing: Float = 0.25f,

    // 34. Lens shading correction (0.0 to 1.0: optical corner fall-off compensation)
    val lensShadingCorrection: Float = 0.35f,

    // 35. Distortion correction (0.0 to 1.0: barrel/pincushion geometric correction)
    val distortionCorrection: Float = 0.25f,

    // 36. Black-clipping control (0.0 to 1.0: protects deep blacks from crushed clipping)
    val blackClippingControl: Float = 0.10f,

    // 37. Highlight-clipping protection (0.0 to 1.0: prevents harsh white specular clipping)
    val highlightClippingProtection: Float = 0.40f,

    // 38. HDR / tone-mapping strength (0.0 to 1.0: dynamic range compression ratio)
    val hdrToneMappingStrength: Float = 0.35f,

    // 39. Local tone mapping strength (0.0 to 1.0: adaptive spatial tone mapping)
    val localToneMapping: Float = 0.20f,

    // 40. Color highlight / shadow separation (0.0 to 1.0: protects color purity in deep shadows and bright highlights)
    val colorHighlightShadowSeparation: Float = 0.15f,

    // 41. Output gamma (1.8 to 2.6: standard 2.2 display encoding)
    val outputGamma: Float = 2.2f,

    // 42. Log-to-display transform strength (0.0 to 1.0: blend between raw Log and standard display view)
    val logToDisplayTransformStrength: Float = 1.0f
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
                dynamicRangeToneMapping == 0.35f &&
                highlightRollOff == 0.45f &&
                shadowRollOff == 0.30f &&
                whiteBalance == WhiteBalanceMode.AUTO &&
                temperature == 0.0f &&
                tint == 0.0f &&
                saturation == 1.0f &&
                vibrance == 0.0f &&
                colorMatrixPreset == 0 &&
                redGain == 1.0f &&
                greenGain == 1.0f &&
                blueGain == 1.0f &&
                lumaCurvePreset == 0 &&
                redCurveStrength == 0.0f &&
                greenCurveStrength == 0.0f &&
                blueCurveStrength == 0.0f &&
                chromaStrength == 1.0f &&
                chromaNoiseReduction == 0.30f &&
                lumaNoiseReduction == 0.25f &&
                temporalNoiseReduction == 0.50f &&
                spatialNoiseReduction == 0.30f &&
                sharpening == 0.20f &&
                microContrast == 0.12f &&
                textureDetail == 0.15f &&
                debanding == 0.20f &&
                demosaicDetailProcessing == 0.25f &&
                lensShadingCorrection == 0.35f &&
                distortionCorrection == 0.25f &&
                blackClippingControl == 0.10f &&
                highlightClippingProtection == 0.40f &&
                hdrToneMappingStrength == 0.35f &&
                localToneMapping == 0.20f &&
                colorHighlightShadowSeparation == 0.15f &&
                outputGamma == 2.2f &&
                logToDisplayTransformStrength == 1.0f

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
