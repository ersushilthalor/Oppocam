package com.example.camera.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.camera.data.CustomLutItem
import com.example.camera.data.CustomLutRepository
import com.example.camera.model.*
import com.example.camera.ui.components.FrostedGlassBox
import com.example.camera.viewmodel.CameraViewModel
import kotlin.math.roundToInt

/**
 * Dedicated Settings Pages.
 */
enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector) {
    PHOTO("Photo Settings", "Resolutions, HDR, RAW & 50MP", Icons.Outlined.CameraAlt),
    VIDEO("Video Settings", "Resolution, Frame Rate, Codec & Bitrate", Icons.Outlined.Videocam),
    CINEMA("Pro Video Settings", "Resolution, FPS, Bitrate, Codec, Noise Reduction, Sharpness, Log & LUTs", Icons.Outlined.Movie),
    PRO_MANUAL("Pro / Manual Settings", "ISO, Shutter, Focus, WB & Image Pipeline", Icons.Outlined.Tune),
    CAMERA_LENS("Camera & Lens Settings", "Hardware lenses & Viewfinder", Icons.Outlined.Lens),
    UI_LAYOUT("UI & Layout Settings", "Templates, Floating windows & Custom studio", Icons.Outlined.DashboardCustomize),
    GENERAL("General Settings", "Volume key, Double tap, Feedback & Thermal", Icons.Outlined.Settings),
    ABOUT("About & Hardware Info", "Device camera specs, Sensor & Storage stats", Icons.Outlined.Info)
}

