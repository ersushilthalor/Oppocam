package com.example.camera.model

import java.util.Locale

/**
 * Real Video Adjustment Parameters for Normal Video Mode.
 * Applied across both live Viewfinder preview (GPU + hardware layer) and recorded video export.
 */
data class VideoAdjustments(
    // Basic Controls
    val tonality: Float = 0f,       // -100 to +100
    val saturation: Float = 0f,     // -100 to +100
    val contrast: Float = 0f,       // -100 to +100
    val exposure: Float = 0.0f,     // -3.0 to +3.0 EV
    val temperature: Float = 0f,    // -100 to +100 (Temp)
    val tint: Float = 0f,           // -100 to +100 (Tint)
    val highlights: Float = 0f,     // -100 to +100
    val shadows: Float = 0f,        // -100 to +100
    val vignette: Float = 0f,       // 0 to 100
    val grain: Float = 0f,          // 0 to 100
    val clarity: Float = 0f,        // -100 to +100
    val sharpness: Float = 0f,      // 0 to 100

    // Advanced - Curve
    val curveBlacks: Float = 0f,    // -100 to +100
    val curveShadows: Float = 0f,   // -100 to +100
    val curveMidtones: Float = 0f,  // -100 to +100
    val curveHighlights: Float = 0f,// -100 to +100
    val curveWhites: Float = 0f,    // -100 to +100

    // Advanced - Color
    val colorVibrance: Float = 0f,  // -100 to +100
    val colorBalanceR: Float = 0f,  // -100 to +100 (Red/Cyan)
    val colorBalanceG: Float = 0f,  // -100 to +100 (Green/Magenta)
    val colorBalanceB: Float = 0f,  // -100 to +100 (Blue/Yellow)

    // Advanced - Effects: Light FX
    val lightFxFlash: Float = 0f,     // 0 to 100
    val lightFxBloom: Float = 0f,     // 0 to 100
    val lightFxSoftLight: Float = 0f, // 0 to 100

    // Advanced - Effects: Texture
    val textureFilmGrain: Float = 0f,    // 0 to 100
    val textureHalation: Float = 0f,     // 0 to 100
    val textureMicroContrast: Float = 0f // 0 to 100
) {
    val isDefault: Boolean
        get() = tonality == 0f && saturation == 0f && contrast == 0f && exposure == 0.0f &&
                temperature == 0f && tint == 0f && highlights == 0f && shadows == 0f &&
                vignette == 0f && grain == 0f && clarity == 0f && sharpness == 0f &&
                curveBlacks == 0f && curveShadows == 0f && curveMidtones == 0f &&
                curveHighlights == 0f && curveWhites == 0f && colorVibrance == 0f &&
                colorBalanceR == 0f && colorBalanceG == 0f && colorBalanceB == 0f &&
                lightFxFlash == 0f && lightFxBloom == 0f && lightFxSoftLight == 0f &&
                textureFilmGrain == 0f && textureHalation == 0f && textureMicroContrast == 0f

    fun getValue(control: BasicAdjustmentControl): Float = when (control) {
        BasicAdjustmentControl.TONALITY -> tonality
        BasicAdjustmentControl.SATURATION -> saturation
        BasicAdjustmentControl.CONTRAST -> contrast
        BasicAdjustmentControl.EXPOSURE -> exposure
        BasicAdjustmentControl.TEMP -> temperature
        BasicAdjustmentControl.TINT -> tint
        BasicAdjustmentControl.HIGHLIGHTS -> highlights
        BasicAdjustmentControl.SHADOWS -> shadows
        BasicAdjustmentControl.VIGNETTE -> vignette
        BasicAdjustmentControl.GRAIN -> grain
        BasicAdjustmentControl.CLARITY -> clarity
        BasicAdjustmentControl.SHARPNESS -> sharpness
    }

    fun withValue(control: BasicAdjustmentControl, value: Float): VideoAdjustments {
        val clamped = value.coerceIn(control.min, control.max)
        return when (control) {
            BasicAdjustmentControl.TONALITY -> copy(tonality = clamped)
            BasicAdjustmentControl.SATURATION -> copy(saturation = clamped)
            BasicAdjustmentControl.CONTRAST -> copy(contrast = clamped)
            BasicAdjustmentControl.EXPOSURE -> copy(exposure = (Math.round(clamped * 10f) / 10f))
            BasicAdjustmentControl.TEMP -> copy(temperature = clamped)
            BasicAdjustmentControl.TINT -> copy(tint = clamped)
            BasicAdjustmentControl.HIGHLIGHTS -> copy(highlights = clamped)
            BasicAdjustmentControl.SHADOWS -> copy(shadows = clamped)
            BasicAdjustmentControl.VIGNETTE -> copy(vignette = clamped)
            BasicAdjustmentControl.GRAIN -> copy(grain = clamped)
            BasicAdjustmentControl.CLARITY -> copy(clarity = clamped)
            BasicAdjustmentControl.SHARPNESS -> copy(sharpness = clamped)
        }
    }
}

