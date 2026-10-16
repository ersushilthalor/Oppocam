package com.example.camera.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.model.CameraMode
import com.example.camera.model.MainCameraStabilizationMode
import com.example.camera.viewmodel.CameraViewModel

/**
 * Dedicated host screen for Camera Settings.
 * Encapsulates all settings state observation and event dispatching,
 * decoupling settings completely from the main camera viewfinder pipeline.
 */
@Composable
fun CameraSettingsHost(
    viewModel: CameraViewModel,
    onOpenCustomUiStudio: () -> Unit,
    onOpenPipelineStudio: () -> Unit,
    onOpenBeforeAfter: () -> Unit,
    onOpenGalleryChooser: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cameraMode by viewModel.cameraMode.collectAsStateWithLifecycle()
    val capabilities by viewModel.engine.capabilities.collectAsStateWithLifecycle()
    val displayedLenses by viewModel.displayedLenses.collectAsStateWithLifecycle()
    val selectedLens by viewModel.selectedLens.collectAsStateWithLifecycle()
    val selectedPhotoResolution by viewModel.engine.selectedPhotoResolution.collectAsStateWithLifecycle()
    val selectedVideoResolution by viewModel.engine.selectedVideoResolution.collectAsStateWithLifecycle()
    val photoMegapixelMode by viewModel.photoMegapixelMode.collectAsStateWithLifecycle()
    val isRefocusPhotoEnabled by viewModel.isRefocusPhotoEnabled.collectAsStateWithLifecycle()
    val refocusFrameCount by viewModel.refocusFrameCount.collectAsStateWithLifecycle()

    androidx.compose.runtime.CompositionLocalProvider(
        com.example.camera.ui.components.LocalFloatingWindowAppearance provides floatingWindowAppearance
    ) {
        SettingsDrawer(
        isOpen = true,
        cameraMode = cameraMode,
        capabilities = capabilities,
        availableLenses = displayedLenses,
        selectedLens = selectedLens,
        selectedPhotoResolution = selectedPhotoResolution,
        selectedVideoResolution = selectedVideoResolution,
        photoMegapixelMode = photoMegapixelMode,
        isRefocusPhotoEnabled = isRefocusPhotoEnabled,
        refocusFrameCount = refocusFrameCount,
        isMotionPhotoEnabled = isMotionPhotoEnabled,
        motionPhotoDuration = motionPhotoDuration,
        onMotionPhotoToggle = { viewModel.setMotionPhotoEnabled(it) },
        onMotionPhotoDurationSelect = { viewModel.setMotionPhotoDuration(it) },
        isUltraFastShutterEnabled = isUltraFastShutterEnabled,
        ultraFastShutterFps = ultraFastShutterFps,
        onUltraFastShutterToggle = { viewModel.setUltraFastShutterEnabled(it) },
        onUltraFastShutterFpsChange = { viewModel.setUltraFastShutterFps(it) },
        zoomPresetsMode = zoomPresetsMode,
        customZoomPresetsStr = customZoomPresetsStr,
        onZoomPresetsModeSelect = { viewModel.setZoomPresetsMode(it) },
        onCustomZoomPresetsChange = { viewModel.setCustomZoomPresets(it) },
        videoFps = videoFps,
        videoBitrate = videoBitrate,
        isVideoStabilizationEnabled = isVideoStabilizationEnabled,
        isHorizontalLockSettingEnabled = isHorizontalLockSettingEnabled,
        onHorizontalLockSettingToggle = { viewModel.setHorizontalLockSettingEnabled(it) },
        isDollyZoomSettingEnabled = isDollyZoomSettingEnabled,
        onDollyZoomSettingToggle = { viewModel.setDollyZoomSettingEnabled(it) },
        isAudioEnabled = isAudioEnabled,
        isRawEnabled = isRawEnabled,
        saveSelfieAsPreviewed = saveSelfieAsPreviewed,
        gridType = gridType,
        cinemaConfig = cinemaConfig,
        cinemaCapabilities = cinemaCapabilities,
        viewfinderResolution = viewfinderResolution,
        hybridStabilizationConfig = hybridStabilizationConfig,
        tapFocusConfig = tapFocusConfig,
        mainCameraStabilizationMode = mainCameraStabilizationMode,
        onMainCameraStabilizationModeSelected = { viewModel.setMainCameraStabilizationMode(it) },
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
        selectedPhotoFilter = selectedPhotoFilter,
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
        onAudioToggle = { viewModel.toggleAudio() },
        onRawToggle = { viewModel.toggleRawCapture() },
        onSaveSelfieAsPreviewedToggle = { viewModel.setSaveSelfieAsPreviewed(it) },
        onGridTypeSelected = { viewModel.setGridType(it) },
        onCinemaConfigChange = { viewModel.updateCinemaConfig(it) },
        onOpenCustomUiStudio = onOpenCustomUiStudio,
        onOpenPipelineStudio = onOpenPipelineStudio,
        onOpenBeforeAfter = onOpenBeforeAfter,
        onOpenGalleryChooser = onOpenGalleryChooser,
        onViewfinderCornerRadiusChange = { viewModel.setViewfinderCornerRadius(it) },
        onDismiss = onDismiss
    )
}
}
