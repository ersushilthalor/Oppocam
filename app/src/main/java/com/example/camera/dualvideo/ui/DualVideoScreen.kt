package com.example.camera.dualvideo.ui

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.camera.dualvideo.engine.DualCameraEngine
import com.example.camera.dualvideo.model.*
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType

@Composable
fun DualVideoScreen(
    availableLenses: List<LensInfo>,
    onBack: () -> Unit,
    onOpenGallery: (Uri?) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBack()
    }

    val context = LocalContext.current
    val dualEngine = remember {
        DualCameraEngine(context, availableLenses).apply {
            initialize()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            dualEngine.release()
        }
    }

    val state by dualEngine.uiState.collectAsState()
    var isResolutionDialogOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("dual_video_screen")
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        // Exactly matches normal Video mode 9:16 portrait framing (16:9 vertical frame)
        val targetRatio = 16f / 9f
        val (targetWidth, targetHeight) = if (containerWidth * targetRatio <= containerHeight) {
            containerWidth to (containerWidth * targetRatio)
        } else {
            (containerHeight / targetRatio) to containerHeight
        }

        // 1. Live Dual Camera OpenGL Viewfinder - centered in 9:16 aspect ratio box
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = targetWidth, height = targetHeight)
                    .clipToBounds()
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    val effW = if (this@apply.width > 0) this@apply.width else w
                                    val effH = if (this@apply.height > 0) this@apply.height else h
                                    dualEngine.setPreviewSurface(Surface(st), effW, effH)
                                }

                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    val effW = if (this@apply.width > 0) this@apply.width else w
                                    val effH = if (this@apply.height > 0) this@apply.height else h
                                    dualEngine.setPreviewSurface(Surface(st), effW, effH)
                                }

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    dualEngine.setPreviewSurface(null, 0, 0)
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("dual_video_viewfinder")
                )
            }
        }

        // 2. Top Bar Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Button
                IconButton(
                    onClick = {
                        if (state.isRecording) {
                            dualEngine.stopRecording { onBack() }
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .testTag("dual_video_back_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Exit Dual Video",
                        tint = Color.White
                    )
                }

                // Dual Video Pill Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xCC111827),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD54F).copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.isRecording) Color.Red else Color(0xFFFFD54F))
                        )
                        Text(
                            text = "DUAL VIDEO",
                            color = Color(0xFFFFD54F),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Resolution & FPS Chip
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0x66000000),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.clickable { isResolutionDialogOpen = true }.testTag("dual_video_res_chip")
                ) {
                    val pW = state.config.resolution.portraitWidth
                    val pH = state.config.resolution.portraitHeight
                    val resTag = when {
                        pW >= 1080 || pH >= 1920 -> "1080p"
                        pW >= 720 || pH >= 1280 -> "720p"
                        else -> "480p"
                    }
                    Text(
                        text = "$resTag ${state.config.fps}fps",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Layout Picker Row (PiP, Split, Equal) & Utility controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Layout Selector Capsule
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0x9918191E),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DualVideoLayout.values().forEach { layout ->
                            val isSelected = state.config.layout == layout
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (isSelected) Color(0xFFFFD54F) else Color.Transparent)
                                    .clickable { dualEngine.setLayout(layout) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .testTag("dual_layout_${layout.name.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = layout.shortName,
                                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Swap Cameras Button (Primary <-> Secondary)
                    IconButton(
                        onClick = { dualEngine.swapCameras() },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                            .testTag("dual_swap_cameras_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Swap Primary and Secondary Cameras",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Audio Toggle Button
                    IconButton(
                        onClick = { dualEngine.setAudioEnabled(!state.config.isAudioEnabled) },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                            .testTag("dual_audio_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (state.config.isAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Toggle Audio",
                            tint = if (state.config.isAudioEnabled) Color(0xFFFFD54F) else Color.Red.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // 3. PiP Corner Cycle Overlay (when in PiP mode)
        if (state.config.layout == DualVideoLayout.PIP) {
            Box(
                modifier = Modifier
                    .padding(top = 110.dp, start = 16.dp, end = 16.dp)
                    .align(Alignment.TopCenter)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x66000000),
                    modifier = Modifier.clickable {
                        val nextPos = when (state.config.pipPosition) {
                            PipPosition.TOP_RIGHT -> PipPosition.TOP_LEFT
                            PipPosition.TOP_LEFT -> PipPosition.BOTTOM_LEFT
                            PipPosition.BOTTOM_LEFT -> PipPosition.BOTTOM_RIGHT
                            PipPosition.BOTTOM_RIGHT -> PipPosition.TOP_RIGHT
                        }
                        dualEngine.setPipPosition(nextPos)
                    }.testTag("dual_pip_pos_cycle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PictureInPicture,
                            contentDescription = "PiP Position",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Tap to move PiP (${state.config.pipPosition.name})",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Hardware Diagnostic / Error Banner
        AnimatedVisibility(
            visible = state.errorMessage != null || !state.capability.isHardwareConcurrentSupported,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .padding(top = if (state.config.layout == DualVideoLayout.PIP) 150.dp else 110.dp, start = 20.dp, end = 20.dp)
                .align(Alignment.TopCenter)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xEE2A1215),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.6f)),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFFFF8A80),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = state.errorMessage ?: state.capability.hardwareDiagnosticMessage ?: "Concurrent camera streaming not supported on this device.",
                        color = Color.White,
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // 4. Primary Lens Switcher Capsule (0.5x, 1x, 2x, etc.)
        val backLenses = state.availablePrimaryLenses
        if (backLenses.size > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
                    .testTag("dual_lens_switcher")
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xCC111827),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        backLenses.forEach { lens ->
                            val isSelected = state.primaryLens?.id == lens.id
                            val label = when (lens.lensType) {
                                LensType.ULTRAWIDE -> "0.5x"
                                LensType.WIDE -> "1x"
                                LensType.TELEPHOTO -> "2x"
                                LensType.TELEPHOTO_3X -> "3x"
                                else -> lens.baseZoomRatio.toString()
                            }

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color(0xFFFFD54F) else Color.Transparent)
                                    .clickable { dualEngine.switchPrimaryLens(lens) }
                                    .testTag("dual_lens_${label.replace(".", "_")}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Bottom Shutter & Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Recording Timer Badge
            AnimatedVisibility(visible = state.isRecording) {
                val mins = state.recordingDurationSeconds / 60
                val secs = state.recordingDurationSeconds % 60
                val timeFormatted = "%02d:%02d".format(mins, secs)

                Row(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Red.copy(alpha = 0.3f))
                        .border(1.dp, Color.Red, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                    Text(
                        text = "REC $timeFormatted",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Shutter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Thumbnail Button
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .border(1.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        .clickable { onOpenGallery(state.lastRecordedVideoUri) }
                        .testTag("dual_gallery_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.lastRecordedVideoUri != null) {
                        AsyncImage(
                            model = state.lastRecordedVideoUri,
                            contentDescription = "Last recorded dual video",
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Gallery",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Main Dual Video Recording Shutter Button
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clickable {
                            if (state.isRecording) {
                                dualEngine.stopRecording()
                            } else {
                                dualEngine.startRecording()
                            }
                        }
                        .testTag("dual_shutter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isRecording) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFE53935))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(Color(0xFFE53935))
                        )
                    }
                }

                // Camera Swap Shortcut
                IconButton(
                    onClick = { dualEngine.swapCameras() },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        .testTag("dual_flip_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FlipCameraAndroid,
                        contentDescription = "Swap Primary and Secondary",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // 6. Resolution & FPS Selection Dialog
        if (isResolutionDialogOpen) {
            AlertDialog(
                onDismissRequest = { isResolutionDialogOpen = false },
                title = {
                    Text(
                        text = "Dual Video Resolution & FPS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Supported by both cameras & hardware encoder:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text("Resolution:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        state.capability.supportedResolutions.forEach { res ->
                            val isSelected = state.config.resolution.portraitWidth == res.portraitWidth && state.config.resolution.portraitHeight == res.portraitHeight
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD54F)) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { dualEngine.setResolution(res) }
                            ) {
                                Text(
                                    text = res.label,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color(0xFFFFD54F) else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Frame Rate:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.capability.supportedFps.forEach { fps ->
                                val isSelected = state.config.fps == fps
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD54F)) else null,
                                    modifier = Modifier.clickable { dualEngine.setFps(fps) }
                                ) {
                                    Text(
                                        text = "${fps} FPS",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFFFFD54F) else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { isResolutionDialogOpen = false }) {
                        Text("Done", color = Color(0xFFFFD54F))
                    }
                }
            )
        }
    }
}
