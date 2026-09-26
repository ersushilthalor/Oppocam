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
    val isUltraFastShutterEnabled by viewModel.isUltraFastShutterEnabled.collectAsStateWithLifecycle()
    val ultraFastShutterFps by viewModel.ultraFastShutterFps.collectAsStateWithLifecycle()
    val isHighQualityZoomEnabled by viewModel.isHighQualityZoomEnabled.collectAsStateWithLifecycle()
    val zoomProcessingQuality by viewModel.zoomProcessingQuality.collectAsStateWithLifecycle()
    val zoomPresetsMode by viewModel.zoomPresetsMode.collectAsStateWithLifecycle()
    val customZoomPresetsStr by viewModel.customZoomPresetsStr.collectAsStateWithLifecycle()
    val videoFps by viewModel.videoFps.collectAsStateWithLifecycle()
    val videoBitrate by viewModel.videoBitrateOption.collectAsStateWithLifecycle()
    val isVideoStabilizationEnabled by viewModel.isVideoStabilizationEnabled.collectAsStateWithLifecycle()
    val isAudioEnabled by viewModel.isAudioEnabled.collectAsStateWithLifecycle()
    val isRawEnabled by viewModel.isRawCaptureEnabled.collectAsStateWithLifecycle()
    val saveSelfieAsPreviewed by viewModel.saveSelfieAsPreviewed.collectAsStateWithLifecycle()
    val gridType by viewModel.gridType.collectAsStateWithLifecycle()
    val cinemaConfig by viewModel.cinemaConfig.collectAsStateWithLifecycle()
    val cinemaCapabilities by viewModel.cinemaCapabilities.collectAsStateWithLifecycle()
    val viewfinderResolution by viewModel.viewfinderResolution.collectAsStateWithLifecycle()
    val hybridStabilizationConfig by viewModel.hybridStabilizationConfig.collectAsStateWithLifecycle()
    val nightConfig by viewModel.nightConfig.collectAsStateWithLifecycle()
    val tapFocusConfig by viewModel.tapFocusConfig.collectAsStateWithLifecycle()

    val mainCameraStabilizationMode = remember(isVideoStabilizationEnabled, hybridStabilizationConfig) {
        when {
            !isVideoStabilizationEnabled -> MainCameraStabilizationMode.OFF
            hybridStabilizationConfig.isUltraStabilizationEnabled -> MainCameraStabilizationMode.ULTRA
            hybridStabilizationConfig.isEisOnly -> MainCameraStabilizationMode.EIS_ONLY
            hybridStabilizationConfig.isHybridEnabled -> MainCameraStabilizationMode.HYBRID_OIS_EIS
            hybridStabilizationConfig.isOisPreferred && !hybridStabilizationConfig.isEisPreferred -> MainCameraStabilizationMode.OIS_ONLY
            else -> MainCameraStabilizationMode.HYBRID_OIS_EIS
        }
    }

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
    val isHdrPlusEnabled by viewModel.isHdrPlusEnabled.collectAsStateWithLifecycle()
    val hdrPlusFrameCount by viewModel.hdrPlusFrameCount.collectAsStateWithLifecycle()
    val isAiAutoFramingEnabled by viewModel.isAiAutoFramingEnabled.collectAsStateWithLifecycle()
    val currentZoom by viewModel.currentZoom.collectAsStateWithLifecycle()
    val exposureCompensation by viewModel.exposureCompensation.collectAsStateWithLifecycle()
    val manualIso by viewModel.manualIso.collectAsStateWithLifecycle()
    val manualShutterSpeedNs by viewModel.manualShutterSpeedNs.collectAsStateWithLifecycle()
    val focusMode by viewModel.focusMode.collectAsStateWithLifecycle()
    val manualFocusDistance by viewModel.manualFocusDistance.collectAsStateWithLifecycle()
    val portraitConfig by viewModel.portraitConfig.collectAsStateWithLifecycle()
    val selectedPhotoFilter by viewModel.selectedPhotoFilter.collectAsStateWithLifecycle()
    val uiCustomizationState by viewModel.uiCustomizationState.collectAsStateWithLifecycle()
    val isCustomPipelineEnabled by viewModel.isCustomPipelineEnabled.collectAsStateWithLifecycle()
    val activePipelinePreset by viewModel.activePipelinePreset.collectAsStateWithLifecycle()
    val instantSwitchState by viewModel.instantSwitchState.collectAsStateWithLifecycle()
    val floatingWindowAppearance by viewModel.floatingWindowAppearance.collectAsStateWithLifecycle()
    val preferredGalleryPackage by viewModel.preferredGalleryPackage.collectAsStateWithLifecycle()

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
        portraitConfig = portraitConfig,
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
        isHdrPlusEnabled = isHdrPlusEnabled,
        hdrPlusFrameCount = hdrPlusFrameCount,
        onHdrPlusToggle = { viewModel.setHdrPlusEnabled(it) },
        onHdrPlusFrameCountSelected = { viewModel.setHdrPlusFrameCount(it) },
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
        onOpenCustomUiStudio = onOpenCustomUiStudio,
        isCustomPipelineEnabled = isCustomPipelineEnabled,
        activePipelinePreset = activePipelinePreset,
        onCustomPipelineToggle = { viewModel.toggleCustomPipelineEnabled(it) },
        onSelectPipelinePreset = { viewModel.selectPipelinePreset(it) },
        onOpenPipelineStudio = onOpenPipelineStudio,
        onOpenBeforeAfter = onOpenBeforeAfter,
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
        onOpenGalleryChooser = onOpenGalleryChooser,
        onDismiss = onDismiss
    )
}