/**
 * Premium Dark Theme Camera Settings UI.
 * Features:
 * - Deep black backgrounds, subtle borders, clean typography, modern settings cards.
 * - Dedicated sub-pages for every category with smooth navigation and back buttons.
 * - Direct functional ON/OFF toggles and adjustments.
 * - All text horizontally oriented with natural wrapping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDrawer(
    isOpen: Boolean,
    cameraMode: CameraMode,
    capabilities: HardwareCapabilities,
    availableLenses: List<LensInfo> = emptyList(),
    selectedLens: LensInfo? = null,
    selectedPhotoResolution: CameraResolution?,
    selectedVideoResolution: CameraResolution?,
    photoMegapixelMode: PhotoMegapixelMode = PhotoMegapixelMode.M12,
    isRefocusPhotoEnabled: Boolean = false,
    refocusFrameCount: Int = 5,
    isUltraFastShutterEnabled: Boolean = false,
    ultraFastShutterFps: Int = 15,
    onUltraFastShutterToggle: (Boolean) -> Unit = {},
    onUltraFastShutterFpsChange: (Int) -> Unit = {},
    zoomPresetsMode: String = "STANDARD",
    customZoomPresetsStr: String = "1, 2, 4, 8",
    onZoomPresetsModeSelect: (String) -> Unit = {},
    onCustomZoomPresetsChange: (String) -> Unit = {},
    videoFps: Int = 30,
    videoBitrate: VideoBitrateOption = VideoBitrateOption.AUTO,
    isVideoStabilizationEnabled: Boolean = true,
    isHorizontalLockSettingEnabled: Boolean = true,
    onHorizontalLockSettingToggle: (Boolean) -> Unit = {},
    isDollyZoomSettingEnabled: Boolean = false,
    onDollyZoomSettingToggle: (Boolean) -> Unit = {},
    isAudioEnabled: Boolean = true,
    isRawEnabled: Boolean = false,
    saveSelfieAsPreviewed: Boolean = true,
    gridType: GridType = GridType.NONE,
    cinemaConfig: CinemaConfig = CinemaConfig(),
    cinemaCapabilities: CinemaHardwareCapabilities = CinemaHardwareCapabilities(),
    viewfinderResolution: ViewfinderResolution = ViewfinderResolution.NORMAL,
    hybridStabilizationConfig: HybridStabilizationConfig = HybridStabilizationConfig(),
    tapFocusConfig: TapFocusConfig = TapFocusConfig(),
    mainCameraStabilizationMode: MainCameraStabilizationMode = MainCameraStabilizationMode.HYBRID_OIS_EIS,
    onMainCameraStabilizationModeSelected: (MainCameraStabilizationMode) -> Unit = {},
    videoCodec: String = "HEVC",
    jpegQuality: Int = 95,
    volumeKeyAction: String = "SHUTTER",
    doubleTapAction: String = "FLIP",
    shutterFeedback: String = "SOUND_AND_HAPTIC",
    antibandingMode: String = "AUTO",
    windNoiseReduction: Boolean = true,
    audioSource: String = "CAMCORDER",
    horizonLeveler: Boolean = true,
    viewfinderFps: Int = 60,
    thermalProtection: Boolean = true,
    isAutoHdrEnabled: Boolean = true,
    isAiAutoFramingEnabled: Boolean = false,
    currentZoom: Float = 1.0f,
    exposureCompensation: Int = 0,
    manualIso: Int? = null,
    manualShutterSpeedNs: Long? = null,
    focusMode: FocusMode = FocusMode.CONTINUOUS,
    manualFocusDistance: Float = 0.0f,
    selectedPhotoFilter: PhotoFilter = PhotoFilter.ORIGINAL,
    // Callbacks
    onVideoCodecSelected: (String) -> Unit = {},
    onJpegQualitySelected: (Int) -> Unit = {},
    onVolumeKeyActionSelected: (String) -> Unit = {},
    onDoubleTapActionSelected: (String) -> Unit = {},
    onShutterFeedbackSelected: (String) -> Unit = {},
    onAntibandingModeSelected: (String) -> Unit = {},
    onWindNoiseReductionToggle: (Boolean) -> Unit = {},
    onAudioSourceSelected: (String) -> Unit = {},
    onHorizonLevelerToggle: (Boolean) -> Unit = {},
    onViewfinderFpsSelected: (Int) -> Unit = {},
    onThermalProtectionToggle: (Boolean) -> Unit = {},
    onAutoHdrToggle: (Boolean) -> Unit = {},
    onAiAutoFramingToggle: (Boolean) -> Unit = {},
    onZoomChange: (Float) -> Unit = {},
    onExposureCompensationChange: (Int) -> Unit = {},
    onManualIsoChange: (Int?) -> Unit = {},
    onManualShutterSpeedChange: (Long?) -> Unit = {},
    onFocusModeChange: (FocusMode) -> Unit = {},
    onManualFocusDistanceChange: (Float) -> Unit = {},
    onPhotoFilterSelected: (PhotoFilter) -> Unit = {},
    onResetAllSettings: () -> Unit = {},
    // UI Customization callbacks
    uiCustomizationState: UiCustomizationState = UiCustomizationState(),
    onSelectTemplate: (UiTemplateType) -> Unit = {},
    onUpdateGlobalLayoutConfig: (ModeLayoutConfig) -> Unit = {},
    onUpdateModeLayoutConfig: (CameraMode, ModeLayoutConfig) -> Unit = { _, _ -> },
    onResetModeLayoutConfig: (CameraMode) -> Unit = {},
    onSaveCustomPreset: (String, ModeLayoutConfig) -> Unit = { _, _ -> },
    onLoadCustomPreset: (CustomUiPreset) -> Unit = {},
    onDeleteCustomPreset: (String) -> Unit = {},
    onResetAllToTemplate: (UiTemplateType) -> Unit = {},
    onLensSelected: (LensInfo) -> Unit = {},
    onForceDeepScan: () -> Unit = {},
    onPhotoResolutionSelected: (CameraResolution) -> Unit = {},
    onPhotoMegapixelModeSelected: (PhotoMegapixelMode) -> Unit = {},
    onRefocusPhotoToggle: (Boolean) -> Unit = {},
    onRefocusFrameCountChange: (Int) -> Unit = {},
    onVideoResolutionSelected: (CameraResolution) -> Unit = {},
    onViewfinderResolutionSelected: (ViewfinderResolution) -> Unit = {},
    onVideoFpsSelected: (Int) -> Unit = {},
    onVideoBitrateSelected: (VideoBitrateOption) -> Unit = {},
    onStabilizationToggle: (Boolean) -> Unit = {},
    onHybridStabilizationChange: (HybridStabilizationConfig) -> Unit = {},
    onOisToggle: (Boolean) -> Unit = {},
    onUltraStabilizationToggle: () -> Unit = {},
    onTapFocusConfigChange: (TapFocusConfig) -> Unit = {},
    onAudioToggle: () -> Unit = {},
    onRawToggle: () -> Unit = {},
    onSaveSelfieAsPreviewedToggle: (Boolean) -> Unit = {},
    onGridTypeSelected: (GridType) -> Unit = {},
    onCinemaConfigChange: (CinemaConfig) -> Unit = {},
    onOpenCustomUiStudio: () -> Unit = {},
    isCustomPipelineEnabled: Boolean = false,
    activePipelinePreset: com.example.camera.pipeline.model.PipelinePreset = com.example.camera.pipeline.model.PipelinePreset.HASSELBLAD,
    onCustomPipelineToggle: (Boolean) -> Unit = {},
    onSelectPipelinePreset: (com.example.camera.pipeline.model.PipelinePreset) -> Unit = {},
    onOpenPipelineStudio: () -> Unit = {},
    onOpenBeforeAfter: () -> Unit = {},
    instantSwitchState: MotorolaInstantSwitchState = MotorolaInstantSwitchState(),
    onKeepUltraWideReadyToggle: (Boolean) -> Unit = {},
    onAutoSwitchToUltraWideToggle: (Boolean) -> Unit = {},
    onShowUltraWidePreviewToggle: (Boolean) -> Unit = {},
    onShowFrontCameraPreviewToggle: (Boolean) -> Unit = {},
    floatingWindowAppearance: FloatingWindowAppearanceConfig = FloatingWindowAppearanceConfig.GLASSMORPHISM,
    onFloatingWindowTransparencyChange: (Float) -> Unit = {},
    onFloatingWindowBlurStrengthChange: (Float) -> Unit = {},
    onFloatingWindowAppearanceChange: (FloatingWindowAppearanceConfig) -> Unit = {},
    onResetFloatingWindowAppearance: () -> Unit = {},
    preferredGalleryPackage: String? = null,
    onOpenGalleryChooser: () -> Unit = {},
    viewfinderCornerRadiusDp: Int = 0,
    onViewfinderCornerRadiusChange: (Int) -> Unit = {},
    lensSwitchOverlapDurationSec: Float = 0.3f,
    onLensSwitchOverlapDurationChange: (Float) -> Unit = {},
    lensSwitchHoldDurationSec: Float = 0.10f,
    onLensSwitchHoldDurationChange: (Float) -> Unit = {},
    isLensSwitchOverlapAnimationEnabled: Boolean = false,
    onLensSwitchOverlapAnimationToggle: (Boolean) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    if (!isOpen) return

    var currentPage by remember { mutableStateOf<SettingsPage?>(null) }

    // Intercept back button to return to overview before closing drawer
    BackHandler(enabled = isOpen) {
        if (currentPage != null) {
            currentPage = null
        } else {
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090A0F))
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("settings_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Top Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (currentPage != null) {
                        IconButton(
                            onClick = { currentPage = null },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.1f))
                                .testTag("settings_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to categories",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = currentPage?.title ?: "CAMERA SETTINGS",
                            color = Color(0xFFFFD54F),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        if (currentPage != null) {
                            Text(
                                text = currentPage!!.subtitle,
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        } else {
                            Text(
                                text = "Preferences, hardware lenses & processing pipelines",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .testTag("close_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Content Area: Either Categories Overview or Dedicated Page
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "settings_page_transition",
                modifier = Modifier.fillMaxSize()
            ) { targetPage ->
                if (targetPage == null) {
                    // 1. Categories Overview
                    SettingsOverviewPage(
                        onSelectPage = { currentPage = it },
                        onResetAll = onResetAllSettings
                    )
                } else {
                    // 2. Dedicated Sub-Pages
                    when (targetPage) {
                        SettingsPage.PHOTO -> PhotoSettingsPage(
                            isAutoHdrEnabled = isAutoHdrEnabled,
                            onAutoHdrToggle = onAutoHdrToggle,
                            photoMegapixelMode = photoMegapixelMode,
                            onPhotoMegapixelModeSelected = onPhotoMegapixelModeSelected,
                            selectedPhotoResolution = selectedPhotoResolution,
                            onPhotoResolutionSelected = onPhotoResolutionSelected,
                            capabilities = capabilities,
                            isRawEnabled = isRawEnabled,
                            onRawToggle = onRawToggle,
                            saveSelfieAsPreviewed = saveSelfieAsPreviewed,
                            onSaveSelfieAsPreviewedToggle = onSaveSelfieAsPreviewedToggle,
                            isRefocusPhotoEnabled = isRefocusPhotoEnabled,
                            onRefocusPhotoToggle = onRefocusPhotoToggle,
                            refocusFrameCount = refocusFrameCount,
                            onRefocusFrameCountChange = onRefocusFrameCountChange,
                            isUltraFastShutterEnabled = isUltraFastShutterEnabled,
                            onUltraFastShutterToggle = onUltraFastShutterToggle,
                            ultraFastShutterFps = ultraFastShutterFps,
                            onUltraFastShutterFpsChange = onUltraFastShutterFpsChange,
                            jpegQuality = jpegQuality,
                            onJpegQualitySelected = onJpegQualitySelected,
                            selectedPhotoFilter = selectedPhotoFilter,
                            onPhotoFilterSelected = onPhotoFilterSelected
                        )
                        SettingsPage.VIDEO -> VideoSettingsPage(
                            selectedVideoResolution = selectedVideoResolution,
                            onVideoResolutionSelected = onVideoResolutionSelected,
                            capabilities = capabilities,
                            videoFps = videoFps,
                            onVideoFpsSelected = onVideoFpsSelected,
                            videoBitrate = videoBitrate,
                            onVideoBitrateSelected = onVideoBitrateSelected,
                            videoCodec = videoCodec,
                            onVideoCodecSelected = onVideoCodecSelected,
                            isAudioEnabled = isAudioEnabled,
                            onAudioToggle = onAudioToggle,
                            windNoiseReduction = windNoiseReduction,
                            onWindNoiseReductionToggle = onWindNoiseReductionToggle,
                            audioSource = audioSource,
                            onAudioSourceSelected = onAudioSourceSelected,
                            isVideoStabilizationEnabled = isVideoStabilizationEnabled,
                            onStabilizationToggle = onStabilizationToggle,
                            isOisPreferred = hybridStabilizationConfig.isOisEnabled && hybridStabilizationConfig.isOisPreferred,
                            onOisToggle = onOisToggle,
                            isHorizontalLockSettingEnabled = isHorizontalLockSettingEnabled,
                            onHorizontalLockSettingToggle = onHorizontalLockSettingToggle,
                            isUltraStabilizationEnabled = hybridStabilizationConfig.isUltraStabilizationEnabled,
                            onUltraStabilizationToggle = onUltraStabilizationToggle,
                            isDollyZoomSettingEnabled = isDollyZoomSettingEnabled,
                            onDollyZoomSettingToggle = onDollyZoomSettingToggle
                        )
                        SettingsPage.CINEMA -> CinemaSettingsPage(
                            cinemaConfig = cinemaConfig,
                            onCinemaConfigChange = onCinemaConfigChange,
                            cinemaCapabilities = cinemaCapabilities,
                            capabilities = capabilities
                        )
                        SettingsPage.PRO_MANUAL -> ProManualSettingsPage(
                            manualIso = manualIso,
                            onManualIsoChange = onManualIsoChange,
                            manualShutterSpeedNs = manualShutterSpeedNs,
                            onManualShutterSpeedChange = onManualShutterSpeedChange,
                            focusMode = focusMode,
                            onFocusModeChange = onFocusModeChange,
                            manualFocusDistance = manualFocusDistance,
                            onManualFocusDistanceChange = onManualFocusDistanceChange,
                            exposureCompensation = exposureCompensation,
                            onExposureCompensationChange = onExposureCompensationChange,
                            capabilities = capabilities,
                            isCustomPipelineEnabled = isCustomPipelineEnabled,
                            onCustomPipelineToggle = onCustomPipelineToggle,
                            activePipelinePreset = activePipelinePreset,
                            onSelectPipelinePreset = onSelectPipelinePreset,
                            onOpenPipelineStudio = onOpenPipelineStudio,
                            onOpenBeforeAfter = onOpenBeforeAfter
                        )
                        SettingsPage.CAMERA_LENS -> CameraLensSettingsPage(
                            availableLenses = availableLenses,
                            selectedLens = selectedLens,
                            onLensSelected = onLensSelected,
                            onForceDeepScan = onForceDeepScan,
                            instantSwitchState = instantSwitchState,
                            lensSwitchOverlapDurationSec = lensSwitchOverlapDurationSec,
                            onLensSwitchOverlapDurationChange = onLensSwitchOverlapDurationChange,
                            lensSwitchHoldDurationSec = lensSwitchHoldDurationSec,
                            onLensSwitchHoldDurationChange = onLensSwitchHoldDurationChange,
                            isLensSwitchOverlapAnimationEnabled = isLensSwitchOverlapAnimationEnabled,
                            onLensSwitchOverlapAnimationToggle = onLensSwitchOverlapAnimationToggle,
                            onKeepUltraWideReadyToggle = onKeepUltraWideReadyToggle,
                            onAutoSwitchToUltraWideToggle = onAutoSwitchToUltraWideToggle,
                            onShowUltraWidePreviewToggle = onShowUltraWidePreviewToggle,
                            onShowFrontCameraPreviewToggle = onShowFrontCameraPreviewToggle,
                            viewfinderFps = viewfinderFps,
                            onViewfinderFpsSelected = onViewfinderFpsSelected,
                            viewfinderResolution = viewfinderResolution,
                            onViewfinderResolutionSelected = onViewfinderResolutionSelected,
                            gridType = gridType,
                            onGridTypeSelected = onGridTypeSelected,
                            horizonLeveler = horizonLeveler,
                            onHorizonLevelerToggle = onHorizonLevelerToggle
                        )
                        SettingsPage.UI_LAYOUT -> UiLayoutSettingsPage(
                            uiCustomizationState = uiCustomizationState,
                            onSelectTemplate = onSelectTemplate,
                            onOpenCustomUiStudio = onOpenCustomUiStudio,
                            onUpdateModeLayoutConfig = onUpdateModeLayoutConfig,
                            onUpdateGlobalLayoutConfig = onUpdateGlobalLayoutConfig,
                            floatingWindowAppearance = floatingWindowAppearance,
                            onFloatingWindowTransparencyChange = onFloatingWindowTransparencyChange,
                            onFloatingWindowBlurStrengthChange = onFloatingWindowBlurStrengthChange,
                            onFloatingWindowAppearanceChange = onFloatingWindowAppearanceChange,
                            onResetFloatingWindowAppearance = onResetFloatingWindowAppearance,
                            viewfinderCornerRadiusDp = viewfinderCornerRadiusDp,
                            onViewfinderCornerRadiusChange = onViewfinderCornerRadiusChange,
                            viewfinderResolution = viewfinderResolution,
                            onViewfinderResolutionSelected = onViewfinderResolutionSelected
                        )
                        SettingsPage.GENERAL -> GeneralSettingsPage(
                            volumeKeyAction = volumeKeyAction,
                            onVolumeKeyActionSelected = onVolumeKeyActionSelected,
                            doubleTapAction = doubleTapAction,
                            onDoubleTapActionSelected = onDoubleTapActionSelected,
                            shutterFeedback = shutterFeedback,
                            onShutterFeedbackSelected = onShutterFeedbackSelected,
                            antibandingMode = antibandingMode,
                            onAntibandingModeSelected = onAntibandingModeSelected,
                            thermalProtection = thermalProtection,
                            onThermalProtectionToggle = onThermalProtectionToggle,
                            isAiAutoFramingEnabled = isAiAutoFramingEnabled,
                            onAiAutoFramingToggle = onAiAutoFramingToggle,
                            preferredGalleryPackage = preferredGalleryPackage,
                            onOpenGalleryChooser = onOpenGalleryChooser,
                            onResetAll = onResetAllSettings
                        )
                        SettingsPage.ABOUT -> AboutSettingsPage(
                            capabilities = capabilities,
                            availableLenses = availableLenses,
                            selectedLens = selectedLens
                        )
                    }
                }
            }
        }
    }
}

/**
 * Main Overview list displaying all dedicated categories.
 */
