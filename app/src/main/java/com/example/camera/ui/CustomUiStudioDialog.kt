package com.example.camera.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.camera.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom UI Studio Screen & Interactive Simulated Device.
 *
 * Provides:
 * 1. Mini Simulated Device on top: Realistic mobile device frame with live camera HUD,
 *    live preview, reticle, top controls, mode selector, and shutter button reflecting
 *    every tweak in real time.
 * 2. Complete Customization controls below:
 *    - Every Text Style: Font Family, Font Weight, Font Size, Text Casing, Letter Spacing,
 *      Text Color, Shadow / Glow / Outline effect.
 *    - Every Icon Style: Icon Design Pack (Rounded, Hairline, Sharp, Bold, Cyber Neon,
 *      Glassmorphism, Neomorphic, Retro), Container Shape (None, Circle, Squircle, Hexagon, Pill),
 *      Container Opacity, Icon Color & Tint, Stroke Weight.
 *    - Shutter Button & Controls: 9 distinctive Shutter styles, size, position, top bar alignment.
 * 3. Upload Photo of Any UI: Users can pick a photo of any UI from gallery. The app automatically
 *    extracts dominant accent colors, text tone, and palette, auto-configures a matching UI theme,
 *    and optionally renders the photo on the simulated device viewfinder for live visual comparison!
 * 4. Presets & Direct Apply to Camera: Saves custom presets and applies directly to the active camera.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomUiStudioDialog(
    initialConfig: ModeLayoutConfig,
    uiCustomizationState: UiCustomizationState,
    currentCameraMode: CameraMode,
    onDismiss: () -> Unit,
    onApplyToCamera: (ModeLayoutConfig) -> Unit,
    onSaveCustomPreset: (String, ModeLayoutConfig) -> Unit,
    onDeleteCustomPreset: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var config by remember { mutableStateOf(initialConfig) }
    var activeStudioTab by remember { mutableIntStateOf(0) }
    var simulatedSelectedMode by remember { mutableStateOf(currentCameraMode) }
    var simulatedShutterTriggered by remember { mutableStateOf(false) }

    // Uploaded UI Photo state
    var uploadedPhotoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var uploadedPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var extractedPalette by remember { mutableStateOf<ExtractedUiPalette?>(null) }
    var showPhotoAsSimulatedWallpaper by remember { mutableStateOf(true) }
    var photoWallpaperOpacity by remember { mutableFloatStateOf(0.45f) }
    var isExtractingColors by remember { mutableStateOf(false) }

    // Save Preset Dialog State
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var customHexInput by remember { mutableStateOf("") }

    // Photo picker launcher (Zero-permission Android Photo Picker)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            uploadedPhotoUri = uri
            isExtractingColors = true
            coroutineScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val input: InputStream? = context.contentResolver.openInputStream(uri)
                        val full = BitmapFactory.decodeStream(input)
                        input?.close()
                        // Downsample for fast UI display & palette analysis
                        if (full != null) {
                            val maxDim = 800
                            val scale = min(1f, maxDim.toFloat() / max(full.width, full.height))
                            if (scale < 1f) {
                                Bitmap.createScaledBitmap(
                                    full,
                                    (full.width * scale).roundToInt(),
                                    (full.height * scale).roundToInt(),
                                    true
                                )
                            } else {
                                full
                            }
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }

                uploadedPhotoBitmap = bitmap
                if (bitmap != null) {
                    val palette = withContext(Dispatchers.Default) {
                        extractPaletteFromBitmap(bitmap)
                    }
                    extractedPalette = palette
                    // Auto update photo URI in config
                    config = config.copy(customUiPhotoUri = uri.toString())
                }
                isExtractingColors = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0F14)),
            color = Color(0xFF0D0F14)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Top Header Bar
                StudioTopHeader(
                    accentColor = config.getComposeAccentColor(),
                    onBackClick = onDismiss,
                    onSavePresetClick = {
                        presetNameInput = "My Custom UI ${System.currentTimeMillis() % 1000}"
                        showSavePresetDialog = true
                    },
                    onApplyClick = {
                        onApplyToCamera(config)
                        onDismiss()
                    }
                )

                // Scrollable Container: Top has Simulated Device, Bottom has Customization Tabs & Controls
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(6.dp))

                    // 1. MINI SIMULATED DEVICE (Phone Mockup)
                    SimulatedPhoneDevice(
                        config = config,
                        selectedMode = simulatedSelectedMode,
                        onModeSelected = { simulatedSelectedMode = it },
                        onShutterClick = {
                            simulatedShutterTriggered = true
                        },
                        shutterTriggered = simulatedShutterTriggered,
                        onShutterAnimationEnd = { simulatedShutterTriggered = false },
                        uploadedPhotoBitmap = uploadedPhotoBitmap,
                        showPhotoWallpaper = showPhotoAsSimulatedWallpaper,
                        photoWallpaperOpacity = photoWallpaperOpacity
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. STUDIO NAVIGATION TABS
                    StudioCategoryTabs(
                        selectedTab = activeStudioTab,
                        onTabSelected = { activeStudioTab = it },
                        accentColor = config.getComposeAccentColor()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. TAB CONTENT
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        when (activeStudioTab) {
                            0 -> UploadUiPhotoTabContent(
                                uploadedBitmap = uploadedPhotoBitmap,
                                extractedPalette = extractedPalette,
                                isExtracting = isExtractingColors,
                                showAsWallpaper = showPhotoAsSimulatedWallpaper,
                                wallpaperOpacity = photoWallpaperOpacity,
                                onPickPhotoClick = {
                                    photoPickerLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                onToggleWallpaper = { showPhotoAsSimulatedWallpaper = it },
                                onOpacityChange = { photoWallpaperOpacity = it },
                                onApplyExtractedTheme = { palette ->
                                    config = config.copy(
                                        accentColorHex = palette.vibrantAccentHex,
                                        textColorHex = palette.textToneHex,
                                        iconColorHex = palette.iconTintHex,
                                        shutterStyle = ShutterStyle.CLASSIC_WHITE
                                    )
                                },
                                onClearPhoto = {
                                    uploadedPhotoBitmap = null
                                    uploadedPhotoUri = null
                                    extractedPalette = null
                                    config = config.copy(customUiPhotoUri = null)
                                }
                            )

                            1 -> TextStylesTabContent(
                                config = config,
                                onConfigChange = { config = it }
                            )

                            2 -> IconStylesTabContent(
                                config = config,
                                onConfigChange = { config = it }
                            )

                            3 -> ShutterControlsTabContent(
                                config = config,
                                onConfigChange = { config = it }
                            )

                            4 -> ColorPaletteTabContent(
                                config = config,
                                customHexInput = customHexInput,
                                onCustomHexInputChange = { customHexInput = it },
                                onConfigChange = { config = it }
                            )

                            5 -> SavedPresetsTabContent(
                                currentConfig = config,
                                presets = uiCustomizationState.customPresets,
                                onLoadPreset = { preset -> config = preset.config },
                                onDeletePreset = onDeleteCustomPreset,
                                onSavePresetPrompt = {
                                    presetNameInput = "Custom UI ${System.currentTimeMillis() % 1000}"
                                    showSavePresetDialog = true
                                },
                                onResetToTemplate = { template ->
                                    config = CameraUiTemplates.getTemplateConfig(template)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
    }

    // Save Preset Dialog
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = {
                Text("Save Custom UI Preset", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("Enter a unique name to save your custom UI layout, fonts, and icon styles:", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        singleLine = true,
                        label = { Text("Preset Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetNameInput.isNotBlank()) {
                            onSaveCustomPreset(presetNameInput.trim(), config)
                            showSavePresetDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = config.getComposeAccentColor())
                ) {
                    Text("Save Preset", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Top App Bar for Custom UI Studio.
 */
@Composable
private fun StudioTopHeader(
    accentColor: Color,
    onBackClick: () -> Unit,
    onSavePresetClick: () -> Unit,
    onApplyClick: () -> Unit
) {
    Surface(
        color = Color(0xFF141720),
        border = BorderStroke(1.dp, Color(0xFF232836)),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.testTag("studio_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Custom UI Studio",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = accentColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "LIVE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = "Simulated Device · Text & Icon Styles · Photo Themer",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onSavePresetClick,
                modifier = Modifier.testTag("studio_save_preset_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkAdd,
                    contentDescription = "Save Preset",
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Button(
                onClick = onApplyClick,
                modifier = Modifier
                    .height(38.dp)
                    .testTag("studio_apply_button"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Apply",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
    }
}

/**
 * Realistic Simulated Mobile Phone Device with live viewfinder and interactive controls.
 */
@Composable
private fun SimulatedPhoneDevice(
    config: ModeLayoutConfig,
    selectedMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    onShutterClick: () -> Unit,
    shutterTriggered: Boolean,
    onShutterAnimationEnd: () -> Unit,
    uploadedPhotoBitmap: Bitmap?,
    showPhotoWallpaper: Boolean,
    photoWallpaperOpacity: Float
) {
    val accentColor = config.getComposeAccentColor()
    val textColor = config.getComposeTextColor()
    val iconColor = config.getComposeIconColor()
    val fontFamily = config.modeFontFamily.toComposeFontFamily()

    // Shutter flash effect
    val flashAlpha by animateFloatAsState(
        targetValue = if (shutterTriggered) 0.85f else 0f,
        animationSpec = tween(durationMillis = if (shutterTriggered) 80 else 240),
        finishedListener = { if (shutterTriggered) onShutterAnimationEnd() }
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        // Device Label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Smartphone,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Interactive Simulated Device Preview",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        // Phone Chassis Frame
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(440.dp)
                .shadow(16.dp, RoundedCornerShape(32.dp), spotColor = accentColor.copy(alpha = 0.35f))
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFF1E222D))
                .border(
                    BorderStroke(
                        4.5.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF4B5563),
                                Color(0xFF1F2430),
                                Color(0xFF374151)
                            )
                        )
                    ),
                    RoundedCornerShape(32.dp)
                )
                .testTag("simulated_phone_chassis"),
            contentAlignment = Alignment.TopCenter
        ) {
            // Inside Screen Display
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(RoundedCornerShape(27.dp))
                    .background(Color(0xFF08090C))
            ) {
                // Layer 1: Simulated Camera Viewfinder Scene or Uploaded Photo
                if (uploadedPhotoBitmap != null && showPhotoWallpaper) {
                    Image(
                        bitmap = uploadedPhotoBitmap.asImageBitmap(),
                        contentDescription = "Uploaded UI Wallpaper",
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(photoWallpaperOpacity),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    // Default artistic camera scene background
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFF1A2234), Color(0xFF090B10)),
                                center = Offset(size.width * 0.5f, size.height * 0.4f),
                                radius = size.width * 0.8f
                            )
                        )
                        // Soft horizon line & rule of thirds cues
                        val wThird = size.width / 3f
                        val hThird = size.height / 3f
                        drawLine(
                            color = Color(0x1AFFFFFF),
                            start = Offset(wThird, 0f),
                            end = Offset(wThird, size.height),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = Color(0x1AFFFFFF),
                            start = Offset(wThird * 2f, 0f),
                            end = Offset(wThird * 2f, size.height),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = Color(0x1AFFFFFF),
                            start = Offset(0f, hThird),
                            end = Offset(size.width, hThird),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = Color(0x1AFFFFFF),
                            start = Offset(0f, hThird * 2f),
                            end = Offset(size.width, hThird * 2f),
                            strokeWidth = 1f
                        )
                    }
                }

                // Layer 2: Viewfinder Center Focus Reticle & Telemetry
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 40.dp, bottom = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SimulatedViewfinderReticle(
                        accentColor = accentColor,
                        textColor = textColor,
                        fontFamily = fontFamily,
                        letterSpacingSp = config.letterSpacingSp
                    )
                }

                // Layer 3: Simulated Live Controls (Top Bar, Zoom, Modes, Shutter)
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 3a. Simulated Top Bar
                    SimulatedTopBar(
                        config = config,
                        accentColor = accentColor,
                        iconColor = iconColor
                    )

                    // 3b. Simulated Bottom Dock (Zoom Capsule, Mode Selector, Shutter)
                    SimulatedBottomDock(
                        config = config,
                        selectedMode = selectedMode,
                        onModeSelected = onModeSelected,
                        accentColor = accentColor,
                        textColor = textColor,
                        iconColor = iconColor,
                        fontFamily = fontFamily,
                        onShutterClick = onShutterClick
                    )
                }

                // Layer 4: Shutter Flash Animation Overlay
                if (flashAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = flashAlpha))
                    )
                }

                // Layer 5: Device Hardware Punch-Hole Camera Cutout & Status Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, start = 14.dp, end = 14.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    // Time
                    Text(
                        text = "12:00",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.CenterStart)
                    )

                    // Punch-Hole Pill
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(9.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF000000))
                    )

                    // Battery & 5G
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "5G",
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Icon(
                            imageVector = Icons.Outlined.BatteryFull,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(9.dp)
                        )
                    }
                }

                // Layer 6: Bottom Home Indicator Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(2.5.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

