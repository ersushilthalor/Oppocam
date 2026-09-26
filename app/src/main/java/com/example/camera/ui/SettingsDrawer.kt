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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.model.*
import com.example.camera.viewmodel.CameraViewModel
import kotlin.math.roundToInt

/**
 * Dedicated Settings Pages.
 */
enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector) {
    PHOTO("Photo Settings", "Resolutions, HDR, RAW, 50MP & Burst", Icons.Outlined.CameraAlt),
    VIDEO("Video Settings", "Resolution, Frame Rate, Codec & Bitrate", Icons.Outlined.Videocam),
    CINEMA("Cinema Settings", "Log profiles, LUTs, Bit depth & Assist tools", Icons.Outlined.Movie),
    PRO_MANUAL("Pro / Manual Settings", "ISO, Shutter, Focus, WB & Image Pipeline", Icons.Outlined.Tune),
    NIGHT_MODE("Night Mode Settings", "Multi-Frame Fusion, Exposure & Tripod", Icons.Outlined.NightsStay),
    CAMERA_LENS("Camera & Lens Settings", "Hardware lenses, Deep scan & Viewfinder", Icons.Outlined.Lens),
    STABILIZATION("Stabilization & Audio", "Hybrid OIS/EIS, Gyro & Wind reduction", Icons.Outlined.VideoStable),
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
    isHighQualityZoomEnabled: Boolean = true,
    zoomProcessingQuality: com.example.camera.zoom.ZoomProcessingQuality = com.example.camera.zoom.ZoomProcessingQuality.BALANCED,
    zoomPresetsMode: String = "STANDARD",
    customZoomPresetsStr: String = "1, 2, 4, 8",
    onZoomPresetsModeSelect: (String) -> Unit = {},
    onCustomZoomPresetsChange: (String) -> Unit = {},
    videoFps: Int = 30,
    videoBitrate: VideoBitrateOption = VideoBitrateOption.AUTO,
    isVideoStabilizationEnabled: Boolean = true,
    isAudioEnabled: Boolean = true,
    isRawEnabled: Boolean = false,
    saveSelfieAsPreviewed: Boolean = true,
    gridType: GridType = GridType.NONE,
    cinemaConfig: CinemaConfig = CinemaConfig(),
    cinemaCapabilities: CinemaHardwareCapabilities = CinemaHardwareCapabilities(),
    viewfinderResolution: ViewfinderResolution = ViewfinderResolution.NORMAL,
    hybridStabilizationConfig: HybridStabilizationConfig = HybridStabilizationConfig(),
    nightConfig: NightConfig = NightConfig(),
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
    isHdrPlusEnabled: Boolean = false,
    hdrPlusFrameCount: com.example.camera.engine.hdrplus.HdrPlusFrameCount = com.example.camera.engine.hdrplus.HdrPlusFrameCount.TWO_FRAMES,
    isAiAutoFramingEnabled: Boolean = false,
    currentZoom: Float = 1.0f,
    exposureCompensation: Int = 0,
    manualIso: Int? = null,
    manualShutterSpeedNs: Long? = null,
    focusMode: FocusMode = FocusMode.CONTINUOUS,
    manualFocusDistance: Float = 0.0f,
    portraitConfig: PortraitConfig = PortraitConfig(),
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
    onHdrPlusToggle: (Boolean) -> Unit = {},
    onHdrPlusFrameCountSelected: (com.example.camera.engine.hdrplus.HdrPlusFrameCount) -> Unit = {},
    onAiAutoFramingToggle: (Boolean) -> Unit = {},
    onZoomChange: (Float) -> Unit = {},
    onExposureCompensationChange: (Int) -> Unit = {},
    onManualIsoChange: (Int?) -> Unit = {},
    onManualShutterSpeedChange: (Long?) -> Unit = {},
    onFocusModeChange: (FocusMode) -> Unit = {},
    onManualFocusDistanceChange: (Float) -> Unit = {},
    onPortraitConfigChange: (PortraitConfig) -> Unit = {},
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
    onHighQualityZoomToggle: (Boolean) -> Unit = {},
    onZoomProcessingQualitySelect: (com.example.camera.zoom.ZoomProcessingQuality) -> Unit = {},
    onVideoResolutionSelected: (CameraResolution) -> Unit = {},
    onViewfinderResolutionSelected: (ViewfinderResolution) -> Unit = {},
    onVideoFpsSelected: (Int) -> Unit = {},
    onVideoBitrateSelected: (VideoBitrateOption) -> Unit = {},
    onStabilizationToggle: (Boolean) -> Unit = {},
    onHybridStabilizationChange: (HybridStabilizationConfig) -> Unit = {},
    onOisToggle: (Boolean) -> Unit = {},
    onUltraStabilizationToggle: () -> Unit = {},
    onNightConfigChange: (NightConfig) -> Unit = {},
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
    onShowUltraWidePreviewToggle: (Boolean) -> Unit = {},
    onKeepFrontCameraReadyToggle: (Boolean) -> Unit = {},
    onShowFrontCameraPreviewToggle: (Boolean) -> Unit = {},
    floatingWindowAppearance: FloatingWindowAppearanceConfig = FloatingWindowAppearanceConfig.GLASSMORPHISM,
    onFloatingWindowTransparencyChange: (Float) -> Unit = {},
    onFloatingWindowBlurStrengthChange: (Float) -> Unit = {},
    onFloatingWindowAppearanceChange: (FloatingWindowAppearanceConfig) -> Unit = {},
    onResetFloatingWindowAppearance: () -> Unit = {},
    preferredGalleryPackage: String? = null,
    onOpenGalleryChooser: () -> Unit = {},
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
                            isHdrPlusEnabled = isHdrPlusEnabled,
                            hdrPlusFrameCount = hdrPlusFrameCount,
                            onHdrPlusToggle = onHdrPlusToggle,
                            onHdrPlusFrameCountSelected = onHdrPlusFrameCountSelected,
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
                            isUltraStabilizationEnabled = hybridStabilizationConfig.isUltraStabilizationEnabled,
                            onUltraStabilizationToggle = onUltraStabilizationToggle
                        )
                        SettingsPage.CINEMA -> CinemaSettingsPage(
                            cinemaConfig = cinemaConfig,
                            onCinemaConfigChange = onCinemaConfigChange,
                            cinemaCapabilities = cinemaCapabilities
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
                        SettingsPage.NIGHT_MODE -> NightModeSettingsPage(
                            nightConfig = nightConfig,
                            onNightConfigChange = onNightConfigChange
                        )
                        SettingsPage.CAMERA_LENS -> CameraLensSettingsPage(
                            availableLenses = availableLenses,
                            selectedLens = selectedLens,
                            onLensSelected = onLensSelected,
                            onForceDeepScan = onForceDeepScan,
                            instantSwitchState = instantSwitchState,
                            onShowUltraWidePreviewToggle = onShowUltraWidePreviewToggle,
                            onKeepFrontCameraReadyToggle = onKeepFrontCameraReadyToggle,
                            onShowFrontCameraPreviewToggle = onShowFrontCameraPreviewToggle,
                            viewfinderFps = viewfinderFps,
                            onViewfinderFpsSelected = onViewfinderFpsSelected,
                            viewfinderResolution = viewfinderResolution,
                            onViewfinderResolutionSelected = onViewfinderResolutionSelected,
                            gridType = gridType,
                            onGridTypeSelected = onGridTypeSelected,
                            horizonLeveler = horizonLeveler,
                            onHorizonLevelerToggle = onHorizonLevelerToggle,
                            isHighQualityZoomEnabled = isHighQualityZoomEnabled,
                            onHighQualityZoomToggle = onHighQualityZoomToggle,
                            zoomProcessingQuality = zoomProcessingQuality,
                            onZoomProcessingQualitySelect = onZoomProcessingQualitySelect
                        )
                        SettingsPage.STABILIZATION -> StabilizationSettingsPage(
                            mainCameraStabilizationMode = mainCameraStabilizationMode,
                            onMainCameraStabilizationModeSelected = onMainCameraStabilizationModeSelected,
                            isVideoStabilizationEnabled = isVideoStabilizationEnabled,
                            onStabilizationToggle = onStabilizationToggle,
                            isOisPreferred = hybridStabilizationConfig.isOisPreferred,
                            onOisToggle = onOisToggle,
                            isUltraStabilizationEnabled = hybridStabilizationConfig.isUltraStabilizationEnabled,
                            onUltraStabilizationToggle = onUltraStabilizationToggle,
                            windNoiseReduction = windNoiseReduction,
                            onWindNoiseReductionToggle = onWindNoiseReductionToggle
                        )
                        SettingsPage.UI_LAYOUT -> UiLayoutSettingsPage(
                            uiCustomizationState = uiCustomizationState,
                            onSelectTemplate = onSelectTemplate,
                            onOpenCustomUiStudio = onOpenCustomUiStudio,
                            floatingWindowAppearance = floatingWindowAppearance,
                            onFloatingWindowTransparencyChange = onFloatingWindowTransparencyChange,
                            onFloatingWindowBlurStrengthChange = onFloatingWindowBlurStrengthChange,
                            onFloatingWindowAppearanceChange = onFloatingWindowAppearanceChange,
                            onResetFloatingWindowAppearance = onResetFloatingWindowAppearance
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

        item {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onResetAll,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFF5252)
                ),
                border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("reset_all_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RESET ALL CAMERA SETTINGS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
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
    isHdrPlusEnabled: Boolean,
    hdrPlusFrameCount: com.example.camera.engine.hdrplus.HdrPlusFrameCount,
    onHdrPlusToggle: (Boolean) -> Unit,
    onHdrPlusFrameCountSelected: (com.example.camera.engine.hdrplus.HdrPlusFrameCount) -> Unit,
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
        item {
            SettingsSwitchCard(
                title = "HDR+",
                description = "Advanced 2-frame / 3-frame RAW computational photography with predictive exposure and natural highlight recovery.",
                isChecked = isHdrPlusEnabled,
                onCheckedChange = onHdrPlusToggle,
                tag = "toggle_hdr_plus"
            )
        }

        if (isHdrPlusEnabled) {
            item {
                SettingsSegmentedCard(
                    title = "HDR+ Frame Count",
                    description = "2 Frames for ultra-fast capture or 3 Frames for extreme dynamic range scenes.",
                    options = listOf(
                        com.example.camera.engine.hdrplus.HdrPlusFrameCount.TWO_FRAMES to "2 Frames",
                        com.example.camera.engine.hdrplus.HdrPlusFrameCount.THREE_FRAMES to "3 Frames"
                    ),
                    selectedOption = hdrPlusFrameCount,
                    onOptionSelected = onHdrPlusFrameCountSelected
                )
            }
        }

        item {
            SettingsSwitchCard(
                title = "Auto HDR",
                description = "Automatically balances highlight and shadow detail in high-contrast scenes.",
                isChecked = isAutoHdrEnabled,
                onCheckedChange = onAutoHdrToggle,
                tag = "toggle_auto_hdr"
            )
        }

        item {
            SettingsSwitchCard(
                title = "RAW Capture (DNG)",
                description = "Saves uncompressed raw sensor data alongside JPEG for maximum post-processing flexibility.",
                isChecked = isRawEnabled,
                onCheckedChange = { onRawToggle() },
                tag = "toggle_raw_capture"
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Resolution Mode",
                description = "Select standard binned output (12M) or computational ultra-high resolution (50M).",
                options = listOf(
                    PhotoMegapixelMode.M12 to "12M Standard",
                    PhotoMegapixelMode.M50 to "50M Ultra-Res"
                ),
                selectedOption = photoMegapixelMode,
                onOptionSelected = onPhotoMegapixelModeSelected
            )
        }

        item {
            SettingsSwitchCard(
                title = "Save Selfie as Previewed",
                description = "Saves front camera pictures without flipping them horizontally.",
                isChecked = saveSelfieAsPreviewed,
                onCheckedChange = onSaveSelfieAsPreviewedToggle,
                tag = "toggle_save_selfie"
            )
        }

        item {
            SettingsSwitchCard(
                title = "Refocus Multi-Frame Photo",
                description = "Captures an optical focal stack allowing interactive refocusing after capture.",
                isChecked = isRefocusPhotoEnabled,
                onCheckedChange = onRefocusPhotoToggle,
                tag = "toggle_refocus_photo"
            )
        }

        if (isRefocusPhotoEnabled) {
            item {
                SettingsSegmentedCard(
                    title = "Refocus Focal Frames",
                    description = "Number of optical focal planes acquired during refocus capture.",
                    options = listOf(3 to "3 Frames", 5 to "5 Frames", 7 to "7 Frames"),
                    selectedOption = refocusFrameCount,
                    onOptionSelected = onRefocusFrameCountChange
                )
            }
        }

        item {
            SettingsSwitchCard(
                title = "Ultra-Fast Shutter Burst",
                description = "Hold shutter button for ultra-fast zero shutter lag continuous shooting.",
                isChecked = isUltraFastShutterEnabled,
                onCheckedChange = onUltraFastShutterToggle,
                tag = "toggle_fast_shutter"
            )
        }

        if (isUltraFastShutterEnabled) {
            item {
                SettingsSegmentedCard(
                    title = "Burst Frame Rate",
                    description = "Continuous RAW acquisition rate while holding the shutter.",
                    options = listOf(10 to "10 FPS", 15 to "15 FPS", 20 to "20 FPS"),
                    selectedOption = ultraFastShutterFps,
                    onOptionSelected = onUltraFastShutterFpsChange
                )
            }
        }

        item {
            SettingsSegmentedCard(
                title = "JPEG Image Quality",
                description = "Compression factor for exported standard photos.",
                options = listOf(90 to "90%", 95 to "95%", 98 to "98%", 100 to "100%"),
                selectedOption = jpegQuality,
                onOptionSelected = onJpegQualitySelected
            )
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
    isUltraStabilizationEnabled: Boolean,
    onUltraStabilizationToggle: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            val supportedResolutions = capabilities.supportedVideoResolutions
            if (supportedResolutions.isNotEmpty()) {
                val current = selectedVideoResolution ?: supportedResolutions.first()
                SettingsSegmentedCard(
                    title = "Video Resolution",
                    description = "Standard recording resolution for video mode.",
                    options = supportedResolutions.take(3).map { it to "${it.width}x${it.height}" },
                    selectedOption = current,
                    onOptionSelected = onVideoResolutionSelected
                )
            }
        }

        item {
            SettingsSegmentedCard(
                title = "Frame Rate (FPS)",
                description = "Frames captured per second for smooth motion or cinematic feel.",
                options = listOf(24 to "24 fps", 30 to "30 fps", 60 to "60 fps"),
                selectedOption = videoFps,
                onOptionSelected = onVideoFpsSelected
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Video Encoding Codec",
                description = "HEVC (H.265) provides superior compression; H.264 offers broader device compatibility.",
                options = listOf("HEVC" to "HEVC (H.265)", "H264" to "H.264 AVC"),
                selectedOption = videoCodec,
                onOptionSelected = onVideoCodecSelected
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Bitrate Quality",
                description = "Controls video compression bitrate and file size.",
                options = listOf(
                    VideoBitrateOption.AUTO to "Auto",
                    VideoBitrateOption.STANDARD to "Standard",
                    VideoBitrateOption.HIGH to "High",
                    VideoBitrateOption.MAX to "Max"
                ),
                selectedOption = videoBitrate,
                onOptionSelected = onVideoBitrateSelected
            )
        }

        item {
            SettingsSwitchCard(
                title = "Video Stabilization (EIS)",
                description = "Uses software motion compensation to reduce camera shake.",
                isChecked = isVideoStabilizationEnabled,
                onCheckedChange = onStabilizationToggle,
                tag = "toggle_video_stabilization"
            )
        }

        item {
            SettingsSwitchCard(
                title = "Ultra Gyroscope Stabilization",
                description = "Hardware sensor-assisted horizon stabilization for extreme motion.",
                isChecked = isUltraStabilizationEnabled,
                onCheckedChange = { onUltraStabilizationToggle() },
                tag = "toggle_ultra_stabilization"
            )
        }

        item {
            SettingsSwitchCard(
                title = "Record Audio",
                description = "Capture high-fidelity stereo audio with recorded videos.",
                isChecked = isAudioEnabled,
                onCheckedChange = { onAudioToggle() },
                tag = "toggle_video_audio"
            )
        }

        if (isAudioEnabled) {
            item {
                SettingsSwitchCard(
                    title = "Wind Noise Reduction",
                    description = "Filters low-frequency turbulence and outdoor wind interference.",
                    isChecked = windNoiseReduction,
                    onCheckedChange = onWindNoiseReductionToggle,
                    tag = "toggle_wind_reduction"
                )
            }

            item {
                SettingsSegmentedCard(
                    title = "Audio Input Source",
                    description = "Choose microphone configuration for video recording.",
                    options = listOf("CAMCORDER" to "Camcorder", "MIC" to "Direct Mic", "UNPROCESSED" to "Raw Audio"),
                    selectedOption = audioSource,
                    onOptionSelected = onAudioSourceSelected
                )
            }
        }
    }
}

