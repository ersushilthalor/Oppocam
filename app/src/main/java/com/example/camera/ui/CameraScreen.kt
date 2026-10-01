package com.example.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import com.example.camera.ui.components.FastShutterLiveCounter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.model.*
import com.example.camera.ui.components.FrostedGlassBox
import com.example.camera.ui.components.VideoAdjustmentsPanel
import com.example.camera.ui.components.VideoAdjustmentsViewfinderOverlay
import com.example.camera.viewmodel.CameraViewModel

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Permission handling
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission || !hasAudioPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            viewModel.safeInitializeCamera()
        }
    }

    if (!hasCameraPermission) {
        CameraPermissionPrompt(
            onRequestPermission = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    )
                )
            },
            modifier = modifier
        )
        return
    }

    // Engine & VM States
    val cameraMode by viewModel.cameraMode.collectAsStateWithLifecycle()

    if (cameraMode == CameraMode.AI_SUBJECT_TRACKING) {
        BackHandler {
            viewModel.setCameraMode(CameraMode.PHOTO)
        }
        com.example.camera.tracking.ui.AiSubjectTrackingScreen(
            onBack = {
                viewModel.setCameraMode(CameraMode.PHOTO)
            }
        )
        return
    }
    val capabilities by viewModel.engine.capabilities.collectAsStateWithLifecycle()
    val selectedPhotoResolution by viewModel.engine.selectedPhotoResolution.collectAsStateWithLifecycle()
    val selectedVideoResolution by viewModel.engine.selectedVideoResolution.collectAsStateWithLifecycle()
    val previewAspectRatio by viewModel.engine.previewAspectRatio.collectAsStateWithLifecycle()
    val previewBufferSize by viewModel.engine.previewBufferSize.collectAsStateWithLifecycle()
    val sensorOrientation by viewModel.engine.sensorOrientation.collectAsStateWithLifecycle()
    val storageStats by viewModel.engine.storageStats.collectAsStateWithLifecycle()
    val isRecordingVideo by viewModel.engine.isRecordingVideo.collectAsStateWithLifecycle()
    val videoDurationSeconds by viewModel.engine.videoDurationSeconds.collectAsStateWithLifecycle()
    val isCapturing by viewModel.engine.isCapturing.collectAsStateWithLifecycle()
    val lastCapturedMedia by viewModel.engine.lastCapturedMedia.collectAsStateWithLifecycle()

    val portraitConfig by viewModel.portraitConfig.collectAsStateWithLifecycle()
    val portraitProcessingState by viewModel.portraitProcessingState.collectAsStateWithLifecycle()
    val isPortraitSettingsOpen by viewModel.isPortraitSettingsOpen.collectAsStateWithLifecycle()
    val saveSelfieAsPreviewed by viewModel.saveSelfieAsPreviewed.collectAsStateWithLifecycle()
    val photoMegapixelMode by viewModel.photoMegapixelMode.collectAsStateWithLifecycle()
    val isRefocusPhotoEnabled by viewModel.isRefocusPhotoEnabled.collectAsStateWithLifecycle()
    val refocusFrameCount by viewModel.refocusFrameCount.collectAsStateWithLifecycle()
    val isUltraFastShutterEnabled by viewModel.isUltraFastShutterEnabled.collectAsStateWithLifecycle()
    val ultraFastShutterFps by viewModel.ultraFastShutterFps.collectAsStateWithLifecycle()
    val fastShutterFrameCount by viewModel.fastShutterFrameCount.collectAsStateWithLifecycle()
    val isFastShutterHolding by viewModel.isFastShutterHolding.collectAsStateWithLifecycle()
    val isVideoSettingsPanelOpen by viewModel.isVideoSettingsPanelOpen.collectAsStateWithLifecycle()

    val cinemaConfig by viewModel.cinemaConfig.collectAsStateWithLifecycle()
    val cinemaCapabilities by viewModel.cinemaCapabilities.collectAsStateWithLifecycle()
    val rec2020AutoToneParams by viewModel.rec2020AutoToneParams.collectAsStateWithLifecycle()
    val isCinemaSettingsOpen by viewModel.isCinemaSettingsOpen.collectAsStateWithLifecycle()
    val isMoreModesOpen by viewModel.isMoreModesOpen.collectAsStateWithLifecycle()
    val videoAdjustments by viewModel.videoAdjustments.collectAsStateWithLifecycle()
    val isVideoAdjustmentsOpen by viewModel.isVideoAdjustmentsOpen.collectAsStateWithLifecycle()
    val isHorizonLockEnabled by viewModel.isHorizonLockEnabled.collectAsStateWithLifecycle()
    val horizonRollDegrees by viewModel.horizonRollDegrees.collectAsStateWithLifecycle()
    val isHorizontalLockSettingEnabled by viewModel.isHorizontalLockSettingEnabled.collectAsStateWithLifecycle()
    val horizonMotionOffset by viewModel.horizonMotionOffset.collectAsStateWithLifecycle()

    val flashMode by viewModel.flashMode.collectAsStateWithLifecycle()
    val timerMode by viewModel.timerMode.collectAsStateWithLifecycle()
    val activeTimerCountdown by viewModel.activeTimerCountdown.collectAsStateWithLifecycle()
    val gridType by viewModel.gridType.collectAsStateWithLifecycle()
    val isManualProOpen by viewModel.isManualProOpen.collectAsStateWithLifecycle()
    val activeProTab by viewModel.activeProTab.collectAsStateWithLifecycle()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsStateWithLifecycle()
    val isGallerySelectionDialogOpen by viewModel.isGallerySelectionDialogOpen.collectAsStateWithLifecycle()
    val preferredGalleryPackage by viewModel.preferredGalleryPackage.collectAsStateWithLifecycle()
    val focusRingPoint by viewModel.focusRingPoint.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()
    val isEvControlOpen by viewModel.isEvControlOpen.collectAsStateWithLifecycle()

    if (isEvControlOpen) {
        BackHandler {
            viewModel.setEvControlOpen(false)
        }
    }

    val exposureCompensation by viewModel.exposureCompensation.collectAsStateWithLifecycle()
    val manualIso by viewModel.manualIso.collectAsStateWithLifecycle()
    val manualShutterSpeedNs by viewModel.manualShutterSpeedNs.collectAsStateWithLifecycle()
    val whiteBalance by viewModel.whiteBalance.collectAsStateWithLifecycle()
    val focusMode by viewModel.focusMode.collectAsStateWithLifecycle()
    val manualFocusDistance by viewModel.manualFocusDistance.collectAsStateWithLifecycle()
    val isAeLocked by viewModel.isAeLocked.collectAsStateWithLifecycle()
    val isAfLocked by viewModel.isAfLocked.collectAsStateWithLifecycle()
    val isRawEnabled by viewModel.isRawCaptureEnabled.collectAsStateWithLifecycle()
    val videoFps by viewModel.videoFps.collectAsStateWithLifecycle()
    val colorProfile by viewModel.colorProfile.collectAsStateWithLifecycle()
    val isAudioEnabled by viewModel.isAudioEnabled.collectAsStateWithLifecycle()
    val currentVideoQuality by viewModel.currentVideoQuality.collectAsStateWithLifecycle()
    val currentZoom by viewModel.currentZoom.collectAsStateWithLifecycle()
    val displayedLenses by viewModel.displayedLenses.collectAsStateWithLifecycle()
    val selectedLens by viewModel.selectedLens.collectAsStateWithLifecycle()
    val activeZoomPresets by viewModel.activeZoomPresets.collectAsStateWithLifecycle()

    val proSaturation by viewModel.proSaturation.collectAsStateWithLifecycle()
    val proContrast by viewModel.proContrast.collectAsStateWithLifecycle()
    val proHighlights by viewModel.proHighlights.collectAsStateWithLifecycle()
    val proShadows by viewModel.proShadows.collectAsStateWithLifecycle()
    val proSharpness by viewModel.proSharpness.collectAsStateWithLifecycle()
    val proNoiseReduction by viewModel.proNoiseReduction.collectAsStateWithLifecycle()

    val nightConfig by viewModel.nightConfig.collectAsStateWithLifecycle()
    val nightProgress by viewModel.nightProgress.collectAsStateWithLifecycle()
    val hybridStabilizationConfig by viewModel.hybridStabilizationConfig.collectAsStateWithLifecycle()
    val uiCustomizationState by viewModel.uiCustomizationState.collectAsStateWithLifecycle()
    val activeLayoutConfig = remember(uiCustomizationState, cameraMode) {
        uiCustomizationState.getConfigForMode(cameraMode)
    }

    val selectedPhotoFilter by viewModel.selectedPhotoFilter.collectAsStateWithLifecycle()
    val isPhotoFilterBarOpen by viewModel.isPhotoFilterBarOpen.collectAsStateWithLifecycle()
    val isPortraitStyleBarOpen by viewModel.isPortraitStyleBarOpen.collectAsStateWithLifecycle()

    val instantSwitchState by viewModel.instantSwitchState.collectAsStateWithLifecycle()

    val isUsingRearMainLens = remember(selectedLens, currentZoom) {
        selectedLens?.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK &&
                (selectedLens?.lensType == LensType.WIDE || (currentZoom in 0.85f..1.5f))
    }
    val isUsingRearLens = remember(selectedLens) {
        selectedLens?.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
    }

    val isCustomPipelineEnabled by viewModel.isCustomPipelineEnabled.collectAsStateWithLifecycle()
    val activePipelinePreset by viewModel.activePipelinePreset.collectAsStateWithLifecycle()
    val isPipelineSheetOpen by viewModel.isPipelineSheetOpen.collectAsStateWithLifecycle()
    val isPipelinePresetFloatingWindowOpen by viewModel.isPipelinePresetFloatingWindowOpen.collectAsStateWithLifecycle()
    val customPresets by viewModel.customPresets.collectAsStateWithLifecycle()
    val isBeforeAfterOpen by viewModel.isBeforeAfterOpen.collectAsStateWithLifecycle()
    val latestPipelineCapture by viewModel.latestPipelineCapture.collectAsStateWithLifecycle()

    val isMotionPhotoEnabled by viewModel.isMotionPhotoEnabled.collectAsStateWithLifecycle()
    val isMotionPhotoRecording by viewModel.isMotionPhotoRecording.collectAsStateWithLifecycle()
    val isDollyZoomSettingEnabled by viewModel.isDollyZoomSettingEnabled.collectAsStateWithLifecycle()
    val isDollyZoomActive by viewModel.isDollyZoomActive.collectAsStateWithLifecycle()
    val dollyCropState by viewModel.dollyCropState.collectAsStateWithLifecycle()
    val selectedVideoPipeline by viewModel.selectedVideoPipeline.collectAsStateWithLifecycle()
    val isCustomVideoPipelineSettingsOpen by viewModel.isCustomVideoPipelineSettingsOpen.collectAsStateWithLifecycle()
    val customVideoPipelineConfig by viewModel.customVideoPipelineConfig.collectAsStateWithLifecycle()

    var isCustomUiStudioOpen by remember { mutableStateOf(false) }
    var isManualProSliderOpen by remember { mutableStateOf(false) }
    var shutterAreaHeightDp by remember { mutableStateOf(216.dp) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    viewModel.engine.onAppBackgrounded()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (hasCameraPermission) {
                        viewModel.engine.onAppForegrounded()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val floatingWindowAppearance by viewModel.floatingWindowAppearance.collectAsStateWithLifecycle()
    val viewfinderCornerRadiusDp by viewModel.viewfinderCornerRadiusDp.collectAsStateWithLifecycle()

    val isAnyWindowOpen = isPhotoFilterBarOpen || isPortraitStyleBarOpen ||
            isCinemaSettingsOpen || isManualProOpen || isMoreModesOpen ||
            (cameraMode == CameraMode.PORTRAIT && isPortraitSettingsOpen) ||
            isVideoSettingsPanelOpen || isVideoAdjustmentsOpen || isSettingsOpen || isGallerySelectionDialogOpen ||
            isCustomUiStudioOpen || isPipelineSheetOpen || isBeforeAfterOpen || isPipelinePresetFloatingWindowOpen ||
            isEvControlOpen

    LaunchedEffect(isAnyWindowOpen) {
        com.example.camera.ui.components.BackdropBlurManager.isWindowActive = isAnyWindowOpen
    }

    CompositionLocalProvider(
        com.example.camera.ui.components.LocalFloatingWindowAppearance provides floatingWindowAppearance
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("camera_main_screen")
        ) {
            val hasRealUltraWide = remember(displayedLenses) {
                displayedLenses.any { it.lensType == LensType.ULTRAWIDE && it.isPhysical } || displayedLenses.any { it.baseZoomRatio < 0.9f }
            }
            val minViewfinderZoom = if (hasRealUltraWide) 0.5f else 1.0f
            val activeFacing = selectedLens?.facing
            val maxLensZoom = remember(displayedLenses, activeFacing) {
                displayedLenses
                    .filter { activeFacing == null || it.facing == activeFacing }
                    .maxOfOrNull { it.maxZoomRatio } ?: 20.0f
            }
            val maxViewfinderZoom = maxOf(capabilities.maxZoom, maxLensZoom, 20.0f)

            // 1. Viewfinder layer preserving exact aspect ratio without distortion
            Viewfinder(
                aspectRatio = previewAspectRatio,
                gridType = gridType,
                focusRingPoint = focusRingPoint,
                isAeLocked = isAeLocked,
                isAfLocked = isAfLocked,
                isFrontCamera = selectedLens?.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT,
                cameraMode = cameraMode,
                previewBufferSize = previewBufferSize,
                sensorOrientation = sensorOrientation,
                activePhotoFilter = selectedPhotoFilter,
                activeLut = cinemaConfig.selectedLut,
                isLutPreviewEnabled = cinemaConfig.isLutPreviewEnabled,
                cinemaConfig = cinemaConfig,
                portraitConfig = portraitConfig,
                videoAdjustments = videoAdjustments,
                selectedVideoPipeline = selectedVideoPipeline,
                rec2020AutoToneParams = rec2020AutoToneParams,
                proSaturation = proSaturation,
                proContrast = proContrast,
                proHighlights = proHighlights,
                proShadows = proShadows,
                isProModeActive = isManualProOpen,
                floatingWindowBlurStrength = floatingWindowAppearance.blurStrength,
                isSettingsOpen = isSettingsOpen,
                onSurfaceTextureAvailable = { texture ->
                    viewModel.engine.setPreviewSurfaceTexture(texture)
                },
                onSurfaceTextureSizeChanged = { texture, width, height ->
                    viewModel.engine.onViewfinderSurfaceSizeChanged(texture, width, height)
                },
                onTapToFocus = { point, normX, normY ->
                    viewModel.onTapToFocus(point, normX, normY)
                },
                onZoomChange = { zoom ->
                    viewModel.setZoom(zoom, isPresetTap = false)
                },
                currentZoom = currentZoom,
                minZoom = minViewfinderZoom,
                maxZoom = maxViewfinderZoom,
                onZoomPresetTap = { preset ->
                    viewModel.setZoom(preset, isPresetTap = true)
                },
                onExposureCompensationChange = { ev ->
                    viewModel.setExposureCompensation(ev)
                },
                onToggleLock = {
                    viewModel.toggleAeAfLock()
                },
                currentExposureCompensation = exposureCompensation,
                onFrameLuminanceStats = { stats ->
                    viewModel.engine.onFrameLuminanceStats(stats)
                },
                isMotionPhotoEnabled = isMotionPhotoEnabled,
                onMotionPhotoPreviewFrame = { bmp ->
                    viewModel.onMotionPhotoPreviewFrame(bmp)
                },
                isHorizonLockEnabled = isHorizonLockEnabled && isHorizontalLockSettingEnabled,
                horizonRollDegrees = horizonRollDegrees,
                horizonNormX = horizonMotionOffset.first,
                horizonNormY = horizonMotionOffset.second,
                isDollyZoomActive = isDollyZoomActive,
                dollyCropState = dollyCropState,
                onTapToLockDollySubject = { normX, normY ->
                    viewModel.onTapToLockDollySubject(normX, normY)
                },
                onOpenCustomPipelineSettings = {
                    viewModel.setCustomVideoPipelineSettingsOpen(true)
                },
                viewfinderCornerRadiusDp = viewfinderCornerRadiusDp,
                modifier = Modifier.fillMaxSize()
            )

        // 1b. Dedicated Horizon Lock On-Screen Indicator (Video Mode)
        if (cameraMode == CameraMode.VIDEO && isHorizontalLockSettingEnabled && isHorizonLockEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 58.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC0D0F18))
                    .border(1.dp, Color(0xFFFFD54F).copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .clickable { viewModel.toggleHorizonLock() }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .testTag("horizon_lock_badge")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ScreenLockRotation,
                        contentDescription = "Horizontal Lock",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "HORIZON LOCK",
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }
            }
        }

        // 1c. Dedicated Dolly Zoom On-Screen Indicator (Video Mode)
        if (cameraMode == CameraMode.VIDEO && isDollyZoomActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (isHorizonLockEnabled) 96.dp else 58.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC0D0F18))
                    .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .clickable { viewModel.toggleDollyZoomActive() }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .testTag("dolly_zoom_badge")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CenterFocusStrong,
                        contentDescription = "Dolly Zoom Active",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "DOLLY ZOOM • ${String.format(java.util.Locale.US, "%.1fX", dollyCropState.scaleFactor)}",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }
            }
        }

        // 1b. Normal Video Adjustments Live Spatial Effects Overlay (Grain, Vignette, Soft Light)
        if (cameraMode == CameraMode.VIDEO) {
            VideoAdjustmentsViewfinderOverlay(
                adjustments = videoAdjustments,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 1b. Cinema Viewfinder Assist Overlays (Waveform, Peaking, Zebras)
        if (cameraMode == CameraMode.CINEMA) {
            CinemaAssistOverlays(
                cinemaConfig = cinemaConfig,
                modifier = Modifier.fillMaxSize()
            )
        }


        // 1e. Computational Night Mode Long-Exposure HUD
        if (cameraMode == CameraMode.NIGHT) {
            NightModeOverlay(
                config = nightConfig,
                captureProgress = nightProgress,
                onDurationChange = { dur ->
                    viewModel.setNightConfig(nightConfig.copy(durationSeconds = dur))
                },
                onToggleNightHdr = {
                    viewModel.setNightConfig(nightConfig.copy(multiFrameFusionEnabled = !nightConfig.multiFrameFusionEnabled))
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 1f. Real-Time Viewfinder HUD & Telemetry specific to the active UI Template
        ViewfinderHudOverlay(
            templateType = uiCustomizationState.selectedTemplate,
            cameraMode = cameraMode,
            exposureCompensation = exposureCompensation,
            onExposureChange = { viewModel.setExposureCompensation(it) },
            currentZoom = currentZoom,
            onZoomChange = { viewModel.setZoom(it, isPresetTap = false) },
            manualIso = manualIso,
            manualShutterSpeedNs = manualShutterSpeedNs,
            storageStats = storageStats,
            capabilities = capabilities,
            modifier = Modifier.fillMaxSize()
        )

        // Fast Shutter Live Frame Counter Overlay (Exact center of Viewfinder)
        FastShutterLiveCounter(
            visible = isUltraFastShutterEnabled && isFastShutterHolding && cameraMode == CameraMode.PHOTO,
            frameCount = fastShutterFrameCount,
            modifier = Modifier.align(Alignment.Center)
        )

        // Motion Photo post-shutter recording pill
        if (isMotionPhotoRecording) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCC0F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 74.dp)
                    .testTag("motion_photo_recording_indicator")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF38BDF8), CircleShape)
                    )
                    Text(
                        text = "Recording motion...",
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 1g. Motorola Instant Camera Switching Picture-in-Picture Little Preview
        LittlePreviewOverlay(
            showUltraWidePreview = instantSwitchState.isShowUltraWidePreview && isUsingRearMainLens,
            showFrontPreview = instantSwitchState.isShowFrontCameraPreview && isUsingRearLens,
            ultraWideStatus = instantSwitchState.ultraWideStatus,
            frontStatus = instantSwitchState.frontStatus,
            onUltraWideSurfaceTextureAvailable = { texture ->
                viewModel.onUltraWideLittlePreviewSurfaceAvailable(texture)
            },
            onFrontSurfaceTextureAvailable = { texture ->
                viewModel.onFrontLittlePreviewSurfaceAvailable(texture)
            },
            onUltraWideClick = {
                viewModel.switchToUltraWideInstant()
            },
            onFrontClick = {
                viewModel.switchToFrontInstant()
            },
            onCloseUltraWidePreview = {
                viewModel.setShowUltraWidePreview(false)
            },
            onCloseFrontPreview = {
                viewModel.setShowFrontCameraPreview(false)
            }
        )

        // 2. Top Controls
        TopControlBar(
            cameraMode = cameraMode,
            flashMode = flashMode,
            timerMode = timerMode,
            gridType = gridType,
            isRawEnabled = isRawEnabled,
            supportsRaw = capabilities.supportsRaw,
            storageStats = storageStats,
            videoQuality = currentVideoQuality,
            videoResolution = selectedVideoResolution,
            videoFps = videoFps,
            photoMegapixelMode = photoMegapixelMode,
            cinemaConfig = cinemaConfig,
            portraitAperture = portraitConfig.simulatedAperture,
            onPortraitApertureClick = { viewModel.setPortraitSettingsOpen(!isPortraitSettingsOpen) },
            onPhotoFilterClick = { viewModel.togglePhotoFilterBar() },
            activePhotoFilter = selectedPhotoFilter,
            onPipelineClick = { viewModel.togglePipelinePresetFloatingWindow() },
            isPipelineActive = isCustomPipelineEnabled,
            selectedPortraitStyle = portraitConfig.selectedStyle,
            onPortraitStyleClick = { viewModel.togglePortraitStyleBar() },
            onCinemaSettingsClick = { viewModel.toggleCinemaSettings() },
            onCinemaEvChange = { ev ->
                viewModel.updateCinemaConfig(cinemaConfig.copy(exposureCompensation = ev))
            },
            isEvOpen = isEvControlOpen,
            onEvClick = { viewModel.toggleEvControlOpen() },
            exposureCompensation = exposureCompensation,
            evStepSize = capabilities.exposureCompensationStep,
            onVideoQualityClick = { viewModel.cycleVideoQuality() },
            onVideoSettingsClick = { viewModel.toggleVideoSettingsPanel() },
            onToggleMegapixelMode = { viewModel.togglePhotoMegapixelMode() },
            isVideoAdjustmentsOpen = isVideoAdjustmentsOpen,
            hasActiveVideoAdjustments = !videoAdjustments.isDefault,
            onVideoAdjustmentsClick = { viewModel.toggleVideoAdjustmentsOpen() },
            isHorizontalLockSettingEnabled = isHorizontalLockSettingEnabled,
            isHorizonLockEnabled = isHorizonLockEnabled,
            onToggleHorizonLock = { viewModel.toggleHorizonLock() },
            isDollyZoomSettingEnabled = isDollyZoomSettingEnabled,
            isDollyZoomActive = isDollyZoomActive,
            onToggleDollyZoom = { viewModel.toggleDollyZoomActive() },
            onFlashClick = { viewModel.cycleFlashMode() },
            onTimerClick = { viewModel.cycleTimerMode() },
            onGridClick = { viewModel.cycleGridType() },
            onRawClick = { viewModel.toggleRawCapture() },
            isMotionPhotoEnabled = isMotionPhotoEnabled,
            onMotionPhotoClick = { viewModel.toggleMotionPhoto() },
            onSettingsClick = { viewModel.setSettingsOpen(true) },
            isProActive = isManualProOpen,
            onToggleProClick = { viewModel.setManualProOpen(!isManualProOpen) },
            layoutConfig = activeLayoutConfig,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 2a. Transparent Floating EV Control Window (Video Mode & Cinema Mode)
        AnimatedVisibility(
            visible = (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.CINEMA) && isEvControlOpen,
            enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(180)) + slideInVertically(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                ),
                initialOffsetY = { -it / 2 }
            ),
            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(150)) + slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(150),
                targetOffsetY = { -it / 2 }
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 62.dp)
        ) {
            com.example.camera.ui.components.FloatingEvControlWindow(
                currentEvIndex = exposureCompensation,
                minEvIndex = capabilities.minExposureCompensation,
                maxEvIndex = capabilities.maxExposureCompensation,
                evStepSize = capabilities.exposureCompensationStep,
                accentColor = activeLayoutConfig.getComposeAccentColor(),
                onEvIndexChange = { newEv ->
                    viewModel.setExposureCompensation(newEv)
                },
                onReset = {
                    viewModel.resetExposureCompensation()
                },
                onDismiss = {
                    viewModel.setEvControlOpen(false)
                }
            )
        }

        // 2b. Floating Frosted Video Settings Panel (Resolution & Frame Rate)
        if (cameraMode == CameraMode.VIDEO) {
            FloatingVideoSettingsPanel(
                isOpen = isVideoSettingsPanelOpen,
                currentResolution = selectedVideoResolution,
                currentFps = videoFps,
                isUltraStabilizationEnabled = hybridStabilizationConfig.isUltraStabilizationEnabled,
                selectedVideoPipeline = selectedVideoPipeline,
                onResolutionSelected = { res ->
                    viewModel.selectVideoResolution(res)
                },
                onFpsSelected = { fps ->
                    viewModel.setVideoFps(fps)
                },
                onUltraStabilizationToggle = {
                    viewModel.toggleUltraStabilization()
                },
                onVideoPipelineSelected = { pipeline ->
                    viewModel.selectVideoPipeline(pipeline)
                },
                onDismiss = { viewModel.setVideoSettingsPanelOpen(false) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
            )
        }

        // 2c. Floating Custom Video Pipeline Settings Panel (Rec.2020 Log 38 ISP Controls)
        AnimatedVisibility(
            visible = cameraMode == CameraMode.VIDEO && isCustomVideoPipelineSettingsOpen,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp, start = 12.dp, end = 12.dp)
        ) {
            com.example.camera.ui.components.CustomVideoPipelineSettingsPanel(
                config = customVideoPipelineConfig,
                onConfigChange = { viewModel.updateCustomVideoPipelineConfig(it) },
                onResetDefaults = { viewModel.resetCustomVideoPipelineConfig() },
                onDismiss = { viewModel.setCustomVideoPipelineSettingsOpen(false) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 3. Manual Pro Control Bar (Cleanly positioned ABOVE shutter button and bottom controls)
        if (cameraMode != CameraMode.PORTRAIT) {
            ManualProControlBar(
                isOpen = isManualProOpen,
                activeTab = activeProTab,
                capabilities = capabilities,
                exposureCompensation = exposureCompensation,
                manualIso = manualIso,
                manualShutterSpeedNs = manualShutterSpeedNs,
                whiteBalance = whiteBalance,
                focusMode = focusMode,
                manualFocusDistance = manualFocusDistance,
                colorProfile = colorProfile,
                proSaturation = proSaturation,
                proContrast = proContrast,
                proHighlights = proHighlights,
                proShadows = proShadows,
                proSharpness = proSharpness,
                proNoiseReduction = proNoiseReduction,
                onSliderVisibilityChange = { isManualProSliderOpen = it },
                onTabSelected = { viewModel.setActiveProTab(it) },
                onExposureChange = { viewModel.setExposureCompensation(it) },
                onIsoChange = { viewModel.setManualIso(it) },
                onShutterChange = { viewModel.setManualShutterSpeedNs(it) },
                onWbChange = { viewModel.setWhiteBalance(it) },
                onFocusModeChange = { viewModel.setFocusMode(it) },
                onFocusDistanceChange = { viewModel.setManualFocusDistance(it) },
                onColorProfileChange = { viewModel.setColorProfile(it) },
                onProSaturationChange = { viewModel.setProSaturation(it) },
                onProContrastChange = { viewModel.setProContrast(it) },
                onProHighlightsChange = { viewModel.setProHighlights(it) },
                onProShadowsChange = { viewModel.setProShadows(it) },
                onProSharpnessChange = { viewModel.setProSharpness(it) },
                onProNoiseReductionChange = { viewModel.setProNoiseReduction(it) },
                onResetProAdjustments = { viewModel.resetProAdjustments() },
                onClose = {
                    viewModel.setManualProOpen(false)
                    isManualProSliderOpen = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = shutterAreaHeightDp + 12.dp)
            )
        }

        // 3b. Dedicated Portrait Mode AI Controls Panel
        AnimatedVisibility(
            visible = cameraMode == CameraMode.PORTRAIT && isPortraitSettingsOpen,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 215.dp)
        ) {
            PortraitControlBar(
                config = portraitConfig,
                processingState = portraitProcessingState,
                onBlurStrengthChanged = { viewModel.setPortraitBlurStrength(it) },
                onApertureSelected = { viewModel.setPortraitAperture(it) },
                onBokehStyleSelected = { viewModel.setPortraitBokehStyle(it) },
                onToggleFaceEnhancement = { viewModel.togglePortraitFaceEnhancement() },
                onToggleSkinTone = { viewModel.togglePortraitSkinTone() },
                onToggleOpticalBlurGuided = { viewModel.toggleOpticalBlurGuided() },
                onPortraitConfigChanged = { viewModel.setPortraitConfig(it) },
                onClose = { viewModel.setPortraitSettingsOpen(false) },
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        // 3c. Floating 'f' button in Portrait Mode
        if (cameraMode == CameraMode.PORTRAIT) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 220.dp)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (isPortraitSettingsOpen) Color(0xFFFFD54F) else Color(0xD91E1E24))
                    .border(
                        width = 1.5.dp,
                        color = if (isPortraitSettingsOpen) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.35f),
                        shape = CircleShape
                    )
                    .clickable { viewModel.setPortraitSettingsOpen(!isPortraitSettingsOpen) }
                    .testTag("portrait_f_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "f",
                    color = if (isPortraitSettingsOpen) Color.Black else Color(0xFFFFD54F),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif
                )
            }
        }

        // 3d0. Dedicated Video Adjustments Panel
        AnimatedVisibility(
            visible = cameraMode == CameraMode.VIDEO &&
                    isVideoAdjustmentsOpen,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 215.dp)
        ) {
            VideoAdjustmentsPanel(
                adjustments = videoAdjustments,
                onAdjustmentsChange = { updated ->
                    viewModel.updateVideoAdjustments(updated)
                },
                onReset = {
                    viewModel.resetVideoAdjustments()
                },
                onDismiss = {
                    viewModel.setVideoAdjustmentsOpen(false)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
            )
        }

        // 3d. Dedicated Cinema Mode Settings Window (matching reference image)
        AnimatedVisibility(
            visible = cameraMode == CameraMode.CINEMA && isCinemaSettingsOpen,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 215.dp)
        ) {
            CinemaSettingsWindow(
                config = cinemaConfig,
                capabilities = cinemaCapabilities,
                rec2020AutoToneParams = rec2020AutoToneParams,
                onConfigChange = { updatedConfig ->
                    viewModel.updateCinemaConfig(updatedConfig)
                },
                onDismissRequest = { viewModel.setCinemaSettingsOpen(false) },
                modifier = Modifier.padding(horizontal = 14.dp)
            )
        }

        // 3d2. Photo Mode Filter Selector Bar
        AnimatedVisibility(
            visible = cameraMode == CameraMode.PHOTO && isPhotoFilterBarOpen,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 215.dp)
        ) {
            PhotoFilterSelectorBar(
                selectedFilter = selectedPhotoFilter,
                onFilterSelected = { viewModel.setSelectedPhotoFilter(it) },
                onClose = { viewModel.setPhotoFilterBarOpen(false) }
            )
        }

        // 3d4. Dedicated Compact Pipeline Preset Floating Window (Photo Mode)
        AnimatedVisibility(
            visible = cameraMode == CameraMode.PHOTO && isPipelinePresetFloatingWindowOpen,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp)
        ) {
            PipelinePresetFloatingWindow(
                activePreset = activePipelinePreset,
                allPresets = remember(customPresets) {
                    com.example.camera.pipeline.model.PipelinePreset.BUILT_IN_PRESETS + customPresets
                },
                onPresetSelected = { preset ->
                    viewModel.selectPipelinePreset(preset)
                },
                isPipelineEnabled = isCustomPipelineEnabled,
                onToggleEnabled = { enabled ->
                    viewModel.toggleCustomPipelineEnabled(enabled)
                },
                onDismiss = {
                    viewModel.setPipelinePresetFloatingWindowOpen(false)
                }
            )
        }

        // 3d3. Portrait Mode Style Selector Bar
        AnimatedVisibility(
            visible = cameraMode == CameraMode.PORTRAIT && isPortraitStyleBarOpen,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 215.dp)
        ) {
            PortraitStyleSelectorBar(
                selectedStyle = portraitConfig.selectedStyle,
                onStyleSelected = { viewModel.setSelectedPortraitStyle(it) },
                onClose = { viewModel.setPortraitStyleBarOpen(false) }
            )
        }

        // 3e. Dedicated More Modes Drawer (Excluded from Pixel UI Template)
        val isPixelTemplate = uiCustomizationState.selectedTemplate == UiTemplateType.STOCK_PIXEL ||
                activeLayoutConfig.modeSelectorStyle == ModeSelectorStyle.PIXEL_PILL
        LaunchedEffect(isPixelTemplate, cameraMode, isMoreModesOpen) {
            if (isPixelTemplate) {
                if (isMoreModesOpen) viewModel.setMoreModesOpen(false)
                if (cameraMode == CameraMode.MORE) viewModel.setCameraMode(CameraMode.PHOTO)
            }
        }
        if (!isPixelTemplate) {
            MoreModesDrawer(
                isOpen = isMoreModesOpen,
                onDismissRequest = {
                    viewModel.setMoreModesOpen(false)
                    if (cameraMode == CameraMode.MORE) {
                        viewModel.setCameraMode(CameraMode.PHOTO)
                    }
                },
                onSelectProManual = {
                    viewModel.setMoreModesOpen(false)
                    viewModel.setCameraMode(CameraMode.PHOTO)
                    viewModel.setManualProOpen(true)
                },
                onSelectCinemaLog = {
                    viewModel.setMoreModesOpen(false)
                    viewModel.setCameraMode(CameraMode.CINEMA)
                },
                onSelectNight = {
                    viewModel.setMoreModesOpen(false)
                    viewModel.setCameraMode(CameraMode.NIGHT)
                },
                onSelectAiSubjectTracking = {
                    viewModel.setMoreModesOpen(false)
                    viewModel.setCameraMode(CameraMode.AI_SUBJECT_TRACKING)
                },
                onOpenSettings = {
                    viewModel.setMoreModesOpen(false)
                    if (cameraMode == CameraMode.MORE) {
                        viewModel.setCameraMode(CameraMode.PHOTO)
                    }
                    viewModel.setSettingsOpen(true)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 140.dp)
            )
        }

        // 4. Toast Notification Overlay
        AnimatedVisibility(
            visible = toastMessage != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 110.dp)
        ) {
            toastMessage?.let { msg ->
                FrostedGlassBox(
                    shape = RoundedCornerShape(20.dp),
                    elevation = 16.dp,
                    baseAlpha = 0.78f,
                    modifier = Modifier.testTag("camera_toast")
                ) {
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // 5. Bottom Controls
        BottomControlBar(
            cameraMode = cameraMode,
            currentZoom = currentZoom,
            displayedLenses = displayedLenses,
            selectedLens = selectedLens,
            activeZoomPresets = activeZoomPresets,
            capabilities = capabilities,
            onShowToast = { msg -> viewModel.showToast(msg) },
            onLensSelected = { lens -> viewModel.selectLens(lens) },
            onZoomChange = { zoom -> viewModel.setZoom(zoom, isPresetTap = false) },
            onZoomPresetTap = { preset -> viewModel.setZoom(preset, isPresetTap = true) },
            isRecordingVideo = isRecordingVideo,
            videoDurationSeconds = videoDurationSeconds,
            isCapturing = isCapturing,
            nightCaptureProgress = nightProgress,
            isManualProOpen = isManualProOpen,
            lastCapturedMedia = lastCapturedMedia,
            activeTimerCountdown = activeTimerCountdown,
            onModeSelected = { viewModel.setCameraMode(it) },
            onShutterClick = { viewModel.onMainActionButtonClick() },
            isUltraFastShutterEnabled = isUltraFastShutterEnabled,
            ultraFastFps = ultraFastShutterFps,
            onFastShutterSingleTap = { viewModel.onFastShutterSingleTap() },
            onFastShutterHoldStart = { viewModel.onFastShutterHoldStart() },
            onFastShutterHoldEnd = { viewModel.onFastShutterHoldEnd() },
            onFlipCameraClick = { viewModel.toggleCameraFacing() },
            onGalleryClick = {
                viewModel.openGallery(context)
            },
            onCinemaModeClick = { viewModel.toggleCinemaSettings() },
            onSettingsClick = { viewModel.setSettingsOpen(true) },
            onTimerClick = { viewModel.cycleTimerMode() },
            onToggleProClick = { viewModel.setManualProOpen(!isManualProOpen) },
            onSetManualProOpen = { viewModel.setManualProOpen(it) },
            onShutterAreaHeightMeasured = { shutterAreaHeightDp = it },
            selectedPhotoFilter = selectedPhotoFilter,
            onPhotoFilterClick = { viewModel.togglePhotoFilterBar() },
            isEvOpen = isEvControlOpen,
            onEvClick = { viewModel.toggleEvControlOpen() },
            exposureCompensation = exposureCompensation,
            evStepSize = capabilities.exposureCompensationStep,
            layoutConfig = activeLayoutConfig.copy(
                showZoomCapsule = activeLayoutConfig.showZoomCapsule && (!isAnyWindowOpen || (isManualProOpen && !isManualProSliderOpen)),
                zoomCapsuleVerticalOffsetDp = if (isManualProOpen) -76 else activeLayoutConfig.zoomCapsuleVerticalOffsetDp
            ),
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Overlay Settings cleanly on top of viewfinder to prevent destroying TextureView or flickering
        if (isSettingsOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0C0E14))
            ) {
                CameraSettingsHost(
                    viewModel = viewModel,
                    onOpenCustomUiStudio = {
                        viewModel.setSettingsOpen(false)
                        isCustomUiStudioOpen = true
                    },
                    onOpenPipelineStudio = {
                        viewModel.setSettingsOpen(false)
                        viewModel.setPipelineSheetOpen(true)
                    },
                    onOpenBeforeAfter = {
                        viewModel.setSettingsOpen(false)
                        viewModel.setBeforeAfterOpen(true)
                    },
                    onOpenGalleryChooser = {
                        viewModel.setGallerySelectionDialogOpen(true)
                    },
                    onDismiss = {
                        viewModel.setSettingsOpen(false)
                        if (cameraMode == CameraMode.MORE) {
                            viewModel.setCameraMode(CameraMode.PHOTO)
                        }
                    }
                )
            }
        }

        // 7. Preferred Gallery App Selection Dialog
        com.example.camera.gallery.GallerySelectionDialog(
            isOpen = isGallerySelectionDialogOpen,
            currentPreferredPackage = preferredGalleryPackage,
            onAppSelected = { pkg ->
                viewModel.setPreferredGalleryPackage(pkg)
                if (lastCapturedMedia != null) {
                    com.example.camera.gallery.GalleryLauncher.openMedia(
                        context = context,
                        uri = lastCapturedMedia!!.uri,
                        isVideo = lastCapturedMedia!!.isVideo,
                        preferredPackage = pkg
                    )
                }
            },
            onDismiss = { viewModel.setGallerySelectionDialogOpen(false) }
        )

        // 8. Dedicated Custom UI Studio & Simulator Page
        if (isCustomUiStudioOpen) {
            CustomUiStudioDialog(
                initialConfig = activeLayoutConfig,
                uiCustomizationState = uiCustomizationState,
                currentCameraMode = cameraMode,
                onDismiss = { isCustomUiStudioOpen = false },
                onApplyToCamera = { newConfig ->
                    viewModel.updateGlobalLayoutConfig(newConfig)
                    viewModel.selectUiTemplate(UiTemplateType.CUSTOM)
                    viewModel.showToast("Custom UI applied to Camera")
                },
                onSaveCustomPreset = { name, newConfig ->
                    viewModel.saveCustomPreset(name, newConfig)
                    viewModel.showToast("Preset '$name' saved")
                },
                onDeleteCustomPreset = { presetId ->
                    viewModel.deleteCustomPreset(presetId)
                    viewModel.showToast("Preset deleted")
                }
            )
        }

        // 9. Custom Image Processing Pipeline Bottom Sheet
        if (isPipelineSheetOpen) {
            com.example.camera.pipeline.ui.CustomPipelineBottomSheet(
                viewModel = viewModel,
                onDismissRequest = { viewModel.setPipelineSheetOpen(false) }
            )
        }

        // 10. Pipeline Split Before / After Comparison Dialog
        if (isBeforeAfterOpen) {
            com.example.camera.pipeline.ui.PipelineBeforeAfterDialog(
                viewModel = viewModel,
                onDismissRequest = { viewModel.setBeforeAfterOpen(false) }
            )
        }
    }
}
}

@Composable
fun CameraPermissionPrompt(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101012))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFD54F).copy(alpha = 0.15f))
                    .border(2.dp, Color(0xFFFFD54F), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(44.dp)
                )
            }

            Text(
                text = "Camera & Audio Access",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "To capture high-resolution photos and record crisp video with genuine Camera2 manual controls, grant camera and microphone permissions.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFD54F),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("grant_permissions_button")
            ) {
                Text(
                    text = "Grant Permissions",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
