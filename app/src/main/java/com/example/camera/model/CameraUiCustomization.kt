package com.example.camera.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONArray
import org.json.JSONObject

enum class UiTemplateType(
    val title: String,
    val subtitle: String,
    val accentHex: String
) {
    STOCK_PIXEL("Stock Pixel Camera", "Google Pixel layout with dual exposure slider, zoom pill & bottom switch dock", "#8AB4F8"),
    MINIMAL_PRO("Minimal Pro / Leica", "Monochrome precision layout with live EV telemetry, exposure dial & red dot trigger", "#E53935"),
    FUTURISTIC_GLASS("Cyber Glass HUD", "Frosted glowing glass pods, cyber brackets, digital gyro & holographic ring", "#00E5FF"),
    DSLR_PRO("DSLR Mirrorless", "Pro camera OLED info band, tactical ISO/WB micro-dials & knurled metal shutter", "#FFB300"),
    IMMERSIVE_EDGE("Immersive Edge-Control", "Ultra-clean full-screen preview with dual edge sliders & floating thumb shutter", "#69F0AE"),
    IPHONE("iPhone Style", "Clean, minimal Apple-inspired layout with yellow accents & top pill indicators", "#FFD54F"),
    SAMSUNG("Samsung Style", "One UI layout with bold toggles, capsule mode selector & solid shutter", "#FFFFFF"),
    VIVO("Vivo Style", "OriginOS/Funtouch camera layout with circular icons, gimbal cues & vivid shutter ring", "#FF7043"),
    CUSTOM("Custom UI", "Completely personalized layout, spacing, typography & controls", "#64B5F6")
}

enum class ShutterStyle(val label: String, val description: String) {
    CLASSIC_WHITE("Classic Ring", "Double ring with solid white center"),
    APPLE_DOT("Apple Minimal", "Thin silver ring with crisp inner circle"),
    SAMSUNG_CAPSULE("Samsung OneUI", "Thick outer border with solid round core"),
    VIVO_GIMBAL("Vivo Origin", "Accent colored outer ring with responsive gimbal core"),
    MINIMAL_ACCENT("Minimalist Accent", "Flat borderless tactile trigger"),
    PIXEL_SOLID("Pixel Shutter", "Google Pixel concentric ring with clean solid core"),
    LEICA_RED_DOT("Leica Red Dot", "Minimalist circular brushed aluminum with central red dot"),
    CYBER_HOLO("Cyber Hologram", "Futuristic neon glowing dual-pulse rings"),
    DSLR_KNURLED("DSLR Knurled Metal", "Tactile textured mechanical trigger")
}

enum class ModeSelectorPosition(val label: String) {
    ABOVE_SHUTTER("Above Shutter Button"),
    BELOW_SHUTTER("Below Shutter Button")
}

enum class ModeSelectorStyle(val label: String) {
    CLASSIC_DOT("Yellow Indicator Dot"),
    CAPSULE_PILL("OneUI Capsule Pill"),
    UNDERLINE("Vivo Accent Underline"),
    MINIMAL_TEXT("Minimal Typography"),
    PIXEL_PILL("Pixel Dark Pill"),
    MONO_TICKER("Pro Monospace Ticker"),
    CYBER_GLOW("Cyber Neon Glow"),
    DSLR_DIAL("Mechanical Dial Wheel")
}

enum class FontFamilyOption(val label: String) {
    DEFAULT("Default Sans"),
    MONOSPACE("Monospace / Pro"),
    SERIF("Editorial Serif"),
    ROUNDED("Modern Rounded"),
    CYBER("Cyber Geometric"),
    CONDENSED("Condensed Display");

    fun toComposeFontFamily(): FontFamily {
        return when (this) {
            DEFAULT -> FontFamily.SansSerif
            MONOSPACE -> FontFamily.Monospace
            SERIF -> FontFamily.Serif
            ROUNDED -> FontFamily.Default
            CYBER -> FontFamily.Monospace
            CONDENSED -> FontFamily.SansSerif
        }
    }
}