@Composable
private fun CinemaSettingsPage(
    cinemaConfig: CinemaConfig,
    onCinemaConfigChange: (CinemaConfig) -> Unit,
    cinemaCapabilities: CinemaHardwareCapabilities
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            SettingsSegmentedCard(
                title = "Cinema Color Profile",
                description = "Flat & Log curves preserve wide dynamic range for professional grading.",
                options = listOf(
                    CinemaColorProfile.NATIVE to "Native",
                    CinemaColorProfile.FLAT_LOG to "Flat Log",
                    CinemaColorProfile.REC_2020 to "Rec.2020",
                    CinemaColorProfile.PROCESSED_JPEG to "Standard"
                ),
                selectedOption = cinemaConfig.colorProfile,
                onOptionSelected = { onCinemaConfigChange(cinemaConfig.copy(colorProfile = it)) }
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Log Bit Depth",
                description = "10-bit delivers 1,024 shades per color channel to eliminate banding.",
                options = listOf(
                    LogBitDepth.BIT_10 to "10-bit Log",
                    LogBitDepth.BIT_8 to "8-bit Standard"
                ),
                selectedOption = cinemaConfig.logBitDepth,
                onOptionSelected = { onCinemaConfigChange(cinemaConfig.copy(logBitDepth = it)) }
            )
        }

        item {
            SettingsSwitchCard(
                title = "Live LUT Viewfinder Preview",
                description = "Applies real-time 3D LUT color transform inside the camera viewfinder.",
                isChecked = cinemaConfig.isLutPreviewEnabled,
                onCheckedChange = { onCinemaConfigChange(cinemaConfig.copy(isLutPreviewEnabled = it)) },
                tag = "toggle_lut_preview"
            )
        }

        item {
            SettingsSwitchCard(
                title = "Cinema Waveform Monitor",
                description = "Real-time luminance IRE waveform display in the viewfinder.",
                isChecked = cinemaConfig.isWaveformEnabled,
                onCheckedChange = { onCinemaConfigChange(cinemaConfig.copy(isWaveformEnabled = it)) },
                tag = "toggle_cinema_waveform"
            )
        }

        item {
            SettingsSwitchCard(
                title = "Focus Peaking Assist",
                description = "Highlights in-focus edges with a bright color overlay.",
                isChecked = cinemaConfig.isFocusPeakingEnabled,
                onCheckedChange = { onCinemaConfigChange(cinemaConfig.copy(isFocusPeakingEnabled = it)) },
                tag = "toggle_focus_peaking"
            )
        }

        item {
            SettingsSwitchCard(
                title = "Zebra Highlight Stripes",
                description = "Overlays diagonal stripes on overexposed scene highlights.",
                isChecked = cinemaConfig.zebraThreshold != ZebraThreshold.OFF,
                onCheckedChange = { onCinemaConfigChange(cinemaConfig.copy(zebraThreshold = if (it) ZebraThreshold.IRE_70 else ZebraThreshold.OFF)) },
                tag = "toggle_zebra_stripes"
            )
        }
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
        item {
            SettingsSegmentedCard(
                title = "Focus Mode",
                description = "Continuous autofocus or precision manual focus plane control.",
                options = listOf(
                    FocusMode.CONTINUOUS to "Auto AF",
                    FocusMode.MANUAL to "Manual MF"
                ),
                selectedOption = focusMode,
                onOptionSelected = onFocusModeChange
            )
        }

        item {
            SettingsSwitchCard(
                title = "Custom Image Processing Pipeline",
                description = "Direct uncompressed RAW/YUV sensor processing before JPEG compression.",
                isChecked = isCustomPipelineEnabled,
                onCheckedChange = onCustomPipelineToggle,
                tag = "toggle_custom_pipeline"
            )
        }

        if (isCustomPipelineEnabled) {
            item {
                SettingsActionCard(
                    title = "Active Pipeline Preset: ${activePipelinePreset.name}",
                    description = activePipelinePreset.subtitle,
                    actionText = "OPEN STUDIO",
                    onClick = onOpenPipelineStudio
                )
            }
            item {
                SettingsActionCard(
                    title = "Pipeline Before / After Review",
                    description = "Inspect recent captures with live interactive split-screen comparison.",
                    actionText = "COMPARE",
                    onClick = onOpenBeforeAfter
                )
            }
        }
    }
}

