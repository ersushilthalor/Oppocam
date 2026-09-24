package com.example.camera.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.Lock
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

    val exposureCompensation by viewModel.exposureCompensation.collectAsStateWithLifecycle()
    val manualIso by viewModel.manualIso.collectAsStateWithLifecycle()
    val manualShutterSpeedNs by viewModel.manualShutterSpeedNs.collectAsStateWithLifecycle()
    val whiteBalance by viewModel.whiteBalance.collectAsStateWithLifecycle()
    val focusMode by viewModel.focusMode.collectAsStateWithLifecycle()
    val manualFocusDistance by viewModel.manualFocusDistance.collectAsStateWithLifecycle()
    val isAeLocked by viewModel.isAeLocked.collectAsStateWithLifecycle()
    val isAfLocked by viewModel.isAfLocked.collectAsStateWithLifecycle()
    val isRawEnabled by viewModel.isRawCaptureEnabled.collectAsStateWithLifecycle()
    val isVideoStabilizationEnabled by viewModel.isVideoStabilizationEnabled.collectAsStateWithLifecycle()
    val videoBitrate by viewModel.videoBitrateOption.collectAsStateWithLifecycle()
    val videoFps by viewModel.videoFps.collectAsStateWithLifecycle()
    val colorProfile by viewModel.colorProfile.collectAsStateWithLifecycle()
    val isAudioEnabled by viewModel.isAudioEnabled.collectAsStateWithLifecycle()
    val currentVideoQuality by viewModel.currentVideoQuality.collectAsStateWithLifecycle()
    val viewfinderResolution by viewModel.viewfinderResolution.collectAsStateWithLifecycle()
    val currentZoom by viewModel.currentZoom.collectAsStateWithLifecycle()
    val displayedLenses by viewModel.displayedLenses.collectAsStateWithLifecycle()
    val selectedLens by viewModel.selectedLens.collectAsStateWithLifecycle()
    val zoomPresetsMode by viewModel.zoomPresetsMode.collectAsStateWithLifecycle()
    val customZoomPresetsStr by viewModel.customZoomPresetsStr.collectAsStateWithLifecycle()
    val activeZoomPresets by viewModel.activeZoomPresets.collectAsStateWithLifecycle()

    val nightConfig by viewModel.nightConfig.collectAsStateWithLifecycle()
    val nightProgress by viewModel.nightProgress.collectAsStateWithLifecycle()
    val hybridStabilizationConfig by viewModel.hybridStabilizationConfig.collectAsStateWithLifecycle()
    val tapFocusConfig by viewModel.tapFocusConfig.collectAsStateWithLifecycle()
    val uiCustomizationState by viewModel.uiCustomizationState.collectAsStateWithLifecycle()
    val activeLayoutConfig = remember(uiCustomizationState, cameraMode) {
        uiCustomizationState.getConfigForMode(cameraMode)
    }

    val selectedPhotoFilter by viewModel.selectedPhotoFilter.collectAsStateWithLifecycle()
    val isPhotoFilterBarOpen by viewModel.isPhotoFilterBarOpen.collectAsStateWithLifecycle()
    val isPortraitStyleBarOpen by viewModel.isPortraitStyleBarOpen.collectAsStateWithLifecycle()

    val videoCodec by viewModel.videoCodec.collectAsStateWithLifecycle()
    val jpegQuality by viewModel.jpegQuality.collectAsStateWithLifecycle()
    val volumeKeyAction by viewModel.volumeKeyAction.collectAsStateWithLifecycle()
    val doubleTapAction by viewModel.doubleTapAction.collectAsStateWithLifecycle()
    val shutterFeedback by viewModel.shutterFeedback.collectAsStateWithLifecycle()
    val antibandingMode by viewModel.antibandingMode.collectAsStateWithLifecycle()
    val windNoiseReduction by viewModel.windNoiseReduction.collectAsStateWithLifecycle()
    val audioSource by viewModel.audioSource.collectAsStateWithLifecycle()
    val horizonLeveler by viewModel.horizonLeveler.collectAsStateWithLifecycle()
    val viewfinderFps by viewModel.viewfinderFps.collectAsStateWithLifecycle()
    val thermalProtection by viewModel.thermalProtection.collectAsStateWithLifecycle()
    val isAutoHdrEnabled by viewModel.isAutoHdrEnabled.collectAsStateWithLifecycle()
    val isAiAutoFramingEnabled by viewModel.isAiAutoFramingEnabled.collectAsStateWithLifecycle()
    val isHighQualityZoomEnabled by viewModel.isHighQualityZoomEnabled.collectAsStateWithLifecycle()
    val zoomProcessingQuality by viewModel.zoomProcessingQuality.collectAsStateWithLifecycle()
    val isZoomProcessing by viewModel.isZoomProcessing.collectAsStateWithLifecycle()
    val zoomProgress by viewModel.zoomProgress.collectAsStateWithLifecycle()
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
    val isBeforeAfterOpen by viewModel.isBeforeAfterOpen.collectAsStateWithLifecycle()
    val latestPipelineCapture by viewModel.latestPipelineCapture.collectAsStateWithLifecycle()

    var isCustomUiStudioOpen by remember { mutableStateOf(false) }

    if (cameraMode == CameraMode.AI_SUBJECT_TRACKING) {
        com.example.camera.tracking.ui.AiSubjectTrackingScreen(
            onBack = {
                viewModel.setCameraMode(CameraMode.PHOTO)
            }
        )
        return
    }

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

    val isAnyWindowOpen = isPhotoFilterBarOpen || isPortraitStyleBarOpen ||
            isCinemaSettingsOpen || isManualProOpen || isMoreModesOpen ||
            (cameraMode == CameraMode.PORTRAIT && isPortraitSettingsOpen) ||
            isVideoSettingsPanelOpen || isVideoAdjustmentsOpen || isSettingsOpen || isGallerySelectionDialogOpen ||
            isCustomUiStudioOpen || isPipelineSheetOpen || isBeforeAfterOpen

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
            val maxViewfinderZoom = kotlin.math.max(capabilities.maxZoom, 10.0f)

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
                rec2020AutoToneParams = rec2020AutoToneParams,
                floatingWindowBlurStrength = floatingWindowAppearance.blurStrength,
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
                    if (preset == 0.5f) {
                        val ultraLens = displayedLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                        if (ultraLens != null) viewModel.selectLens(ultraLens)
                    } else if (preset == 1.0f) {
                        val mainLens = displayedLenses.firstOrNull { it.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.WIDE && !it.isZoomPreset }
                            ?: displayedLenses.firstOrNull { it.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK }
                        if (mainLens != null) viewModel.selectLens(mainLens)
                    } else if (preset in 2.0f..3.0f) {
                        val tele = displayedLenses.firstOrNull { (it.lensType == LensType.TELEPHOTO || it.lensType == LensType.TELEPHOTO_3X) && it.isPhysical }
                        if (tele != null) viewModel.selectLens(tele)
                    }
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
                modifier = Modifier.fillMaxSize()
            )

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

        // 1g. Fast Shutter Live Frame Counter Overlay (Exact center of Viewfinder)
        FastShutterLiveCounter(
            visible = isUltraFastShutterEnabled && isFastShutterHolding && cameraMode == CameraMode.PHOTO,
            frameCount = fastShutterFrameCount,
            modifier = Modifier.align(Alignment.Center)
        )


        // Subtle High-Quality Zoom processing pill (non-blocking indicator)
        if (isZoomProcessing) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCC111827),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 74.dp)
                    .testTag("zoom_processing_indicator")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { zoomProgress },
                        modifier = Modifier.size(13.dp),
                        color = Color(0xFF60A5FA),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Enhancing Zoom Clarity ${(zoomProgress * 100).toInt()}%",
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
            selectedPortraitStyle = portraitConfig.selectedStyle,
            onPortraitStyleClick = { viewModel.togglePortraitStyleBar() },
            onCinemaSettingsClick = { viewModel.toggleCinemaSettings() },
            onCinemaEvChange = { ev ->
                viewModel.updateCinemaConfig(cinemaConfig.copy(exposureCompensation = ev))
            },
            onVideoQualityClick = { viewModel.cycleVideoQuality() },
            onVideoSettingsClick = { viewModel.toggleVideoSettingsPanel() },
            onToggleMegapixelMode = { viewModel.togglePhotoMegapixelMode() },
            isVideoAdjustmentsOpen = isVideoAdjustmentsOpen,
            hasActiveVideoAdjustments = !videoAdjustments.isDefault,
            onVideoAdjustmentsClick = { viewModel.toggleVideoAdjustmentsOpen() },
            onFlashClick = { viewModel.cycleFlashMode() },
            onTimerClick = { viewModel.cycleTimerMode() },
            onGridClick = { viewModel.cycleGridType() },
            onRawClick = { viewModel.toggleRawCapture() },
            onSettingsClick = { viewModel.setSettingsOpen(true) },
            layoutConfig = activeLayoutConfig,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 2b. Floating Frosted Video Settings Panel (Resolution & Frame Rate)
        if (cameraMode == CameraMode.VIDEO) {
            FloatingVideoSettingsPanel(
                isOpen = isVideoSettingsPanelOpen,
                currentResolution = selectedVideoResolution,
                currentFps = videoFps,
                isUltraStabilizationEnabled = hybridStabilizationConfig.isUltraStabilizationEnabled,
                onResolutionSelected = { res ->
                    viewModel.selectVideoResolution(res)
                },
                onFpsSelected = { fps ->
                    viewModel.setVideoFps(fps)
                },
                onUltraStabilizationToggle = {
                    viewModel.toggleUltraStabilization()
                },
                onDismiss = { viewModel.setVideoSettingsPanelOpen(false) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
            )
        }

        // 3. Manual Pro Control Bar (Slide-up above bottom controls in Photo/Video modes)
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
                isAeLocked = isAeLocked,
                isAfLocked = isAfLocked,
                onTabSelected = { viewModel.setActiveProTab(it) },
                onExposureChange = { viewModel.setExposureCompensation(it) },
                onIsoChange = { viewModel.setManualIso(it) },
                onShutterChange = { viewModel.setManualShutterSpeedNs(it) },
                onWbChange = { viewModel.setWhiteBalance(it) },
                onFocusModeChange = { viewModel.setFocusMode(it) },
                onFocusDistanceChange = { viewModel.setManualFocusDistance(it) },
                onColorProfileChange = { viewModel.setColorProfile(it) },
                onToggleAeLock = { viewModel.toggleAeLock() },
                onToggleAfLock = { viewModel.toggleAfLock() },
                onClose = { viewModel.setManualProOpen(false) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 215.dp)
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

        // 3d0. Dedicated Video Adjustments Panel (Normal Video Mode, matching reference design)
        AnimatedVisibility(
            visible = cameraMode == CameraMode.VIDEO && isVideoAdjustmentsOpen,
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

        // 3e. Dedicated More Modes Drawer
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
            layoutConfig = activeLayoutConfig.copy(
                showZoomCapsule = activeLayoutConfig.showZoomCapsule && !isAnyWindowOpen
            ),
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 6. Settings Bottom Sheet (Light Mode, Categorized 19 Categories)
        SettingsDrawer(
            isOpen = isSettingsOpen,
            cameraMode = cameraMode,
            capabilities = capabilities,
            availableLenses = displayedLenses,
            selectedLens = selectedLens,
            selectedPhotoResolution = selectedPhotoResolution,
            selectedVideoResolution = selectedVideoResolution,
            photoMegapixelMode = photoMegapixelMode,
            isRefocusPhotoEnabled = isRefocusPhotoEnabled,
            refocusFrameCount = refocusFrameCount,
            isUltraFastShutterEnabled = isUltraFastShutterEnabled,
            ultraFastShutterFps = ultraFastShutterFps,
            onUltraFastShutterToggle = { viewModel.setUltraFastShutterEnabled(it) },
            onUltraFastShutterFpsChange = { viewModel.setUltraFastShutterFps(it) },
            isHighQualityZoomEnabled = isHighQualityZoomEnabled,
            zoomProcessingQuality = zoomProcessingQuality,
            zoomPresetsMode = zoomPresetsMode,
            customZoomPresetsStr = customZoomPresetsStr,
            onZoomPresetsModeSelect = { viewModel.setZoomPresetsMode(it) },
            onCustomZoomPresetsChange = { viewModel.setCustomZoomPresets(it) },
            videoFps = videoFps,
            videoBitrate = videoBitrate,
            isVideoStabilizationEnabled = isVideoStabilizationEnabled,
            isAudioEnabled = isAudioEnabled,
            isRawEnabled = isRawEnabled,
            saveSelfieAsPreviewed = saveSelfieAsPreviewed,
            gridType = gridType,
            cinemaConfig = cinemaConfig,
            cinemaCapabilities = cinemaCapabilities,
            viewfinderResolution = viewfinderResolution,
            hybridStabilizationConfig = hybridStabilizationConfig,
            nightConfig = nightConfig,
            tapFocusConfig = tapFocusConfig,
            mainCameraStabilizationMode = remember(isVideoStabilizationEnabled, hybridStabilizationConfig) {
                when {
                    !isVideoStabilizationEnabled -> com.example.camera.model.MainCameraStabilizationMode.OFF
                    hybridStabilizationConfig.isUltraStabilizationEnabled -> com.example.camera.model.MainCameraStabilizationMode.ULTRA
                    hybridStabilizationConfig.isEisOnly -> com.example.camera.model.MainCameraStabilizationMode.EIS_ONLY
                    hybridStabilizationConfig.isHybridEnabled -> com.example.camera.model.MainCameraStabilizationMode.HYBRID_OIS_EIS
                    hybridStabilizationConfig.isOisPreferred && !hybridStabilizationConfig.isEisPreferred -> com.example.camera.model.MainCameraStabilizationMode.OIS_ONLY
                    else -> com.example.camera.model.MainCameraStabilizationMode.HYBRID_OIS_EIS
                }
            },
            onMainCameraStabilizationModeSelected = { viewModel.setMainCameraStabilizationMode(it) },
            // Extended Settings States
            videoCodec = videoCodec,
            jpegQuality = jpegQuality,
            volumeKeyAction = volumeKeyAction,
            doubleTapAction = doubleTapAction,
            shutterFeedback = shutterFeedback,
            antibandingMode = antibandingMode,
            windNoiseReduction = windNoiseReduction,
            audioSource = audioSource,
            horizonLeveler = horizonLeveler,
            viewfinderFps = viewfinderFps,
            thermalProtection = thermalProtection,
            isAutoHdrEnabled = isAutoHdrEnabled,
            isAiAutoFramingEnabled = isAiAutoFramingEnabled,
            currentZoom = currentZoom,
            exposureCompensation = exposureCompensation,
            manualIso = manualIso,
            manualShutterSpeedNs = manualShutterSpeedNs,
            focusMode = focusMode,
            manualFocusDistance = manualFocusDistance,
            portraitConfig = portraitConfig,
            selectedPhotoFilter = selectedPhotoFilter,
            // Extended Callbacks
            onVideoCodecSelected = { viewModel.setVideoCodec(it) },
            onJpegQualitySelected = { viewModel.setJpegQuality(it) },
            onVolumeKeyActionSelected = { viewModel.setVolumeKeyAction(it) },
            onDoubleTapActionSelected = { viewModel.setDoubleTapAction(it) },
            onShutterFeedbackSelected = { viewModel.setShutterFeedback(it) },
            onAntibandingModeSelected = { viewModel.setAntibandingMode(it) },
            onWindNoiseReductionToggle = { viewModel.setWindNoiseReduction(it) },
            onAudioSourceSelected = { viewModel.setAudioSource(it) },
            onHorizonLevelerToggle = { viewModel.setHorizonLeveler(it) },
            onViewfinderFpsSelected = { viewModel.setViewfinderFps(it) },
            onThermalProtectionToggle = { viewModel.setThermalProtection(it) },
            onAutoHdrToggle = { viewModel.setAutoHdrEnabled(it) },
            onAiAutoFramingToggle = { viewModel.setAiAutoFramingEnabled(it) },
            onZoomChange = { viewModel.setZoom(it, isPresetTap = false) },
            onExposureCompensationChange = { viewModel.setExposureCompensation(it) },
            onManualIsoChange = { viewModel.setManualIso(it) },
            onManualShutterSpeedChange = { viewModel.setManualShutterSpeed(it) },
            onFocusModeChange = { viewModel.setFocusMode(it) },
            onManualFocusDistanceChange = { viewModel.setManualFocusDistance(it) },
            onPortraitConfigChange = { viewModel.setPortraitConfig(it) },
            onPhotoFilterSelected = { viewModel.setPhotoFilter(it) },
            onResetAllSettings = { viewModel.resetAllSettings() },
            // UI Customization callbacks
            uiCustomizationState = uiCustomizationState,
            onSelectTemplate = { viewModel.selectUiTemplate(it) },
            onUpdateGlobalLayoutConfig = { viewModel.updateGlobalLayoutConfig(it) },
            onUpdateModeLayoutConfig = { mode, config -> viewModel.updateModeLayoutConfig(mode, config) },
            onResetModeLayoutConfig = { mode -> viewModel.resetModeLayoutToGlobal(mode) },
            onSaveCustomPreset = { name, config -> viewModel.saveCustomPreset(name, config) },
            onLoadCustomPreset = { viewModel.loadCustomPreset(it) },
            onDeleteCustomPreset = { viewModel.deleteCustomPreset(it) },
            onResetAllToTemplate = { viewModel.resetLayoutToTemplate(it) },
            onLensSelected = { viewModel.selectLens(it) },
            onForceDeepScan = { viewModel.forceDeepScanLenses() },
            onPhotoResolutionSelected = { viewModel.selectPhotoResolution(it) },
            onPhotoMegapixelModeSelected = { viewModel.setPhotoMegapixelMode(it) },
            onRefocusPhotoToggle = { viewModel.setRefocusPhotoEnabled(it) },
            onRefocusFrameCountChange = { viewModel.setRefocusFrameCount(it) },
            onHighQualityZoomToggle = { viewModel.setHighQualityZoomEnabled(it) },
            onZoomProcessingQualitySelect = { viewModel.setZoomProcessingQuality(it) },
            onVideoResolutionSelected = { viewModel.selectVideoResolution(it) },
            onViewfinderResolutionSelected = { viewModel.setViewfinderResolution(it) },
            onVideoFpsSelected = { viewModel.setVideoFps(it) },
            onVideoBitrateSelected = { viewModel.setVideoBitrate(it) },
            onStabilizationToggle = { viewModel.setVideoStabilization(it) },
            onHybridStabilizationChange = { viewModel.setHybridStabilizationConfig(it) },
            onOisToggle = { viewModel.setOisPreferred(it) },
            onUltraStabilizationToggle = { viewModel.toggleUltraStabilization() },
            onNightConfigChange = { viewModel.setNightConfig(it) },
            onTapFocusConfigChange = { viewModel.setTapFocusConfig(it) },
            onAudioToggle = { viewModel.toggleAudio() },
            onRawToggle = { viewModel.toggleRawCapture() },
            onSaveSelfieAsPreviewedToggle = { viewModel.setSaveSelfieAsPreviewed(it) },
            onGridTypeSelected = { viewModel.setGridType(it) },
            onCinemaConfigChange = { viewModel.updateCinemaConfig(it) },
            onOpenCustomUiStudio = { isCustomUiStudioOpen = true },
            isCustomPipelineEnabled = isCustomPipelineEnabled,
            activePipelinePreset = activePipelinePreset,
            onCustomPipelineToggle = { viewModel.toggleCustomPipelineEnabled(it) },
            onSelectPipelinePreset = { viewModel.selectPipelinePreset(it) },
            onOpenPipelineStudio = {
                viewModel.setSettingsOpen(false)
                viewModel.setPipelineSheetOpen(true)
            },
            onOpenBeforeAfter = {
                viewModel.setSettingsOpen(false)
                viewModel.setBeforeAfterOpen(true)
            },
            instantSwitchState = instantSwitchState,
            onShowUltraWidePreviewToggle = { viewModel.setShowUltraWidePreview(it) },
            onKeepFrontCameraReadyToggle = { viewModel.setKeepFrontCameraReady(it) },
            onShowFrontCameraPreviewToggle = { viewModel.setShowFrontCameraPreview(it) },
            floatingWindowAppearance = floatingWindowAppearance,
            onFloatingWindowTransparencyChange = { viewModel.setFloatingWindowTransparency(it) },
            onFloatingWindowBlurStrengthChange = { viewModel.setFloatingWindowBlurStrength(it) },
            onFloatingWindowAppearanceChange = { viewModel.setFloatingWindowAppearance(it) },
            onResetFloatingWindowAppearance = { viewModel.resetFloatingWindowAppearance() },
            preferredGalleryPackage = preferredGalleryPackage,
            onOpenGalleryChooser = { viewModel.setGallerySelectionDialogOpen(true) },
            onDismiss = {
                viewModel.setSettingsOpen(false)
                if (cameraMode == CameraMode.MORE) {
                    viewModel.setCameraMode(CameraMode.PHOTO)
                }
            }
        )

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