enum class FontWeightOption(val label: String, val weight: FontWeight) {
    LIGHT("Light", FontWeight.Light),
    NORMAL("Regular", FontWeight.Normal),
    MEDIUM("Medium", FontWeight.Medium),
    SEMI_BOLD("SemiBold", FontWeight.SemiBold),
    BOLD("Bold", FontWeight.Bold),
    EXTRA_BOLD("ExtraBold", FontWeight.ExtraBold)
}

enum class TextCaseOption(val label: String) {
    UPPERCASE("UPPERCASE"),
    TITLE_CASE("Title Case"),
    LOWERCASE("lowercase");

    fun format(text: String): String {
        return when (this) {
            UPPERCASE -> text.uppercase()
            TITLE_CASE -> text.lowercase().replaceFirstChar { it.uppercase() }
            LOWERCASE -> text.lowercase()
        }
    }
}

enum class TextShadowOption(val label: String) {
    NONE("None"),
    SUBTLE_SHADOW("Drop Shadow"),
    NEON_GLOW("Neon Glow"),
    TACTICAL_OUTLINE("Outline")
}

enum class IconStyleOption(val label: String, val description: String) {
    ROUNDED_MATERIAL("Modern Rounded", "Clean, rounded corners with smooth balance"),
    MINIMAL_OUTLINE("Minimal Hairline", "Crisp ultra-thin 1.2dp modern line art"),
    SHARP_GEOMETRIC("Sharp Geometric", "Tactical angular cut-outs and zero radii"),
    BOLD_SOLID("Bold Solid", "High-contrast filled tactile silhouettes"),
    CYBER_NEON("Cyber Neon", "Futuristic glowing holographic stroke with colored halo"),
    FROSTED_GLASS("Glassmorphism", "Soft blurred translucent pill with frosted highlight"),
    NEOMORPHIC("Neomorphic Soft", "Subtle embossed depth with dual soft shadows"),
    RETRO_BADGE("Retro Badge", "Vintage mechanical camera stamped insignia")
}

enum class IconShapeOption(val label: String) {
    TRANSPARENT_NONE("None / Floating"),
    CIRCLE_GLASS("Circle Pod"),
    ROUNDED_SQUARE("Squircle Pod"),
    HEXAGON("Tactical Hexagon"),
    PILL("Capsule Pill")
}

enum class TopBarAlignment(val label: String) {
    SPACE_BETWEEN("Space Between"),
    CENTER("Centered"),
    COMPACT_LEFT("Left Grouped"),
    COMPACT_RIGHT("Right Grouped")
}

enum class TopControlItem(val id: String, val label: String) {
    FLASH("flash", "Flash / Torch"),
    TIMER("timer", "Timer Countdown"),
    GRID("grid", "Grid & Horizon Level"),
    RESOLUTION("resolution", "Photo/Video Resolution"),
    RAW("raw", "RAW Sensor Capture"),
    PRO_EXP("pro_exp", "Pro Manual Mode"),
    SETTINGS("settings", "Settings Gear")
}

