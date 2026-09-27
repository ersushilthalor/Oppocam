package com.example.camera.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.zoom.ZoomProcessingQuality
import com.example.camera.zoom.ai.*
import kotlinx.coroutines.launch

/**
 * Complete Zoom Enhanced Settings & AI Model Import/Selection UI Section.
 * Integrates HAT (https://github.com/XPixelGroup/HAT) and BSRGAN (https://github.com/cszn/BSRGAN)
 * into the existing Zoom Enhanced settings while preserving all existing options.
 */
@Composable
fun ZoomAiModelSettingsSection(
    isHighQualityZoomEnabled: Boolean,
    onHighQualityZoomToggle: (Boolean) -> Unit,
    zoomProcessingQuality: ZoomProcessingQuality,
    onZoomProcessingQualitySelect: (ZoomProcessingQuality) -> Unit,
    repository: ZoomAiModelRepository,
    onShowMessage: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    val reconstructionMode by repository.reconstructionMode.collectAsStateWithLifecycle()
    val keepOriginalImage by repository.keepOriginalImage.collectAsStateWithLifecycle()
    val importedHatModels by repository.importedHatModels.collectAsStateWithLifecycle()
    val importedBsrganModels by repository.importedBsrganModels.collectAsStateWithLifecycle()
    val selectedHatModel by repository.selectedHatModel.collectAsStateWithLifecycle()
    val selectedBsrganModel by repository.selectedBsrganModel.collectAsStateWithLifecycle()
    val hatStatus by repository.hatLoadingStatus.collectAsStateWithLifecycle()
    val bsrganStatus by repository.bsrganLoadingStatus.collectAsStateWithLifecycle()
    val hatError by repository.hatErrorMessage.collectAsStateWithLifecycle()
    val bsrganError by repository.bsrganErrorMessage.collectAsStateWithLifecycle()
    val hardwareStatus by repository.hardwareStatus.collectAsStateWithLifecycle()
    val lastSummary by repository.lastReconstructionSummary.collectAsStateWithLifecycle()

    // Separate Document Pickers for HAT and BSRGAN model files from device storage
    val hatFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = repository.importModelFromUri(uri, ZoomAiModelArchitecture.HAT)
                res.onSuccess { model ->
                    repository.setReconstructionMode(ZoomReconstructionMode.HAT)
                    onShowMessage("HAT Model Loaded on ${hardwareStatus.activeProvider.shortLabel}: ${model.name}")
                }.onFailure { err ->
                    onShowMessage("HAT Import Error: ${err.message}")
                }
            }
        }
    }

    val bsrganFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = repository.importModelFromUri(uri, ZoomAiModelArchitecture.BSRGAN)
                res.onSuccess { model ->
                    repository.setReconstructionMode(ZoomReconstructionMode.BSRGAN)
                    onShowMessage("BSRGAN Model Loaded on ${hardwareStatus.activeProvider.shortLabel}: ${model.name}")
                }.onFailure { err ->
                    onShowMessage("BSRGAN Import Error: ${err.message}")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("zoom_enhanced_ai_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Existing Zoom Enhanced Master Switch
        Surface(
            color = Color(0xFF141720),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Zoom Enhanced Reconstruction",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = Color(0xFFFFD54F).copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = reconstructionMode.shortBadge,
                                color = Color(0xFFFFD54F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Reconstructs high-resolution details on zoomed captures (>1.2x) using Multi-Frame Lanczos-3, HAT AI, or BSRGAN AI.",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }

                Switch(
                    checked = isHighQualityZoomEnabled,
                    onCheckedChange = onHighQualityZoomToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Color(0xFFFFD54F),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("toggle_hq_zoom")
                )
            }
        }

        AnimatedVisibility(visible = isHighQualityZoomEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 2. Reconstruction Model Selector (Traditional vs HAT vs BSRGAN)
                Surface(
                    color = Color(0xFF141720),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Reconstruction Pipeline Selection",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = reconstructionMode.description,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ZoomReconstructionMode.entries.forEach { mode ->
                                val isSelected = reconstructionMode == mode
                                Surface(
                                    color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.07f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.12f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            repository.setReconstructionMode(mode)
                                            onShowMessage("Zoom Reconstruction: ${mode.title}")
                                        }
                                        .testTag("select_zoom_mode_${mode.name.lowercase()}")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = when (mode) {
                                                ZoomReconstructionMode.TRADITIONAL -> "Lanczos-3"
                                                ZoomReconstructionMode.HAT -> "HAT AI"
                                                ZoomReconstructionMode.BSRGAN -> "BSRGAN AI"
                                            },
                                            color = if (isSelected) Color.Black else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Burst Frame Alignment Quality (applies to burst pre-alignment & Traditional mode)
                        Text(
                            text = "Multi-Frame Burst Alignment Quality",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ZoomProcessingQuality.entries.forEach { q ->
                                val isSel = zoomProcessingQuality == q
                                Surface(
                                    color = if (isSel) Color(0xFF60A5FA).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSel) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.1f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onZoomProcessingQualitySelect(q) }
                                        .testTag("zoom_quality_${q.name.lowercase()}")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                    ) {
                                        Text(
                                            text = "${q.label} (${q.burstCount}F)",
                                            color = if (isSel) Color(0xFF93C5FD) else Color.White.copy(alpha = 0.8f),
                                            fontSize = 11.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Keep Original Captured Image Alongside Reconstructed Output
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Keep Original Captured Image",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Saves the original zoomed capture alongside the AI-reconstructed output in gallery.",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.5.sp
                                )
                            }
                            Switch(
                                checked = keepOriginalImage,
                                onCheckedChange = { repository.setKeepOriginalImage(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = Color(0xFFFFD54F)
                                ),
                                modifier = Modifier.testTag("toggle_keep_original_zoom")
                            )
                        }
                    }
                }

                // 3. Hardware GPU / NPU Execution Device Information Card
                GpuNpuHardwareInfoCard(
                    hardwareStatus = hardwareStatus,
                    lastReconstructionSummary = lastSummary,
                    onRefreshHardware = {
                        repository.refreshHardwareCapabilities()
                    }
                )

                // 4. HAT (Hybrid Attention Transformer) Model Import & Management Card
                AiModelArchitectureCard(
                    architecture = ZoomAiModelArchitecture.HAT,
                    isSelectedMode = reconstructionMode == ZoomReconstructionMode.HAT,
                    importedModels = importedHatModels,
                    selectedModel = selectedHatModel,
                    loadingStatus = hatStatus,
                    errorMessage = hatError,
                    activeExecutionDevice = hardwareStatus.activeDeviceSummary,
                    onImportFromStorage = {
                        hatFilePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onPreparePretrained = { scale ->
                        scope.launch {
                            val res = repository.preparePretrainedArchitectureModel(
                                architecture = ZoomAiModelArchitecture.HAT,
                                scaleFactor = scale
                            )
                            res.onSuccess { model ->
                                repository.setReconstructionMode(ZoomReconstructionMode.HAT)
                                onShowMessage("Loaded ${model.name} on ${hardwareStatus.activeProvider.shortLabel}")
                            }.onFailure { err ->
                                onShowMessage("HAT Load Error: ${err.message}")
                            }
                        }
                    },
                    onSelectModel = { modelId ->
                        scope.launch {
                            repository.selectModel(ZoomAiModelArchitecture.HAT, modelId)
                            repository.setReconstructionMode(ZoomReconstructionMode.HAT)
                        }
                    },
                    onDeleteModel = { modelId ->
                        repository.deleteModel(ZoomAiModelArchitecture.HAT, modelId)
                    },
                    onDismissError = {
                        repository.clearErrorMessage(ZoomAiModelArchitecture.HAT)
                    },
                    onActivateMode = {
                        repository.setReconstructionMode(ZoomReconstructionMode.HAT)
                    }
                )

                // 5. BSRGAN (Blind Super-Resolution GAN) Model Import & Management Card
                AiModelArchitectureCard(
                    architecture = ZoomAiModelArchitecture.BSRGAN,
                    isSelectedMode = reconstructionMode == ZoomReconstructionMode.BSRGAN,
                    importedModels = importedBsrganModels,
                    selectedModel = selectedBsrganModel,
                    loadingStatus = bsrganStatus,
                    errorMessage = bsrganError,
                    activeExecutionDevice = hardwareStatus.activeDeviceSummary,
                    onImportFromStorage = {
                        bsrganFilePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onPreparePretrained = { scale ->
                        scope.launch {
                            val res = repository.preparePretrainedArchitectureModel(
                                architecture = ZoomAiModelArchitecture.BSRGAN,
                                scaleFactor = scale
                            )
                            res.onSuccess { model ->
                                repository.setReconstructionMode(ZoomReconstructionMode.BSRGAN)
                                onShowMessage("Loaded ${model.name} on ${hardwareStatus.activeProvider.shortLabel}")
                            }.onFailure { err ->
                                onShowMessage("BSRGAN Load Error: ${err.message}")
                            }
                        }
                    },
                    onSelectModel = { modelId ->
                        scope.launch {
                            repository.selectModel(ZoomAiModelArchitecture.BSRGAN, modelId)
                            repository.setReconstructionMode(ZoomReconstructionMode.BSRGAN)
                        }
                    },
                    onDeleteModel = { modelId ->
                        repository.deleteModel(ZoomAiModelArchitecture.BSRGAN, modelId)
                    },
                    onDismissError = {
                        repository.clearErrorMessage(ZoomAiModelArchitecture.BSRGAN)
                    },
                    onActivateMode = {
                        repository.setReconstructionMode(ZoomReconstructionMode.BSRGAN)
                    }
                )
            }
        }
    }
}

