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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.pipeline.model.PipelinePreset
import com.example.camera.ui.components.FrostedGlassBox

/**
 * Compact, translucent floating window displaying all available photo pipeline presets.
 * Matching the existing floating frosted-glass design language.
 *
 * Tapping a preset immediately selects it and applies it to subsequent photo captures.
 */
@Composable
fun PipelinePresetFloatingWindow(
    activePreset: PipelinePreset,
    allPresets: List<PipelinePreset>,
    onPresetSelected: (PipelinePreset) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentGold = Color(0xFFFFD54F)

    FrostedGlassBox(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("pipeline_preset_floating_window"),
        shape = RoundedCornerShape(24.dp),
        elevation = 20.dp,
        baseAlpha = 0.84f,
        baseTint = Color(0xFF0F121C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header Bar
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
                            .background(accentGold)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PIPELINE",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PRESETS",
                        color = accentGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Active preset badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentGold.copy(alpha = 0.15f))
                            .border(1.dp, accentGold.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = activePreset.name.uppercase(),
                            color = accentGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Close Button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FFFFFF))
                            .testTag("pipeline_preset_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close Pipeline Presets",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Presets Horizontal Scroller
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                allPresets.forEach { preset ->
                    val isSelected = preset.id == activePreset.id

                    val presetColor = when (preset.id) {
                        "preset_natural" -> Color(0xFF81D4FA)
                        "preset_hasselblad" -> Color(0xFFFFB74D)
                        "preset_samsung" -> Color(0xFF64B5F6)
                        "preset_pixel" -> Color(0xFF81C784)
                        "preset_iphone" -> Color(0xFFFF8A65)
                        else -> Color(0xFFBA68C8)
                    }

                    Box(
                        modifier = Modifier
                            .width(135.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) Color(0x33FFD54F) else Color(0x22FFFFFF)
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentGold else Color.White.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                onPresetSelected(preset)
                            }
                            .padding(10.dp)
                            .testTag("pipeline_preset_${preset.id}"),
                        contentAlignment = Alignment.TopStart
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(presetColor)
                                )

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = accentGold,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = preset.name,
                                color = if (isSelected) accentGold else Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = preset.subtitle,
                                color = if (isSelected) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Normal,
                                lineHeight = 12.sp,
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Selected pipeline processes uncompressed sensor data directly on capture.",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
                letterSpacing = 0.2.sp
            )
        }
    }
}
