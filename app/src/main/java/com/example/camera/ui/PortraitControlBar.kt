package com.example.camera.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FaceRetouchingNatural
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.BokehStyle
import com.example.camera.model.PortraitConfig
import com.example.camera.model.PortraitProcessingState
import com.example.camera.ui.components.FrostedGlassBox

/**
 * Liquid Glass Floating Portrait Settings Window
 * Consistent with Cinema Mode Liquid Glass styling:
 * - Translucent glass background with specular sheen
 * - Rounded corners (26.dp) and natural depth elevation
 * - Modern, clean aperture selector, blur intensity slider, and bokeh styles
 */
@Composable
fun PortraitControlBar(
    config: PortraitConfig,
    processingState: PortraitProcessingState = PortraitProcessingState(),
    onBlurStrengthChanged: (Float) -> Unit,
    onApertureSelected: (String) -> Unit,
    onBokehStyleSelected: (BokehStyle) -> Unit,
    onToggleFaceEnhancement: () -> Unit = {},
    onToggleSkinTone: () -> Unit = {},
    onToggleOpticalBlurGuided: () -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accentColor = Color(0xFFFFD54F) // Master camera gold accent
    val apertures = listOf("f/0.95", "f/1.2", "f/1.4", "f/1.8", "f/2.4", "f/2.8")

    FrostedGlassBox(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("portrait_control_bar"),
        shape = RoundedCornerShape(26.dp),
        elevation = 20.dp,
        baseAlpha = 0.82f,
        baseTint = Color(0xFF0F121C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            // Header: Title, accent dot, aperture badge & circular close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PORTRAIT",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DEPTH",
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Current aperture pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .border(1.dp, accentColor.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = config.simulatedAperture,
                            color = accentColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onClose() }
                            .testTag("close_portrait_settings"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close Portrait Settings",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 1: Simulated Aperture Chips
            PortraitSectionHeader(
                title = "SIMULATED APERTURE",
                badge = config.simulatedAperture
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                apertures.forEach { aperture ->
                    val isSelected = config.simulatedAperture == aperture

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) {
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            accentColor.copy(alpha = 0.25f),
                                            accentColor.copy(alpha = 0.10f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.06f),
                                            Color.White.copy(alpha = 0.02f)
                                        )
                                    )
                                }
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onApertureSelected(aperture) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("aperture_chip_$aperture"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "f",
                                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.70f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Italic,
                                fontFamily = FontFamily.Serif
                            )
                            Text(
                                text = aperture.removePrefix("f"),
                                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 2: Blur Intensity Slider
            PortraitSectionHeader(
                title = "BLUR INTENSITY",
                badge = "${config.blurStrength.toInt()}%"
            )

            Spacer(modifier = Modifier.height(6.dp))

            Slider(
                value = config.blurStrength,
                onValueChange = onBlurStrengthChanged,
                valueRange = 0f..100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("portrait_blur_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Section 3: Cinematic Bokeh Character
            PortraitSectionHeader(
                title = "BOKEH CHARACTER",
                badge = config.bokehStyle.label
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BokehStyle.entries.forEach { style ->
                    val isSelected = config.bokehStyle == style

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) {
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            accentColor.copy(alpha = 0.25f),
                                            accentColor.copy(alpha = 0.10f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.06f),
                                            Color.White.copy(alpha = 0.02f)
                                        )
                                    )
                                }
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onBokehStyleSelected(style) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("bokeh_style_${style.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = style.label,
                            color = if (isSelected) accentColor else Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 4: Optical Blur Guidance Toggle Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (config.opticalBlurGuided) {
                            Brush.verticalGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = 0.16f),
                                    accentColor.copy(alpha = 0.06f)
                                )
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.05f),
                                    Color.White.copy(alpha = 0.02f)
                                )
                            )
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = if (config.opticalBlurGuided) accentColor.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onToggleOpticalBlurGuided() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("optical_blur_guided_toggle")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Optical Blur Guidance",
                            color = if (config.opticalBlurGuided) accentColor else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (config.opticalBlurGuided)
                                "Physical lens defocus + fine depth & hair matting"
                            else
                                "Standard synthetic portrait blur",
                            color = Color.White.copy(alpha = 0.60f),
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = config.opticalBlurGuided,
                        onCheckedChange = { onToggleOpticalBlurGuided() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = accentColor,
                            checkedTrackColor = accentColor.copy(alpha = 0.35f),
                            uncheckedThumbColor = Color.White.copy(alpha = 0.65f),
                            uncheckedTrackColor = Color.White.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitSectionHeader(
    title: String,
    badge: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Color(0xFFFFD54F))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        }

        if (badge != null) {
            Text(
                text = badge,
                color = Color(0xFFFFD54F),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
