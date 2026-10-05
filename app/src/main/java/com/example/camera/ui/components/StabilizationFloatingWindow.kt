package com.example.camera.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VideoStable
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideoStable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.engine.eisplus.EisPlusTelemetry
import com.example.camera.model.VideoStabilizationMode

/**
 * Dedicated Floating Selector Window for Video Stabilization.
 * Provides instant switching between:
 * - OFF: Stabilization disabled, full sensor FOV
 * - EIS: Standard Camera2 HAL electronic stabilization
 * - EIS+: Ultra Advanced Multi-Sensor Fusion Stabilization (PhotonCamera EIS+)
 */
@Composable
fun StabilizationFloatingWindow(
    currentMode: VideoStabilizationMode,
    onSelectMode: (VideoStabilizationMode) -> Unit,
    onDismiss: () -> Unit,
    telemetry: EisPlusTelemetry? = null,
    modifier: Modifier = Modifier
) {
    FrostedGlassBox(
        modifier = modifier
            .wrapContentWidth()
            .widthIn(min = 300.dp, max = 360.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("stabilization_floating_window"),
        shape = RoundedCornerShape(24.dp),
        elevation = 16.dp,
        baseAlpha = 0.90f,
        baseTint = Color(0xFF10121A),
        borderWidth = 1.dp,
        borderColor = Color.White.copy(alpha = 0.12f),
        showTopHighlightRim = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                when (currentMode) {
                                    VideoStabilizationMode.EIS_PLUS -> Color(0xFFFFD54F).copy(alpha = 0.22f)
                                    VideoStabilizationMode.EIS -> Color(0xFF81D4FA).copy(alpha = 0.22f)
                                    VideoStabilizationMode.OFF -> Color.White.copy(alpha = 0.08f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (currentMode == VideoStabilizationMode.EIS_PLUS) Icons.Filled.VideoStable else Icons.Outlined.VideoStable,
                            contentDescription = "Stabilization",
                            tint = when (currentMode) {
                                VideoStabilizationMode.EIS_PLUS -> Color(0xFFFFD54F)
                                VideoStabilizationMode.EIS -> Color(0xFF81D4FA)
                                VideoStabilizationMode.OFF -> Color.White.copy(alpha = 0.6f)
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "VIDEO STABILIZATION",
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = when (currentMode) {
                                VideoStabilizationMode.OFF -> "Disabled (Full FOV)"
                                VideoStabilizationMode.EIS -> "Standard Electronic Stabilization"
                                VideoStabilizationMode.EIS_PLUS -> "Ultra Advanced PhotonCamera Fusion"
                            },
                            color = when (currentMode) {
                                VideoStabilizationMode.EIS_PLUS -> Color(0xFFFFD54F)
                                VideoStabilizationMode.EIS -> Color(0xFF81D4FA)
                                VideoStabilizationMode.OFF -> Color.White.copy(alpha = 0.55f)
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Close Button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3 Mode Options
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StabilizationOptionRow(
                    mode = VideoStabilizationMode.OFF,
                    badge = "RAW",
                    title = "OFF",
                    subtitle = "Stabilization Disabled",
                    description = "Zero crop • Full raw sensor field of view",
                    isSelected = currentMode == VideoStabilizationMode.OFF,
                    accentColor = Color.White,
                    onClick = {
                        onSelectMode(VideoStabilizationMode.OFF)
                    }
                )

                StabilizationOptionRow(
                    mode = VideoStabilizationMode.EIS,
                    badge = "HAL",
                    title = "EIS",
                    subtitle = "Standard Electronic Stabilization",
                    description = "Camera2 HAL electronic stabilization • ~5% crop margin",
                    isSelected = currentMode == VideoStabilizationMode.EIS,
                    accentColor = Color(0xFF81D4FA),
                    onClick = {
                        onSelectMode(VideoStabilizationMode.EIS)
                    }
                )

                StabilizationOptionRow(
                    mode = VideoStabilizationMode.EIS_PLUS,
                    badge = "ULTRA",
                    title = "EIS+",
                    subtitle = "Ultra Advanced Stabilization",
                    description = "PhotonCamera Fusion • Gyro+OIS • Rolling shutter • 5–7 look-ahead • Tripod lock",
                    isSelected = currentMode == VideoStabilizationMode.EIS_PLUS,
                    accentColor = Color(0xFFFFD54F),
                    onClick = {
                        onSelectMode(VideoStabilizationMode.EIS_PLUS)
                    }
                )
            }

            // Real-time EIS+ telemetry when active
            if (currentMode == VideoStabilizationMode.EIS_PLUS && telemetry != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color(0xFFFFD54F).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (telemetry.isTripodLocked) Color(0xFF00E676) else Color(0xFFFFD54F))
                        )
                        Text(
                            text = if (telemetry.isTripodLocked) "TRIPOD LOCKED" else "HANDHELD STEADY",
                            color = if (telemetry.isTripodLocked) Color(0xFF00E676) else Color(0xFFFFD54F),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Text(
                        text = "Margin: ${telemetry.dynamicCropMarginPercent}% • Gyro 200Hz",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun StabilizationOptionRow(
    mode: VideoStabilizationMode,
    badge: String,
    title: String,
    subtitle: String,
    description: String,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) accentColor else Color.White.copy(alpha = 0.10f)
    val bgColor = if (isSelected) accentColor.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("stab_option_${mode.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    color = if (isSelected) accentColor else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isSelected) accentColor.copy(alpha = 0.25f)
                            else Color.White.copy(alpha = 0.12f)
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badge,
                        color = if (isSelected) accentColor else Color.White.copy(alpha = 0.7f),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = description,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 13.sp
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(accentColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.Black,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
