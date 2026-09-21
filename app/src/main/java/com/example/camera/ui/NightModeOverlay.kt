package com.example.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
 * Computational Night Mode Long-Exposure HUD Overlay.
 * Allows adjusting capture duration between 1s–5s and presents smooth computational
 * fusion progress indicators during active capture.
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
        // 1. Duration Selection Pills (1s, 2s, 3s, 4s, 5s)
        if (!captureProgress.isCapturing) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 190.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xDD18181E))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("night_duration_selector"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.NightsStay,
                    contentDescription = null,
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.padding(start = 6.dp, end = 4.dp).size(16.dp)
                )

                (1..5).forEach { sec ->
                    val isSelected = config.durationSeconds == sec
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) Color(0xFFFFB300) else Color.Transparent)
                            .clickable { onDurationChange(sec) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("night_duration_${sec}s"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${sec}s",
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // 2. Active Multi-Frame Capture & Alignment Progress
        AnimatedVisibility(
            visible = captureProgress.isCapturing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xF0121218))
                    .border(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .padding(28.dp)
                    .testTag("night_progress_dialog"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { captureProgress.progress },
                        modifier = Modifier.size(76.dp),
                        color = Color(0xFFFFB300),
                        strokeWidth = 4.dp,
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                    Text(
                        text = "%.1fs".format(captureProgress.remainingSeconds),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "COMPUTATIONAL NIGHT FUSION",
                    color = Color(0xFFFFB300),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    softWrap = false
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = captureProgress.statusText,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
