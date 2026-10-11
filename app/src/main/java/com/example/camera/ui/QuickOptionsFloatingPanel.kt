package com.example.camera.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.*
import com.example.camera.ui.components.FloatingSelectorDefaults
import com.example.camera.ui.components.FloatingWindowCard

/**
 * Compact, rounded, dark translucent floating options panel that expands downward
 * over the viewfinder when tapping the 6-dot menu icon in the top control bar.
 * Matches the reference design with circular icons, clear status labels, spacing, and layout:
 * - Photo mode: Timer, Hi-Res (50MP), RAW, Motion Photo, Grid, Flash, Filters, Settings
 * - Video mode: Dolly Zoom, Horizontal Lock, Stabilization, Resolution/FPS, Torch, Audio, Video Adjustments, Settings
 * - Cinema mode: LOG, LUT, Aspect Ratio, Resolution, EV, Tuning, Audio, Settings
 */
@Composable
fun QuickOptionsFloatingPanel(
    isOpen: Boolean,
    cameraMode: CameraMode,
    // Photo properties
    flashMode: FlashMode,
    timerMode: TimerMode,
    gridType: GridType,
    photoMegapixelMode: PhotoMegapixelMode,
    isRawEnabled: Boolean,
    isMotionPhotoEnabled: Boolean,
    activePhotoFilter: PhotoFilter,
    isPipelineActive: Boolean,
    // Video properties
    videoResolution: CameraResolution?,
    videoFps: Int,
    isAudioEnabled: Boolean,
    isVideoStabilizationEnabled: Boolean,
    isUltraStabilizationEnabled: Boolean,
    isHorizonLockEnabled: Boolean,
    isDollyZoomActive: Boolean,
    hasActiveVideoAdjustments: Boolean,
    // Cinema properties
    cinemaConfig: CinemaConfig = CinemaConfig(),
    isEvOpen: Boolean = false,
    exposureCompensation: Int = 0,
    evStepSize: Float = 0.333f,
    // Actions
    onFlashClick: () -> Unit,
    onTimerClick: () -> Unit,
    onGridClick: () -> Unit,
    onToggleMegapixelMode: () -> Unit,
    onRawClick: () -> Unit,
    onMotionPhotoClick: () -> Unit,
    onPhotoFilterClick: () -> Unit,
    onToggleDollyZoom: () -> Unit,
    onToggleHorizonLock: () -> Unit,
    onStabilizationClick: () -> Unit,
    onVideoSettingsClick: () -> Unit,
    onAudioToggle: () -> Unit,
    onVideoAdjustmentsClick: () -> Unit,
    onCinemaResolutionClick: () -> Unit = {},
    onCinemaAspectRatioClick: () -> Unit = {},
    onCinemaSettingsClick: () -> Unit = {},
    onLogClick: () -> Unit = {},
    onLutClick: () -> Unit = {},
    onEvClick: () -> Unit = {},
    onSettingsClick: () -> Unit,
    onDismiss: () -> Unit,
    accentColor: Color = Color(0xFFFFD54F),
    modifier: Modifier = Modifier
) {
    val resLabel = when {
        videoResolution?.width == 3840 || videoResolution?.height == 3840 -> "4K"
        videoResolution?.width == 7680 || videoResolution?.height == 7680 -> "8K"
        videoResolution?.width == 1920 || videoResolution?.height == 1920 -> "1080P"
        videoResolution?.width == 1280 || videoResolution?.height == 1280 -> "720P"
        else -> "4K"
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(animationSpec = tween(180)) + expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = tween(220)
        ),
        exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = tween(180)
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            FloatingWindowCard(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .testTag("quick_options_floating_panel"),
                shape = FloatingSelectorDefaults.CardShape
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (cameraMode) {
                        CameraMode.VIDEO -> {
                            // Row 1: Dolly Zoom, Horizontal Lock, Stabilization, Resolution
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                QuickOptionCircleButton(
                                    icon = Icons.Filled.CenterFocusStrong,
                                    label = "Dolly Zoom",
                                    statusText = if (isDollyZoomActive) "ON" else "OFF",
                                    isActive = isDollyZoomActive,
                                    activeColor = Color(0xFF00E5FF),
                                    onClick = onToggleDollyZoom,
                                    testTag = "panel_dolly_zoom_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Filled.ScreenLockRotation,
                                    label = "Horizon Lock",
                                    statusText = if (isHorizonLockEnabled) "ON" else "OFF",
                                    isActive = isHorizonLockEnabled,
                                    activeColor = accentColor,
                                    onClick = onToggleHorizonLock,
                                    testTag = "panel_horizon_lock_button",
                                    modifier = Modifier.weight(1f)
                                )

                                val isSteadyActive = isUltraStabilizationEnabled || isVideoStabilizationEnabled
                                val steadyLabel = when {
                                    isUltraStabilizationEnabled -> "ULTRA"
                                    isVideoStabilizationEnabled -> "EIS"
                                    else -> "OFF"
                                }
                                QuickOptionCircleButton(
                                    icon = if (isUltraStabilizationEnabled) Icons.Filled.DirectionsRun else Icons.Outlined.Shield,
                                    label = "Steady",
                                    statusText = steadyLabel,
                                    isActive = isSteadyActive,
                                    activeColor = accentColor,
                                    onClick = onStabilizationClick,
                                    testTag = "panel_stabilization_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.VideoSettings,
                                    label = "Quality",
                                    statusText = "$resLabel · $videoFps",
                                    isActive = true,
                                    activeColor = accentColor,
                                    onClick = onVideoSettingsClick,
                                    testTag = "panel_resolution_button",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Row 2: Torch, Audio, Video Adjustments, Settings
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val isTorchOn = flashMode != FlashMode.OFF
                                QuickOptionCircleButton(
                                    icon = if (isTorchOn) Icons.Filled.FlashOn else Icons.Outlined.FlashOff,
                                    label = "Torch",
                                    statusText = if (isTorchOn) "ON" else "OFF",
                                    isActive = isTorchOn,
                                    activeColor = accentColor,
                                    onClick = onFlashClick,
                                    testTag = "panel_torch_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = if (isAudioEnabled) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                                    label = "Audio",
                                    statusText = if (isAudioEnabled) "ON" else "MUTE",
                                    isActive = !isAudioEnabled,
                                    activeColor = Color(0xFFFF6B6B),
                                    onClick = onAudioToggle,
                                    testTag = "panel_audio_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.Tune,
                                    label = "Tuning",
                                    statusText = if (hasActiveVideoAdjustments) "ACTIVE" else "STD",
                                    isActive = hasActiveVideoAdjustments,
                                    activeColor = accentColor,
                                    onClick = onVideoAdjustmentsClick,
                                    testTag = "panel_video_tuning_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.Settings,
                                    label = "Settings",
                                    statusText = "More",
                                    isActive = false,
                                    activeColor = accentColor,
                                    onClick = onSettingsClick,
                                    testTag = "panel_settings_button",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        CameraMode.CINEMA -> {
                            val cinemaRes = when {
                                cinemaConfig.selectedResolution?.width == 7680 || cinemaConfig.selectedResolution?.height == 7680 -> "8K"
                                cinemaConfig.selectedResolution?.width == 3840 || cinemaConfig.selectedResolution?.height == 3840 -> "4K"
                                cinemaConfig.selectedResolution?.width == 1920 || cinemaConfig.selectedResolution?.height == 1920 -> "1080P"
                                else -> "4K"
                            }
                            val isLogActive = cinemaConfig.colorProfile != CinemaColorProfile.NATIVE || cinemaConfig.logBitDepth != LogBitDepth.OFF
                            val isLutActive = !cinemaConfig.selectedLut.isOff

                            // Row 1: LOG, LUT, Aspect Ratio, Resolution
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                QuickOptionCircleButton(
                                    textBadge = "LOG",
                                    label = "Log Profile",
                                    statusText = if (isLogActive) "ON" else "OFF",
                                    isActive = isLogActive,
                                    activeColor = accentColor,
                                    onClick = onLogClick,
                                    testTag = "panel_cinema_log_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.Palette,
                                    label = "LUT",
                                    statusText = if (isLutActive) cinemaConfig.selectedLut.label else "OFF",
                                    isActive = isLutActive,
                                    activeColor = accentColor,
                                    onClick = onLutClick,
                                    testTag = "panel_cinema_lut_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.AspectRatio,
                                    label = "Aspect Ratio",
                                    statusText = cinemaConfig.aspectRatio.label,
                                    isActive = true,
                                    activeColor = accentColor,
                                    onClick = onCinemaAspectRatioClick,
                                    testTag = "panel_cinema_aspect_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.VideoSettings,
                                    label = "Resolution",
                                    statusText = cinemaRes,
                                    isActive = true,
                                    activeColor = accentColor,
                                    onClick = onCinemaResolutionClick,
                                    testTag = "panel_cinema_res_button",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Row 2: Exposure EV, Fine Tuning, Audio, Settings
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val evText = when {
                                    exposureCompensation == 0 -> "0.0"
                                    exposureCompensation > 0 -> String.format(java.util.Locale.US, "+%.1f", exposureCompensation * evStepSize)
                                    else -> String.format(java.util.Locale.US, "%.1f", exposureCompensation * evStepSize)
                                }
                                val isEvActive = exposureCompensation != 0 || isEvOpen
                                QuickOptionCircleButton(
                                    icon = Icons.Filled.Exposure,
                                    label = "Exposure EV",
                                    statusText = evText,
                                    isActive = isEvActive,
                                    activeColor = accentColor,
                                    onClick = onEvClick,
                                    testTag = "panel_cinema_ev_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.Tune,
                                    label = "Fine Tuning",
                                    statusText = "18 Params",
                                    isActive = true,
                                    activeColor = accentColor,
                                    onClick = onCinemaSettingsClick,
                                    testTag = "panel_cinema_tuning_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = if (isAudioEnabled) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                                    label = "Audio",
                                    statusText = if (isAudioEnabled) "ON" else "MUTE",
                                    isActive = !isAudioEnabled,
                                    activeColor = Color(0xFFFF6B6B),
                                    onClick = onAudioToggle,
                                    testTag = "panel_cinema_audio_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.Settings,
                                    label = "Settings",
                                    statusText = "More",
                                    isActive = false,
                                    activeColor = accentColor,
                                    onClick = onSettingsClick,
                                    testTag = "panel_cinema_settings_button",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        else -> {
                            // Photo Mode (and other capture modes)
                            // Row 1: Timer, Hi-Res (50MP), RAW, Motion Photo
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.Timer,
                                    label = "Timer",
                                    statusText = timerMode.label,
                                    isActive = timerMode != TimerMode.OFF,
                                    activeColor = accentColor,
                                    onClick = onTimerClick,
                                    testTag = "panel_timer_button",
                                    modifier = Modifier.weight(1f)
                                )

                                val is50M = photoMegapixelMode == PhotoMegapixelMode.M50
                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.HighQuality,
                                    label = "Hi-Res",
                                    statusText = photoMegapixelMode.label,
                                    isActive = is50M,
                                    activeColor = accentColor,
                                    onClick = onToggleMegapixelMode,
                                    testTag = "panel_megapixel_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.Camera,
                                    label = "RAW",
                                    statusText = if (isRawEnabled) "ON" else "OFF",
                                    isActive = isRawEnabled,
                                    activeColor = accentColor,
                                    onClick = onRawClick,
                                    testTag = "panel_raw_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = if (isMotionPhotoEnabled) Icons.Filled.MotionPhotosOn else Icons.Outlined.MotionPhotosOff,
                                    label = "Live Photo",
                                    statusText = if (isMotionPhotoEnabled) "ON" else "OFF",
                                    isActive = isMotionPhotoEnabled,
                                    activeColor = accentColor,
                                    onClick = onMotionPhotoClick,
                                    testTag = "panel_motion_photo_button",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Row 2: Grid, Flash, Filters, Settings
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.GridOn,
                                    label = "Grid",
                                    statusText = gridType.title,
                                    isActive = gridType != GridType.NONE,
                                    activeColor = accentColor,
                                    onClick = onGridClick,
                                    testTag = "panel_grid_button",
                                    modifier = Modifier.weight(1f)
                                )

                                val flashIcon = when (flashMode) {
                                    FlashMode.OFF -> Icons.Outlined.FlashOff
                                    FlashMode.AUTO -> Icons.Outlined.FlashAuto
                                    FlashMode.ON -> Icons.Outlined.FlashOn
                                    FlashMode.TORCH -> Icons.Outlined.Highlight
                                }
                                QuickOptionCircleButton(
                                    icon = flashIcon,
                                    label = "Flash",
                                    statusText = flashMode.title,
                                    isActive = flashMode != FlashMode.OFF,
                                    activeColor = accentColor,
                                    onClick = onFlashClick,
                                    testTag = "panel_flash_button",
                                    modifier = Modifier.weight(1f)
                                )

                                val isFilterActive = activePhotoFilter != PhotoFilter.ORIGINAL || isPipelineActive
                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.AutoAwesome,
                                    label = "Filters",
                                    statusText = if (isPipelineActive) "Pipeline" else activePhotoFilter.displayName,
                                    isActive = isFilterActive,
                                    activeColor = accentColor,
                                    onClick = onPhotoFilterClick,
                                    testTag = "panel_filters_button",
                                    modifier = Modifier.weight(1f)
                                )

                                QuickOptionCircleButton(
                                    icon = Icons.Outlined.Settings,
                                    label = "Settings",
                                    statusText = "More",
                                    isActive = false,
                                    activeColor = accentColor,
                                    onClick = onSettingsClick,
                                    testTag = "panel_settings_button",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Small subtle grab bar handle
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.22f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun QuickOptionCircleButton(
    icon: ImageVector? = null,
    textBadge: String? = null,
    label: String,
    statusText: String,
    isActive: Boolean,
    activeColor: Color = Color(0xFFFFD54F),
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 28.dp),
                onClick = onClick
            )
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) activeColor else Color(0x2EFFFFFF)
                )
                .border(
                    width = if (isActive) 1.5.dp else 1.dp,
                    color = if (isActive) activeColor else Color.White.copy(alpha = 0.18f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isActive) Color(0xFF14171E) else Color.White.copy(alpha = 0.95f),
                    modifier = Modifier.size(23.dp)
                )
            } else if (textBadge != null) {
                Text(
                    text = textBadge,
                    color = if (isActive) Color(0xFF14171E) else Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = label,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Text(
            text = statusText,
            color = if (isActive) activeColor else Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