data class ModeLayoutConfig(
    val visibleModes: List<CameraMode> = listOf(
        CameraMode.PHOTO,
        CameraMode.PORTRAIT,
        CameraMode.VIDEO,
        CameraMode.MORE
    ),
    val modeSelectorPosition: ModeSelectorPosition = ModeSelectorPosition.BELOW_SHUTTER,
    val modeSelectorStyle: ModeSelectorStyle = ModeSelectorStyle.CLASSIC_DOT,
    val modeTextSizeSp: Float = 13.5f,
    val modeFontFamily: FontFamilyOption = FontFamilyOption.DEFAULT,
    val shutterStyle: ShutterStyle = ShutterStyle.CLASSIC_WHITE,
    val shutterSizeDp: Int = 80,
    val shutterHorizontalOffsetDp: Int = 0,
    val flipButtonSizeDp: Int = 54,
    val galleryThumbSizeDp: Int = 54,
    val showGalleryButton: Boolean = true,
    val showFlipButton: Boolean = true,
    val topControlsOrder: List<TopControlItem> = listOf(
        TopControlItem.FLASH,
        TopControlItem.TIMER,
        TopControlItem.RESOLUTION,
        TopControlItem.SETTINGS
    ),
    val hiddenTopControls: Set<TopControlItem> = emptySet(),
    val topControlsIconSizeDp: Int = 20,
    val topControlsSpacingDp: Int = 16,
    val topBarAlignment: TopBarAlignment = TopBarAlignment.SPACE_BETWEEN,
    val topPaddingDp: Int = 12,
    val bottomPaddingDp: Int = 14,
    val showZoomCapsule: Boolean = true,
    val zoomCapsuleScale: Float = 1.0f,
    val zoomCapsuleVerticalOffsetDp: Int = 0,
    val accentColorHex: String = "#FFD54F",
    // Extended text styling
    val textColorHex: String = "#FFFFFF",
    val fontWeightOption: FontWeightOption = FontWeightOption.SEMI_BOLD,
    val textCaseOption: TextCaseOption = TextCaseOption.UPPERCASE,
    val letterSpacingSp: Float = 0.5f,
    val textShadowOption: TextShadowOption = TextShadowOption.SUBTLE_SHADOW,
    // Extended icon styling
    val iconStyleOption: IconStyleOption = IconStyleOption.ROUNDED_MATERIAL,
    val iconColorHex: String = "#FFFFFF",
    val iconShapeOption: IconShapeOption = IconShapeOption.TRANSPARENT_NONE,
    val iconContainerOpacity: Float = 0.35f,
    val iconStrokeWidthDp: Float = 1.8f,
    // Uploaded photo UI matching
    val customUiPhotoUri: String? = null,
    val customUiPhotoOverlayOpacity: Float = 0f
) {
    fun getComposeAccentColor(): Color {
        return try {
            Color(android.graphics.Color.parseColor(accentColorHex))
        } catch (e: Exception) {
            Color(0xFFFFD54F)
        }
    }

    fun getComposeTextColor(): Color {
        return try {
            Color(android.graphics.Color.parseColor(textColorHex))
        } catch (e: Exception) {
            Color.White
        }
    }

    fun getComposeIconColor(): Color {
        return try {
            Color(android.graphics.Color.parseColor(iconColorHex))
        } catch (e: Exception) {
            Color.White
        }
    }

    fun formatModeText(label: String): String {
        return textCaseOption.format(label)
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        val modesArray = JSONArray()
        visibleModes.forEach { modesArray.put(it.name) }
        json.put("visibleModes", modesArray)
        json.put("modeSelectorPosition", modeSelectorPosition.name)
        json.put("modeSelectorStyle", modeSelectorStyle.name)
        json.put("modeTextSizeSp", modeTextSizeSp.toDouble())
        json.put("modeFontFamily", modeFontFamily.name)
        json.put("shutterStyle", shutterStyle.name)
        json.put("shutterSizeDp", shutterSizeDp)
        json.put("shutterHorizontalOffsetDp", shutterHorizontalOffsetDp)
        json.put("flipButtonSizeDp", flipButtonSizeDp)
        json.put("galleryThumbSizeDp", galleryThumbSizeDp)
        json.put("showGalleryButton", showGalleryButton)
        json.put("showFlipButton", showFlipButton)

        val topOrderArray = JSONArray()
        topControlsOrder.forEach { topOrderArray.put(it.name) }
        json.put("topControlsOrder", topOrderArray)

        val hiddenTopArray = JSONArray()
        hiddenTopControls.forEach { hiddenTopArray.put(it.name) }
        json.put("hiddenTopControls", hiddenTopArray)

        json.put("topControlsIconSizeDp", topControlsIconSizeDp)
        json.put("topControlsSpacingDp", topControlsSpacingDp)
        json.put("topBarAlignment", topBarAlignment.name)
        json.put("topPaddingDp", topPaddingDp)
        json.put("bottomPaddingDp", bottomPaddingDp)
        json.put("showZoomCapsule", showZoomCapsule)
        json.put("zoomCapsuleScale", zoomCapsuleScale.toDouble())
        json.put("zoomCapsuleVerticalOffsetDp", zoomCapsuleVerticalOffsetDp)
        json.put("accentColorHex", accentColorHex)

        json.put("textColorHex", textColorHex)
        json.put("fontWeightOption", fontWeightOption.name)
        json.put("textCaseOption", textCaseOption.name)
        json.put("letterSpacingSp", letterSpacingSp.toDouble())
        json.put("textShadowOption", textShadowOption.name)
        json.put("iconStyleOption", iconStyleOption.name)
        json.put("iconColorHex", iconColorHex)
        json.put("iconShapeOption", iconShapeOption.name)
        json.put("iconContainerOpacity", iconContainerOpacity.toDouble())
        json.put("iconStrokeWidthDp", iconStrokeWidthDp.toDouble())
        if (customUiPhotoUri != null) json.put("customUiPhotoUri", customUiPhotoUri)
        json.put("customUiPhotoOverlayOpacity", customUiPhotoOverlayOpacity.toDouble())
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): ModeLayoutConfig {
            val visibleModesList = mutableListOf<CameraMode>()
            val modesArray = json.optJSONArray("visibleModes")
            if (modesArray != null) {
                for (i in 0 until modesArray.length()) {
                    val mName = modesArray.optString(i)
                    try { visibleModesList.add(CameraMode.valueOf(mName)) } catch (ignored: Exception) {}
                }
            }
            if (visibleModesList.isEmpty()) {
                visibleModesList.addAll(listOf(
                    CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.VIDEO, CameraMode.MORE
                ))
            }

            val topOrderList = mutableListOf<TopControlItem>()
            val topOrderArray = json.optJSONArray("topControlsOrder")
            if (topOrderArray != null) {
                for (i in 0 until topOrderArray.length()) {
                    val item = topOrderArray.optString(i)
                    try { topOrderList.add(TopControlItem.valueOf(item)) } catch (ignored: Exception) {}
                }
            }
            if (topOrderList.isEmpty()) {
                topOrderList.addAll(listOf(
                    TopControlItem.FLASH, TopControlItem.TIMER,
                    TopControlItem.RESOLUTION, TopControlItem.SETTINGS
                ))
            }

            val hiddenTopSet = mutableSetOf<TopControlItem>()
            val hiddenTopArray = json.optJSONArray("hiddenTopControls")
            if (hiddenTopArray != null) {
                for (i in 0 until hiddenTopArray.length()) {
                    val item = hiddenTopArray.optString(i)
                    try { hiddenTopSet.add(TopControlItem.valueOf(item)) } catch (ignored: Exception) {}
                }
            }

            return ModeLayoutConfig(
                visibleModes = visibleModesList,
                modeSelectorPosition = try {
                    ModeSelectorPosition.valueOf(json.optString("modeSelectorPosition", ModeSelectorPosition.BELOW_SHUTTER.name))
                } catch (e: Exception) { ModeSelectorPosition.BELOW_SHUTTER },
                modeSelectorStyle = try {
                    ModeSelectorStyle.valueOf(json.optString("modeSelectorStyle", ModeSelectorStyle.CLASSIC_DOT.name))
                } catch (e: Exception) { ModeSelectorStyle.CLASSIC_DOT },
                modeTextSizeSp = json.optDouble("modeTextSizeSp", 13.5).toFloat(),
                modeFontFamily = try {
                    FontFamilyOption.valueOf(json.optString("modeFontFamily", FontFamilyOption.DEFAULT.name))
                } catch (e: Exception) { FontFamilyOption.DEFAULT },
                shutterStyle = try {
                    ShutterStyle.valueOf(json.optString("shutterStyle", ShutterStyle.CLASSIC_WHITE.name))
                } catch (e: Exception) { ShutterStyle.CLASSIC_WHITE },
                shutterSizeDp = json.optInt("shutterSizeDp", 80),
                shutterHorizontalOffsetDp = json.optInt("shutterHorizontalOffsetDp", 0),
                flipButtonSizeDp = json.optInt("flipButtonSizeDp", 54),
                galleryThumbSizeDp = json.optInt("galleryThumbSizeDp", 54),
                showGalleryButton = json.optBoolean("showGalleryButton", true),
                showFlipButton = json.optBoolean("showFlipButton", true),
                topControlsOrder = topOrderList,
                hiddenTopControls = hiddenTopSet,
                topControlsIconSizeDp = json.optInt("topControlsIconSizeDp", 24),
                topControlsSpacingDp = json.optInt("topControlsSpacingDp", 16),
                topBarAlignment = try {
                    TopBarAlignment.valueOf(json.optString("topBarAlignment", TopBarAlignment.SPACE_BETWEEN.name))
                } catch (e: Exception) { TopBarAlignment.SPACE_BETWEEN },
                topPaddingDp = json.optInt("topPaddingDp", 12),
                bottomPaddingDp = json.optInt("bottomPaddingDp", 14),
                showZoomCapsule = json.optBoolean("showZoomCapsule", true),
                zoomCapsuleScale = json.optDouble("zoomCapsuleScale", 1.0).toFloat(),
                zoomCapsuleVerticalOffsetDp = json.optInt("zoomCapsuleVerticalOffsetDp", 0),
                accentColorHex = json.optString("accentColorHex", "#FFD54F"),
                textColorHex = json.optString("textColorHex", "#FFFFFF"),
                fontWeightOption = try {
                    FontWeightOption.valueOf(json.optString("fontWeightOption", FontWeightOption.SEMI_BOLD.name))
                } catch (e: Exception) { FontWeightOption.SEMI_BOLD },
                textCaseOption = try {
                    TextCaseOption.valueOf(json.optString("textCaseOption", TextCaseOption.UPPERCASE.name))
                } catch (e: Exception) { TextCaseOption.UPPERCASE },
                letterSpacingSp = json.optDouble("letterSpacingSp", 0.5).toFloat(),
                textShadowOption = try {
                    TextShadowOption.valueOf(json.optString("textShadowOption", TextShadowOption.SUBTLE_SHADOW.name))
                } catch (e: Exception) { TextShadowOption.SUBTLE_SHADOW },
                iconStyleOption = try {
                    IconStyleOption.valueOf(json.optString("iconStyleOption", IconStyleOption.ROUNDED_MATERIAL.name))
                } catch (e: Exception) { IconStyleOption.ROUNDED_MATERIAL },
                iconColorHex = json.optString("iconColorHex", "#FFFFFF"),
                iconShapeOption = try {
                    IconShapeOption.valueOf(json.optString("iconShapeOption", IconShapeOption.TRANSPARENT_NONE.name))
                } catch (e: Exception) { IconShapeOption.TRANSPARENT_NONE },
                iconContainerOpacity = json.optDouble("iconContainerOpacity", 0.35).toFloat(),
                iconStrokeWidthDp = json.optDouble("iconStrokeWidthDp", 1.8).toFloat(),
                customUiPhotoUri = if (json.has("customUiPhotoUri")) json.optString("customUiPhotoUri") else null,
                customUiPhotoOverlayOpacity = json.optDouble("customUiPhotoOverlayOpacity", 0.0).toFloat()
            )
        }
    }
}

