package com.example.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.NightCaptureProgress
import com.example.camera.model.NightConfig

/**
 * Flagship Computational Night Mode HUD Overlay.
 *
 * Provides:
 * - Intelligent AUTO mode (scene & gyro adaptive) or manual 1s–5s duration.
 * - Dynamic scene illumination & stability badges (Bright Night, Normal, Ultra Low Light, Tripod).
 * - Real-time progress dial with frame count, exposure time, and ISO telemetry.
 */
@Composable
fun NightModeOverlay(
    config: NightConfig,
    captureProgress: NightCaptureProgress,
    onDurationChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .testTag("night_mode_overlay")
    ) {
        // 1. Duration Selection Pills (AUTO, 1s, 2s, 3s, 4s, 5s)
        if (!captureProgress.isCapturing) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 190.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Adaptive Scene Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC111116))
                        .border(0.5.dp, Color(0xFFFFB300).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (config.durationSeconds == 0) "ADAPTIVE BRACKETING" else "MANUAL EXPOSURE STACK",
                        color = Color(0xFFFFB300),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xDD18181E))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("night_duration_selector"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.NightsStay,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.padding(start = 6.dp, end = 2.dp).size(16.dp)
                    )

                    // AUTO Pill (0s)
                    val isAutoSelected = config.durationSeconds == 0
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isAutoSelected) Color(0xFFFFB300) else Color.Transparent)
                            .clickable { onDurationChange(0) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("night_duration_auto"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "AUTO",
                            color = if (isAutoSelected) Color.Black else Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = if (isAutoSelected) FontWeight.ExtraBold else FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    // 1s .. 5s Pills
                    listOf(1, 2, 3, 5).forEach { sec ->
                        val isSelected = config.durationSeconds == sec
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) Color(0xFFFFB300) else Color.Transparent)
                                .clickable { onDurationChange(sec) }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                                .testTag("night_duration_${sec}s"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${sec}s",
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        // Viewfinder remains clean and stable during night capture; progress is displayed on the shutter button
    }
}
