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
import com.example.camera.engine.CameraOpticalCalibration
import com.example.camera.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.absoluteValue

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
    private val preferences = CameraPreferences(application.applicationContext)

    val lastCapturedMedia: StateFlow<CapturedMedia?> = engine.lastCapturedMedia

    // Mode & Base State must be initialized before combined flows
    private val _cameraMode = MutableStateFlow(preferences.cameraMode)
    val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    private val _nightConfig = MutableStateFlow(preferences.getModeNightConfig(preferences.cameraMode))
    val nightConfig: StateFlow<NightConfig> = _nightConfig.asStateFlow()
    val nightProgress: StateFlow<NightCaptureProgress> = engine.nightProgress

    val hybridStabilizationConfig: StateFlow<HybridStabilizationConfig> = engine.hybridStabilizationConfig

    private val _selectedAspectRatio = MutableStateFlow(
        if (preferences.cameraMode == CameraMode.PHOTO || preferences.cameraMode == CameraMode.NIGHT) {
            CameraAspectRatio.RATIO_4_3
        } else {
            CameraAspectRatio.RATIO_9_16
        }
    )
    val selectedAspectRatio: StateFlow<CameraAspectRatio> = _selectedAspectRatio.asStateFlow()

    private val _tapFocusConfig = MutableStateFlow(preferences.getModeTapFocusConfig(preferences.cameraMode))
    val tapFocusConfig: StateFlow<TapFocusConfig> = _tapFocusConfig.asStateFlow()

    val isRecordingVideo: StateFlow<Boolean> = engine.isRecordingVideo
    val isRecordingPaused: StateFlow<Boolean> = engine.isRecordingPaused
    val isSavingVideo: StateFlow<Boolean> = engine.isSavingVideo
    val videoDurationSeconds: StateFlow<Int> = engine.videoDurationSeconds
    val selectedVideoPipeline: StateFlow<com.example.camera.videopipeline.VideoPipelineType> = engine.selectedVideoPipeline

    // Photo Filter State
    private val _selectedPhotoFilter = MutableStateFlow(preferences.getModePhotoFilter(preferences.cameraMode))
    val selectedPhotoFilter: StateFlow<PhotoFilter> = _selectedPhotoFilter.asStateFlow()

    private val _isPhotoFilterBarOpen = MutableStateFlow(false)
    val isPhotoFilterBarOpen: StateFlow<Boolean> = _isPhotoFilterBarOpen.asStateFlow()

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

    // Cinema / Pro Video Mode State & Panel visibility
    val cinemaConfig: StateFlow<CinemaConfig> = engine.cinemaConfig
    val cinemaCapabilities: StateFlow<CinemaHardwareCapabilities> = engine.cinemaCapabilities
    val capabilities: StateFlow<HardwareCapabilities> = engine.capabilities
    private val _isCinemaSettingsOpen = MutableStateFlow(false)
    val isCinemaSettingsOpen: StateFlow<Boolean> = _isCinemaSettingsOpen.asStateFlow()

    // Pro Video Log Profile Floating Window
    private val _isLogProfileWindowOpen = MutableStateFlow(false)
    val isLogProfileWindowOpen: StateFlow<Boolean> = _isLogProfileWindowOpen.asStateFlow()

    fun setLogProfileWindowOpen(open: Boolean) {
        _isLogProfileWindowOpen.value = open
        if (open) {
            _isLutWindowOpen.value = false
            _isCinemaSettingsOpen.value = false
        }
    }

    fun toggleLogProfileWindow() {
        setLogProfileWindowOpen(!_isLogProfileWindowOpen.value)
    }

    // Pro Video LUT Floating Window
    private val _isLutWindowOpen = MutableStateFlow(false)
    val isLutWindowOpen: StateFlow<Boolean> = _isLutWindowOpen.asStateFlow()

    fun setLutWindowOpen(open: Boolean) {
        _isLutWindowOpen.value = open
        if (open) {
            _isLogProfileWindowOpen.value = false
            _isCinemaSettingsOpen.value = false
        }
    }

    fun toggleLutWindow() {
        setLutWindowOpen(!_isLutWindowOpen.value)
    }

    // Video Stabilization Floating Window (OFF / EIS / EIS+)
    private val _isStabilizationWindowOpen = MutableStateFlow(false)
    val isStabilizationWindowOpen: StateFlow<Boolean> = _isStabilizationWindowOpen.asStateFlow()

    fun setStabilizationWindowOpen(open: Boolean) {
        _isStabilizationWindowOpen.value = open
        if (open) {
            _isLogProfileWindowOpen.value = false
            _isLutWindowOpen.value = false
            _isCinemaSettingsOpen.value = false
        }
    }

    fun toggleStabilizationWindow() {
        setStabilizationWindowOpen(!_isStabilizationWindowOpen.value)
    }

    fun selectLogProfile(profile: CinemaColorProfile) {
        val current = cinemaConfig.value
        val canDo10Bit = cinemaCapabilities.value.getSupportedBitDepthsForCodec(current.codec).contains(LogBitDepth.BIT_10)
        val updated = if (profile == CinemaColorProfile.HLG10) {
            current.copy(
                colorProfile = CinemaColorProfile.HLG10,
                colorSpace = CinemaColorSpace.REC_2020,
                logBitDepth = if (canDo10Bit) LogBitDepth.BIT_10 else LogBitDepth.BIT_8,
                codec = if (current.codec == CinemaCodec.H264 && cinemaCapabilities.value.supportedCodecs.contains(CinemaCodec.H265)) CinemaCodec.H265 else current.codec
            )
        } else {
            current.copy(colorProfile = profile)
        }
        updateCinemaConfig(updated)
        showToast("Log Profile: ${profile.label}")
    }

    fun selectLut(lut: CinematicLut) {
        val current = cinemaConfig.value
        val updated = current.copy(
            selectedLut = lut,
            customLutPath = null,
            customLutName = null,
            isBakeLutToOutput = true,
            isLutPreviewEnabled = true
        )
        updateCinemaConfig(updated)
        showToast("LUT Applied: ${lut.label}")
    }

    fun selectCustomLut(path: String, title: String) {
        val current = cinemaConfig.value
        val updated = current.copy(
            selectedLut = CinematicLut.CUSTOM,
            customLutPath = path,
            customLutName = title,
            isBakeLutToOutput = true,
            isLutPreviewEnabled = true
        )
        updateCinemaConfig(updated)
        showToast("Custom LUT: $title")
    }

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

    fun setFloatingWindowGlassStyle(style: com.example.camera.model.FloatingWindowGlassStyle) {
        val preset = when (style) {
            com.example.camera.model.FloatingWindowGlassStyle.LIQUID_GLASS -> com.example.camera.model.FloatingWindowAppearanceConfig.LIQUID_GLASS
            com.example.camera.model.FloatingWindowGlassStyle.FROSTED_GLASS -> com.example.camera.model.FloatingWindowAppearanceConfig.FROSTED_GLASS
            com.example.camera.model.FloatingWindowGlassStyle.TRANSPARENT_GLASS -> com.example.camera.model.FloatingWindowAppearanceConfig.TRANSPARENT_GLASS
            com.example.camera.model.FloatingWindowGlassStyle.SUBTLE_FROST -> com.example.camera.model.FloatingWindowAppearanceConfig.SUBTLE_FROST
            com.example.camera.model.FloatingWindowGlassStyle.DEEP_FROST -> com.example.camera.model.FloatingWindowAppearanceConfig.DEEP_FROST
            com.example.camera.model.FloatingWindowGlassStyle.SOLID_DARK -> com.example.camera.model.FloatingWindowAppearanceConfig.SOLID_DARK
        }
        val current = _floatingWindowAppearance.value
        val merged = preset.copy(
            showVideoResolution = current.showVideoResolution,
            showVideoFps = current.showVideoFps,
            showVideoStabilization = current.showVideoStabilization,
            showCinemaColorProfile = current.showCinemaColorProfile,
            showCinemaLutControls = current.showCinemaLutControls,
            showCinemaResolutionFps = current.showCinemaResolutionFps,
            showCinemaStabilization = current.showCinemaStabilization,
            showCinemaAssistTools = current.showCinemaAssistTools,
            showProExposureControls = current.showProExposureControls,
            showProToneAdjustments = current.showProToneAdjustments,
            showVideoAdjustmentsColorTone = current.showVideoAdjustmentsColorTone,
            showVideoAdjustmentsEffects = current.showVideoAdjustmentsEffects,
            showPipelineMasterToggle = current.showPipelineMasterToggle,
            showPipelinePresetList = current.showPipelinePresetList
        )
        setFloatingWindowAppearance(merged)
    }

    fun resetFloatingWindowAppearance() {
        val default = com.example.camera.model.FloatingWindowAppearanceConfig.LIQUID_GLASS
        setFloatingWindowAppearance(default)
    }

    // Viewfinder Corner Radius (0..48 dp)
    private val _viewfinderCornerRadiusDp = MutableStateFlow(preferences.viewfinderCornerRadiusDp)
    val viewfinderCornerRadiusDp: StateFlow<Int> = _viewfinderCornerRadiusDp.asStateFlow()

    fun setViewfinderCornerRadius(radiusDp: Int) {
        val clamped = radiusDp.coerceIn(0, 48)
        _viewfinderCornerRadiusDp.value = clamped
        preferences.viewfinderCornerRadiusDp = clamped
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

    // Google Photos Compatible Motion Photo
    private val _isMotionPhotoEnabled = MutableStateFlow(preferences.isMotionPhotoEnabled)
    val isMotionPhotoEnabled: StateFlow<Boolean> = _isMotionPhotoEnabled.asStateFlow()

    private val _motionPhotoDuration = MutableStateFlow(preferences.motionPhotoDuration)
    val motionPhotoDuration: StateFlow<com.example.camera.motionphoto.MotionPhotoDuration> = _motionPhotoDuration.asStateFlow()

    val isMotionPhotoRecording: StateFlow<Boolean> = engine.motionPhotoEngine.isRecordingPostShutter

    fun toggleMotionPhoto() {
        val next = !_isMotionPhotoEnabled.value
        _isMotionPhotoEnabled.value = next
        preferences.isMotionPhotoEnabled = next
        engine.isMotionPhotoEnabled = next
        showToast(if (next) "Motion Photo: ON (${_motionPhotoDuration.value.label})" else "Motion Photo: OFF")
    }

    fun setMotionPhotoEnabled(enabled: Boolean) {
        _isMotionPhotoEnabled.value = enabled
        preferences.isMotionPhotoEnabled = enabled
        engine.isMotionPhotoEnabled = enabled
        showToast(if (enabled) "Motion Photo: ON (${_motionPhotoDuration.value.label})" else "Motion Photo: OFF")
    }

    fun setMotionPhotoDuration(duration: com.example.camera.motionphoto.MotionPhotoDuration) {
        _motionPhotoDuration.value = duration
        preferences.motionPhotoDuration = duration
        engine.motionPhotoDuration = duration
        showToast("Motion Photo Duration: ${duration.title}")
    }

    fun onMotionPhotoPreviewFrame(bitmap: Bitmap) {
        engine.onPreviewBitmapFrame(bitmap)
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

    // Floating EV Control Window visibility (Video Mode and Cinema Mode)
    private val _isEvControlOpen = MutableStateFlow(false)
    val isEvControlOpen: StateFlow<Boolean> = _isEvControlOpen.asStateFlow()

    fun setEvControlOpen(isOpen: Boolean) {
        _isEvControlOpen.value = isOpen
        if (isOpen) {
            _isVideoSettingsPanelOpen.value = false
            _isVideoAdjustmentsOpen.value = false
            _isCinemaSettingsOpen.value = false
        }
    }

    fun toggleEvControlOpen() {
        setEvControlOpen(!_isEvControlOpen.value)
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

    // Custom Video Pipeline State (Dedicated "Custom Pipeline" in Video Mode)
    private val _customVideoPipelineConfig = MutableStateFlow(preferences.getCustomVideoPipelineConfig())
    val customVideoPipelineConfig: StateFlow<com.example.camera.videopipeline.CustomVideoPipelineConfig> = _customVideoPipelineConfig.asStateFlow()

    private val _isCustomVideoPipelineSettingsOpen = MutableStateFlow(false)
    val isCustomVideoPipelineSettingsOpen: StateFlow<Boolean> = _isCustomVideoPipelineSettingsOpen.asStateFlow()

    fun setCustomVideoPipelineSettingsOpen(isOpen: Boolean) {
        _isCustomVideoPipelineSettingsOpen.value = isOpen
        if (isOpen) {
            _isVideoSettingsPanelOpen.value = false
            _isVideoAdjustmentsOpen.value = false
            _isSettingsOpen.value = false
            _isManualProOpen.value = false
        }
    }

    fun toggleCustomVideoPipelineSettingsOpen() {
        setCustomVideoPipelineSettingsOpen(!_isCustomVideoPipelineSettingsOpen.value)
    }

    private var saveCustomVideoPipelineJob: kotlinx.coroutines.Job? = null

    fun updateCustomVideoPipelineConfig(config: com.example.camera.videopipeline.CustomVideoPipelineConfig) {
        _customVideoPipelineConfig.value = config
        com.example.camera.videopipeline.VideoPipelineManager.getCustomPipeline().updateConfig(config)
        engine.updatePreviewSettings()

        saveCustomVideoPipelineJob?.cancel()
        saveCustomVideoPipelineJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            preferences.saveCustomVideoPipelineConfig(config)
        }
    }

    fun resetCustomVideoPipelineConfig() {
        saveCustomVideoPipelineJob?.cancel()
        val defaultCfg = com.example.camera.videopipeline.CustomVideoPipelineConfig()
        _customVideoPipelineConfig.value = defaultCfg
        com.example.camera.videopipeline.VideoPipelineManager.getCustomPipeline().updateConfig(defaultCfg)
        engine.updatePreviewSettings()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            preferences.saveCustomVideoPipelineConfig(defaultCfg)
        }
        showToast("Custom Pipeline reset to Cinema Natural baseline")
    }

    fun updateCinemaConfig(config: CinemaConfig) {
        engine.setCinemaConfig(config)
        preferences.saveCinemaConfig(config)
    }

    fun selectCinemaAspectRatio(aspectRatio: com.example.camera.model.CinemaAspectRatio) {
        val current = cinemaConfig.value
        if (current.aspectRatio != aspectRatio) {
            val updated = current.copy(aspectRatio = aspectRatio)
            updateCinemaConfig(updated)
            _selectedAspectRatio.value = when (aspectRatio) {
                com.example.camera.model.CinemaAspectRatio.IMAX -> CameraAspectRatio.RATIO_IMAX
                com.example.camera.model.CinemaAspectRatio.CINEMATIC -> CameraAspectRatio.RATIO_CINEMATIC
                else -> CameraAspectRatio.RATIO_16_9
            }
            engine.setPreviewAspectRatio(aspectRatio.ratioValue)
            showToast("Cinema Ratio: ${aspectRatio.label}")
        }
    }

    fun cycleCinemaAspectRatio() {
        val current = cinemaConfig.value.aspectRatio
        val values = com.example.camera.model.CinemaAspectRatio.values()
        val nextIndex = (values.indexOf(current) + 1) % values.size
        selectCinemaAspectRatio(values[nextIndex])
    }

    fun cycleCinemaResolution() {
        val supported = cinemaCapabilities.value.supportedResolutions.takeIf { it.isNotEmpty() }
            ?: listOf(
                CameraResolution(3840, 2160),
                CameraResolution(1920, 1080),
                CameraResolution(1280, 720)
            )
        val current = cinemaConfig.value.selectedResolution ?: supported.firstOrNull { it.width >= 3840 } ?: supported.first()
        val curIndex = supported.indexOfFirst { it.width == current.width && it.height == current.height }
        val nextIndex = if (curIndex >= 0 && curIndex + 1 < supported.size) curIndex + 1 else 0
        val next = supported[nextIndex]
        updateCinemaConfig(cinemaConfig.value.copy(selectedResolution = next))
        val label = when {
            next.width >= 7680 -> "8K"
            next.width >= 3840 -> "4K"
            next.width >= 1920 -> "1080p"
            else -> "720p"
        }
        showToast("Pro Video Resolution: $label")
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

    private val _isPipelinePresetFloatingWindowOpen = MutableStateFlow(false)
    val isPipelinePresetFloatingWindowOpen: StateFlow<Boolean> = _isPipelinePresetFloatingWindowOpen.asStateFlow()

    private val _isBeforeAfterOpen = MutableStateFlow(false)
    val isBeforeAfterOpen: StateFlow<Boolean> = _isBeforeAfterOpen.asStateFlow()

    val latestPipelineCapture = com.example.camera.pipeline.engine.PipelineCaptureCache.latestCapture

    private val _isReprocessing = MutableStateFlow(false)
    val isReprocessing: StateFlow<Boolean> = _isReprocessing.asStateFlow()

    fun setPipelineSheetOpen(isOpen: Boolean) {
        _isPipelineSheetOpen.value = isOpen
    }

    fun togglePipelinePresetFloatingWindow() {
        _isPipelinePresetFloatingWindowOpen.value = !_isPipelinePresetFloatingWindowOpen.value
    }

    fun setPipelinePresetFloatingWindowOpen(open: Boolean) {
        _isPipelinePresetFloatingWindowOpen.value = open
    }

    fun setBeforeAfterOpen(isOpen: Boolean) {
        _isBeforeAfterOpen.value = isOpen
    }

    fun toggleCustomPipelineEnabled(enabled: Boolean) {
        _isCustomPipelineEnabled.value = enabled
        preferences.isCustomPipelineEnabled = enabled
        showToast(if (enabled) "Custom Image Pipeline: ON" else "Custom Image Pipeline: OFF")
    }

    // --- Camera Switching State ---
    private val _instantSwitchState = MutableStateFlow(
        MotorolaInstantSwitchState(
            isKeepUltraWideReady = preferences.isKeepUltraWideReady,
            isAutoSwitchToUltraWide = preferences.isAutoSwitchToUltraWide,
            isShowUltraWidePreview = preferences.isShowUltraWidePreview,
            isKeepFrontCameraReady = preferences.isKeepFrontCameraReady,
            isShowFrontCameraPreview = preferences.isShowFrontCameraPreview,
            ultraWideStatus = if (preferences.isKeepUltraWideReady) BackgroundCameraStatus.READY_QUIET else BackgroundCameraStatus.OFF,
            switchPointMm = preferences.lensSwitchPointMm
        )
    )
    val instantSwitchState: StateFlow<MotorolaInstantSwitchState> = _instantSwitchState.asStateFlow()

    val lensSwitchPointMm: StateFlow<Float> = engine.lensSwitchPointMm
    val isUsingUltraWideSurface: StateFlow<Boolean> = engine.isUsingUltraWideSurface
    val displayedPreviewSource: StateFlow<com.example.camera.engine.PreviewStreamSource> = engine.displayedPreviewSource

    fun setLensSwitchPointMm(switchPointMm: Float) {
        val clamped = switchPointMm.coerceIn(
            CameraOpticalCalibration.MIN_SWITCH_POINT_MM,
            CameraOpticalCalibration.MAX_SWITCH_POINT_MM
        )
        preferences.lensSwitchPointMm = clamped
        _instantSwitchState.update { it.copy(switchPointMm = clamped) }
        engine.setLensSwitchPointMm(clamped)
        val uwCrop = CameraOpticalCalibration.calculateUltraWideCropForSwitchPoint(clamped)
        val mainCrop = CameraOpticalCalibration.calculateMainCropForSwitchPoint(clamped)
        showToast("Switch Point: ${clamped.toInt()}mm (UW ${String.format(java.util.Locale.US, "%.2f", uwCrop)}×, Main ${String.format(java.util.Locale.US, "%.2f", mainCrop)}×)")
    }

    fun setKeepUltraWideReady(enabled: Boolean) {
        preferences.isKeepUltraWideReady = enabled
        _instantSwitchState.value = _instantSwitchState.value.copy(
            isKeepUltraWideReady = enabled,
            ultraWideStatus = if (enabled) BackgroundCameraStatus.READY_QUIET else BackgroundCameraStatus.OFF
        )
        engine.setKeepUltraWideReady(enabled)
        showToast(if (enabled) "Keep Ultra Wide Ready: ON" else "Keep Ultra Wide Ready: OFF")
    }

    fun setAutoSwitchToUltraWide(enabled: Boolean) {
        preferences.isAutoSwitchToUltraWide = enabled
        _instantSwitchState.update { it.copy(isAutoSwitchToUltraWide = enabled) }
        engine.setAutoSwitchToUltraWide(enabled)
        showToast(if (enabled) "Auto Switch to Ultra Wide: ON" else "Auto Switch to Ultra Wide: OFF")
    }

    fun setShowUltraWidePreview(enabled: Boolean) {
        _instantSwitchState.value = _instantSwitchState.value.copy(isShowUltraWidePreview = enabled)
        showToast(if (enabled) "Ultra-Wide Little Preview: ON" else "Ultra-Wide Little Preview: OFF")
    }

    fun setKeepFrontCameraReady(enabled: Boolean) {
        _instantSwitchState.value = _instantSwitchState.value.copy(isKeepFrontCameraReady = enabled)
        showToast(if (enabled) "Keep Front Camera Ready: ON" else "Keep Front Camera Ready: OFF")
    }

    fun setShowFrontCameraPreview(enabled: Boolean) {
        _instantSwitchState.value = _instantSwitchState.value.copy(isShowFrontCameraPreview = enabled)
        showToast(if (enabled) "Front Camera Little Preview: ON" else "Front Camera Little Preview: OFF")
    }

    fun onUltraWideLittlePreviewSurfaceAvailable(surfaceTexture: SurfaceTexture?) {
        // Safe stub - no background camera HAL lock
    }

    fun onFrontLittlePreviewSurfaceAvailable(surfaceTexture: SurfaceTexture?) {
        // Safe stub - no background camera HAL lock
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
        val status = if (_isCustomPipelineEnabled.value) "Pipeline: ${preset.displayName}" else "${preset.displayName} selected (Pipeline: OFF)"
        showToast(status)
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

    private val _videoStabilizationMode = MutableStateFlow(preferences.getModeVideoStabilizationMode(preferences.cameraMode))
    val videoStabilizationMode: StateFlow<VideoStabilizationMode> = _videoStabilizationMode.asStateFlow()
    private val _isOisEnabled = MutableStateFlow(preferences.isOisEnabled)
    val isOisEnabled: StateFlow<Boolean> = _isOisEnabled.asStateFlow()
    val actualOisHardwareActive: StateFlow<Boolean> = engine.actualOisHardwareActive
    val actualEisHardwareActive: StateFlow<Boolean> = engine.actualEisHardwareActive

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
        val initialOis = preferences.isOisEnabled
        _isOisEnabled.value = initialOis
        engine.isOisEnabled = initialOis
        val initialStabMode = preferences.getModeVideoStabilizationMode(initialMode)
        _videoStabilizationMode.value = initialStabMode
        engine.videoStabilizationMode = initialStabMode
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
        engine.setMode(initialMode)
        engine.restoreInitialVideoResolution(CameraResolution(preferences.videoWidth, preferences.videoHeight))
        engine.setCinemaConfig(preferences.getCinemaConfig())
        engine.updateHybridStabilizationConfig(preferences.hybridStabilizationConfig)
        engine.currentVideoAdjustments = _videoAdjustments.value
        com.example.camera.videopipeline.VideoPipelineManager.getCustomPipeline().updateConfig(_customVideoPipelineConfig.value)
        engine.setKeepUltraWideReady(preferences.isKeepUltraWideReady)
        engine.setAutoSwitchToUltraWide(preferences.isAutoSwitchToUltraWide)

        viewModelScope.launch {
            engine.ultraWideStreamStatus.collect { status ->
                _instantSwitchState.update { it.copy(ultraWideStatus = status) }
            }
        }

        viewModelScope.launch {
            engine.isAutoMacroActive.collect { isMacro ->
                _instantSwitchState.update { it.copy(isAutoMacroActive = isMacro) }
            }
        }

        engine.onLensSwitchCompletedListener = { switchedLens, targetZoom ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (zoomTransitionJob == null || !zoomTransitionJob!!.isActive) {
                    _currentZoom.value = targetZoom
                    preferences.setModeZoom(_cameraMode.value, targetZoom)
                }
            }
        }

        if (preferences.isHorizontalLockSettingEnabled && preferences.isHorizonLockActive) {
            engine.setHorizonLockEnabled(true)
        }

        // Restore initial mode aspect ratio:
        // - Photo mode: fixed 3:4
        // - Night mode: fixed 3:4
        // - Cinema mode: mode-specific cinema aspect ratio (16:9, IMAX, Cinematic)
        // - All other modes: fixed 9:16
        if (initialMode == CameraMode.PHOTO || initialMode == CameraMode.NIGHT) {
            _selectedAspectRatio.value = CameraAspectRatio.RATIO_4_3
            engine.setPreviewAspectRatio(4f / 3f)
        } else if (initialMode == CameraMode.CINEMA) {
            val aspect = engine.cinemaConfig.value.aspectRatio
            _selectedAspectRatio.value = when (aspect) {
                com.example.camera.model.CinemaAspectRatio.IMAX -> CameraAspectRatio.RATIO_IMAX
                com.example.camera.model.CinemaAspectRatio.CINEMATIC -> CameraAspectRatio.RATIO_CINEMATIC
                else -> CameraAspectRatio.RATIO_16_9
            }
            engine.setPreviewAspectRatio(aspect.ratioValue)
        } else {
            _selectedAspectRatio.value = CameraAspectRatio.RATIO_9_16
            engine.setPreviewAspectRatio(16f / 9f)
        }

        viewModelScope.launch {
            engine.availableLenses.collect { lenses ->
                if (lenses.isNotEmpty()) {
                    refreshActiveZoomPresets()
                    val savedModeZoom = preferences.getModeZoom(preferences.cameraMode)
                    val savedModeLens = preferences.getModeLens(preferences.cameraMode, lenses)
                    if (savedModeLens != null && engine.selectedLens.value?.id != savedModeLens.id) {
                        if (savedModeZoom > 0f) {
                            _currentZoom.value = savedModeZoom
                        }
                        engine.selectLens(
                            savedModeLens,
                            preserveZoom = savedModeZoom > 0f,
                            targetZoom = savedModeZoom.takeIf { it > 0f }
                        )
                    } else if (savedModeZoom > 0f) {
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

        // 3. Immediately synchronize aspect ratio:
        // Photo, Night, and Pro modes strictly keep 3:4 aspect ratio.
        // Cinema mode uses selected cinema aspect ratio (16:9, IMAX, Cinematic).
        // Video mode keeps 9:16 aspect ratio.
        if (mode == CameraMode.PHOTO || mode == CameraMode.NIGHT) {
            _selectedAspectRatio.value = CameraAspectRatio.RATIO_4_3
            engine.setPreviewAspectRatio(4f / 3f)
        } else if (mode == CameraMode.CINEMA) {
            val aspect = engine.cinemaConfig.value.aspectRatio
            _selectedAspectRatio.value = when (aspect) {
                com.example.camera.model.CinemaAspectRatio.IMAX -> CameraAspectRatio.RATIO_IMAX
                com.example.camera.model.CinemaAspectRatio.CINEMATIC -> CameraAspectRatio.RATIO_CINEMATIC
                else -> CameraAspectRatio.RATIO_16_9
            }
            engine.setPreviewAspectRatio(aspect.ratioValue)
        } else {
            _selectedAspectRatio.value = CameraAspectRatio.RATIO_9_16
            engine.setPreviewAspectRatio(16f / 9f)
        }

        if (mode == CameraMode.CINEMA) {
            // Set Zebra feature to OFF by default whenever Cinema Mode is opened
            val curCinema = engine.cinemaConfig.value
            if (curCinema.zebraThreshold != com.example.camera.model.ZebraThreshold.OFF) {
                updateCinemaConfig(curCinema.copy(zebraThreshold = com.example.camera.model.ZebraThreshold.OFF))
            }
        }

        if (mode != CameraMode.VIDEO) {
            _isVideoAdjustmentsOpen.value = false
        }
        if (mode != CameraMode.PHOTO) {
            _isManualProOpen.value = false
        }

        // 4. Restore mode-specific controls before switching engine mode to avoid exposure resets
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

        val mStabMode = preferences.getModeVideoStabilizationMode(mode)
        _videoStabilizationMode.value = mStabMode
        engine.videoStabilizationMode = mStabMode
        _isStabilizationWindowOpen.value = false

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
        if (mode == CameraMode.CINEMA) {
            updateCinemaConfig(engine.cinemaConfig.value.copy(exposureCompensation = mEv))
        }
        _isEvControlOpen.value = false

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

        val mNightConfig = preferences.getModeNightConfig(mode)
        _nightConfig.value = mNightConfig

        val mTapFocus = preferences.getModeTapFocusConfig(mode)
        _tapFocusConfig.value = mTapFocus

        val mHybridStab = preferences.getModeHybridStabilizationConfig(mode)
        engine.updateHybridStabilizationConfig(mHybridStab)

        // 5. Switch engine mode to synchronize preview buffer and session with restored settings
        engine.setMode(mode)

        if (mode == CameraMode.AI_SUBJECT_TRACKING) {
            engine.closeCamera()
        } else if (previousMode == CameraMode.AI_SUBJECT_TRACKING) {
            safeInitializeCamera()
        }

        // 6. Restore mode-specific lens and zoom if available
        val modeZoom = preferences.getModeZoom(mode)
        val modeLens = preferences.getModeLens(mode, engine.availableLenses.value)
        if (modeLens != null && modeLens.id != engine.selectedLens.value?.id) {
            if (modeZoom > 0f) {
                _currentZoom.value = modeZoom
            }
            engine.selectLens(
                modeLens,
                preserveZoom = modeZoom > 0f,
                targetZoom = modeZoom.takeIf { it > 0f }
            )
        } else if (modeZoom > 0f) {
            _currentZoom.value = modeZoom
            engine.setZoom(modeZoom, isPresetTap = false)
        } else {
            engine.updatePreviewSettings()
        }

        _isCinemaSettingsOpen.value = false
        if (mode == CameraMode.MORE) {
            _isMoreModesOpen.value = true
        }

        if ((mode == CameraMode.VIDEO || mode == CameraMode.CINEMA) && preferences.isHorizontalLockSettingEnabled && preferences.isHorizonLockActive) {
            engine.setHorizonLockEnabled(true)
            switchToRealUltraWideIfAvailable()
        }
    }

    fun selectLens(lens: LensInfo, instant: Boolean = false) {
        val currentLens = engine.selectedLens.value
        val currentZ = _currentZoom.value

        val targetZ = when (lens.lensType) {
            LensType.ULTRAWIDE -> 0.5f
            LensType.WIDE -> 1.0f
            LensType.TELEPHOTO -> 2.0f
            LensType.TELEPHOTO_3X -> 3.0f
            else -> lens.baseZoomRatio
        }

        val isDifferentLens = currentLens == null || currentLens.id != lens.id || currentLens.lensType != lens.lensType
        val isSignificantZoomChange = (targetZ - currentZ).absoluteValue >= 0.05f

        if (!instant && isDifferentLens && isSignificantZoomChange && lens.facing == (currentLens?.facing ?: lens.facing)) {
            // Smooth continuous sub-step interpolation across complete range to target lens
            val duration = if (currentZ < 1.0f && targetZ == 1.0f) 350L else 500L
            startContinuousZoomTransition(fromZoom = currentZ, targetZoom = targetZ, targetLens = lens, durationMs = duration)
            val lensDesc = when (lens.lensType) {
                LensType.ULTRAWIDE -> "0.5x Ultra-Wide"
                LensType.WIDE -> "1x Main"
                LensType.TELEPHOTO -> "2x Telephoto"
                LensType.TELEPHOTO_3X -> "3x Telephoto"
                LensType.MACRO -> "Macro"
                LensType.FRONT -> "Front Selfie"
            }
            showToast("Switched to $lensDesc Lens")
            return
        }

        if (instant) {
            zoomTransitionJob?.cancel()
            zoomTransitionJob = null
        }

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

    fun selectVideoPipeline(pipeline: com.example.camera.videopipeline.VideoPipelineType) {
        engine.setVideoPipeline(pipeline)
        if (pipeline != com.example.camera.videopipeline.VideoPipelineType.NORMAL) {
            _isVideoAdjustmentsOpen.value = false
        }
        showToast("Video Pipeline: ${pipeline.title} (${pipeline.subtitle})")
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

        // Exposure compensation operates on top of continuous Auto Exposure:
        // Ensure AE is active, unlocked, and adapting continuously to lighting changes
        engine.isAeLocked = false

        // In Video and Cinema modes, clear any manual ISO or shutter speed lock
        // so camera HAL continuously adapts ISO and shutter speed with the AE bias
        if (_cameraMode.value == CameraMode.VIDEO || _cameraMode.value == CameraMode.CINEMA) {
            _manualIso.value = null
            _manualShutterSpeedNs.value = null
            engine.manualIso = null
            engine.manualExposureTimeNs = null
        }

        if (_cameraMode.value == CameraMode.CINEMA) {
            updateCinemaConfig(engine.cinemaConfig.value.copy(
                exposureCompensation = value,
                manualIso = null,
                manualShutterSpeedNs = null
            ))
        }
        engine.updatePreviewSettings()
    }

    fun resetExposureCompensation() {
        setExposureCompensation(0)
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

    // Pro Advanced Image Adjustments (Saturation, Contrast, Highlights, Shadows, Sharpness, Noise Reduction)
    val proSaturation: StateFlow<Float> = engine.proSaturation
    val proContrast: StateFlow<Float> = engine.proContrast
    val proHighlights: StateFlow<Float> = engine.proHighlights
    val proShadows: StateFlow<Float> = engine.proShadows
    val proSharpness: StateFlow<Float> = engine.proSharpness
    val proNoiseReduction: StateFlow<Float> = engine.proNoiseReduction

    fun setProSaturation(value: Float) {
        val clamped = value.coerceIn(-100f, 100f)
        engine.proSaturation.value = clamped
        preferences.proSaturation = clamped
        val params = _activePipelineParams.value
        updatePipelineParams(params.copy(saturation = clamped))
    }

    fun setProContrast(value: Float) {
        val clamped = value.coerceIn(0.5f, 2.0f)
        engine.proContrast.value = clamped
        preferences.proContrast = clamped
        val params = _activePipelineParams.value
        updatePipelineParams(params.copy(contrast = clamped))
    }

    fun setProHighlights(value: Float) {
        val clamped = value.coerceIn(-100f, 100f)
        engine.proHighlights.value = clamped
        preferences.proHighlights = clamped
        val params = _activePipelineParams.value
        updatePipelineParams(params.copy(highlights = clamped))
    }

    fun setProShadows(value: Float) {
        val clamped = value.coerceIn(-100f, 100f)
        engine.proShadows.value = clamped
        preferences.proShadows = clamped
        val params = _activePipelineParams.value
        updatePipelineParams(params.copy(shadows = clamped))
    }

    fun setProSharpness(value: Float) {
        val clamped = value.coerceIn(0f, 100f)
        engine.proSharpness.value = clamped
        preferences.proSharpness = clamped
        val params = _activePipelineParams.value
        updatePipelineParams(params.copy(sharpness = clamped))
    }

    fun setProNoiseReduction(value: Float) {
        val clamped = value.coerceIn(0f, 100f)
        engine.proNoiseReduction.value = clamped
        preferences.proNoiseReduction = clamped
        val params = _activePipelineParams.value
        updatePipelineParams(params.copy(noiseReduction = clamped))
    }

    fun resetProAdjustments() {
        setProSaturation(0f)
        setProContrast(1.0f)
        setProHighlights(0f)
        setProShadows(0f)
        setProSharpness(15f)
        setProNoiseReduction(12f)
        showToast("Reset Pro Adjustments")
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

    private var zoomTransitionJob: Job? = null

    /**
     * Unified continuous zoom interpolation system.
     * Smoothly transitions between any two zoom values over exactly 500 ms (0.5s) using continuous
     * sub-step interpolation with a smooth sinusoidal ease-in-out curve:
     * - Starts slow
     * - Becomes faster in the middle
     * - Slows down smoothly at the end
     * - No jumps, steps, snapping, or predefined zoom-value jumps
     * The entire transition always takes exactly 0.5s regardless of zoom distance (1x->2x, 1x->3x, 1x->5x, 0.5x->1x).
     * Continuous floating-point interpolation from current to target zoom.
     * Synchronizes physical lens switching and digital crop at the exact sub-step threshold where the zoom
     * crosses the optical boundary (e.g., 0.5x <-> 1.0x at 1.000x / 0.999x, 1.0x <-> 2.0x at 1.8x, etc.),
     * with no discrete steps. Instant switching between physical lenses remains independent and instant.
     */
    fun startContinuousZoomTransition(
        fromZoom: Float,
        targetZoom: Float,
        targetLens: LensInfo? = null,
        durationMs: Long = 500L
    ) {
        zoomTransitionJob?.cancel()
        zoomTransitionJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            val currentFacing = engine.selectedLens.value?.facing
            val lensesForFacing = engine.availableLenses.value.filter { currentFacing == null || it.facing == currentFacing }
            val ultraWideLens = lensesForFacing.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                ?: lensesForFacing.firstOrNull { it.lensType == LensType.ULTRAWIDE }
            val mainWideLens = lensesForFacing.firstOrNull { it.isPrimaryMain }
                ?: lensesForFacing.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                ?: lensesForFacing.firstOrNull { it.lensType == LensType.WIDE }
                ?: lensesForFacing.firstOrNull()
            val tele2xLens = lensesForFacing.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical }
            val tele3xLens = lensesForFacing.firstOrNull { it.lensType == LensType.TELEPHOTO_3X && it.isPhysical }

            val minZoom = if (ultraWideLens != null) 0.5f else 1.0f
            val maxLensZoom = lensesForFacing.maxOfOrNull { it.maxZoomRatio } ?: 20.0f
            val maxZoom = maxOf(engine.capabilities.value.maxZoom, maxLensZoom, 20.0f)

            val startZ = fromZoom.coerceIn(minZoom, maxZoom)
            val endZ = targetZoom.coerceIn(minZoom, maxZoom)

            val switchPoint = engine.lensSwitchPointMm.value
            val switchZoom = CameraOpticalCalibration.switchPointToZoom(switchPoint)

            val resolvedDestinationLens = targetLens ?: when {
                endZ < switchZoom -> ultraWideLens
                endZ >= 2.8f && tele3xLens != null -> tele3xLens
                endZ >= 1.8f && tele2xLens != null -> tele2xLens
                else -> mainWideLens
            } ?: engine.resolvedTargetLens.value

            if (kotlin.math.abs(endZ - startZ) < 0.001f) {
                _currentZoom.value = endZ
                preferences.setModeZoom(_cameraMode.value, endZ)
                val destLens = resolvedDestinationLens
                if (destLens != null && !engine.isRunningOnLens(destLens)) {
                    engine.selectLens(destLens, preserveZoom = true, targetZoom = endZ)
                } else {
                    engine.setZoom(endZ, isPresetTap = true)
                }
                zoomTransitionJob = null
                return@launch
            }

            val startTime = android.os.SystemClock.uptimeMillis()
            val frameIntervalMs = 16L // ~60 FPS continuous sub-step updates

            // If starting downward transition into Ultra-Wide (< switchZoom) from near switchZoom (e.g. 1.0x),
            // perform immediate FOV-matched 0ms handoff to Ultra-Wide at switchZoom boundary so the continuous
            // zoom out is handled entirely on the Ultra-Wide sensor with no optical jumps or stream interruptions.
            if (endZ < switchZoom && startZ in (switchZoom * 0.95f)..(switchZoom * 1.15f) && ultraWideLens != null && currentFacing == CameraCharacteristics.LENS_FACING_BACK) {
                if (engine.selectedLens.value?.lensType != LensType.ULTRAWIDE) {
                    engine.selectLens(ultraWideLens, preserveZoom = true, targetZoom = startZ)
                    preferences.lastFacing = ultraWideLens.facing
                    preferences.saveLastLens(ultraWideLens)
                    preferences.setModeLens(_cameraMode.value, ultraWideLens)
                }
            }

            // If starting upward transition toward 1x or crossing switch point, prewarm the main lens
            // and keep it ready in the background for earliest technically possible handoff.
            if (startZ < switchZoom && endZ >= switchZoom && currentFacing == CameraCharacteristics.LENS_FACING_BACK) {
                engine.isContinuousZoomTransitionActive = true
                engine.ensureUltraWideSimultaneousReady()
            }

            var switchedToMainAtHandoff = false

            while (isActive) {
                val now = android.os.SystemClock.uptimeMillis()
                val elapsed = now - startTime
                val isFinal = elapsed >= durationMs

                // Smooth continuous ease-in-out curve across the complete zoom range:
                // - Starts slow
                // - Becomes faster in the middle
                // - Slows down smoothly at the end
                // - Continuous floating-point interpolation with no jumps, steps, or fixed intermediate values
                val rawProgress = if (isFinal) 1f else (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val progress = ((1.0 - kotlin.math.cos(rawProgress.toDouble() * Math.PI)) / 2.0).toFloat()
                val currentZ = if (isFinal) endZ else (startZ + (endZ - startZ) * progress)

                _currentZoom.value = currentZ
                preferences.setModeZoom(_cameraMode.value, currentZ)

                // Continuous zoom update advancing smoothly without artificial clamping or discrete holds
                // Passes isContinuousTransition = true so physical lens handoffs are synchronized within the timeline
                engine.setZoom(currentZ, isPresetTap = false, isContinuousTransition = true)

                // When transitioning up from Ultra-Wide, hand off to Main lens at the earliest
                // technically possible moment when reaching/crossing switchZoom (1x).
                if (startZ < switchZoom && currentZ >= switchZoom && !switchedToMainAtHandoff && mainWideLens != null) {
                    switchedToMainAtHandoff = true
                    if (engine.selectedLens.value?.id != mainWideLens.id) {
                        engine.selectLens(mainWideLens, preserveZoom = true, targetZoom = currentZ, isContinuousTransition = true)
                        preferences.lastFacing = mainWideLens.facing
                        preferences.saveLastLens(mainWideLens)
                        preferences.setModeLens(_cameraMode.value, mainWideLens)
                    }

                    // If target was 1x (or switch point), switch to main 1x lens at earliest moment
                    // with ultra-wide-at-1x phase lasting the absolute minimum time (imperceptible).
                    // Do not unnecessarily wait until the entire zoom animation finishes.
                    if (endZ <= switchZoom + 0.005f) {
                        _currentZoom.value = endZ
                        preferences.setModeZoom(_cameraMode.value, endZ)
                        engine.setZoom(endZ, isPresetTap = false, isContinuousTransition = false)
                        engine.isContinuousZoomTransitionActive = false
                        zoomTransitionJob = null
                        break
                    }
                }

                if (isFinal) {
                    break
                }

                val sleepTime = (frameIntervalMs - (android.os.SystemClock.uptimeMillis() - now)).coerceAtLeast(2L)
                delay(sleepTime)
            }

            // Cleanly finalize at exact destination zoom & lens without sudden late switch
            _currentZoom.value = endZ
            preferences.setModeZoom(_cameraMode.value, endZ)

            if (resolvedDestinationLens != null) {
                preferences.lastFacing = resolvedDestinationLens.facing
                preferences.saveLastLens(resolvedDestinationLens)
                preferences.setModeLens(_cameraMode.value, resolvedDestinationLens)
                if (!engine.isRunningOnLens(resolvedDestinationLens) && engine.targetLens.value?.id != resolvedDestinationLens.id) {
                    engine.selectLens(resolvedDestinationLens, preserveZoom = true, targetZoom = endZ)
                }
            }

            engine.setZoom(endZ, isPresetTap = false, isContinuousTransition = false)
            engine.isContinuousZoomTransitionActive = false
            zoomTransitionJob = null
        }
    }

    fun startSmoothHalfXToOneXTransition(fromZoom: Float = _currentZoom.value) {
        startContinuousZoomTransition(fromZoom = fromZoom, targetZoom = 1.0f, durationMs = 350L)
    }

    fun startSmoothOneXToHalfXTransition(fromZoom: Float = _currentZoom.value, targetZoom: Float = 0.5f) {
        startContinuousZoomTransition(fromZoom = fromZoom, targetZoom = targetZoom, durationMs = 500L)
    }

    fun startSmoothLensTransition(fromZoom: Float, targetZoom: Float, targetLens: LensInfo? = null) {
        val duration = if (fromZoom < 1.0f && targetZoom == 1.0f) 350L else 500L
        startContinuousZoomTransition(fromZoom = fromZoom, targetZoom = targetZoom, targetLens = targetLens, durationMs = duration)
    }

    fun setZoom(zoom: Float, isPresetTap: Boolean = false) {
        val currentFacing = engine.selectedLens.value?.facing
        val lensesForFacing = engine.availableLenses.value.filter { currentFacing == null || it.facing == currentFacing }
        val ultraWideLens = lensesForFacing.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
            ?: lensesForFacing.firstOrNull { it.lensType == LensType.ULTRAWIDE }
            ?: engine.availableLenses.value.firstOrNull { it.lensType == LensType.ULTRAWIDE }

        val minZoom = if (ultraWideLens != null) 0.5f else 1.0f
        val maxLensZoom = lensesForFacing.maxOfOrNull { it.maxZoomRatio } ?: 20.0f
        val maxZoom = maxOf(engine.capabilities.value.maxZoom, maxLensZoom, 20.0f)
        val clamped = zoom.coerceIn(minZoom, maxZoom)

        // Cancel running transition if user initiates a new manual zoom action or slider gesture
        if (!isPresetTap) {
            zoomTransitionJob?.cancel()
            zoomTransitionJob = null
            _currentZoom.value = clamped
            preferences.setModeZoom(_cameraMode.value, clamped)
            engine.setZoom(clamped, isPresetTap = false, isContinuousTransition = true)
            return
        }

        val currentZ = _currentZoom.value

        if (kotlin.math.abs(clamped - currentZ) >= 0.05f) {
            val duration = if (currentZ < 1.0f && clamped == 1.0f) 350L else 500L
            startContinuousZoomTransition(fromZoom = currentZ, targetZoom = clamped, durationMs = duration)
            return
        }

        _currentZoom.value = clamped
        preferences.setModeZoom(_cameraMode.value, clamped)
        engine.setZoom(clamped, isPresetTap = true)
    }

    fun setVideoStabilization(enabled: Boolean) {
        if (enabled) {
            val caps = engine.capabilities.value
            if (caps.supportedVideoResolutions.isNotEmpty() && !caps.supportsEis && !caps.supportsOis && !engine.gyroStabilizationEngine.isGyroAvailable) {
                showToast("Stabilization not supported by hardware")
                return
            }
        }
        _isVideoStabilizationEnabled.value = enabled
        preferences.isVideoStabilizationEnabled = enabled
        preferences.setModeVideoStabilization(_cameraMode.value, enabled)
        engine.isVideoStabilizationEnabled = enabled

        val mode = if (enabled) VideoStabilizationMode.EIS else VideoStabilizationMode.OFF
        _videoStabilizationMode.value = mode
        preferences.videoStabilizationMode = mode
        preferences.setModeVideoStabilizationMode(_cameraMode.value, mode)
        engine.videoStabilizationMode = mode

        // CRITICAL: EIS must NEVER automatically enable or activate hardware OIS.
        // When OIS is OFF in settings, enabling EIS keeps OIS strictly OFF.
        val isOisAllowed = preferences.isOisEnabled
        val currentHybrid = hybridStabilizationConfig.value
        val updatedHybrid = currentHybrid.copy(
            isOisPreferred = isOisAllowed,
            isOisEnabled = isOisAllowed,
            isEisPreferred = enabled,
            isEisOnly = enabled && !isOisAllowed,
            isHybridEnabled = enabled && isOisAllowed
        )
        setHybridStabilizationConfig(updatedHybrid)

        engine.updatePreviewSettings()
        showToast(if (enabled) "Video Stabilization (EIS): ON" else "Video Stabilization: OFF")
    }

    fun toggleVideoStabilization() {
        setVideoStabilization(!_isVideoStabilizationEnabled.value)
    }

    fun setVideoStabilizationMode(mode: VideoStabilizationMode) {
        setVideoStabilization(mode == VideoStabilizationMode.EIS)
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

    val isHorizonLockEnabled: StateFlow<Boolean> = engine.isHorizonLockEnabled
    val horizonRollDegrees: StateFlow<Float> = engine.stableActionHorizonEngine.rollDegreesFlow
    val horizonMotionOffset: StateFlow<Pair<Float, Float>> = engine.stableActionHorizonEngine.motionOffsetFlow

    private val _isHorizontalLockSettingEnabled = MutableStateFlow(preferences.isHorizontalLockSettingEnabled)
    val isHorizontalLockSettingEnabled: StateFlow<Boolean> = _isHorizontalLockSettingEnabled.asStateFlow()

    fun setHorizontalLockSettingEnabled(enabled: Boolean) {
        _isHorizontalLockSettingEnabled.value = enabled
        preferences.isHorizontalLockSettingEnabled = enabled
        if (!enabled) {
            setHorizonLockEnabled(false)
        }
        showToast(if (enabled) "Horizontal Lock: Available in Video & Cinema Mode" else "Horizontal Lock: Disabled")
    }

    fun toggleHorizontalLockSetting() {
        setHorizontalLockSettingEnabled(!_isHorizontalLockSettingEnabled.value)
    }

    fun switchToRealUltraWideIfAvailable(): Boolean {
        val currentFacing = selectedLens.value?.facing ?: CameraCharacteristics.LENS_FACING_BACK
        val realUltraWide = engine.availableLenses.value.firstOrNull {
            it.facing == currentFacing && it.lensType == LensType.ULTRAWIDE && !it.isZoomPreset &&
                (it.isPhysical || it.physicalCameraId != null || it.isIndependentCamera || it.focalLengthMm <= 2.8f || it.fovDegrees >= 95f)
        } ?: engine.availableLenses.value.firstOrNull {
            it.facing == CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.ULTRAWIDE && !it.isZoomPreset &&
                (it.isPhysical || it.physicalCameraId != null || it.isIndependentCamera || it.focalLengthMm <= 2.8f || it.fovDegrees >= 95f)
        }
        if (realUltraWide != null) {
            if (selectedLens.value?.id != realUltraWide.id) {
                Log.i("CameraViewModel", "Horizontal Lock active: auto-switching to real ultra-wide lens ${realUltraWide.displayName}")
                selectLens(realUltraWide)
            }
            return true
        } else {
            Log.i("CameraViewModel", "No real ultra-wide lens available on device; continuing with current lens")
            return false
        }
    }

    fun toggleHorizonLock() {
        val newState = !engine.isHorizonLockEnabled.value
        setHorizonLockEnabled(newState)
    }

    fun setHorizonLockEnabled(enabled: Boolean) {
        preferences.isHorizonLockActive = enabled
        engine.setHorizonLockEnabled(enabled)
        if (enabled) {
            switchToRealUltraWideIfAvailable()
        }
        showToast(if (enabled) "Horizontal Lock: ON" else "Horizontal Lock: OFF")
    }

    private val _isDollyZoomSettingEnabled = MutableStateFlow(preferences.isDollyZoomSettingEnabled)
    val isDollyZoomSettingEnabled: StateFlow<Boolean> = _isDollyZoomSettingEnabled.asStateFlow()

    fun setDollyZoomSettingEnabled(enabled: Boolean) {
        _isDollyZoomSettingEnabled.value = enabled
        preferences.isDollyZoomSettingEnabled = enabled
        if (!enabled) {
            setDollyZoomActive(false)
        }
        showToast(if (enabled) "Dolly Zoom: Available in Video Mode" else "Dolly Zoom: Disabled")
    }

    val isDollyZoomActive: StateFlow<Boolean> = engine.isDollyZoomActive
    val dollyCropState: StateFlow<com.example.camera.dollyzoom.DollyCropState> = engine.dollyZoomEngine.cropStateFlow

    fun toggleDollyZoomActive() {
        val next = !engine.isDollyZoomActive.value
        setDollyZoomActive(next)
    }

    fun setDollyZoomActive(active: Boolean) {
        engine.setDollyZoomActive(active)
        showToast(if (active) "Dolly Zoom: ON (Locking subject)" else "Dolly Zoom: OFF")
    }

    fun onTapToLockDollySubject(normX: Float, normY: Float) {
        engine.dollyZoomEngine.lockSubjectAt(normX, normY)
        showToast("Dolly Zoom: Subject Locked")
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
        val isOisAllowed = preferences.isOisEnabled
        val updated = when (mode) {
            MainCameraStabilizationMode.OFF -> current.copy(
                isHybridEnabled = false,
                isOisPreferred = false,
                isOisEnabled = false,
                isEisPreferred = false,
                isUltraStabilizationEnabled = false,
                isEisOnly = false
            )
            MainCameraStabilizationMode.OIS_ONLY -> current.copy(
                isHybridEnabled = false,
                isOisPreferred = isOisAllowed,
                isOisEnabled = isOisAllowed,
                isEisPreferred = false,
                isUltraStabilizationEnabled = false,
                isEisOnly = false
            )
            MainCameraStabilizationMode.EIS_ONLY -> current.copy(
                isHybridEnabled = false,
                isOisPreferred = false,
                isOisEnabled = isOisAllowed,
                isEisPreferred = true,
                isUltraStabilizationEnabled = false,
                isEisOnly = true
            )
            MainCameraStabilizationMode.HYBRID_OIS_EIS -> current.copy(
                isHybridEnabled = true,
                isOisPreferred = isOisAllowed,
                isOisEnabled = isOisAllowed,
                isEisPreferred = true,
                isUltraStabilizationEnabled = false,
                isEisOnly = !isOisAllowed
            )
            MainCameraStabilizationMode.ULTRA -> current.copy(
                isHybridEnabled = true,
                isOisPreferred = isOisAllowed,
                isOisEnabled = isOisAllowed,
                isEisPreferred = true,
                isUltraStabilizationEnabled = true,
                isEisOnly = !isOisAllowed
            )
        }
        val isStabOn = (mode != MainCameraStabilizationMode.OFF)
        _isVideoStabilizationEnabled.value = isStabOn
        preferences.isVideoStabilizationEnabled = isStabOn
        preferences.setModeVideoStabilization(_cameraMode.value, isStabOn)
        engine.isVideoStabilizationEnabled = isStabOn
        setHybridStabilizationConfig(updated)
        showToast("Stabilization: ${mode.title}")
    }

    fun toggleUltraStabilization() {
        val current = hybridStabilizationConfig.value
        val nextState = !current.isUltraStabilizationEnabled
        val isOisAllowed = preferences.isOisEnabled
        val updated = current.copy(
            isUltraStabilizationEnabled = nextState,
            isHybridEnabled = if (nextState) true else current.isHybridEnabled,
            isEisPreferred = if (nextState) true else current.isEisPreferred,
            isOisPreferred = if (nextState) (current.isOisPreferred && isOisAllowed) else current.isOisPreferred,
            isOisEnabled = isOisAllowed
        )
        if (nextState) {
            _isVideoStabilizationEnabled.value = true
            preferences.isVideoStabilizationEnabled = true
            preferences.setModeVideoStabilization(_cameraMode.value, true)
            engine.isVideoStabilizationEnabled = true
        }
        setHybridStabilizationConfig(updated)
        showToast(if (nextState) "Ultra Action Steady: Active" else "Ultra Stabilization: Off")
    }

    fun setOisEnabled(enabled: Boolean) {
        _isOisEnabled.value = enabled
        preferences.isOisEnabled = enabled
        preferences.isOisPreferred = enabled
        engine.isOisEnabled = enabled

        val current = hybridStabilizationConfig.value
        val updated = current.copy(
            isOisPreferred = enabled,
            isOisEnabled = enabled,
            isHybridEnabled = if (!enabled && !current.isEisPreferred) false else current.isHybridEnabled,
            isEisOnly = if (!enabled && current.isEisPreferred) true else (if (!enabled) false else current.isEisOnly)
        )
        setHybridStabilizationConfig(updated)
        engine.updatePreviewSettings()
        showToast(if (enabled) "Optical Image Stabilization (OIS): ON" else "Optical Image Stabilization (OIS): OFF")
    }

    fun setOisPreferred(enabled: Boolean) {
        setOisEnabled(enabled)
    }

    fun setTapFocusConfig(config: TapFocusConfig) {
        _tapFocusConfig.value = config
        preferences.tapFocusConfig = config
        preferences.setModeTapFocusConfig(_cameraMode.value, config)
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

    fun onMainActionButtonClick() {
        when (_cameraMode.value) {
            CameraMode.PHOTO, CameraMode.MORE, CameraMode.AI_SUBJECT_TRACKING -> triggerPhotoCapture()
            CameraMode.VIDEO, CameraMode.CINEMA -> triggerVideoCapture()
            CameraMode.NIGHT -> triggerNightCapture()
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

    private fun triggerVideoCapture() {
        if (engine.isRecordingVideo.value) {
            val isCinema = _cameraMode.value == CameraMode.CINEMA
            com.example.camera.sound.CameraSoundManager.playStopVideo()
            engine.stopVideoRecording { savedUri ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (savedUri != null) {
                        val message = if (isCinema) "Cinema video saved to DCIM/Camera" else "Video saved to DCIM/Camera"
                        showToast(message)
                    } else {
                        val errorMessage = if (isCinema) "Failed to save cinema video" else "Failed to save video"
                        showToast(errorMessage)
                    }
                }
            }
        } else {
            com.example.camera.sound.CameraSoundManager.playStartVideo()
            engine.startVideoRecording { error ->
                showToast("Recording error: $error")
            }
        }
    }

    fun pauseVideoRecording() {
        if (engine.isRecordingVideo.value && !engine.isRecordingPaused.value) {
            engine.pauseVideoRecording()
            showToast("Recording paused")
        }
    }

    fun resumeVideoRecording() {
        if (engine.isRecordingVideo.value && engine.isRecordingPaused.value) {
            engine.resumeVideoRecording()
            showToast("Recording resumed")
        }
    }

    fun toggleVideoRecordingPause() {
        if (!engine.isRecordingVideo.value) return
        if (engine.isRecordingPaused.value) {
            resumeVideoRecording()
        } else {
            pauseVideoRecording()
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
