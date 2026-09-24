package com.example.camera.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.data.CameraPreferences
import com.example.camera.engine.Camera2Engine
import com.example.camera.engine.PortraitProcessor
import com.example.camera.computational.video.ComputationalVideoPipeline
import com.example.camera.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProControlTab(val label: String) {
    EXPOSURE("EV"),
    ISO("ISO"),
    SHUTTER("SEC"),
    WB("WB"),
    FOCUS("FOCUS"),
    TONE("TONE")
}

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    val engine = Camera2Engine(application.applicationContext)
    private val portraitProcessor by lazy { PortraitProcessor(application.applicationContext) }
    private val preferences = CameraPreferences(application.applicationContext)

    val dollyZoomState: StateFlow<DollyZoomState> = engine.dollyZoomEngine.dollyState
    val lastCapturedMedia: StateFlow<CapturedMedia?> = engine.lastCapturedMedia

    // Mode & Base State must be initialized before combined flows
    private val _cameraMode = MutableStateFlow(preferences.cameraMode)
    val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    private val _nightConfig = MutableStateFlow(preferences.getModeNightConfig(preferences.cameraMode))
    val nightConfig: StateFlow<NightConfig> = _nightConfig.asStateFlow()
    val nightProgress: StateFlow<NightCaptureProgress> = engine.nightProgress
    val humanVisionProgress = engine.humanVisionProgress

    val hybridStabilizationConfig: StateFlow<HybridStabilizationConfig> = engine.hybridStabilizationConfig

    private val _selectedAspectRatio = MutableStateFlow(CameraAspectRatio.RATIO_9_16)
    val selectedAspectRatio: StateFlow<CameraAspectRatio> = _selectedAspectRatio.asStateFlow()

    private val _tapFocusConfig = MutableStateFlow(preferences.getModeTapFocusConfig(preferences.cameraMode))
    val tapFocusConfig: StateFlow<TapFocusConfig> = _tapFocusConfig.asStateFlow()

    val isRecordingVideo: StateFlow<Boolean> = engine.isRecordingVideo
    val videoDurationSeconds: StateFlow<Int> = engine.videoDurationSeconds

    val computationalVideoPipeline: StateFlow<ComputationalVideoPipeline> = engine.computationalVideoPipeline

    fun setComputationalVideoPipeline(pipeline: ComputationalVideoPipeline) {
        engine.setComputationalVideoPipeline(pipeline)
    }

    fun cycleComputationalVideoPipeline() {
        val current = computationalVideoPipeline.value
        val next = when (current) {
            ComputationalVideoPipeline.DEFAULT -> ComputationalVideoPipeline.PIXEL
            ComputationalVideoPipeline.PIXEL -> ComputationalVideoPipeline.SAMSUNG
            ComputationalVideoPipeline.SAMSUNG -> ComputationalVideoPipeline.IPHONE
            ComputationalVideoPipeline.IPHONE -> ComputationalVideoPipeline.VIVO
            ComputationalVideoPipeline.VIVO -> ComputationalVideoPipeline.DEFAULT
        }
        engine.setComputationalVideoPipeline(next)
        showToast("Computational Video: ${next.displayName}")
    }

    // Portrait Mode Controls & Pipeline State
    private val _portraitConfig = MutableStateFlow(preferences.getModePortraitConfig(preferences.cameraMode))
    val portraitConfig: StateFlow<PortraitConfig> = _portraitConfig.asStateFlow()

    private val _portraitProcessingState = MutableStateFlow(PortraitProcessingState())
    val portraitProcessingState: StateFlow<PortraitProcessingState> = _portraitProcessingState.asStateFlow()

    // Photo Filter State
    private val _selectedPhotoFilter = MutableStateFlow(preferences.getModePhotoFilter(preferences.cameraMode))
    val selectedPhotoFilter: StateFlow<PhotoFilter> = _selectedPhotoFilter.asStateFlow()

    private val _isPhotoFilterBarOpen = MutableStateFlow(false)
    val isPhotoFilterBarOpen: StateFlow<Boolean> = _isPhotoFilterBarOpen.asStateFlow()

    // Portrait Style State
    private val _isPortraitStyleBarOpen = MutableStateFlow(false)
    val isPortraitStyleBarOpen: StateFlow<Boolean> = _isPortraitStyleBarOpen.asStateFlow()

    // UI Customization State
    private val _uiCustomizationState = MutableStateFlow(preferences.uiCustomizationState)
    val uiCustomizationState: StateFlow<UiCustomizationState> = _uiCustomizationState.asStateFlow()

    // Filtered lenses strictly adhering to current facing:
    // When on Back Camera -> ONLY back lenses (0.5x, 1x, 2x, etc.)
    // When on Front Camera -> ONLY front selfie lens
    val displayedLenses: StateFlow<List<LensInfo>> = combine(
        engine.availableLenses,
        engine.selectedLens
    ) { lenses, selected ->
        val currentFacing = selected?.facing ?: android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        lenses.filter { it.facing == currentFacing }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedLens: StateFlow<LensInfo?> = engine.selectedLens

    // Timer
    private val _timerMode = MutableStateFlow(preferences.getModeTimerMode(preferences.cameraMode))
    val timerMode: StateFlow<TimerMode> = _timerMode.asStateFlow()

    private val _activeTimerCountdown = MutableStateFlow<Int?>(null)
    val activeTimerCountdown: StateFlow<Int?> = _activeTimerCountdown.asStateFlow()

    // Grid
    private val _gridType = MutableStateFlow(preferences.getModeGridType(preferences.cameraMode))
    val gridType: StateFlow<GridType> = _gridType.asStateFlow()

    // Flash
    private val _flashMode = MutableStateFlow(preferences.getModeFlashMode(preferences.cameraMode))
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    // Manual Pro controls drawer / bar
    private val _isManualProOpen = MutableStateFlow(false)
    val isManualProOpen: StateFlow<Boolean> = _isManualProOpen.asStateFlow()

    private val _activeProTab = MutableStateFlow(ProControlTab.EXPOSURE)
    val activeProTab: StateFlow<ProControlTab> = _activeProTab.asStateFlow()

    // Settings drawer & media viewer
    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _isVideoSettingsPanelOpen = MutableStateFlow(false)
    val isVideoSettingsPanelOpen: StateFlow<Boolean> = _isVideoSettingsPanelOpen.asStateFlow()

    private val _isGallerySelectionDialogOpen = MutableStateFlow(false)
    val isGallerySelectionDialogOpen: StateFlow<Boolean> = _isGallerySelectionDialogOpen.asStateFlow()

    private val _preferredGalleryPackage = MutableStateFlow(preferences.preferredGalleryPackage)
    val preferredGalleryPackage: StateFlow<String?> = _preferredGalleryPackage.asStateFlow()

    // Focus indicator ring
    private val _focusRingPoint = MutableStateFlow<Offset?>(null)
    val focusRingPoint: StateFlow<Offset?> = _focusRingPoint.asStateFlow()

    // Mirror Selfie (Save selfie as previewed without flipping)
    private val _saveSelfieAsPreviewed = MutableStateFlow(preferences.saveSelfieAsPreviewed)
    val saveSelfieAsPreviewed: StateFlow<Boolean> = _saveSelfieAsPreviewed.asStateFlow()

    // Portrait Settings Window Open/Close
    private val _isPortraitSettingsOpen = MutableStateFlow(false)
    val isPortraitSettingsOpen: StateFlow<Boolean> = _isPortraitSettingsOpen.asStateFlow()

    // Cinema Mode State & Panel visibility
    val cinemaConfig: StateFlow<CinemaConfig> = engine.cinemaConfig
    val cinemaCapabilities: StateFlow<CinemaHardwareCapabilities> = engine.cinemaCapabilities
    val rec2020AutoToneParams: StateFlow<com.example.camera.engine.Rec2020AutoToneParams> = engine.rec2020AutoToneParams
    val capabilities: StateFlow<HardwareCapabilities> = engine.capabilities
    private val _isCinemaSettingsOpen = MutableStateFlow(false)
    val isCinemaSettingsOpen: StateFlow<Boolean> = _isCinemaSettingsOpen.asStateFlow()

    // Floating Window Appearance (Transparency & Blur Strength)
    private val _floatingWindowAppearance = MutableStateFlow(preferences.getFloatingWindowAppearance())
    val floatingWindowAppearance: StateFlow<FloatingWindowAppearanceConfig> = _floatingWindowAppearance.asStateFlow()

    fun setFloatingWindowTransparency(transparency: Float) {
        val clamped = transparency.coerceIn(0.0f, 1.0f)
        _floatingWindowAppearance.update { it.copy(transparency = clamped) }
        preferences.floatingWindowTransparency = clamped
    }

    fun setFloatingWindowBlurStrength(blurStrength: Float) {
        val clamped = blurStrength.coerceIn(0.0f, 50.0f)
        _floatingWindowAppearance.update { it.copy(blurStrength = clamped) }
        preferences.floatingWindowBlurStrength = clamped
        com.example.camera.ui.components.BackdropBlurManager.onBlurStrengthChanged(clamped)
    }

    fun setFloatingWindowAppearance(config: FloatingWindowAppearanceConfig) {
        _floatingWindowAppearance.value = config
        preferences.saveFloatingWindowAppearance(config)
        com.example.camera.ui.components.BackdropBlurManager.onBlurStrengthChanged(config.blurStrength)
    }

    fun resetFloatingWindowAppearance() {
        val default = FloatingWindowAppearanceConfig.GLASSMORPHISM
        setFloatingWindowAppearance(default)
    }

    // Photo Megapixel Mode (12M vs 50M Ultra)
    private val _photoMegapixelMode = MutableStateFlow(preferences.getModePhotoMegapixelMode(preferences.cameraMode))
    val photoMegapixelMode: StateFlow<PhotoMegapixelMode> = _photoMegapixelMode.asStateFlow()

    // Refocus Photo Mode
    private val _isRefocusPhotoEnabled = MutableStateFlow(preferences.getModeRefocusEnabled(preferences.cameraMode))
    val isRefocusPhotoEnabled: StateFlow<Boolean> = _isRefocusPhotoEnabled.asStateFlow()

    private val _refocusFrameCount = MutableStateFlow(preferences.getModeRefocusFrameCount(preferences.cameraMode))
    val refocusFrameCount: StateFlow<Int> = _refocusFrameCount.asStateFlow()

    fun setRefocusFrameCount(count: Int) {
        val clamped = count.coerceIn(5, 20)
        _refocusFrameCount.value = clamped
        preferences.refocusFrameCount = clamped
        preferences.setModeRefocusFrameCount(_cameraMode.value, clamped)
        engine.refocusFrameCount = clamped
        showToast("Refocus: $clamped Focus Planes")
    }

    // Ultra Fast Shutter System
    private val _isUltraFastShutterEnabled = MutableStateFlow(preferences.getModeUltraFastShutterEnabled(preferences.cameraMode))
    val isUltraFastShutterEnabled: StateFlow<Boolean> = _isUltraFastShutterEnabled.asStateFlow()

    private val _ultraFastShutterFps = MutableStateFlow(preferences.getModeUltraFastShutterFps(preferences.cameraMode))
    val ultraFastShutterFps: StateFlow<Int> = _ultraFastShutterFps.asStateFlow()

    val fastShutterFrameCount: StateFlow<Int> = engine.fastShutterFrameCount
    val isFastShutterHolding: StateFlow<Boolean> = engine.isFastShutterHolding

    fun setUltraFastShutterEnabled(enabled: Boolean) {
        _isUltraFastShutterEnabled.value = enabled
        preferences.isUltraFastShutterEnabled = enabled
        preferences.setModeUltraFastShutterEnabled(_cameraMode.value, enabled)
        engine.updateFastShutterState(enabled, _ultraFastShutterFps.value)
        showToast(if (enabled) "Fast Shutter: ON (${_ultraFastShutterFps.value} FPS)" else "Fast Shutter: OFF")
    }

    fun setUltraFastShutterFps(fps: Int) {
        val clamped = fps.coerceIn(5, 20)
        _ultraFastShutterFps.value = clamped
        preferences.ultraFastShutterFps = clamped
        preferences.setModeUltraFastShutterFps(_cameraMode.value, clamped)
        engine.updateFastShutterState(_isUltraFastShutterEnabled.value, clamped)
        showToast("Capture Rate: $clamped FPS")
    }

    /**
     * Executes normal single photo on single tap.
     */
    fun onFastShutterSingleTap() {
        onMainActionButtonClick()
    }

    /**
     * Starts continuous RAW acquisition when user presses and holds the shutter button.
     */
    fun onFastShutterHoldStart() {
        if (_cameraMode.value != CameraMode.PHOTO) return
        com.example.camera.sound.CameraSoundManager.playShutter()
        engine.startUltraFastContinuousCapture(
            fps = _ultraFastShutterFps.value,
            onComplete = { coverUri ->
                if (coverUri != null) {
                    showToast("Burst saved to DCIM/Camera")
                }
            }
        )
    }

    /**
     * Immediately stops continuous RAW acquisition when user releases their finger.
     */
    fun onFastShutterHoldEnd() {
        if (_cameraMode.value != CameraMode.PHOTO) return
        engine.stopUltraFastContinuousCapture()
    }

    // High-Quality Zoom Engine (Multi-Frame Lanczos-3 & Micro-Detail Recovery)
    private val _isHighQualityZoomEnabled = MutableStateFlow(preferences.getModeHqZoomEnabled(preferences.cameraMode))
    val isHighQualityZoomEnabled: StateFlow<Boolean> = _isHighQualityZoomEnabled.asStateFlow()

    private val _zoomProcessingQuality = MutableStateFlow(preferences.getModeZoomQuality(preferences.cameraMode))
    val zoomProcessingQuality: StateFlow<com.example.camera.zoom.ZoomProcessingQuality> = _zoomProcessingQuality.asStateFlow()

    val isZoomProcessing: StateFlow<Boolean> = engine.isZoomProcessing
    val zoomProgress: StateFlow<Float> = engine.zoomProgress

    fun setHighQualityZoomEnabled(enabled: Boolean) {
        _isHighQualityZoomEnabled.value = enabled
        preferences.isHighQualityZoomEnabled = enabled
        preferences.setModeHqZoomEnabled(_cameraMode.value, enabled)
        engine.isHighQualityZoomEnabled = enabled
        if (enabled) {
            showToast("HQ Zoom Engine: ON (Lanczos-3 + Multi-Frame)")
        } else {
            showToast("HQ Zoom Engine: OFF")
        }
    }

    fun setZoomProcessingQuality(quality: com.example.camera.zoom.ZoomProcessingQuality) {
        _zoomProcessingQuality.value = quality
        preferences.zoomProcessingQuality = quality
        preferences.setModeZoomQuality(_cameraMode.value, quality)
        engine.zoomProcessingQuality = quality
        showToast("Zoom Clarity: ${quality.label}")
    }

    private val _zoomPresetsMode = MutableStateFlow(preferences.zoomPresetsMode)
    val zoomPresetsMode: StateFlow<String> = _zoomPresetsMode.asStateFlow()

    private val _customZoomPresetsStr = MutableStateFlow(preferences.customZoomPresetsStr)
    val customZoomPresetsStr: StateFlow<String> = _customZoomPresetsStr.asStateFlow()

    private val _activeZoomPresets = MutableStateFlow(preferences.getEffectiveZoomPresets(hasUltraWide = true))
    val activeZoomPresets: StateFlow<List<Float>> = _activeZoomPresets.asStateFlow()

    fun setZoomPresetsMode(mode: String) {
        _zoomPresetsMode.value = mode
        preferences.zoomPresetsMode = mode
        refreshActiveZoomPresets()
        val label = when (mode) {
            "POWERS_OF_TWO" -> "1x, 2x, 4x, 8x"
            "CINEMATIC" -> "Cine (1x, 2x, 3x, 6x, 10x)"
            "CUSTOM" -> "Custom (${preferences.customZoomPresetsStr})"
            else -> "Standard (0.5x, 1x, 2x, 3x, 5x, 10x)"
        }
        showToast("Zoom Presets: $label")
    }

    fun setCustomZoomPresets(presets: String) {
        _customZoomPresetsStr.value = presets
        preferences.customZoomPresetsStr = presets
        refreshActiveZoomPresets()
        showToast("Custom Zoom Presets Updated")
    }

    fun refreshActiveZoomPresets() {
        val hasUW = engine.availableLenses.value.any { it.lensType == LensType.ULTRAWIDE }
        _activeZoomPresets.value = preferences.getEffectiveZoomPresets(hasUW)
    }

    fun setRefocusPhotoEnabled(enabled: Boolean) {
        _isRefocusPhotoEnabled.value = enabled
        preferences.isRefocusPhotoEnabled = enabled
        preferences.setModeRefocusEnabled(_cameraMode.value, enabled)
        engine.isRefocusPhotoEnabled = enabled
        showToast(if (enabled) "Refocus Photo: ON" else "Refocus Photo: OFF")
    }

    fun setPhotoMegapixelMode(mode: PhotoMegapixelMode) {
        _photoMegapixelMode.value = mode
        preferences.photoMegapixelMode = mode
        preferences.setModePhotoMegapixelMode(_cameraMode.value, mode)
        engine.photoMegapixelMode = mode
        if (mode == PhotoMegapixelMode.M50) {
            showToast("50M Computational Ultra HD")
        } else {
            showToast("12M Standard Mode")
        }
    }

    fun togglePhotoMegapixelMode() {
        val next = if (_photoMegapixelMode.value == PhotoMegapixelMode.M12) {
            PhotoMegapixelMode.M50
        } else {
            PhotoMegapixelMode.M12
        }
        setPhotoMegapixelMode(next)
    }


    // More Modes Drawer visibility
    private val _isMoreModesOpen = MutableStateFlow(false)
    val isMoreModesOpen: StateFlow<Boolean> = _isMoreModesOpen.asStateFlow()

    fun setMoreModesOpen(isOpen: Boolean) {
        _isMoreModesOpen.value = isOpen
    }

    fun toggleMoreModes() {
        _isMoreModesOpen.value = !_isMoreModesOpen.value
    }

    fun setCinemaSettingsOpen(isOpen: Boolean) {
        _isCinemaSettingsOpen.value = isOpen
    }

    fun toggleCinemaSettings() {
        _isCinemaSettingsOpen.value = !_isCinemaSettingsOpen.value
    }

    // Video Adjustments State (Normal Video Mode)
    private val _videoAdjustments = MutableStateFlow(preferences.getVideoAdjustments())
    val videoAdjustments: StateFlow<com.example.camera.model.VideoAdjustments> = _videoAdjustments.asStateFlow()

    private val _isVideoAdjustmentsOpen = MutableStateFlow(false)
    val isVideoAdjustmentsOpen: StateFlow<Boolean> = _isVideoAdjustmentsOpen.asStateFlow()

    fun setVideoAdjustmentsOpen(isOpen: Boolean) {
        _isVideoAdjustmentsOpen.value = isOpen
        if (isOpen) {
            _isVideoSettingsPanelOpen.value = false
            _isSettingsOpen.value = false
            _isManualProOpen.value = false
        }
    }

    fun toggleVideoAdjustmentsOpen() {
        setVideoAdjustmentsOpen(!_isVideoAdjustmentsOpen.value)
    }

    private var saveVideoAdjustmentsJob: kotlinx.coroutines.Job? = null

    fun updateVideoAdjustments(adjustments: com.example.camera.model.VideoAdjustments) {
        _videoAdjustments.value = adjustments
        engine.currentVideoAdjustments = adjustments

        // Debounce persistence to background thread to prevent UI lag while dragging sliders
        saveVideoAdjustmentsJob?.cancel()
        saveVideoAdjustmentsJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            preferences.saveVideoAdjustments(adjustments)
        }
    }

    fun resetVideoAdjustments() {
        saveVideoAdjustmentsJob?.cancel()
        val def = com.example.camera.model.VideoAdjustments()
        _videoAdjustments.value = def
        engine.currentVideoAdjustments = def
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            preferences.saveVideoAdjustments(def)
        }
    }

    fun updateCinemaConfig(config: CinemaConfig) {
        engine.setCinemaConfig(config)
        preferences.saveCinemaConfig(config)
    }

    // --- Custom Image Processing Pipeline (RAW/YUV Uncompressed Processing) ---
    private val _isCustomPipelineEnabled = MutableStateFlow(preferences.isCustomPipelineEnabled)
    val isCustomPipelineEnabled: StateFlow<Boolean> = _isCustomPipelineEnabled.asStateFlow()

    private val _activePipelinePreset = MutableStateFlow(preferences.getActivePipelinePreset())
    val activePipelinePreset: StateFlow<com.example.camera.pipeline.model.PipelinePreset> = _activePipelinePreset.asStateFlow()

    private val _activePipelineParams = MutableStateFlow(preferences.getPipelineParams(preferences.activePipelinePresetId))
    val activePipelineParams: StateFlow<com.example.camera.pipeline.model.CustomPipelineParams> = _activePipelineParams.asStateFlow()

    private val _customPresets = MutableStateFlow(preferences.getCustomPresets())
    val customPresets: StateFlow<List<com.example.camera.pipeline.model.PipelinePreset>> = _customPresets.asStateFlow()

    private val _isPipelineSheetOpen = MutableStateFlow(false)
    val isPipelineSheetOpen: StateFlow<Boolean> = _isPipelineSheetOpen.asStateFlow()

    private val _isBeforeAfterOpen = MutableStateFlow(false)
    val isBeforeAfterOpen: StateFlow<Boolean> = _isBeforeAfterOpen.asStateFlow()

    val latestPipelineCapture = com.example.camera.pipeline.engine.PipelineCaptureCache.latestCapture

    private val _isReprocessing = MutableStateFlow(false)
    val isReprocessing: StateFlow<Boolean> = _isReprocessing.asStateFlow()

    fun setPipelineSheetOpen(isOpen: Boolean) {
        _isPipelineSheetOpen.value = isOpen
    }

    fun setBeforeAfterOpen(isOpen: Boolean) {
        _isBeforeAfterOpen.value = isOpen
    }

    fun toggleCustomPipelineEnabled(enabled: Boolean) {
        _isCustomPipelineEnabled.value = enabled
        preferences.isCustomPipelineEnabled = enabled
        showToast(if (enabled) "Custom Image Pipeline: ON" else "Custom Image Pipeline: OFF")
    }

    // --- Motorola Instant Camera Switching ---
    val instantSwitchState: StateFlow<MotorolaInstantSwitchState> = engine.motorolaSwitchEngine.switchState

    fun setShowUltraWidePreview(enabled: Boolean) {
        engine.motorolaSwitchEngine.setShowUltraWidePreview(enabled)
        showToast(if (enabled) "Ultra-Wide Little Preview: ON" else "Ultra-Wide Little Preview: OFF")
    }

    fun setKeepFrontCameraReady(enabled: Boolean) {
        engine.motorolaSwitchEngine.setKeepFrontCameraReady(enabled)
        showToast(if (enabled) "Keep Front Camera Ready: ON" else "Keep Front Camera Ready: OFF")
    }

    fun setShowFrontCameraPreview(enabled: Boolean) {
        engine.motorolaSwitchEngine.setShowFrontCameraPreview(enabled)
        showToast(if (enabled) "Front Camera Little Preview: ON" else "Front Camera Little Preview: OFF")
    }

    fun onUltraWideLittlePreviewSurfaceAvailable(surfaceTexture: SurfaceTexture?) {
        engine.motorolaSwitchEngine.setUltraWidePreviewSurfaceTexture(surfaceTexture)
    }

    fun onFrontLittlePreviewSurfaceAvailable(surfaceTexture: SurfaceTexture?) {
        engine.motorolaSwitchEngine.setFrontPreviewSurfaceTexture(surfaceTexture)
    }

    fun switchToUltraWideInstant() {
        val ultraLens = engine.availableLenses.value.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
        if (ultraLens != null) {
            selectLens(ultraLens)
        } else {
            showToast("Real Ultra-Wide lens is not available on this device")
        }
    }

    fun switchToFrontInstant() {
        val frontLens = engine.availableLenses.value.firstOrNull {
            it.facing == CameraCharacteristics.LENS_FACING_FRONT
        }
        if (frontLens != null) {
            selectLens(frontLens)
        } else {
            toggleCameraFacing()
        }
    }

    fun switchToMainInstant() {
        val mainLens = engine.availableLenses.value.firstOrNull {
            it.facing == CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.WIDE && !it.isZoomPreset
        } ?: engine.availableLenses.value.firstOrNull {
            it.facing == CameraCharacteristics.LENS_FACING_BACK
        }
        if (mainLens != null) {
            selectLens(mainLens)
        }
    }

    fun selectPipelinePreset(preset: com.example.camera.pipeline.model.PipelinePreset) {
        _activePipelinePreset.value = preset
        preferences.saveActivePipelinePreset(preset)
        val params = preferences.getPipelineParams(preset.id)
        _activePipelineParams.value = params
        showToast("Preset: ${preset.displayName}")
    }

    fun updatePipelineParams(params: com.example.camera.pipeline.model.CustomPipelineParams) {
        _activePipelineParams.value = params
        preferences.savePipelineParams(_activePipelinePreset.value.id, params)
    }

    fun savePipelineCustomPreset(name: String, description: String) {
        val id = "custom_" + System.currentTimeMillis()
        val newPreset = com.example.camera.pipeline.model.PipelinePreset(
            id = id,
            name = name,
            subtitle = "Custom Tuning",
            description = description,
            isBuiltIn = false,
            params = _activePipelineParams.value
        )
        preferences.saveCustomPreset(newPreset)
        _customPresets.value = preferences.getCustomPresets()
        selectPipelinePreset(newPreset)
        showToast("Preset saved: $name")
    }

    fun deletePipelineCustomPreset(presetId: String) {
        preferences.deleteCustomPreset(presetId)
        _customPresets.value = preferences.getCustomPresets()
        if (_activePipelinePreset.value.id == presetId) {
            selectPipelinePreset(com.example.camera.pipeline.model.PipelinePreset.HASSELBLAD)
        }
        showToast("Custom preset deleted")
    }

    fun resetActivePresetParams() {
        val preset = _activePipelinePreset.value
        val defaultParams = com.example.camera.pipeline.model.PipelinePreset.BUILT_IN_PRESETS.firstOrNull { it.id == preset.id }?.params
            ?: preset.params
        _activePipelineParams.value = defaultParams
        preferences.savePipelineParams(preset.id, defaultParams)
        showToast("Reset ${preset.displayName} parameters")
    }

    fun reprocessLatestCaptureWithCurrentParams(onComplete: (Uri?) -> Unit = {}) {
        viewModelScope.launch {
            _isReprocessing.value = true
            val uri = engine.reprocessLatestPipelineCapture(
                params = _activePipelineParams.value,
                preset = _activePipelinePreset.value
            )
            _isReprocessing.value = false
            if (uri != null) {
                showToast("Photo re-rendered and saved to gallery!")
            } else {
                showToast("Re-rendering failed: No cached capture")
            }
            onComplete(uri)
        }
    }

    // Background Sequential Queue for Portrait Processing
    // Strictly queues portrait captures sequentially to prevent duplicate processing,
    // memory spikes, and crashes during rapid multi-photo captures.
    private val portraitProcessingChannel = Channel<Pair<Bitmap, PortraitConfig>>(capacity = 10)

    // Toast/Feedback banner
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // Parameters
    private val _exposureCompensation = MutableStateFlow(preferences.getModeEv(preferences.cameraMode))
    val exposureCompensation: StateFlow<Int> = _exposureCompensation.asStateFlow()

    private val _manualIso = MutableStateFlow<Int?>(preferences.getModeIso(preferences.cameraMode))
    val manualIso: StateFlow<Int?> = _manualIso.asStateFlow()

    private val _manualShutterSpeedNs = MutableStateFlow<Long?>(preferences.getModeShutter(preferences.cameraMode))
    val manualShutterSpeedNs: StateFlow<Long?> = _manualShutterSpeedNs.asStateFlow()

    private val _whiteBalance = MutableStateFlow(preferences.getModeWhiteBalance(preferences.cameraMode))
    val whiteBalance: StateFlow<WhiteBalanceMode> = _whiteBalance.asStateFlow()

    private val _focusMode = MutableStateFlow(preferences.getModeFocusMode(preferences.cameraMode))
    val focusMode: StateFlow<FocusMode> = _focusMode.asStateFlow()

    private val _manualFocusDistance = MutableStateFlow(preferences.getModeFocusDistance(preferences.cameraMode))
    val manualFocusDistance: StateFlow<Float> = _manualFocusDistance.asStateFlow()

    val isAeLocked: StateFlow<Boolean> = engine.isAeLockedFlow
    val isAfLocked: StateFlow<Boolean> = engine.isAfLockedFlow

    val isCameraInitialized: StateFlow<Boolean> = engine.isCameraInitialized
    val cameraInitError: StateFlow<String?> = engine.cameraInitError

    fun safeInitializeCamera(onResult: (success: Boolean, errorMessage: String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch(Dispatchers.Default) {
            engine.safeInitializeCamera { success, error ->
                viewModelScope.launch(Dispatchers.Main) {
                    onResult(success, error)
                }
            }
        }
    }

    private val _isRawCaptureEnabled = MutableStateFlow(preferences.getModeRaw(preferences.cameraMode))
    val isRawCaptureEnabled: StateFlow<Boolean> = _isRawCaptureEnabled.asStateFlow()

    private val _isVideoStabilizationEnabled = MutableStateFlow(preferences.getModeVideoStabilization(preferences.cameraMode))
    val isVideoStabilizationEnabled: StateFlow<Boolean> = _isVideoStabilizationEnabled.asStateFlow()

    private val _videoBitrateOption = MutableStateFlow(preferences.getModeVideoBitrate(preferences.cameraMode))
    val videoBitrateOption: StateFlow<VideoBitrateOption> = _videoBitrateOption.asStateFlow()

    private val _videoFps = MutableStateFlow(preferences.getModeVideoFps(preferences.cameraMode))
    val videoFps: StateFlow<Int> = _videoFps.asStateFlow()

    private val _viewfinderResolution = MutableStateFlow(preferences.viewfinderResolution)
    val viewfinderResolution: StateFlow<ViewfinderResolution> = _viewfinderResolution.asStateFlow()

    private val _colorProfile = MutableStateFlow(preferences.getModeColorProfile(preferences.cameraMode))
    val colorProfile: StateFlow<ColorProfile> = _colorProfile.asStateFlow()

    private val _isAudioEnabled = MutableStateFlow(preferences.getModeAudioEnabled(preferences.cameraMode))
    val isAudioEnabled: StateFlow<Boolean> = _isAudioEnabled.asStateFlow()

    private val _currentZoom = MutableStateFlow(preferences.getModeZoom(preferences.cameraMode))
    val currentZoom: StateFlow<Float> = _currentZoom.asStateFlow()

    // Active Video Quality (4K 30, 4K 60, 1080p 30, 1080p 60, 720p 30)
    val currentVideoQuality: StateFlow<VideoQualityOption> = combine(
        engine.selectedVideoResolution,
        _videoFps
    ) { res, fps ->
        when {
            res?.width == 3840 && fps == 60 -> VideoQualityOption.UHD_4K_60
            res?.width == 3840 -> VideoQualityOption.UHD_4K_30
            res?.width == 1920 && fps == 60 -> VideoQualityOption.FHD_1080_60
            res?.width == 1920 -> VideoQualityOption.FHD_1080_30
            res?.width == 1280 -> VideoQualityOption.HD_720_30
            else -> VideoQualityOption.UHD_4K_30
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VideoQualityOption.UHD_4K_30)


    private var timerJob: Job? = null
    private var focusDismissJob: Job? = null
    private var toastDismissJob: Job? = null

    init {
        val initialMode = preferences.cameraMode
        _cameraMode.value = initialMode

        // Apply restored mode-specific preferences into engine
        engine.flashMode = preferences.getModeFlashMode(initialMode)
        engine.isRawCaptureEnabled = preferences.getModeRaw(initialMode)
        engine.isVideoStabilizationEnabled = preferences.getModeVideoStabilization(initialMode)
        engine.videoBitrateOption = preferences.getModeVideoBitrate(initialMode)
        engine.videoFps = preferences.getModeVideoFps(initialMode)
        engine.colorProfile = preferences.getModeColorProfile(initialMode)
        engine.isAudioEnabled = preferences.getModeAudioEnabled(initialMode)
        engine.whiteBalanceMode = preferences.getModeWhiteBalance(initialMode)
        engine.focusMode = preferences.getModeFocusMode(initialMode)
        engine.exposureCompensationIndex = preferences.getModeEv(initialMode)
        engine.manualIso = preferences.getModeIso(initialMode)
        engine.manualExposureTimeNs = preferences.getModeShutter(initialMode)
        engine.manualFocusDistance = preferences.getModeFocusDistance(initialMode)
        engine.saveSelfieAsPreviewed = preferences.saveSelfieAsPreviewed
        engine.viewfinderResolution = preferences.viewfinderResolution
        engine.photoMegapixelMode = preferences.getModePhotoMegapixelMode(initialMode)
        engine.isRefocusPhotoEnabled = preferences.getModeRefocusEnabled(initialMode)
        engine.refocusFrameCount = preferences.getModeRefocusFrameCount(initialMode)
        engine.isUltraFastShutterEnabled = preferences.getModeUltraFastShutterEnabled(initialMode)
        engine.ultraFastShutterFps = preferences.getModeUltraFastShutterFps(initialMode)
        engine.isHighQualityZoomEnabled = preferences.getModeHqZoomEnabled(initialMode)
        engine.zoomProcessingQuality = preferences.getModeZoomQuality(initialMode)
        engine.setMode(initialMode)
        engine.restoreInitialVideoResolution(CameraResolution(preferences.videoWidth, preferences.videoHeight))
        engine.setCinemaConfig(preferences.getCinemaConfig())
        engine.updateHybridStabilizationConfig(preferences.hybridStabilizationConfig)
        engine.currentVideoAdjustments = _videoAdjustments.value

        // Restore initial mode aspect ratio:
        // - Photo mode: fixed 3:4
        // - Portrait mode: fixed 3:4
        // - All other modes (Video, Cinema, etc.): fixed 9:16
        if (initialMode == CameraMode.PHOTO || initialMode == CameraMode.PORTRAIT) {
            _selectedAspectRatio.value = CameraAspectRatio.RATIO_4_3
            engine.setPreviewAspectRatio(4f / 3f)
        } else {
            _selectedAspectRatio.value = CameraAspectRatio.RATIO_9_16
            engine.setPreviewAspectRatio(16f / 9f)
        }

        viewModelScope.launch {
            engine.availableLenses.collect { lenses ->
                if (lenses.isNotEmpty()) {
                    refreshActiveZoomPresets()
                    val savedModeLens = preferences.getModeLens(preferences.cameraMode, lenses)
                    if (savedModeLens != null && engine.selectedLens.value?.id != savedModeLens.id) {
                        engine.selectLens(savedModeLens)
                    }
                    val savedModeZoom = preferences.getModeZoom(preferences.cameraMode)
                    if (savedModeZoom > 0f) {
                        _currentZoom.value = savedModeZoom
                        engine.setZoom(savedModeZoom, isPresetTap = false)
                    }
                }
            }
        }

        viewModelScope.launch {
            engine.currentZoomState.collect { zoom ->
                _currentZoom.value = zoom
                preferences.currentZoom = zoom
                preferences.setModeZoom(_cameraMode.value, zoom)
            }
        }

        // Sequential background portrait processor
        // Processes portrait jobs safely one by one in the background without UI blocking,
        // memory spikes, or duplicate processing crashes.
        viewModelScope.launch(Dispatchers.Default) {
            for ((bitmap, config) in portraitProcessingChannel) {
                try {
                    val uri = portraitProcessor.processAndSavePortrait(
                        orientedBitmap = bitmap,
                        config = config
                    )
                    if (uri != null) {
                        withContext(Dispatchers.Main) {
                            showToast("Portrait saved to DCIM/Camera")
                            engine.updateStorageStats()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            showToast("Portrait processing failed")
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("CameraViewModel", "Error in background portrait processing", t)
                } finally {
                    try {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    } catch (ignored: Exception) {}
                }
            }
        }
    }

    fun setPortraitSettingsOpen(isOpen: Boolean) {
        _isPortraitSettingsOpen.value = isOpen
    }

    fun toggleSaveSelfieAsPreviewed() {
        val next = !_saveSelfieAsPreviewed.value
        _saveSelfieAsPreviewed.value = next
        preferences.saveSelfieAsPreviewed = next
        engine.saveSelfieAsPreviewed = next
        showToast(if (next) "Save selfie as previewed: ON" else "Save selfie as previewed: OFF")
    }

    fun setSaveSelfieAsPreviewed(enabled: Boolean) {
        _saveSelfieAsPreviewed.value = enabled
        preferences.saveSelfieAsPreviewed = enabled
        engine.saveSelfieAsPreviewed = enabled
    }

    fun setCameraMode(mode: CameraMode) {
        val previousMode = _cameraMode.value
        if (mode == previousMode) return

        // 1. Persist previous mode's dynamic state
        engine.selectedLens.value?.let { currentLens ->
            preferences.setModeLens(previousMode, currentLens)
        }
        preferences.setModeZoom(previousMode, _currentZoom.value)

        // 2. Set new mode
        _cameraMode.value = mode
        preferences.cameraMode = mode

        // 3. Restore mode-specific lens if available
        val modeLens = preferences.getModeLens(mode, engine.availableLenses.value)
        if (modeLens != null && modeLens.id != engine.selectedLens.value?.id) {
            engine.selectLens(modeLens)
        }
        val modeZoom = preferences.getModeZoom(mode)
        if (modeZoom > 0f) {
            _currentZoom.value = modeZoom
            engine.setZoom(modeZoom, isPresetTap = false)
        }

        // 4. Restore mode-specific controls
        val mFlash = preferences.getModeFlashMode(mode)
        _flashMode.value = mFlash
        engine.flashMode = mFlash

        val mTimer = preferences.getModeTimerMode(mode)
        _timerMode.value = mTimer

        val mGrid = preferences.getModeGridType(mode)
        _gridType.value = mGrid

        val mRaw = preferences.getModeRaw(mode)
        _isRawCaptureEnabled.value = mRaw
        engine.isRawCaptureEnabled = mRaw

        val mMp = preferences.getModePhotoMegapixelMode(mode)
        _photoMegapixelMode.value = mMp
        engine.photoMegapixelMode = mMp

        val mRefocus = preferences.getModeRefocusEnabled(mode)
        _isRefocusPhotoEnabled.value = mRefocus
        engine.isRefocusPhotoEnabled = mRefocus

        val mRefocusCount = preferences.getModeRefocusFrameCount(mode)
        _refocusFrameCount.value = mRefocusCount
        engine.refocusFrameCount = mRefocusCount

        val mFastShutter = preferences.getModeUltraFastShutterEnabled(mode)
        _isUltraFastShutterEnabled.value = mFastShutter
        engine.isUltraFastShutterEnabled = mFastShutter

        val mFastFps = preferences.getModeUltraFastShutterFps(mode)
        _ultraFastShutterFps.value = mFastFps
        engine.ultraFastShutterFps = mFastFps

        val mHqZoom = preferences.getModeHqZoomEnabled(mode)
        _isHighQualityZoomEnabled.value = mHqZoom
        engine.isHighQualityZoomEnabled = mHqZoom

        val mZoomQuality = preferences.getModeZoomQuality(mode)
        _zoomProcessingQuality.value = mZoomQuality
        engine.zoomProcessingQuality = mZoomQuality

        val mVideoStab = preferences.getModeVideoStabilization(mode)
        _isVideoStabilizationEnabled.value = mVideoStab
        engine.isVideoStabilizationEnabled = mVideoStab

        val mVideoFps = preferences.getModeVideoFps(mode)
        _videoFps.value = mVideoFps
        engine.videoFps = mVideoFps

        val mVideoBitrate = preferences.getModeVideoBitrate(mode)
        _videoBitrateOption.value = mVideoBitrate
        engine.videoBitrateOption = mVideoBitrate

        val mAudio = preferences.getModeAudioEnabled(mode)
        _isAudioEnabled.value = mAudio
        engine.isAudioEnabled = mAudio

        val mAutoHdr = preferences.getModeAutoHdr(mode)
        _autoHdrEnabled.value = mAutoHdr

        val mAutoFraming = preferences.getModeAutoFraming(mode)
        _autoFramingEnabled.value = mAutoFraming

        val mFilter = preferences.getModePhotoFilter(mode)
        _selectedPhotoFilter.value = mFilter

        val mWb = preferences.getModeWhiteBalance(mode)
        _whiteBalance.value = mWb
        engine.whiteBalanceMode = mWb

        val mFocus = preferences.getModeFocusMode(mode)
        _focusMode.value = mFocus
        engine.focusMode = mFocus

        val mEv = preferences.getModeEv(mode)
        _exposureCompensation.value = mEv
        engine.exposureCompensationIndex = mEv

        val mIso = preferences.getModeIso(mode)
        _manualIso.value = mIso
        engine.manualIso = mIso

        val mShutter = preferences.getModeShutter(mode)
        _manualShutterSpeedNs.value = mShutter
        engine.manualExposureTimeNs = mShutter

        val mDist = preferences.getModeFocusDistance(mode)
        _manualFocusDistance.value = mDist
        engine.manualFocusDistance = mDist

        val mColorProfile = preferences.getModeColorProfile(mode)
        _colorProfile.value = mColorProfile
        engine.colorProfile = mColorProfile

        val mPortraitConfig = preferences.getModePortraitConfig(mode)
        _portraitConfig.value = mPortraitConfig

        val mNightConfig = preferences.getModeNightConfig(mode)
        _nightConfig.value = mNightConfig

        val mTapFocus = preferences.getModeTapFocusConfig(mode)
        _tapFocusConfig.value = mTapFocus

        val mHybridStab = preferences.getModeHybridStabilizationConfig(mode)
        engine.updateHybridStabilizationConfig(mHybridStab)

        engine.updatePreviewSettings()

        // Apply strictly required aspect ratios:
        // - Photo mode: fixed 3:4
        // - Portrait mode: fixed 3:4
        // - All other modes (Video, Cinema, etc.): fixed 9:16
        if (mode == CameraMode.PHOTO || mode == CameraMode.PORTRAIT) {
            _selectedAspectRatio.value = CameraAspectRatio.RATIO_4_3
            engine.setPreviewAspectRatio(4f / 3f)
        } else {
            _selectedAspectRatio.value = CameraAspectRatio.RATIO_9_16
            engine.setPreviewAspectRatio(16f / 9f)
        }

        if (mode != CameraMode.VIDEO) {
            _isVideoAdjustmentsOpen.value = false
        }

        if (mode == CameraMode.AI_SUBJECT_TRACKING) {
            engine.closeCamera()
        } else {
            if (previousMode == CameraMode.AI_SUBJECT_TRACKING) {
                engine.startCamera()
            }
            engine.setMode(mode)
        }

        _isCinemaSettingsOpen.value = false
        if (mode == CameraMode.MORE) {
            _isMoreModesOpen.value = true
        }
    }

    fun selectLens(lens: LensInfo) {
        engine.selectLens(lens)
        preferences.lastFacing = lens.facing
        preferences.saveLastLens(lens)
        preferences.setModeLens(_cameraMode.value, lens)
        val lensDesc = when (lens.lensType) {
            LensType.ULTRAWIDE -> "0.5x Ultra-Wide"
            LensType.WIDE -> "1x Main"
            LensType.TELEPHOTO -> "2x Telephoto"
            LensType.TELEPHOTO_3X -> "3x Telephoto"
            LensType.MACRO -> "Macro"
            LensType.FRONT -> "Front Selfie"
        }
        showToast("Switched to $lensDesc Lens")
    }

    fun forceDeepScanLenses() {
        val count = engine.detectHardwareLenses(forceDeepScan = true)
        showToast("Deep scan found $count hardware & aux lenses")
    }

    fun toggleCameraFacing() {
        val currentLens = engine.selectedLens.value ?: return
        val targetFacing = if (currentLens.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } else {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        }
        val targetLens = engine.availableLenses.value.firstOrNull {
            it.facing == targetFacing && (it.lensType == LensType.WIDE || it.lensType == LensType.FRONT) && !it.isZoomPreset
        } ?: engine.availableLenses.value.firstOrNull { it.facing == targetFacing }

        if (targetLens != null) {
            engine.selectLens(targetLens)
            preferences.lastFacing = targetFacing
            preferences.saveLastLens(targetLens)
            preferences.setModeLens(_cameraMode.value, targetLens)
            val label = if (targetFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) "Front Camera" else "Rear Camera"
            showToast("Switched to $label")
        }
    }

    fun cycleFlashMode() {
        val nextMode = when (_flashMode.value) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        _flashMode.value = nextMode
        preferences.flashMode = nextMode
        preferences.setModeFlashMode(_cameraMode.value, nextMode)
        engine.flashMode = nextMode
        engine.updatePreviewSettings()
        showToast("Flash: ${nextMode.title}")
    }

    fun cycleTimerMode() {
        val nextMode = when (_timerMode.value) {
            TimerMode.OFF -> TimerMode.SEC_3
            TimerMode.SEC_3 -> TimerMode.SEC_5
            TimerMode.SEC_5 -> TimerMode.SEC_10
            TimerMode.SEC_10 -> TimerMode.OFF
        }
        _timerMode.value = nextMode
        preferences.timerMode = nextMode
        preferences.setModeTimerMode(_cameraMode.value, nextMode)
        showToast("Timer: ${nextMode.label}")
    }

    fun setGridType(type: GridType) {
        _gridType.value = type
        preferences.gridType = type
        preferences.setModeGridType(_cameraMode.value, type)
    }

    fun cycleGridType() {
        val nextGrid = when (_gridType.value) {
            GridType.NONE -> GridType.THIRDS
            GridType.THIRDS -> GridType.GOLDEN
            GridType.GOLDEN -> GridType.SQUARE
            GridType.SQUARE -> GridType.LEVEL
            GridType.LEVEL -> GridType.NONE
        }
        _gridType.value = nextGrid
        preferences.gridType = nextGrid
        preferences.setModeGridType(_cameraMode.value, nextGrid)
        showToast("Grid: ${nextGrid.title}")
    }

    fun toggleRawCapture() {
        val caps = engine.capabilities.value
        if (!caps.supportsRaw) {
            showToast("RAW format not supported by this sensor")
            return
        }
        val next = !_isRawCaptureEnabled.value
        _isRawCaptureEnabled.value = next
        preferences.isRawEnabled = next
        preferences.setModeRaw(_cameraMode.value, next)
        engine.isRawCaptureEnabled = next
        engine.restartCamera()
        showToast(if (next) "RAW (DNG + JPEG) Enabled" else "RAW Disabled")
    }

    fun cycleVideoQuality() {
        val supportedQualities = listOf(
            VideoQualityOption.UHD_4K_30,
            VideoQualityOption.UHD_4K_60,
            VideoQualityOption.FHD_1080_30,
            VideoQualityOption.FHD_1080_60,
            VideoQualityOption.HD_720_30
        )
        val currentIndex = supportedQualities.indexOf(currentVideoQuality.value).let { if (it >= 0) it else 0 }
        val nextQuality = supportedQualities[(currentIndex + 1) % supportedQualities.size]
        setVideoQuality(nextQuality)
    }

    fun setVideoQuality(quality: VideoQualityOption) {
        engine.selectVideoResolution(quality.resolution)
        engine.videoFps = quality.fps
        _videoFps.value = quality.fps
        preferences.videoWidth = quality.width
        preferences.videoHeight = quality.height
        preferences.videoFps = quality.fps
        showToast("Video Quality: ${quality.fullLabel}")
    }

    fun toggleManualPro() {
        _isManualProOpen.value = !_isManualProOpen.value
    }

    fun setManualProOpen(open: Boolean) {
        _isManualProOpen.value = open
    }

    fun setActiveProTab(tab: ProControlTab) {
        _activeProTab.value = tab
    }

    fun setExposureCompensation(value: Int) {
        _exposureCompensation.value = value
        preferences.exposureCompensation = value
        preferences.setModeEv(_cameraMode.value, value)
        engine.exposureCompensationIndex = value
        if (_cameraMode.value == CameraMode.CINEMA) {
            updateCinemaConfig(engine.cinemaConfig.value.copy(exposureCompensation = value))
        }
        engine.updatePreviewSettings()
    }

    fun setManualIso(iso: Int?) {
        _manualIso.value = iso
        preferences.manualIso = iso
        preferences.setModeIso(_cameraMode.value, iso)
        engine.manualIso = iso
        engine.updatePreviewSettings()
    }

    fun setManualShutterSpeedNs(ns: Long?) {
        _manualShutterSpeedNs.value = ns
        preferences.manualShutterSpeedNs = ns
        preferences.setModeShutter(_cameraMode.value, ns)
        engine.manualExposureTimeNs = ns
        engine.updatePreviewSettings()
    }

    fun setWhiteBalance(wb: WhiteBalanceMode) {
        _whiteBalance.value = wb
        preferences.whiteBalance = wb
        preferences.setModeWhiteBalance(_cameraMode.value, wb)
        engine.whiteBalanceMode = wb
        engine.updatePreviewSettings()
        showToast("WB: ${wb.title}")
    }

    fun setFocusMode(mode: FocusMode) {
        _focusMode.value = mode
        preferences.focusMode = mode
        preferences.setModeFocusMode(_cameraMode.value, mode)
        engine.focusMode = mode
        engine.updatePreviewSettings()
        showToast("Focus: ${mode.title}")
    }

    fun setManualFocusDistance(distance: Float) {
        _manualFocusDistance.value = distance
        preferences.manualFocusDistance = distance
        preferences.setModeFocusDistance(_cameraMode.value, distance)
        engine.manualFocusDistance = distance
        engine.updatePreviewSettings()
    }

    fun toggleAeLock() {
        val next = !engine.isAeLockedFlow.value
        engine.isAeLocked = next
        engine.updatePreviewSettings()
        showToast(if (next) "Exposure Locked" else "Exposure Unlocked")
    }

    fun toggleAfLock() {
        val next = !engine.isAfLockedFlow.value
        engine.isAfLocked = next
        engine.updatePreviewSettings()
        showToast(if (next) "Focus Locked" else "Focus Unlocked")
    }

    fun setZoom(zoom: Float, isPresetTap: Boolean = false) {
        val ultraWideLens = engine.availableLenses.value.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val minZoom = ultraWideLens?.baseZoomRatio?.coerceAtLeast(0.35f) ?: 1.0f
        val clamped = zoom.coerceIn(minZoom, 10.0f)
        preferences.setModeZoom(_cameraMode.value, clamped)
        engine.setZoom(clamped, isPresetTap)
    }

    fun setVideoStabilization(enabled: Boolean) {
        val caps = engine.capabilities.value
        if (!caps.supportsEis && !caps.supportsOis) {
            showToast("Stabilization not supported by hardware")
            return
        }
        _isVideoStabilizationEnabled.value = enabled
        preferences.isVideoStabilizationEnabled = enabled
        preferences.setModeVideoStabilization(_cameraMode.value, enabled)
        engine.isVideoStabilizationEnabled = enabled
        engine.updatePreviewSettings()
        showToast(if (enabled) "Stabilization Enabled" else "Stabilization Disabled")
    }

    fun setVideoBitrate(bitrate: VideoBitrateOption) {
        _videoBitrateOption.value = bitrate
        preferences.videoBitrate = bitrate
        preferences.setModeVideoBitrate(_cameraMode.value, bitrate)
        engine.videoBitrateOption = bitrate
        showToast("Bitrate: ${bitrate.title}")
    }

    fun setVideoFps(fps: Int) {
        _videoFps.value = fps
        preferences.videoFps = fps
        preferences.setModeVideoFps(_cameraMode.value, fps)
        engine.videoFps = fps
        showToast("Frame Rate: ${fps} FPS")
    }

    fun setColorProfile(profile: ColorProfile) {
        _colorProfile.value = profile
        preferences.colorProfile = profile
        preferences.setModeColorProfile(_cameraMode.value, profile)
        engine.colorProfile = profile
        engine.updatePreviewSettings()
        showToast("Profile: ${profile.title}")
    }

    fun toggleAudio() {
        val next = !_isAudioEnabled.value
        _isAudioEnabled.value = next
        preferences.isAudioEnabled = next
        preferences.setModeAudioEnabled(_cameraMode.value, next)
        engine.isAudioEnabled = next
        showToast(if (next) "Audio Recording On" else "Audio Muted")
    }

    fun selectPhotoResolution(res: CameraResolution) {
        engine.selectPhotoResolution(res)
        showToast("Photo Resolution: ${res.displayLabel}")
    }

    fun selectVideoResolution(res: CameraResolution) {
        engine.selectVideoResolution(res)
        preferences.videoWidth = res.width
        preferences.videoHeight = res.height
        showToast("Video Resolution: ${res.displayLabel}")
    }

    fun setViewfinderResolution(res: ViewfinderResolution) {
        _viewfinderResolution.value = res
        preferences.viewfinderResolution = res
        engine.applyViewfinderResolution(res)
        showToast("Viewfinder: ${res.label}")
    }

    fun setSettingsOpen(open: Boolean) {
        _isSettingsOpen.value = open
    }

    fun setVideoSettingsPanelOpen(open: Boolean) {
        _isVideoSettingsPanelOpen.value = open
    }

    fun toggleVideoSettingsPanel() {
        _isVideoSettingsPanelOpen.value = !_isVideoSettingsPanelOpen.value
    }

    fun setPreferredGalleryPackage(packageName: String?) {
        _preferredGalleryPackage.value = packageName
        preferences.preferredGalleryPackage = packageName
    }

    fun setGallerySelectionDialogOpen(open: Boolean) {
        _isGallerySelectionDialogOpen.value = open
    }

    fun openGallery(context: Context) {
        val media = lastCapturedMedia.value
        if (media != null) {
            if (!preferences.hasPromptedGallerySelection && preferences.preferredGalleryPackage == null) {
                preferences.hasPromptedGallerySelection = true
                _isGallerySelectionDialogOpen.value = true
            } else {
                com.example.camera.gallery.GalleryLauncher.openMedia(
                    context = context,
                    uri = media.uri,
                    isVideo = media.isVideo,
                    preferredPackage = _preferredGalleryPackage.value
                )
            }
        } else {
            showToast("No recent photos yet")
        }
    }

    fun onTapToFocus(point: Offset, normX: Float, normY: Float, isLock: Boolean = false) {
        _focusRingPoint.value = point
        engine.triggerFocusAndMeter(normX, normY, isLock)

        if (!isLock) {
            focusDismissJob?.cancel()
            focusDismissJob = viewModelScope.launch {
                delay(2400)
                _focusRingPoint.value = null
            }
        }
    }

    fun toggleAeAfLock() {
        engine.toggleAeAfLock()
        val locked = engine.isAeLockedFlow.value
        showToast(if (locked) "AE/AF LOCKED" else "AE/AF UNLOCKED")
    }

    fun setNightConfig(config: NightConfig) {
        _nightConfig.value = config
        preferences.nightConfig = config
        preferences.setModeNightConfig(_cameraMode.value, config)
    }

    fun setHybridStabilizationConfig(config: HybridStabilizationConfig) {
        engine.updateHybridStabilizationConfig(config)
        preferences.hybridStabilizationConfig = config
        preferences.setModeHybridStabilizationConfig(_cameraMode.value, config)
    }

    fun setMainCameraStabilizationMode(mode: MainCameraStabilizationMode) {
        val current = hybridStabilizationConfig.value
        val updated = when (mode) {
            MainCameraStabilizationMode.OFF -> current.copy(
                isHybridEnabled = false,
                isOisPreferred = false,
                isEisPreferred = false,
                isUltraStabilizationEnabled = false,
                isEisOnly = false
            )
            MainCameraStabilizationMode.OIS_ONLY -> current.copy(
                isHybridEnabled = false,
                isOisPreferred = true,
                isEisPreferred = false,
                isUltraStabilizationEnabled = false,
                isEisOnly = false
            )
            MainCameraStabilizationMode.EIS_ONLY -> current.copy(
                isHybridEnabled = false,
                isOisPreferred = false,
                isEisPreferred = true,
                isUltraStabilizationEnabled = false,
                isEisOnly = true
            )
            MainCameraStabilizationMode.HYBRID_OIS_EIS -> current.copy(
                isHybridEnabled = true,
                isOisPreferred = true,
                isEisPreferred = true,
                isUltraStabilizationEnabled = false,
                isEisOnly = false
            )
            MainCameraStabilizationMode.ULTRA -> current.copy(
                isHybridEnabled = true,
                isOisPreferred = true,
                isEisPreferred = true,
                isUltraStabilizationEnabled = true,
                isEisOnly = false
            )
        }
        val isStabOn = (mode != MainCameraStabilizationMode.OFF)
        _isVideoStabilizationEnabled.value = isStabOn
        preferences.isVideoStabilizationEnabled = isStabOn
        engine.isVideoStabilizationEnabled = isStabOn
        setHybridStabilizationConfig(updated)
        showToast("Stabilization: ${mode.title}")
    }

    fun toggleUltraStabilization() {
        val current = hybridStabilizationConfig.value
        val nextState = !current.isUltraStabilizationEnabled
        val updated = current.copy(
            isUltraStabilizationEnabled = nextState,
            isHybridEnabled = if (nextState) true else current.isHybridEnabled,
            isEisPreferred = if (nextState) true else current.isEisPreferred
        )
        if (nextState) {
            _isVideoStabilizationEnabled.value = true
            preferences.isVideoStabilizationEnabled = true
            engine.isVideoStabilizationEnabled = true
        }
        setHybridStabilizationConfig(updated)
        showToast(if (nextState) "Ultra Action Steady: Active" else "Ultra Stabilization: Off")
    }

    fun setOisPreferred(enabled: Boolean) {
        val current = hybridStabilizationConfig.value
        val updated = current.copy(isOisPreferred = enabled)
        setHybridStabilizationConfig(updated)
        showToast(if (enabled) "Optical Stabilization (OIS): On" else "Optical Stabilization (OIS): Off")
    }

    fun setTapFocusConfig(config: TapFocusConfig) {
        _tapFocusConfig.value = config
        preferences.tapFocusConfig = config
        preferences.setModeTapFocusConfig(_cameraMode.value, config)
    }

    fun calibrateDollyZoom() {
        engine.calibrateDollyZoom()
        showToast("Dolly Subject Calibrated")
    }

    fun resetDollyZoom() {
        engine.resetDollyZoom()
        showToast("Dolly Zoom Reset")
    }

    fun lockDollySubjectAt(x: Float, y: Float) {
        engine.lockDollySubjectAt(x, y)
        showToast("Subject Locked for Dolly Zoom")
    }

    fun triggerNightCapture() {
        if (engine.isCapturing.value) return

        val timerSeconds = _timerMode.value.seconds
        if (timerSeconds > 0) {
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                for (remaining in timerSeconds downTo 1) {
                    _activeTimerCountdown.value = remaining
                    delay(1000)
                }
                _activeTimerCountdown.value = null
                executeNightCapture()
            }
        } else {
            executeNightCapture()
        }
    }

    private fun executeNightCapture() {
        com.example.camera.sound.CameraSoundManager.playShutter()
        val config = _nightConfig.value
        engine.takeNightPhoto(
            config = config,
            onProgress = {},
            onComplete = { uri ->
                if (uri != null) {
                    showToast("Night photo captured")
                } else {
                    showToast("Night capture failed")
                }
            }
        )
    }

    fun setPortraitBlurStrength(strength: Float) {
        _portraitConfig.update { it.copy(blurStrength = strength) }
        preferences.portraitBlurStrength = strength
    }

    fun setPortraitAperture(aperture: String) {
        _portraitConfig.update { it.copy(simulatedAperture = aperture) }
        preferences.portraitAperture = aperture
        showToast("Aperture: $aperture")
    }

    fun setPortraitBokehStyle(style: BokehStyle) {
        _portraitConfig.update { it.copy(bokehStyle = style) }
        showToast("Bokeh: ${style.label}")
    }

    fun togglePortraitFaceEnhancement() {
        val next = !_portraitConfig.value.faceEnhancement
        _portraitConfig.update { it.copy(faceEnhancement = next) }
        showToast("Face Enhancement: ${if (next) "ON" else "OFF"}")
    }

    fun togglePortraitSkinTone() {
        val next = !_portraitConfig.value.skinToneCorrection
        _portraitConfig.update { it.copy(skinToneCorrection = next) }
        showToast("Skin Tone Correction: ${if (next) "ON" else "OFF"}")
    }

    fun toggleOpticalBlurGuided() {
        val next = !_portraitConfig.value.opticalBlurGuided
        _portraitConfig.update { it.copy(opticalBlurGuided = next) }
        preferences.portraitOpticalBlurGuided = next
        preferences.setModePortraitConfig(_cameraMode.value, _portraitConfig.value)
        showToast("Optical Blur Guidance: ${if (next) "ON" else "OFF"}")
    }

    fun setOpticalBlurGuided(enabled: Boolean) {
        _portraitConfig.update { it.copy(opticalBlurGuided = enabled) }
        preferences.portraitOpticalBlurGuided = enabled
        preferences.setModePortraitConfig(_cameraMode.value, _portraitConfig.value)
        showToast("Optical Blur Guidance: ${if (enabled) "ON" else "OFF"}")
    }

    fun setSelectedPhotoFilter(filter: PhotoFilter) {
        _selectedPhotoFilter.value = filter
        engine.selectedPhotoFilter = filter
        showToast("Filter: ${filter.displayName}")
    }

    fun togglePhotoFilterBar() {
        _isPhotoFilterBarOpen.value = !_isPhotoFilterBarOpen.value
    }

    fun setPhotoFilterBarOpen(open: Boolean) {
        _isPhotoFilterBarOpen.value = open
    }

    fun setSelectedPortraitStyle(style: PortraitStyle) {
        _portraitConfig.update { it.copy(selectedStyle = style) }
        showToast("Portrait Style: ${style.displayName}")
    }

    fun togglePortraitStyleBar() {
        _isPortraitStyleBarOpen.value = !_isPortraitStyleBarOpen.value
    }

    fun setPortraitStyleBarOpen(open: Boolean) {
        _isPortraitStyleBarOpen.value = open
    }

    fun onMainActionButtonClick() {
        when (_cameraMode.value) {
            CameraMode.PHOTO, CameraMode.MORE, CameraMode.AI_SUBJECT_TRACKING -> triggerPhotoCapture()
            CameraMode.PORTRAIT -> triggerPortraitCapture()
            CameraMode.VIDEO, CameraMode.CINEMA, CameraMode.DOLLY_ZOOM -> triggerVideoCapture()
            CameraMode.NIGHT -> triggerNightCapture()
            CameraMode.HUMAN_VISION -> triggerHumanVisionCapture()
        }
    }

    private fun triggerHumanVisionCapture() {
        if (engine.isCapturing.value) return

        val timerSeconds = _timerMode.value.seconds
        if (timerSeconds > 0) {
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                for (remaining in timerSeconds downTo 1) {
                    _activeTimerCountdown.value = remaining
                    delay(1000)
                }
                _activeTimerCountdown.value = null
                executeHumanVisionCapture()
            }
        } else {
            executeHumanVisionCapture()
        }
    }

    private fun executeHumanVisionCapture() {
        showToast("Human Vision: Capturing wide scene & distant acuity...")
        com.example.camera.sound.CameraSoundManager.playShutter()
        engine.takeHumanVisionPhoto { uri ->
            if (uri != null) {
                showToast("Human Vision photo saved to DCIM/Camera")
            } else {
                showToast("Human Vision capture completed")
            }
        }
    }

    private fun triggerPhotoCapture() {
        if (engine.isCapturing.value) return

        val timerSeconds = _timerMode.value.seconds
        if (timerSeconds > 0) {
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                for (remaining in timerSeconds downTo 1) {
                    _activeTimerCountdown.value = remaining
                    delay(1000)
                }
                _activeTimerCountdown.value = null
                executePhotoCapture()
            }
        } else {
            executePhotoCapture()
        }
    }

    private fun executePhotoCapture() {
        val is50M = _photoMegapixelMode.value == PhotoMegapixelMode.M50
        if (is50M) {
            showToast("Processing 50MP Computational photo...")
        }
        com.example.camera.sound.CameraSoundManager.playShutter()
        engine.takePhoto { uri ->
            if (uri != null) {
                if (is50M) {
                    showToast("50MP Computational photo saved to DCIM/Camera")
                } else {
                    showToast("Saved to DCIM/Camera")
                }
            } else {
                showToast("Failed to save photo")
            }
        }
    }

    private fun triggerPortraitCapture() {
        if (engine.isCapturing.value) return

        val timerSeconds = _timerMode.value.seconds
        if (timerSeconds > 0) {
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                for (remaining in timerSeconds downTo 1) {
                    _activeTimerCountdown.value = remaining
                    delay(1000)
                }
                _activeTimerCountdown.value = null
                executePortraitCapture()
            }
        } else {
            executePortraitCapture()
        }
    }

    private fun executePortraitCapture() {
        com.example.camera.sound.CameraSoundManager.playShutter()
        engine.captureStillBitmap { capturedBitmap ->
            if (capturedBitmap == null) {
                showToast("Portrait capture failed")
                return@captureStillBitmap
            }

            // Immediately return to camera viewfinder and process in the background.
            // No progress or loading UI is shown.
            val config = _portraitConfig.value
            val result = portraitProcessingChannel.trySend(Pair(capturedBitmap, config))
            if (!result.isSuccess) {
                viewModelScope.launch(Dispatchers.Default) {
                    portraitProcessingChannel.send(Pair(capturedBitmap, config))
                }
            }
        }
    }

    private fun triggerVideoCapture() {
        if (engine.isRecordingVideo.value) {
            com.example.camera.sound.CameraSoundManager.playStopVideo()
            engine.stopVideoRecording()
            showToast("Video saved to DCIM/Camera")
        } else {
            com.example.camera.sound.CameraSoundManager.playStartVideo()
            engine.startVideoRecording { error ->
                showToast("Recording error: $error")
            }
        }
    }

    // --- Camera UI Customization & Templates ---

    fun selectUiTemplate(template: UiTemplateType) {
        val templateConfig = CameraUiTemplates.getTemplateConfig(template)
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = template,
            globalConfig = templateConfig
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Switched to ${template.title}")
    }

    fun updateGlobalLayoutConfig(config: ModeLayoutConfig) {
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = UiTemplateType.CUSTOM,
            globalConfig = config
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
    }

    fun updateModeLayoutConfig(mode: CameraMode, config: ModeLayoutConfig) {
        val currentModes = _uiCustomizationState.value.modeSpecificConfigs.toMutableMap()
        currentModes[mode] = config
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = UiTemplateType.CUSTOM,
            modeSpecificConfigs = currentModes
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
    }

    fun resetModeLayoutToGlobal(mode: CameraMode) {
        val currentModes = _uiCustomizationState.value.modeSpecificConfigs.toMutableMap()
        currentModes.remove(mode)
        val updated = _uiCustomizationState.value.copy(
            modeSpecificConfigs = currentModes
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Reset ${mode.name} layout to default")
    }

    fun saveCustomPreset(name: String, config: ModeLayoutConfig) {
        val preset = CustomUiPreset(
            id = "preset_${System.currentTimeMillis()}",
            name = name.ifBlank { "Preset ${_uiCustomizationState.value.customPresets.size + 1}" },
            templateType = UiTemplateType.CUSTOM,
            config = config
        )
        val currentPresets = _uiCustomizationState.value.customPresets.toMutableList()
        currentPresets.add(preset)
        val updated = _uiCustomizationState.value.copy(customPresets = currentPresets)
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Saved preset: ${preset.name}")
    }

    fun loadCustomPreset(preset: CustomUiPreset) {
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = preset.templateType,
            globalConfig = preset.config
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Loaded preset: ${preset.name}")
    }

    fun deleteCustomPreset(presetId: String) {
        val currentPresets = _uiCustomizationState.value.customPresets.filterNot { it.id == presetId }
        val updated = _uiCustomizationState.value.copy(customPresets = currentPresets)
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Preset removed")
    }

    fun resetLayoutToTemplate(template: UiTemplateType) {
        val templateConfig = CameraUiTemplates.getTemplateConfig(template)
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = template,
            globalConfig = templateConfig,
            modeSpecificConfigs = emptyMap()
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Reset all layouts to ${template.title}")
    }

    // Extended Settings & Features State
    private val _videoCodec = MutableStateFlow(preferences.videoCodec)
    val videoCodec: StateFlow<String> = _videoCodec.asStateFlow()

    fun setVideoCodec(codec: String) {
        _videoCodec.value = codec
        preferences.videoCodec = codec
        showToast("Video Codec: $codec")
    }

    private val _jpegQuality = MutableStateFlow(preferences.jpegQuality)
    val jpegQuality: StateFlow<Int> = _jpegQuality.asStateFlow()

    fun setJpegQuality(quality: Int) {
        _jpegQuality.value = quality
        preferences.jpegQuality = quality
        showToast("JPEG Quality: $quality%")
    }

    private val _volumeKeyAction = MutableStateFlow(preferences.volumeKeyAction)
    val volumeKeyAction: StateFlow<String> = _volumeKeyAction.asStateFlow()

    fun setVolumeKeyAction(action: String) {
        _volumeKeyAction.value = action
        preferences.volumeKeyAction = action
        showToast("Volume key set to: $action")
    }

    private val _doubleTapAction = MutableStateFlow(preferences.doubleTapAction)
    val doubleTapAction: StateFlow<String> = _doubleTapAction.asStateFlow()

    fun setDoubleTapAction(action: String) {
        _doubleTapAction.value = action
        preferences.doubleTapAction = action
        showToast("Double-tap set to: $action")
    }

    private val _showHorizonLevel = MutableStateFlow(preferences.showHorizonLevel)
    val showHorizonLevel: StateFlow<Boolean> = _showHorizonLevel.asStateFlow()

    fun setShowHorizonLevel(show: Boolean) {
        _showHorizonLevel.value = show
        preferences.showHorizonLevel = show
    }

    private val _antiBanding = MutableStateFlow(preferences.antiBanding)
    val antiBanding: StateFlow<String> = _antiBanding.asStateFlow()

    fun setAntiBanding(mode: String) {
        _antiBanding.value = mode
        preferences.antiBanding = mode
        showToast("Anti-banding: $mode")
    }

    private val _zoomSpeed = MutableStateFlow(preferences.zoomSpeed)
    val zoomSpeed: StateFlow<String> = _zoomSpeed.asStateFlow()

    fun setZoomSpeed(speed: String) {
        _zoomSpeed.value = speed
        preferences.zoomSpeed = speed
    }

    private val _audioSource = MutableStateFlow(preferences.audioSource)
    val audioSource: StateFlow<String> = _audioSource.asStateFlow()

    fun setAudioSource(source: String) {
        _audioSource.value = source
        preferences.audioSource = source
        showToast("Audio source: $source")
    }

    private val _previewQuality = MutableStateFlow(preferences.previewQuality)
    val previewQuality: StateFlow<String> = _previewQuality.asStateFlow()

    fun setPreviewQuality(quality: String) {
        _previewQuality.value = quality
        preferences.previewQuality = quality
        showToast("Preview Quality: $quality")
    }

    private val _shutterFeedback = MutableStateFlow(preferences.shutterFeedback)
    val shutterFeedback: StateFlow<String> = _shutterFeedback.asStateFlow()

    fun setShutterFeedback(feedback: String) {
        _shutterFeedback.value = feedback
        preferences.shutterFeedback = feedback
    }

    val antibandingMode: StateFlow<String> = _antiBanding.asStateFlow()
    fun setAntibandingMode(mode: String) = setAntiBanding(mode)

    private val _windNoiseReduction = MutableStateFlow(preferences.windNoiseReduction)
    val windNoiseReduction: StateFlow<Boolean> = _windNoiseReduction.asStateFlow()
    fun setWindNoiseReduction(enabled: Boolean) {
        _windNoiseReduction.value = enabled
        preferences.windNoiseReduction = enabled
        preferences.setModeWindNoiseReduction(_cameraMode.value, enabled)
        showToast("Wind Noise Reduction: " + if (enabled) "On" else "Off")
    }

    val horizonLeveler: StateFlow<Boolean> = _showHorizonLevel.asStateFlow()
    fun setHorizonLeveler(show: Boolean) = setShowHorizonLevel(show)

    private val _viewfinderFps = MutableStateFlow(preferences.viewfinderFps)
    val viewfinderFps: StateFlow<Int> = _viewfinderFps.asStateFlow()
    fun setViewfinderFps(fps: Int) {
        _viewfinderFps.value = fps
        preferences.viewfinderFps = fps
        showToast("Viewfinder: $fps FPS")
    }

    private val _thermalProtection = MutableStateFlow(preferences.thermalProtection)
    val thermalProtection: StateFlow<Boolean> = _thermalProtection.asStateFlow()
    fun setThermalProtection(enabled: Boolean) {
        _thermalProtection.value = enabled
        preferences.thermalProtection = enabled
        showToast("Thermal Protection: " + if (enabled) "Adaptive" else "Off")
    }

    private val _autoHdrEnabled = MutableStateFlow(preferences.getModeAutoHdr(preferences.cameraMode))
    val autoHdrEnabled: StateFlow<Boolean> = _autoHdrEnabled.asStateFlow()
    val isAutoHdrEnabled: StateFlow<Boolean> = _autoHdrEnabled.asStateFlow()

    fun setAutoHdrEnabled(enabled: Boolean) {
        _autoHdrEnabled.value = enabled
        preferences.autoHdrEnabled = enabled
        preferences.setModeAutoHdr(_cameraMode.value, enabled)
        showToast(if (enabled) "Auto HDR Enabled" else "Auto HDR Disabled")
    }

    private val _autoFramingEnabled = MutableStateFlow(preferences.getModeAutoFraming(preferences.cameraMode))
    val autoFramingEnabled: StateFlow<Boolean> = _autoFramingEnabled.asStateFlow()
    val isAiAutoFramingEnabled: StateFlow<Boolean> = _autoFramingEnabled.asStateFlow()

    fun setAutoFramingEnabled(enabled: Boolean) {
        _autoFramingEnabled.value = enabled
        preferences.autoFramingEnabled = enabled
        preferences.setModeAutoFraming(_cameraMode.value, enabled)
        showToast(if (enabled) "AI Auto-Framing On" else "AI Auto-Framing Off")
    }

    fun setAiAutoFramingEnabled(enabled: Boolean) = setAutoFramingEnabled(enabled)

    fun setPortraitConfig(config: PortraitConfig) {
        _portraitConfig.value = config
        preferences.portraitBlurStrength = config.blurStrength
        preferences.portraitAperture = config.simulatedAperture
        preferences.setModePortraitConfig(_cameraMode.value, config)
    }

    fun setPhotoFilter(filter: PhotoFilter) {
        _selectedPhotoFilter.value = filter
        preferences.selectedPhotoFilter = filter
        preferences.setModePhotoFilter(_cameraMode.value, filter)
        showToast("Filter: ${filter.displayName}")
    }

    fun setManualShutterSpeed(ns: Long?) {
        setManualShutterSpeedNs(ns)
    }

    fun setFlashMode(mode: FlashMode) {
        _flashMode.value = mode
        preferences.flashMode = mode
        preferences.setModeFlashMode(_cameraMode.value, mode)
        engine.flashMode = mode
        engine.updatePreviewSettings()
    }

    fun setTimerMode(mode: TimerMode) {
        _timerMode.value = mode
        preferences.timerMode = mode
        preferences.setModeTimerMode(_cameraMode.value, mode)
    }

    fun resetAllSettings() {
        resetAllSettingsToDefaults()
    }

    fun resetAllSettingsToDefaults() {
        preferences.resetAllSettingsToDefaults()
        selectUiTemplate(UiTemplateType.STOCK_PIXEL)
        setCameraMode(CameraMode.PHOTO)
        setGridType(GridType.NONE)
        setFlashMode(FlashMode.OFF)
        setTimerMode(TimerMode.OFF)
        _videoCodec.value = "HEVC"
        _jpegQuality.value = 100
        _showHorizonLevel.value = true
        _autoHdrEnabled.value = true
        _autoFramingEnabled.value = true
        _windNoiseReduction.value = true
        _thermalProtection.value = true
        _viewfinderFps.value = 60
        _isRefocusPhotoEnabled.value = true
        _refocusFrameCount.value = 10
        _isHighQualityZoomEnabled.value = true
        _zoomProcessingQuality.value = com.example.camera.zoom.ZoomProcessingQuality.BALANCED
        _photoMegapixelMode.value = PhotoMegapixelMode.M12
        resetFloatingWindowAppearance()
        showToast("All settings reset to defaults")
    }

    fun showToast(message: String) {
        _toastMessage.value = message
        toastDismissJob?.cancel()
        toastDismissJob = viewModelScope.launch {
            delay(2500)
            _toastMessage.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.release()
    }
}
