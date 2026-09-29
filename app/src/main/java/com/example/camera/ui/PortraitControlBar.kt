package com.example.camera.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.depth.DepthModelInstallState
import com.example.camera.depth.DepthModelManager
import com.example.camera.depth.DepthModelType
import com.example.camera.depth.PhotonVirtualApertureEngine
import com.example.camera.model.BokehStyle
import com.example.camera.model.PortraitConfig
import com.example.camera.model.PortraitProcessingState
import com.example.camera.ui.components.FrostedGlassBox
import java.util.Locale

/**
 * Liquid Glass Floating Portrait & Photon Virtual Aperture Settings Window.
 * Exclusively available in Portrait Mode:
 * - Physical Virtual Aperture f-stop selector & continuous f-stop slider (f/0.95 .. f/16)
 * - Depth-aware background blur intensity slider
 * - Bokeh character selector
 * - Real AI Depth Model status & live Virtual Aperture preview toggles (enabled only when a verified model is installed)
 */
@Composable
fun PortraitControlBar(
    config: PortraitConfig,
    processingState: PortraitProcessingState = PortraitProcessingState(),
    onBlurStrengthChanged: (Float) -> Unit,
    onApertureSelected: (String) -> Unit,
    onBokehStyleSelected: (BokehStyle) -> Unit,
    onToggleFaceEnhancement: () -> Unit = {},
    onToggleSkinTone: () -> Unit = {},
    onToggleOpticalBlurGuided: () -> Unit = {},
    onPortraitConfigChanged: ((PortraitConfig) -> Unit)? = null,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val modelManager = remember { DepthModelManager.getInstance(context) }
    val modelStatuses by modelManager.modelStatuses.collectAsStateWithLifecycle()
    val selectedModel by modelManager.selectedModel.collectAsStateWithLifecycle()

    val hasVerifiedAiModel = remember(modelStatuses) {
        modelStatuses.values.any { it.state is DepthModelInstallState.Installed }
    }
    val activeInstalledModel = remember(modelStatuses, selectedModel) {
        modelManager.getActiveInstalledModelFile()?.first
    }

    var showInlineModelSheet by remember { mutableStateOf(false) }

    val accentColor = Color(0xFFFFD54F) // Master camera gold accent
    val apertures = remember {
        PhotonVirtualApertureEngine.SUPPORTED_APERTURES.map { it.first }
    }
    val currentFNumber = remember(config.simulatedAperture) {
        PhotonVirtualApertureEngine.parseFNumber(config.simulatedAperture)
    }

    FrostedGlassBox(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 430.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("portrait_control_bar"),
        shape = RoundedCornerShape(26.dp),
        elevation = 20.dp,
        baseAlpha = 0.84f,
        baseTint = Color(0xFF0F121C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            // Header: Title, accent dot, AI model badge, aperture badge & circular close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PORTRAIT",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "VIRTUAL APERTURE",
                        color = accentColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Current aperture pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .border(1.dp, accentColor.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = config.simulatedAperture,
                            color = accentColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onClose() }
                            .testTag("close_portrait_settings"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close Portrait Settings",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // AI Depth Model Status Bar inside Portrait Mode
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (activeInstalledModel != null) Color(0x1F4CAF50) else Color.White.copy(alpha = 0.05f),
                border = BorderStroke(
                    1.dp,
                    if (activeInstalledModel != null) Color(0xFF66BB6A).copy(alpha = 0.45f)
                    else accentColor.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showInlineModelSheet = !showInlineModelSheet }
                    .testTag("portrait_ai_depth_model_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (activeInstalledModel != null) Icons.Outlined.Layers else Icons.Outlined.CloudDownload,
                            contentDescription = null,
                            tint = if (activeInstalledModel != null) Color(0xFF66BB6A) else accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = if (activeInstalledModel != null) {
                                    "AI Depth: ${activeInstalledModel.displayName}"
                                } else {
                                    "AI Depth Model Not Installed"
                                },
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (activeInstalledModel != null) {
                                    "Photon Virtual Aperture Active • Tap to switch/manage"
                                } else {
                                    "Tap here or in Settings → Depth Processing to download"
                                },
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 10.sp
                            )
                        }
                    }

                    Text(
                        text = if (showInlineModelSheet) "HIDE" else "MODELS",
                        color = accentColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // Expandable Inline AI Model Downloader / Switcher inside Portrait Mode
            AnimatedVisibility(visible = showInlineModelSheet) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DepthModelType.entries.forEach { modelType ->
                        val info = modelStatuses[modelType]
                        val state = info?.state ?: DepthModelInstallState.NotInstalled
                        val isInstalled = state is DepthModelInstallState.Installed
                        val isCurrent = (selectedModel == modelType && isInstalled)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF151926),
                            border = BorderStroke(
                                1.dp,
                                if (isCurrent) accentColor else Color.White.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = modelType.displayName,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val subText = when (state) {
                                            is DepthModelInstallState.Installed ->
                                                "Installed (${modelManager.formatBytes(state.fileSizeBytes)}) • ${state.verifiedFormat}"
                                            is DepthModelInstallState.Downloading ->
                                                "Downloading ${modelManager.formatBytes(state.downloadedBytes)}..."
                                            is DepthModelInstallState.Verifying ->
                                                "Verifying neural network tensors..."
                                            is DepthModelInstallState.Failed ->
                                                state.errorMessage
                                            DepthModelInstallState.NotInstalled ->
                                                info?.remoteSizeBytes?.let { "Size: ${modelManager.formatBytes(it)}" }
                                                    ?: "On-Demand Download"
                                        }
                                        Text(
                                            text = subText,
                                            color = if (state is DepthModelInstallState.Failed) Color(0xFFFF8A80)
                                            else Color.White.copy(alpha = 0.65f),
                                            fontSize = 10.5.sp
                                        )
                                    }

                                    when (state) {
                                        is DepthModelInstallState.Installed -> {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TextButton(
                                                    onClick = { modelManager.selectModel(modelType) },
                                                    enabled = !isCurrent,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = if (isCurrent) "ACTIVE" else "USE",
                                                        color = if (isCurrent) Color(0xFF66BB6A) else accentColor,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.ExtraBold
                                                    )
                                                }
                                                TextButton(
                                                    onClick = { modelManager.deleteModel(modelType) },
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "DELETE",
                                                        color = Color(0xFFFF5252),
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                        is DepthModelInstallState.Downloading -> {
                                            TextButton(
                                                onClick = { modelManager.cancelDownload(modelType) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "CANCEL",
                                                    color = Color(0xFFFF8A80),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        is DepthModelInstallState.Verifying -> {
                                            CircularProgressIndicator(
                                                color = accentColor,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        else -> {
                                            TextButton(
                                                onClick = { modelManager.startDownload(modelType) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (state is DepthModelInstallState.Failed && state.partialBytes > 0L) "RESUME" else "DOWNLOAD",
                                                    color = accentColor,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                    }
                                }

                                if (state is DepthModelInstallState.Downloading) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { state.progressFraction.coerceIn(0.01f, 0.99f) },
                                        color = accentColor,
                                        trackColor = Color.White.copy(alpha = 0.14f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val appearance = com.example.camera.ui.components.LocalFloatingWindowAppearance.current

            if (appearance.showPortraitApertureBlur) {
                Spacer(modifier = Modifier.height(14.dp))

                // Section 1: Virtual Aperture F-Stop Selector (f/0.95 .. f/16)
                PortraitSectionHeader(
                    title = "VIRTUAL APERTURE (DOF)",
                    badge = "${config.simulatedAperture} • ${
                        when {
                            currentFNumber <= 1.4f -> "Ultra Shallow"
                            currentFNumber <= 2.8f -> "Creamy Portrait"
                            currentFNumber <= 5.6f -> "Balanced DOF"
                            else -> "Deep Focus"
                        }
                    }"
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    apertures.forEach { aperture ->
                        val isSelected = config.simulatedAperture == aperture

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) {
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                accentColor.copy(alpha = 0.25f),
                                                accentColor.copy(alpha = 0.10f)
                                            )
                                        )
                                    } else {
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = 0.06f),
                                                Color.White.copy(alpha = 0.02f)
                                            )
                                        )
                                    }
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) accentColor else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onApertureSelected(aperture) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .testTag("aperture_chip_$aperture"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "f",
                                    color = if (isSelected) accentColor else Color.White.copy(alpha = 0.70f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontStyle = FontStyle.Italic,
                                    fontFamily = FontFamily.Serif
                                )
                                Text(
                                    text = aperture.removePrefix("f"),
                                    color = if (isSelected) accentColor else Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Continuous Virtual Aperture Slider (f/0.95 wide open to f/16 stopped down)
                val apertureIndex = remember(config.simulatedAperture) {
                    val idx = PhotonVirtualApertureEngine.SUPPORTED_APERTURES.indexOfFirst {
                        it.first == config.simulatedAperture
                    }
                    if (idx >= 0) idx.toFloat() else 2f
                }
                Slider(
                    value = apertureIndex,
                    onValueChange = { rawIdx ->
                        val nearestIdx = rawIdx.toInt().coerceIn(0, PhotonVirtualApertureEngine.SUPPORTED_APERTURES.lastIndex)
                        val selectedAp = PhotonVirtualApertureEngine.SUPPORTED_APERTURES[nearestIdx].first
                        if (selectedAp != config.simulatedAperture) {
                            onApertureSelected(selectedAp)
                        }
                    },
                    valueRange = 0f..(PhotonVirtualApertureEngine.SUPPORTED_APERTURES.lastIndex).toFloat(),
                    steps = PhotonVirtualApertureEngine.SUPPORTED_APERTURES.size - 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("virtual_aperture_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Section 2: Blur Intensity Slider
                PortraitSectionHeader(
                    title = "DEPTH BLUR INTENSITY",
                    badge = "${config.blurStrength.toInt()}%"
                )

                Spacer(modifier = Modifier.height(4.dp))

                Slider(
                    value = config.blurStrength,
                    onValueChange = onBlurStrengthChanged,
                    valueRange = 0f..100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("portrait_blur_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                    )
                )
            }

            if (appearance.showPortraitBokehStyle) {
                Spacer(modifier = Modifier.height(10.dp))

                // Section 3: Cinematic Bokeh Character
                PortraitSectionHeader(
                    title = "BOKEH CHARACTER",
                    badge = config.bokehStyle.label
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BokehStyle.entries.forEach { style ->
                        val isSelected = config.bokehStyle == style

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) {
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                accentColor.copy(alpha = 0.25f),
                                                accentColor.copy(alpha = 0.10f)
                                            )
                                        )
                                    } else {
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = 0.06f),
                                                Color.White.copy(alpha = 0.02f)
                                            )
                                        )
                                    }
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) accentColor else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onBokehStyleSelected(style) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .testTag("bokeh_style_${style.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = style.label,
                                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Section 4: Real-Time Viewfinder Virtual Aperture & Depth Map Preview (ONLY active when AI model is verified)
            if (hasVerifiedAiModel && onPortraitConfigChanged != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = config.liveAperturePreviewEnabled,
                        onClick = {
                            onPortraitConfigChanged(
                                config.copy(liveAperturePreviewEnabled = !config.liveAperturePreviewEnabled)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        label = {
                            Text(
                                text = if (config.liveAperturePreviewEnabled) "Live Bokeh Preview: ON" else "Live Bokeh Preview: OFF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor.copy(alpha = 0.22f),
                            selectedLabelColor = accentColor,
                            selectedLeadingIconColor = accentColor,
                            containerColor = Color.White.copy(alpha = 0.06f),
                            labelColor = Color.White.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    FilterChip(
                        selected = config.showDepthPreview,
                        onClick = {
                            onPortraitConfigChanged(
                                config.copy(showDepthPreview = !config.showDepthPreview)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Layers,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        label = {
                            Text(
                                text = if (config.showDepthPreview) "Depth Map: ON" else "Depth Map: OFF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor.copy(alpha = 0.22f),
                            selectedLabelColor = accentColor,
                            selectedLeadingIconColor = accentColor,
                            containerColor = Color.White.copy(alpha = 0.06f),
                            labelColor = Color.White.copy(alpha = 0.8f)
                        )
                    )
                }
            }

            if (appearance.showPortraitOpticalDepth) {
                Spacer(modifier = Modifier.height(12.dp))

                // Section 5: Optical Blur Guidance Toggle Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (config.opticalBlurGuided) {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        accentColor.copy(alpha = 0.16f),
                                        accentColor.copy(alpha = 0.06f)
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.05f),
                                        Color.White.copy(alpha = 0.02f)
                                    )
                                )
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (config.opticalBlurGuided) accentColor.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onToggleOpticalBlurGuided() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("optical_blur_guided_toggle")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Optical Blur Guidance",
                                color = if (config.opticalBlurGuided) accentColor else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (config.opticalBlurGuided)
                                    "Physical lens defocus + fine depth & hair matting"
                                else
                                    "Standard synthetic portrait blur",
                                color = Color.White.copy(alpha = 0.60f),
                                fontSize = 11.sp
                            )
                        }

                        Switch(
                            checked = config.opticalBlurGuided,
                            onCheckedChange = { onToggleOpticalBlurGuided() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentColor,
                                checkedTrackColor = accentColor.copy(alpha = 0.35f),
                                uncheckedThumbColor = Color.White.copy(alpha = 0.65f),
                                uncheckedTrackColor = Color.White.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitSectionHeader(
    title: String,
    badge: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Color(0xFFFFD54F))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        }

        if (badge != null) {
            Text(
                text = badge,
                color = Color(0xFFFFD54F),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
