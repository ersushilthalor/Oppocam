package com.example.camera.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.WhiteBalanceMode
import com.example.camera.videopipeline.CustomVideoPipelineConfig
import java.util.Locale
import kotlin.math.roundToInt

private val CustomGold = Color(0xFFFFD54F)
private val CustomDarkBg = Color(0xF210121A)
private val CustomCardBg = Color(0xFF181B24)
private val CustomPillUnselected = Color(0xFF1E222D)

enum class CustomPipelineTab(val title: String) {
    EXPOSURE_TONE("Exposure & Tone"),
    DYNAMIC_RANGE("HDR & Curves"),
    COLOR_CHANNELS("Color & RGB"),
    DETAIL_OPTICS("Detail & Optics")
}

/**
 * Complete Control Panel for "Custom Pipeline" with advanced processing-level controls:
 *
 * • Exposure / overall brightness, Black level, Black-clipping control, Midtone control, Contrast, Local contrast, Output gamma
 * • Dynamic range / tone mapping, HDR / tone-mapping strength, Local tone mapping strength, Highlight recovery, Highlight roll-off, Highlight-clipping protection, Shadow recovery, Shadow roll-off, Luma curve, Log-to-display transform strength
 * • White balance, Temperature, Tint, Saturation, Vibrance, Chroma strength, Color matrix / color transform, RGB channel gain (R/G/B), RGB curves (R/G/B), Color highlight/shadow separation
 * • Sharpening, Micro-contrast, Texture/detail, Demosaic/detail processing, Luma noise reduction, Chroma noise reduction, Temporal noise reduction, Spatial noise reduction, Debanding, Lens shading correction, Distortion correction
 */