@Composable
private fun SettingsOverviewPage(
    onSelectPage: (SettingsPage) -> Unit,
    onResetAll: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {

        items(SettingsPage.entries.toTypedArray()) { page ->
            CategoryCard(
                title = page.title,
                subtitle = page.subtitle,
                icon = page.icon,
                onClick = { onSelectPage(page) }
            )
        }

    }
}

// -------------------------------------------------------------
// DEDICATED SUB-PAGES
// -------------------------------------------------------------

@Composable
private fun PhotoSettingsPage(
    isAutoHdrEnabled: Boolean,
    onAutoHdrToggle: (Boolean) -> Unit,
    photoMegapixelMode: PhotoMegapixelMode,
    onPhotoMegapixelModeSelected: (PhotoMegapixelMode) -> Unit,
    selectedPhotoResolution: CameraResolution?,
    onPhotoResolutionSelected: (CameraResolution) -> Unit,
    capabilities: HardwareCapabilities,
    isRawEnabled: Boolean,
    onRawToggle: () -> Unit,
    saveSelfieAsPreviewed: Boolean,
    onSaveSelfieAsPreviewedToggle: (Boolean) -> Unit,
    isRefocusPhotoEnabled: Boolean,
    onRefocusPhotoToggle: (Boolean) -> Unit,
    refocusFrameCount: Int,
    onRefocusFrameCountChange: (Int) -> Unit,
    isUltraFastShutterEnabled: Boolean,
    onUltraFastShutterToggle: (Boolean) -> Unit,
    ultraFastShutterFps: Int,
    onUltraFastShutterFpsChange: (Int) -> Unit,
    jpegQuality: Int,
    onJpegQualitySelected: (Int) -> Unit,
    selectedPhotoFilter: PhotoFilter,
    onPhotoFilterSelected: (PhotoFilter) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {







        if (isRefocusPhotoEnabled) {
            }


        if (isUltraFastShutterEnabled) {
            }

    }
}

@Composable
private fun VideoSettingsPage(
    selectedVideoResolution: CameraResolution?,
    onVideoResolutionSelected: (CameraResolution) -> Unit,
    capabilities: HardwareCapabilities,
    videoFps: Int,
    onVideoFpsSelected: (Int) -> Unit,
    videoBitrate: VideoBitrateOption,
    onVideoBitrateSelected: (VideoBitrateOption) -> Unit,
    videoCodec: String,
    onVideoCodecSelected: (String) -> Unit,
    isAudioEnabled: Boolean,
    onAudioToggle: () -> Unit,
    windNoiseReduction: Boolean,
    onWindNoiseReductionToggle: (Boolean) -> Unit,
    audioSource: String,
    onAudioSourceSelected: (String) -> Unit,
    isVideoStabilizationEnabled: Boolean,
    onStabilizationToggle: (Boolean) -> Unit,
    isOisPreferred: Boolean = true,
    onOisToggle: (Boolean) -> Unit = {},
    isHorizontalLockSettingEnabled: Boolean = true,
    onHorizontalLockSettingToggle: (Boolean) -> Unit = {},
    isUltraStabilizationEnabled: Boolean,
    onUltraStabilizationToggle: () -> Unit,
    isDollyZoomSettingEnabled: Boolean = false,
    onDollyZoomSettingToggle: (Boolean) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {







        if (isAudioEnabled) {
    
            }
    }
}

