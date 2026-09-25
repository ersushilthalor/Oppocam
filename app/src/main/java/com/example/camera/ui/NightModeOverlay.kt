package com.example.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.NightsStay
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
import com.example.camera.model.NightCaptureProgress
import com.example.camera.model.NightConfig
import com.example.camera.ui.components.FrostedGlassBox

/**
 * Flagship Computational Night Mode Overlay.
 *
 * Requirements:
 * - Viewfinder strictly 3:4 aspect ratio without stretching or distortion.
 * - Removed unnecessary option below the zoom bar.
 * - Compact Night HDR control panel directly below the top toolbar.
 * - Prevents overlap with viewfinder and shutter button while preserving Night Mode and HDR functionality.
 */
@Composable
fun NightModeOverlay(
    config: NightConfig,
    captureProgress: NightCaptureProgress,
    onDurationChange: (Int) -> Unit,
    onToggleNightHdr: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("night_mode_overlay")
    ) {
        // Compact Control Panel directly below top toolbar
        AnimatedVisibility(
            visible = !captureProgress.isCapturing,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp, start = 14.dp, end = 14.dp)
        ) {
            FrostedGlassBox(
                shape = RoundedCornerShape(20.dp),
                elevation = 14.dp,
                baseAlpha = 0.88f,
                baseTint = Color(0xFF0E1118),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("night_hdr_compact_panel")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Night HDR Icon Toggle Button
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x33FFB300))
                            .border(1.dp, Color(0xFFFFB300).copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                            .clickable { onToggleNightHdr() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NightsStay,
                            contentDescription = "Night HDR",
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "NIGHT HDR",
                            color = Color(0xFFFFB300),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Right: Compact Exposure Duration Selector (AUTO, 1s, 2s, 3s, 5s)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // AUTO Option
                        val isAutoSelected = config.durationSeconds == 0
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isAutoSelected) Color(0xFFFFB300) else Color.White.copy(alpha = 0.08f))
                                .border(
                                    width = 1.dp,
                                    color = if (isAutoSelected) Color(0xFFFFB300) else Color.White.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onDurationChange(0) }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .testTag("night_duration_auto"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "AUTO",
                                color = if (isAutoSelected) Color.Black else Color.White,
                                fontSize = 10.5.sp,
                                fontWeight = if (isAutoSelected) FontWeight.Black else FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        // Duration presets (1s, 2s, 3s, 5s)
                        listOf(1, 2, 3, 5).forEach { sec ->
                            val isSelected = config.durationSeconds == sec
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFFFFB300) else Color.White.copy(alpha = 0.08f))
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFFFFB300) else Color.White.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onDurationChange(sec) }
                                    .padding(horizontal = 7.dp, vertical = 5.dp)
                                    .testTag("night_duration_${sec}s"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${sec}s",
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }

        // Active Capture Countdown HUD (subtle, non-overlapping)
        if (captureProgress.isCapturing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xCC0E1118),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { captureProgress.progress },
                            modifier = Modifier.size(14.dp),
                            color = Color(0xFFFFB300),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "HOLD STILL • FUSING FRAMES",
                            color = Color(0xFFFFB300),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }
        }
    }
}