@Composable
fun CustomVideoPipelineSettingsPanel(
    config: CustomVideoPipelineConfig,
    onConfigChange: (CustomVideoPipelineConfig) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(CustomPipelineTab.EXPOSURE_TONE) }

    FrostedGlassBox(
        modifier = modifier
            .wrapContentWidth()
            .widthIn(min = 270.dp, max = 340.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("custom_pipeline_settings_panel"),
        shape = RoundedCornerShape(20.dp),
        elevation = 14.dp,
        baseAlpha = 0.84f,
        baseTint = Color(0xFF0F121C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .heightIn(max = 330.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(CustomGold)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Column {
                        Text(
                            text = "CUSTOM PIPELINE",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Rec.2020 Natural Log • 38 ISP Controls",
                            color = CustomGold,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reset Button
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onResetDefaults() }
                            .testTag("cvp_reset_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RestartAlt,
                            contentDescription = "Reset Custom Pipeline Defaults",
                            tint = CustomGold,
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    if (onDismiss != null) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onDismiss() }
                                .testTag("cvp_close_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Close",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(7.dp))

            // Navigation Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                CustomPipelineTab.entries.forEach { tab ->
                    val isSelected = activeTab == tab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) CustomGold else CustomPillUnselected)
                            .clickable { activeTab = tab }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                            .testTag("cvp_tab_${tab.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.8f),
                            fontSize = 10.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Active Tab Controls Scrollable Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            when (activeTab) {
                CustomPipelineTab.EXPOSURE_TONE -> {
                    PipelineSliderControl(
                        label = "Exposure / Overall Brightness",
                        value = config.exposure,
                        valueDisplay = String.format(Locale.US, "%+.2f EV", config.exposure),
                        range = -2.0f..2.0f,
                        onValueChange = { onConfigChange(config.copy(exposure = it)) },
                        testTag = "cvp_exposure"
                    )
                    PipelineSliderControl(
                        label = "Black Level (Pedestal)",
                        value = config.blackLevel,
                        valueDisplay = String.format(Locale.US, "%+.3f", config.blackLevel),
                        range = -0.05f..0.05f,
                        onValueChange = { onConfigChange(config.copy(blackLevel = it)) },
                        testTag = "cvp_black_level"
                    )
                    PipelineSliderControl(
                        label = "Black-Clipping Control",
                        value = config.blackClippingControl,
                        valueDisplay = String.format(Locale.US, "%.2f", config.blackClippingControl),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(blackClippingControl = it)) },
                        testTag = "cvp_black_clipping"
                    )
                    PipelineSliderControl(
                        label = "Midtone Control (18% Gray Pivot)",
                        value = config.midtoneControl,
                        valueDisplay = String.format(Locale.US, "%+.2f", config.midtoneControl),
                        range = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(midtoneControl = it)) },
                        testTag = "cvp_midtone"
                    )
                    PipelineSliderControl(
                        label = "Contrast (Filmic S-Slope)",
                        value = config.contrast,
                        valueDisplay = String.format(Locale.US, "%+.2f", config.contrast),
                        range = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(contrast = it)) },
                        testTag = "cvp_contrast"
                    )
                    PipelineSliderControl(
                        label = "Local Contrast (Micro-Dynamic)",
                        value = config.localContrast,
                        valueDisplay = String.format(Locale.US, "%.2f", config.localContrast),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(localContrast = it)) },
                        testTag = "cvp_local_contrast"
                    )
                    PipelineSliderControl(
                        label = "Output Gamma",
                        value = config.outputGamma,
                        valueDisplay = String.format(Locale.US, "%.2f", config.outputGamma),
                        range = 1.8f..2.6f,
                        onValueChange = { onConfigChange(config.copy(outputGamma = it)) },
                        testTag = "cvp_output_gamma"
                    )
                }

                CustomPipelineTab.DYNAMIC_RANGE -> {
                    PipelineSegmentedSelector(
                        label = "Luma Curve Foundation",
                        options = listOf("Rec.2020 Natural Log", "Gentle Filmic S", "Extended DR", "Lifted Shadows"),
                        selectedIndex = config.lumaCurvePreset,
                        onSelect = { onConfigChange(config.copy(lumaCurvePreset = it)) },
                        testTagPrefix = "cvp_lumacurve"
                    )
                    PipelineSliderControl(
                        label = "Dynamic Range / Tone Mapping",
                        value = config.dynamicRangeToneMapping,
                        valueDisplay = String.format(Locale.US, "%.2f", config.dynamicRangeToneMapping),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(dynamicRangeToneMapping = it)) },
                        testTag = "cvp_dyn_range_tm"
                    )
                    PipelineSliderControl(
                        label = "HDR Tone Mapping Strength",
                        value = config.hdrToneMappingStrength,
                        valueDisplay = String.format(Locale.US, "%.2f", config.hdrToneMappingStrength),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(hdrToneMappingStrength = it)) },
                        testTag = "cvp_hdr_strength"
                    )
                    PipelineSliderControl(
                        label = "Local Tone Mapping Strength",
                        value = config.localToneMapping,
                        valueDisplay = String.format(Locale.US, "%.2f", config.localToneMapping),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(localToneMapping = it)) },
                        testTag = "cvp_local_tm"
                    )
                    PipelineSliderControl(
                        label = "Highlight Recovery (Soft Knee)",
                        value = config.highlightRecovery,
                        valueDisplay = String.format(Locale.US, "%.2f", config.highlightRecovery),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(highlightRecovery = it)) },
                        testTag = "cvp_hl_recovery"
                    )
                    PipelineSliderControl(
                        label = "Highlight Roll-off (Shoulder)",
                        value = config.highlightRollOff,
                        valueDisplay = String.format(Locale.US, "%.2f", config.highlightRollOff),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(highlightRollOff = it)) },
                        testTag = "cvp_hl_rolloff"
                    )
                    PipelineSliderControl(
                        label = "Highlight-Clipping Protection",
                        value = config.highlightClippingProtection,
                        valueDisplay = String.format(Locale.US, "%.2f", config.highlightClippingProtection),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(highlightClippingProtection = it)) },
                        testTag = "cvp_hl_clipping"
                    )
                    PipelineSliderControl(
                        label = "Shadow Recovery (Toe Expansion)",
                        value = config.shadowRecovery,
                        valueDisplay = String.format(Locale.US, "%.2f", config.shadowRecovery),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(shadowRecovery = it)) },
                        testTag = "cvp_sh_recovery"
                    )
                    PipelineSliderControl(
                        label = "Shadow Roll-off (Deep Toe)",
                        value = config.shadowRollOff,
                        valueDisplay = String.format(Locale.US, "%.2f", config.shadowRollOff),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(shadowRollOff = it)) },
                        testTag = "cvp_sh_rolloff"
                    )
                    PipelineSliderControl(
                        label = "Log-to-Display Transform Strength",
                        value = config.logToDisplayTransformStrength,
                        valueDisplay = String.format(Locale.US, "%.2f", config.logToDisplayTransformStrength),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(logToDisplayTransformStrength = it)) },
                        testTag = "cvp_log_to_display"
                    )
                }

                CustomPipelineTab.COLOR_CHANNELS -> {
                    PipelineSegmentedSelector(
                        label = "Color Matrix / Color Transform",
                        options = listOf("Rec.2020 Neutral", "Natural Cinema", "Film DCI-P3", "Pure Sensor"),
                        selectedIndex = config.colorMatrixPreset,
                        onSelect = { onConfigChange(config.copy(colorMatrixPreset = it)) },
                        testTagPrefix = "cvp_colormatrix"
                    )
                    PipelineSegmentedSelector(
                        label = "White Balance Hardware Mode",
                        options = listOf("Auto", "Daylight", "Cloudy", "Fluorescent", "Incandescent"),
                        selectedIndex = when (config.whiteBalance) {
                            WhiteBalanceMode.DAYLIGHT -> 1
                            WhiteBalanceMode.CLOUDY -> 2
                            WhiteBalanceMode.FLUORESCENT -> 3
                            WhiteBalanceMode.INCANDESCENT -> 4
                            else -> 0
                        },
                        onSelect = { idx ->
                            val mode = when (idx) {
                                1 -> WhiteBalanceMode.DAYLIGHT
                                2 -> WhiteBalanceMode.CLOUDY
                                3 -> WhiteBalanceMode.FLUORESCENT
                                4 -> WhiteBalanceMode.INCANDESCENT
                                else -> WhiteBalanceMode.AUTO
                            }
                            onConfigChange(config.copy(whiteBalance = mode))
                        },
                        testTagPrefix = "cvp_wb_mode"
                    )
                    PipelineSliderControl(
                        label = "Temperature (Amber ↔ Blue)",
                        value = config.temperature,
                        valueDisplay = String.format(Locale.US, "%+.2f", config.temperature),
                        range = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(temperature = it)) },
                        testTag = "cvp_temperature"
                    )
                    PipelineSliderControl(
                        label = "Tint (Magenta ↔ Green)",
                        value = config.tint,
                        valueDisplay = String.format(Locale.US, "%+.2f", config.tint),
                        range = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(tint = it)) },
                        testTag = "cvp_tint"
                    )
                    PipelineSliderControl(
                        label = "RGB Channel Gain: Red",
                        value = config.redGain,
                        valueDisplay = String.format(Locale.US, "%.2f", config.redGain),
                        range = 0.5f..1.5f,
                        onValueChange = { onConfigChange(config.copy(redGain = it)) },
                        testTag = "cvp_red_gain"
                    )
                    PipelineSliderControl(
                        label = "RGB Channel Gain: Green",
                        value = config.greenGain,
                        valueDisplay = String.format(Locale.US, "%.2f", config.greenGain),
                        range = 0.5f..1.5f,
                        onValueChange = { onConfigChange(config.copy(greenGain = it)) },
                        testTag = "cvp_green_gain"
                    )
                    PipelineSliderControl(
                        label = "RGB Channel Gain: Blue",
                        value = config.blueGain,
                        valueDisplay = String.format(Locale.US, "%.2f", config.blueGain),
                        range = 0.5f..1.5f,
                        onValueChange = { onConfigChange(config.copy(blueGain = it)) },
                        testTag = "cvp_blue_gain"
                    )
                    PipelineSliderControl(
                        label = "RGB Curves: Red Curve Deviation",
                        value = config.redCurveStrength,
                        valueDisplay = String.format(Locale.US, "%+.2f", config.redCurveStrength),
                        range = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(redCurveStrength = it)) },
                        testTag = "cvp_red_curve"
                    )
                    PipelineSliderControl(
                        label = "RGB Curves: Green Curve Deviation",
                        value = config.greenCurveStrength,
                        valueDisplay = String.format(Locale.US, "%+.2f", config.greenCurveStrength),
                        range = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(greenCurveStrength = it)) },
                        testTag = "cvp_green_curve"
                    )
                    PipelineSliderControl(
                        label = "RGB Curves: Blue Curve Deviation",
                        value = config.blueCurveStrength,
                        valueDisplay = String.format(Locale.US, "%+.2f", config.blueCurveStrength),
                        range = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(blueCurveStrength = it)) },
                        testTag = "cvp_blue_curve"
                    )
                    PipelineSliderControl(
                        label = "Saturation",
                        value = config.saturation,
                        valueDisplay = String.format(Locale.US, "%.2f", config.saturation),
                        range = 0.0f..2.0f,
                        onValueChange = { onConfigChange(config.copy(saturation = it)) },
                        testTag = "cvp_saturation"
                    )
                    PipelineSliderControl(
                        label = "Vibrance (Protective)",
                        value = config.vibrance,
                        valueDisplay = String.format(Locale.US, "%+.2f", config.vibrance),
                        range = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(vibrance = it)) },
                        testTag = "cvp_vibrance"
                    )
                    PipelineSliderControl(
                        label = "Chroma Strength",
                        value = config.chromaStrength,
                        valueDisplay = String.format(Locale.US, "%.2f", config.chromaStrength),
                        range = 0.0f..2.0f,
                        onValueChange = { onConfigChange(config.copy(chromaStrength = it)) },
                        testTag = "cvp_chroma_strength"
                    )
                    PipelineSliderControl(
                        label = "Color Highlight / Shadow Separation",
                        value = config.colorHighlightShadowSeparation,
                        valueDisplay = String.format(Locale.US, "%.2f", config.colorHighlightShadowSeparation),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(colorHighlightShadowSeparation = it)) },
                        testTag = "cvp_color_sep"
                    )
                }

                CustomPipelineTab.DETAIL_OPTICS -> {
                    PipelineSliderControl(
                        label = "Sharpening (Spatial Convolution)",
                        value = config.sharpening,
                        valueDisplay = String.format(Locale.US, "%.2f", config.sharpening),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(sharpening = it)) },
                        testTag = "cvp_sharpening"
                    )
                    PipelineSliderControl(
                        label = "Micro-Contrast (Localized Clarity)",
                        value = config.microContrast,
                        valueDisplay = String.format(Locale.US, "%.2f", config.microContrast),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(microContrast = it)) },
                        testTag = "cvp_micro_contrast"
                    )
                    PipelineSliderControl(
                        label = "Texture / Detail Preservation",
                        value = config.textureDetail,
                        valueDisplay = String.format(Locale.US, "%.2f", config.textureDetail),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(textureDetail = it)) },
                        testTag = "cvp_texture_detail"
                    )
                    PipelineSliderControl(
                        label = "Demosaic / Detail Processing",
                        value = config.demosaicDetailProcessing,
                        valueDisplay = String.format(Locale.US, "%.2f", config.demosaicDetailProcessing),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(demosaicDetailProcessing = it)) },
                        testTag = "cvp_demosaic"
                    )
                    PipelineSliderControl(
                        label = "Luma Noise Reduction",
                        value = config.lumaNoiseReduction,
                        valueDisplay = String.format(Locale.US, "%.2f", config.lumaNoiseReduction),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(lumaNoiseReduction = it)) },
                        testTag = "cvp_luma_nr"
                    )
                    PipelineSliderControl(
                        label = "Chroma Noise Reduction",
                        value = config.chromaNoiseReduction,
                        valueDisplay = String.format(Locale.US, "%.2f", config.chromaNoiseReduction),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(chromaNoiseReduction = it)) },
                        testTag = "cvp_chroma_nr"
                    )
                    PipelineSliderControl(
                        label = "Spatial Noise Reduction (Bilateral)",
                        value = config.spatialNoiseReduction,
                        valueDisplay = String.format(Locale.US, "%.2f", config.spatialNoiseReduction),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(spatialNoiseReduction = it)) },
                        testTag = "cvp_spatial_nr"
                    )
                    PipelineSliderControl(
                        label = "Temporal Noise Reduction (Hardware ISP)",
                        value = config.temporalNoiseReduction,
                        valueDisplay = String.format(Locale.US, "%.2f", config.temporalNoiseReduction),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(temporalNoiseReduction = it)) },
                        testTag = "cvp_temporal_nr"
                    )
                    PipelineSliderControl(
                        label = "Debanding (Gradient Dither)",
                        value = config.debanding,
                        valueDisplay = String.format(Locale.US, "%.2f", config.debanding),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(debanding = it)) },
                        testTag = "cvp_debanding"
                    )
                    PipelineSliderControl(
                        label = "Lens Shading Correction",
                        value = config.lensShadingCorrection,
                        valueDisplay = String.format(Locale.US, "%.2f", config.lensShadingCorrection),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(lensShadingCorrection = it)) },
                        testTag = "cvp_lens_shading"
                    )
                    PipelineSliderControl(
                        label = "Distortion Correction (Geometric)",
                        value = config.distortionCorrection,
                        valueDisplay = String.format(Locale.US, "%.2f", config.distortionCorrection),
                        range = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(distortionCorrection = it)) },
                        testTag = "cvp_distortion"
                    )
                }
            }
        }
    }
}
}

@Composable
private fun PipelineSliderControl(
    label: String,
    value: Float,
    valueDisplay: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    testTag: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CustomCardBg)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueDisplay,
                color = CustomGold,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .testTag(testTag),
            colors = SliderDefaults.colors(
                thumbColor = CustomGold,
                activeTrackColor = CustomGold,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            )
        )
    }
}

@Composable
private fun PipelineSegmentedSelector(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    testTagPrefix: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CustomCardBg)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(5.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            options.forEachIndexed { index, title ->
                val isSelected = selectedIndex == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) CustomGold else CustomPillUnselected)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                        .testTag("${testTagPrefix}_$index"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
