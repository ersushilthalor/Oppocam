package com.example.camera.ui

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.camera.model.*

/**
 * Camera UI Customization Sheet & Visual Layout Editor.
 *
 * Implements:
 * 1. Ready-made UI Templates:
 *    - iPhone Style
 *    - Samsung One UI Style
 *    - Vivo Origin/Funtouch Style
 *    - Custom UI
 * 2. Complete Live Visual Editor with interactive canvas & controls:
 *    - Mode Selector (Above / Below Shutter, styles, fonts, reorder & show/hide modes)
 *    - Shutter Button (5 distinctive styles, sizing & offset)
 *    - Top Controls (alignment, icon size, spacing, show/hide individual items & reordering)
 *    - Viewfinder Zoom Capsule (scale, vertical offset, visibility)
 *    - Per-mode customized layouts (Photo, Video, Portrait, Cinema, etc.)
 *    - Presets management (Save, Load, Delete, Reset)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraUiCustomizationView(
    uiState: UiCustomizationState,
    currentCameraMode: CameraMode,
    onSelectTemplate: (UiTemplateType) -> Unit,
    onUpdateGlobalConfig: (ModeLayoutConfig) -> Unit,
    onUpdateModeConfig: (CameraMode, ModeLayoutConfig) -> Unit,
    onResetModeConfig: (CameraMode) -> Unit,
    onSavePreset: (String, ModeLayoutConfig) -> Unit,
    onLoadPreset: (CustomUiPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onResetAllToTemplate: (UiTemplateType) -> Unit,
    modifier: Modifier = Modifier
) {
    var isLiveEditorOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Section Title: Templates
        Text(
            text = "Camera UI Templates",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Select a pre-designed layout or build your own custom interface.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Ready-made Templates Cards
        UiTemplateType.entries.forEach { template ->
            val isSelected = uiState.selectedTemplate == template
            val accentColor = try {
                Color(android.graphics.Color.parseColor(template.accentHex))
            } catch (e: Exception) {
                MaterialTheme.colorScheme.primary
            }

            Surface(
                onClick = { onSelectTemplate(template) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("template_card_${template.name.lowercase()}"),
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) Color(0xFF1E2638) else Color(0xFF151820),
                border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, accentColor) else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) accentColor else Color(0x33FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = when (template) {
                            UiTemplateType.STOCK_PIXEL -> Icons.Outlined.PhotoCamera
                            UiTemplateType.MINIMAL_PRO -> Icons.Outlined.CenterFocusStrong
                            UiTemplateType.FUTURISTIC_GLASS -> Icons.Outlined.AutoAwesome
                            UiTemplateType.DSLR_PRO -> Icons.Outlined.CameraAlt
                            UiTemplateType.IMMERSIVE_EDGE -> Icons.Outlined.Fullscreen
                            UiTemplateType.IPHONE -> Icons.Outlined.PhoneIphone
                            UiTemplateType.SAMSUNG -> Icons.Outlined.PhoneAndroid
                            UiTemplateType.VIVO -> Icons.Outlined.Camera
                            UiTemplateType.CUSTOM -> Icons.Outlined.Tune
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = template.title,
                            tint = if (isSelected) Color.Black else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = template.title,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = accentColor.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        color = accentColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = template.subtitle,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectTemplate(template) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = accentColor,
                            unselectedColor = Color.White.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Open Visual Live Editor Button
        Button(
            onClick = { isLiveEditorOpen = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("open_live_ui_editor_button"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2563EB)
            )
        ) {
            Icon(
                imageVector = Icons.Outlined.DesignServices,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Open Visual Layout Editor",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Saved Custom Presets Section
        if (uiState.customPresets.isNotEmpty()) {
            Text(
                text = "Saved Presets",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))

            uiState.customPresets.forEach { preset ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1B1F2A)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.name,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "${preset.config.shutterStyle.label} · ${preset.config.modeSelectorPosition.label}",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onLoadPreset(preset) }) {
                                Text("Apply", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = { onDeletePreset(preset.id) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Delete preset",
                                    tint = Color(0xFFEF5350),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Full Screen Visual Layout Editor Dialog
    if (isLiveEditorOpen) {
        LiveUiEditorDialog(
            uiState = uiState,
            currentCameraMode = currentCameraMode,
            onDismiss = { isLiveEditorOpen = false },
            onUpdateGlobalConfig = onUpdateGlobalConfig,
            onUpdateModeConfig = onUpdateModeConfig,
            onResetModeConfig = onResetModeConfig,
            onSavePreset = onSavePreset,
            onResetAllToTemplate = onResetAllToTemplate
        )
    }
}

/**
 * Fullscreen Interactive Visual UI Editor Dialog with Live Canvas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveUiEditorDialog(
    uiState: UiCustomizationState,
    currentCameraMode: CameraMode,
    onDismiss: () -> Unit,
    onUpdateGlobalConfig: (ModeLayoutConfig) -> Unit,
    onUpdateModeConfig: (CameraMode, ModeLayoutConfig) -> Unit,
    onResetModeConfig: (CameraMode) -> Unit,
    onSavePreset: (String, ModeLayoutConfig) -> Unit,
    onResetAllToTemplate: (UiTemplateType) -> Unit
) {
    // Mode customization scope: null means Global (All modes), or specific CameraMode
    var targetModeScope by remember { mutableStateOf<CameraMode?>(null) }

    // Active editor tab
    var activeTab by remember { mutableStateOf(EditorTab.MODES) }

    // Working config state for real-time reactivity
    val activeConfig = remember(targetModeScope, uiState) {
        if (targetModeScope == null) {
            uiState.globalConfig
        } else {
            uiState.getConfigForMode(targetModeScope!!)
        }
    }

    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    fun updateConfig(newCfg: ModeLayoutConfig) {
        if (targetModeScope == null) {
            onUpdateGlobalConfig(newCfg)
        } else {
            onUpdateModeConfig(targetModeScope!!, newCfg)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Visual Camera Layout Editor",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (targetModeScope == null) "Editing: Global (All Modes)" else "Editing: ${targetModeScope!!.name} Mode Only",
                                fontSize = 12.sp,
                                color = Color(0xFF64B5F6)
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        TextButton(onClick = { showSavePresetDialog = true }) {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Preset", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF10141E))
                )
            },
            containerColor = Color(0xFF0C0F17)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // 1. Mode Scope Selector Bar (Global vs. Specific Mode)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF141A29)
                ) {
                    val scopeScroll = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scopeScroll)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = targetModeScope == null,
                            onClick = { targetModeScope = null },
                            label = { Text("Global (All Modes)", fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF2563EB),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0x22FFFFFF),
                                labelColor = Color.White.copy(alpha = 0.8f)
                            )
                        )

                        CameraMode.entries.forEach { mode ->
                            val hasOverride = uiState.modeSpecificConfigs.containsKey(mode)
                            FilterChip(
                                selected = targetModeScope == mode,
                                onClick = { targetModeScope = mode },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(mode.name)
                                        if (hasOverride) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFFFD54F))
                                            )
                                        }
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF2563EB),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color(0x22FFFFFF),
                                    labelColor = Color.White.copy(alpha = 0.8f)
                                )
                            )
                        }
                    }
                }

                // 2. Interactive Live Preview Canvas (Upper half)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.0f)
                        .background(Color.Black)
                        .border(1.dp, Color(0x33FFFFFF))
                ) {
                    LivePreviewCanvas(
                        config = activeConfig,
                        cameraMode = targetModeScope ?: currentCameraMode
                    )

                    // Floating indicator in preview
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = "LIVE PREVIEW",
                            color = Color(0xFFFFD54F),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // 3. Editor Tabs (Lower half controls)
                TabRow(
                    selectedTabIndex = activeTab.ordinal,
                    containerColor = Color(0xFF141926),
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[activeTab.ordinal]),
                            color = Color(0xFF3B82F6)
                        )
                    }
                ) {
                    EditorTab.entries.forEach { tab ->
                        Tab(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            text = {
                                Text(
                                    text = tab.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                // 4. Editor Tab Content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.15f)
                        .background(Color(0xFF0F131D))
                ) {
                    when (activeTab) {
                        EditorTab.MODES -> ModeSelectorEditor(
                            config = activeConfig,
                            onUpdate = { updateConfig(it) }
                        )
                        EditorTab.SHUTTER -> ShutterAndActionsEditor(
                            config = activeConfig,
                            onUpdate = { updateConfig(it) }
                        )
                        EditorTab.TOP_BAR -> TopBarControlsEditor(
                            config = activeConfig,
                            onUpdate = { updateConfig(it) }
                        )
                        EditorTab.ZOOM_VIEWFINDER -> ZoomAndViewfinderEditor(
                            config = activeConfig,
                            onUpdate = { updateConfig(it) }
                        )
                        EditorTab.RESET -> ResetAndRestoreEditor(
                            targetMode = targetModeScope,
                            onResetMode = {
                                if (targetModeScope != null) {
                                    onResetModeConfig(targetModeScope!!)
                                }
                            },
                            onResetAllToTemplate = onResetAllToTemplate
                        )
                    }
                }
            }
        }
    }

    // Save Preset Dialog
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("Save Custom Preset", color = Color.White) },
            text = {
                Column {
                    Text("Enter a unique name for this custom layout preset:", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        placeholder = { Text("e.g. Minimal Night, Cinema Rig") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0x55FFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSavePreset(newPresetName, activeConfig)
                        showSavePresetDialog = false
                        newPresetName = ""
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            },
            containerColor = Color(0xFF1A202C)
        )
    }
}

enum class EditorTab(val label: String) {
    MODES("Modes"),
    SHUTTER("Shutter"),
    TOP_BAR("Top Bar"),
    ZOOM_VIEWFINDER("Zoom"),
    RESET("Presets & Reset")
}

/**
 * Live Preview Canvas simulating camera screen with applied custom layout config.
 */