@Composable
private fun CinemaSettingSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    formatPattern: String = "%.2f",
    unit: String = "",
    valueMultiplier: Float = 1.0f
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            modifier = Modifier.width(130.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFFD54F),
                activeTrackColor = Color(0xFFFFD54F),
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
        )
        Text(
            text = String.format(formatPattern, value * valueMultiplier) + unit,
            color = Color(0xFFFFD54F),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun CinemaSettingsPage(
    cinemaConfig: CinemaConfig,
    onCinemaConfigChange: (CinemaConfig) -> Unit,
    cinemaCapabilities: CinemaHardwareCapabilities,
    capabilities: HardwareCapabilities
) {
    val context = LocalContext.current
    val customLutRepo = remember(context) { CustomLutRepository(context) }
    val customLuts by customLutRepo.customLuts.collectAsStateWithLifecycle()

    val lutFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            var resolvedName: String? = null
            try {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && cursor.moveToFirst()) {
                        resolvedName = cursor.getString(nameIdx)
                    }
                }
            } catch (ignored: Exception) {}

            val fileName = (resolvedName ?: uri.lastPathSegment?.substringAfterLast('/'))
                ?.removeSuffix(".cube")
                ?.takeIf { it.isNotBlank() } ?: "Custom Grade"

            val imported = customLutRepo.importLut(uri, fileName)
            if (imported != null) {
                android.widget.Toast.makeText(context, "Imported LUT: ${imported.title}", android.widget.Toast.LENGTH_SHORT).show()
                onCinemaConfigChange(
                    cinemaConfig.copy(
                        selectedLut = CinematicLut.CUSTOM,
                        customLutPath = imported.filePath,
                        customLutName = imported.title,
                        isBakeLutToOutput = true,
                        isLutPreviewEnabled = true
                    )
                )
            } else {
                android.widget.Toast.makeText(context, "Invalid or unsupported .cube file format", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // 1. Resolution

        // 2. Frame Rate (FPS)

        // 3. Video Bitrate

        // 4. Video Codec

        // 5. Log & Color Profile

        // 6. Log Bit Depth

        // 7. Live Noise Reduction

        // 8. Sharpness & Detail

        // 9. Cinematic 3D LUT

        // 10. Color Gamut & Space

        // 11. Assist Tools





        // 12. Fine-Tuning & Detailed Processing Controls
    }
}

@Composable
private fun ProManualSettingsPage(
    manualIso: Int?,
    onManualIsoChange: (Int?) -> Unit,
    manualShutterSpeedNs: Long?,
    onManualShutterSpeedChange: (Long?) -> Unit,
    focusMode: FocusMode,
    onFocusModeChange: (FocusMode) -> Unit,
    manualFocusDistance: Float,
    onManualFocusDistanceChange: (Float) -> Unit,
    exposureCompensation: Int,
    onExposureCompensationChange: (Int) -> Unit,
    capabilities: HardwareCapabilities,
    isCustomPipelineEnabled: Boolean,
    onCustomPipelineToggle: (Boolean) -> Unit,
    activePipelinePreset: com.example.camera.pipeline.model.PipelinePreset,
    onSelectPipelinePreset: (com.example.camera.pipeline.model.PipelinePreset) -> Unit,
    onOpenPipelineStudio: () -> Unit,
    onOpenBeforeAfter: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {


        if (isCustomPipelineEnabled) {
                }
    }
}

@Composable
private fun CameraLensSettingsPage(
    availableLenses: List<LensInfo>,
    selectedLens: LensInfo?,
    onLensSelected: (LensInfo) -> Unit,
    onForceDeepScan: () -> Unit,
    instantSwitchState: MotorolaInstantSwitchState,
    lensSwitchOverlapDurationSec: Float = 0.3f,
    onLensSwitchOverlapDurationChange: (Float) -> Unit = {},
    lensSwitchHoldDurationSec: Float = 0.10f,
    onLensSwitchHoldDurationChange: (Float) -> Unit = {},
    isLensSwitchOverlapAnimationEnabled: Boolean = false,
    onLensSwitchOverlapAnimationToggle: (Boolean) -> Unit = {},
    onKeepUltraWideReadyToggle: (Boolean) -> Unit = {},
    onAutoSwitchToUltraWideToggle: (Boolean) -> Unit = {},
    onShowUltraWidePreviewToggle: (Boolean) -> Unit,
    onShowFrontCameraPreviewToggle: (Boolean) -> Unit,
    viewfinderFps: Int,
    onViewfinderFpsSelected: (Int) -> Unit,
    viewfinderResolution: ViewfinderResolution,
    onViewfinderResolutionSelected: (ViewfinderResolution) -> Unit,
    gridType: GridType,
    onGridTypeSelected: (GridType) -> Unit,
    horizonLeveler: Boolean,
    onHorizonLevelerToggle: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {











    }
}

@Composable
private fun LensSwitchHoldDurationSettingCard(
    holdDurationSec: Float,
    onHoldDurationChange: (Float) -> Unit,
    isOverlapAnimationEnabled: Boolean = false
) {
    val roundedDuration = ((holdDurationSec.coerceIn(0.05f, 0.30f) * 20f).roundToInt() / 20f).coerceIn(0.05f, 0.30f)
    val isDefault = kotlin.math.abs(roundedDuration - 0.10f) < 0.001f
    val displayValue = if (isOverlapAnimationEnabled) {
        if (isDefault) {
            String.format(java.util.Locale.US, "%.2f sec (Default • Overlap Blend)", roundedDuration)
        } else {
            String.format(java.util.Locale.US, "%.2f sec (Overlap Blend)", roundedDuration)
        }
    } else {
        if (isDefault) {
            String.format(java.util.Locale.US, "%.2f sec (Default)", roundedDuration)
        } else {
            String.format(java.util.Locale.US, "%.2f sec", roundedDuration)
        }
    }

    val options = listOf(
        0.05f to "0.05 sec",
        0.10f to "0.10 sec",
        0.15f to "0.15 sec",
        0.20f to "0.20 sec",
        0.25f to "0.25 sec",
        0.30f to "0.30 sec"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setting_lens_switch_hold_duration_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFD54F).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Lens Switch Hold Duration",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isOverlapAnimationEnabled) {
                                "Duration used for the Overlap Animation. Smoothly blends cropped Ultra-Wide into native Main lens over this exact duration."
                            } else {
                                "Hold duration at the selected switch point field of view on the Ultra-Wide lens before switching to the Main lens."
                            },
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isOverlapAnimationEnabled) "Animation Duration" else "Current Hold Time",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Text(
                        text = displayValue,
                        color = Color(0xFFFFD54F),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEach { (value, label) ->
                    val isSelected = kotlin.math.abs(roundedDuration - value) < 0.01f
                    val tagValue = String.format(java.util.Locale.US, "%.2f", value)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onHoldDurationChange(value) },
                        modifier = Modifier.testTag("hold_duration_option_$tagValue"),
                        label = {
                            Text(
                                text = label,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFD54F),
                            selectedLabelColor = Color.Black,
                            containerColor = Color.White.copy(alpha = 0.08f),
                            labelColor = Color.White
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchPointSettingCard(
    switchPointMm: Float,
    onSwitchPointChange: (Float) -> Unit
) {
    val roundedFocal = switchPointMm.roundToInt().coerceIn(23, 85)
    val uwCrop = (roundedFocal.toFloat() / 16.0f * 100f).roundToInt() / 100f
    val mainCrop = (roundedFocal.toFloat() / 23.0f * 100f).roundToInt() / 100f

    val isDefaultOriginal = roundedFocal == 23
    val displayValue = if (isDefaultOriginal) "23mm (Original)" else "${roundedFocal}mm"

    val presets = listOf(
        23 to "23mm (Original)",
        28 to "28mm",
        32 to "32mm",
        40 to "40mm",
        50 to "50mm",
        60 to "60mm",
        85 to "85mm"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setting_lens_switch_point_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFD54F).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Cameraswitch,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Switch Point",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Lens handoff: Ultra-Wide (16mm) → Main (23mm)",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 11.5.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFD54F).copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = displayValue,
                        color = Color(0xFFFFD54F),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Real-time crop calculation and active value readout banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF26262E))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ultra-wide: ${String.format(java.util.Locale.US, "%.2f", uwCrop)}× crop",
                    color = Color(0xFF64FFDA),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Main: ${String.format(java.util.Locale.US, "%.2f", mainCrop)}× crop",
                    color = Color(0xFFFFD54F),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Continuous Slider 23mm -> 85mm
            Slider(
                value = roundedFocal.toFloat(),
                onValueChange = { onSwitchPointChange(it.roundToInt().toFloat()) },
                valueRange = 23f..85f,
                steps = 61,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFFD54F),
                    activeTrackColor = Color(0xFFFFD54F),
                    inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("slider_lens_switch_point")
            )

            // Preset Quick Selection Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (presetFocal, label) ->
                    val isSelected = roundedFocal == presetFocal
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSwitchPointChange(presetFocal.toFloat()) },
                        label = {
                            Text(
                                text = label,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFD54F),
                            selectedLabelColor = Color.Black,
                            containerColor = Color.White.copy(alpha = 0.08f),
                            labelColor = Color.White
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = Color.White.copy(alpha = 0.15f),
                            selectedBorderColor = Color(0xFFFFD54F)
                        ),
                        modifier = Modifier.testTag("chip_switch_point_$presetFocal")
                    )
                }
            }
        }
    }
}

