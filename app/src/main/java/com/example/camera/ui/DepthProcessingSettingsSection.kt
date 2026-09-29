package com.example.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.depth.*
import com.example.camera.model.PortraitConfig

/**
 * Dedicated "Depth Processing" Settings Page inside Camera Settings.
 *
 * Provides:
 * - Real on-demand AI Depth Model management (Depth Anything V2 & MediaSWLF-I / MiDaS v2.1)
 * - Live byte-accurate HTTP download progress, pause/cancel, resume/retry, and TFLite/ONNX tensor verification
 * - Real model status, version, verified runtime format, input/output tensor shape, and disk storage usage
 * - One-tap Delete Model to remove weights and reclaim device storage
 * - Hardware Inference Backend selection (Auto NPU/GPU/CPU, GPU Delegate, NNAPI, CPU XNNPACK)
 * - Portrait Mode Virtual Aperture configuration
 */
@Composable
fun DepthProcessingSettingsPage(
    portraitConfig: PortraitConfig,
    onPortraitConfigChange: (PortraitConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val modelManager = remember { DepthModelManager.getInstance(context) }

    val modelStatuses by modelManager.modelStatuses.collectAsStateWithLifecycle()
    val selectedModel by modelManager.selectedModel.collectAsStateWithLifecycle()
    val hardwareBackend by modelManager.hardwareBackend.collectAsStateWithLifecycle()
    val storageSummary by modelManager.storageSummary.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        modelManager.refreshAllStatuses()
    }

    val accentGold = Color(0xFFFFD54F)
    val installedCount = remember(modelStatuses) {
        modelStatuses.values.count { it.state is DepthModelInstallState.Installed }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("depth_processing_settings_page"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        // 1. Storage & Active AI Depth Engine Status Overview Card
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF131622),
                border = BorderStroke(1.dp, accentGold.copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(accentGold.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Layers,
                                    contentDescription = null,
                                    tint = accentGold,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Photon Virtual Aperture & AI Depth",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Exclusive to Portrait Mode • On-Demand Neural Models",
                                    color = accentGold,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (installedCount > 0) Color(0x264CAF50) else Color.White.copy(alpha = 0.08f)
                        ) {
                            Text(
                                text = if (installedCount > 0) "$installedCount INSTALLED" else "NO MODEL",
                                color = if (installedCount > 0) Color(0xFF66BB6A) else Color.White.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Real Storage Usage Metrics
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StorageStatColumn(
                            label = "ACTIVE MODEL",
                            value = if (modelManager.isModelInstalledAndVerified(selectedModel)) {
                                selectedModel.shortName
                            } else {
                                val fallback = modelManager.getActiveInstalledModelFile()?.first
                                fallback?.shortName ?: "None Installed"
                            },
                            highlight = installedCount > 0
                        )
                        StorageStatColumn(
                            label = "MODELS STORAGE",
                            value = modelManager.formatBytes(storageSummary.totalModelsBytes),
                            highlight = storageSummary.totalModelsBytes > 0L
                        )
                        StorageStatColumn(
                            label = "AVAILABLE FREE",
                            value = modelManager.formatBytes(storageSummary.availableDeviceBytes),
                            highlight = false
                        )
                    }
                }
            }
        }

        // 2. Depth Models Section Header
        item {
            Text(
                text = "DOWNLOADABLE AI DEPTH MODELS",
                color = accentGold,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }

        // 3. Individual Model Cards (Depth Anything V2 & MediaSWLF-I)
        DepthModelType.entries.forEach { modelType ->
            item(key = modelType.id) {
                val statusInfo = modelStatuses[modelType] ?: DepthModelStatusInfo(
                    modelType = modelType,
                    state = DepthModelInstallState.NotInstalled,
                    isSelected = (selectedModel == modelType)
                )
                DepthModelManagementCard(
                    statusInfo = statusInfo,
                    isSelected = (selectedModel == modelType),
                    formatBytes = { modelManager.formatBytes(it) },
                    onSelectModel = { modelManager.selectModel(modelType) },
                    onStartDownload = { modelManager.startDownload(modelType) },
                    onCancelDownload = { modelManager.cancelDownload(modelType) },
                    onDeleteModel = { modelManager.deleteModel(modelType) }
                )
            }
        }

        // 4. Hardware Inference Acceleration Section
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF131622),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Depth Inference Hardware Backend",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = hardwareBackend.description,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DepthHardwareBackend.entries.forEach { backend ->
                            val isSelected = hardwareBackend == backend
                            FilterChip(
                                selected = isSelected,
                                onClick = { modelManager.selectHardwareBackend(backend) },
                                label = {
                                    Text(
                                        text = backend.label,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accentGold,
                                    selectedLabelColor = Color.Black,
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White
                                ),
                                modifier = Modifier.testTag("depth_backend_${backend.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        // 5. Portrait Mode Virtual Aperture Pipeline Controls
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF131622),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Portrait Mode Virtual Aperture",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Configures PhotonCamera depth-aware Circle of Confusion (CoC) rendering exclusively in Portrait Mode.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Virtual Aperture Master Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Depth-Aware Virtual Aperture",
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Applies physical f-stop depth-of-field roll-off and hair-aware bokeh transitions.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.5.sp
                            )
                        }
                        Switch(
                            checked = portraitConfig.virtualApertureEnabled,
                            onCheckedChange = {
                                onPortraitConfigChange(portraitConfig.copy(virtualApertureEnabled = it))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = accentGold
                            ),
                            modifier = Modifier.testTag("toggle_virtual_aperture_enabled")
                        )
                    }

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )

                    // Real-time Viewfinder Aperture Preview Toggle (Strictly requires an installed & verified AI model)
                    val hasVerifiedModel = installedCount > 0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Real-Time Viewfinder Aperture Preview",
                                color = if (hasVerifiedModel) Color.White else Color.White.copy(alpha = 0.45f),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (hasVerifiedModel) {
                                    "Live depth-based bokeh preview in Portrait Mode using ${selectedModel.shortName}."
                                } else {
                                    "Requires an installed & verified AI depth model above. Disabled until a model is installed."
                                },
                                color = if (hasVerifiedModel) Color.White.copy(alpha = 0.6f) else Color(0xFFFFB74D),
                                fontSize = 11.5.sp
                            )
                        }
                        Switch(
                            checked = hasVerifiedModel && portraitConfig.liveAperturePreviewEnabled,
                            onCheckedChange = {
                                if (hasVerifiedModel) {
                                    onPortraitConfigChange(portraitConfig.copy(liveAperturePreviewEnabled = it))
                                }
                            },
                            enabled = hasVerifiedModel,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = accentGold
                            ),
                            modifier = Modifier.testTag("toggle_live_aperture_preview")
                        )
                    }

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )

                    // Depth Map Visualizer Overlay Toggle (Portrait Mode only, when verified model installed)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Show Live AI Depth Map Overlay",
                                color = if (hasVerifiedModel) Color.White else Color.White.copy(alpha = 0.45f),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (hasVerifiedModel) {
                                    "Displays real-time neural inverse-depth colormap & active focal plane in Portrait Mode."
                                } else {
                                    "Download and verify a depth model above to enable real depth map visualization."
                                },
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.5.sp
                            )
                        }
                        Switch(
                            checked = hasVerifiedModel && portraitConfig.showDepthPreview,
                            onCheckedChange = {
                                if (hasVerifiedModel) {
                                    onPortraitConfigChange(portraitConfig.copy(showDepthPreview = it))
                                }
                            },
                            enabled = hasVerifiedModel,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = accentGold
                            ),
                            modifier = Modifier.testTag("toggle_depth_map_overlay")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageStatColumn(
    label: String,
    value: String,
    highlight: Boolean
) {
    Column {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            color = if (highlight) Color(0xFFFFD54F) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun DepthModelManagementCard(
    statusInfo: DepthModelStatusInfo,
    isSelected: Boolean,
    formatBytes: (Long) -> String,
    onSelectModel: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val model = statusInfo.modelType
    val state = statusInfo.state
    val isInstalled = state is DepthModelInstallState.Installed
    val accentGold = Color(0xFFFFD54F)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF131622),
        border = BorderStroke(
            width = if (isSelected && isInstalled) 1.5.dp else 1.dp,
            color = when {
                isSelected && isInstalled -> accentGold
                isInstalled -> Color(0xFF4CAF50).copy(alpha = 0.45f)
                else -> Color.White.copy(alpha = 0.09f)
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("depth_model_card_${model.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Row: Model Title, Version & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = model.displayName,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isSelected && isInstalled) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = accentGold.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    color = accentGold,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Version: ${model.version} • Source: ${model.sourceRepo}",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp
                    )
                }

                // Status Pill
                val (badgeText, badgeColor, badgeBg) = when (state) {
                    is DepthModelInstallState.Installed -> Triple(
                        "INSTALLED",
                        Color(0xFF66BB6A),
                        Color(0x264CAF50)
                    )
                    is DepthModelInstallState.Downloading -> Triple(
                        "DOWNLOADING",
                        accentGold,
                        accentGold.copy(alpha = 0.16f)
                    )
                    is DepthModelInstallState.Verifying -> Triple(
                        "VERIFYING",
                        Color(0xFF4FC3F7),
                        Color(0x264FC3F7)
                    )
                    is DepthModelInstallState.Failed -> Triple(
                        if (state.partialBytes > 0L) "PAUSED / RETRY" else "ERROR",
                        Color(0xFFFF7043),
                        Color(0x26FF7043)
                    )
                    DepthModelInstallState.NotInstalled -> Triple(
                        "NOT INSTALLED",
                        Color.White.copy(alpha = 0.65f),
                        Color.White.copy(alpha = 0.08f)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = model.description,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // State-specific Details & Progress Bar
            when (state) {
                is DepthModelInstallState.Installed -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Storage Used: ${formatBytes(state.fileSizeBytes)}",
                                    color = accentGold,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Format: ${state.verifiedFormat}",
                                    color = Color(0xFF81C784),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Verified Tensor Graph: ${state.inputShapeSummary}",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onSelectModel,
                            enabled = !isSelected,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentGold,
                                contentColor = Color.Black,
                                disabledContainerColor = Color(0xFF2E3548),
                                disabledContentColor = Color.White.copy(alpha = 0.7f)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("use_depth_model_${model.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isSelected) "Selected Active Model" else "Use This Model",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onDeleteModel,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFFF5252)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.55f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("delete_depth_model_${model.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete ${model.displayName}",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Delete",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                is DepthModelInstallState.Downloading -> {
                    val pct = (state.progressFraction * 100f).toInt().coerceIn(0, 99)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val totalLabel = if (state.totalBytes > 0L) {
                                "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
                            } else {
                                "${formatBytes(state.downloadedBytes)} downloaded"
                            }
                            Text(
                                text = totalLabel,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            val speedLabel = if (state.speedBytesPerSec > 0L) {
                                "${formatBytes(state.speedBytesPerSec)}/s • $pct%"
                            } else {
                                "$pct%"
                            }
                            Text(
                                text = speedLabel,
                                color = accentGold,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (state.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { state.progressFraction.coerceIn(0.01f, 0.99f) },
                                color = accentGold,
                                trackColor = Color.White.copy(alpha = 0.14f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        } else {
                            LinearProgressIndicator(
                                color = accentGold,
                                trackColor = Color.White.copy(alpha = 0.14f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = onCancelDownload,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cancel_download_${model.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Cancel,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Pause / Cancel Download",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                is DepthModelInstallState.Verifying -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = accentGold,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "Verifying neural network weights & tensor shapes...",
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                is DepthModelInstallState.Failed -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0x22FF5252),
                        border = BorderStroke(1.dp, Color(0x55FF5252)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.errorMessage,
                            color = Color(0xFFFF8A80),
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onStartDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentGold,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("retry_download_${model.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (state.partialBytes > 0L) "Resume Download" else "Retry Download",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (state.partialBytes > 0L) {
                            OutlinedButton(
                                onClick = onDeleteModel,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                                border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                DepthModelInstallState.NotInstalled -> {
                    val sizeHint = statusInfo.remoteSizeBytes?.let {
                        "Download Size: ${formatBytes(it)}"
                    } ?: "On-Demand Download • Verified TFLite/ONNX"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = sizeHint,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Button(
                            onClick = onStartDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentGold,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("download_depth_model_${model.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Download Model",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}
