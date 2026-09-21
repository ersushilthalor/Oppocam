package com.example.camera.pipeline.model

import com.squareup.moshi.JsonClass

/**
 * Color Rendering matrix presets representing subtle sensor chromatic character.
 */
enum class ColorMatrixPreset(val displayName: String, val description: String) {
    NATURAL("Sensor Natural", "Accurate baseline chromatic response directly from sensor"),
    HASSELBLAD("Hasselblad HNCS", "Natural Color Solution: neutral gradations, true-to-life skin tones"),
    SAMSUNG("Samsung Vivid", "Controlled punchy blues, golden accents, crisp separation"),
    PIXEL("Pixel TrueTone", "Balanced computational color fidelity, neutral whites, protected skin tones"),
    IPHONE("iPhone Warm", "Signature gentle golden ambient warmth, rich smooth peach skin tones"),
    CUSTOM("Custom Calibration", "User-configured 3x3 color matrix")
}

/**
 * Parameters for the Custom Image Processing Pipeline.
 * All parameters operate on uncompressed RAW/YUV sensor data before JPEG compression.
 */
@JsonClass(generateAdapter = true)
data class CustomPipelineParams(
    // Exposure & Tone Controls
    val exposure: Float = 0.0f,               // -2.0f..+2.0f (EV)
    val contrast: Float = 1.0f,               // 0.6f..1.4f (1.0f is linear neutral)
    val highlights: Float = 0.0f,             // -100f..+100f (compresses or brightens highlights)
    val shadows: Float = 0.0f,                // -100f..+100f (lifts or deepens shadows)
    val whites: Float = 0.0f,                 // -100f..+100f (white point clipping threshold)
    val blacks: Float = 0.0f,                 // -100f..+100f (black point depth)
    val highlightRollOff: Float = 20.0f,      // 0f..100f (smooth shoulder curve preventing digital clipping)
    val shadowRecovery: Float = 10.0f,        // 0f..100f (gentle toe lift without lifting true black)
    val localToneMapping: Float = 0.0f,       // 0f..100f (HDR strength / dynamic range compression)

    // Color Controls
    val saturation: Float = 0.0f,             // -100f..+100f
    val vibrance: Float = 0.0f,               // -100f..+100f (smart saturation preserving skin tones & avoiding clipping)
    val temperature: Float = 0.0f,            // -100f..+100f (cool blue to warm amber)
    val tint: Float = 0.0f,                   // -100f..+100f (green to magenta)
    val colorMatrixPreset: ColorMatrixPreset = ColorMatrixPreset.NATURAL,
    val customColorMatrix: List<Float> = listOf(
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    ),

    // Detail & Texture Controls
    val sharpness: Float = 15.0f,             // 0f..100f (edge-steered unsharp mask)
    val microContrast: Float = 0.0f,          // -100f..+100f (Clarity / mid-frequency contrast)
    val texture: Float = 0.0f,                // -100f..+100f (fine micro-texture boost without edge halo)
    val noiseReduction: Float = 12.0f,        // 0f..100f (edge-preserving spatial & chroma noise reduction)
    val detailPreservation: Float = 85.0f     // 0f..100f (retains organic grain and fine micro-structures)
) {
    fun to3x3Matrix(): FloatArray {
        return when (colorMatrixPreset) {
            ColorMatrixPreset.NATURAL -> floatArrayOf(
                1.00f, 0.00f, 0.00f,
                0.00f, 1.00f, 0.00f,
                0.00f, 0.00f, 1.00f
            )
            ColorMatrixPreset.HASSELBLAD -> floatArrayOf(
                // HNCS subtle calibration: very neutral, realistic skin preservation, soft smooth transitions
                1.01f, -0.01f, 0.00f,
                -0.01f, 1.01f, 0.00f,
                0.00f, -0.01f, 1.01f
            )
            ColorMatrixPreset.SAMSUNG -> floatArrayOf(
                // Slightly punchy colors: vivid cyan-blue sky, deep foliage green, rich reds
                1.05f, -0.03f, -0.02f,
                -0.02f, 1.04f, -0.02f,
                -0.01f, -0.02f, 1.06f
            )
            ColorMatrixPreset.PIXEL -> floatArrayOf(
                // Lightly punchy colors, neutral daylight balance, natural highlight rendition
                1.02f, -0.01f, -0.01f,
                -0.01f, 1.02f, -0.01f,
                -0.01f, -0.01f, 1.03f
            )
            ColorMatrixPreset.IPHONE -> floatArrayOf(
                // Subtle warm tone, natural organic colors, smooth highlights, realistic skin undertones
                1.04f, 0.00f, -0.03f,
                -0.01f, 1.02f, -0.01f,
                -0.03f, -0.01f, 0.99f
            )
            ColorMatrixPreset.CUSTOM -> {
                if (customColorMatrix.size == 9) customColorMatrix.toFloatArray()
                else floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
        }
    }
}

/**
 * High-level pipeline preset.
 */
@JsonClass(generateAdapter = true)
data class PipelinePreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val description: String,
    val isBuiltIn: Boolean,
    val params: CustomPipelineParams
) {
    val displayName: String get() = name

    companion object {
        val NATURAL = PipelinePreset(
            id = "preset_natural",
            name = "Sensor Natural",
            subtitle = "Pristine Sensor",
            description = "Direct uncompressed RAW/YUV sensor baseline with neutral tonal distribution",
            isBuiltIn = true,
            params = CustomPipelineParams(
                exposure = 0.0f,
                contrast = 1.0f,
                highlights = 0.0f,
                shadows = 0.0f,
                whites = 0.0f,
                blacks = 0.0f,
                highlightRollOff = 15.0f,
                shadowRecovery = 5.0f,
                localToneMapping = 0.0f,
                saturation = 0.0f,
                vibrance = 0.0f,
                temperature = 0.0f,
                tint = 0.0f,
                colorMatrixPreset = ColorMatrixPreset.NATURAL,
                sharpness = 12.0f,
                microContrast = 0.0f,
                texture = 0.0f,
                noiseReduction = 10.0f,
                detailPreservation = 90.0f
            )
        )

        val HASSELBLAD = PipelinePreset(
            id = "preset_hasselblad",
            name = "Hasselblad",
            subtitle = "Natural & Soft Roll-off",
            description = "Natural, neutral, soft highlight roll-off, realistic skin tones with medium organic detail",
            isBuiltIn = true,
            params = CustomPipelineParams(
                exposure = 0.0f,
                contrast = 1.02f,
                highlights = -12.0f,
                shadows = 6.0f,
                whites = -6.0f,
                blacks = 0.0f,
                highlightRollOff = 42.0f,
                shadowRecovery = 8.0f,
                localToneMapping = 12.0f,
                saturation = -1.0f,
                vibrance = 5.0f,
                temperature = 0.0f,
                tint = 0.0f,
                colorMatrixPreset = ColorMatrixPreset.HASSELBLAD,
                sharpness = 18.0f,
                microContrast = 8.0f,
                texture = 10.0f,
                noiseReduction = 14.0f,
                detailPreservation = 88.0f
            )
        )

        val SAMSUNG = PipelinePreset(
            id = "preset_samsung",
            name = "Samsung",
            subtitle = "Punchy & Crisp Details",
            description = "Slightly punchy color, controlled contrast, crisp micro-details and clean shadow rendering",
            isBuiltIn = true,
            params = CustomPipelineParams(
                exposure = 0.08f,
                contrast = 1.10f,
                highlights = -18.0f,
                shadows = 22.0f,
                whites = 8.0f,
                blacks = -5.0f,
                highlightRollOff = 22.0f,
                shadowRecovery = 22.0f,
                localToneMapping = 32.0f,
                saturation = 14.0f,
                vibrance = 18.0f,
                temperature = 2.0f,
                tint = -1.0f,
                colorMatrixPreset = ColorMatrixPreset.SAMSUNG,
                sharpness = 38.0f,
                microContrast = 20.0f,
                texture = 18.0f,
                noiseReduction = 26.0f,
                detailPreservation = 76.0f
            )
        )

        val PIXEL = PipelinePreset(
            id = "preset_pixel",
            name = "Pixel",
            subtitle = "Balanced HDR & Lifted Shadows",
            description = "Lightly punchy colors, balanced HDR, lifted shadows, natural highlights with smart tone mapping",
            isBuiltIn = true,
            params = CustomPipelineParams(
                exposure = -0.04f,
                contrast = 1.05f,
                highlights = -26.0f,
                shadows = 32.0f,
                whites = 0.0f,
                blacks = -2.0f,
                highlightRollOff = 30.0f,
                shadowRecovery = 34.0f,
                localToneMapping = 44.0f,
                saturation = 6.0f,
                vibrance = 15.0f,
                temperature = -1.0f,
                tint = 0.0f,
                colorMatrixPreset = ColorMatrixPreset.PIXEL,
                sharpness = 26.0f,
                microContrast = 15.0f,
                texture = 14.0f,
                noiseReduction = 20.0f,
                detailPreservation = 86.0f
            )
        )

        val IPHONE = PipelinePreset(
            id = "preset_iphone",
            name = "iPhone",
            subtitle = "Warm Tone & Smooth Highlights",
            description = "Slightly warm tone, natural colors, smooth highlights, realistic skin undertones",
            isBuiltIn = true,
            params = CustomPipelineParams(
                exposure = 0.05f,
                contrast = 1.04f,
                highlights = -15.0f,
                shadows = 16.0f,
                whites = -2.0f,
                blacks = 2.0f,
                highlightRollOff = 36.0f,
                shadowRecovery = 18.0f,
                localToneMapping = 24.0f,
                saturation = 4.0f,
                vibrance = 9.0f,
                temperature = 6.0f,
                tint = 2.0f,
                colorMatrixPreset = ColorMatrixPreset.IPHONE,
                sharpness = 22.0f,
                microContrast = 10.0f,
                texture = 12.0f,
                noiseReduction = 18.0f,
                detailPreservation = 84.0f
            )
        )

        val BUILT_IN_PRESETS = listOf(
            NATURAL,
            HASSELBLAD,
            SAMSUNG,
            PIXEL,
            IPHONE
        )

        fun findById(id: String): PipelinePreset {
            return BUILT_IN_PRESETS.firstOrNull { it.id == id } ?: HASSELBLAD
        }
    }
}
