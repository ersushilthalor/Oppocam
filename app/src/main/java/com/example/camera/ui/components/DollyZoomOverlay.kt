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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Videocam
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
import com.example.camera.model.DollyDirection
import com.example.camera.model.DollyZoomState

@Composable
fun DollyZoomOverlay(
    dollyState: DollyZoomState,
    onCalibrate: () -> Unit,
    onReset: () -> Unit,
    onDirectionChange: (DollyDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("dolly_zoom_overlay"),
        contentAlignment = Alignment.Center
    ) {
        // Center Subject Reticle
        Box(
            modifier = Modifier
                .size(180.dp)
                .border(
                    width = 2.dp,
                    color = if (dollyState.isCalibrated) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CenterFocusStrong,
                contentDescription = "Subject Alignment",
                tint = if (dollyState.isCalibrated) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(36.dp)
            )

            if (dollyState.isCalibrated) {
                Text(
                    text = "SUBJECT LOCKED",
                    color = Color(0xFFFFD54F),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                )
            }
        }

        // Top Status Bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 110.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.75f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD54F).copy(alpha = 0.6f)),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (dollyState.isCalibrated) Color(0xFF4CAF50) else Color(0xFFFFD54F))
                    )
                    Text(
                        text = dollyState.statusPrompt,
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Metrics readout
            if (dollyState.isCalibrated) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = "Dist: %.1fm · Zoom: %.2fx (Base: %.1fx)".format(
                            dollyState.currentDistanceMeters,
                            dollyState.smoothedZoom,
                            dollyState.initialZoom
                        ),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Bottom Controls Bar (above shutter)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 200.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Direction Selector Pills
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DollyDirection.entries.forEach { dir ->
                    val isSelected = dollyState.direction == dir
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) Color(0xFFFFD54F) else Color.Black.copy(alpha = 0.6f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onDirectionChange(dir) }
                    ) {
                        Text(
                            text = dir.label,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Calibrate & Reset Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onCalibrate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dollyState.isCalibrated) Color(0xFF2E7D32) else Color(0xFFFFD54F),
                        contentColor = if (dollyState.isCalibrated) Color.White else Color.Black
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("dolly_calibrate_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (dollyState.isCalibrated) "Recalibrate Subject" else "Calibrate Subject",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (dollyState.isCalibrated) {
                    IconButton(
                        onClick = onReset,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Dolly",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