@Composable
private fun NightModeSettingsPage(
    nightConfig: NightConfig,
    onNightConfigChange: (NightConfig) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            SettingsSwitchCard(
                title = "Multi-Frame Night Fusion",
                description = "Aligns and fuses multiple sensor exposures to eliminate low-light noise.",
                isChecked = nightConfig.multiFrameFusionEnabled,
                onCheckedChange = { onNightConfigChange(nightConfig.copy(multiFrameFusionEnabled = it)) },
                tag = "toggle_night_fusion"
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Default Night Duration",
                description = "Target multi-frame exposure duration in seconds or automatic scene detection.",
                options = listOf(
                    0 to "AUTO",
                    1 to "1s",
                    2 to "2s",
                    3 to "3s",
                    5 to "5s"
                ),
                selectedOption = nightConfig.durationSeconds,
                onOptionSelected = { onNightConfigChange(nightConfig.copy(durationSeconds = it)) }
            )
        }

        item {
            SettingsSwitchCard(
                title = "Tripod Auto-Detection",
                description = "Automatically detects stationary mounting to extend exposure up to 10s.",
                isChecked = nightConfig.tripodDetectionEnabled,
                onCheckedChange = { onNightConfigChange(nightConfig.copy(tripodDetectionEnabled = it)) },
                tag = "toggle_tripod_detection"
            )
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
    onShowUltraWidePreviewToggle: (Boolean) -> Unit,
    onKeepFrontCameraReadyToggle: (Boolean) -> Unit,
    onShowFrontCameraPreviewToggle: (Boolean) -> Unit,
    viewfinderFps: Int,
    onViewfinderFpsSelected: (Int) -> Unit,
    viewfinderResolution: ViewfinderResolution,
    onViewfinderResolutionSelected: (ViewfinderResolution) -> Unit,
    gridType: GridType,
    onGridTypeSelected: (GridType) -> Unit,
    horizonLeveler: Boolean,
    onHorizonLevelerToggle: (Boolean) -> Unit,
    isHighQualityZoomEnabled: Boolean,
    onHighQualityZoomToggle: (Boolean) -> Unit,
    zoomProcessingQuality: com.example.camera.zoom.ZoomProcessingQuality,
    onZoomProcessingQualitySelect: (com.example.camera.zoom.ZoomProcessingQuality) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            SettingsActionCard(
                title = "Hardware Lens Deep Scan",
                description = "Detected ${availableLenses.size} optical camera sensors on this device.",
                actionText = "RE-SCAN",
                onClick = onForceDeepScan
            )
        }

        item {
            SettingsSwitchCard(
                title = "Ultra-Wide Mini Preview",
                description = "Picture-in-picture live view from the ultra-wide lens.",
                isChecked = instantSwitchState.isShowUltraWidePreview,
                onCheckedChange = onShowUltraWidePreviewToggle,
                tag = "toggle_uw_preview"
            )
        }

        item {
            SettingsSwitchCard(
                title = "Front Camera Standby",
                description = "Maintains front camera sensor ready for instantaneous switching.",
                isChecked = instantSwitchState.isKeepFrontCameraReady,
                onCheckedChange = onKeepFrontCameraReadyToggle,
                tag = "toggle_front_standby"
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Framing Grid & Guides",
                description = "Composition alignment overlays on top of the live viewfinder.",
                options = listOf(
                    GridType.NONE to "Off",
                    GridType.THIRDS to "3x3 Rule",
                    GridType.GOLDEN to "Golden Ratio",
                    GridType.SQUARE to "1:1 Box",
                    GridType.LEVEL to "Level Horizon"
                ),
                selectedOption = gridType,
                onOptionSelected = onGridTypeSelected
            )
        }

        item {
            SettingsSwitchCard(
                title = "Virtual Horizon Leveler",
                description = "Live pitch and roll gyroscope orientation level line.",
                isChecked = horizonLeveler,
                onCheckedChange = onHorizonLevelerToggle,
                tag = "toggle_horizon_leveler"
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Viewfinder Refresh Rate",
                description = "Target preview display framerate on high refresh rate displays.",
                options = listOf(30 to "30 FPS", 60 to "60 FPS", 120 to "120 FPS"),
                selectedOption = viewfinderFps,
                onOptionSelected = onViewfinderFpsSelected
            )
        }

        item {
            SettingsSwitchCard(
                title = "High-Quality Zoom Enhancement",
                description = "Multi-frame super-resolution processing on digital zoom crops.",
                isChecked = isHighQualityZoomEnabled,
                onCheckedChange = onHighQualityZoomToggle,
                tag = "toggle_hq_zoom"
            )
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
            SettingsSegmentedCard(
                title = "Main Camera Stabilization Mode",
                description = "Hardware OIS, software EIS, or coordinated hybrid stabilization.",
                options = listOf(
                    MainCameraStabilizationMode.HYBRID_OIS_EIS to "Hybrid",
                    MainCameraStabilizationMode.EIS_ONLY to "EIS Only",
                    MainCameraStabilizationMode.OIS_ONLY to "OIS Only",
                    MainCameraStabilizationMode.OFF to "Off"
                ),
                selectedOption = mainCameraStabilizationMode,
                onOptionSelected = onMainCameraStabilizationModeSelected
            )
        }

        item {
            SettingsSwitchCard(
                title = "Video Stabilization",
                description = "Real-time electronic motion stabilization during video capture.",
                isChecked = isVideoStabilizationEnabled,
                onCheckedChange = onStabilizationToggle,
                tag = "toggle_video_stab_card"
            )
        }

        item {
            SettingsSwitchCard(
                title = "Hardware OIS Preferred",
                description = "Prioritize mechanical lens-shift stabilization when supported.",
                isChecked = isOisPreferred,
                onCheckedChange = onOisToggle,
                tag = "toggle_ois_preferred"
            )
        }

        item {
            SettingsSwitchCard(
                title = "Ultra Gyroscope Horizon Lock",
                description = "High-precision physical gyroscope tracking for action shots.",
                isChecked = isUltraStabilizationEnabled,
                onCheckedChange = { onUltraStabilizationToggle() },
                tag = "toggle_ultra_stab_card"
            )
        }
    }
}

