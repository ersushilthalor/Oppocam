package com.example.camera.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.*
import androidx.compose.ui.platform.LocalContext
import com.example.camera.data.CameraPreferences
import com.example.camera.hdr.model.*
import kotlin.math.roundToInt

/**
 * Samsung One UI Camera Settings Categories:
 * 1. ALL ("All")
 * 2. PICTURES ("Pictures & Quality")
 * 3. PROCESSING ("Photo Pipelines")
 * 4. STABILIZATION ("Stabilization")
 * 5. VIDEOS ("Videos & Audio")
 * 6. UI_CUSTOMIZATION ("UI & Layout")
 * 7. INTELLIGENT ("Intelligent")
 * 8. CONTROLS ("Controls & Gestures")
 * 9. ADVANCED ("Advanced & Labs")
 * 10. ABOUT ("About Camera")
 */
enum class FlagshipCategory(val title: String, val icon: ImageVector) {
    ALL("All", Icons.Outlined.GridView),
    PICTURES("Pictures", Icons.Outlined.CameraAlt),
    PROCESSING("Pipelines", Icons.Outlined.AutoFixHigh),
    STABILIZATION("Stabilization", Icons.Outlined.VideoStable),
    VIDEOS("Videos", Icons.Outlined.Videocam),
    UI_CUSTOMIZATION("UI & Layout", Icons.Outlined.DashboardCustomize),
    INTELLIGENT("Intelligent", Icons.Outlined.AutoAwesome),
    CONTROLS("Controls", Icons.Outlined.TouchApp),
    ADVANCED("Advanced", Icons.Outlined.Build),
    ABOUT("About", Icons.Outlined.Info)
}

