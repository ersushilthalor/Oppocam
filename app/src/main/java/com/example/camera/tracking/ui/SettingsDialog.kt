package com.example.camera.tracking.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
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
import com.example.camera.tracking.model.TrackingAspectRatio
import com.example.camera.tracking.model.TrackingCameraLens
import com.example.camera.tracking.model.TrackingFpsOption
import com.example.camera.tracking.model.TrackingResolution
import com.example.camera.tracking.model.VideoResolution
import com.example.camera.tracking.model.ViewfinderResolution
import com.example.camera.tracking.viewmodel.CameraTrackingUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    uiState: CameraTrackingUiState,
    onDismiss: () -> Unit,
    onAspectRatioChanged: (TrackingAspectRatio) -> Unit,
    onTrackingIntensityChanged: (Float) -> Unit,
    onVideoResolutionChanged: (VideoResolution) -> Unit,
    onViewfinderResolutionChanged: (ViewfinderResolution) -> Unit = {},
    onTrackingResolutionChanged: (TrackingResolution) -> Unit = {},
    onCameraLensChanged: (TrackingCameraLens) -> Unit = {},
    onFpsOptionChanged: (TrackingFpsOption) -> Unit = {},
    onClearLearnedProfiles: () -> Unit = {},
    onToggleGimbal: (Boolean) -> Unit,
    onGimbalSensitivityChanged: (Float) -> Unit,
    onStartCinematicPan: (durationSec: Float) -> Unit,
    onStopCinematicPan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A),
        contentColor = Color.White,
        modifier = modifier.testTag("settings_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Camera & Tracking Settings",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("settings_close_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF94A3B8)
                    )
                }
            }

            HorizontalDivider(
                color = Color(0xFF334155),
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // 1. ASPECT RATIO
            SettingSectionHeader(
                icon = Icons.Default.AspectRatio,
                title = "Aspect Ratio",
                subtitle = "Active framing mask for photo capture & video recording"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackingAspectRatio.values().forEach { ratio ->
                    val isSelected = uiState.aspectRatio == ratio
                    AspectChip(
                        ratio = ratio,
                        isSelected = isSelected,
                        onClick = { onAspectRatioChanged(ratio) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = "${uiState.aspectRatio.label} • ${uiState.aspectRatio.description}",
                fontSize = 12.sp,
                color = Color(0xFF38BDF8),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider(color = Color(0xFF1E293B))

            // 2. TRACKING INTENSITY (SPEED)
            Spacer(modifier = Modifier.height(12.dp))
            SettingSectionHeader(
                icon = Icons.Default.Speed,
                title = "Tracking Intensity (Speed)",
                subtitle = "Control tracking smoothness vs rapid snap response"
            )

            val intensity = uiState.trackingIntensity
            val intensityLabel = when {
                intensity <= 0.6f -> "Cinematic Smooth (0.5×)"
                intensity <= 1.3f -> "Balanced Standard (1.0×)"
                intensity <= 2.2f -> "Fast Action (1.8×)"
                else -> "Ultra Fast / Instant (3.0×)"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = intensityLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF38BDF8)
                )
                Text(
                    text = "${String.format("%.1f", intensity)}×",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Slider(
                value = intensity,
                onValueChange = { onTrackingIntensityChanged(it) },
                valueRange = 0.2f..3.0f,
                steps = 13,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF38BDF8),
                    activeTrackColor = Color(0xFF0284C7),
                    inactiveTrackColor = Color(0xFF334155)
                ),
                modifier = Modifier.testTag("tracking_intensity_slider")
            )

            // Quick Intensity Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    0.5f to "Smooth",
                    1.0f to "Normal",
                    1.8f to "Fast",
                    3.0f to "Ultra"
                ).forEach { (presetVal, label) ->
                    val isCurrent = kotlin.math.abs(intensity - presetVal) < 0.15f
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCurrent) Color(0xFF0284C7) else Color(0xFF1E293B),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onTrackingIntensityChanged(presetVal) }
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isCurrent) Color.White else Color(0xFF94A3B8),
                            modifier = Modifier.padding(vertical = 6.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF1E293B))

            // 3. DIGITAL GIMBAL (ROCK-STEADY 3X EIS)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Digital Gimbal (Rock-Steady EIS)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Text(
                        text = "3× crop margin + ultra-fast Gyroscope shake counter-shift keeps video rock-steady.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 15.sp
                    )
                }

                Switch(
                    checked = uiState.isGimbalEnabled,
                    onCheckedChange = { onToggleGimbal(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF10B981),
                        uncheckedThumbColor = Color(0xFF64748B),
                        uncheckedTrackColor = Color(0xFF1E293B)
                    ),
                    modifier = Modifier.testTag("gimbal_switch")
                )
            }

            AnimatedVisibility(visible = uiState.isGimbalEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Gimbal Damping Sensitivity",
                            fontSize = 12.sp,
                            color = Color(0xFFE2E8F0)
                        )
                        Text(
                            text = "${String.format("%.1f", uiState.gimbalSensitivity)}×",
                            fontSize = 12.sp,
                            color = Color(0xFF10B981),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = uiState.gimbalSensitivity,
                        onValueChange = { onGimbalSensitivityChanged(it) },
                        valueRange = 0.5f..2.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF10B981),
                            activeTrackColor = Color(0xFF059669),
                            inactiveTrackColor = Color(0xFF334155)
                        )
                    )

                    Surface(
                        color = Color(0xFF065F46).copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Real Hardware Gyroscope Active (Real-Time EIS)",
                                fontSize = 11.sp,
                                color = Color(0xFF6EE7B7),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF1E293B))

            // 4. CINEMATIC PAN (LEFT TO RIGHT)
            Spacer(modifier = Modifier.height(12.dp))
            SettingSectionHeader(
                icon = Icons.Default.Movie,
                title = "Cinematic Pan (Constant Speed)",
                subtitle = "Smooth horizontal sweep across the 3× crop frame at strictly constant velocity"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(3f to "3 sec", 5f to "5 sec", 8f to "8 sec", 10f to "10 sec").forEach { (dur, label) ->
                    val isSelected = uiState.cinematicPanDurationSec == dur
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) Color(0xFF6366F1) else Color(0xFF1E293B),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onStartCinematicPan(dur) }
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = Color.White,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            if (uiState.isCinematicPanActive) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF312E81), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Panning: ${(uiState.cinematicPanProgress * 100).toInt()}%",
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Button(
                            onClick = onStopCinematicPan,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Cancel Pan", fontSize = 10.sp)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { uiState.cinematicPanProgress },
                        color = Color(0xFFA5B4FC),
                        trackColor = Color(0xFF1E1B4B),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            } else {
                Button(
                    onClick = {
                        onStartCinematicPan(uiState.cinematicPanDurationSec)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("start_pan_btn")
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Start ${uiState.cinematicPanDurationSec.toInt()}s Cinematic Pan (Left → Right)")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF1E293B))

            // 5. VIDEO RESOLUTION
            Spacer(modifier = Modifier.height(12.dp))
            SettingSectionHeader(
                icon = Icons.Default.Videocam,
                title = "Video Recording Resolution",
                subtitle = "Bitrate and canvas encoding for tracked video files"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VideoResolution.values().forEach { res ->
                    val isSelected = uiState.videoResolution == res
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF0369A1) else Color(0xFF1E293B)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onVideoResolutionChanged(res) }
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = res.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "${res.width}p",
                                fontSize = 10.sp,
                                color = if (isSelected) Color(0xFFBAE6FD) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF1E293B))

            // 6. VIEWFINDER RESOLUTION
            Spacer(modifier = Modifier.height(12.dp))
            SettingSectionHeader(
                icon = Icons.Default.CropFree,
                title = "Viewfinder Resolution (2K to 4K)",
                subtitle = "Live camera sensor feed & tracking engine resolution (1080p • 2K QHD • 4K UHD)"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ViewfinderResolution.values().forEach { res ->
                    val isSelected = uiState.viewfinderResolution == res
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF0F766E) else Color(0xFF1E293B)
                        ),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF2DD4BF)) else null,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onViewfinderResolutionChanged(res) }
                            .testTag("viewfinder_res_${res.name}")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = res.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "${res.width}p",
                                fontSize = 10.sp,
                                color = if (isSelected) Color(0xFF99F6E4) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF1E293B))

            // 6b. AI TRACKING INPUT RESOLUTION (720p / 1080p)
            Spacer(modifier = Modifier.height(12.dp))
            SettingSectionHeader(
                icon = Icons.Default.Tune,
                title = "AI Tracking Input Resolution",
                subtitle = "Controls resolution fed to ML tracking engine. Preview stays full resolution. 720p defaults for stable 30 FPS."
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackingResolution.values().forEach { res ->
                    val isSelected = uiState.trackingResolution == res
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B)
                        ),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8)) else null,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onTrackingResolutionChanged(res) }
                            .testTag("tracking_res_${res.name}")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = res.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (res == TrackingResolution.HD_720P) "Default (Fast 30 FPS)" else "High Detail",
                                fontSize = 10.sp,
                                color = if (isSelected) Color(0xFFE0F2FE) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF1E293B))

            // 7. CAMERA LENS (ULTRA-WIDE + FRONT)
            Spacer(modifier = Modifier.height(12.dp))
            SettingSectionHeader(
                icon = Icons.Default.CropFree,
                title = "Camera Lens Selection",
                subtitle = "Switch between Ultra-Wide 0.5×, Main 1× Wide, or Front Camera for subject tracking"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackingCameraLens.values().forEach { lens ->
                    val isSelected = uiState.selectedLens == lens
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF7C3AED) else Color(0xFF1E293B)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onCameraLensChanged(lens) }
                            .testTag("lens_${lens.name}")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = lens.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (lens.isFront) "Selfie" else "Back",
                                fontSize = 10.sp,
                                color = if (isSelected) Color(0xFFDDD6FE) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF1E293B))

            // 8. SELECTABLE TRACKING FPS
            Spacer(modifier = Modifier.height(12.dp))
            SettingSectionHeader(
                icon = Icons.Default.Speed,
                title = "Tracking Processing Rate (FPS)",
                subtitle = "Selectable tracking frequency supported by device sensor and AI engine"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackingFpsOption.values().forEach { fpsOpt ->
                    val isSelected = uiState.selectedFpsOption == fpsOpt
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF2563EB) else Color(0xFF1E293B)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onFpsOptionChanged(fpsOpt) }
                            .testTag("tracking_fps_${fpsOpt.name}")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = fpsOpt.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (fpsOpt == TrackingFpsOption.FPS_30) "Efficient" else if (fpsOpt == TrackingFpsOption.FPS_60) "Smooth" else "Max Speed",
                                fontSize = 10.sp,
                                color = if (isSelected) Color(0xFFBFDBFE) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF1E293B))

            // 9. ADAPTIVE SUBJECT LEARNING
            Spacer(modifier = Modifier.height(12.dp))
            SettingSectionHeader(
                icon = Icons.Default.Tune,
                title = "Adaptive Subject Learning",
                subtitle = "Locally learns persistent appearance signatures of your tracked subjects to improve future reacquisition"
            )

            Surface(
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Learned Subject Profiles",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "${uiState.learnedSubjectsCount} persistent subject profile(s) stored",
                                fontSize = 11.sp,
                                color = Color(0xFF38BDF8)
                            )
                        }

                        Button(
                            onClick = onClearLearnedProfiles,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("clear_learned_profiles_btn")
                        ) {
                            Text("Reset Memory", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Negative Learning Filter: Strictly ignores static walls, background, and furniture to prevent false profile learning.\n• On-Device Privacy: Subject profiles are processed purely in local memory.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SettingSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun AspectChip(
    ratio: TrackingAspectRatio,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8)) else null,
        modifier = modifier
            .clickable { onClick() }
            .testTag("aspect_ratio_${ratio.name}")
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = ratio.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.White else Color(0xFFCBD5E1)
            )
        }
    }
}