data class UiCustomizationState(
    val selectedTemplate: UiTemplateType = UiTemplateType.STOCK_PIXEL,
    val globalConfig: ModeLayoutConfig = CameraUiTemplates.getTemplateConfig(UiTemplateType.STOCK_PIXEL),
    val modeSpecificConfigs: Map<CameraMode, ModeLayoutConfig> = emptyMap(),
    val customPresets: List<CustomUiPreset> = emptyList()
) {
    /**
     * Resolves layout config for a given camera mode (mode-specific override if present, else global config).
     */
    fun getConfigForMode(mode: CameraMode): ModeLayoutConfig {
        return modeSpecificConfigs[mode] ?: globalConfig
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("selectedTemplate", selectedTemplate.name)
        root.put("globalConfig", globalConfig.toJson())

        val modeObj = JSONObject()
        modeSpecificConfigs.forEach { (mode, config) ->
            modeObj.put(mode.name, config.toJson())
        }
        root.put("modeSpecificConfigs", modeObj)

        val presetsArr = JSONArray()
        customPresets.forEach { preset ->
            val pObj = JSONObject()
            pObj.put("id", preset.id)
            pObj.put("name", preset.name)
            pObj.put("templateType", preset.templateType.name)
            pObj.put("config", preset.config.toJson())
            presetsArr.put(pObj)
        }
        root.put("customPresets", presetsArr)

        return root.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): UiCustomizationState {
            if (jsonStr.isBlank()) return UiCustomizationState()
            return try {
                val root = JSONObject(jsonStr)
                val template = try {
                    UiTemplateType.valueOf(root.optString("selectedTemplate", UiTemplateType.STOCK_PIXEL.name))
                } catch (e: Exception) { UiTemplateType.STOCK_PIXEL }

                val gConfig = root.optJSONObject("globalConfig")?.let { ModeLayoutConfig.fromJson(it) }
                    ?: CameraUiTemplates.getTemplateConfig(template)

                val modeMap = mutableMapOf<CameraMode, ModeLayoutConfig>()
                val modeObj = root.optJSONObject("modeSpecificConfigs")
                if (modeObj != null) {
                    val keys = modeObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        try {
                            val mode = CameraMode.valueOf(k)
                            val cfgJson = modeObj.getJSONObject(k)
                            modeMap[mode] = ModeLayoutConfig.fromJson(cfgJson)
                        } catch (ignored: Exception) {}
                    }
                }

                val presetsList = mutableListOf<CustomUiPreset>()
                val pArr = root.optJSONArray("customPresets")
                if (pArr != null) {
                    for (i in 0 until pArr.length()) {
                        val pObj = pArr.getJSONObject(i)
                        val id = pObj.optString("id", System.currentTimeMillis().toString())
                        val name = pObj.optString("name", "Custom Preset")
                        val tType = try {
                            UiTemplateType.valueOf(pObj.optString("templateType", UiTemplateType.CUSTOM.name))
                        } catch (e: Exception) { UiTemplateType.CUSTOM }
                        val cfg = pObj.optJSONObject("config")?.let { ModeLayoutConfig.fromJson(it) }
                            ?: ModeLayoutConfig()
                        presetsList.add(CustomUiPreset(id, name, tType, cfg))
                    }
                }

                UiCustomizationState(
                    selectedTemplate = template,
                    globalConfig = gConfig,
                    modeSpecificConfigs = modeMap,
                    customPresets = presetsList
                )
            } catch (e: Exception) {
                UiCustomizationState()
            }
        }
    }
}

