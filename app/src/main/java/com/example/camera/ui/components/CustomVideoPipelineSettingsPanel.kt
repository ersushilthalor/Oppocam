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

private val AccentGold = Color(0xFFFFD54F)
private val PanelDarkBg = Color(0xF210121A)
private val CardDarkBg = Color(0xFF181B24)
private val PillUnselectedBg = Color(0xFF1E222D)
private val PillSelectedBg = Color(0xFF333847)

enum class CustomPipelineTab(val title: String) {
    EXPOSURE_TONE("Exposure & Tone"),
    DYNAMIC_RANGE("HDR & Recovery"),
    COLOR("Color & Matrix"),
    DETAIL_NOISE("Detail & Denoise")
}

/**
 * High-precision Control Panel for the dedicated "Custom Pipeline" in Video Mode.
 *
 * Exposes all 25 genuine hardware image/video processing parameters:
 * • Exposure / Brightness, Black Level, Midtone Control, Contrast, Local Contrast, Output Gamma
 * • Highlight Recovery, Highlight Roll-off, Shadow Recovery, Shadow Roll-off, HDR Tone Mapping Strength, Local Tone Mapping, Luma Curve
 * • White Balance, Temperature, Tint, Saturation, Vibrance, Chroma Strength, Color Matrix / Color Transform
 * • Sharpening, Micro-Contrast / Detail, Luma Noise Reduction, Chroma Noise Reduction, Temporal Noise Reduction
 */
