package com.example.camera.restormer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.restormer.data.ModelImportResult
import com.example.camera.restormer.data.RestormerModelManager
import com.example.camera.restormer.engine.RestormerInferenceEngine
import com.example.camera.restormer.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestormerStudioSheet(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    modelManager: RestormerModelManager,
    config: RestormerConfig,
    onUpdateConfig: (RestormerConfig) -> Unit,
    onShowToast: (String) -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val models by modelManager.models.collectAsState()
    val activeModel by modelManager.activeModel.collectAsState()

    var isDiagnosticRunning by remember { mutableStateOf(false) }
    var diagnosticResult by remember { mutableStateOf<RestormerInferenceEngine.BenchmarkResult?>(null) }
    var diagnosticErrorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Models, 1: Tiling & Performance, 2: Diagnostics

    // State for PyTorch .pth conversion dialog
    var showPthConversionDialog by remember { mutableStateOf(false) }
    var pthDialogFileName by remember { mutableStateOf("model.pth") }

    // File picker launcher for importing models (.onnx and .tflite)
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                onShowToast("Validating and importing model from device storage...")
                val result = modelManager.importModelFromUri(uri)
                when (result) {
                    is ModelImportResult.Success -> {
                        onShowToast("Model imported successfully: ${result.modelInfo.name} (${result.benchmarkMs}ms)")
                        onUpdateConfig(config.copy(activeModelId = result.modelInfo.id))
                    }
                    is ModelImportResult.PthCheckpointDetected -> {
                        pthDialogFileName = result.fileName
                        showPthConversionDialog = true
                    }
                    is ModelImportResult.Error -> {
                        onShowToast("Import failed: ${result.reason}")
                    }
                }
            }
        }
    }

    if (showPthConversionDialog) {
        AlertDialog(
            onDismissRequest = { showPthConversionDialog = false },
            containerColor = Color(0xFF1E1E28),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.85f),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PyTorch (.pth) Checkpoint Guide",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "File: '$pthDialogFileName'",
                        color = Color(0xFFFFD54F),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "The official Restormer repository provides raw PyTorch (.pth) weights. Android mobile devices execute compiled ONNX or TFLite neural networks, not raw Python/PyTorch pickles.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "Run this one-line command to export the official checkpoint into an ONNX model:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    val exportCmd = "python3 scripts/export_restormer_to_onnx.py --checkpoint $pthDialogFileName --task deraining --output restormer_derain.onnx"
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .border(1.dp, Color(0xFFFFD54F).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = exportCmd,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFFFFD54F)
                        )
                    }

                    Text(
                        text = "After running the script, transfer the resulting .onnx file to your phone and tap 'Import Model (.onnx / .tflite)' to load it.",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cmd = "python3 scripts/export_restormer_to_onnx.py --checkpoint $pthDialogFileName --task deraining --output restormer_derain.onnx"
                        clipboardManager.setText(AnnotatedString(cmd))
                        onShowToast("Conversion command copied to clipboard!")
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFD54F),
                        contentColor = Color.Black
                    )
                ) {
                    Text("Copy Command", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPthConversionDialog = false }
                ) {
                    Text("Close", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = Color(0xFF141418),
        contentColor = Color.White,
        tonalElevation = 16.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .testTag("restormer_studio_sheet")
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "RESTORMER AI",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFFFD54F).copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "12 MP TILED",
                                color = Color(0xFFFFD54F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = "Official CVPR 2022 Transformer Restoration & Model Manager",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }

                // Master Toggle
                Switch(
                    checked = config.isEnabled,
                    onCheckedChange = { isChecked ->
                        onUpdateConfig(config.copy(isEnabled = isChecked))
                        onShowToast(if (isChecked) "Restormer AI: Enabled" else "Restormer AI: Disabled")
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Color(0xFFFFD54F),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("restormer_master_toggle")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Navigation Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(4.dp)
            ) {
                TabButton(
                    title = "AI Models",
                    isSelected = selectedTab == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedTab = 0 }
                )
                TabButton(
                    title = "Tiling & Performance",
                    isSelected = selectedTab == 1,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedTab = 1 }
                )
                TabButton(
                    title = "Diagnostic Test",
                    isSelected = selectedTab == 2,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedTab = 2 }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            when (selectedTab) {
                0 -> {
                    // Models Tab
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Action buttons row: Import model & PyTorch Guide
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        modelPickerLauncher.launch(arrayOf("*/*"))
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2A2A34)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .testTag("import_model_button"),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        Color(0xFFFFD54F).copy(alpha = 0.5f)
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FileOpen,
                                        contentDescription = null,
                                        tint = Color(0xFFFFD54F),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Import Model",
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        pthDialogFileName = "deraining.pth"
                                        showPthConversionDialog = true
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .testTag("pytorch_guide_button"),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        Color.White.copy(alpha = 0.25f)
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Transform,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = ".pth Guide",
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        // Model List
                        items(models) { model ->
                            val isSelected = model.id == activeModel.id
                            ModelCard(
                                model = model,
                                isSelected = isSelected,
                                onSelect = {
                                    modelManager.selectActiveModel(model.id)
                                    onUpdateConfig(config.copy(activeModelId = model.id))
                                    onShowToast("Selected: ${model.name}")
                                },
                                onDelete = if (!model.isBuiltIn) {
                                    {
                                        modelManager.deleteCustomModel(model.id)
                                        onShowToast("Deleted model: ${model.name}")
                                    }
                                } else null
                            )
                        }
                    }
                }
                1 -> {
                    // Tiling & Performance Controls
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Resolution preservation info card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.04f))
                                .border(1.dp, Color(0xFFFFD54F).copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.HighQuality,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD54F),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Full 12 MP Resolution Preserved",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Tiled inference processes full 4032x3024 sensor data with cosine boundary feathering. Memory bounded to active tile buffers.",
                                        color = Color.White.copy(alpha = 0.65f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        // Tile Size selector
                        Column {
                            Text(
                                text = "Tile Size (Pixels)",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(64, 128, 256).forEach { size ->
                                    val isPicked = config.tileSize == size
                                    FilterChip(
                                        selected = isPicked,
                                        onClick = { onUpdateConfig(config.copy(tileSize = size)) },
                                        label = { Text("${size}x${size}") },
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

                        // Overlap Margin selector
                        Column {
                            Text(
                                text = "Tile Overlap (Seam Smoothing Margin)",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(8, 16, 32).forEach { overlap ->
                                    val isPicked = config.overlap == overlap
                                    FilterChip(
                                        selected = isPicked,
                                        onClick = { onUpdateConfig(config.copy(overlap = overlap)) },
                                        label = { Text("${overlap}px") },
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

                        // Hardware Acceleration Delegate
                        Column {
                            Text(
                                text = "Hardware Execution Provider",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HardwareDelegateOption.values().forEach { delegate ->
                                    val isPicked = config.hardwareDelegate == delegate
                                    FilterChip(
                                        selected = isPicked,
                                        onClick = { onUpdateConfig(config.copy(hardwareDelegate = delegate)) },
                                        label = { Text(delegate.displayName.substringBefore(' ')) },
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

                        // Restoration Strength Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Restoration Strength",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${(config.restorationStrength * 100).toInt()}%",
                                    color = Color(0xFFFFD54F),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = config.restorationStrength,
                                onValueChange = { onUpdateConfig(config.copy(restorationStrength = it)) },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFFD54F),
                                    activeTrackColor = Color(0xFFFFD54F),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                )
                            )
                        }
                    }
                }
                2 -> {
                    // Diagnostic Verification Tab
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Model Verification & Live Diagnostic",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Executes real neural inference through the active model '${activeModel.name}' to verify tensor operations, execution latency, and numerical validity.",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp
                        )

                        // Model Details card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.04f))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Active Model:",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = activeModel.name,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Task / Backend:",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "${activeModel.task.displayName} (${activeModel.backend.displayName})",
                                        color = Color(0xFFFFD54F),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Storage Location:",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = if (activeModel.filePath != null) "Internal Storage" else "APK Assets",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                isDiagnosticRunning = true
                                diagnosticErrorMessage = null
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.Default) {
                                        try {
                                            val engine = RestormerInferenceEngine(
                                                context = context,
                                                modelInfo = activeModel,
                                                delegateOption = config.hardwareDelegate
                                            )
                                            val res = engine.runDiagnosticBenchmark(config.tileSize)
                                            engine.close()
                                            res
                                        } catch (e: Throwable) {
                                            val msg = StringBuilder("[${e.javaClass.simpleName}] ${e.message}")
                                            if (e.cause != null) {
                                                msg.append("\nCause: [${e.cause?.javaClass?.simpleName}] ${e.cause?.message}")
                                            }
                                            diagnosticErrorMessage = msg.toString()
                                            RestormerInferenceEngine.BenchmarkResult(
                                                isSuccess = false,
                                                latencyMs = 0L,
                                                tilePixels = config.tileSize * config.tileSize,
                                                meanDiffPerPixel = 0f,
                                                variance = 0f,
                                                message = "Initialization/Inference Exception: ${e.message}"
                                            )
                                        }
                                    }
                                    diagnosticResult = result
                                    isDiagnosticRunning = false
                                }
                            },
                            enabled = !isDiagnosticRunning,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD54F),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("run_diagnostic_button")
                        ) {
                            if (isDiagnosticRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.Black,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Executing Inference...")
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Verify Model Inference Now", fontWeight = FontWeight.Bold)
                            }
                        }

                        diagnosticResult?.let { res ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (res.isSuccess) Color(0xFF1E3A24) else Color(0xFF3E1E1E)
                                    )
                                    .border(
                                        1.dp,
                                        if (res.isSuccess) Color(0xFF4CAF50) else Color(0xFFF44336),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .padding(14.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (res.isSuccess) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                                            contentDescription = null,
                                            tint = if (res.isSuccess) Color(0xFF4CAF50) else Color(0xFFF44336),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (res.isSuccess) "Real Inference Verified" else "Verification Failed",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = res.message,
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 11.sp
                                    )
                                    if (res.isSuccess) {
                                        Text(
                                            text = "Latency: ${res.latencyMs} ms · Variance: ${String.format("%.5f", res.variance)} · Tile: ${config.tileSize}x${config.tileSize}",
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 11.sp
                                        )
                                    } else if (diagnosticErrorMessage != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Error Details:\n$diagnosticErrorMessage",
                                            color = Color(0xFFFF8A80),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}

@Composable
private fun TabButton(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0xFFFFD54F) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ModelCard(
    model: RestormerModelInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isSelected) Color(0xFF282834) else Color.White.copy(alpha = 0.04f)
            )
            .border(
                1.dp,
                if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(14.dp)
            )
            .clickable { onSelect() }
            .padding(12.dp)
            .testTag("model_card_${model.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFFD54F).copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = model.task.badge,
                            color = Color(0xFFFFD54F),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = model.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = model.validationSummary,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${model.backend.displayName} · ${model.inputWidth}x${model.inputHeight} NCHW",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 10.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete model",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                RadioButton(
                    selected = isSelected,
                    onClick = onSelect,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = Color(0xFFFFD54F),
                        unselectedColor = Color.White.copy(alpha = 0.3f)
                    )
                )
            }
        }
    }
}