enum class VideoAdjustmentsTab {
    BASIC,
    ADVANCED
}

enum class AdvancedSubCategory(val label: String) {
    CURVE("Curve"),
    COLOR("Color"),
    EFFECTS("Effects")
}

enum class EffectsSubCategory(val label: String) {
    LIGHT_FX("Light FX"),
    TEXTURE("Texture")
}

enum class BasicAdjustmentControl(
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val isDecimal: Boolean = false
) {
    TONALITY("Tonality", -100f, 100f, 0f),
    SATURATION("Saturation", -100f, 100f, 0f),
    CONTRAST("Contrast", -100f, 100f, 0f),
    EXPOSURE("Exposure", -3.0f, 3.0f, 0.0f, isDecimal = true),
    TEMP("Temp", -100f, 100f, 0f),
    TINT("Tint", -100f, 100f, 0f),
    HIGHLIGHTS("Highlights", -100f, 100f, 0f),
    SHADOWS("Shadows", -100f, 100f, 0f),
    VIGNETTE("Vignette", 0f, 100f, 0f),
    GRAIN("Grain", 0f, 100f, 0f),
    CLARITY("Clarity", -100f, 100f, 0f),
    SHARPNESS("Sharpness", 0f, 100f, 0f);

    fun formatValue(value: Float): String {
        return if (isDecimal) {
            String.format(Locale.US, "%.1f", value)
        } else {
            val intVal = Math.round(value)
            if (intVal > 0 && this != VIGNETTE && this != GRAIN && this != SHARPNESS) {
                "+$intVal"
            } else {
                "$intVal"
            }
        }
    }
}

/**
 * Configuration for the Video HDR-style visual preset.
 * Reuses the existing Video Adjust controls (highlights compression, shadows lift,
 * contrast, clarity, vibrance, etc.) to simulate high dynamic range tone mapping on both
 * the live viewfinder and recorded video without altering 8-bit SDR video codec, bit depth, or metadata.
 */
data class VideoHdrPresetConfig(
    val isEnabled: Boolean = false,
    val enabledControls: Set<String> = defaultEnabledControls,
    val adjustments: VideoAdjustments = defaultPresetAdjustments
) {
    companion object {
        // High-dynamic-range simulated visual look:
        // Compressed highlights (-30), boosted shadows (+35), expanded micro-contrast/clarity (+20),
        // balanced mid-contrast (+12), rich color vibrance (+15), subtle saturation (+5), and texture micro-contrast (+15).
        val defaultPresetAdjustments = VideoAdjustments(
            highlights = -30f,
            shadows = 35f,
            contrast = 12f,
            clarity = 20f,
            colorVibrance = 15f,
            saturation = 5f,
            tonality = 0f,
            exposure = 0.0f,
            temperature = 0f,
            tint = 0f,
            sharpness = 0f,
            vignette = 0f,
            textureMicroContrast = 15f
        )

        val defaultEnabledControls: Set<String> = setOf(
            "HIGHLIGHTS",
            "SHADOWS",
            "CONTRAST",
            "CLARITY",
            "COLOR_VIBRANCE"
        )
    }

    /**
     * Applies the configured HDR preset values to only the selected controls,
     * preserving other adjustments from [base].
     */
    fun applyPreset(base: VideoAdjustments): VideoAdjustments {
        var res = base
        if (enabledControls.contains("HIGHLIGHTS")) res = res.copy(highlights = adjustments.highlights)
        if (enabledControls.contains("SHADOWS")) res = res.copy(shadows = adjustments.shadows)
        if (enabledControls.contains("CONTRAST")) res = res.copy(contrast = adjustments.contrast)
        if (enabledControls.contains("CLARITY")) res = res.copy(clarity = adjustments.clarity)
        if (enabledControls.contains("COLOR_VIBRANCE")) res = res.copy(colorVibrance = adjustments.colorVibrance)
        if (enabledControls.contains("SATURATION")) res = res.copy(saturation = adjustments.saturation)
        if (enabledControls.contains("TONALITY")) res = res.copy(tonality = adjustments.tonality)
        if (enabledControls.contains("EXPOSURE")) res = res.copy(exposure = adjustments.exposure)
        if (enabledControls.contains("TEMP")) res = res.copy(temperature = adjustments.temperature)
        if (enabledControls.contains("TINT")) res = res.copy(tint = adjustments.tint)
        if (enabledControls.contains("SHARPNESS")) res = res.copy(sharpness = adjustments.sharpness)
        if (enabledControls.contains("VIGNETTE")) res = res.copy(vignette = adjustments.vignette)
        if (enabledControls.contains("MICRO_CONTRAST")) res = res.copy(textureMicroContrast = adjustments.textureMicroContrast)
        return res
    }
}