@Composable
fun CustomVideoPipelineSettingsPanel(
    config: CustomVideoPipelineConfig,
    onConfigChange: (CustomVideoPipelineConfig) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(CustomPipelineTab.EXPOSURE_TONE) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
            .background(PanelDarkBg)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
            )
            .testTag("custom_pipeline_settings_panel")
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AccentGold)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CUSTOM PIPELINE",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "REC.2020 LOG",
                        color = AccentGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Reset Button
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PillUnselectedBg)
                            .clickable { onResetDefaults() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("custom_pipeline_reset_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.RestartAlt,
                                contentDescription = "Reset Custom Pipeline to Natural Defaults",
                                tint = AccentGold,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "RESET",
                                color = AccentGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Close Button
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onDismiss() }
                            .testTag("custom_pipeline_close_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close Settings",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Category Tab Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CustomPipelineTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) PillSelectedBg else PillUnselectedBg)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) AccentGold.copy(alpha = 0.7f) else Color.Transparent,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { selectedTab = tab }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .testTag("custom_pipeline_tab_${tab.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable Content for Selected Category
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (selectedTab) {
                    CustomPipelineTab.EXPOSURE_TONE -> {
                        PipelineSliderItem(
                            label = "Exposure / Brightness",
                            value = config.exposure,
                            valueDisplay = String.format(Locale.US, "%+.1f EV", config.exposure),
                            range = -2.0f..2.0f,
                            onValueChange = { onConfigChange(config.copy(exposure = (it * 10f).roundToInt() / 10f)) },
                            testTag = "cvp_exposure"
                        )
                        PipelineSliderItem(
                            label = "Black Level",
                            value = config.blackLevel,
                            valueDisplay = String.format(Locale.US, "%+.3f", config.blackLevel),
                            range = -0.05f..0.05f,
                            onValueChange = { onConfigChange(config.copy(blackLevel = it)) },
                            testTag = "cvp_black_level"
                        )
                        PipelineSliderItem(
                            label = "Midtone Control",
                            value = config.midtoneControl,
                            valueDisplay = String.format(Locale.US, "%+.2f", config.midtoneControl),
                            range = -1.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(midtoneControl = it)) },
                            testTag = "cvp_midtone"
                        )
                        PipelineSliderItem(
                            label = "Contrast",
                            value = config.contrast,
                            valueDisplay = String.format(Locale.US, "%+.2f", config.contrast),
                            range = -1.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(contrast = it)) },
                            testTag = "cvp_contrast"
                        )
                        PipelineSliderItem(
                            label = "Local Contrast",
                            value = config.localContrast,
                            valueDisplay = String.format(Locale.US, "%.2f", config.localContrast),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(localContrast = it)) },
                            testTag = "cvp_local_contrast"
                        )
                        PipelineSliderItem(
                            label = "Output Gamma",
                            value = config.outputGamma,
                            valueDisplay = String.format(Locale.US, "%.2f", config.outputGamma),
                            range = 1.8f..2.6f,
                            onValueChange = { onConfigChange(config.copy(outputGamma = (it * 100f).roundToInt() / 100f)) },
                            testTag = "cvp_output_gamma"
                        )
                    }

                    CustomPipelineTab.DYNAMIC_RANGE -> {
                        PipelineSliderItem(
                            label = "Highlight Recovery",
                            value = config.highlightRecovery,
                            valueDisplay = String.format(Locale.US, "%.2f", config.highlightRecovery),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(highlightRecovery = it)) },
                            testTag = "cvp_hl_recovery"
                        )
                        PipelineSliderItem(
                            label = "Highlight Roll-off",
                            value = config.highlightRollOff,
                            valueDisplay = String.format(Locale.US, "%.2f", config.highlightRollOff),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(highlightRollOff = it)) },
                            testTag = "cvp_hl_rolloff"
                        )
                        PipelineSliderItem(
                            label = "Shadow Recovery",
                            value = config.shadowRecovery,
                            valueDisplay = String.format(Locale.US, "%.2f", config.shadowRecovery),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(shadowRecovery = it)) },
                            testTag = "cvp_sh_recovery"
                        )
                        PipelineSliderItem(
                            label = "Shadow Roll-off",
                            value = config.shadowRollOff,
                            valueDisplay = String.format(Locale.US, "%.2f", config.shadowRollOff),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(shadowRollOff = it)) },
                            testTag = "cvp_sh_rolloff"
                        )
                        PipelineSliderItem(
                            label = "HDR / Tone Mapping Strength",
                            value = config.hdrToneMappingStrength,
                            valueDisplay = String.format(Locale.US, "%.2f", config.hdrToneMappingStrength),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(hdrToneMappingStrength = it)) },
                            testTag = "cvp_hdr_strength"
                        )
                        PipelineSliderItem(
                            label = "Local Tone Mapping",
                            value = config.localToneMapping,
                            valueDisplay = String.format(Locale.US, "%.2f", config.localToneMapping),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(localToneMapping = it)) },
                            testTag = "cvp_local_tm"
                        )

                        // Luma Curve Preset Selector
                        PipelineSegmentedSelector(
                            label = "Luma Curve",
                            options = listOf("Natural Log", "Filmic S", "Extended DR", "Lifted"),
                            selectedIndex = config.lumaCurvePreset,
                            onSelect = { onConfigChange(config.copy(lumaCurvePreset = it)) },
                            testTagPrefix = "cvp_lumacurve"
                        )
                    }

                    CustomPipelineTab.COLOR -> {
                        // White Balance Mode
                        PipelineSegmentedSelector(
                            label = "White Balance",
                            options = listOf("Auto", "Daylight", "Cloudy", "Shade", "Tungsten"),
                            selectedIndex = when (config.whiteBalance) {
                                WhiteBalanceMode.AUTO -> 0
                                WhiteBalanceMode.DAYLIGHT -> 1
                                WhiteBalanceMode.CLOUDY -> 2
                                WhiteBalanceMode.SHADE -> 3
                                WhiteBalanceMode.INCANDESCENT -> 4
                                else -> 0
                            },
                            onSelect = { idx ->
                                val mode = when (idx) {
                                    0 -> WhiteBalanceMode.AUTO
                                    1 -> WhiteBalanceMode.DAYLIGHT
                                    2 -> WhiteBalanceMode.CLOUDY
                                    3 -> WhiteBalanceMode.SHADE
                                    4 -> WhiteBalanceMode.INCANDESCENT
                                    else -> WhiteBalanceMode.AUTO
                                }
                                onConfigChange(config.copy(whiteBalance = mode))
                            },
                            testTagPrefix = "cvp_wb"
                        )

                        PipelineSliderItem(
                            label = "Temperature",
                            value = config.temperature,
                            valueDisplay = String.format(Locale.US, "%+.2f", config.temperature),
                            range = -1.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(temperature = it)) },
                            testTag = "cvp_temperature"
                        )
                        PipelineSliderItem(
                            label = "Tint",
                            value = config.tint,
                            valueDisplay = String.format(Locale.US, "%+.2f", config.tint),
                            range = -1.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(tint = it)) },
                            testTag = "cvp_tint"
                        )
                        PipelineSliderItem(
                            label = "Saturation",
                            value = config.saturation,
                            valueDisplay = String.format(Locale.US, "%.2f", config.saturation),
                            range = 0.0f..2.0f,
                            onValueChange = { onConfigChange(config.copy(saturation = it)) },
                            testTag = "cvp_saturation"
                        )
                        PipelineSliderItem(
                            label = "Vibrance",
                            value = config.vibrance,
                            valueDisplay = String.format(Locale.US, "%+.2f", config.vibrance),
                            range = -1.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(vibrance = it)) },
                            testTag = "cvp_vibrance"
                        )
                        PipelineSliderItem(
                            label = "Chroma Strength",
                            value = config.chromaStrength,
                            valueDisplay = String.format(Locale.US, "%.2f", config.chromaStrength),
                            range = 0.0f..2.0f,
                            onValueChange = { onConfigChange(config.copy(chromaStrength = it)) },
                            testTag = "cvp_chroma_strength"
                        )

                        // Color Matrix / Color Transform Preset
                        PipelineSegmentedSelector(
                            label = "Color Matrix / Color Transform",
                            options = listOf("Rec.2020", "Cinema", "DCI-P3", "Sensor"),
                            selectedIndex = config.colorMatrixPreset,
                            onSelect = { onConfigChange(config.copy(colorMatrixPreset = it)) },
                            testTagPrefix = "cvp_colormatrix"
                        )
                    }

                    CustomPipelineTab.DETAIL_NOISE -> {
                        PipelineSliderItem(
                            label = "Sharpening",
                            value = config.sharpening,
                            valueDisplay = String.format(Locale.US, "%.2f", config.sharpening),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(sharpening = it)) },
                            testTag = "cvp_sharpening"
                        )
                        PipelineSliderItem(
                            label = "Micro-Contrast / Detail",
                            value = config.microContrast,
                            valueDisplay = String.format(Locale.US, "%.2f", config.microContrast),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(microContrast = it)) },
                            testTag = "cvp_micro_contrast"
                        )
                        PipelineSliderItem(
                            label = "Luma Noise Reduction",
                            value = config.lumaNoiseReduction,
                            valueDisplay = String.format(Locale.US, "%.2f", config.lumaNoiseReduction),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(lumaNoiseReduction = it)) },
                            testTag = "cvp_luma_nr"
                        )
                        PipelineSliderItem(
                            label = "Chroma Noise Reduction",
                            value = config.chromaNoiseReduction,
                            valueDisplay = String.format(Locale.US, "%.2f", config.chromaNoiseReduction),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(chromaNoiseReduction = it)) },
                            testTag = "cvp_chroma_nr"
                        )
                        PipelineSliderItem(
                            label = "Temporal Noise Reduction",
                            value = config.temporalNoiseReduction,
                            valueDisplay = String.format(Locale.US, "%.2f", config.temporalNoiseReduction),
                            range = 0.0f..1.0f,
                            onValueChange = { onConfigChange(config.copy(temporalNoiseReduction = it)) },
                            testTag = "cvp_temporal_nr"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineSliderItem(
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
            .clip(RoundedCornerShape(12.dp))
            .background(CardDarkBg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueDisplay,
                color = AccentGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
            colors = SliderDefaults.colors(
                thumbColor = AccentGold,
                activeTrackColor = AccentGold,
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
            .clip(RoundedCornerShape(12.dp))
            .background(CardDarkBg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEachIndexed { index, title ->
                val isSelected = selectedIndex == index
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) AccentGold else PillUnselectedBg)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("${testTagPrefix}_$index"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