@Composable
fun LivePreviewCanvas(
    config: ModeLayoutConfig,
    cameraMode: CameraMode,
    modifier: Modifier = Modifier
) {
    val accentColor = config.getComposeAccentColor()
    val fontFamily = config.modeFontFamily.toComposeFontFamily()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05070A))
    ) {
        // Grid Horizon lines hint
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val lineCol = Color.White.copy(alpha = 0.08f)
            drawLine(lineCol, start = androidx.compose.ui.geometry.Offset(w / 3f, 0f), end = androidx.compose.ui.geometry.Offset(w / 3f, h))
            drawLine(lineCol, start = androidx.compose.ui.geometry.Offset(w * 2f / 3f, 0f), end = androidx.compose.ui.geometry.Offset(w * 2f / 3f, h))
            drawLine(lineCol, start = androidx.compose.ui.geometry.Offset(0f, h / 3f), end = androidx.compose.ui.geometry.Offset(w, h / 3f))
            drawLine(lineCol, start = androidx.compose.ui.geometry.Offset(0f, h * 2f / 3f), end = androidx.compose.ui.geometry.Offset(w, h * 2f / 3f))
        }

        // Top Controls Preview
        val visibleTopItems = config.topControlsOrder.filterNot { config.hiddenTopControls.contains(it) }
        val horizontalArrangement = when (config.topBarAlignment) {
            TopBarAlignment.SPACE_BETWEEN -> Arrangement.SpaceBetween
            TopBarAlignment.CENTER -> Arrangement.spacedBy(config.topControlsSpacingDp.dp, Alignment.CenterHorizontally)
            TopBarAlignment.COMPACT_LEFT -> Arrangement.spacedBy(config.topControlsSpacingDp.dp, Alignment.Start)
            TopBarAlignment.COMPACT_RIGHT -> Arrangement.spacedBy(config.topControlsSpacingDp.dp, Alignment.End)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = config.topPaddingDp.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = Alignment.CenterVertically
        ) {
            visibleTopItems.forEach { item ->
                val icon = when (item) {
                    TopControlItem.FLASH -> Icons.Outlined.FlashOn
                    TopControlItem.TIMER -> Icons.Outlined.Timer
                    TopControlItem.GRID -> Icons.Outlined.GridOn
                    TopControlItem.RESOLUTION -> Icons.Outlined.HighQuality
                    TopControlItem.RAW -> Icons.Outlined.RawOn
                    TopControlItem.PRO_EXP -> Icons.Outlined.Tune
                    TopControlItem.SETTINGS -> Icons.Outlined.Settings
                }
                Icon(
                    imageVector = icon,
                    contentDescription = item.label,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(config.topControlsIconSizeDp.dp)
                )
            }
        }

        // Master Zoom Capsule Preview
        if (config.showZoomCapsule) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (60 + config.zoomCapsuleVerticalOffsetDp).dp)
                    .scale(config.zoomCapsuleScale)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(".5", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("1x", color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        Text("2", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("5", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Bottom Controls Container
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(top = 10.dp, bottom = config.bottomPaddingDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode Selector ABOVE shutter
            if (config.modeSelectorPosition == ModeSelectorPosition.ABOVE_SHUTTER) {
                PreviewModeCarousel(config = config, currentMode = cameraMode, accentColor = accentColor, fontFamily = fontFamily)
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Shutter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Thumbnail
                if (config.showGalleryButton) {
                    Box(
                        modifier = Modifier
                            .size(config.galleryThumbSizeDp.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FFFFFF))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.size(config.galleryThumbSizeDp.dp))
                }

                // Shutter Button
                Box(
                    modifier = Modifier.offset(x = config.shutterHorizontalOffsetDp.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PreviewShutterButton(config = config, accentColor = accentColor)
                }

                // Flip Button
                if (config.showFlipButton) {
                    Box(
                        modifier = Modifier
                            .size(config.flipButtonSizeDp.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FFFFFF))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.FlipCameraAndroid, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.size(config.flipButtonSizeDp.dp))
                }
            }

            // Mode Selector BELOW shutter
            if (config.modeSelectorPosition == ModeSelectorPosition.BELOW_SHUTTER) {
                Spacer(modifier = Modifier.height(10.dp))
                PreviewModeCarousel(config = config, currentMode = cameraMode, accentColor = accentColor, fontFamily = fontFamily)
            }
        }
    }
}

@Composable
fun PreviewShutterButton(config: ModeLayoutConfig, accentColor: Color) {
    val sizeDp = config.shutterSizeDp.dp
    when (config.shutterStyle) {
        ShutterStyle.CLASSIC_WHITE -> {
            Box(
                modifier = Modifier
                    .size(sizeDp)
                    .clip(CircleShape)
                    .border(3.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(sizeDp * 0.78f)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        ShutterStyle.APPLE_DOT -> {
            Box(
                modifier = Modifier
                    .size(sizeDp)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(sizeDp * 0.82f)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        ShutterStyle.SAMSUNG_CAPSULE -> {
            Box(
                modifier = Modifier
                    .size(sizeDp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        ShutterStyle.VIVO_GIMBAL -> {
            Box(
                modifier = Modifier
                    .size(sizeDp)
                    .clip(CircleShape)
                    .border(3.dp, accentColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(sizeDp * 0.75f)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        ShutterStyle.MINIMAL_ACCENT -> {
            Box(
                modifier = Modifier
                    .size(sizeDp * 0.85f)
                    .clip(CircleShape)
                    .background(accentColor)
            )
        }
        ShutterStyle.PIXEL_SOLID -> {
            Box(
                modifier = Modifier
                    .size(sizeDp)
                    .clip(CircleShape)
                    .border(3.5.dp, Color.White, CircleShape)
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(sizeDp * 0.78f)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        ShutterStyle.LEICA_RED_DOT -> {
            Box(
                modifier = Modifier
                    .size(sizeDp)
                    .clip(CircleShape)
                    .border(2.5.dp, Color(0xFFE0E0E0), CircleShape)
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(sizeDp * 0.75f)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                )
            }
        }
        ShutterStyle.CYBER_HOLO -> {
            Box(
                modifier = Modifier
                    .size(sizeDp)
                    .clip(CircleShape)
                    .border(2.dp, Color(0xFF00E5FF), CircleShape)
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(sizeDp * 0.75f)
                        .clip(CircleShape)
                        .background(Color(0xFF00E5FF))
                )
            }
        }
        ShutterStyle.DSLR_KNURLED -> {
            Box(
                modifier = Modifier
                    .size(sizeDp)
                    .clip(CircleShape)
                    .border(4.dp, Color(0xFF555555), CircleShape)
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(sizeDp * 0.8f)
                        .clip(CircleShape)
                        .background(Color(0xFFD5D5D5))
                )
            }
        }
    }
}

@Composable
fun PreviewModeCarousel(
    config: ModeLayoutConfig,
    currentMode: CameraMode,
    accentColor: Color,
    fontFamily: androidx.compose.ui.text.font.FontFamily
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        config.visibleModes.take(5).forEach { mode ->
            val isSelected = mode == currentMode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (config.modeSelectorStyle) {
                    ModeSelectorStyle.CLASSIC_DOT -> {
                        Text(
                            text = mode.name,
                            color = if (isSelected) accentColor else Color.White.copy(alpha = 0.6f),
                            fontSize = config.modeTextSizeSp.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = fontFamily
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                    }
                    ModeSelectorStyle.CAPSULE_PILL -> {
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) accentColor else Color.Transparent
                        ) {
                            Text(
                                text = mode.name,
                                color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.6f),
                                fontSize = config.modeTextSizeSp.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = fontFamily,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                            )
                        }
                    }
                    ModeSelectorStyle.UNDERLINE -> {
                        Text(
                            text = mode.name,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = config.modeTextSizeSp.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = fontFamily
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height(2.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                    }
                    ModeSelectorStyle.MINIMAL_TEXT -> {
                        Text(
                            text = mode.name,
                            color = if (isSelected) accentColor else Color.White.copy(alpha = 0.5f),
                            fontSize = config.modeTextSizeSp.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal,
                            fontFamily = fontFamily
                        )
                    }
                    ModeSelectorStyle.PIXEL_PILL -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) Color(0x44FFFFFF) else Color.Transparent
                        ) {
                            Text(
                                text = mode.name,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                                fontSize = config.modeTextSizeSp.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = fontFamily,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    ModeSelectorStyle.MONO_TICKER -> {
                        Text(
                            text = mode.name,
                            color = if (isSelected) Color(0xFFE53935) else Color.White.copy(alpha = 0.5f),
                            fontSize = config.modeTextSizeSp.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    ModeSelectorStyle.CYBER_GLOW -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) Color(0x3300E5FF) else Color.Transparent
                        ) {
                            Text(
                                text = mode.name,
                                color = if (isSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.6f),
                                fontSize = config.modeTextSizeSp.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    ModeSelectorStyle.DSLR_DIAL -> {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSelected) Color(0xFF262C36) else Color.Transparent
                        ) {
                            Text(
                                text = mode.name,
                                color = if (isSelected) Color(0xFFFFB300) else Color.Gray,
                                fontSize = config.modeTextSizeSp.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Editors per Tab ----------------

@Composable
fun ModeSelectorEditor(
    config: ModeLayoutConfig,
    onUpdate: (ModeLayoutConfig) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Mode Selector Position", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModeSelectorPosition.entries.forEach { pos ->
                    val isSel = config.modeSelectorPosition == pos
                    FilterChip(
                        selected = isSel,
                        onClick = { onUpdate(config.copy(modeSelectorPosition = pos)) },
                        label = { Text(pos.label, fontSize = 12.sp) }
                    )
                }
            }
        }

        item {
            Text("Indicator Style", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeSelectorStyle.entries.forEach { style ->
                    val isSel = config.modeSelectorStyle == style
                    FilterChip(
                        selected = isSel,
                        onClick = { onUpdate(config.copy(modeSelectorStyle = style)) },
                        label = { Text(style.label, fontSize = 11.sp) }
                    )
                }
            }
        }

        item {
            Text("Font Family / Typeface", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FontFamilyOption.entries.forEach { font ->
                    val isSel = config.modeFontFamily == font
                    FilterChip(
                        selected = isSel,
                        onClick = { onUpdate(config.copy(modeFontFamily = font)) },
                        label = { Text(font.label, fontSize = 11.sp) }
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Text Size", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Text("${config.modeTextSizeSp.toInt()} sp", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.modeTextSizeSp,
                onValueChange = { onUpdate(config.copy(modeTextSizeSp = it)) },
                valueRange = 11f..18f,
                steps = 6
            )
        }

        item {
            Text("Mode Order & Visibility (Reorder & Toggle)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Text("Select which modes are shown in the carousel and their display order.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }

        item {
            Text("Main Carousel Modes (Photo, Portrait, Video, More). All other modes are organized inside More Modes.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }

        val primaryCarouselModes = listOf(CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.VIDEO, CameraMode.MORE)
        items(primaryCarouselModes) { mode ->
            val isVisible = config.visibleModes.contains(mode)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF191E2C),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isVisible,
                            onCheckedChange = { checked ->
                                val list = config.visibleModes.toMutableList()
                                if (checked && !list.contains(mode)) {
                                    list.add(mode)
                                } else if (!checked) {
                                    list.remove(mode)
                                }
                                onUpdate(config.copy(visibleModes = list))
                            }
                        )
                        Text(mode.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }

                    if (isVisible) {
                        Row {
                            val curIdx = config.visibleModes.indexOf(mode)
                            IconButton(
                                enabled = curIdx > 0,
                                onClick = {
                                    val list = config.visibleModes.toMutableList()
                                    val temp = list[curIdx - 1]
                                    list[curIdx - 1] = mode
                                    list[curIdx] = temp
                                    onUpdate(config.copy(visibleModes = list))
                                }
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", tint = if (curIdx > 0) Color.White else Color.Gray, modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                enabled = curIdx < config.visibleModes.size - 1,
                                onClick = {
                                    val list = config.visibleModes.toMutableList()
                                    val temp = list[curIdx + 1]
                                    list[curIdx + 1] = mode
                                    list[curIdx] = temp
                                    onUpdate(config.copy(visibleModes = list))
                                }
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", tint = if (curIdx < config.visibleModes.size - 1) Color.White else Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShutterAndActionsEditor(
    config: ModeLayoutConfig,
    onUpdate: (ModeLayoutConfig) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Shutter Button Style", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            ShutterStyle.entries.forEach { style ->
                val isSel = config.shutterStyle == style
                Surface(
                    onClick = { onUpdate(config.copy(shutterStyle = style)) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSel) Color(0xFF1E283F) else Color(0xFF181C26),
                    border = if (isSel) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF3B82F6)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSel, onClick = { onUpdate(config.copy(shutterStyle = style)) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(style.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(style.description, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Shutter Size", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Text("${config.shutterSizeDp} dp", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.shutterSizeDp.toFloat(),
                onValueChange = { onUpdate(config.copy(shutterSizeDp = it.toInt())) },
                valueRange = 60f..100f,
                steps = 7
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Shutter Horizontal Offset", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Text("${config.shutterHorizontalOffsetDp} dp", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.shutterHorizontalOffsetDp.toFloat(),
                onValueChange = { onUpdate(config.copy(shutterHorizontalOffsetDp = it.toInt())) },
                valueRange = -50f..50f,
                steps = 9
            )
        }

        item {
            Text("Action Buttons Visibility & Sizing", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Show Gallery Thumbnail", color = Color.White, fontSize = 13.sp)
                Switch(checked = config.showGalleryButton, onCheckedChange = { onUpdate(config.copy(showGalleryButton = it)) })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Show Flip Camera Button", color = Color.White, fontSize = 13.sp)
                Switch(checked = config.showFlipButton, onCheckedChange = { onUpdate(config.copy(showFlipButton = it)) })
            }
        }

        item {
            Text("Accent Color Palette", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            val colors = listOf("#FFD54F", "#FFFFFF", "#FF7043", "#64B5F6", "#81C784", "#FF4081")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                colors.forEach { hex ->
                    val c = Color(android.graphics.Color.parseColor(hex))
                    val isSel = config.accentColorHex.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(if (isSel) 3.dp else 1.dp, if (isSel) Color(0xFF3B82F6) else Color.Transparent, CircleShape)
                            .clickable { onUpdate(config.copy(accentColorHex = hex)) }
                    )
                }
            }
        }
    }
}

@Composable
fun TopBarControlsEditor(
    config: ModeLayoutConfig,
    onUpdate: (ModeLayoutConfig) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Top Controls Alignment", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TopBarAlignment.entries.forEach { align ->
                    val isSel = config.topBarAlignment == align
                    FilterChip(
                        selected = isSel,
                        onClick = { onUpdate(config.copy(topBarAlignment = align)) },
                        label = { Text(align.label, fontSize = 11.sp) }
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Icon Size", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Text("${config.topControlsIconSizeDp} dp", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.topControlsIconSizeDp.toFloat(),
                onValueChange = { onUpdate(config.copy(topControlsIconSizeDp = it.toInt())) },
                valueRange = 14f..32f,
                steps = 8
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Icon Spacing", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Text("${config.topControlsSpacingDp} dp", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.topControlsSpacingDp.toFloat(),
                onValueChange = { onUpdate(config.copy(topControlsSpacingDp = it.toInt())) },
                valueRange = 8f..32f,
                steps = 5
            )
        }

        item {
            Text("Customize Top Bar Items (Toggle & Order)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Text("Hide or show individual toggles and rearrange their sequence.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }

        items(config.topControlsOrder) { item ->
            val isHidden = config.hiddenTopControls.contains(item)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF191E2C),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = !isHidden,
                            onCheckedChange = { visible ->
                                val set = config.hiddenTopControls.toMutableSet()
                                if (visible) set.remove(item) else set.add(item)
                                onUpdate(config.copy(hiddenTopControls = set))
                            }
                        )
                        Text(item.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }

                    Row {
                        val curIdx = config.topControlsOrder.indexOf(item)
                        IconButton(
                            enabled = curIdx > 0,
                            onClick = {
                                val list = config.topControlsOrder.toMutableList()
                                val temp = list[curIdx - 1]
                                list[curIdx - 1] = item
                                list[curIdx] = temp
                                onUpdate(config.copy(topControlsOrder = list))
                            }
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move left", tint = if (curIdx > 0) Color.White else Color.Gray, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            enabled = curIdx < config.topControlsOrder.size - 1,
                            onClick = {
                                val list = config.topControlsOrder.toMutableList()
                                val temp = list[curIdx + 1]
                                list[curIdx + 1] = item
                                list[curIdx] = temp
                                onUpdate(config.copy(topControlsOrder = list))
                            }
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move right", tint = if (curIdx < config.topControlsOrder.size - 1) Color.White else Color.Gray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ZoomAndViewfinderEditor(
    config: ModeLayoutConfig,
    onUpdate: (ModeLayoutConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Show Master Zoom Capsule", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Switch(checked = config.showZoomCapsule, onCheckedChange = { onUpdate(config.copy(showZoomCapsule = it)) })
        }

        if (config.showZoomCapsule) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zoom Capsule Scale", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Text(String.format("%.1fx", config.zoomCapsuleScale), color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.zoomCapsuleScale,
                onValueChange = { onUpdate(config.copy(zoomCapsuleScale = it)) },
                valueRange = 0.8f..1.3f,
                steps = 5
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zoom Vertical Offset", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Text("${config.zoomCapsuleVerticalOffsetDp} dp", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.zoomCapsuleVerticalOffsetDp.toFloat(),
                onValueChange = { onUpdate(config.copy(zoomCapsuleVerticalOffsetDp = it.toInt())) },
                valueRange = -40f..40f,
                steps = 8
            )
        }
    }
}

@Composable
fun ResetAndRestoreEditor(
    targetMode: CameraMode?,
    onResetMode: () -> Unit,
    onResetAllToTemplate: (UiTemplateType) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (targetMode != null) {
            Text(
                text = "Reset Current Mode (${targetMode.name})",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 15.sp
            )
            Text(
                text = "Remove mode-specific custom layout overrides and revert to the Global configuration.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
            Button(
                onClick = onResetMode,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset ${targetMode.name} to Global Layout")
            }
            HorizontalDivider(color = Color(0x33FFFFFF), modifier = Modifier.padding(vertical = 8.dp))
        }

        Text(
            text = "Restore Ready-Made Template",
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 15.sp
        )
        Text(
            text = "Reset all camera modes to one of the authentic device templates.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { onResetAllToTemplate(UiTemplateType.IPHONE) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222838)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("iPhone Style", fontSize = 12.sp, color = Color.White)
            }
            Button(
                onClick = { onResetAllToTemplate(UiTemplateType.SAMSUNG) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222838)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Samsung Style", fontSize = 12.sp, color = Color.White)
            }
        }

        Button(
            onClick = { onResetAllToTemplate(UiTemplateType.VIVO) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222838)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Vivo Style", fontSize = 12.sp, color = Color.White)
        }
    }
}