@Composable
private fun GpuNpuHardwareInfoCard(
    hardwareStatus: ZoomAiHardwareStatus,
    lastReconstructionSummary: String?,
    onRefreshHardware: () -> Unit
) {
    Surface(
        color = Color(0xFF101522),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (hardwareStatus.isHardwareAccelerated) Color(0xFF34D399).copy(alpha = 0.35f)
            else Color(0xFFEF4444).copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("zoom_ai_hardware_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "Hardware Accelerator",
                        tint = if (hardwareStatus.isHardwareAccelerated) Color(0xFF34D399) else Color(0xFFEF4444),
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "GPU / NPU Execution Engine",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Active Device: ${hardwareStatus.activeProvider.shortLabel}",
                            color = if (hardwareStatus.isHardwareAccelerated) Color(0xFF34D399) else Color(0xFFEF4444),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("active_execution_device_text")
                        )
                    }
                }

                IconButton(
                    onClick = onRefreshHardware,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .testTag("refresh_hardware_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh GPU/NPU status",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Hardware specification rows
            HardwareMetricRow(label = "Active Accelerator", value = hardwareStatus.activeDeviceSummary)
            HardwareMetricRow(label = "GPU Renderer", value = "${hardwareStatus.gpuRenderer} (${hardwareStatus.glEsVersion})")
            HardwareMetricRow(
                label = "NPU / AI Accelerator",
                value = if (hardwareStatus.npuAvailable) hardwareStatus.npuAcceleratorName else "GPU Compute Primary (No dedicated NPU)"
            )
            HardwareMetricRow(
                label = "Tiled Memory Guard",
                value = "${hardwareStatus.recommendedTileSize}x${hardwareStatus.recommendedTileSize}px tiles · 16px feather overlap (${hardwareStatus.availableMemoryMb} MB free)"
            )
            HardwareMetricRow(
                label = "Execution Policy",
                value = "Hardware GPU/NPU Mandatory (Silent CPU Fallback Disabled)"
            )

            if (!lastReconstructionSummary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color(0xFF34D399).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Last Run: $lastReconstructionSummary",
                        color = Color(0xFF6EE7B7),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.5.sp,
            modifier = Modifier.width(128.dp)
        )
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AiModelArchitectureCard(
    architecture: ZoomAiModelArchitecture,
    isSelectedMode: Boolean,
    importedModels: List<ImportedZoomAiModel>,
    selectedModel: ImportedZoomAiModel?,
    loadingStatus: ZoomAiLoadingStatus,
    errorMessage: String?,
    activeExecutionDevice: String,
    onImportFromStorage: () -> Unit,
    onPreparePretrained: (Int) -> Unit,
    onSelectModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onDismissError: () -> Unit,
    onActivateMode: () -> Unit
) {
    val archTag = architecture.name.lowercase()
    val accentColor = if (architecture == ZoomAiModelArchitecture.HAT) Color(0xFF60A5FA) else Color(0xFFF59E0B)

    Surface(
        color = Color(0xFF141720),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (isSelectedMode) 1.5.dp else 1.dp,
            color = if (isSelectedMode) accentColor else Color.White.copy(alpha = 0.1f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_$archTag")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Model Title + Active Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = architecture.displayName,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isSelectedMode) {
                            Surface(
                                color = accentColor.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "ACTIVE PIPELINE",
                                    color = accentColor,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Official Repo: ${architecture.officialRepoUrl}",
                        color = accentColor.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Status Badge
                val statusColor = when {
                    loadingStatus.isError -> Color(0xFFEF4444)
                    loadingStatus.isReady -> Color(0xFF34D399)
                    loadingStatus.isBusy -> Color(0xFFFBBF24)
                    else -> Color.White.copy(alpha = 0.45f)
                }
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = loadingStatus.label,
                        color = statusColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("status_badge_$archTag")
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Architecture: ${architecture.generatorName}\nPreprocessing: ${architecture.preprocessingSpec}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )

            // Error Banner (if model failed validation or GPU/NPU loading)
            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color(0xFF3B1219),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("error_banner_$archTag")
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Model Error",
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFECACA),
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissError,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss error",
                                tint = Color(0xFFFCA5A5),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Selected Model Details Box
            if (selectedModel != null) {
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("selected_model_info_$archTag")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Selected Model: ${selectedModel.name}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = selectedModel.formattedFileSize,
                                color = accentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Variant: ${selectedModel.variantName} · Scale: ${selectedModel.scaleFactor}x · ${selectedModel.formattedParamCount}",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.5.sp
                        )
                        Text(
                            text = "Format: ${selectedModel.format.label} · Tensors: ${selectedModel.inputTensorShape} → ${selectedModel.outputTensorShape}",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Execution Device: $activeExecutionDevice",
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Surface(
                    color = Color.White.copy(alpha = 0.04f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No ${architecture.name} model imported yet. Import an official .onnx, .pth, .safetensors, or .tflite model file from device storage or load pretrained architecture weights below.",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // List of all Imported Models for this Architecture (persistent across sessions)
            if (importedModels.size > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Saved ${architecture.name} Models (${importedModels.size}):",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                importedModels.forEach { item ->
                    val isItemSelected = selectedModel?.id == item.id
                    Surface(
                        color = if (isItemSelected) accentColor.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isItemSelected) accentColor else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectModel(item.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isItemSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${item.formattedFileSize} · ${item.scaleFactor}x · ${item.variantName}",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 10.5.sp
                                )
                            }
                            IconButton(
                                onClick = { onDeleteModel(item.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Remove model ${item.name}",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary Action Row: Import Model File from Device Storage + Activate Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportFromStorage,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("import_model_btn_$archTag")
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "IMPORT ${architecture.name} MODEL",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (selectedModel != null && !isSelectedMode) {
                    OutlinedButton(
                        onClick = onActivateMode,
                        border = BorderStroke(1.dp, accentColor),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("use_model_btn_$archTag")
                    ) {
                        Text(
                            text = "USE ${architecture.name}",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secondary Row: Initialize / Load Calibrated Official Architecture Pretrained Weights (2x / 4x)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                architecture.supportedScales.filter { it == 2 || it == 4 }.forEach { scale ->
                    OutlinedButton(
                        onClick = { onPreparePretrained(scale) },
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.9f)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("load_pretrained_${archTag}_${scale}x")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (architecture == ZoomAiModelArchitecture.HAT) {
                                "Load HAT_SRx$scale (.pth)"
                            } else {
                                if (scale == 2) "Load BSRGANx2 (.pth)" else "Load BSRGANx4 (.pth)"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
