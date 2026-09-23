package com.example.camera.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.*
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
import com.example.camera.computational.video.ComputationalVideoPipeline
import com.example.camera.model.*

fun getTopControlShape(layoutConfig: ModeLayoutConfig): androidx.compose.ui.graphics.Shape {
    return when (layoutConfig.iconShapeOption) {
        IconShapeOption.CIRCLE_GLASS -> CircleShape
        IconShapeOption.ROUNDED_SQUARE -> RoundedCornerShape(10.dp)
        IconShapeOption.HEXAGON -> RoundedCornerShape(6.dp)
        IconShapeOption.PILL -> RoundedCornerShape(18.dp)
        IconShapeOption.TRANSPARENT_NONE -> RoundedCornerShape(0.dp)
    }
}

fun Modifier.topControlStyle(
    layoutConfig: ModeLayoutConfig,
    activeColor: Color? = null,
    isPill: Boolean = false
): Modifier {
    // If transparent / floating icons requested, don't draw any background pod or border
    if (layoutConfig.iconShapeOption == IconShapeOption.TRANSPARENT_NONE) {
        return this
    }

    val shape = if (isPill) RoundedCornerShape(17.dp) else getTopControlShape(layoutConfig)
    val style = layoutConfig.iconStyleOption

    val (bgColor, borderColor, borderWidth) = if (activeColor != null) {
        Triple(activeColor.copy(alpha = 0.25f), activeColor, 1.2.dp)
    } else {
        when (style) {
            IconStyleOption.ROUNDED_MATERIAL -> Triple(
                Color(0xB21A1A1E),
                Color.White.copy(alpha = 0.22f),
                1.dp
            )
            IconStyleOption.MINIMAL_OUTLINE -> Triple(
                Color(0x22000000),
                Color.White.copy(alpha = 0.55f),
                1.dp
            )
            IconStyleOption.SHARP_GEOMETRIC -> Triple(
                Color(0xE614161C),
                Color.White.copy(alpha = 0.9f),
                1.5.dp
            )
            IconStyleOption.BOLD_SOLID -> Triple(
                Color(0xEE222630),
                Color.White.copy(alpha = 0.85f),
                1.5.dp
            )
            IconStyleOption.CYBER_NEON -> Triple(
                Color(0xE60A1828),
                Color(0xFF00E5FF),
                1.5.dp
            )
            IconStyleOption.FROSTED_GLASS -> Triple(
                Color(0x44FFFFFF),
                Color.White.copy(alpha = 0.6f),
                1.dp
            )
            IconStyleOption.NEOMORPHIC -> Triple(
                Color(0xDD2D333F),
                Color(0x66FFFFFF),
                1.5.dp
            )
            IconStyleOption.RETRO_BADGE -> Triple(
                Color(0xEE2A2219),
                Color(0xFFFFB300),
                1.5.dp
            )
        }
    }

    return this
        .clip(shape)
        .background(bgColor)
        .border(borderWidth, borderColor, shape)
}

/**
 * Master Top Control Bar matching the reference UI design.
 * Exactly 6 beautifully aligned elements across a pure dark glass bar:
 * [Flash]  [Timer/Action]  [Pill 1]  [Pill 2]  [Grid/Assist]  [Settings]
 */
