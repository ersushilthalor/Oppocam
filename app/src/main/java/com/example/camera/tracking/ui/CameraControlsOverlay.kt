package com.example.camera.tracking.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.camera.tracking.model.*
import com.example.camera.tracking.viewmodel.CameraTrackingUiState

/**
 * FIXED UI Overlay for AI Subject Tracking.
 * Stays strictly stationary while the camera image and crop window move beneath.
 */
@Composable
fun CameraControlsOverlay(
    uiState: CameraTrackingUiState,
    onBackToMainCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onCaptureModeChanged: (CameraCaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onUnlockTracking: () -> Unit,
    onOpenMediaReview: (CapturedMediaItem) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onToggleGimbal: (Boolean) -> Unit,
    onStartCinematicPan: (Float) -> Unit,
    onStopCinematicPan: () -> Unit,
    onCameraLensChanged: (TrackingCameraLens) -> Unit = {},
    onFpsOptionChanged: (TrackingFpsOption) -> Unit = {},
    onToggleTrackingResolution: () -> Unit = {},
    onDismissResolutionNotice: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("camera_controls_overlay"),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // TOP FIXED BAR
        TopFixedControlsBar(
            uiState = uiState,
            onBackToMainCamera = onBackToMainCamera,
            onFlipCamera = onFlipCamera,
            onOpenSettings = onOpenSettings,
            onToggleAspectRatio = onToggleAspectRatio,
            onToggleGimbal = onToggleGimbal,
            onStartCinematicPan = onStartCinematicPan,
            onStopCinematicPan = onStopCinematicPan,
            onCameraLensChanged = onCameraLensChanged,
            onFpsOptionChanged = onFpsOptionChanged,
            onToggleTrackingResolution = onToggleTrackingResolution,
            onDismissResolutionNotice = onDismissResolutionNotice
        )

        // CENTER FIXED STATUS HUD
        CenterTrackingHud(
            uiState = uiState,
            onUnlockTracking = onUnlockTracking,
            onStopCinematicPan = onStopCinematicPan
        )

        // BOTTOM FIXED CAMERA BAR
        BottomFixedControlsBar(
            uiState = uiState,
            onCaptureModeChanged = onCaptureModeChanged,
            onShutterClick = onShutterClick,
            onOpenMediaReview = onOpenMediaReview
        )
    }
}