@Composable
private fun StabilizationSettingsPage(
    mainCameraStabilizationMode: MainCameraStabilizationMode,
    onMainCameraStabilizationModeSelected: (MainCameraStabilizationMode) -> Unit,
    isVideoStabilizationEnabled: Boolean,
    onStabilizationToggle: (Boolean) -> Unit,
    isOisPreferred: Boolean,
    onOisToggle: (Boolean) -> Unit,
    isHorizontalLockSettingEnabled: Boolean = true,
    onHorizontalLockSettingToggle: (Boolean) -> Unit = {},
    isUltraStabilizationEnabled: Boolean,
    onUltraStabilizationToggle: () -> Unit,
    windNoiseReduction: Boolean,
    onWindNoiseReductionToggle: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            SettingsSwitchCard(
                title = "Hardware OIS (Optical Image Stabilization)",
                description = if (isOisPreferred) {
                    "ON: Main camera uses physical hardware voice-coil Optical Image Stabilization (OIS) whenever supported."
                } else {
                    "OFF: Hardware Optical Image Stabilization is disabled."
                },
                isChecked = isOisPreferred,
                onCheckedChange = onOisToggle,
                tag = "toggle_ois_switch"
            )
        }


    }
}

@Composable
private fun UiLayoutSettingsPage(
    uiCustomizationState: UiCustomizationState,
    onSelectTemplate: (UiTemplateType) -> Unit,
    onOpenCustomUiStudio: () -> Unit,
    onUpdateModeLayoutConfig: (CameraMode, ModeLayoutConfig) -> Unit = { _, _ -> },
    onUpdateGlobalLayoutConfig: (ModeLayoutConfig) -> Unit = {},
    floatingWindowAppearance: FloatingWindowAppearanceConfig,
    onFloatingWindowTransparencyChange: (Float) -> Unit,
    onFloatingWindowBlurStrengthChange: (Float) -> Unit,
    onFloatingWindowAppearanceChange: (FloatingWindowAppearanceConfig) -> Unit,
    onResetFloatingWindowAppearance: () -> Unit,
    viewfinderCornerRadiusDp: Int,
    onViewfinderCornerRadiusChange: (Int) -> Unit,
    viewfinderResolution: ViewfinderResolution = ViewfinderResolution.NORMAL,
    onViewfinderResolutionSelected: (ViewfinderResolution) -> Unit = {}
) {
    val allTemplates = remember {
        listOf(
            UiTemplateType.STOCK_PIXEL to "Pixel",
            UiTemplateType.SAMSUNG to "One UI",
            UiTemplateType.IPHONE to "iPhone",
            UiTemplateType.VIVO to "Vivo",
            UiTemplateType.MINIMAL_PRO to "Pro Clean",
            UiTemplateType.FUTURISTIC_GLASS to "Glass",
            UiTemplateType.DSLR_PRO to "DSLR",
            UiTemplateType.IMMERSIVE_EDGE to "Immersive",
            UiTemplateType.CUSTOM to "Custom UI"
        )
    }

    val scalePresets = remember {
        listOf(
            0.75f to "75% Compact",
            0.85f to "85% Small",
            1.00f to "100% Normal",
            1.12f to "112% Large",
            1.25f to "125% XL"
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        // 1. UI Template Style Selector (Multi-row grid with all templates visible: Pixel, One UI, iPhone, Vivo, etc.)
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF131622),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "UI Template Style",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Choose primary camera layout aesthetic (Pixel, Samsung One UI, iPhone iOS, Vivo OriginOS, Leica, Cyber Glass, DSLR).",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    allTemplates.chunked(3).forEach { rowItems ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { (template, label) ->
                                val isSelected = uiCustomizationState.selectedTemplate == template
                                Surface(
                                    onClick = { onSelectTemplate(template) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.08f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.12f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .testTag("ui_template_option_${template.name.lowercase()}")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) Color.Black else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Selected: ${uiCustomizationState.selectedTemplate.title} — ${uiCustomizationState.selectedTemplate.subtitle}",
                        color = Color(0xFFFFD54F).copy(alpha = 0.9f),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // 2. Custom UI Studio Action
        item {
            SettingsActionCard(
                title = "Custom UI Studio",
                description = "Fine-tune button positions, colors, typography, and controls density.",
                actionText = "OPEN STUDIO",
                onClick = onOpenCustomUiStudio
            )
        }

        // 2a. Top Bar Controls & Icon Customization (Per Mode)
        item {
            var selectedModeForTopBar by remember { mutableStateOf(CameraMode.CINEMA) }
            var expandedSlotItem by remember { mutableStateOf<TopControlItem?>(null) }
            var showAddSlotMenu by remember { mutableStateOf(false) }

            val currentModeConfig = remember(selectedModeForTopBar, uiCustomizationState) {
                uiCustomizationState.getConfigForMode(selectedModeForTopBar)
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF131622),
                border = BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Top Bar Customization (Per Mode)",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Choose which top icons are shown, select actions, and set position (Left / Center / Right) independently for each camera mode.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mode Selector Chips
                    val modeScroll = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(modeScroll),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CameraMode.entries.forEach { mode ->
                            val isSel = selectedModeForTopBar == mode
                            FilterChip(
                                selected = isSel,
                                onClick = { selectedModeForTopBar = mode },
                                label = {
                                    Text(
                                        text = mode.title,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.5.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF2563EB),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White.copy(alpha = 0.85f)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Subheading & Add Slot Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedModeForTopBar.title} Top Slots:",
                            color = Color(0xFF64B5F6),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Box {
                            TextButton(
                                onClick = { showAddSlotMenu = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Slot", tint = Color(0xFF64B5F6), modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Slot", color = Color(0xFF64B5F6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            DropdownMenu(
                                expanded = showAddSlotMenu,
                                onDismissRequest = { showAddSlotMenu = false },
                                modifier = Modifier.background(Color(0xFF1E2435))
                            ) {
                                TopControlItem.entries.forEach { opt ->
                                    val alreadyInOrder = currentModeConfig.topControlsOrder.contains(opt)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = opt.label + if (alreadyInOrder) " (already added)" else "",
                                                color = if (alreadyInOrder) Color.Gray else Color.White,
                                                fontSize = 12.sp
                                            )
                                        },
                                        onClick = {
                                            showAddSlotMenu = false
                                            if (!alreadyInOrder) {
                                                val updated = currentModeConfig.copy(
                                                    topControlsOrder = currentModeConfig.topControlsOrder + opt,
                                                    hiddenTopControls = currentModeConfig.hiddenTopControls - opt
                                                )
                                                onUpdateModeLayoutConfig(selectedModeForTopBar, updated)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Slots List
                    currentModeConfig.topControlsOrder.forEachIndexed { index, item ->
                        val isHidden = currentModeConfig.hiddenTopControls.contains(item)
                        val curPos = currentModeConfig.getIconPosition(item)

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF181C28),
                            border = BorderStroke(1.dp, if (!isHidden) Color(0xFF2563EB).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.06f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Checkbox(
                                            checked = !isHidden,
                                            onCheckedChange = { visible ->
                                                val set = currentModeConfig.hiddenTopControls.toMutableSet()
                                                if (visible) set.remove(item) else set.add(item)
                                                onUpdateModeLayoutConfig(selectedModeForTopBar, currentModeConfig.copy(hiddenTopControls = set))
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = "Slot ${index + 1}: ${item.label}",
                                            color = if (!isHidden) Color.White else Color.White.copy(alpha = 0.5f),
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Row {
                                        IconButton(
                                            enabled = index > 0,
                                            onClick = {
                                                val list = currentModeConfig.topControlsOrder.toMutableList()
                                                val temp = list[index - 1]
                                                list[index - 1] = item
                                                list[index] = temp
                                                onUpdateModeLayoutConfig(selectedModeForTopBar, currentModeConfig.copy(topControlsOrder = list))
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move earlier", tint = if (index > 0) Color.White else Color.Gray, modifier = Modifier.size(15.dp))
                                        }
                                        IconButton(
                                            enabled = index < currentModeConfig.topControlsOrder.size - 1,
                                            onClick = {
                                                val list = currentModeConfig.topControlsOrder.toMutableList()
                                                val temp = list[index + 1]
                                                list[index + 1] = item
                                                list[index] = temp
                                                onUpdateModeLayoutConfig(selectedModeForTopBar, currentModeConfig.copy(topControlsOrder = list))
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move later", tint = if (index < currentModeConfig.topControlsOrder.size - 1) Color.White else Color.Gray, modifier = Modifier.size(15.dp))
                                        }
                                        if (currentModeConfig.topControlsOrder.size > 1) {
                                            IconButton(
                                                onClick = {
                                                    val list = currentModeConfig.topControlsOrder.filterNot { it == item }
                                                    val set = currentModeConfig.hiddenTopControls.filterNot { it == item }.toSet()
                                                    onUpdateModeLayoutConfig(selectedModeForTopBar, currentModeConfig.copy(topControlsOrder = list, hiddenTopControls = set))
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Outlined.Delete, contentDescription = "Remove Slot", tint = Color(0xFFFF6B6B), modifier = Modifier.size(15.dp))
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Action selector dropdown button
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Surface(
                                        onClick = { expandedSlotItem = if (expandedSlotItem == item) null else item },
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF202636),
                                        border = BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Action: ${item.label}",
                                                color = Color(0xFF64B5F6),
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = expandedSlotItem == item,
                                        onDismissRequest = { expandedSlotItem = null },
                                        modifier = Modifier
                                            .background(Color(0xFF1E2435))
                                            .heightIn(max = 260.dp)
                                    ) {
                                        TopControlItem.entries.forEach { opt ->
                                            val isCur = opt == item
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = opt.label,
                                                        color = if (isCur) Color(0xFF64B5F6) else Color.White,
                                                        fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                                        fontSize = 12.sp
                                                    )
                                                },
                                                onClick = {
                                                    expandedSlotItem = null
                                                    if (opt != item) {
                                                        onUpdateModeLayoutConfig(selectedModeForTopBar, currentModeConfig.withItemAction(item, opt))
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Position selector
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Position:", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                                    TopIconPosition.entries.forEach { pos ->
                                        val isPosSel = curPos == pos
                                        Surface(
                                            onClick = {
                                                onUpdateModeLayoutConfig(selectedModeForTopBar, currentModeConfig.withIconPosition(item, pos))
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isPosSel) Color(0xFF2563EB) else Color(0xFF222838),
                                            border = BorderStroke(1.dp, if (isPosSel) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.08f)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(28.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = pos.label,
                                                    color = if (isPosSel) Color.White else Color.White.copy(alpha = 0.7f),
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isPosSel) FontWeight.Bold else FontWeight.Normal
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
        }

        // 2b. Viewfinder Corner Radius
        item {
            ViewfinderCornerRadiusCard(
                cornerRadiusDp = viewfinderCornerRadiusDp,
                onCornerRadiusChange = onViewfinderCornerRadiusChange
            )
        }

        // 2c. Viewfinder Preview Resolution
        item {
            SettingsSegmentedCard(
                title = "Viewfinder Resolution",
                description = "Live camera viewfinder preview resolution. Controls only the on-screen preview clarity and does not affect final photo or video recording resolution.",
                options = listOf(
                    ViewfinderResolution.NORMAL to "Normal",
                    ViewfinderResolution.RES_2K to "2K",
                    ViewfinderResolution.RES_4K to "4K"
                ),
                selectedOption = viewfinderResolution,
                onOptionSelected = onViewfinderResolutionSelected
            )
        }

        // 3. All Floating Windows Size (Scale) Control
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF131622),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "All Floating Windows Size",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Scale the size of all floating settings windows (Video, Pro Video, Pipeline).",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x26FFD54F)
                        ) {
                            Text(
                                text = "${(floatingWindowAppearance.windowScale * 100).roundToInt()}%",
                                color = Color(0xFFFFD54F),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Slider(
                        value = floatingWindowAppearance.windowScale.coerceIn(0.75f, 1.25f),
                        onValueChange = { newScale ->
                            onFloatingWindowAppearanceChange(
                                floatingWindowAppearance.copy(windowScale = newScale.coerceIn(0.75f, 1.25f))
                            )
                        },
                        valueRange = 0.75f..1.25f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFD54F),
                            activeTrackColor = Color(0xFFFFD54F),
                            inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slider_floating_window_size")
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        scalePresets.forEach { (scaleValue, label) ->
                            val isSelected = kotlin.math.abs(floatingWindowAppearance.windowScale - scaleValue) < 0.03f
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    onFloatingWindowAppearanceChange(
                                        floatingWindowAppearance.copy(windowScale = scaleValue)
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFFFD54F),
                                    selectedLabelColor = Color.Black,
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        // 4. Floating Window Backdrop Blur & Transparency
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF131622),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Floating Window Blur & Glass",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Real-time 1:1 optical backdrop blur and frosted glass transparency.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                        TextButton(onClick = onResetFloatingWindowAppearance) {
                            Text(
                                text = "RESET",
                                color = Color(0xFFFFD54F),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val stylePresets = listOf(
                        FloatingWindowAppearanceConfig.LIQUID_GLASS to "Liquid Glass",
                        FloatingWindowAppearanceConfig.FROSTED_GLASS to "Frosted Glass",
                        FloatingWindowAppearanceConfig.TRANSPARENT_GLASS to "Transparent Glass",
                        FloatingWindowAppearanceConfig.SUBTLE_FROST to "Subtle Frost",
                        FloatingWindowAppearanceConfig.DEEP_FROST to "Deep Frost",
                        FloatingWindowAppearanceConfig.SOLID_DARK to "Solid Dark"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        stylePresets.forEach { (preset, label) ->
                            val isSelected = floatingWindowAppearance.glassStyle == preset.glassStyle
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    onFloatingWindowAppearanceChange(
                                        floatingWindowAppearance.copy(
                                            glassStyle = preset.glassStyle,
                                            transparency = preset.transparency,
                                            blurStrength = preset.blurStrength,
                                            windowScale = preset.windowScale
                                        )
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFFFD54F),
                                    selectedLabelColor = Color.Black,
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Blur Strength Slider (0..50 dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Backdrop Blur Strength",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${floatingWindowAppearance.blurStrengthDp} dp",
                            color = Color(0xFFFFD54F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = floatingWindowAppearance.blurStrength.coerceIn(0f, 50f),
                        onValueChange = onFloatingWindowBlurStrengthChange,
                        valueRange = 0f..50f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFD54F),
                            activeTrackColor = Color(0xFFFFD54F),
                            inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                        )
                    )

                    // Transparency Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Glass Transparency",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${floatingWindowAppearance.transparencyPercent}%",
                            color = Color(0xFFFFD54F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = floatingWindowAppearance.transparency.coerceIn(0f, 1f),
                        onValueChange = onFloatingWindowTransparencyChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFD54F),
                            activeTrackColor = Color(0xFFFFD54F),
                            inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Real-time Live Floating Window Preview Box
                    Text(
                        text = "LIVE WINDOW PREVIEW",
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(105.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFFE91E63),
                                        Color(0xFF3F51B5),
                                        Color(0xFF009688),
                                        Color(0xFFFF9800)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        FrostedGlassBox(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .padding(vertical = 6.dp),
                            shape = RoundedCornerShape(14.dp),
                            elevation = 10.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "${floatingWindowAppearance.glassStyle.displayName} • ${floatingWindowAppearance.transparencyPercent}% Translucent",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Backdrop Blur: ${floatingWindowAppearance.blurStrengthDp} dp",
                                    color = Color(0xFFFFD54F),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Real-time physical frosted glass preview",
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Floating Window Settings & Content Customization ("Choose which settings appear in Floating Windows")
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF131622),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Floating Window Controls & Content",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Choose which settings and controls are displayed inside each floating window.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Video Floating Window Section
                    Text(
                        text = "VIDEO FLOATING WINDOW",
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FloatingContentToggleRow(
                        label = "Video Resolution (HD / FHD / 2K / 4K)",
                        checked = floatingWindowAppearance.showVideoResolution,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showVideoResolution = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Frame Rate (24 / 30 / 60 / 120 FPS)",
                        checked = floatingWindowAppearance.showVideoFps,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showVideoFps = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Video Stabilization Mode",
                        checked = floatingWindowAppearance.showVideoStabilization,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showVideoStabilization = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Video Color & Tone Sliders",
                        checked = floatingWindowAppearance.showVideoAdjustmentsColorTone,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showVideoAdjustmentsColorTone = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Video Effects & Sharpening Sliders",
                        checked = floatingWindowAppearance.showVideoAdjustmentsEffects,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showVideoAdjustmentsEffects = it))
                        }
                    )

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )

                    // Pro Video Floating Window Section
                    Text(
                        text = "PRO VIDEO FLOATING WINDOW",
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FloatingContentToggleRow(
                        label = "Hollywood 3D LUTs & Intensity",
                        checked = floatingWindowAppearance.showCinemaLutControls,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showCinemaLutControls = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Log Color Profile & Bit Depth",
                        checked = floatingWindowAppearance.showCinemaColorProfile,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showCinemaColorProfile = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Pro Video Resolution, Aspect & FPS",
                        checked = floatingWindowAppearance.showCinemaResolutionFps,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showCinemaResolutionFps = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Pro Video Gimbal & Stabilization",
                        checked = floatingWindowAppearance.showCinemaStabilization,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showCinemaStabilization = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Assist Tools (Waveform / Peaking / Zebra)",
                        checked = floatingWindowAppearance.showCinemaAssistTools,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showCinemaAssistTools = it))
                        }
                    )

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )

                    // Pro & Pipeline Floating Windows Section
                    Text(
                        text = "PRO MANUAL & PIPELINE FLOATING WINDOWS",
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FloatingContentToggleRow(
                        label = "Pro Manual Exposure (ISO / Shutter / Focus / WB)",
                        checked = floatingWindowAppearance.showProExposureControls,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showProExposureControls = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Pro Fine-Tuning (Tone / Sharpness / Saturation)",
                        checked = floatingWindowAppearance.showProToneAdjustments,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showProToneAdjustments = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Pipeline Master ON/OFF Switch",
                        checked = floatingWindowAppearance.showPipelineMasterToggle,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showPipelineMasterToggle = it))
                        }
                    )
                    FloatingContentToggleRow(
                        label = "Pipeline Preset Cards List",
                        checked = floatingWindowAppearance.showPipelinePresetList,
                        onCheckedChange = {
                            onFloatingWindowAppearanceChange(floatingWindowAppearance.copy(showPipelinePresetList = it))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingContentToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color(0xFFFFD54F),
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
            )
        )
    }
}

@Composable
private fun GeneralSettingsPage(
    volumeKeyAction: String,
    onVolumeKeyActionSelected: (String) -> Unit,
    doubleTapAction: String,
    onDoubleTapActionSelected: (String) -> Unit,
    shutterFeedback: String,
    onShutterFeedbackSelected: (String) -> Unit,
    antibandingMode: String,
    onAntibandingModeSelected: (String) -> Unit,
    thermalProtection: Boolean,
    onThermalProtectionToggle: (Boolean) -> Unit,
    isAiAutoFramingEnabled: Boolean,
    onAiAutoFramingToggle: (Boolean) -> Unit,
    preferredGalleryPackage: String?,
    onOpenGalleryChooser: () -> Unit,
    onResetAll: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            SettingsSegmentedCard(
                title = "Volume Key Action",
                description = "Hardware volume rocker function while camera is active.",
                options = listOf(
                    "SHUTTER" to "Shutter",
                    "ZOOM" to "Zoom",
                    "VOLUME" to "System Vol"
                ),
                selectedOption = volumeKeyAction,
                onOptionSelected = onVolumeKeyActionSelected
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Double Tap Viewfinder",
                description = "Action when double-tapping the viewfinder screen.",
                options = listOf(
                    "FLIP" to "Flip Camera",
                    "LOCK" to "Lock Focus",
                    "NONE" to "None"
                ),
                selectedOption = doubleTapAction,
                onOptionSelected = onDoubleTapActionSelected
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Shutter Feedback",
                description = "Tactile and acoustic feedback on capture.",
                options = listOf(
                    "SOUND_AND_HAPTIC" to "All",
                    "HAPTIC_ONLY" to "Haptic",
                    "SOUND_ONLY" to "Sound",
                    "MUTE" to "Mute"
                ),
                selectedOption = shutterFeedback,
                onOptionSelected = onShutterFeedbackSelected
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Anti-Banding Filter",
                description = "Prevents light flickering from fluorescent and LED indoor lighting.",
                options = listOf(
                    "AUTO" to "Auto",
                    "50HZ" to "50 Hz",
                    "60HZ" to "60 Hz",
                    "OFF" to "Off"
                ),
                selectedOption = antibandingMode,
                onOptionSelected = onAntibandingModeSelected
            )
        }

        item {
            SettingsSwitchCard(
                title = "Adaptive Thermal Protection",
                description = "Adjusts sensor framerate when device temperature exceeds safe limits.",
                isChecked = thermalProtection,
                onCheckedChange = onThermalProtectionToggle,
                tag = "toggle_thermal_protection"
            )
        }

        item {
            SettingsActionCard(
                title = "Preferred Gallery App",
                description = preferredGalleryPackage ?: "System Default Gallery",
                actionText = "CHANGE",
                onClick = onOpenGalleryChooser
            )
        }
    }
}

@Composable
private fun AboutSettingsPage(
    capabilities: HardwareCapabilities,
    availableLenses: List<LensInfo>,
    selectedLens: LensInfo?
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF131622),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Camera Pro Engine",
                        color = Color(0xFFFFD54F),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Native Camera2 Pipeline • Version 1.0",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "HARDWARE SPECIFICATIONS",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "• Active Camera ID: ${selectedLens?.id ?: "0"}", color = Color.White, fontSize = 13.sp)
                    Text(text = "• Detected Physical Lenses: ${availableLenses.size}", color = Color.White, fontSize = 13.sp)
                    Text(text = "• RAW DNG Capture: ${if (capabilities.supportsRaw) "Supported" else "Unsupported"}", color = Color.White, fontSize = 13.sp)
                    Text(text = "• Optical Image Stabilization: ${if (capabilities.supportsOis) "Supported" else "Unsupported"}", color = Color.White, fontSize = 13.sp)
                    Text(text = "• Max Digital Zoom: ${capabilities.maxZoom}x", color = Color.White, fontSize = 13.sp)
                    Text(text = "• ISO Range: ${capabilities.minIso} - ${capabilities.maxIso}", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

// -------------------------------------------------------------
// REUSABLE MODERN SETTINGS CARDS
// -------------------------------------------------------------

@Composable
fun ViewfinderCornerRadiusCard(
    cornerRadiusDp: Int,
    onCornerRadiusChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = remember {
        listOf(
            0 to "0 dp (Sharp)",
            8 to "8 dp (Subtle)",
            16 to "16 dp (Standard)",
            24 to "24 dp (Curved)",
            36 to "36 dp (Modern)",
            48 to "48 dp (Max)"
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF131622),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Viewfinder Corner Radius",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (cornerRadiusDp == 0) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color.White.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "SHARP",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0x26FFD54F)
                            ) {
                                Text(
                                    text = "ROUNDED",
                                    color = Color(0xFFFFD54F),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Customize the edge curvature and rounded corners of the live camera preview.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x26FFD54F)
                ) {
                    Text(
                        text = "${cornerRadiusDp} dp",
                        color = Color(0xFFFFD54F),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Interactive Live Mini Preview Box showing exact corner radius
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0A0C12))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Miniature Viewfinder simulating aspect ratio & live corner radius
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(50.dp)
                        .clip(RoundedCornerShape(cornerRadiusDp.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF1E2638),
                                    Color(0xFF141926)
                                )
                            )
                        )
                        .border(
                            BorderStroke(1.5.dp, Color(0xFFFFD54F).copy(alpha = 0.85f)),
                            RoundedCornerShape(cornerRadiusDp.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CropFree,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F).copy(alpha = 0.85f),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "${cornerRadiusDp} dp",
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Slider from 0 to 48 dp
            Slider(
                value = cornerRadiusDp.toFloat(),
                onValueChange = { onCornerRadiusChange(it.roundToInt().coerceIn(0, 48)) },
                valueRange = 0f..48f,
                steps = 47,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFFD54F),
                    activeTrackColor = Color(0xFFFFD54F),
                    inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("slider_viewfinder_corner_radius")
            )

            // Preset Quick Selection Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (presetValue, label) ->
                    val isSelected = cornerRadiusDp == presetValue
                    FilterChip(
                        selected = isSelected,
                        onClick = { onCornerRadiusChange(presetValue) },
                        label = {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFD54F),
                            selectedLabelColor = Color.Black,
                            containerColor = Color.White.copy(alpha = 0.08f),
                            labelColor = Color.White
                        ),
                        modifier = Modifier.testTag("chip_corner_radius_$presetValue")
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF131622),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0x22FFD54F)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open",
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SettingsSwitchCard(
    title: String,
    description: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF131622),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = Color(0xFFFFD54F),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier.testTag(tag)
            )
        }
    }
}

@Composable
private fun <T> SettingsSegmentedCard(
    title: String,
    description: String,
    options: List<Pair<T, String>>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF131622),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEach { (value, label) ->
                    val isSelected = selectedOption == value
                    FilterChip(
                        selected = isSelected,
                        onClick = { onOptionSelected(value) },
                        label = {
                            Text(
                                text = label,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFD54F),
                            selectedLabelColor = Color.Black,
                            containerColor = Color.White.copy(alpha = 0.08f),
                            labelColor = Color.White
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsActionCard(
    title: String,
    description: String,
    actionText: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF131622),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFD54F),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = actionText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Dedicated Fullscreen Camera Settings Screen.
 * Binds directly to CameraViewModel to eliminate method size limitations and keep CameraScreen clean.
 */
@Composable
fun CameraSettingsScreen(
    viewModel: CameraViewModel,
    onDismiss: () -> Unit,
    onOpenCustomUiStudio: () -> Unit = {}
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
    val tapFocusConfig by viewModel.tapFocusConfig.collectAsStateWithLifecycle()
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
    val currentZoom by viewModel.currentZoom.collectAsStateWithLifecycle()
    val exposureCompensation by viewModel.exposureCompensation.collectAsStateWithLifecycle()
    val manualIso by viewModel.manualIso.collectAsStateWithLifecycle()
    val manualShutterSpeedNs by viewModel.manualShutterSpeedNs.collectAsStateWithLifecycle()
    val focusMode by viewModel.focusMode.collectAsStateWithLifecycle()
    val manualFocusDistance by viewModel.manualFocusDistance.collectAsStateWithLifecycle()
    val selectedPhotoFilter by viewModel.selectedPhotoFilter.collectAsStateWithLifecycle()
    val uiCustomizationState by viewModel.uiCustomizationState.collectAsStateWithLifecycle()
    val isCustomPipelineEnabled by viewModel.isCustomPipelineEnabled.collectAsStateWithLifecycle()
    val activePipelinePreset by viewModel.activePipelinePreset.collectAsStateWithLifecycle()
    val instantSwitchState by viewModel.instantSwitchState.collectAsStateWithLifecycle()
    val floatingWindowAppearance by viewModel.floatingWindowAppearance.collectAsStateWithLifecycle()
    val preferredGalleryPackage by viewModel.preferredGalleryPackage.collectAsStateWithLifecycle()

    val mainCameraStabilizationMode = remember(isVideoStabilizationEnabled, hybridStabilizationConfig) {
        val oisEffective = hybridStabilizationConfig.isOisEnabled && hybridStabilizationConfig.isOisPreferred
        when {
            !isVideoStabilizationEnabled -> MainCameraStabilizationMode.OFF
            hybridStabilizationConfig.isUltraStabilizationEnabled -> MainCameraStabilizationMode.ULTRA
            hybridStabilizationConfig.isEisOnly || (!oisEffective && hybridStabilizationConfig.isEisPreferred) -> MainCameraStabilizationMode.EIS_ONLY
            hybridStabilizationConfig.isHybridEnabled && oisEffective -> MainCameraStabilizationMode.HYBRID_OIS_EIS
            oisEffective && !hybridStabilizationConfig.isEisPreferred -> MainCameraStabilizationMode.OIS_ONLY
            hybridStabilizationConfig.isHybridEnabled -> MainCameraStabilizationMode.EIS_ONLY
            else -> MainCameraStabilizationMode.OFF
        }
    }

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
        onManualShutterSpeedChange = { viewModel.setManualShutterSpeed(it) },
        onFocusModeChange = { viewModel.setFocusMode(it) },
        onManualFocusDistanceChange = { viewModel.setManualFocusDistance(it) },
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
        onVideoResolutionSelected = { viewModel.selectVideoResolution(it) },
        onViewfinderResolutionSelected = { viewModel.setViewfinderResolution(it) },
        onVideoFpsSelected = { viewModel.setVideoFps(it) },
        onVideoBitrateSelected = { viewModel.setVideoBitrate(it) },
        onStabilizationToggle = { viewModel.setVideoStabilization(it) },
        onHybridStabilizationChange = { viewModel.setHybridStabilizationConfig(it) },
        onOisToggle = { viewModel.setOisEnabled(it) },
        onUltraStabilizationToggle = { viewModel.toggleUltraStabilization() },
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
        onOpenPipelineStudio = {
            viewModel.setSettingsOpen(false)
            viewModel.setPipelineSheetOpen(true)
        },
        onOpenBeforeAfter = {
            viewModel.setSettingsOpen(false)
            viewModel.setBeforeAfterOpen(true)
        },
        instantSwitchState = instantSwitchState,
        lensSwitchOverlapDurationSec = viewModel.lensSwitchOverlapDurationSec.collectAsStateWithLifecycle().value,
        onLensSwitchOverlapDurationChange = { viewModel.setLensSwitchOverlapDuration(it) },
        lensSwitchHoldDurationSec = viewModel.lensSwitchHoldDurationSec.collectAsStateWithLifecycle().value,
        onLensSwitchHoldDurationChange = { viewModel.setLensSwitchHoldDuration(it) },
        isLensSwitchOverlapAnimationEnabled = viewModel.isLensSwitchOverlapAnimationEnabled.collectAsStateWithLifecycle().value,
        onLensSwitchOverlapAnimationToggle = { viewModel.setLensSwitchOverlapAnimationEnabled(it) },
        onKeepUltraWideReadyToggle = { viewModel.setKeepUltraWideReady(it) },
        onAutoSwitchToUltraWideToggle = { viewModel.setAutoSwitchToUltraWide(it) },
        onShowUltraWidePreviewToggle = { viewModel.setShowUltraWidePreview(it) },
        onShowFrontCameraPreviewToggle = { viewModel.setShowFrontCameraPreview(it) },
        floatingWindowAppearance = floatingWindowAppearance,
        onFloatingWindowTransparencyChange = { viewModel.setFloatingWindowTransparency(it) },
        onFloatingWindowBlurStrengthChange = { viewModel.setFloatingWindowBlurStrength(it) },
        onFloatingWindowAppearanceChange = { viewModel.setFloatingWindowAppearance(it) },
        onResetFloatingWindowAppearance = { viewModel.resetFloatingWindowAppearance() },
        preferredGalleryPackage = preferredGalleryPackage,
        onOpenGalleryChooser = { viewModel.setGallerySelectionDialogOpen(true) },
        onDismiss = onDismiss
    )
}
