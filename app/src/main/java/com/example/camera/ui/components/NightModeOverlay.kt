package com.example.camera.ui.components

import androidx.compose.animation.AnimatedVisibility
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

@Composable
fun NightModeOverlay(
    selectedDurationSeconds: Int,
    captureProgress: NightCaptureProgress,
    onDurationSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("night_mode_overlay")
    ) {
        // Duration Selector Pills above Shutter (when not capturing)
        if (!captureProgress.isCapturing) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 190.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD54F).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NightsStay,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(16.dp)
                        )

                        listOf(1, 2, 3, 5).forEach { sec ->
                            val isSelected = selectedDurationSeconds == sec
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSelected) Color(0xFFFFD54F) else Color.Transparent)
                                    .clickable { onDurationSelected(sec) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${sec}s",
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Computational Multi-Frame Fusion · Hand-Shake Reduced",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        // Active Capture Countdown & Fusion Progress Dialog / Ring
        AnimatedVisibility(
            visible = captureProgress.isCapturing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF16161C).copy(alpha = 0.95f),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFFD54F)),
                modifier = Modifier
                    .padding(32.dp)
                    .testTag("night_capture_progress_dialog")
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(90.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { captureProgress.progress },
                            modifier = Modifier.size(90.dp),
                            color = Color(0xFFFFD54F),
                            trackColor = Color.White.copy(alpha = 0.15f),
                            strokeWidth = 6.dp
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "%.1fs".format(captureProgress.remainingSeconds),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = "HOLD DEVICE STEADY",
                        color = Color(0xFFFFD54F),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp
                    )

                    Text(
                        text = captureProgress.statusText,
                        color = Color(0xFFE5E7EB),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
