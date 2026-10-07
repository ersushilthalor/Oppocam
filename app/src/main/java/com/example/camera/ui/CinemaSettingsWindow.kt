package com.example.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.CinemaConfig
import com.example.camera.model.CinemaHardwareCapabilities
import com.example.camera.ui.components.FrostedGlassBox

/**
 * Dedicated Fine Tuning Controls Window.
 *
 * Exclusively displays physical ISP and color grading Fine Tuning Controls:
 * - Exposure, Contrast S-Curve, Luma Curve, Output Gamma, Washed-Out Recovery
 * - Tonal zones: Whites, Highlights, Highlight Roll-Off, Midtones, Shadows, Shadow Roll-Off, Blacks, Black Level
 * - Color & WB: Temperature, Tint, Saturation, Vibrance, Chroma Strength, Color Matrix
 * - Detail & Texture: Sharpening, Micro-Contrast, Local Contrast, Tone Map Strength, Luma NR, Chroma NR
 *
 * General camera settings (Resolution, FPS, Codec, Bit depth, Hardware Noise modes, OIS, Assist tools)
 * are excluded from this screen to keep it 100% focused on fine tuning.
 */
@Composable
fun CinemaSettingsWindow(
    config: CinemaConfig,
    capabilities: CinemaHardwareCapabilities,
    onConfigChange: (CinemaConfig) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    FrostedGlassBox(
        modifier = modifier
            .wrapContentWidth()
            .widthIn(min = 280.dp, max = 340.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("cinema_settings_window"),
        shape = RoundedCornerShape(20.dp),
        elevation = 14.dp,
        baseTint = Color(0xFF0F121C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: Title, reset button & dismiss button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFD54F))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "FINE TUNING",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CONTROLS",
                        color = Color(0xFFFFD54F),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (config.hasColorFineTuning) {
                        TextButton(
                            onClick = { resetFineTuning(config, onConfigChange) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "RESET",
                                color = Color(0xFFFFD54F),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onDismissRequest() }
                            .testTag("cinema_settings_close"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close Fine Tuning Controls",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ASPECT RATIO: 16:9, IMAX 1.43:1, Cinematic 2.39:1
            CinemaSubSectionHeader("CINEMA ASPECT RATIO")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                com.example.camera.model.CinemaAspectRatio.values().forEach { ratio ->
                    val isSelected = config.aspectRatio == ratio
                    val tagSuffix = when (ratio) {
                        com.example.camera.model.CinemaAspectRatio.RATIO_16_9 -> "16_9"
                        com.example.camera.model.CinemaAspectRatio.IMAX -> "imax"
                        com.example.camera.model.CinemaAspectRatio.CINEMATIC -> "cinematic"
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.08f))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onConfigChange(config.copy(aspectRatio = ratio)) }
                            .testTag("cinema_aspect_option_$tagSuffix"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = ratio.label,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // STAGE 1: EXPOSURE & CONTRAST
            CinemaSubSectionHeader("1. EXPOSURE & CONTRAST")
            CinemaSliderRow(
                label = "Live Exposure",
                value = config.exposure,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(exposure = it)) }
            )
            CinemaSliderRow(
                label = "Contrast S-Curve",
                value = config.contrast,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(contrast = it)) }
            )
            CinemaSliderRow(
                label = "Luma Curve",
                value = config.lumaCurve,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(lumaCurve = it)) }
            )
            CinemaSliderRow(
                label = "Output Gamma",
                value = config.outputGamma,
                valueRange = 0.5f..1.5f,
                onValueChange = { onConfigChange(config.copy(outputGamma = it)) }
            )
            CinemaSliderRow(
                label = "Washed-Out Recovery",
                value = config.washedOut,
                valueRange = 0.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(washedOut = it)) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // STAGE 2: TONAL ZONES (BLACKS, SHADOWS, MIDTONES, HIGHLIGHTS, WHITES)
            CinemaSubSectionHeader("2. TONAL ZONES")
            CinemaSliderRow(
                label = "Whites",
                value = config.whites,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(whites = it)) }
            )
            CinemaSliderRow(
                label = "Highlights",
                value = config.highlights,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(highlights = it)) }
            )
            CinemaSliderRow(
                label = "Highlight Roll-off",
                value = config.highlightRolloff,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(highlightRolloff = it)) }
            )
            CinemaSliderRow(
                label = "Midtones",
                value = config.midtones,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(midtones = it)) }
            )
            CinemaSliderRow(
                label = "Shadows",
                value = config.shadows,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(shadows = it)) }
            )
            CinemaSliderRow(
                label = "Shadow Roll-off",
                value = config.shadowRolloff,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(shadowRolloff = it)) }
            )
            CinemaSliderRow(
                label = "Blacks",
                value = config.blacks,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(blacks = it)) }
            )
            CinemaSliderRow(
                label = "Black Level",
                value = config.blackLevel,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(blackLevel = it)) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // STAGE 3: COLOR & WHITE BALANCE
            CinemaSubSectionHeader("3. COLOR & WHITE BALANCE")
            CinemaSliderRow(
                label = "Temperature",
                value = config.temperature,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(temperature = it)) }
            )
            CinemaSliderRow(
                label = "Tint",
                value = config.tint,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(tint = it)) }
            )
            CinemaSliderRow(
                label = "Saturation",
                value = config.saturation,
                valueRange = 0.0f..2.0f,
                onValueChange = { onConfigChange(config.copy(saturation = it)) }
            )
            CinemaSliderRow(
                label = "Vibrance",
                value = config.vibrance,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(vibrance = it)) }
            )
            CinemaSliderRow(
                label = "Chroma Strength",
                value = config.chromaStrength,
                valueRange = 0.0f..2.0f,
                onValueChange = { onConfigChange(config.copy(chromaStrength = it)) }
            )
            CinemaSliderRow(
                label = "Color Matrix",
                value = config.colorTransform,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(colorTransform = it)) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // STAGE 4: DETAIL, SHARPNESS & NOISE REDUCTION
            CinemaSubSectionHeader("4. DETAIL & SHARPNESS")
            CinemaSliderRow(
                label = "Sharpening",
                value = config.fineSharpening,
                valueRange = 0.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(fineSharpening = it)) }
            )
            CinemaSliderRow(
                label = "Micro-Contrast",
                value = config.microContrast,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(microContrast = it)) }
            )
            CinemaSliderRow(
                label = "Local Contrast",
                value = config.localContrast,
                valueRange = -1.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(localContrast = it)) }
            )
            CinemaSliderRow(
                label = "Tone Map Strength",
                value = config.toneMappingStrength,
                valueRange = 0.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(toneMappingStrength = it)) }
            )
            CinemaSliderRow(
                label = "Luma NR",
                value = config.lumaNoiseReduction,
                valueRange = 0.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(lumaNoiseReduction = it)) }
            )
            CinemaSliderRow(
                label = "Chroma NR",
                value = config.chromaNoiseReduction,
                valueRange = 0.0f..1.0f,
                onValueChange = { onConfigChange(config.copy(chromaNoiseReduction = it)) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom Reset Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(0.8.dp, Color(0xFFFFD54F).copy(alpha = 0.40f), RoundedCornerShape(10.dp))
                    .clickable { resetFineTuning(config, onConfigChange) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = "Reset Fine-Tuning",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "RESET ALL FINE-TUNING",
                        color = Color(0xFFFFD54F),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

private fun resetFineTuning(config: CinemaConfig, onConfigChange: (CinemaConfig) -> Unit) {
    onConfigChange(
        config.copy(
            exposure = 0.0f,
            contrast = 0.0f,
            saturation = 1.0f,
            washedOut = 0.0f,
            shadows = 0.0f,
            highlights = 0.0f,
            vibrance = 0.0f,
            tint = 0.0f,
            temperature = 0.0f,
            blacks = 0.0f,
            whites = 0.0f,
            midtones = 0.0f,
            fineSharpening = 0.0f,
            microContrast = 0.0f,
            localContrast = 0.0f,
            colorTransform = 0.0f,
            chromaStrength = 1.0f,
            toneMappingStrength = 0.0f,
            blackLevel = 0.0f,
            lumaCurve = 0.0f,
            outputGamma = 1.0f,
            highlightRolloff = 0.0f,
            shadowRolloff = 0.0f,
            lumaNoiseReduction = 0.0f,
            chromaNoiseReduction = 0.0f
        )
    )
}

@Composable
private fun CinemaSubSectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFFFFD54F),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun CinemaSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
            modifier = Modifier.width(115.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFFD54F),
                activeTrackColor = Color(0xFFFFD54F),
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
        )
        Text(
            text = String.format("%.2f", value),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End
        )
    }
}