data class CustomUiPreset(
    val id: String,
    val name: String,
    val templateType: UiTemplateType,
    val config: ModeLayoutConfig
)

object CameraUiTemplates {
    fun getTemplateConfig(type: UiTemplateType): ModeLayoutConfig {
        return when (type) {
            UiTemplateType.STOCK_PIXEL -> ModeLayoutConfig(
                modeSelectorPosition = ModeSelectorPosition.BELOW_SHUTTER,
                modeSelectorStyle = ModeSelectorStyle.PIXEL_PILL,
                shutterStyle = ShutterStyle.PIXEL_SOLID,
                shutterSizeDp = 84,
                shutterHorizontalOffsetDp = 0,
                flipButtonSizeDp = 54,
                galleryThumbSizeDp = 54,
                topControlsIconSizeDp = 18,
                topControlsSpacingDp = 18,
                topBarAlignment = TopBarAlignment.SPACE_BETWEEN,
                accentColorHex = "#8AB4F8",
                modeTextSizeSp = 13.0f,
                modeFontFamily = FontFamilyOption.ROUNDED,
                topPaddingDp = 10,
                bottomPaddingDp = 10,
                showZoomCapsule = true,
                zoomCapsuleScale = 1.0f,
                zoomCapsuleVerticalOffsetDp = 0,
                iconShapeOption = IconShapeOption.TRANSPARENT_NONE
            )
            UiTemplateType.MINIMAL_PRO -> ModeLayoutConfig(
                modeSelectorPosition = ModeSelectorPosition.ABOVE_SHUTTER,
                modeSelectorStyle = ModeSelectorStyle.MONO_TICKER,
                shutterStyle = ShutterStyle.LEICA_RED_DOT,
                shutterSizeDp = 76,
                shutterHorizontalOffsetDp = 0,
                flipButtonSizeDp = 48,
                galleryThumbSizeDp = 48,
                topControlsIconSizeDp = 18,
                topControlsSpacingDp = 16,
                topBarAlignment = TopBarAlignment.SPACE_BETWEEN,
                accentColorHex = "#E53935",
                modeTextSizeSp = 12.0f,
                modeFontFamily = FontFamilyOption.MONOSPACE,
                topPaddingDp = 8,
                bottomPaddingDp = 12,
                showZoomCapsule = true,
                zoomCapsuleScale = 0.92f,
                zoomCapsuleVerticalOffsetDp = 4,
                iconShapeOption = IconShapeOption.TRANSPARENT_NONE
            )
            UiTemplateType.FUTURISTIC_GLASS -> ModeLayoutConfig(
                modeSelectorPosition = ModeSelectorPosition.ABOVE_SHUTTER,
                modeSelectorStyle = ModeSelectorStyle.CYBER_GLOW,
                shutterStyle = ShutterStyle.CYBER_HOLO,
                shutterSizeDp = 86,
                shutterHorizontalOffsetDp = 0,
                flipButtonSizeDp = 56,
                galleryThumbSizeDp = 56,
                topControlsIconSizeDp = 20,
                topControlsSpacingDp = 20,
                topBarAlignment = TopBarAlignment.SPACE_BETWEEN,
                accentColorHex = "#00E5FF",
                modeTextSizeSp = 13.5f,
                modeFontFamily = FontFamilyOption.MONOSPACE,
                topPaddingDp = 12,
                bottomPaddingDp = 14,
                showZoomCapsule = true,
                zoomCapsuleScale = 1.05f,
                zoomCapsuleVerticalOffsetDp = -2,
                iconShapeOption = IconShapeOption.TRANSPARENT_NONE
            )
            UiTemplateType.DSLR_PRO -> ModeLayoutConfig(
                modeSelectorPosition = ModeSelectorPosition.ABOVE_SHUTTER,
                modeSelectorStyle = ModeSelectorStyle.DSLR_DIAL,
                shutterStyle = ShutterStyle.DSLR_KNURLED,
                shutterSizeDp = 88,
                shutterHorizontalOffsetDp = 0,
                flipButtonSizeDp = 54,
                galleryThumbSizeDp = 54,
                topControlsIconSizeDp = 18,
                topControlsSpacingDp = 14,
                topBarAlignment = TopBarAlignment.SPACE_BETWEEN,
                accentColorHex = "#FFB300",
                modeTextSizeSp = 12.5f,
                modeFontFamily = FontFamilyOption.MONOSPACE,
                topPaddingDp = 8,
                bottomPaddingDp = 16,
                showZoomCapsule = true,
                zoomCapsuleScale = 0.95f,
                zoomCapsuleVerticalOffsetDp = 0,
                iconShapeOption = IconShapeOption.TRANSPARENT_NONE
            )
            UiTemplateType.IMMERSIVE_EDGE -> ModeLayoutConfig(
                modeSelectorPosition = ModeSelectorPosition.BELOW_SHUTTER,
                modeSelectorStyle = ModeSelectorStyle.MINIMAL_TEXT,
                shutterStyle = ShutterStyle.MINIMAL_ACCENT,
                shutterSizeDp = 78,
                shutterHorizontalOffsetDp = 0,
                flipButtonSizeDp = 48,
                galleryThumbSizeDp = 48,
                topControlsIconSizeDp = 18,
                topControlsSpacingDp = 16,
                topBarAlignment = TopBarAlignment.SPACE_BETWEEN,
                accentColorHex = "#69F0AE",
                modeTextSizeSp = 12.5f,
                modeFontFamily = FontFamilyOption.ROUNDED,
                topPaddingDp = 6,
                bottomPaddingDp = 8,
                showZoomCapsule = false,
                zoomCapsuleScale = 1.0f,
                zoomCapsuleVerticalOffsetDp = 0,
                iconShapeOption = IconShapeOption.TRANSPARENT_NONE
            )
            UiTemplateType.IPHONE -> ModeLayoutConfig(
                modeSelectorPosition = ModeSelectorPosition.ABOVE_SHUTTER,
                modeSelectorStyle = ModeSelectorStyle.CLASSIC_DOT,
                shutterStyle = ShutterStyle.APPLE_DOT,
                shutterSizeDp = 78,
                shutterHorizontalOffsetDp = 0,
                flipButtonSizeDp = 52,
                galleryThumbSizeDp = 52,
                topControlsIconSizeDp = 18,
                topControlsSpacingDp = 14,
                topBarAlignment = TopBarAlignment.SPACE_BETWEEN,
                accentColorHex = "#FFD54F",
                modeTextSizeSp = 13.0f,
                modeFontFamily = FontFamilyOption.DEFAULT,
                topPaddingDp = 10,
                bottomPaddingDp = 12,
                iconShapeOption = IconShapeOption.TRANSPARENT_NONE
            )
            UiTemplateType.SAMSUNG -> ModeLayoutConfig(
                modeSelectorPosition = ModeSelectorPosition.ABOVE_SHUTTER,
                modeSelectorStyle = ModeSelectorStyle.CAPSULE_PILL,
                shutterStyle = ShutterStyle.SAMSUNG_CAPSULE,
                shutterSizeDp = 82,
                shutterHorizontalOffsetDp = 0,
                flipButtonSizeDp = 56,
                galleryThumbSizeDp = 56,
                topControlsIconSizeDp = 20,
                topControlsSpacingDp = 18,
                topBarAlignment = TopBarAlignment.SPACE_BETWEEN,
                accentColorHex = "#FFFFFF",
                modeTextSizeSp = 13.5f,
                modeFontFamily = FontFamilyOption.ROUNDED,
                topPaddingDp = 12,
                bottomPaddingDp = 16,
                iconShapeOption = IconShapeOption.TRANSPARENT_NONE
            )
            UiTemplateType.VIVO -> ModeLayoutConfig(
                modeSelectorPosition = ModeSelectorPosition.BELOW_SHUTTER,
                modeSelectorStyle = ModeSelectorStyle.UNDERLINE,
                shutterStyle = ShutterStyle.VIVO_GIMBAL,
                shutterSizeDp = 84,
                shutterHorizontalOffsetDp = 0,
                flipButtonSizeDp = 54,
                galleryThumbSizeDp = 54,
                topControlsIconSizeDp = 20,
                topControlsSpacingDp = 16,
                topBarAlignment = TopBarAlignment.SPACE_BETWEEN,
                accentColorHex = "#FF7043",
                modeTextSizeSp = 14.0f,
                modeFontFamily = FontFamilyOption.DEFAULT,
                topPaddingDp = 12,
                bottomPaddingDp = 14,
                iconShapeOption = IconShapeOption.TRANSPARENT_NONE
            )
            UiTemplateType.CUSTOM -> ModeLayoutConfig(
                modeSelectorPosition = ModeSelectorPosition.ABOVE_SHUTTER,
                modeSelectorStyle = ModeSelectorStyle.CAPSULE_PILL,
                shutterStyle = ShutterStyle.CLASSIC_WHITE,
                shutterSizeDp = 80,
                shutterHorizontalOffsetDp = 0,
                flipButtonSizeDp = 54,
                galleryThumbSizeDp = 54,
                topControlsIconSizeDp = 20,
                topControlsSpacingDp = 16,
                topBarAlignment = TopBarAlignment.SPACE_BETWEEN,
                accentColorHex = "#64B5F6",
                modeTextSizeSp = 13.5f,
                modeFontFamily = FontFamilyOption.DEFAULT,
                topPaddingDp = 12,
                bottomPaddingDp = 14,
                iconShapeOption = IconShapeOption.TRANSPARENT_NONE
            )
        }
    }
}