/**
 * Clean Light Theme, Samsung-Style Camera Settings Bottom Sheet.
 * Displays all camera settings, UI customization, photo mode processing pipelines,
 * and synchronized main camera stabilization with dedicated EIS-only option.
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
    isHighQualityZoomEnabled: Boolean = true,
    zoomProcessingQuality: com.example.camera.zoom.ZoomProcessingQuality = com.example.camera.zoom.ZoomProcessingQuality.BALANCED,
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
    // Extended Settings State
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
    portraitConfig: PortraitConfig = PortraitConfig(),
    selectedPhotoFilter: PhotoFilter = PhotoFilter.ORIGINAL,
    mainCameraStabilizationMode: MainCameraStabilizationMode = MainCameraStabilizationMode.HYBRID_OIS_EIS,
    onMainCameraStabilizationModeSelected: (MainCameraStabilizationMode) -> Unit = {},
    // Callbacks
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
    onPortraitConfigChange: (PortraitConfig) -> Unit = {},
    onPhotoFilterSelected: (PhotoFilter) -> Unit = {},
    onResetAllSettings: () -> Unit = {},
    // Custom Image Processing Pipeline
    isCustomPipelineEnabled: Boolean = true,
    activePipelinePreset: com.example.camera.pipeline.model.PipelinePreset = com.example.camera.pipeline.model.PipelinePreset.HASSELBLAD,
    onCustomPipelineToggle: (Boolean) -> Unit = {},
    onSelectPipelinePreset: (com.example.camera.pipeline.model.PipelinePreset) -> Unit = {},
    onOpenPipelineStudio: () -> Unit = {},
    onOpenBeforeAfter: () -> Unit = {},
    // Motorola Instant Camera Switching
    instantSwitchState: MotorolaInstantSwitchState = MotorolaInstantSwitchState(),
    onKeepUltraWideReadyToggle: (Boolean) -> Unit = {},
    onShowUltraWidePreviewToggle: (Boolean) -> Unit = {},
    onKeepFrontCameraReadyToggle: (Boolean) -> Unit = {},
    onShowFrontCameraPreviewToggle: (Boolean) -> Unit = {},
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
    onOpenCustomUiStudio: () -> Unit = {},
    // Floating Window Appearance Customization
    floatingWindowAppearance: FloatingWindowAppearanceConfig = FloatingWindowAppearanceConfig(),
    onFloatingWindowTransparencyChange: (Float) -> Unit = {},
    onFloatingWindowBlurStrengthChange: (Float) -> Unit = {},
    onFloatingWindowAppearanceChange: (FloatingWindowAppearanceConfig) -> Unit = {},
    onResetFloatingWindowAppearance: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    var selectedFilterCategory by remember { mutableStateOf(FlagshipCategory.ALL) }
    var showResetDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFFF7F9FC),
        contentColor = Color(0xFF1E293B),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color(0xFFCBD5E1))
        },
        modifier = modifier.testTag("settings_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Samsung One UI Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Camera settings",
                        color = Color(0xFF0F172A),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp
                    )
                    Text(
                        text = "Customize capture, pipelines, stabilization & UI",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE2E8F0))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings",
                        tint = Color(0xFF334155),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Category Filter Pills (Samsung Style Capsule Pills)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(FlagshipCategory.entries.toTypedArray()) { cat ->
                    val isSelected = selectedFilterCategory == cat
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) Color(0xFF1D4ED8) else Color(0xFFFFFFFF),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) Color(0xFF1D4ED8) else Color(0xFFE2E8F0)
                        ),
                        modifier = Modifier
                            .clickable { selectedFilterCategory = cat }
                            .testTag("category_pill_${cat.name}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = cat.icon,
                                contentDescription = null,
                                tint = if (isSelected) Color.White else Color(0xFF64748B),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = cat.title,
                                color = if (isSelected) Color.White else Color(0xFF334155),
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Settings Content Body
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. PICTURES & QUALITY
                if (selectedFilterCategory == FlagshipCategory.ALL || selectedFilterCategory == FlagshipCategory.PICTURES) {
                    item {
                        SamsungSectionHeader("PICTURES & QUALITY")
                        SamsungCard {
                            // Photo Resolution
                            SamsungRowItem(
                                icon = Icons.Outlined.PhotoSizeSelectActual,
                                title = "Photo Resolution",
                                subtitle = "${photoMegapixelMode.label} · ${selectedPhotoResolution?.let { "${it.width}x${it.height}" } ?: "High Res"}"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PhotoMegapixelMode.entries.forEach { mode ->
                                        val isSelected = photoMegapixelMode == mode
                                        SamsungSmallChip(
                                            label = mode.label,
                                            isSelected = isSelected,
                                            onClick = { onPhotoMegapixelModeSelected(mode) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // RAW Capture (DNG)
                            SamsungSwitchItem(
                                icon = Icons.Outlined.RawOn,
                                title = "RAW (DNG) Copies",
                                subtitle = "Save 16-bit uncompressed RAW files to DCIM/Raw",
                                checked = isRawEnabled,
                                onCheckedChange = { onRawToggle() }
                            )

                            SamsungDivider()

                            // JPEG Quality
                            SamsungRowItem(
                                icon = Icons.Outlined.HighQuality,
                                title = "Picture Quality",
                                subtitle = "$jpegQuality% JPEG encoder quality"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(90, 95, 100).forEach { q ->
                                        SamsungSmallChip(
                                            label = "$q%",
                                            isSelected = jpegQuality == q,
                                            onClick = { onJpegQualitySelected(q) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // Auto HDR
                            SamsungSwitchItem(
                                icon = Icons.Outlined.HdrOn,
                                title = "Auto HDR",
                                subtitle = "Capture detail in bright highlights and dark shadows",
                                checked = isAutoHdrEnabled,
                                onCheckedChange = onAutoHdrToggle
                            )

                            SamsungDivider()

                            // Framing Grid
                            SamsungRowItem(
                                icon = Icons.Outlined.GridOn,
                                title = "Grid Lines",
                                subtitle = gridType.name.replace("_", " ")
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    GridType.entries.take(4).forEach { gt ->
                                        SamsungSmallChip(
                                            label = when (gt) {
                                                GridType.NONE -> "Off"
                                                GridType.THIRDS -> "3x3"
                                                GridType.GOLDEN -> "Golden"
                                                GridType.SQUARE -> "1:1"
                                                else -> gt.title
                                            },
                                            isSelected = gridType == gt,
                                            onClick = { onGridTypeSelected(gt) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // Refocus Burst Planes
                            SamsungSwitchItem(
                                icon = Icons.Outlined.FilterCenterFocus,
                                title = "Refocus Photo Mode",
                                subtitle = "Multi-plane focus bracketing for post-capture refocusing",
                                checked = isRefocusPhotoEnabled,
                                onCheckedChange = onRefocusPhotoToggle
                            )

                            if (isRefocusPhotoEnabled) {
                                SamsungDivider()
                                SamsungRowItem(
                                    icon = Icons.Outlined.Layers,
                                    title = "Focus Planes",
                                    subtitle = "$refocusFrameCount focus planes captured per burst"
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(5, 8, 12, 15).forEach { count ->
                                            SamsungSmallChip(
                                                label = "${count}p",
                                                isSelected = refocusFrameCount == count,
                                                onClick = { onRefocusFrameCountChange(count) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. PHOTO MODE PROCESSING PIPELINES
                if (selectedFilterCategory == FlagshipCategory.ALL || selectedFilterCategory == FlagshipCategory.PROCESSING) {
                    item {
                        SamsungSectionHeader("PHOTO PROCESSING PIPELINES")
                        SamsungCard {
                            // Master Toggle
                            SamsungSwitchItem(
                                icon = Icons.Outlined.AutoFixHigh,
                                title = "Custom ISP Image Pipeline",
                                subtitle = "Hardware-accelerated color science & tone-mapping engine",
                                checked = isCustomPipelineEnabled,
                                onCheckedChange = onCustomPipelineToggle
                            )

                            if (isCustomPipelineEnabled) {
                                SamsungDivider()

                                // Active Preset Selector
                                SamsungRowItem(
                                    icon = Icons.Outlined.Palette,
                                    title = "Pipeline Color Science",
                                    subtitle = activePipelinePreset.name
                                ) {
                                    // Row with chips for top presets
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        com.example.camera.pipeline.model.PipelinePreset.BUILT_IN_PRESETS.take(4).forEach { preset ->
                                            SamsungSmallChip(
                                                label = preset.name,
                                                isSelected = activePipelinePreset.id == preset.id,
                                                onClick = { onSelectPipelinePreset(preset) }
                                            )
                                        }
                                    }
                                }

                                SamsungDivider()

                                // Open Pipeline Studio Action
                                SamsungActionItem(
                                    icon = Icons.Outlined.Tune,
                                    title = "Open Processing Studio",
                                    subtitle = "Adjust curves, sharpening, chroma denoise & tone mapping",
                                    actionLabel = "Configure",
                                    onClick = onOpenPipelineStudio
                                )

                                SamsungDivider()

                                // Before / After Compare Action
                                SamsungActionItem(
                                    icon = Icons.Outlined.Compare,
                                    title = "Before / After Compare View",
                                    subtitle = "Side-by-side split screen of sensor RAW vs processed output",
                                    actionLabel = "View",
                                    onClick = onOpenBeforeAfter
                                )
                            }
                        }
                    }
                }

                // 3. MAIN CAMERA STABILIZATION
                if (selectedFilterCategory == FlagshipCategory.ALL || selectedFilterCategory == FlagshipCategory.STABILIZATION) {
                    item {
                        SamsungSectionHeader("MAIN CAMERA STABILIZATION")
                        SamsungCard {
                            // Main Camera Stabilization Mode Selection
                            SamsungRowItem(
                                icon = Icons.Outlined.VideoStable,
                                title = "Main Camera Stabilization",
                                subtitle = mainCameraStabilizationMode.title
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(
                                        MainCameraStabilizationMode.HYBRID_OIS_EIS,
                                        MainCameraStabilizationMode.EIS_ONLY,
                                        MainCameraStabilizationMode.OIS_ONLY,
                                        MainCameraStabilizationMode.ULTRA,
                                        MainCameraStabilizationMode.OFF
                                    ).forEach { mode ->
                                        SamsungSmallChip(
                                            label = when (mode) {
                                                MainCameraStabilizationMode.HYBRID_OIS_EIS -> "OIS+EIS"
                                                MainCameraStabilizationMode.EIS_ONLY -> "EIS Only"
                                                MainCameraStabilizationMode.OIS_ONLY -> "OIS Only"
                                                MainCameraStabilizationMode.ULTRA -> "Ultra"
                                                MainCameraStabilizationMode.OFF -> "Off"
                                            },
                                            isSelected = mainCameraStabilizationMode == mode,
                                            onClick = { onMainCameraStabilizationModeSelected(mode) }
                                        )
                                    }
                                }
                            }

                            // Info badge explaining active synchronization
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFEFF6FF))
                                    .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = when (mainCameraStabilizationMode) {
                                        MainCameraStabilizationMode.HYBRID_OIS_EIS ->
                                            "✓ Synchronized OIS + EIS: Gyroscope frame-sync locked at 500Hz. Eliminates micro-jitter and motion blur."
                                        MainCameraStabilizationMode.EIS_ONLY ->
                                            "✓ EIS Only: Digital sensor stabilization active. Optical floating coil locked to prevent conflict."
                                        MainCameraStabilizationMode.OIS_ONLY ->
                                            "✓ OIS Only: Pure physical voice-coil stabilization. No sensor crop or digital processing."
                                        MainCameraStabilizationMode.ULTRA ->
                                            "✓ Ultra Action Steady: Super-wide gyro EIS for intense action, sports, and running."
                                        MainCameraStabilizationMode.OFF ->
                                            "✕ Stabilization Off: Native raw sensor readout without motion compensation."
                                    },
                                    color = Color(0xFF1D4ED8),
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }

                            SamsungDivider()

                            // Individual OIS Toggle
                            SamsungSwitchItem(
                                icon = Icons.Outlined.Camera,
                                title = "Optical Image Stabilization (OIS)",
                                subtitle = if (capabilities.supportsOis) "Physical voice-coil floating lens stabilization" else "Sensor does not have hardware OIS coil",
                                checked = hybridStabilizationConfig.isOisPreferred && capabilities.supportsOis,
                                enabled = capabilities.supportsOis,
                                onCheckedChange = onOisToggle
                            )

                            SamsungDivider()

                            // Individual Video EIS Toggle
                            SamsungSwitchItem(
                                icon = Icons.Outlined.Videocam,
                                title = "Electronic Video Stabilization (EIS)",
                                subtitle = "Digital sensor margin motion compensation",
                                checked = isVideoStabilizationEnabled && hybridStabilizationConfig.isEisPreferred,
                                onCheckedChange = onStabilizationToggle
                            )

                            SamsungDivider()

                            // Ultra Action Steady Toggle
                            SamsungSwitchItem(
                                icon = Icons.Outlined.DirectionsRun,
                                title = "Ultra Action Steady",
                                subtitle = "Wide-angle action stabilization for intense movement",
                                checked = hybridStabilizationConfig.isUltraStabilizationEnabled,
                                onCheckedChange = { onUltraStabilizationToggle() }
                            )
                        }
                    }
                }

                // 4. VIDEOS & AUDIO
                if (selectedFilterCategory == FlagshipCategory.ALL || selectedFilterCategory == FlagshipCategory.VIDEOS) {
                    item {
                        SamsungSectionHeader("VIDEOS & AUDIO")
                        SamsungCard {
                            // Video Resolution
                            SamsungRowItem(
                                icon = Icons.Outlined.Hd,
                                title = "Video Resolution",
                                subtitle = selectedVideoResolution?.let { "${it.width}x${it.height}" } ?: "4K UHD"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val is4k = selectedVideoResolution?.width == 3840
                                    val is1080 = selectedVideoResolution?.width == 1920
                                    val is720 = selectedVideoResolution?.width == 1280

                                    SamsungSmallChip("4K", is4k) {
                                        capabilities.supportedVideoResolutions.firstOrNull { it.width == 3840 }
                                            ?.let { onVideoResolutionSelected(it) }
                                    }
                                    SamsungSmallChip("1080p", is1080) {
                                        capabilities.supportedVideoResolutions.firstOrNull { it.width == 1920 }
                                            ?.let { onVideoResolutionSelected(it) }
                                    }
                                    SamsungSmallChip("720p", is720) {
                                        capabilities.supportedVideoResolutions.firstOrNull { it.width == 1280 }
                                            ?.let { onVideoResolutionSelected(it) }
                                    }
                                }
                            }

                            SamsungDivider()

                            // Frame Rate
                            SamsungRowItem(
                                icon = Icons.Outlined.Speed,
                                title = "Video Framerate",
                                subtitle = "$videoFps frames per second"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(24, 30, 60).forEach { fps ->
                                        SamsungSmallChip(
                                            label = "${fps}fps",
                                            isSelected = videoFps == fps,
                                            onClick = { onVideoFpsSelected(fps) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // Video Codec
                            SamsungRowItem(
                                icon = Icons.Outlined.Code,
                                title = "Video Codec",
                                subtitle = if (videoCodec == "HEVC") "HEVC / H.265 (High Efficiency)" else "H.264 (Maximum Compatibility)"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("HEVC", "AVC").forEach { codec ->
                                        SamsungSmallChip(
                                            label = codec,
                                            isSelected = videoCodec == codec,
                                            onClick = { onVideoCodecSelected(codec) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // Cinema Log Profile
                            SamsungRowItem(
                                icon = Icons.Outlined.MovieFilter,
                                title = "Cinema Log Curve",
                                subtitle = "${cinemaConfig.colorProfile.label} (${cinemaConfig.logBitDepth.label})"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(
                                        CinemaColorProfile.NATIVE,
                                        CinemaColorProfile.FLAT_LOG,
                                        CinemaColorProfile.REC_2020,
                                        CinemaColorProfile.HLG,
                                        CinemaColorProfile.APPLE_LOG_2
                                    ).forEach { profile ->
                                        SamsungSmallChip(
                                            label = when (profile) {
                                                CinemaColorProfile.NATIVE -> "Native"
                                                CinemaColorProfile.FLAT_LOG -> "Flat"
                                                CinemaColorProfile.REC_2020 -> "Rec.2020"
                                                CinemaColorProfile.HLG -> "HLG"
                                                CinemaColorProfile.APPLE_LOG_2 -> "Apple Log 2"
                                                else -> profile.label
                                            },
                                            isSelected = cinemaConfig.colorProfile == profile,
                                            onClick = { onCinemaConfigChange(cinemaConfig.copy(colorProfile = profile)) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // Audio Recording
                            SamsungSwitchItem(
                                icon = Icons.Outlined.Mic,
                                title = "Record Audio",
                                subtitle = if (isAudioEnabled) "Stereo microphone capture" else "Video muted",
                                checked = isAudioEnabled,
                                onCheckedChange = { onAudioToggle() }
                            )

                            if (isAudioEnabled) {
                                SamsungDivider()

                                // Audio Source
                                SamsungRowItem(
                                    icon = Icons.Outlined.SettingsVoice,
                                    title = "Audio Source",
                                    subtitle = audioSource
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("CAMCORDER", "MIC", "BLUETOOTH").forEach { src ->
                                            SamsungSmallChip(
                                                label = when (src) {
                                                    "CAMCORDER" -> "Built-in"
                                                    "MIC" -> "Ext Mic"
                                                    else -> "Bluetooth"
                                                },
                                                isSelected = audioSource == src,
                                                onClick = { onAudioSourceSelected(src) }
                                            )
                                        }
                                    }
                                }

                                SamsungDivider()

                                // Wind Noise Reduction
                                SamsungSwitchItem(
                                    icon = Icons.Outlined.Air,
                                    title = "Wind Noise Reduction",
                                    subtitle = "Hardware high-pass frequency filter for outdoor wind",
                                    checked = windNoiseReduction,
                                    onCheckedChange = onWindNoiseReductionToggle
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        SamsungSectionHeader("ADAPTIVE DUAL-EXPOSURE HDR VIDEO")
                        SamsungCard {
                            val context = LocalContext.current
                            val cameraPrefs = remember { CameraPreferences(context) }
                            var hdrMode by remember { mutableStateOf(cameraPrefs.adaptiveHdrVideoMode) }
                            var hdrStrength by remember { mutableStateOf(cameraPrefs.hdrExposureStrength) }
                            var hdrPriority by remember { mutableStateOf(cameraPrefs.hdrProcessingPriority) }
                            var hdrSource by remember { mutableStateOf(cameraPrefs.hdrSourceCapture) }
                            var hdrOutput by remember { mutableStateOf(cameraPrefs.hdrOutputFormat) }

                            // 1. Adaptive HDR Video Mode
                            SamsungRowItem(
                                icon = Icons.Outlined.HdrOn,
                                title = "Adaptive HDR Video",
                                subtitle = when (hdrMode) {
                                    AdaptiveHdrMode.OFF -> "Off · Single standard exposure"
                                    AdaptiveHdrMode.AUTO -> "Auto · Enabled automatically for high-dynamic-range scenes"
                                    AdaptiveHdrMode.ALWAYS_ON -> "Always On · Alternating dual-exposure 60 FPS capture"
                                }
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AdaptiveHdrMode.values().forEach { mode ->
                                        SamsungSmallChip(
                                            label = when (mode) {
                                                AdaptiveHdrMode.OFF -> "Off"
                                                AdaptiveHdrMode.AUTO -> "Auto"
                                                AdaptiveHdrMode.ALWAYS_ON -> "Always"
                                            },
                                            isSelected = hdrMode == mode,
                                            onClick = {
                                                hdrMode = mode
                                                cameraPrefs.adaptiveHdrVideoMode = mode
                                            }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // 2. HDR Exposure Strength
                            SamsungRowItem(
                                icon = Icons.Outlined.Tune,
                                title = "HDR Exposure Strength",
                                subtitle = when (hdrStrength) {
                                    HdrExposureStrength.AUTO -> "Auto · Dynamic EV separation based on scene contrast"
                                    HdrExposureStrength.MILD -> "Mild · 1.0 - 1.5 EV separation"
                                    HdrExposureStrength.STANDARD -> "Standard · 1.5 - 2.5 EV separation"
                                    HdrExposureStrength.HIGH -> "High · 2.5 - 3.5 EV separation"
                                    HdrExposureStrength.MAXIMUM -> "Maximum · Up to 4.5 EV separation"
                                }
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    HdrExposureStrength.values().forEach { str ->
                                        SamsungSmallChip(
                                            label = when (str) {
                                                HdrExposureStrength.AUTO -> "Auto"
                                                HdrExposureStrength.MILD -> "Mild"
                                                HdrExposureStrength.STANDARD -> "Std"
                                                HdrExposureStrength.HIGH -> "High"
                                                HdrExposureStrength.MAXIMUM -> "Max"
                                            },
                                            isSelected = hdrStrength == str,
                                            onClick = {
                                                hdrStrength = str
                                                cameraPrefs.hdrExposureStrength = str
                                            }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // 3. Processing Priority
                            SamsungRowItem(
                                icon = Icons.Outlined.Memory,
                                title = "Processing Priority",
                                subtitle = when (hdrPriority) {
                                    ProcessingPriority.MAXIMUM -> "Maximum Speed · Full GPU/CPU compute"
                                    ProcessingPriority.BALANCED -> "Balanced · Smooth background processing"
                                    ProcessingPriority.BATTERY_SAVER -> "Battery Saver · Throttled to keep device cool"
                                }
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ProcessingPriority.values().forEach { prio ->
                                        SamsungSmallChip(
                                            label = when (prio) {
                                                ProcessingPriority.MAXIMUM -> "Max Speed"
                                                ProcessingPriority.BALANCED -> "Balanced"
                                                ProcessingPriority.BATTERY_SAVER -> "Saver"
                                            },
                                            isSelected = hdrPriority == prio,
                                            onClick = {
                                                hdrPriority = prio
                                                cameraPrefs.hdrProcessingPriority = prio
                                            }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // 4. Source Capture
                            SamsungRowItem(
                                icon = Icons.Outlined.CameraAlt,
                                title = "Source Capture",
                                subtitle = when (hdrSource) {
                                    HdrSourceCapture.RAW_IF_SUPPORTED -> "RAW when supported · Lossless sensor data"
                                    HdrSourceCapture.HIGHEST_QUALITY_SUPPORTED -> "Highest Quality Supported"
                                    HdrSourceCapture.AUTOMATIC -> "Automatic · Device optimal sensor stream"
                                }
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    HdrSourceCapture.values().forEach { src ->
                                        SamsungSmallChip(
                                            label = when (src) {
                                                HdrSourceCapture.RAW_IF_SUPPORTED -> "RAW"
                                                HdrSourceCapture.HIGHEST_QUALITY_SUPPORTED -> "HQ"
                                                HdrSourceCapture.AUTOMATIC -> "Auto"
                                            },
                                            isSelected = hdrSource == src,
                                            onClick = {
                                                hdrSource = src
                                                cameraPrefs.hdrSourceCapture = src
                                            }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // 5. Output Format
                            SamsungRowItem(
                                icon = Icons.Outlined.VideoSettings,
                                title = "Output Format",
                                subtitle = when (hdrOutput) {
                                    HdrOutputFormat.HDR_10BIT -> "10-bit HDR (HEVC Main10 BT.2020 HLG)"
                                    HdrOutputFormat.HDR_PLUS_SDR_COMPAT -> "HDR 10-bit + SDR Compatibility Copy"
                                }
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    HdrOutputFormat.values().forEach { out ->
                                        SamsungSmallChip(
                                            label = when (out) {
                                                HdrOutputFormat.HDR_10BIT -> "10-bit HDR"
                                                HdrOutputFormat.HDR_PLUS_SDR_COMPAT -> "HDR+SDR"
                                            },
                                            isSelected = hdrOutput == out,
                                            onClick = {
                                                hdrOutput = out
                                                cameraPrefs.hdrOutputFormat = out
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. UI CUSTOMIZATION & LAYOUT
                if (selectedFilterCategory == FlagshipCategory.ALL || selectedFilterCategory == FlagshipCategory.UI_CUSTOMIZATION) {
                    item {
                        SamsungSectionHeader("UI CUSTOMIZATION & LAYOUT")
                        SamsungCard {
                            // Template Presets
                            SamsungRowItem(
                                icon = Icons.Outlined.ViewQuilt,
                                title = "Camera UI Theme",
                                subtitle = uiCustomizationState.selectedTemplate.title
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(
                                        UiTemplateType.SAMSUNG,
                                        UiTemplateType.IPHONE,
                                        UiTemplateType.STOCK_PIXEL,
                                        UiTemplateType.MINIMAL_PRO
                                    ).forEach { template ->
                                        SamsungSmallChip(
                                            label = when (template) {
                                                UiTemplateType.SAMSUNG -> "One UI"
                                                UiTemplateType.IPHONE -> "iOS"
                                                UiTemplateType.STOCK_PIXEL -> "Pixel"
                                                UiTemplateType.MINIMAL_PRO -> "Leica"
                                                else -> template.title
                                            },
                                            isSelected = uiCustomizationState.selectedTemplate == template,
                                            onClick = { onSelectTemplate(template) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // Custom UI Studio Action
                            SamsungActionItem(
                                icon = Icons.Outlined.DesignServices,
                                title = "Customize Camera UI Studio",
                                subtitle = "Rearrange top bar, shutter, zoom slider, histograms & audio meters",
                                actionLabel = "Open Studio",
                                onClick = onOpenCustomUiStudio
                            )

                            SamsungDivider()

                            // Reset UI Layout
                            SamsungActionItem(
                                icon = Icons.Outlined.RestartAlt,
                                title = "Reset Camera Layout",
                                subtitle = "Restore default One UI layout positions for all camera modes",
                                actionLabel = "Reset",
                                onClick = { onResetAllToTemplate(UiTemplateType.SAMSUNG) }
                            )
                        }
                    }
                }

                // 5b. FLOATING WINDOW APPEARANCE
                if (selectedFilterCategory == FlagshipCategory.ALL || selectedFilterCategory == FlagshipCategory.UI_CUSTOMIZATION) {
                    item {
                        SamsungSectionHeader("FLOATING WINDOW APPEARANCE")
                        SamsungCard {
                            // Preset Style Chips Row
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFEFF6FF)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Layers,
                                                contentDescription = null,
                                                tint = Color(0xFF1D4ED8),
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = "Glass Appearance Style",
                                                color = Color(0xFF0F172A),
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Quick optical presets for floating dialogs & popups",
                                                color = Color(0xFF64748B),
                                                fontSize = 10.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SamsungSmallChip(
                                        label = "Glassmorphic",
                                        isSelected = (floatingWindowAppearance.transparencyPercent == 50 && floatingWindowAppearance.blurStrengthDp == 24),
                                        onClick = { onFloatingWindowAppearanceChange(FloatingWindowAppearanceConfig.GLASSMORPHISM) }
                                    )
                                    SamsungSmallChip(
                                        label = "Subtle Frost",
                                        isSelected = (floatingWindowAppearance.transparencyPercent == 35 && floatingWindowAppearance.blurStrengthDp == 14),
                                        onClick = { onFloatingWindowAppearanceChange(FloatingWindowAppearanceConfig.SUBTLE_FROST) }
                                    )
                                    SamsungSmallChip(
                                        label = "Deep Frost",
                                        isSelected = (floatingWindowAppearance.transparencyPercent == 70 && floatingWindowAppearance.blurStrengthDp == 38),
                                        onClick = { onFloatingWindowAppearanceChange(FloatingWindowAppearanceConfig.DEEP_FROST) }
                                    )
                                    SamsungSmallChip(
                                        label = "Solid Dark",
                                        isSelected = (floatingWindowAppearance.transparencyPercent == 15 && floatingWindowAppearance.blurStrengthDp == 8),
                                        onClick = { onFloatingWindowAppearanceChange(FloatingWindowAppearanceConfig.SOLID_DARK) }
                                    )
                                }
                            }

                            SamsungDivider()

                            // Slider 1: Transparency (controls how much blurred background shows through)
                            SamsungSliderItem(
                                icon = Icons.Outlined.Opacity,
                                title = "Transparency",
                                subtitle = "Controls how much blurred background shows through",
                                value = floatingWindowAppearance.transparency,
                                valueRange = 0.0f..1.0f,
                                valueDisplay = "${floatingWindowAppearance.transparencyPercent}%",
                                onValueChange = onFloatingWindowTransparencyChange
                            )

                            SamsungDivider()

                            // Slider 2: Blur Strength (controls optical blur applied to background under window)
                            SamsungSliderItem(
                                icon = Icons.Outlined.BlurOn,
                                title = "Blur Strength",
                                subtitle = "Backdrop blur intensity applied behind the window",
                                value = floatingWindowAppearance.blurStrength,
                                valueRange = 0.0f..50.0f,
                                valueDisplay = "${floatingWindowAppearance.blurStrengthDp} dp",
                                onValueChange = onFloatingWindowBlurStrengthChange
                            )

                            SamsungDivider()

                            // Live Frosted Glass Preview Card (visualizing instant live updates)
                            SamsungFrostedPreviewCard(
                                transparency = floatingWindowAppearance.transparency,
                                blurStrength = floatingWindowAppearance.blurStrength
                            )

                            SamsungDivider()

                            // Reset Action Item
                            SamsungActionItem(
                                icon = Icons.Outlined.RestartAlt,
                                title = "Reset Window Appearance",
                                subtitle = "Restore default 50% transparency and 24 dp blur",
                                actionLabel = "Reset",
                                onClick = onResetFloatingWindowAppearance
                            )
                        }
                    }
                }

                // 6. INTELLIGENT FEATURES
                if (selectedFilterCategory == FlagshipCategory.ALL || selectedFilterCategory == FlagshipCategory.INTELLIGENT) {
                    item {
                        SamsungSectionHeader("INTELLIGENT FEATURES")
                        SamsungCard {
                            // AI Super-Resolution Zoom
                            SamsungSwitchItem(
                                icon = Icons.Outlined.ZoomIn,
                                title = "AI Super-Resolution Zoom",
                                subtitle = "Multi-frame subpixel detail reconstruction (Lanczos-3)",
                                checked = isHighQualityZoomEnabled,
                                onCheckedChange = onHighQualityZoomToggle
                            )

                            if (isHighQualityZoomEnabled) {
                                SamsungDivider()
                                SamsungRowItem(
                                    icon = Icons.Outlined.ShutterSpeed,
                                    title = "Zoom Clarity Quality",
                                    subtitle = zoomProcessingQuality.label
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        com.example.camera.zoom.ZoomProcessingQuality.entries.forEach { q ->
                                            SamsungSmallChip(
                                                label = when (q) {
                                                    com.example.camera.zoom.ZoomProcessingQuality.FAST -> "Speed"
                                                    com.example.camera.zoom.ZoomProcessingQuality.BALANCED -> "Balanced"
                                                    com.example.camera.zoom.ZoomProcessingQuality.MAXIMUM -> "Clarity"
                                                },
                                                isSelected = zoomProcessingQuality == q,
                                                onClick = { onZoomProcessingQualitySelect(q) }
                                            )
                                        }
                                    }
                                }
                            }

                            SamsungDivider()

                            // Optical Defocus Portrait
                            SamsungSwitchItem(
                                icon = Icons.Outlined.Portrait,
                                title = "Optical Defocus Guided Portrait",
                                subtitle = "Optical blur estimation and fine hair matting",
                                checked = portraitConfig.opticalBlurGuided,
                                onCheckedChange = { onPortraitConfigChange(portraitConfig.copy(opticalBlurGuided = it)) }
                            )

                            SamsungDivider()

                            // AI Auto-Framing
                            SamsungSwitchItem(
                                icon = Icons.Outlined.CropFree,
                                title = "AI Auto-Framing",
                                subtitle = "Smoothly crop and follow detected subjects automatically",
                                checked = isAiAutoFramingEnabled,
                                onCheckedChange = onAiAutoFramingToggle
                            )

                            SamsungDivider()

                            // Tap to Focus & Spot Metering
                            SamsungSwitchItem(
                                icon = Icons.Outlined.CenterFocusStrong,
                                title = "Tap to Focus & Spot Metering",
                                subtitle = "Lock focus point and calculate exposure from touch target",
                                checked = tapFocusConfig.isTapToFocusEnabled,
                                onCheckedChange = { onTapFocusConfigChange(tapFocusConfig.copy(isTapToFocusEnabled = it)) }
                            )
                        }
                    }
                }

                // 7. CONTROLS & FEEDBACK
                if (selectedFilterCategory == FlagshipCategory.ALL || selectedFilterCategory == FlagshipCategory.CONTROLS) {
                    item {
                        SamsungSectionHeader("CONTROLS & FEEDBACK")
                        SamsungCard {
                            // Save Selfie As Previewed
                            SamsungSwitchItem(
                                icon = Icons.Outlined.FlipCameraAndroid,
                                title = "Save Selfies as Previewed",
                                subtitle = "Save front camera photos without flipping horizontally",
                                checked = saveSelfieAsPreviewed,
                                onCheckedChange = onSaveSelfieAsPreviewedToggle
                            )

                            SamsungDivider()

                            // Volume Key Action
                            SamsungRowItem(
                                icon = Icons.Outlined.VolumeUp,
                                title = "Volume Key Action",
                                subtitle = volumeKeyAction
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("SHUTTER", "ZOOM", "VOLUME").forEach { action ->
                                        SamsungSmallChip(
                                            label = action,
                                            isSelected = volumeKeyAction == action,
                                            onClick = { onVolumeKeyActionSelected(action) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // Double-Tap Action
                            SamsungRowItem(
                                icon = Icons.Outlined.TouchApp,
                                title = "Double-Tap Action",
                                subtitle = if (doubleTapAction == "FLIP") "Switch Front/Rear" else doubleTapAction
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("FLIP", "ZOOM", "NONE").forEach { act ->
                                        SamsungSmallChip(
                                            label = act,
                                            isSelected = doubleTapAction == act,
                                            onClick = { onDoubleTapActionSelected(act) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // Shutter Feedback
                            SamsungRowItem(
                                icon = Icons.Outlined.Vibration,
                                title = "Shutter Feedback",
                                subtitle = shutterFeedback.replace("_", " ")
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("SOUND_AND_HAPTIC", "HAPTIC_ONLY", "SILENT").forEach { mode ->
                                        SamsungSmallChip(
                                            label = when (mode) {
                                                "SOUND_AND_HAPTIC" -> "Both"
                                                "HAPTIC_ONLY" -> "Haptic"
                                                else -> "Silent"
                                            },
                                            isSelected = shutterFeedback == mode,
                                            onClick = { onShutterFeedbackSelected(mode) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 8. ADVANCED & LABS
                if (selectedFilterCategory == FlagshipCategory.ALL || selectedFilterCategory == FlagshipCategory.ADVANCED) {
                    item {
                        SamsungSectionHeader("ADVANCED & LABS")
                        SamsungCard {
                            // Anti-Banding
                            SamsungRowItem(
                                icon = Icons.Outlined.WbIncandescent,
                                title = "Anti-Banding (Flicker)",
                                subtitle = "Frequency flicker elimination ($antibandingMode)"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("AUTO", "50HZ", "60HZ").forEach { mode ->
                                        SamsungSmallChip(
                                            label = mode,
                                            isSelected = antibandingMode == mode,
                                            onClick = { onAntibandingModeSelected(mode) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // Viewfinder Refresh Rate
                            SamsungRowItem(
                                icon = Icons.Outlined.Refresh,
                                title = "Viewfinder Framerate",
                                subtitle = "${viewfinderFps}Hz smooth preview"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(60, 120).forEach { rate ->
                                        SamsungSmallChip(
                                            label = "${rate}Hz",
                                            isSelected = viewfinderFps == rate,
                                            onClick = { onViewfinderFpsSelected(rate) }
                                        )
                                    }
                                }
                            }

                            SamsungDivider()

                            // Tilt Horizon Leveler
                            SamsungSwitchItem(
                                icon = Icons.Outlined.ScreenRotation,
                                title = "Gyro Horizon Leveler",
                                subtitle = "Real-time gravity leveling guide line",
                                checked = horizonLeveler,
                                onCheckedChange = onHorizonLevelerToggle
                            )

                            SamsungDivider()

                            // Thermal Protection
                            SamsungSwitchItem(
                                icon = Icons.Outlined.Thermostat,
                                title = "Thermal Protection",
                                subtitle = "Dynamically throttle ISP load during prolonged recording",
                                checked = thermalProtection,
                                onCheckedChange = onThermalProtectionToggle
                            )

                            SamsungDivider()

                            // Motorola Instant Camera Switching
                            SamsungSwitchItem(
                                icon = Icons.Outlined.Cameraswitch,
                                title = "Keep Ultra-Wide Sensor Ready",
                                subtitle = "Background standby stream for instant lens transitions",
                                checked = instantSwitchState.isKeepUltraWideReady,
                                onCheckedChange = onKeepUltraWideReadyToggle
                            )
                        }
                    }
                }

                // 9. GENERAL / ABOUT & RESET
                if (selectedFilterCategory == FlagshipCategory.ALL || selectedFilterCategory == FlagshipCategory.ABOUT) {
                    item {
                        SamsungSectionHeader("ABOUT & DIAGNOSTICS")
                        SamsungCard {
                            // Hardware Diagnostic Info
                            SamsungRowItem(
                                icon = Icons.Outlined.Info,
                                title = "Camera Hardware",
                                subtitle = "Camera2 Level 3 · ${availableLenses.size} detected lenses · ${if (capabilities.supportsOis) "OIS Present" else "No OIS"}"
                            )

                            SamsungDivider()

                            // Reset Settings
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showResetDialog = true }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFFEE2E2)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.RestartAlt,
                                            contentDescription = null,
                                            tint = Color(0xFFDC2626),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "Reset Settings",
                                            color = Color(0xFFDC2626),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Restore all camera parameters to factory defaults",
                                            color = Color(0xFF64748B),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "Reset Camera Settings?",
                    color = Color(0xFF0F172A),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will restore photo, video, processing pipeline, stabilization, and control preferences back to factory defaults.",
                    color = Color(0xFF475569),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetAllSettings()
                        showResetDialog = false
                    }
                ) {
                    Text("Reset", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B), fontSize = 13.sp)
                }
            }
        )
    }
}

@Composable
private fun SamsungSectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF1D4ED8),
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun SamsungCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
private fun SamsungDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        thickness = 0.6.dp,
        color = Color(0xFFF1F5F9)
    )
}

@Composable
private fun SamsungRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEFF6FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF1D4ED8),
                    modifier = Modifier.size(15.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    color = Color(0xFF0F172A),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = Color(0xFF64748B),
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (action != null) {
            Spacer(modifier = Modifier.width(6.dp))
            action()
        }
    }
}

@Composable
private fun SamsungActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
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
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEFF6FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF1D4ED8),
                    modifier = Modifier.size(15.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    color = Color(0xFF0F172A),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = Color(0xFF64748B),
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = actionLabel,
                color = Color(0xFF1D4ED8),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF1D4ED8),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun SamsungSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 7.dp),
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
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (enabled) Color(0xFFEFF6FF) else Color(0xFFF1F5F9)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) Color(0xFF1D4ED8) else Color(0xFF94A3B8),
                    modifier = Modifier.size(15.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    color = if (enabled) Color(0xFF0F172A) else Color(0xFF94A3B8),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = if (enabled) Color(0xFF64748B) else Color(0xFFCBD5E1),
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF1D4ED8),
                uncheckedThumbColor = Color(0xFF94A3B8),
                uncheckedTrackColor = Color(0xFFE2E8F0)
            ),
            modifier = Modifier.scale(0.75f)
        )
    }
}

@Composable
private fun SamsungSmallChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFF1D4ED8) else Color(0xFFF1F5F9),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) Color(0xFF1D4ED8) else Color(0xFFE2E8F0)
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else Color(0xFF334155),
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun SamsungSliderItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
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
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEFF6FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFF1D4ED8),
                        modifier = Modifier.size(15.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        color = Color(0xFF0F172A),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        color = Color(0xFF64748B),
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFEFF6FF),
                border = BorderStroke(1.dp, Color(0xFFBFDBFE))
            ) {
                Text(
                    text = valueDisplay,
                    color = Color(0xFF1D4ED8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF1D4ED8),
                activeTrackColor = Color(0xFF1D4ED8),
                inactiveTrackColor = Color(0xFFE2E8F0)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
        )
    }
}

@Composable
private fun SamsungFrostedPreviewCard(
    transparency: Float,
    blurStrength: Float
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = "LIVE WINDOW PREVIEW",
            color = Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Vibrant simulated viewfinder background with bokeh spheres
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF1E293B),
                            Color(0xFF0D9488),
                            Color(0xFFE11D48)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Simulated photographic backdrop elements (bokeh circles)
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .offset(x = (-60).dp, y = (-20).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8).copy(alpha = 0.55f))
            )
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(x = 50.dp, y = 20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFBBF24).copy(alpha = 0.60f))
            )
            Box(
                modifier = Modifier
                    .size(45.dp)
                    .offset(x = 20.dp, y = (-30).dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEC4899).copy(alpha = 0.50f))
            )

            // Floating Frosted Glass Window Preview
            // Blur radius matches blurStrength, transparency governs background show-through
            val previewTintAlpha = (1.0f - transparency).coerceIn(0.10f, 0.95f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 1.dp,
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.60f),
                                Color.White.copy(alpha = 0.20f),
                                Color.White.copy(alpha = 0.05f)
                            )
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .background(
                        Color(0xFF0B0F19).copy(alpha = previewTintAlpha)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Layers,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Frosted Glass Window",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${(transparency * 100f).roundToInt()}% Transparency · ${blurStrength.roundToInt()} dp Blur",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Backdrop blur is applied to background content under the window. Surrounding viewfinder remains crisp.",
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            lineHeight = 13.sp
        )
    }
}

