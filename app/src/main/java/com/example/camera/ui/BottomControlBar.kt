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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.camera.model.*
import com.example.camera.ui.components.HorizontalRulerZoomSlider
import com.example.camera.ui.components.HalfCircleZoomSlider
import com.example.camera.ui.components.HALF_CIRCLE_TRACK_WIDTH_MULTIPLIER
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Master Bottom Control Bar matching the reference UI design.
 * Structure:
 * 1. Floating Master Zoom Capsule directly over the viewfinder: [0.5] [(1x)] [2] [3]
 * 2. Solid Pure Black Bottom Panel:
 *    - Shutter row: [Gallery]  [Shutter Button]  [Flip Camera]
 *    - Mode carousel: [PHOTO ●]  [VIDEO]  [CINEMA]  [MORE]
 */
@Composable
fun BottomControlBar(
    cameraMode: CameraMode,
    currentZoom: Float = 1.0f,
    displayedLenses: List<LensInfo> = emptyList(),
    selectedLens: LensInfo? = null,
    activeZoomPresets: List<Float>? = null,
    onLensSelected: (LensInfo) -> Unit = {},
    onZoomChange: (Float) -> Unit = {},
    onZoomPresetTap: (Float) -> Unit = onZoomChange,
    capabilities: HardwareCapabilities = HardwareCapabilities(),
    onShowToast: (String) -> Unit = {},
    isRecordingVideo: Boolean,
    isRecordingPaused: Boolean = false,
    videoDurationSeconds: Int,
    isCapturing: Boolean,
    isManualProOpen: Boolean = false,
    lastCapturedMedia: CapturedMedia?,
    activeTimerCountdown: Int?,
    onModeSelected: (CameraMode) -> Unit,
    onShutterClick: () -> Unit,
    isUltraFastShutterEnabled: Boolean = false,
    ultraFastFps: Int = 15,
    onFastShutterSingleTap: () -> Unit = onShutterClick,
    onFastShutterHoldStart: () -> Unit = {},
    onFastShutterHoldEnd: () -> Unit = {},
    onFlipCameraClick: () -> Unit,
    onPauseResumeRecordingClick: () -> Unit = {},
    onToggleProClick: () -> Unit = {},
    onSetManualProOpen: (Boolean) -> Unit = {},
    onGalleryClick: () -> Unit,
    onCinemaModeClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit = {},
    onTimerClick: () -> Unit = {},
    selectedPhotoFilter: PhotoFilter = PhotoFilter.ORIGINAL,
    onPhotoFilterClick: () -> Unit = {},
    isEvOpen: Boolean = false,
    onEvClick: () -> Unit = {},
    exposureCompensation: Int = 0,
    evStepSize: Float = 0.333f,
    onShutterAreaHeightMeasured: (Dp) -> Unit = {},
    layoutConfig: ModeLayoutConfig = ModeLayoutConfig(),
    modifier: Modifier = Modifier,
    isOppoVideoTemplate: Boolean = false
) {
    val accentColor = layoutConfig.getComposeAccentColor()
    val fontFamily = layoutConfig.modeFontFamily.toComposeFontFamily()
    val customTextColor = layoutConfig.getComposeTextColor()
    val customIconColor = layoutConfig.getComposeIconColor()
    val density = LocalDensity.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("master_bottom_control_bar"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Floating Master Zoom Capsule (0.5, 1x, 2, 3, 5, 10) with Photo Filter icon positioned to its right
        val isFrontCamera = selectedLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
        if (layoutConfig.showZoomCapsule) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = layoutConfig.zoomCapsuleVerticalOffsetDp.dp)
                    .padding(horizontal = 0.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!isFrontCamera) {
                    MasterZoomCapsule(
                        currentZoom = currentZoom,
                        displayedLenses = displayedLenses,
                        selectedLens = selectedLens,
                        capabilities = capabilities,
                        customPresets = activeZoomPresets,
                        onShowToast = onShowToast,
                        onLensSelected = onLensSelected,
                        onZoomChange = onZoomChange,
                        onZoomPresetTap = onZoomPresetTap,
                        layoutConfig = layoutConfig,
                        modifier = Modifier
                            .scale(layoutConfig.zoomCapsuleScale)
                            .testTag("master_zoom_capsule")
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0x991E1E24))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                            .clickable { onFlipCameraClick() }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag("front_camera_capsule"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FlipCameraAndroid,
                                contentDescription = "Front Camera",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "1x Front",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }




            }
        }

        // 2. Frosted Glass Bottom Control Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val heightDp = with(density) { coordinates.size.height.toDp() }
                    onShutterAreaHeightMeasured(heightDp)
                }
                .then(
                    if (isOppoVideoTemplate) {
                        // The OPPO video preview continues behind the shutter row and ends
                        // before the mode carousel; transparent here reveals only that preview.
                        Modifier.background(Color.Transparent)
                    } else if (layoutConfig.modeSelectorStyle == ModeSelectorStyle.OPPO_PILL ||
                        layoutConfig.modeSelectorStyle == ModeSelectorStyle.IPHONE_LIQUID_GLASS ||
                        layoutConfig.useFocalLengthZoomLabels) {
                        Modifier.background(Color.Black)
                    } else {
                        Modifier.background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xD90E1017),
                                    Color(0xF5080A0E)
                                )
                            )
                        )
                    }
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

                    val badgeBg = if (isRecordingPaused) Color(0xFFFFB300).copy(alpha = 0.25f) else Color.Red.copy(alpha = 0.25f)
                    val badgeBorder = if (isRecordingPaused) Color(0xFFFFB300) else Color.Red
                    val badgeDotColor = if (isRecordingPaused) Color(0xFFFFB300) else Color.Red.copy(alpha = dotAlpha)
                    val labelText = if (isRecordingPaused) "PAUSED $timeFormatted" else "REC $timeFormatted"

                    Row(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(badgeBg)
                            .border(1.dp, badgeBorder, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(badgeDotColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = labelText,
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
                        // Left: Flip Camera button during active recording, or Gallery Thumbnail
                        if (isRecordingVideo) {
                            Box(
                                modifier = Modifier
                                    .size(layoutConfig.flipButtonSizeDp.dp.coerceAtLeast(50.dp))
                                    .clip(CircleShape)
                                    .background(Color(0xB21E1E24))
                                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
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
                        } else if (layoutConfig.showGalleryButton) {
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
                        val isFastShutterActive = isUltraFastShutterEnabled && cameraMode == CameraMode.PHOTO
                        val coroutineScope = rememberCoroutineScope()

                        Box(
                            modifier = Modifier
                                .offset(x = layoutConfig.shutterHorizontalOffsetDp.dp)
                                .size(shutterSize)
                                .clip(CircleShape)
                                .then(
                                    when (layoutConfig.shutterStyle) {
                                        ShutterStyle.CLASSIC_WHITE -> Modifier.border(3.5.dp, Color.White, CircleShape)
                                        ShutterStyle.APPLE_DOT -> Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                                        ShutterStyle.IPHONE_RING -> Modifier.border(4.dp, Color(0xFF8D8D8D), CircleShape).padding(3.dp)
                                        ShutterStyle.OPPO_ORANGE -> Modifier.border(5.dp, Color(0xFF5A3928), CircleShape).padding(3.dp)
                                        ShutterStyle.SAMSUNG_CAPSULE -> Modifier.border(4.dp, Color.White, CircleShape).padding(4.dp)
                                        ShutterStyle.VIVO_GIMBAL -> Modifier.border(3.5.dp, Color.White, CircleShape).padding(3.dp)
                                        ShutterStyle.MINIMAL_ACCENT -> Modifier
                                        ShutterStyle.PIXEL_SOLID -> Modifier.border(4.dp, Color.White, CircleShape).padding(3.dp)
                                        ShutterStyle.LEICA_RED_DOT -> Modifier.border(3.dp, Color(0xFFE0E0E0), CircleShape).padding(3.dp)
                                        ShutterStyle.CYBER_HOLO -> Modifier.border(2.5.dp, Color(0xFF00E5FF), CircleShape).padding(3.dp)
                                        ShutterStyle.DSLR_KNURLED -> Modifier.border(4.dp, Color(0xFF555555), CircleShape).padding(2.dp)
                                    }
                                )
                                .then(
                                    if (isFastShutterActive) {
                                        Modifier.pointerInput(isFastShutterActive) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val down = awaitFirstDown(requireUnconsumed = false)
                                                    var holdTriggered = false
                                                    val holdJob = coroutineScope.launch {
                                                        delay(180)
                                                        holdTriggered = true
                                                        onFastShutterHoldStart()
                                                    }

                                                    val upOrCancel = waitForUpOrCancellation()
                                                    holdJob.cancel()

                                                    if (upOrCancel != null) {
                                                        if (holdTriggered) {
                                                            onFastShutterHoldEnd()
                                                        } else {
                                                            onFastShutterSingleTap()
                                                        }
                                                    } else {
                                                        if (holdTriggered) {
                                                            onFastShutterHoldEnd()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier.clickable { onShutterClick() }
                                    }
                                )
                                .testTag("main_shutter_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            val buttonScale by animateFloatAsState(
                                targetValue = if (isCapturing) 0.85f else 1.0f,
                                label = "shutterScale"
                            )

                            when (cameraMode) {
                                CameraMode.PHOTO, CameraMode.MORE, CameraMode.AI_SUBJECT_TRACKING -> {
                                    val shutterColor = when (layoutConfig.shutterStyle) {
                                        ShutterStyle.MINIMAL_ACCENT -> accentColor
                                        ShutterStyle.OPPO_ORANGE -> Color(0xFFFF8A00)
                                        ShutterStyle.LEICA_RED_DOT -> Color(0xFFE53935)
                                        ShutterStyle.CYBER_HOLO -> Color(0xFF00E5FF)
                                        ShutterStyle.DSLR_KNURLED -> Color(0xFFDDDDDD)
                                        else -> Color.White
                                    }
                                    if (layoutConfig.shutterStyle == ShutterStyle.VIVO_GIMBAL) {
                                        // Vivo reference: white outer ring, pale-gold inner ring, dark open center.
                                        Box(
                                            modifier = Modifier
                                                .size(shutterSize * 0.72f)
                                                .scale(buttonScale)
                                                .clip(CircleShape)
                                                .background(Color(0xFF080808))
                                                .border(2.dp, Color(0xFFFFE9A8), CircleShape)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(shutterSize * 0.8f)
                                                .scale(buttonScale)
                                                .clip(CircleShape)
                                                .background(shutterColor)
                                        )
                                    }
                                }

                                CameraMode.VIDEO, CameraMode.CINEMA -> {
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

                        // Right: Camera Switcher / Flip Button OR Pause/Resume Button during active recording
                        if (isRecordingVideo) {
                            Box(
                                modifier = Modifier
                                    .size(layoutConfig.flipButtonSizeDp.dp.coerceAtLeast(50.dp))
                                    .clip(CircleShape)
                                    .background(if (isRecordingPaused) Color(0xFFFFB300) else Color(0xB21E1E24))
                                    .border(1.5.dp, if (isRecordingPaused) Color.White else Color.White.copy(alpha = 0.35f), CircleShape)
                                    .clickable { onPauseResumeRecordingClick() }
                                    .testTag("pause_resume_video_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isRecordingPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = if (isRecordingPaused) "Resume Recording" else "Pause Recording",
                                    tint = if (isRecordingPaused) Color.Black else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else if (layoutConfig.showFlipButton) {
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

                val isPixelVideoGroup = cameraMode == CameraMode.VIDEO ||
                        cameraMode == CameraMode.CINEMA ||
                        cameraMode == CameraMode.AI_SUBJECT_TRACKING
                val isPixelPhotoGroup = !isPixelVideoGroup

                // Mode Carousel Composable
                val modeCarouselContent = @Composable {
                    if (!isRecordingVideo) {
                        val modeScrollState = rememberScrollState()
                        if (layoutConfig.modeSelectorStyle == ModeSelectorStyle.PIXEL_PILL) {
                            // Pixel UI Template:
                            // First Icon (Photo Modes): Photo, Pro Manual
                            // Second Icon (Video and Special Modes): Video, Cinema, AI Subject Tracing
                            // "More" option is completely removed from the Pixel UI template.
                            data class PixelModeEntry(
                                val tag: String,
                                val label: String,
                                val isSelected: Boolean,
                                val onSelect: () -> Unit
                            )

                            val pixelEntries = listOf(
                                PixelModeEntry(
                                    tag = "mode_photo",
                                    label = "Photo",
                                    isSelected = cameraMode == CameraMode.PHOTO,
                                    onSelect = {
                                        onSetManualProOpen(false)
                                        onModeSelected(CameraMode.PHOTO)
                                    }
                                ),
                                PixelModeEntry(
                                    tag = "mode_video",
                                    label = "Video",
                                    isSelected = cameraMode == CameraMode.VIDEO,
                                    onSelect = {
                                        onSetManualProOpen(false)
                                        onModeSelected(CameraMode.VIDEO)
                                    }
                                ),
                                PixelModeEntry(
                                    tag = "mode_cinema",
                                    label = "Pro Video",
                                    isSelected = cameraMode == CameraMode.CINEMA,
                                    onSelect = {
                                        onSetManualProOpen(false)
                                        onModeSelected(CameraMode.CINEMA)
                                    }
                                ),
                                PixelModeEntry(
                                    tag = "mode_ai_subject_tracking",
                                    label = "AI Tracking",
                                    isSelected = cameraMode == CameraMode.AI_SUBJECT_TRACKING,
                                    onSelect = {
                                        onSetManualProOpen(false)
                                        onModeSelected(CameraMode.AI_SUBJECT_TRACKING)
                                    }
                                )
                            )

                            val selectedIndex = pixelEntries.indexOfFirst { it.isSelected }
                            LaunchedEffect(cameraMode, isManualProOpen, isPixelPhotoGroup) {
                                if (selectedIndex >= 0) {
                                    val itemEstimatedWidthPx = 180
                                    val targetScroll = (selectedIndex * itemEstimatedWidthPx - 140).coerceAtLeast(0)
                                    modeScrollState.animateScrollTo(targetScroll)
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(modeScrollState)
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                pixelEntries.forEach { entry ->
                                    val displayText = layoutConfig.formatModeText(entry.label)
                                    val modeFontWeight = if (entry.isSelected) layoutConfig.fontWeightOption.weight else FontWeight.Normal
                                    val modeLetterSpacing = layoutConfig.letterSpacingSp.sp

                                    Column(
                                        modifier = Modifier
                                            .clickable { entry.onSelect() }
                                            .padding(vertical = 4.dp, horizontal = 4.dp)
                                            .testTag(entry.tag),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = if (entry.isSelected) Color(0x3DFFFFFF) else Color.Transparent,
                                            border = if (entry.isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)) else null
                                        ) {
                                            Text(
                                                text = displayText,
                                                color = if (entry.isSelected) customTextColor else customTextColor.copy(alpha = 0.65f),
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
                                }
                            }
                        } else {
                            // Non-Pixel UI Templates: preserve original mode carousel behavior
                            val isOppoModeRail = layoutConfig.modeSelectorStyle == ModeSelectorStyle.OPPO_PILL
                            // Keep every user-facing camera mode directly in the selector.
                            // Pro Manual and the More entry are intentionally removed.
                            val modesToDisplay = remember {
                                listOf(
                                    CameraMode.PHOTO,
                                    CameraMode.VIDEO,
                                    CameraMode.CINEMA,
                                    CameraMode.AI_SUBJECT_TRACKING
                                )
                            }

                            val isMoreModeActive = if (isOppoModeRail) {
                                cameraMode == CameraMode.MORE
                            } else {
                                cameraMode != CameraMode.PHOTO && cameraMode != CameraMode.VIDEO
                            }

                            LaunchedEffect(cameraMode, isOppoModeRail) {
                                val targetMode = when {
                                    isOppoModeRail && cameraMode == CameraMode.CINEMA -> CameraMode.CINEMA
                                    isMoreModeActive -> CameraMode.MORE
                                    else -> cameraMode
                                }
                                val index = modesToDisplay.indexOf(targetMode)
                                if (index >= 0) {
                                    val itemEstimatedWidthPx = if (isOppoModeRail) 330 else if (layoutConfig.modeSelectorStyle == ModeSelectorStyle.IPHONE_LIQUID_GLASS) 140 else 180
                                    val targetScroll = (index * itemEstimatedWidthPx - 140).coerceAtLeast(0)
                                    modeScrollState.animateScrollTo(targetScroll)
                                }
                            }

                            Row(
                                modifier = if (layoutConfig.modeSelectorStyle == ModeSelectorStyle.IPHONE_LIQUID_GLASS) {
                                    // The iPhone mode rail is a compact horizontally-scrollable glass capsule.
                                    Modifier
                                        .widthIn(max = 280.dp)
                                        .clip(RoundedCornerShape(28.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(Color(0x32FFFFFF), Color(0x18FFFFFF))
                                            ),
                                            RoundedCornerShape(28.dp)
                                        )
                                        .border(
                                            1.dp,
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 0.22f),
                                                    Color.White.copy(alpha = 0.08f)
                                                )
                                            ),
                                            RoundedCornerShape(28.dp)
                                        )
                                        .horizontalScroll(modeScrollState)
                                        .padding(horizontal = 5.dp, vertical = 3.dp)
                                } else {
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(modeScrollState)
                                        .padding(horizontal = 16.dp)
                                },
                                horizontalArrangement = Arrangement.spacedBy(
                                    when (layoutConfig.modeSelectorStyle) {
                                        ModeSelectorStyle.OPPO_PILL -> 12.dp
                                        ModeSelectorStyle.IPHONE_LIQUID_GLASS -> 5.dp
                                        else -> 22.dp
                                    },
                                    Alignment.CenterHorizontally
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                modesToDisplay.forEach { mode ->
                                    val isSelected = if (mode == CameraMode.MORE) isMoreModeActive else (cameraMode == mode)
                                    val targetTextColor = if (isSelected) {
                                        if (layoutConfig.modeSelectorStyle == ModeSelectorStyle.MONO_TICKER) Color(0xFFE53935)
                                        else if (layoutConfig.modeSelectorStyle == ModeSelectorStyle.CYBER_GLOW) Color(0xFF00E5FF)
                                        else if (layoutConfig.shutterStyle == ShutterStyle.VIVO_GIMBAL ||
                                            layoutConfig.modeSelectorStyle == ModeSelectorStyle.IPHONE_LIQUID_GLASS) accentColor
                                        else customTextColor
                                    } else {
                                        customTextColor.copy(alpha = 0.65f)
                                    }
                                    val textColor by animateColorAsState(
                                        targetTextColor,
                                        label = "modeTextColor"
                                    )

                                    val rawName = when {
                                        isOppoModeRail && mode == CameraMode.CINEMA -> "Pro Video"
                                        mode == CameraMode.MORE && isMoreModeActive &&
                                            cameraMode != CameraMode.MORE &&
                                            layoutConfig.modeSelectorStyle != ModeSelectorStyle.OPPO_PILL -> cameraMode.name
                                        else -> mode.name
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
                                            .then(if (isOppoModeRail) Modifier.width(90.dp) else Modifier)
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
                                            ModeSelectorStyle.IPHONE_LIQUID_GLASS -> {
                                                Surface(
                                                    shape = RoundedCornerShape(22.dp),
                                                    color = if (isSelected) Color(0x3DFFFFFF) else Color.Transparent,
                                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(
                                                        1.dp,
                                                        Brush.verticalGradient(
                                                            colors = listOf(
                                                                Color.White.copy(alpha = 0.44f),
                                                                Color.White.copy(alpha = 0.12f)
                                                            )
                                                        )
                                                    ) else null,
                                                    shadowElevation = if (isSelected) 2.dp else 0.dp
                                                ) {
                                                    Text(
                                                        text = displayText,
                                                        color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.72f),
                                                        fontSize = layoutConfig.modeTextSizeSp.sp,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                        fontFamily = fontFamily,
                                                        letterSpacing = 0.0.sp,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp)
                                                    )
                                                }
                                            }
                                            ModeSelectorStyle.OPPO_PILL -> {
                                                Surface(
                                                    shape = RoundedCornerShape(22.dp),
                                                    color = if (isSelected) Color(0xFF272326) else Color.Transparent
                                                ) {
                                                    Text(
                                                        text = if (isSelected) "$displayText ▴" else displayText,
                                                        color = if (isSelected) Color(0xFFD9A49B) else customTextColor.copy(alpha = 0.68f),
                                                        fontSize = layoutConfig.modeTextSizeSp.sp,
                                                        fontWeight = modeFontWeight,
                                                        fontFamily = fontFamily,
                                                        letterSpacing = modeLetterSpacing,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
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
                }

                // Render in accordance with modeSelectorPosition
                if (layoutConfig.modeSelectorPosition == ModeSelectorPosition.ABOVE_SHUTTER && layoutConfig.modeSelectorStyle != ModeSelectorStyle.OPPO_PILL) {
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
                                // First Icon (Photo Modes): Photo, Pro Manual
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isPixelPhotoGroup) Color.White else Color.Transparent)
                                        .clickable {
                                            onSetManualProOpen(false)
                                            onModeSelected(CameraMode.PHOTO)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                        .testTag("pixel_dock_photo_modes_icon"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CameraAlt,
                                        contentDescription = "Photo Modes",
                                        tint = if (isPixelPhotoGroup) Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Second Icon (Video and Special Modes): Video, Cinema, AI Subject Tracing
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isPixelVideoGroup) Color.White else Color.Transparent)
                                        .clickable {
                                            onSetManualProOpen(false)
                                            onModeSelected(CameraMode.VIDEO)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                        .testTag("pixel_dock_video_modes_icon"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Videocam,
                                        contentDescription = "Video and Special Modes",
                                        tint = if (isPixelVideoGroup) Color.Black else Color.White,
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
    customPresets: List<Float>? = null,
    onShowToast: (String) -> Unit = {},
    onLensSelected: (LensInfo) -> Unit,
    onZoomChange: (Float) -> Unit,
    onZoomPresetTap: (Float) -> Unit,
    layoutConfig: ModeLayoutConfig = ModeLayoutConfig(),
    modifier: Modifier = Modifier
) {
    val fontFamily = layoutConfig.modeFontFamily.toComposeFontFamily()
    val isFrontCamera = selectedLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
    if (isFrontCamera) {
        return
    }
    val isOppoStyle = layoutConfig.modeSelectorStyle == ModeSelectorStyle.OPPO_PILL
    val hasRealUltraWide = remember(displayedLenses) {
        displayedLenses.any { it.lensType == LensType.ULTRAWIDE }
    }
    val presets = remember(isFrontCamera, hasRealUltraWide, customPresets) {
        customPresets?.takeIf { it.isNotEmpty() } ?: if (hasRealUltraWide) {
            listOf(0.5f, 1.0f, 2.0f, 3.0f)
        } else {
            listOf(1.0f, 2.0f, 3.0f)
        }
    }

    var isSliderOpen by remember { mutableStateOf(false) }
    var isCapsuleDragging by remember { mutableStateOf(false) }

    val isMainWide = selectedLens == null || selectedLens.isPrimaryMain || selectedLens.lensType == LensType.WIDE
    val isUltraWide = selectedLens?.lensType == LensType.ULTRAWIDE

    // Fixed zoom slider bounds:
    // Do NOT dynamically change the slider range based on which physical lens is currently active.
    // If a real Ultra-wide lens exists on the device, the slider must always allow the full range starting from 0.5x.
    // Keep minZoom and maxZoom invariant across active lens state so 0.5x <-> 10x is smooth, continuous and bidirectional.
    val minZoom = if (isFrontCamera) 0.8f else if (hasRealUltraWide) 0.5f else 1.0f
    val maxLensZoom = remember(displayedLenses, selectedLens?.facing) {
        displayedLenses
            .filter { selectedLens == null || it.facing == selectedLens.facing }
            .maxOfOrNull { it.maxZoomRatio }
    }
    val maxZoom = if (isFrontCamera) {
        1.0f
    } else if (isOppoStyle) {
        // Oppo's curved dial is deliberately limited to the device-supported range up to 10x.
        val supportedMax = maxOf(capabilities.maxZoom, maxLensZoom ?: capabilities.maxZoom)
        supportedMax.coerceAtMost(10.0f).coerceAtLeast(minZoom)
    } else {
        maxOf(capabilities.maxZoom, maxLensZoom ?: 20.0f, 20.0f)
    }

    var liveZoom by remember { mutableFloatStateOf(currentZoom.coerceIn(minZoom, maxZoom)) }
    LaunchedEffect(currentZoom, minZoom, maxZoom) {
        if (!isCapsuleDragging) {
            liveZoom = currentZoom.coerceIn(minZoom, maxZoom)
        }
    }

    val currentZoomState = rememberUpdatedState(liveZoom)
    val onZoomChangeState = rememberUpdatedState(onZoomChange)
    val minZoomState = rememberUpdatedState(minZoom)
    val maxZoomState = rememberUpdatedState(maxZoom)
    val isSliderOpenState = rememberUpdatedState(isSliderOpen)

    val density = LocalDensity.current
    val totalRulerWidthPx = if (isOppoStyle) {
        val sliderWidthDp = LocalConfiguration.current.screenWidthDp.coerceAtLeast(300)
        with(density) { sliderWidthDp.dp.toPx() * HALF_CIRCLE_TRACK_WIDTH_MULTIPLIER }
    } else {
        with(density) { (com.example.camera.ui.components.RULER_TOTAL_TICKS * com.example.camera.ui.components.RULER_TICK_SPACING_DP.value).dp.toPx() }
    }
    val capsuleSwipeSlopPx = with(density) { 3.5.dp.toPx() }

    // Persistent outer container so opening the slider during a horizontal swipe
    // never unmounts the pointerInput target or drops the first swipe gesture.
    Box(
        modifier = modifier
            // Keep the closed capsule separated from the bottom panel, but when expanded,
            // let the semicircle base meet the black panel edge exactly (no gap/overlap).
            .padding(bottom = if (isSliderOpen) 0.dp else 14.dp)
            .pointerInput(totalRulerWidthPx) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Only initiate capsule-to-slider opening drag when starting on the closed capsule
                    if (isSliderOpenState.value || isFrontCamera) {
                        continue
                    }

                    val downX = down.position.x
                    var lastX = downX
                    var dragOpened = false
                    val pointerId = down.id
                    var accumulatedNorm = com.example.camera.ui.components.zoomToNormalizedLog(
                        currentZoomState.value,
                        minZoomState.value,
                        maxZoomState.value
                    )

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                            ?: break

                        if (!change.pressed) {
                            if (dragOpened) {
                                change.consume()
                            }
                            break
                        }

                        val currentX = change.position.x
                        val totalDx = currentX - downX

                        if (!dragOpened && kotlin.math.abs(totalDx) >= capsuleSwipeSlopPx) {
                            dragOpened = true
                            isCapsuleDragging = true
                            isSliderOpen = true
                            val minZ = minZoomState.value
                            val maxZ = maxZoomState.value
                            // Apply full distance from initial touch-down so zero drag distance is lost
                            val deltaNorm = (-totalDx * com.example.camera.ui.components.ZOOM_SLIDER_DRAG_SENSITIVITY) / totalRulerWidthPx
                            accumulatedNorm = (accumulatedNorm + deltaNorm).coerceIn(0f, 1f)
                            val newZoom = com.example.camera.ui.components.normalizedToZoomLog(
                                accumulatedNorm,
                                minZ,
                                maxZ
                            )
                            liveZoom = newZoom
                            lastX = currentX
                            change.consume()
                            onZoomChangeState.value(newZoom)
                        } else if (dragOpened) {
                            val stepDx = currentX - lastX
                            lastX = currentX
                            if (kotlin.math.abs(stepDx) > 0.01f) {
                                val minZ = minZoomState.value
                                val maxZ = maxZoomState.value
                                val deltaNorm = (-stepDx * com.example.camera.ui.components.ZOOM_SLIDER_DRAG_SENSITIVITY) / totalRulerWidthPx
                                accumulatedNorm = (accumulatedNorm + deltaNorm).coerceIn(0f, 1f)
                                val newZoom = com.example.camera.ui.components.normalizedToZoomLog(
                                    accumulatedNorm,
                                    minZ,
                                    maxZ
                                )
                                liveZoom = newZoom
                                onZoomChangeState.value(newZoom)
                            }
                            change.consume()
                        }
                    }

                    if (dragOpened) {
                        isCapsuleDragging = false
                    }
                }
            }
        },
        contentAlignment = Alignment.Center
    ) {
        if (isSliderOpen) {
            val sliderZoomChange: (Float) -> Unit = { newZoom ->
                liveZoom = newZoom.coerceIn(minZoom, maxZoom)
                onZoomChange(liveZoom)
            }
            val closeSlider = {
                isCapsuleDragging = false
                isSliderOpen = false
            }
            if (isOppoStyle) {
                HalfCircleZoomSlider(
                    currentZoom = liveZoom,
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    onZoomChange = sliderZoomChange,
                    onClose = closeSlider,
                    isExternalDragging = isCapsuleDragging,
                    modifier = Modifier.offset(y = (-20).dp)
                )
            } else {
                HorizontalRulerZoomSlider(
                    currentZoom = liveZoom,
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    onZoomChange = sliderZoomChange,
                    onClose = closeSlider,
                    isExternalDragging = isCapsuleDragging
                )
            }
        } else if (layoutConfig.useFocalLengthZoomLabels) {
            // Vivo-style focal length scale: 14 / 35 mm / 85 / 170 / 230.
            // Keep the existing horizontal ruler slider and zoom callback behavior.
            val focalLengthSteps = listOf(
                14 to 0.5f,
                35 to 1.0f,
                85 to (85f / 35f),
                170 to (170f / 35f),
                230 to (230f / 35f)
            )
            val selectedFocalLength = focalLengthSteps.minByOrNull { (_, ratio) ->
                (ratio - liveZoom).absoluteValue
            }?.first ?: 35
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 1.dp)
                    .testTag("vivo_focal_length_bar"),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top
            ) {
                focalLengthSteps.forEach { (focalLength, preset) ->
                    val isActive = focalLength == selectedFocalLength
                    Column(
                        modifier = Modifier
                            .width(if (focalLength == 35) 34.dp else 28.dp)
                            .clickable {
                                when {
                                    focalLength == 14 && !hasRealUltraWide -> {
                                        onShowToast("Ultra-Wide lens is not available on this device")
                                    }
                                    preset > maxZoom -> {
                                        onShowToast("${focalLength}mm zoom is not supported on this device")
                                    }
                                    (liveZoom - preset).absoluteValue < 0.15f -> {
                                        isSliderOpen = true
                                    }
                                    else -> {
                                        liveZoom = preset
                                        onZoomPresetTap(preset)
                                    }
                                }
                            }
                            .testTag("vivo_focal_${focalLength}mm"),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = focalLength.toString(),
                            color = if (isActive) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.92f),
                            fontSize = 13.5.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                            fontFamily = fontFamily,
                            letterSpacing = (-0.2).sp,
                            maxLines = 1,
                            softWrap = false
                        )
                        if (focalLength == 35) {
                            Text(
                                text = "mm",
                                color = if (isActive) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.88f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = fontFamily,
                                lineHeight = 9.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
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
                    presets.forEach { preset ->
                        val isClosest = presets.minByOrNull { (it - currentZoom).absoluteValue } == preset
                        val isExactMatch = (currentZoom - preset).absoluteValue < 0.2f
                        val isActive = isExactMatch || isClosest

                        val label = when (preset) {
                            0.5f -> if (layoutConfig.modeSelectorStyle == ModeSelectorStyle.IPHONE_LIQUID_GLASS) ".5" else "0.5"
                            0.8f -> "0.8"
                            1.0f -> "1x"
                            2.0f -> "2"
                            3.0f -> "3"
                            4.0f -> "4"
                            5.0f -> "5"
                            8.0f -> "8"
                            10.0f -> "10"
                            else -> if (preset % 1.0f == 0f) "${preset.toInt()}" else "%.1f".format(preset)
                        }

                        val displayText = if (isActive && (currentZoom - preset).absoluteValue >= 0.05f) {
                            if (currentZoom < 2.0f) {
                                String.format(java.util.Locale.US, "%.2fx", currentZoom)
                            } else {
                                String.format(java.util.Locale.US, "%.1fx", currentZoom)
                            }
                        } else {
                            label
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActive && layoutConfig.modeSelectorStyle == ModeSelectorStyle.IPHONE_LIQUID_GLASS) {
                                        Color(0xFF4B4A45)
                                    } else if (isActive) {
                                        Color(0xFF26210A)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .border(
                                    width = if (isActive && layoutConfig.modeSelectorStyle != ModeSelectorStyle.IPHONE_LIQUID_GLASS) 1.5.dp else 0.dp,
                                    color = if (isActive && layoutConfig.modeSelectorStyle != ModeSelectorStyle.IPHONE_LIQUID_GLASS) {
                                        Color(0xFFFFD54F)
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = CircleShape
                                )
                                .clickable {
                                    if (isActive) {
                                        if (!isFrontCamera) {
                                            liveZoom = preset
                                            isSliderOpen = true
                                        }
                                    } else if (preset == 0.5f && !hasRealUltraWide && !isFrontCamera) {
                                        onShowToast("Ultra-Wide lens is not available on this device")
                                    } else if (preset > maxZoom) {
                                        onShowToast("${preset.toInt()}x zoom is not supported on this device")
                                    } else {
                                        onZoomPresetTap(preset)
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
}

