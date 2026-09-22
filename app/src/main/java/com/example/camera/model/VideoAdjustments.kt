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