@Composable
private fun UiLayoutSettingsPage(
    uiCustomizationState: UiCustomizationState,
    onSelectTemplate: (UiTemplateType) -> Unit,
    onOpenCustomUiStudio: () -> Unit,
    floatingWindowAppearance: FloatingWindowAppearanceConfig,
    onFloatingWindowTransparencyChange: (Float) -> Unit,
    onFloatingWindowBlurStrengthChange: (Float) -> Unit,
    onFloatingWindowAppearanceChange: (FloatingWindowAppearanceConfig) -> Unit,
    onResetFloatingWindowAppearance: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            SettingsSegmentedCard(
                title = "UI Template Style",
                description = "Choose primary camera layout aesthetic and controls arrangement.",
                options = listOf(
                    UiTemplateType.STOCK_PIXEL to "Pixel",
                    UiTemplateType.MINIMAL_PRO to "Pro Clean",
                    UiTemplateType.FUTURISTIC_GLASS to "Glass",
                    UiTemplateType.DSLR_PRO to "DSLR"
                ),
                selectedOption = uiCustomizationState.selectedTemplate,
                onOptionSelected = onSelectTemplate
            )
        }

        item {
            SettingsActionCard(
                title = "Custom UI Studio",
                description = "Fine-tune button positions, colors, typography, and controls density.",
                actionText = "OPEN STUDIO",
                onClick = onOpenCustomUiStudio
            )
        }

        item {
            SettingsSegmentedCard(
                title = "Floating Window Appearance",
                description = "Visual backdrop effect for popups and drawers.",
                options = listOf(
                    FloatingWindowAppearanceConfig.GLASSMORPHISM to "Glass",
                    FloatingWindowAppearanceConfig.SUBTLE_FROST to "Subtle",
                    FloatingWindowAppearanceConfig.DEEP_FROST to "Deep",
                    FloatingWindowAppearanceConfig.SOLID_DARK to "Dark"
                ),
                selectedOption = floatingWindowAppearance,
                onOptionSelected = onFloatingWindowAppearanceChange
            )
        }
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
        onDismiss = onDismiss
    )
}
