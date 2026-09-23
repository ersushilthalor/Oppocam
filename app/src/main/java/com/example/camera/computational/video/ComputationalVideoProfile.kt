package com.example.camera.computational.video

/**
 * Computational video processing parameters covering all 15 stages of the pipeline:
 * 1. Frame Buffer & History
 * 2. Temporal Multi-Frame Processing
 * 3. Motion Detection & Frame Alignment
 * 4. Temporal Noise Reduction
 * 5. HDR / Dynamic Range Processing
 * 6. Highlight Recovery Knee
 * 7. Shadow Recovery Boost
 * 8. Color Processing (3x3 matrix)
 * 9. Tone Mapping (S-curve finish)
 * 10. Local Contrast
 * 11. Fine Detail Recovery
 * 12. Edge-Aware Adaptive Sharpening
 * 13. Chroma Denoising
 * 14. Skin-Tone Protection
 * 15. Temporal Consistency & Anti-Flicker
 */
data class ComputationalVideoProfile(
    val pipeline: ComputationalVideoPipeline,
    val temporalDenoise: Float,          // 0.0 to 1.0 (temporal blend factor in static areas)
    val motionThreshold: Float,         // Motion sensitivity (0.01 to 0.20)
    val temporalFlickerDamping: Float,  // Temporal exposure / anti-flicker smoothing
    val hdrToneMap: Float,              // S-curve tone mapping intensity for finished SDR
    val highlightRecovery: Float,       // Knee compression for highlight roll-off without harsh clipping
    val shadowRecovery: Float,          // Shadow detail boost without raising black levels
    val localContrast: Float,           // Unsharp micro-contrast factor
    val edgeSharpening: Float,          // Edge-aware adaptive sharpening factor
    val fineDetail: Float,              // High-frequency texture retention factor
    val chromaDenoise: Float,           // Bilateral chroma noise reduction factor
    val saturation: Float,              // Global saturation adjustment
    val vibrance: Float,                // Smart vibrance for muted tones
    val warmth: Float,                  // Warm/cool chromatic balance
    val skinToneProtection: Float,      // Melanin protection & skin softness factor
    val colorMatrix: FloatArray,        // 3x3 color transform matrix (9 elements)
    val performanceTier: Int = 0        // 0 = full quality, 1 = balanced, 2 = low-power fallback
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ComputationalVideoProfile
        return pipeline == other.pipeline &&
                temporalDenoise == other.temporalDenoise &&
                motionThreshold == other.motionThreshold &&
                temporalFlickerDamping == other.temporalFlickerDamping &&
                hdrToneMap == other.hdrToneMap &&
                highlightRecovery == other.highlightRecovery &&
                shadowRecovery == other.shadowRecovery &&
                localContrast == other.localContrast &&
                edgeSharpening == other.edgeSharpening &&
                fineDetail == other.fineDetail &&
                chromaDenoise == other.chromaDenoise &&
                saturation == other.saturation &&
                vibrance == other.vibrance &&
                warmth == other.warmth &&
                skinToneProtection == other.skinToneProtection &&
                colorMatrix.contentEquals(other.colorMatrix) &&
                performanceTier == other.performanceTier
    }

    override fun hashCode(): Int {
        var result = pipeline.hashCode()
        result = 31 * result + temporalDenoise.hashCode()
        result = 31 * result + motionThreshold.hashCode()
        result = 31 * result + temporalFlickerDamping.hashCode()
        result = 31 * result + hdrToneMap.hashCode()
        result = 31 * result + highlightRecovery.hashCode()
        result = 31 * result + shadowRecovery.hashCode()
        result = 31 * result + localContrast.hashCode()
        result = 31 * result + edgeSharpening.hashCode()
        result = 31 * result + fineDetail.hashCode()
        result = 31 * result + chromaDenoise.hashCode()
        result = 31 * result + saturation.hashCode()
        result = 31 * result + vibrance.hashCode()
        result = 31 * result + warmth.hashCode()
        result = 31 * result + skinToneProtection.hashCode()
        result = 31 * result + colorMatrix.contentHashCode()
        result = 31 * result + performanceTier
        return result
    }

    companion object {
        val IDENTITY_MATRIX = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )

        // Pixel: Melanin-accurate Real Tone, neutral-cool punchy contrast, rich foliage
        val PIXEL_COLOR_MATRIX = floatArrayOf(
            1.04f, -0.02f, -0.02f,
            -0.01f, 1.05f, -0.04f,
            -0.02f, -0.02f, 1.04f
        )

        // Samsung: Vibrant rich blues, emerald greens, cheerful punch
        val SAMSUNG_COLOR_MATRIX = floatArrayOf(
            1.08f, -0.04f, -0.04f,
            -0.02f, 1.10f, -0.08f,
            -0.04f, -0.02f, 1.12f
        )

        // iPhone: Golden warmth, smooth tonal gradations, organic skin glow
        val IPHONE_COLOR_MATRIX = floatArrayOf(
            1.06f,  0.01f, -0.05f,
            0.00f,  1.02f, -0.02f,
           -0.03f, -0.01f,  0.98f
        )

        // Vivo: Pure Zeiss chroma, high chromatic saturation, neutral clarity
        val VIVO_COLOR_MATRIX = floatArrayOf(
            1.05f, -0.03f, -0.02f,
            -0.02f, 1.08f, -0.06f,
            -0.03f, -0.03f, 1.08f
        )

        fun forPipeline(pipeline: ComputationalVideoPipeline, tier: Int = 0): ComputationalVideoProfile {
            return when (pipeline) {
                ComputationalVideoPipeline.DEFAULT -> ComputationalVideoProfile(
                    pipeline = ComputationalVideoPipeline.DEFAULT,
                    temporalDenoise = 0.0f,
                    motionThreshold = 0.12f,
                    temporalFlickerDamping = 0.0f,
                    hdrToneMap = 0.0f,
                    highlightRecovery = 0.0f,
                    shadowRecovery = 0.0f,
                    localContrast = 0.0f,
                    edgeSharpening = 0.0f,
                    fineDetail = 0.0f,
                    chromaDenoise = 0.0f,
                    saturation = 0.0f,
                    vibrance = 0.0f,
                    warmth = 0.0f,
                    skinToneProtection = 0.0f,
                    colorMatrix = IDENTITY_MATRIX,
                    performanceTier = tier
                )
                ComputationalVideoPipeline.PIXEL -> ComputationalVideoProfile(
                    pipeline = ComputationalVideoPipeline.PIXEL,
                    temporalDenoise = if (tier == 2) 0.35f else 0.65f,
                    motionThreshold = 0.08f,
                    temporalFlickerDamping = 0.45f,
                    hdrToneMap = 0.48f,
                    highlightRecovery = 0.62f,
                    shadowRecovery = 0.36f,
                    localContrast = if (tier == 2) 0.20f else 0.42f,
                    edgeSharpening = if (tier == 2) 0.25f else 0.46f,
                    fineDetail = 0.50f,
                    chromaDenoise = 0.60f,
                    saturation = 0.08f,
                    vibrance = 0.16f,
                    warmth = -0.02f,
                    skinToneProtection = 0.88f,
                    colorMatrix = PIXEL_COLOR_MATRIX,
                    performanceTier = tier
                )
                ComputationalVideoPipeline.SAMSUNG -> ComputationalVideoProfile(
                    pipeline = ComputationalVideoPipeline.SAMSUNG,
                    temporalDenoise = if (tier == 2) 0.40f else 0.72f,
                    motionThreshold = 0.09f,
                    temporalFlickerDamping = 0.50f,
                    hdrToneMap = 0.62f,
                    highlightRecovery = 0.70f,
                    shadowRecovery = 0.66f,
                    localContrast = if (tier == 2) 0.25f else 0.54f,
                    edgeSharpening = if (tier == 2) 0.30f else 0.58f,
                    fineDetail = 0.45f,
                    chromaDenoise = 0.75f,
                    saturation = 0.22f,
                    vibrance = 0.30f,
                    warmth = 0.04f,
                    skinToneProtection = 0.62f,
                    colorMatrix = SAMSUNG_COLOR_MATRIX,
                    performanceTier = tier
                )
                ComputationalVideoPipeline.IPHONE -> ComputationalVideoProfile(
                    pipeline = ComputationalVideoPipeline.IPHONE,
                    temporalDenoise = if (tier == 2) 0.30f else 0.55f,
                    motionThreshold = 0.07f,
                    temporalFlickerDamping = 0.75f,
                    hdrToneMap = 0.36f,
                    highlightRecovery = 0.86f,
                    shadowRecovery = 0.42f,
                    localContrast = if (tier == 2) 0.15f else 0.26f,
                    edgeSharpening = if (tier == 2) 0.18f else 0.28f,
                    fineDetail = 0.62f,
                    chromaDenoise = 0.52f,
                    saturation = 0.05f,
                    vibrance = 0.12f,
                    warmth = 0.12f,
                    skinToneProtection = 0.82f,
                    colorMatrix = IPHONE_COLOR_MATRIX,
                    performanceTier = tier
                )
                ComputationalVideoPipeline.VIVO -> ComputationalVideoProfile(
                    pipeline = ComputationalVideoPipeline.VIVO,
                    temporalDenoise = if (tier == 2) 0.45f else 0.80f,
                    motionThreshold = 0.08f,
                    temporalFlickerDamping = 0.60f,
                    hdrToneMap = 0.52f,
                    highlightRecovery = 0.68f,
                    shadowRecovery = 0.56f,
                    localContrast = if (tier == 2) 0.28f else 0.56f,
                    edgeSharpening = if (tier == 2) 0.24f else 0.44f,
                    fineDetail = 0.68f,
                    chromaDenoise = 0.86f,
                    saturation = 0.15f,
                    vibrance = 0.22f,
                    warmth = 0.02f,
                    skinToneProtection = 0.92f,
                    colorMatrix = VIVO_COLOR_MATRIX,
                    performanceTier = tier
                )
            }
        }
    }
}
