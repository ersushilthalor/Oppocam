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
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.CameraResolution
import com.example.camera.ui.components.FrostedGlassBox

/**
 * Liquid Glass Floating Video Settings Panel
 * Consistent with Cinema Mode Liquid Glass styling:
 * - Translucent glass background with specular sheen
 * - Rounded corners (26.dp) and natural depth elevation
 * - Modern, clean resolution, framerate, and stabilization selectors
 */
@Composable
fun FloatingVideoSettingsPanel(
    isOpen: Boolean,
    currentResolution: CameraResolution?,
    currentFps: Int,
    isUltraStabilizationEnabled: Boolean = false,
    onResolutionSelected: (CameraResolution) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onUltraStabilizationToggle: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = Color(0xFFFFD54F) // Master camera gold accent

    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
        modifier = modifier
    ) {
        FrostedGlassBox(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("floating_video_settings_panel"),
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
                // Header: Mode title, accent dot & circular dismiss button
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
                            text = "VIDEO",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "FORMAT",
                            color = accentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onDismiss() }
                            .testTag("video_settings_close"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close Video Settings",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 1: Resolution Selector
                VideoPanelSectionHeader(
                    title = "RESOLUTION",
                    badge = when {
                        currentResolution?.width == 3840 || currentResolution?.height == 3840 -> "4K UHD"
                        currentResolution?.width == 7680 || currentResolution?.height == 7680 -> "8K MAX"
                        currentResolution?.width == 1920 || currentResolution?.height == 1920 -> "1080p FHD"
                        currentResolution?.width == 1280 || currentResolution?.height == 1280 -> "720p HD"
                        else -> "4K UHD"
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                val resolutionOptions = listOf(
                    "720p" to CameraResolution(1280, 720),
                    "1080p" to CameraResolution(1920, 1080),
                    "4K" to CameraResolution(3840, 2160),
                    "8K" to CameraResolution(7680, 4320)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    resolutionOptions.forEach { (label, res) ->
                        val isSelected = currentResolution?.let {
                            (it.width == res.width && it.height == res.height) ||
                            (it.width == res.height && it.height == res.width)
                        } ?: (label == "4K")

                        VideoGlassChip(
                            label = label,
                            isSelected = isSelected,
                            accentColor = accentColor,
                            onClick = { onResolutionSelected(res) },
                            testTag = "res_option_$label"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 2: Frame Rate Selector
                VideoPanelSectionHeader(
                    title = "FRAME RATE",
                    badge = "${currentFps} fps"
                )

                Spacer(modifier = Modifier.height(8.dp))

                val fpsOptions = listOf(
                    "24fps" to 24,
                    "30fps" to 30,
                    "60fps" to 60,
                    "120fps" to 120
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    fpsOptions.forEach { (label, fps) ->
                        val isSelected = currentFps == fps

                        VideoGlassChip(
                            label = label,
                            isSelected = isSelected,
                            accentColor = accentColor,
                            onClick = { onFpsSelected(fps) },
                            testTag = "fps_option_$label"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 3: Ultra Steady Gyro Stabilization
                VideoPanelSectionHeader(
                    title = "STABILIZATION",
                    badge = if (isUltraStabilizationEnabled) "ULTRA GYRO" else "STANDARD"
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isUltraStabilizationEnabled) {
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
                            color = if (isUltraStabilizationEnabled) accentColor.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onUltraStabilizationToggle() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("ultra_stab_toggle")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ultra Steady Gimbal Mode",
                                color = if (isUltraStabilizationEnabled) accentColor else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isUltraStabilizationEnabled) "Hardware OIS + Gyroscopic EIS anti-shake active" else "Standard optical image stabilization",
                                color = Color.White.copy(alpha = 0.60f),
                                fontSize = 11.sp
                            )
                        }

                        Switch(
                            checked = isUltraStabilizationEnabled,
                            onCheckedChange = { onUltraStabilizationToggle() },
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
}

@Composable
private fun VideoPanelSectionHeader(
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

@Composable
private fun VideoGlassChip(
    label: String,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
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
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) accentColor else Color.White.copy(alpha = 0.85f),
            fontSize = 12.5.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
            letterSpacing = 0.3.sp
        )
    }
}