/**
 * Center Focus Reticle in Simulated Device.
 */
@Composable
private fun SimulatedViewfinderReticle(
    accentColor: Color,
    textColor: Color,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    letterSpacingSp: Float
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Brackets
        Box(
            modifier = Modifier
                .size(46.dp)
                .border(BorderStroke(1.2.dp, accentColor.copy(alpha = 0.8f)), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        // Telemetry tag
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color(0x66000000),
            border = BorderStroke(0.5.dp, Color(0x33FFFFFF))
        ) {
            Text(
                text = "ISO 100 · 1/250s · f/1.8",
                fontSize = 7.5.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                letterSpacing = (letterSpacingSp * 0.6f).sp,
                color = textColor,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * Simulated Top Control Bar.
 */
@Composable
private fun SimulatedTopBar(
    config: ModeLayoutConfig,
    accentColor: Color,
    iconColor: Color
) {
    val iconSize = (config.topControlsIconSizeDp * 0.52f).dp.coerceAtLeast(11.dp)
    val buttonSize = (config.topControlsIconSizeDp * 0.85f).dp.coerceAtLeast(18.dp)

    val shape = when (config.iconShapeOption) {
        IconShapeOption.TRANSPARENT_NONE -> RoundedCornerShape(0.dp)
        IconShapeOption.CIRCLE_GLASS -> CircleShape
        IconShapeOption.ROUNDED_SQUARE -> RoundedCornerShape(4.dp)
        IconShapeOption.HEXAGON -> RoundedCornerShape(3.dp)
        IconShapeOption.PILL -> RoundedCornerShape(8.dp)
    }

    val containerBg = if (config.iconShapeOption == IconShapeOption.TRANSPARENT_NONE) {
        Color.Transparent
    } else {
        Color(0xFF16181F).copy(alpha = config.iconContainerOpacity.coerceIn(0.1f, 1f))
    }

    val containerBorder = if (config.iconShapeOption == IconShapeOption.TRANSPARENT_NONE) {
        null
    } else {
        BorderStroke(0.7.dp, Color.White.copy(alpha = 0.2f))
    }

    val arrangement = when (config.topBarAlignment) {
        TopBarAlignment.SPACE_BETWEEN -> Arrangement.SpaceBetween
        TopBarAlignment.CENTER -> Arrangement.Center
        TopBarAlignment.COMPACT_LEFT -> Arrangement.Start
        TopBarAlignment.COMPACT_RIGHT -> Arrangement.End
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val sampleIcons = listOf(
            Icons.Outlined.FlashAuto to "Flash",
            Icons.Outlined.Timer to "Timer",
            Icons.Outlined.GridOn to "Grid",
            Icons.Outlined.Hd to "Resolution",
            Icons.Outlined.Settings to "Settings"
        )

        sampleIcons.forEach { (vector, _) ->
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .clip(shape)
                    .background(containerBg)
                    .then(if (containerBorder != null) Modifier.border(containerBorder, shape) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    tint = if (vector == Icons.Outlined.FlashAuto) accentColor else iconColor,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

/**
 * Simulated Bottom Dock (Zoom Capsule, Mode Selector, Shutter & Auxiliary buttons).
 */
@Composable
private fun SimulatedBottomDock(
    config: ModeLayoutConfig,
    selectedMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    accentColor: Color,
    textColor: Color,
    iconColor: Color,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    onShutterClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Zoom Capsule
        if (config.showZoomCapsule) {
            Surface(
                shape = CircleShape,
                color = Color(0x66000000),
                border = BorderStroke(0.6.dp, Color(0x33FFFFFF)),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("0.5", "1x", "2x", "5x").forEach { z ->
                        val is1x = z == "1x"
                        Text(
                            text = z,
                            fontSize = 7.5.sp,
                            fontWeight = if (is1x) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (is1x) accentColor else Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // Mode Selector (Above Shutter position)
        if (config.modeSelectorPosition == ModeSelectorPosition.ABOVE_SHUTTER) {
            SimulatedModeSelectorBar(
                config = config,
                selectedMode = selectedMode,
                onModeSelected = onModeSelected,
                accentColor = accentColor,
                textColor = textColor,
                fontFamily = fontFamily
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Shutter & Side Buttons Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Gallery Thumbnail
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(
                        when (config.iconShapeOption) {
                            IconShapeOption.ROUNDED_SQUARE -> RoundedCornerShape(4.dp)
                            else -> CircleShape
                        }
                    )
                    .background(Color(0xFF282C37))
                    .border(0.8.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(13.dp)
                )
            }

            // Shutter Button
            SimulatedShutterButton(
                style = config.shutterStyle,
                accentColor = accentColor,
                sizeDp = (config.shutterSizeDp * 0.48f).coerceIn(34f, 48f),
                onClick = onShutterClick
            )

            // Camera Flip Button
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(
                        when (config.iconShapeOption) {
                            IconShapeOption.ROUNDED_SQUARE -> RoundedCornerShape(4.dp)
                            else -> CircleShape
                        }
                    )
                    .background(Color(0xFF282C37))
                    .border(0.8.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Cameraswitch,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // Mode Selector (Below Shutter position)
        if (config.modeSelectorPosition == ModeSelectorPosition.BELOW_SHUTTER) {
            Spacer(modifier = Modifier.height(6.dp))
            SimulatedModeSelectorBar(
                config = config,
                selectedMode = selectedMode,
                onModeSelected = onModeSelected,
                accentColor = accentColor,
                textColor = textColor,
                fontFamily = fontFamily
            )
        }
    }
}

/**
 * Mode Selector Row inside Simulated Device.
 */
@Composable
private fun SimulatedModeSelectorBar(
    config: ModeLayoutConfig,
    selectedMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    accentColor: Color,
    textColor: Color,
    fontFamily: androidx.compose.ui.text.font.FontFamily
) {
    val modes = listOf(
        CameraMode.PORTRAIT,
        CameraMode.PHOTO,
        CameraMode.VIDEO,
        CameraMode.NIGHT
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { mode ->
            val isSelected = mode == selectedMode
            val labelText = config.formatModeText(mode.title)
            val scaledTextSize = (config.modeTextSizeSp * 0.58f).sp

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 3.dp, vertical = 2.dp)
            ) {
                when (config.modeSelectorStyle) {
                    ModeSelectorStyle.CAPSULE_PILL -> {
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) accentColor else Color.Transparent
                        ) {
                            Text(
                                text = labelText,
                                fontSize = scaledTextSize,
                                fontWeight = if (isSelected) config.fontWeightOption.weight else FontWeight.Normal,
                                fontFamily = fontFamily,
                                letterSpacing = (config.letterSpacingSp * 0.5f).sp,
                                color = if (isSelected) Color.Black else textColor.copy(alpha = 0.65f),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    ModeSelectorStyle.UNDERLINE -> {
                        Text(
                            text = labelText,
                            fontSize = scaledTextSize,
                            fontWeight = if (isSelected) config.fontWeightOption.weight else FontWeight.Normal,
                            fontFamily = fontFamily,
                            letterSpacing = (config.letterSpacingSp * 0.5f).sp,
                            color = if (isSelected) accentColor else textColor.copy(alpha = 0.65f)
                        )
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 1.dp)
                                    .width(12.dp)
                                    .height(1.5.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                    }

                    ModeSelectorStyle.CYBER_GLOW -> {
                        Text(
                            text = labelText,
                            fontSize = scaledTextSize,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                            fontFamily = fontFamily,
                            letterSpacing = (config.letterSpacingSp * 0.5f).sp,
                            color = if (isSelected) accentColor else textColor.copy(alpha = 0.55f)
                        )
                    }

                    else -> { // CLASSIC_DOT or default
                        Text(
                            text = labelText,
                            fontSize = scaledTextSize,
                            fontWeight = if (isSelected) config.fontWeightOption.weight else FontWeight.Normal,
                            fontFamily = fontFamily,
                            letterSpacing = (config.letterSpacingSp * 0.5f).sp,
                            color = if (isSelected) textColor else textColor.copy(alpha = 0.65f)
                        )
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 1.5.dp)
                                    .size(3.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shutter Button inside Simulated Device.
 */
@Composable
private fun SimulatedShutterButton(
    style: ShutterStyle,
    accentColor: Color,
    sizeDp: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when (style) {
            ShutterStyle.APPLE_DOT -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.8.dp, Color.White, CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }

            ShutterStyle.LEICA_RED_DOT -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.8.dp, Color(0xFFD1D5DB), CircleShape)
                        .padding(2.5.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                )
            }

            ShutterStyle.CYBER_HOLO -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.5.dp, accentColor, CircleShape)
                        .padding(2.5.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.25f))
                        .border(1.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size((sizeDp * 0.45f).dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }
            }

            ShutterStyle.VIVO_GIMBAL -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.8.dp, accentColor, CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }

            ShutterStyle.SAMSUNG_CAPSULE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(2.5.dp, Color(0xFFD1D5DB), CircleShape)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }

            else -> { // CLASSIC_WHITE or PIXEL_SOLID
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(2.dp, Color.White, CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

/**
 * Studio Category Tabs below Simulated Device.
 */
@Composable
private fun StudioCategoryTabs(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    accentColor: Color
) {
    val tabTitles = listOf(
        "📷 Photo Match",
        "✍️ Text Styles",
        "🎨 Icon Styles",
        "🔘 Shutter & Layout",
        "🌈 Colors",
        "💾 Presets"
    )

    ScrollableTabRow(
        selectedTabIndex = selectedTab,
        containerColor = Color(0xFF141720),
        contentColor = Color.White,
        edgePadding = 12.dp,
        divider = { HorizontalDivider(color = Color(0xFF232836)) }
    ) {
        tabTitles.forEachIndexed { index, title ->
            val isSelected = selectedTab == index
            Tab(
                selected = isSelected,
                onClick = { onTabSelected(index) },
                text = {
                    Text(
                        text = title,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        color = if (isSelected) accentColor else Color.White.copy(alpha = 0.7f)
                    )
                }
            )
        }
    }
}

/**
 * Tab 1: Upload Photo of Any UI & Auto Match Theme.
 */
@Composable
private fun UploadUiPhotoTabContent(
    uploadedBitmap: Bitmap?,
    extractedPalette: ExtractedUiPalette?,
    isExtracting: Boolean,
    showAsWallpaper: Boolean,
    wallpaperOpacity: Float,
    onPickPhotoClick: () -> Unit,
    onToggleWallpaper: (Boolean) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onApplyExtractedTheme: (ExtractedUiPalette) -> Unit,
    onClearPhoto: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StudioSectionCard(
            title = "Upload Photo of Any Camera UI",
            subtitle = "Upload a screenshot of any camera UI (iPhone, Leica, Cyberpunk, DSLR, Samsung, etc.). The studio extracts the colors, fonts, and icon styles so you can use it immediately!"
        ) {
            Button(
                onClick = onPickPhotoClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("upload_ui_photo_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uploadedBitmap != null) "Choose Another UI Photo" else "Upload UI Photo from Gallery",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            if (isExtracting) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color(0xFF2563EB),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Extracting UI colors & typography palette...", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.8f))
                }
            }

            if (uploadedBitmap != null && extractedPalette != null) {
                Spacer(modifier = Modifier.height(16.dp))

                // Uploaded Image Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E222D),
                    border = BorderStroke(1.dp, Color(0xFF323846))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = uploadedBitmap.asImageBitmap(),
                            contentDescription = "Uploaded Preview",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Uploaded UI Photo",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = "${uploadedBitmap.width}x${uploadedBitmap.height}px · Color extraction complete",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = onClearPhoto) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Clear",
                                tint = Color(0xFFEF5350)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Extracted Palette Preview
                Text(
                    text = "Extracted Color Palette from UI Photo:",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PaletteSwatch(
                        title = "Accent",
                        hex = extractedPalette.vibrantAccentHex,
                        modifier = Modifier.weight(1f)
                    )
                    PaletteSwatch(
                        title = "Text",
                        hex = extractedPalette.textToneHex,
                        modifier = Modifier.weight(1f)
                    )
                    PaletteSwatch(
                        title = "Icon Tint",
                        hex = extractedPalette.iconTintHex,
                        modifier = Modifier.weight(1f)
                    )
                    PaletteSwatch(
                        title = "Background",
                        hex = extractedPalette.darkBgHex,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // One-tap apply button
                Button(
                    onClick = { onApplyExtractedTheme(extractedPalette) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = try {
                            Color(android.graphics.Color.parseColor(extractedPalette.vibrantAccentHex))
                        } catch (e: Exception) { Color(0xFF64B5F6) }
                    )
                ) {
                    Icon(imageVector = Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Auto-Apply Theme to Camera UI", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Simulated Wallpaper Options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Show on Simulated Viewfinder",
                        fontSize = 13.sp,
                        color = Color.White
                    )
                    Switch(
                        checked = showAsWallpaper,
                        onCheckedChange = onToggleWallpaper,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White)
                    )
                }

                if (showAsWallpaper) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Overlay Opacity: ${(wallpaperOpacity * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Slider(
                        value = wallpaperOpacity,
                        onValueChange = onOpacityChange,
                        valueRange = 0.1f..1.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Tab 2: Customize Every Text Style.
 */
@Composable
private fun TextStylesTabContent(
    config: ModeLayoutConfig,
    onConfigChange: (ModeLayoutConfig) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. Font Family
        StudioSectionCard(
            title = "1. Font Family",
            subtitle = "Select typography design language for all camera mode labels & telemetry"
        ) {
            val families = FontFamilyOption.entries
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(families) { font ->
                    val isSel = config.modeFontFamily == font
                    FilterChip(
                        selected = isSel,
                        onClick = { onConfigChange(config.copy(modeFontFamily = font)) },
                        label = { Text(font.label, fontFamily = font.toComposeFontFamily()) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Font Weight
        StudioSectionCard(
            title = "2. Font Weight",
            subtitle = "Adjust thickness from Light to ExtraBold"
        ) {
            val weights = FontWeightOption.entries
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(weights) { w ->
                    val isSel = config.fontWeightOption == w
                    FilterChip(
                        selected = isSel,
                        onClick = { onConfigChange(config.copy(fontWeightOption = w)) },
                        label = { Text(w.label, fontWeight = w.weight) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Text Size
        StudioSectionCard(
            title = "3. Mode Text Size: ${String.format("%.1f", config.modeTextSizeSp)} sp",
            subtitle = "Scale mode indicator text size"
        ) {
            Slider(
                value = config.modeTextSizeSp,
                onValueChange = { onConfigChange(config.copy(modeTextSizeSp = it)) },
                valueRange = 10f..18f,
                steps = 7,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Text Casing
        StudioSectionCard(
            title = "4. Text Casing",
            subtitle = "Display mode labels in UPPERCASE, Title Case, or lowercase"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextCaseOption.entries.forEach { c ->
                    val isSel = config.textCaseOption == c
                    StudioSelectButton(
                        text = c.label,
                        isSelected = isSel,
                        onClick = { onConfigChange(config.copy(textCaseOption = c)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 5. Letter Spacing
        StudioSectionCard(
            title = "5. Letter Spacing: ${String.format("%.1f", config.letterSpacingSp)} sp",
            subtitle = "Adjust tracking between characters for editorial elegance or high readability"
        ) {
            Slider(
                value = config.letterSpacingSp,
                onValueChange = { onConfigChange(config.copy(letterSpacingSp = it)) },
                valueRange = -0.5f..3.5f,
                steps = 7,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 6. Text Color
        StudioSectionCard(
            title = "6. Text Color",
            subtitle = "Set primary text color for mode selector & camera metrics"
        ) {
            val textColors = listOf(
                "#FFFFFF" to "Pure White",
                "#F3F4F6" to "Soft Ivory",
                "#FFD54F" to "Golden Sun",
                "#00E5FF" to "Cyber Cyan",
                "#69F0AE" to "Mint Green",
                "#E53935" to "Leica Red",
                "#FFA726" to "Warm Coral"
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(textColors) { (hex, name) ->
                    val isSel = config.textColorHex.equals(hex, ignoreCase = true)
                    val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { Color.White }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onConfigChange(config.copy(textColorHex = hex)) }
                            .padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    BorderStroke(
                                        if (isSel) 2.5.dp else 1.dp,
                                        if (isSel) Color(0xFF2563EB) else Color(0x44FFFFFF)
                                    ),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(name, fontSize = 9.sp, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

/**
 * Tab 3: Customize Every Icon Style.
 */
@Composable
private fun IconStylesTabContent(
    config: ModeLayoutConfig,
    onConfigChange: (ModeLayoutConfig) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. Icon Design Language
        StudioSectionCard(
            title = "1. Icon Design Pack",
            subtitle = "Choose the overall visual aesthetic of top & bottom camera icons"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                IconStyleOption.entries.forEach { style ->
                    val isSel = config.iconStyleOption == style
                    Surface(
                        onClick = { onConfigChange(config.copy(iconStyleOption = style)) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSel) Color(0xFF1E2838) else Color(0xFF141720),
                        border = if (isSel) BorderStroke(1.5.dp, Color(0xFF2563EB)) else BorderStroke(1.dp, Color(0xFF242834)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(style.label, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.5.sp)
                                Text(style.description, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                            RadioButton(
                                selected = isSel,
                                onClick = { onConfigChange(config.copy(iconStyleOption = style)) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF2563EB))
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Icon Container Shape
        StudioSectionCard(
            title = "2. Icon Container Shape",
            subtitle = "Shape of the pod/capsule framing each icon"
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(IconShapeOption.entries) { shape ->
                    val isSel = config.iconShapeOption == shape
                    FilterChip(
                        selected = isSel,
                        onClick = { onConfigChange(config.copy(iconShapeOption = shape)) },
                        label = { Text(shape.label) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Icon Container Background Opacity
        StudioSectionCard(
            title = "3. Icon Pod Opacity: ${(config.iconContainerOpacity * 100).roundToInt()}%",
            subtitle = "Translucency of the background pod behind top & side buttons"
        ) {
            Slider(
                value = config.iconContainerOpacity,
                onValueChange = { onConfigChange(config.copy(iconContainerOpacity = it)) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Icon Size
        StudioSectionCard(
            title = "4. Icon Size: ${config.topControlsIconSizeDp} dp",
            subtitle = "Diameter of control glyphs"
        ) {
            Slider(
                value = config.topControlsIconSizeDp.toFloat(),
                onValueChange = { onConfigChange(config.copy(topControlsIconSizeDp = it.roundToInt())) },
                valueRange = 14f..32f,
                steps = 8,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 5. Icon Tint / Color
        StudioSectionCard(
            title = "5. Icon Color & Tint",
            subtitle = "Glyph stroke tone across controls"
        ) {
            val iconColors = listOf(
                "#FFFFFF" to "Clean White",
                config.accentColorHex to "Match Accent",
                "#00E5FF" to "Cyber Neon",
                "#FFD54F" to "Gold Amber",
                "#69F0AE" to "Mint",
                "#E53935" to "Leica Red"
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(iconColors) { (hex, name) ->
                    val isSel = config.iconColorHex.equals(hex, ignoreCase = true)
                    val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { Color.White }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onConfigChange(config.copy(iconColorHex = hex)) }
                            .padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    BorderStroke(
                                        if (isSel) 2.5.dp else 1.dp,
                                        if (isSel) Color(0xFF2563EB) else Color(0x44FFFFFF)
                                    ),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(name, fontSize = 9.sp, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

/**
 * Tab 4: Shutter & Viewfinder Controls.
 */
@Composable
private fun ShutterControlsTabContent(
    config: ModeLayoutConfig,
    onConfigChange: (ModeLayoutConfig) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. Shutter Style
        StudioSectionCard(
            title = "1. Shutter Button Style",
            subtitle = "Select one of 9 tactile shutter button triggers"
        ) {
            val styles = ShutterStyle.entries
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                styles.forEach { s ->
                    val isSel = config.shutterStyle == s
                    Surface(
                        onClick = { onConfigChange(config.copy(shutterStyle = s)) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSel) Color(0xFF1E2838) else Color(0xFF141720),
                        border = if (isSel) BorderStroke(1.5.dp, Color(0xFF2563EB)) else BorderStroke(1.dp, Color(0xFF242834)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.label, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.5.sp)
                                Text(s.description, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                            RadioButton(
                                selected = isSel,
                                onClick = { onConfigChange(config.copy(shutterStyle = s)) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF2563EB))
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Shutter Size
        StudioSectionCard(
            title = "2. Shutter Button Diameter: ${config.shutterSizeDp} dp",
            subtitle = "Scale size of tactile shutter trigger"
        ) {
            Slider(
                value = config.shutterSizeDp.toFloat(),
                onValueChange = { onConfigChange(config.copy(shutterSizeDp = it.roundToInt())) },
                valueRange = 64f..96f,
                steps = 7,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Mode Selector Position
        StudioSectionCard(
            title = "3. Mode Selector Position",
            subtitle = "Dock position relative to the main shutter button"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeSelectorPosition.entries.forEach { pos ->
                    val isSel = config.modeSelectorPosition == pos
                    StudioSelectButton(
                        text = pos.label,
                        isSelected = isSel,
                        onClick = { onConfigChange(config.copy(modeSelectorPosition = pos)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Mode Selector Indicator Style
        StudioSectionCard(
            title = "4. Mode Selection Indicator",
            subtitle = "Active mode indicator pill, dot, underline, or glow"
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ModeSelectorStyle.entries) { st ->
                    val isSel = config.modeSelectorStyle == st
                    FilterChip(
                        selected = isSel,
                        onClick = { onConfigChange(config.copy(modeSelectorStyle = st)) },
                        label = { Text(st.label) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 5. Top Bar Alignment
        StudioSectionCard(
            title = "5. Top Bar Alignment",
            subtitle = "Distribution of top icons across the status header"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TopBarAlignment.entries.forEach { align ->
                    val isSel = config.topBarAlignment == align
                    StudioSelectButton(
                        text = align.label,
                        isSelected = isSel,
                        onClick = { onConfigChange(config.copy(topBarAlignment = align)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Tab 5: Color Palette & Accent.
 */
@Composable
private fun ColorPaletteTabContent(
    config: ModeLayoutConfig,
    customHexInput: String,
    onCustomHexInputChange: (String) -> Unit,
    onConfigChange: (ModeLayoutConfig) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StudioSectionCard(
            title = "Camera UI Theme Accent Color",
            subtitle = "Sets the signature color for indicators, active buttons, focus brackets, and pills"
        ) {
            val palette = listOf(
                "#8AB4F8" to "Pixel Blue",
                "#E53935" to "Leica Crimson",
                "#00E5FF" to "Cyber Cyan",
                "#FFB300" to "Hasselblad Amber",
                "#FFD54F" to "Apple Yellow",
                "#FFFFFF" to "Samsung White",
                "#FF7043" to "Vivo Orange",
                "#69F0AE" to "Fuji Mint",
                "#B388FF" to "Sony Violet",
                "#FF1744" to "Canon Red"
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(palette) { (hex, name) ->
                    val isSel = config.accentColorHex.equals(hex, ignoreCase = true)
                    val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { Color(0xFFFFD54F) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onConfigChange(config.copy(accentColorHex = hex)) }
                            .padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    BorderStroke(
                                        if (isSel) 2.5.dp else 1.dp,
                                        if (isSel) Color.White else Color(0x44FFFFFF)
                                    ),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(name, fontSize = 9.sp, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Custom Hex Input
            Text("Enter Custom Hex Color Code:", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customHexInput,
                    onValueChange = onCustomHexInputChange,
                    singleLine = true,
                    placeholder = { Text("#FF5722") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val trimmed = customHexInput.trim()
                        val valid = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
                        try {
                            android.graphics.Color.parseColor(valid)
                            onConfigChange(config.copy(accentColorHex = valid))
                        } catch (e: Exception) {}
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Apply", color = Color.White)
                }
            }
        }
    }
}

/**
 * Tab 6: Saved Presets & Reset.
 */
@Composable
private fun SavedPresetsTabContent(
    currentConfig: ModeLayoutConfig,
    presets: List<CustomUiPreset>,
    onLoadPreset: (CustomUiPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onSavePresetPrompt: () -> Unit,
    onResetToTemplate: (UiTemplateType) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StudioSectionCard(
            title = "Save & Load Custom Presets",
            subtitle = "Store your unique UI designs or reset to factory defaults"
        ) {
            Button(
                onClick = onSavePresetPrompt,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Icon(imageVector = Icons.Outlined.Save, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Current Layout as Preset", color = Color.White, fontWeight = FontWeight.Bold)
            }

            if (presets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text("Saved Presets:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(6.dp))

                presets.forEach { preset ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1B202D),
                        border = BorderStroke(1.dp, Color(0xFF2D3344))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(preset.name, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.5.sp)
                                Text(
                                    "${preset.config.shutterStyle.label} · ${preset.config.modeFontFamily.label}",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onLoadPreset(preset) }) {
                                    Text("Load", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = { onDeletePreset(preset.id) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Delete",
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

        Spacer(modifier = Modifier.height(14.dp))

        // Reset to Factory Templates
        StudioSectionCard(
            title = "Reset to Base Template",
            subtitle = "Quickly re-align your customization with an official camera style"
        ) {
            val baseTemplates = listOf(
                UiTemplateType.STOCK_PIXEL,
                UiTemplateType.MINIMAL_PRO,
                UiTemplateType.FUTURISTIC_GLASS,
                UiTemplateType.DSLR_PRO,
                UiTemplateType.IPHONE,
                UiTemplateType.SAMSUNG,
                UiTemplateType.VIVO
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(baseTemplates) { t ->
                    Button(
                        onClick = { onResetToTemplate(t) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2433)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(t.title, fontSize = 11.5.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// Helper UI Components
// -----------------------------------------------------------------------------------------

@Composable
private fun StudioSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF151821),
        border = BorderStroke(1.dp, Color(0xFF222633))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color.White
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
private fun StudioSelectButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) Color(0xFF2563EB) else Color(0xFF1E2330),
        border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF2D3344)),
        modifier = modifier.height(38.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PaletteSwatch(
    title: String,
    hex: String,
    modifier: Modifier = Modifier
) {
    val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { Color.White }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text(hex, fontSize = 8.5.sp, color = Color.White.copy(alpha = 0.6f))
    }
}

/**
 * Extracted Palette model from an uploaded UI photo.
 */
data class ExtractedUiPalette(
    val vibrantAccentHex: String,
    val textToneHex: String,
    val iconTintHex: String,
    val darkBgHex: String
)

/**
 * Fast pixel-sampling color extraction algorithm to analyze uploaded UI screenshot.
 */
private fun extractPaletteFromBitmap(bitmap: Bitmap): ExtractedUiPalette {
    val step = max(1, min(bitmap.width, bitmap.height) / 40)
    var maxSat = -1f
    var bestVibrantColor = 0
    var totalLuminance = 0.0
    var count = 0

    val colorCounts = mutableMapOf<Int, Int>()

    for (x in 0 until bitmap.width step step) {
        for (y in 0 until bitmap.height step step) {
            val pixel = bitmap.getPixel(x, y)
            val a = (pixel shr 24) and 0xff
            if (a < 128) continue

            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff

            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val sat = hsv[1]
            val value = hsv[2]

            // Look for rich, vibrant saturated accents (like buttons, highlights)
            if (sat > maxSat && value in 0.35f..0.95f) {
                maxSat = sat
                bestVibrantColor = pixel
            }

            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            totalLuminance += lum
            count++

            // Bucket colors to 4-bit per channel to find dominant background
            val bucketKey = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            colorCounts[bucketKey] = (colorCounts[bucketKey] ?: 0) + 1
        }
    }

    val avgLum = if (count > 0) totalLuminance / count else 128.0
    val isOverallDark = avgLum < 128.0

    val accentHex = if (maxSat > 0.3f && bestVibrantColor != 0) {
        String.format("#%06X", 0xFFFFFF and bestVibrantColor)
    } else {
        if (isOverallDark) "#64B5F6" else "#2563EB"
    }

    val textHex = if (isOverallDark) "#FFFFFF" else "#1F2937"
    val iconHex = accentHex
    val bgHex = if (isOverallDark) "#12141A" else "#F9FAFB"

    return ExtractedUiPalette(
        vibrantAccentHex = accentHex,
        textToneHex = textHex,
        iconTintHex = iconHex,
        darkBgHex = bgHex
    )
}