@Composable
private fun TopFixedControlsBar(
    uiState: CameraTrackingUiState,
    onBackToMainCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onToggleGimbal: (Boolean) -> Unit,
    onStartCinematicPan: (Float) -> Unit,
    onStopCinematicPan: () -> Unit,
    onCameraLensChanged: (TrackingCameraLens) -> Unit = {},
    onFpsOptionChanged: (TrackingFpsOption) -> Unit = {},
    onToggleTrackingResolution: () -> Unit = {},
    onDismissResolutionNotice: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Upper row: Back button on left, clean icon buttons on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back Button to exit AI tracking mode back to regular camera
            IconButton(
                onClick = onBackToMainCamera,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0x44000000))
                    .testTag("back_to_camera_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Exit AI Tracking",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Clean icon actions: Settings Gear and Camera Flip
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0x44000000))
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Settings",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onFlipCamera,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0x44000000))
                        .testTag("flip_camera_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterTrackingHud(
    uiState: CameraTrackingUiState,
    onUnlockTracking: () -> Unit,
    onStopCinematicPan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Keep it extremely clean and minimal.
        // No text on the viewfinder. Only one small AI Subject Tracking icon.
        val isLocked = uiState.trackingStatus == TrackingStatus.TRACKING_LOCKED
        Surface(
            shape = CircleShape,
            color = if (isLocked) Color(0xDD0B192C) else Color(0x55000000),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = if (isLocked) Color(0xFF00E5FF).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .size(36.dp)
                .clickable {
                    if (isLocked) onUnlockTracking()
                }
                .testTag("ai_tracking_status_icon")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = "AI Subject Tracking",
                    tint = if (isLocked) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        // Recording indicator if active
        if (uiState.isRecording) {
            Spacer(modifier = Modifier.height(8.dp))
            val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "rec_alpha"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Red.copy(alpha = 0.9f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = pulseAlpha))
                )
                Spacer(modifier = Modifier.width(6.dp))
                val minutes = uiState.recordingDurationSec / 60
                val seconds = uiState.recordingDurationSec % 60
                Text(
                    text = String.format("REC %02d:%02d (3× CROP)", minutes, seconds),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun BottomFixedControlsBar(
    uiState: CameraTrackingUiState,
    onCaptureModeChanged: (CameraCaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onOpenMediaReview: (CapturedMediaItem) -> Unit,
    onCameraLensChanged: (TrackingCameraLens) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f))
                )
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Lens Quick Switcher (matching normal photo mode): 0.5x, 1x, Front
        Box(
            modifier = Modifier
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xD9141418))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                uiState.availableLenses.forEach { lens ->
                    val isSelected = uiState.selectedLens == lens
                    val label = when (lens) {
                        TrackingCameraLens.ULTRAWIDE -> "0.5"
                        TrackingCameraLens.WIDE -> "1x"
                        TrackingCameraLens.FRONT -> "Front"
                    }

                    Box(
                        modifier = Modifier
                            .size(width = if (lens == TrackingCameraLens.FRONT) 52.dp else 38.dp, height = 38.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Color(0xFF26210A) else Color.Transparent)
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) Color(0xFFFFD54F) else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { onCameraLensChanged(lens) }
                            .testTag("lens_quick_${lens.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color(0xFFFFD54F) else Color.White,
                            fontSize = if (lens == TrackingCameraLens.FRONT) 11.sp else 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Mode Selector: PHOTO / VIDEO
        Row(
            modifier = Modifier.padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PHOTO",
                fontSize = 14.sp,
                fontWeight = if (uiState.captureMode == CameraCaptureMode.PHOTO) FontWeight.Bold else FontWeight.Normal,
                color = if (uiState.captureMode == CameraCaptureMode.PHOTO) Color(0xFF00E5FF) else Color(0x88FFFFFF),
                modifier = Modifier
                    .clickable { onCaptureModeChanged(CameraCaptureMode.PHOTO) }
                    .testTag("mode_photo_button")
            )
            Text(
                text = "VIDEO",
                fontSize = 14.sp,
                fontWeight = if (uiState.captureMode == CameraCaptureMode.VIDEO) FontWeight.Bold else FontWeight.Normal,
                color = if (uiState.captureMode == CameraCaptureMode.VIDEO) Color(0xFF00E5FF) else Color(0x88FFFFFF),
                modifier = Modifier
                    .clickable { onCaptureModeChanged(CameraCaptureMode.VIDEO) }
                    .testTag("mode_video_button")
            )
        }

        // Action Row: Media Review Gallery, Shutter Button, Quick Info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Media Review Thumbnail Button
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.5.dp, Color(0x5500E5FF), RoundedCornerShape(12.dp))
                    .clickable {
                        uiState.lastCapturedMedia?.let { onOpenMediaReview(it) }
                    }
                    .testTag("media_gallery_button"),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.lastCapturedMedia != null) {
                    AsyncImage(
                        model = uiState.lastCapturedMedia.uri,
                        contentDescription = "Last captured media",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Inspect Output",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Center Shutter Button
            ShutterButton(
                captureMode = uiState.captureMode,
                isRecording = uiState.isRecording,
                onClick = onShutterClick
            )

            // Right side: Active Zoom Level readout
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(54.dp)
            ) {
                Text(
                    text = String.format("%.1f×", uiState.currentZoom),
                    color = if (uiState.currentZoom > 1.2f) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (uiState.currentZoom > 1.2f) "3× CROP" else "WIDE",
                    color = Color(0x66FFFFFF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(
    captureMode: CameraCaptureMode,
    isRecording: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(Color.Transparent)
            .clickable { onClick() }
            .testTag("shutter_button"),
        contentAlignment = Alignment.Center
    ) {
        if (captureMode == CameraCaptureMode.PHOTO) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.White)
            )
        } else {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Red)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color.Red)
                )
            }
        }
    }
}