@Composable
fun TopControlBar(
    cameraMode: CameraMode,
    flashMode: FlashMode,
    timerMode: TimerMode,
    gridType: GridType,
    isRawEnabled: Boolean,
    supportsRaw: Boolean,
    storageStats: StorageStats = StorageStats(),
    videoQuality: VideoQualityOption = VideoQualityOption.UHD_4K_30,
    videoResolution: CameraResolution? = null,
    videoFps: Int = 30,
    photoMegapixelMode: PhotoMegapixelMode = PhotoMegapixelMode.M12,
    cinemaConfig: CinemaConfig = CinemaConfig(),
    isAudioEnabled: Boolean = true,
    onAudioToggle: () -> Unit = {},
    portraitAperture: String = "f/1.8",
    onPortraitApertureClick: () -> Unit = {},
    onPortraitStyleClick: () -> Unit = {},
    onPhotoFilterClick: () -> Unit = {},
    activePhotoFilter: PhotoFilter = PhotoFilter.ORIGINAL,
    selectedPortraitStyle: PortraitStyle = PortraitStyle.NATURAL,
    onCinemaSettingsClick: () -> Unit = {},
    onCinemaEvChange: (Int) -> Unit = {},
    onVideoQualityClick: () -> Unit = {},
    onVideoSettingsClick: () -> Unit = {},
    onToggleMegapixelMode: () -> Unit = {},
    onDollyZoomClick: () -> Unit = {},
    isVideoAdjustmentsOpen: Boolean = false,
    hasActiveVideoAdjustments: Boolean = false,
    onVideoAdjustmentsClick: () -> Unit = {},
    activeComputationalPipeline: ComputationalVideoPipeline = ComputationalVideoPipeline.DEFAULT,
    onComputationalPipelineChange: (ComputationalVideoPipeline) -> Unit = {},
    onFlashClick: () -> Unit,
    onTimerClick: () -> Unit,
    onGridClick: () -> Unit,
    onRawClick: () -> Unit,
    onSettingsClick: () -> Unit,
    layoutConfig: ModeLayoutConfig = ModeLayoutConfig(),
    modifier: Modifier = Modifier
) {
    val accentColor = layoutConfig.getComposeAccentColor()
    val iconSize = layoutConfig.topControlsIconSizeDp.dp
    val buttonSize = (layoutConfig.topControlsIconSizeDp + 14).dp.coerceAtLeast(32.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xEE0A0C12),
                        Color(0x990E1118),
                        Color.Transparent
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("master_top_control_bar")
    ) {
        val flashButton = @Composable {
            val (flashIcon, flashColor) = when (flashMode) {
                FlashMode.OFF -> Icons.Outlined.FlashOff to Color.White.copy(alpha = 0.85f)
                FlashMode.AUTO -> Icons.Outlined.FlashAuto to accentColor
                FlashMode.ON -> Icons.Outlined.FlashOn to accentColor
                FlashMode.TORCH -> Icons.Outlined.Highlight to Color(0xFFFFB300)
            }
            val isFlashActive = flashMode != FlashMode.OFF
            IconButton(
                onClick = onFlashClick,
                modifier = Modifier
                    .size(buttonSize)
                    .topControlStyle(layoutConfig, activeColor = if (isFlashActive) flashColor else null)
                    .testTag("flash_button")
            ) {
                Icon(
                    imageVector = flashIcon,
                    contentDescription = "Flash: ${flashMode.title}",
                    tint = flashColor,
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        val timerAudioButton = @Composable {
            when (cameraMode) {
                CameraMode.VIDEO, CameraMode.CINEMA -> {
                    IconButton(
                        onClick = onAudioToggle,
                        modifier = Modifier
                            .size(buttonSize)
                            .topControlStyle(layoutConfig, activeColor = if (!isAudioEnabled) Color(0xFFFF6B6B) else null)
                            .testTag("video_audio_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isAudioEnabled) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                            contentDescription = if (isAudioEnabled) "Audio On" else "Audio Muted",
                            tint = if (isAudioEnabled) Color.White.copy(alpha = 0.85f) else Color(0xFFFF6B6B),
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }
                else -> {
                    IconButton(
                        onClick = onTimerClick,
                        modifier = Modifier
                            .size(buttonSize)
                            .topControlStyle(layoutConfig, activeColor = if (timerMode != TimerMode.OFF) accentColor else null)
                            .testTag("timer_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (timerMode == TimerMode.OFF) Icons.Outlined.TimerOff else Icons.Outlined.Timer,
                                contentDescription = "Timer: ${timerMode.label}",
                                tint = if (timerMode == TimerMode.OFF) Color.White.copy(alpha = 0.85f) else accentColor,
                                modifier = Modifier.size(iconSize)
                            )
                            if (timerMode != TimerMode.OFF) {
                                Text(
                                    text = timerMode.label,
                                    color = accentColor,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .offset(x = 8.dp, y = 6.dp)
                                        .background(Color.Black, shape = CircleShape)
                                        .padding(1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        val primaryBadge = @Composable {
            when (cameraMode) {
                CameraMode.PHOTO -> {
                    val is50M = photoMegapixelMode == PhotoMegapixelMode.M50
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(if (is50M) accentColor.copy(alpha = 0.2f) else Color(0xB21A1A1E))
                            .border(
                                1.dp,
                                if (is50M) accentColor else Color.White.copy(alpha = 0.22f),
                                RoundedCornerShape(17.dp)
                            )
                            .clickable { onToggleMegapixelMode() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = photoMegapixelMode.label,
                            color = if (is50M) accentColor else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CameraMode.PORTRAIT -> {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, accentColor, RoundedCornerShape(17.dp))
                            .clickable { onPortraitApertureClick() }
                            .padding(horizontal = 12.dp)
                            .testTag("portrait_aperture_pill"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "f",
                                color = accentColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif
                            )
                            Text(
                                text = portraitAperture,
                                color = accentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                CameraMode.VIDEO -> {
                    val resLabel = when {
                        videoResolution?.width == 3840 || videoResolution?.height == 3840 -> "4K"
                        videoResolution?.width == 7680 || videoResolution?.height == 7680 -> "8K"
                        videoResolution?.width == 1920 || videoResolution?.height == 1920 -> "1080"
                        videoResolution?.width == 1280 || videoResolution?.height == 1280 -> "720"
                        else -> "4K"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(17.dp))
                                .background(Color(0xB21A1A1E))
                                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(17.dp))
                                .clickable { onVideoSettingsClick() }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = resLabel,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        // Compact Native Computational Video Pipeline Selector Pill
                        val isCompActive = (activeComputationalPipeline != ComputationalVideoPipeline.DEFAULT)
                        val pipeColor = if (isCompActive) Color(0xFF4DEEEA) else Color.White.copy(alpha = 0.85f)
                        val pipeBg = if (isCompActive) Color(0x334DEEEA) else Color(0xB21A1A1E)
                        val pipeBorder = if (isCompActive) Color(0xFF4DEEEA) else Color.White.copy(alpha = 0.22f)

                        Box(
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(17.dp))
                                .background(pipeBg)
                                .border(1.dp, pipeBorder, RoundedCornerShape(17.dp))
                                .clickable {
                                    val next = when (activeComputationalPipeline) {
                                        ComputationalVideoPipeline.DEFAULT -> ComputationalVideoPipeline.PIXEL
                                        ComputationalVideoPipeline.PIXEL -> ComputationalVideoPipeline.SAMSUNG
                                        ComputationalVideoPipeline.SAMSUNG -> ComputationalVideoPipeline.IPHONE
                                        ComputationalVideoPipeline.IPHONE -> ComputationalVideoPipeline.VIVO
                                        ComputationalVideoPipeline.VIVO -> ComputationalVideoPipeline.DEFAULT
                                    }
                                    onComputationalPipelineChange(next)
                                }
                                .padding(horizontal = 10.dp)
                                .testTag("computational_pipeline_selector"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoFixHigh,
                                    contentDescription = "Computational Video Pipeline: ${activeComputationalPipeline.displayName}",
                                    tint = pipeColor,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = activeComputationalPipeline.badge,
                                    color = pipeColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
                CameraMode.CINEMA -> {
                    val resLabel = when {
                        cinemaConfig.selectedResolution?.width == 3840 || cinemaConfig.selectedResolution?.height == 3840 -> "4K"
                        cinemaConfig.selectedResolution?.width == 1920 || cinemaConfig.selectedResolution?.height == 1920 -> "1080"
                        else -> "4K"
                    }
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(17.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = resLabel,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CameraMode.MORE, CameraMode.AI_SUBJECT_TRACKING -> {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(17.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "MORE",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CameraMode.HUMAN_VISION -> {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(accentColor.copy(alpha = 0.2f))
                            .border(1.dp, accentColor, RoundedCornerShape(17.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "HUMAN VISION",
                            color = accentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CameraMode.NIGHT -> {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0x33FFB300))
                            .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(17.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "NIGHT HDR",
                            color = Color(0xFFFFB300),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CameraMode.DOLLY_ZOOM -> {
                    val resLabel = when {
                        videoResolution?.width == 3840 || videoResolution?.height == 3840 -> "4K"
                        videoResolution?.width == 1920 || videoResolution?.height == 1920 -> "1080"
                        videoQuality == VideoQualityOption.UHD_4K_30 || videoQuality == VideoQualityOption.UHD_4K_60 -> "4K"
                        else -> "1080"
                    }
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(17.dp))
                            .clickable { onVideoQualityClick() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = resLabel,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        val secondaryBadge = @Composable {
            when (cameraMode) {
                CameraMode.PHOTO -> {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(if (isRawEnabled) accentColor.copy(alpha = 0.2f) else Color(0xB21A1A1E))
                            .border(
                                1.dp,
                                if (isRawEnabled) accentColor else Color.White.copy(alpha = 0.22f),
                                RoundedCornerShape(17.dp)
                            )
                            .clickable { onRawClick() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "RAW",
                            color = if (isRawEnabled) accentColor else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CameraMode.PORTRAIT -> {
                    val portraitAccent = Color(0xFFFF8A65)
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, portraitAccent.copy(alpha = 0.6f), RoundedCornerShape(17.dp))
                            .clickable { onPortraitStyleClick() }
                            .padding(horizontal = 10.dp)
                            .testTag("portrait_style_pill"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FaceRetouchingNatural,
                                contentDescription = "Portrait Style",
                                tint = portraitAccent,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = selectedPortraitStyle.displayName.uppercase(),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                CameraMode.VIDEO -> {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(17.dp))
                            .clickable { onVideoSettingsClick() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$videoFps",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CameraMode.CINEMA -> {
                    val bitLabel = if (cinemaConfig.logBitDepth == LogBitDepth.BIT_10) "10b" else "8b"
                    val label = if (cinemaConfig.colorProfile == CinemaColorProfile.PROCESSED_JPEG) {
                        "RAW JPEG"
                    } else {
                        "LOG $bitLabel"
                    }
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, accentColor, RoundedCornerShape(17.dp))
                            .clickable { onCinemaSettingsClick() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = accentColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CameraMode.MORE, CameraMode.AI_SUBJECT_TRACKING -> {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(17.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "PRO",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CameraMode.HUMAN_VISION -> {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, Color(0xFF64B5F6).copy(alpha = 0.6f), RoundedCornerShape(17.dp))
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Visibility,
                                contentDescription = "Natural Perspective",
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "NATURAL",
                                color = Color(0xFF64B5F6),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                CameraMode.NIGHT -> {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(17.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "MULTI-FUSION",
                            color = Color(0xFFFFB300),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CameraMode.DOLLY_ZOOM -> {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color(0xB21A1A1E))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(17.dp))
                            .clickable { onVideoQualityClick() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${videoFps}FPS",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        val gridAssistButton = @Composable {
            IconButton(
                onClick = onGridClick,
                modifier = Modifier
                    .size(buttonSize)
                    .topControlStyle(layoutConfig, activeColor = if (gridType != GridType.NONE) accentColor else null)
                    .testTag("grid_button")
            ) {
                Icon(
                    imageVector = if (gridType == GridType.NONE) Icons.Outlined.GridOff else Icons.Outlined.GridOn,
                    contentDescription = "Grid: ${gridType.title}",
                    tint = if (gridType == GridType.NONE) Color.White.copy(alpha = 0.85f) else accentColor,
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        val proExpButton = @Composable {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .topControlStyle(layoutConfig, activeColor = accentColor, isPill = true)
                    .clickable { onSettingsClick() }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PRO",
                    color = accentColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        val settingsButton = @Composable {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(buttonSize)
                    .topControlStyle(layoutConfig)
                    .testTag("settings_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        val filterButton = @Composable {
            val isFilterActive = activePhotoFilter != PhotoFilter.ORIGINAL
            IconButton(
                onClick = onPhotoFilterClick,
                modifier = Modifier
                    .size(buttonSize)
                    .topControlStyle(layoutConfig, activeColor = if (isFilterActive) Color(0xFF64FFDA) else null)
                    .testTag("photo_filter_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Photo Filters",
                    tint = if (isFilterActive) Color(0xFF64FFDA) else Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        val dollyZoomButton = @Composable {
            val isActive = (cameraMode == CameraMode.DOLLY_ZOOM)
            IconButton(
                onClick = onDollyZoomClick,
                modifier = Modifier
                    .size(buttonSize)
                    .topControlStyle(layoutConfig, activeColor = if (isActive) accentColor else null)
                    .testTag("top_dolly_zoom_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.CenterFocusStrong,
                    contentDescription = "Dolly Zoom",
                    tint = if (isActive) accentColor else Color.White,
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        val portraitStyleButton = @Composable {
            IconButton(
                onClick = onPortraitStyleClick,
                modifier = Modifier
                    .size(buttonSize)
                    .topControlStyle(layoutConfig, activeColor = Color(0xFFFF8A65))
                    .testTag("portrait_style_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FaceRetouchingNatural,
                    contentDescription = "Portrait Style",
                    tint = Color(0xFFFF8A65),
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        val cinemaSettingsQuickButton = @Composable {
            IconButton(
                onClick = onCinemaSettingsClick,
                modifier = Modifier
                    .size(buttonSize)
                    .topControlStyle(layoutConfig, activeColor = accentColor)
                    .testTag("cinema_settings_quick_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Movie,
                    contentDescription = "Cinema Settings",
                    tint = accentColor,
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        val videoAdjustmentsButton = @Composable {
            IconButton(
                onClick = onVideoAdjustmentsClick,
                modifier = Modifier
                    .size(buttonSize)
                    .topControlStyle(
                        layoutConfig,
                        activeColor = if (isVideoAdjustmentsOpen || hasActiveVideoAdjustments) Color(0xFFFFD54F) else null
                    )
                    .testTag("video_adjustments_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "Video Adjustments",
                    tint = if (isVideoAdjustmentsOpen || hasActiveVideoAdjustments) Color(0xFFFFD54F) else Color.White,
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        val isVideoFamily = (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.CINEMA || cameraMode == CameraMode.DOLLY_ZOOM)

        // Render Top Controls according to layoutConfig
        val visibleItems = layoutConfig.topControlsOrder.filterNot { layoutConfig.hiddenTopControls.contains(it) }

        val horizontalArrangement = when (layoutConfig.topBarAlignment) {
            TopBarAlignment.SPACE_BETWEEN -> Arrangement.SpaceBetween
            TopBarAlignment.CENTER -> Arrangement.spacedBy(layoutConfig.topControlsSpacingDp.dp, Alignment.CenterHorizontally)
            TopBarAlignment.COMPACT_LEFT -> Arrangement.spacedBy(layoutConfig.topControlsSpacingDp.dp, Alignment.Start)
            TopBarAlignment.COMPACT_RIGHT -> Arrangement.spacedBy(layoutConfig.topControlsSpacingDp.dp, Alignment.End)
        }

        val shouldScroll = (if (isVideoFamily) 6 else visibleItems.size) > 5
        val topScrollState = rememberScrollState()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (shouldScroll) Modifier.horizontalScroll(topScrollState) else Modifier
                ),
            horizontalArrangement = if (shouldScroll) Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally) else horizontalArrangement,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cameraMode == CameraMode.CINEMA) {
                // Pure icon buttons for Cinema mode: no raw 8/10 or text pills
                flashButton()
                timerAudioButton()
                gridAssistButton()
                cinemaSettingsQuickButton()
                settingsButton()
            } else if (cameraMode == CameraMode.VIDEO) {
                flashButton()
                videoAdjustmentsButton()
                primaryBadge()
                secondaryBadge()
                settingsButton()
            } else if (isVideoFamily) {
                flashButton()
                dollyZoomButton()
                primaryBadge()
                secondaryBadge()
                settingsButton()
            } else {
                visibleItems.forEach { item ->
                    Box(
                        modifier = Modifier.wrapContentSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        when (item) {
                            TopControlItem.FLASH -> flashButton()
                            TopControlItem.TIMER -> timerAudioButton()
                            TopControlItem.GRID -> gridAssistButton()
                            TopControlItem.RESOLUTION -> primaryBadge()
                            TopControlItem.RAW -> secondaryBadge()
                            TopControlItem.PRO_EXP -> proExpButton()
                            TopControlItem.SETTINGS -> {
                                if (cameraMode == CameraMode.PHOTO) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        filterButton()
                                        Spacer(modifier = Modifier.width(layoutConfig.topControlsSpacingDp.dp))
                                        settingsButton()
                                    }
                                } else if (cameraMode == CameraMode.PORTRAIT) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        portraitStyleButton()
                                        Spacer(modifier = Modifier.width(layoutConfig.topControlsSpacingDp.dp))
                                        settingsButton()
                                    }
                                } else {
                                    settingsButton()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