/**
 * Controls available for inclusion in the Video HDR preset.
 * All controls map directly to existing VideoAdjustments fields.
 */
enum class HdrPresetTargetControl(
    val key: String,
    val label: String,
    val description: String,
    val min: Float,
    val max: Float,
    val isDecimal: Boolean = false
) {
    HIGHLIGHTS("HIGHLIGHTS", "Highlights Compression", "Compresses bright sky & highlight clipping", -100f, 100f),
    SHADOWS("SHADOWS", "Shadows Lift", "Recovers details in deep shadow zones", -100f, 100f),
    CONTRAST("CONTRAST", "Dynamic Contrast", "Local midtone tonal separation", -100f, 100f),
    CLARITY("CLARITY", "Clarity / Structure", "Edge micro-acutance enhancement", -100f, 100f),
    COLOR_VIBRANCE("COLOR_VIBRANCE", "Color Vibrance", "Expands color dynamics without clipping skin tones", -100f, 100f),
    SATURATION("SATURATION", "Saturation", "Overall color intensity", -100f, 100f),
    TONALITY("TONALITY", "Tonality", "Global tonal shift bias", -100f, 100f),
    EXPOSURE("EXPOSURE", "Exposure Bias", "Photometric exposure compensation", -3.0f, 3.0f, isDecimal = true),
    MICRO_CONTRAST("MICRO_CONTRAST", "Micro-Contrast", "Fine texture and detail contrast boost", 0f, 100f),
    TEMP("TEMP", "Color Temp", "Warmth / Coolness white balance bias", -100f, 100f),
    TINT("TINT", "Tint", "Magenta / Green tint balance", -100f, 100f),
    SHARPNESS("SHARPNESS", "Sharpness", "High-frequency edge sharpness", 0f, 100f);

    fun getValue(adjustments: VideoAdjustments): Float {
        return when (this) {
            HIGHLIGHTS -> adjustments.highlights
            SHADOWS -> adjustments.shadows
            CONTRAST -> adjustments.contrast
            CLARITY -> adjustments.clarity
            COLOR_VIBRANCE -> adjustments.colorVibrance
            SATURATION -> adjustments.saturation
            TONALITY -> adjustments.tonality
            EXPOSURE -> adjustments.exposure
            MICRO_CONTRAST -> adjustments.textureMicroContrast
            TEMP -> adjustments.temperature
            TINT -> adjustments.tint
            SHARPNESS -> adjustments.sharpness
        }
    }

    fun withValue(adjustments: VideoAdjustments, value: Float): VideoAdjustments {
        val clamped = value.coerceIn(min, max)
        return when (this) {
            HIGHLIGHTS -> adjustments.copy(highlights = clamped)
            SHADOWS -> adjustments.copy(shadows = clamped)
            CONTRAST -> adjustments.copy(contrast = clamped)
            CLARITY -> adjustments.copy(clarity = clamped)
            COLOR_VIBRANCE -> adjustments.copy(colorVibrance = clamped)
            SATURATION -> adjustments.copy(saturation = clamped)
            TONALITY -> adjustments.copy(tonality = clamped)
            EXPOSURE -> adjustments.copy(exposure = Math.round(clamped * 10f) / 10f)
            MICRO_CONTRAST -> adjustments.copy(textureMicroContrast = clamped)
            TEMP -> adjustments.copy(temperature = clamped)
            TINT -> adjustments.copy(tint = clamped)
            SHARPNESS -> adjustments.copy(sharpness = clamped)
        }
    }

    fun formatValue(value: Float): String {
        return if (isDecimal) {
            String.format(Locale.US, "%.1f", value)
        } else {
            val intVal = Math.round(value)
            if (intVal > 0 && this != MICRO_CONTRAST && this != SHARPNESS) {
                "+$intVal"
            } else {
                "$intVal"
            }
        }
    }
}

