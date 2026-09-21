package com.example.camera.ui

import android.hardware.camera2.CameraCharacteristics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.camera.model.*
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Master Bottom Control Bar matching the reference UI design.
 * Structure:
 * 1. Floating Master Zoom Capsule directly over the viewfinder: [0.5] [(1x)] [2] [3] [5] [10]
 * 2. Solid Pure Black Bottom Panel:
 *    - Shutter row: [Gallery]  [Shutter Button]  [Flip Camera]
 *    - Mode carousel: [PHOTO ●]  [PORTRAIT]  [VIDEO]  [CINEMA]  [MORE]
 */
@Composable
fun BottomControlBar(
    cameraMode: CameraMode,
    currentZoom: Float = 1.0f,
    displayedLenses: List<LensInfo> = emptyList(),
    selectedLens: LensInfo? = null,
    onLensSelected: (LensInfo) -> Unit = {},
    onZoomChange: (Float) -> Unit = {},
    onZoomPresetTap: (Float) -> Unit = onZoomChange,
    capabilities: HardwareCapabilities = HardwareCapabilities(),
    onShowToast: (String) -> Unit = {},
    isRecordingVideo: Boolean,
    videoDurationSeconds: Int,
    isCapturing: Boolean,
    isManualProOpen: Boolean = false,
    lastCapturedMedia: CapturedMedia?,
    activeTimerCountdown: Int?,
    onModeSelected: (CameraMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipCameraClick: () -> Unit,
    onToggleProClick: () -> Unit = {},
    onGalleryClick: () -> Unit,
    onCinemaModeClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit = {},
    onTimerClick: () -> Unit = {},
    layoutConfig: ModeLayoutConfig = ModeLayoutConfig(),
    modifier: Modifier = Modifier
) {
    val accentColor = layoutConfig.getComposeAccentColor()
    val fontFamily = layoutConfig.modeFontFamily.toComposeFontFamily()
    val customTextColor = layoutConfig.getComposeTextColor()
    val customIconColor = layoutConfig.getComposeIconColor()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("master_bottom_control_bar"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Floating Master Zoom Capsule (0.5, 1x, 2, 3, 5, 10)
        if (layoutConfig.showZoomCapsule) {
            MasterZoomCapsule(
                currentZoom = currentZoom,
                displayedLenses = displayedLenses,
                selectedLens = selectedLens,
                capabilities = capabilities,
                onShowToast = onShowToast,
                onLensSelected = onLensSelected,
                onZoomChange = onZoomChange,
                onZoomPresetTap = onZoomPresetTap,
                modifier = Modifier
                    .offset(y = layoutConfig.zoomCapsuleVerticalOffsetDp.dp)
                    .scale(layoutConfig.zoomCapsuleScale)
                    .padding(bottom = 14.dp)
                    .testTag("master_zoom_capsule")
            )
        }

        // 2. Frosted Glass Bottom Control Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xD90E1017),
                            Color(0xF5080A0E)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 14.dp, bottom = layoutConfig.bottomPaddingDp.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Active Countdown Badge (Timer)
                AnimatedVisibility(visible = activeTimerCountdown != null) {
                    activeTimerCountdown?.let { count ->
                        Box(
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(accentColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = count.toString(),
                                color = Color.Black,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                // Active Video Recording Timer Badge
                AnimatedVisibility(visible = isRecordingVideo) {
                    val minutes = videoDurationSeconds / 60
                    val seconds = videoDurationSeconds % 60
                    val timeFormatted = "%02d:%02d".format(minutes, seconds)

                    val infiniteTransition = rememberInfiniteTransition(label = "recDotPulse")
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(500, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dotAlpha"
                    )

                    Row(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Red.copy(alpha = 0.25f))
                            .border(1.dp, Color.Red, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.Red.copy(alpha = dotAlpha))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "REC $timeFormatted",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Shutter & Action Buttons Row Composable
                val shutterRowContent = @Composable {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Gallery Thumbnail
                        if (layoutConfig.showGalleryButton) {
                            Box(
                                modifier = Modifier
                                    .size(layoutConfig.galleryThumbSizeDp.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x22FFFFFF))
                                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                    .clickable { onGalleryClick() }
                                    .testTag("gallery_thumbnail_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (lastCapturedMedia != null) {
                                    AsyncImage(
                                        model = lastCapturedMedia.uri,
                                        contentDescription = "Last captured media",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.PhotoLibrary,
                                        contentDescription = "Gallery",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.size(layoutConfig.galleryThumbSizeDp.dp))
                        }

                        // Center: Customizable Shutter Button
                        val shutterSize = layoutConfig.shutterSizeDp.dp
                        Box(
                            modifier = Modifier
                                .offset(x = layoutConfig.shutterHorizontalOffsetDp.dp)
                                .size(shutterSize)
                                .clip(CircleShape)
                                .then(
                                    when (layoutConfig.shutterStyle) {
                                        ShutterStyle.CLASSIC_WHITE -> Modifier.border(3.5.dp, Color.White, CircleShape)
                                        ShutterStyle.APPLE_DOT -> Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                                        ShutterStyle.SAMSUNG_CAPSULE -> Modifier.border(4.dp, Color.White, CircleShape).padding(4.dp)
                                        ShutterStyle.VIVO_GIMBAL -> Modifier.border(3.dp, accentColor, CircleShape)
                                        ShutterStyle.MINIMAL_ACCENT -> Modifier
                                        ShutterStyle.PIXEL_SOLID -> Modifier.border(4.dp, Color.White, CircleShape).padding(3.dp)
                                        ShutterStyle.LEICA_RED_DOT -> Modifier.border(3.dp, Color(0xFFE0E0E0), CircleShape).padding(3.dp)
                                        ShutterStyle.CYBER_HOLO -> Modifier.border(2.5.dp, Color(0xFF00E5FF), CircleShape).padding(3.dp)
                                        ShutterStyle.DSLR_KNURLED -> Modifier.border(4.dp, Color(0xFF555555), CircleShape).padding(2.dp)
                                    }
                                )
                                .clickable { onShutterClick() }
                                .testTag("main_shutter_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            val buttonScale by animateFloatAsState(
                                targetValue = if (isCapturing) 0.85f else 1.0f,
                                label = "shutterScale"
                            )

                            when (cameraMode) {
                                CameraMode.PHOTO, CameraMode.MORE, CameraMode.AI_SUBJECT_TRACKING, CameraMode.HUMAN_VISION -> {
                                    val shutterColor = when (layoutConfig.shutterStyle) {
                                        ShutterStyle.MINIMAL_ACCENT -> accentColor
                                        ShutterStyle.LEICA_RED_DOT -> Color(0xFFE53935)
                                        ShutterStyle.CYBER_HOLO -> Color(0xFF00E5FF)
                                        ShutterStyle.DSLR_KNURLED -> Color(0xFFDDDDDD)
                                        else -> Color.White
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(shutterSize * 0.8f)
                                            .scale(buttonScale)
                                            .clip(CircleShape)
                                            .background(shutterColor)
                                    )
                                }
                                CameraMode.NIGHT -> {
                                    Box(
                                        modifier = Modifier
                                            .size(shutterSize * 0.8f)
                                            .scale(buttonScale)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                            .border(3.dp, Color(0xFFFFB300), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(shutterSize * 0.25f)
                                                .clip(CircleShape)
                                                .background(Color(0xFFFFB300))
                                        )
                                    }
                                }
                                CameraMode.PORTRAIT -> {
                                    Box(
                                        modifier = Modifier
                                            .size(shutterSize * 0.8f)
                                            .scale(buttonScale)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                            .border(2.5.dp, accentColor, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(shutterSize * 0.2f)
                                                .clip(CircleShape)
                                                .background(accentColor)
                                        )
                                    }
                                }
                                CameraMode.VIDEO, CameraMode.CINEMA, CameraMode.DOLLY_ZOOM -> {
                                    if (isRecordingVideo) {
                                        Box(
                                            modifier = Modifier
                                                .size(shutterSize * 0.38f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFE53935))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(shutterSize * 0.8f)
                                                .clip(CircleShape)
                                                .background(Color(0xFFE53935))
                                        )
                                    }
                                }
                            }
                        }

                        // Right: Camera Switcher / Flip Button
                        if (layoutConfig.showFlipButton) {
                            Box(
                                modifier = Modifier
                                    .size(layoutConfig.flipButtonSizeDp.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xB21E1E24))
                                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                                    .clickable { onFlipCameraClick() }
                                    .testTag("flip_camera_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FlipCameraAndroid,
                                    contentDescription = "Flip Camera",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.size(layoutConfig.flipButtonSizeDp.dp))
                        }
                    }
                }

                // Mode Carousel Composable
                val modeCarouselContent = @Composable {
                    if (!isRecordingVideo) {
                        val modeScrollState = rememberScrollState()
                        // User directive: only Photo, Portrait, and Video in the main bar; all other modes in More Modes
                        val modesToDisplay = remember(layoutConfig.visibleModes) {
                            val filtered = layoutConfig.visibleModes.filter {
                                it == CameraMode.PHOTO || it == CameraMode.PORTRAIT || it == CameraMode.VIDEO || it == CameraMode.MORE
                            }
                            if (filtered.isEmpty()) {
                                listOf(CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.VIDEO, CameraMode.MORE)
                            } else {
                                filtered
                            }
                        }

                        val isMoreModeActive = (cameraMode != CameraMode.PHOTO && cameraMode != CameraMode.PORTRAIT && cameraMode != CameraMode.VIDEO)

                        LaunchedEffect(cameraMode) {
                            val targetMode = if (isMoreModeActive) CameraMode.MORE else cameraMode
                            val index = modesToDisplay.indexOf(targetMode)
                            if (index >= 0) {
                                val itemEstimatedWidthPx = 180
                                val targetScroll = (index * itemEstimatedWidthPx - 140).coerceAtLeast(0)
                                modeScrollState.animateScrollTo(targetScroll)
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(modeScrollState)
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            modesToDisplay.forEach { mode ->
                                val isSelected = if (mode == CameraMode.MORE) isMoreModeActive else (cameraMode == mode)
                                val targetTextColor = if (isSelected) {
                                    if (layoutConfig.modeSelectorStyle == ModeSelectorStyle.MONO_TICKER) Color(0xFFE53935)
                                    else if (layoutConfig.modeSelectorStyle == ModeSelectorStyle.CYBER_GLOW) Color(0xFF00E5FF)
                                    else customTextColor
                                } else {
                                    customTextColor.copy(alpha = 0.65f)
                                }
                                val textColor by animateColorAsState(
                                    targetTextColor,
                                    label = "modeTextColor"
                                )

                                val rawName = if (mode == CameraMode.MORE && isMoreModeActive && cameraMode != CameraMode.MORE) {
                                    cameraMode.name
                                } else {
                                    mode.name
                                }
                                val displayText = layoutConfig.formatModeText(rawName)
                                val modeFontWeight = if (isSelected) layoutConfig.fontWeightOption.weight else FontWeight.Normal
                                val modeLetterSpacing = layoutConfig.letterSpacingSp.sp

                                Column(
                                    modifier = Modifier
                                        .clickable {
                                            if (mode == CameraMode.MORE) {
                                                onModeSelected(CameraMode.MORE)
                                            } else {
                                                onModeSelected(mode)
                                            }
                                        }
                                        .padding(vertical = 4.dp, horizontal = 6.dp)
                                        .testTag("mode_${mode.name.lowercase()}"),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    when (layoutConfig.modeSelectorStyle) {
                                        ModeSelectorStyle.CLASSIC_DOT -> {
                                            Text(
                                                text = displayText,
                                                color = textColor,
                                                fontSize = layoutConfig.modeTextSizeSp.sp,
                                                fontWeight = modeFontWeight,
                                                fontFamily = fontFamily,
                                                letterSpacing = modeLetterSpacing,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .clip(CircleShape)
                                                        .background(accentColor)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(5.dp))
                                            }
                                        }
                                        ModeSelectorStyle.CAPSULE_PILL -> {
                                            Surface(
                                                shape = CircleShape,
                                                color = if (isSelected) accentColor else Color.Transparent
                                            ) {
                                                Text(
                                                    text = displayText,
                                                    color = if (isSelected) Color.Black else customTextColor.copy(alpha = 0.65f),
                                                    fontSize = layoutConfig.modeTextSizeSp.sp,
                                                    fontWeight = modeFontWeight,
                                                    fontFamily = fontFamily,
                                                    letterSpacing = modeLetterSpacing,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                        ModeSelectorStyle.UNDERLINE -> {
                                            Text(
                                                text = displayText,
                                                color = if (isSelected) customTextColor else customTextColor.copy(alpha = 0.65f),
                                                fontSize = layoutConfig.modeTextSizeSp.sp,
                                                fontWeight = modeFontWeight,
                                                fontFamily = fontFamily,
                                                letterSpacing = modeLetterSpacing,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                            Spacer(modifier = Modifier.height(3.dp))
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(22.dp)
                                                        .height(2.5.dp)
                                                        .clip(CircleShape)
                                                        .background(accentColor)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.height(2.5.dp))
                                            }
                                        }
                                        ModeSelectorStyle.MINIMAL_TEXT -> {
                                            Text(
                                                text = displayText,
                                                color = textColor,
                                                fontSize = layoutConfig.modeTextSizeSp.sp,
                                                fontWeight = modeFontWeight,
                                                fontFamily = fontFamily,
                                                letterSpacing = modeLetterSpacing,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        ModeSelectorStyle.PIXEL_PILL -> {
                                            Surface(
                                                shape = RoundedCornerShape(20.dp),
                                                color = if (isSelected) Color(0x3DFFFFFF) else Color.Transparent,
                                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)) else null
                                            ) {
                                                Text(
                                                    text = displayText,
                                                    color = if (isSelected) customTextColor else customTextColor.copy(alpha = 0.65f),
                                                    fontSize = layoutConfig.modeTextSizeSp.sp,
                                                    fontWeight = modeFontWeight,
                                                    fontFamily = fontFamily,
                                                    letterSpacing = modeLetterSpacing,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                        ModeSelectorStyle.MONO_TICKER -> {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = displayText,
                                                    color = if (isSelected) Color(0xFFE53935) else Color.White.copy(alpha = 0.6f),
                                                    fontSize = layoutConfig.modeTextSizeSp.sp,
                                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    letterSpacing = 1.5.sp
                                                )
                                                if (isSelected) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Box(modifier = Modifier.width(16.dp).height(2.dp).background(Color(0xFFE53935)))
                                                }
                                            }
                                        }
                                        ModeSelectorStyle.CYBER_GLOW -> {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isSelected) Color(0x3300E5FF) else Color.Transparent,
                                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF)) else null
                                            ) {
                                                Text(
                                                    text = displayText,
                                                    color = if (isSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.7f),
                                                    fontSize = layoutConfig.modeTextSizeSp.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    letterSpacing = 1.sp,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                        ModeSelectorStyle.DSLR_DIAL -> {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isSelected) Color(0xFF262C36) else Color.Transparent,
                                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB300)) else null
                                            ) {
                                                Text(
                                                    text = displayText,
                                                    color = if (isSelected) Color(0xFFFFB300) else Color.Gray,
                                                    fontSize = layoutConfig.modeTextSizeSp.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Render in accordance with modeSelectorPosition
                if (layoutConfig.modeSelectorPosition == ModeSelectorPosition.ABOVE_SHUTTER) {
                    modeCarouselContent()
                    Spacer(modifier = Modifier.height(14.dp))
                    shutterRowContent()
                } else {
                    shutterRowContent()
                    Spacer(modifier = Modifier.height(18.dp))
                    modeCarouselContent()
                }

                // Auxiliary Quick-Access Dock for Pixel Style (matches reference screenshot)
                if (layoutConfig.modeSelectorStyle == ModeSelectorStyle.PIXEL_PILL && !isRecordingVideo) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp, end = 36.dp, top = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Settings button
                        IconButton(
                            onClick = onSettingsClick,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0x33FFFFFF))
                                .testTag("pixel_dock_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Settings",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Center: Photo / Video Quick-Switch Pill
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = Color(0x33FFFFFF),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            modifier = Modifier.testTag("pixel_dock_mode_switcher")
                        ) {
                            Row(
                                modifier = Modifier.padding(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isPhotoMode = (cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT || cameraMode == CameraMode.HUMAN_VISION)
                                val isVideoMode = (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.CINEMA)

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isPhotoMode) Color.White else Color.Transparent)
                                        .clickable { onModeSelected(CameraMode.PHOTO) }
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CameraAlt,
                                        contentDescription = "Photo",
                                        tint = if (isPhotoMode) Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isVideoMode) Color.White else Color.Transparent)
                                        .clickable { onModeSelected(CameraMode.VIDEO) }
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Videocam,
                                        contentDescription = "Video",
                                        tint = if (isVideoMode) Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Right: Quick Timer button
                        IconButton(
                            onClick = onTimerClick,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0x33FFFFFF))
                                .testTag("pixel_dock_timer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Timer,
                                contentDescription = "Timer",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Master Zoom Capsule matching the reference screenshot:
 * Dark frosted pill floating above the bottom controls, featuring:
 * 0.5   [1x] (with golden yellow circle border)   2   3   5   10
 * Supports direct tapping and horizontal drag scrubbing for fine zoom control.
 */
@Composable
fun MasterZoomCapsule(
    currentZoom: Float,
    displayedLenses: List<LensInfo>,
    selectedLens: LensInfo?,
    capabilities: HardwareCapabilities = HardwareCapabilities(),
    onShowToast: (String) -> Unit = {},
    onLensSelected: (LensInfo) -> Unit,
    onZoomChange: (Float) -> Unit,
    onZoomPresetTap: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isFrontCamera = selectedLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
    val hasRealUltraWide = remember(displayedLenses) {
        displayedLenses.any { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
    }
    val presets = remember(isFrontCamera, hasRealUltraWide) {
        if (isFrontCamera) {
            if (hasRealUltraWide) listOf(0.5f, 1.0f) else listOf(1.0f)
        } else {
            if (hasRealUltraWide) listOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f, 10.0f) else listOf(1.0f, 2.0f, 3.0f, 5.0f, 10.0f)
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    var isSliderOpen by remember { mutableStateOf(false) }

    if (isSliderOpen) {
        Column(
            modifier = modifier
                .widthIn(min = 280.dp, max = 340.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF2121316))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("smooth_zoom_slider_container"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: Live zoom badge + lens descriptor + close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF26210A))
                            .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "%.1fx".format(currentZoom),
                            color = Color(0xFFFFD54F),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val lensName = when {
                        currentZoom < 0.95f -> "Ultra-Wide"
                        currentZoom in 0.95f..1.8f -> "Wide (1x)"
                        currentZoom in 1.8f..2.5f -> "2x Tele"
                        currentZoom in 2.5f..4.5f -> "3x Tele"
                        currentZoom in 4.5f..8.0f -> "5x Tele"
                        else -> "10x SuperZoom"
                    }
                    Text(
                        text = lensName,
                        color = Color(0xFFE5E7EB),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                IconButton(
                    onClick = { isSliderOpen = false },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close Zoom Slider",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Continuous 0.5x - 10.0x Slider
            val minSliderZoom = if (hasRealUltraWide) 0.5f else 1.0f
            Slider(
                value = currentZoom.coerceIn(minSliderZoom, 10.0f),
                onValueChange = { newVal ->
                    val rounded = (newVal * 10f).roundToInt() / 10f
                    onZoomChange(rounded)

                    // Switch available physical lenses smoothly without freezing
                    if (rounded <= 0.6f && hasRealUltraWide) {
                        val ultraLens = displayedLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                        if (ultraLens != null && selectedLens?.id != ultraLens.id) {
                            onLensSelected(ultraLens)
                        }
                    } else if (rounded in 0.95f..1.1f) {
                        val wideLens = displayedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.WIDE && !it.isZoomPreset }
                            ?: displayedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                        if (wideLens != null && selectedLens?.id != wideLens.id) {
                            onLensSelected(wideLens)
                        }
                    } else if (rounded in 1.9f..2.1f) {
                        val tele2x = displayedLenses.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical }
                        if (tele2x != null && selectedLens?.id != tele2x.id) {
                            onLensSelected(tele2x)
                        }
                    } else if (rounded in 2.9f..3.1f) {
                        val tele3x = displayedLenses.firstOrNull { it.lensType == LensType.TELEPHOTO_3X && it.isPhysical }
                        if (tele3x != null && selectedLens?.id != tele3x.id) {
                            onLensSelected(tele3x)
                        }
                    }
                },
                valueRange = minSliderZoom..10.0f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFFD54F),
                    activeTrackColor = Color(0xFFFFD54F),
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .testTag("zoom_smooth_slider")
            )

            // Preset Quick-Jump Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                presets.forEach { preset ->
                    val isMatch = (currentZoom - preset).absoluteValue < 0.25f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isMatch) Color(0xFF26210A) else Color(0x2EFFFFFF))
                            .border(
                                width = if (isMatch) 1.dp else 0.dp,
                                color = if (isMatch) Color(0xFFFFD54F) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                onZoomPresetTap(preset)
                                if (preset == 0.5f) {
                                    val ultraLens = displayedLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                                    if (ultraLens != null) onLensSelected(ultraLens)
                                } else if (preset == 1.0f) {
                                    val mainLens = displayedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.WIDE && !it.isZoomPreset }
                                        ?: displayedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                                    if (mainLens != null) onLensSelected(mainLens)
                                } else if (preset in 2.0f..3.0f) {
                                    val tele = displayedLenses.firstOrNull { (it.lensType == LensType.TELEPHOTO || it.lensType == LensType.TELEPHOTO_3X) && it.isPhysical }
                                    if (tele != null) onLensSelected(tele)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (preset == 0.5f) ".5" else if (preset == 1.0f) "1x" else "${preset.toInt()}",
                            color = if (isMatch) Color(0xFFFFD54F) else Color.White,
                            fontSize = 11.sp,
                            fontWeight = if (isMatch) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    } else {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xD9141418))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
            .pointerInput(currentZoom, hasRealUltraWide) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val sensitivity = 0.022f
                        val minAllowableZoom = if (hasRealUltraWide) 0.5f else 1.0f
                        val newZoom = (currentZoom + dragAmount * sensitivity).coerceIn(minAllowableZoom, 10.0f)
                        val rounded = (newZoom * 10).roundToInt() / 10f
                        onZoomChange(rounded)
                    }
                )
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presets.forEach { preset ->
                val isClosest = presets.minByOrNull { (it - currentZoom).absoluteValue } == preset
                val isExactMatch = (currentZoom - preset).absoluteValue < 0.2f
                val isActive = isExactMatch || (isClosest && !isDragging)

                val label = when (preset) {
                    0.5f -> "0.5"
                    1.0f -> "1x"
                    2.0f -> "2"
                    3.0f -> "3"
                    5.0f -> "5"
                    10.0f -> "10"
                    else -> "${preset}x"
                }

                val displayText = if (isActive && (currentZoom - preset).absoluteValue >= 0.25f) {
                    "%.1fx".format(currentZoom)
                } else {
                    label
                }

                // Physical lens mapping (Real hardware lenses only)
                val targetLens = when (preset) {
                    0.5f -> displayedLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                    1.0f -> displayedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.WIDE && it.isPhysical && !it.isZoomPreset }
                        ?: displayedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.WIDE && !it.isZoomPreset }
                        ?: displayedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                    2.0f -> displayedLenses.firstOrNull { (it.lensType == LensType.TELEPHOTO || it.lensType == LensType.TELEPHOTO_3X) && it.isPhysical }
                        ?: displayedLenses.firstOrNull { it.lensType == LensType.TELEPHOTO || it.lensType == LensType.TELEPHOTO_3X }
                    3.0f -> displayedLenses.firstOrNull { it.lensType == LensType.TELEPHOTO_3X && it.isPhysical }
                        ?: displayedLenses.firstOrNull { it.lensType == LensType.TELEPHOTO_3X }
                        ?: displayedLenses.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical }
                    5.0f -> displayedLenses.firstOrNull { it.isPhysical && (it.baseZoomRatio in 4.5f..5.5f || it.equivalent35mmFocalMm in 110f..140f) }
                    10.0f -> displayedLenses.firstOrNull { it.isPhysical && (it.baseZoomRatio in 9.0f..11.0f || it.equivalent35mmFocalMm >= 220f) }
                    else -> null
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF26210A) else Color.Transparent)
                        .border(
                            width = if (isActive) 1.5.dp else 0.dp,
                            color = if (isActive) Color(0xFFFFD54F) else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable {
                            if (isActive) {
                                isSliderOpen = true
                            } else if (targetLens != null) {
                                onLensSelected(targetLens)
                            } else if (preset == 1.0f) {
                                val mainLens = displayedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.WIDE && !it.isZoomPreset }
                                    ?: displayedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                                if (mainLens != null && selectedLens?.id != mainLens.id) {
                                    onLensSelected(mainLens)
                                } else {
                                    onZoomPresetTap(1.0f)
                                }
                            } else if (preset == 0.5f) {
                                val ultraLens = displayedLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                                if (ultraLens != null) {
                                    onLensSelected(ultraLens)
                                } else {
                                    onShowToast("Real Ultra-Wide lens is not available on this device")
                                }
                            } else {
                                if (preset <= capabilities.maxZoom) {
                                    onZoomPresetTap(preset)
                                } else {
                                    onShowToast("${preset.toInt()}x zoom is not supported on this device")
                                }
                            }
                        }
                        .testTag("zoom_preset_${(preset * 10).roundToInt()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayText,
                        color = if (isActive) Color(0xFFFFD54F) else Color.White,
                        fontSize = if (displayText.length >= 4) 10.5.sp else 12.5.sp,
                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
}

