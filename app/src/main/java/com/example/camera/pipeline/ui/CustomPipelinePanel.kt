package com.example.camera.pipeline.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.pipeline.model.ColorMatrixPreset
import com.example.camera.pipeline.model.CustomPipelineParams
import com.example.camera.pipeline.model.PipelinePreset
import com.example.camera.viewmodel.CameraViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomPipelineBottomSheet(
    viewModel: CameraViewModel,
    onDismissRequest: () -> Unit
) {
    val isPipelineEnabled by viewModel.isCustomPipelineEnabled.collectAsState()
    val activePreset by viewModel.activePipelinePreset.collectAsState()
    val activeParams by viewModel.activePipelineParams.collectAsState()
    val customPresets by viewModel.customPresets.collectAsState()
    val latestCapture by viewModel.latestPipelineCapture.collectAsState()

    var showSaveDialog by remember { mutableStateOf(false) }
    var selectedCategoryTab by remember { mutableIntStateOf(0) }

    val allPresets = remember(customPresets) {
        PipelinePreset.BUILT_IN_PRESETS + customPresets
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = Color(0xFF16181D),
        contentColor = Color.White,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.35f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Image Processing Pipeline",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Text(
                        text = "Uncompressed RAW/YUV sensor ISP pipeline",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isPipelineEnabled,
                        onCheckedChange = { viewModel.toggleCustomPipelineEnabled(it) },
                        modifier = Modifier.testTag("pipeline_master_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFE5A93B),
                            checkedTrackColor = Color(0xFFE5A93B).copy(alpha = 0.4f)
                        )
                    )
                }
            }

            // Presets Horizontal Reel
            Text(
                text = "CAMERA COLOR & RENDERING PRESETS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = Color(0xFFE5A93B)
                ),
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                allPresets.forEach { preset ->
                    val isSelected = preset.id == activePreset.id
                    val chipBg = if (isSelected) Color(0xFFE5A93B) else Color(0xFF262A35)
                    val chipTextColor = if (isSelected) Color.Black else Color.White

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = chipBg,
                        modifier = Modifier
                            .testTag("preset_chip_${preset.id}")
                            .clickable {
                                viewModel.selectPipelinePreset(preset)
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = preset.displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = chipTextColor
                                )
                            )
                            if (!preset.isBuiltIn) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = null,
                                    tint = chipTextColor,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Preset Description Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF20242E)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFFE5A93B),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = activePreset.description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp
                        )
                    )
                }
            }

            // Category Tab Selector
            TabRow(
                selectedTabIndex = selectedCategoryTab,
                containerColor = Color(0xFF1E222A),
                contentColor = Color(0xFFE5A93B),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .padding(bottom = 12.dp)
            ) {
                Tab(
                    selected = selectedCategoryTab == 0,
                    onClick = { selectedCategoryTab = 0 },
                    text = { Text("Tone & HDR", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedCategoryTab == 1,
                    onClick = { selectedCategoryTab = 1 },
                    text = { Text("Color & WB", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedCategoryTab == 2,
                    onClick = { selectedCategoryTab = 2 },
                    text = { Text("Detail & NR", fontSize = 12.sp) }
                )
            }

            // Sliders Content Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedCategoryTab) {
                    0 -> ToneControls(
                        params = activeParams,
                        onParamsChanged = { viewModel.updatePipelineParams(it) }
                    )
                    1 -> ColorControls(
                        params = activeParams,
                        onParamsChanged = { viewModel.updatePipelineParams(it) }
                    )
                    2 -> DetailControls(
                        params = activeParams,
                        onParamsChanged = { viewModel.updatePipelineParams(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Compare Before/After Button (if a capture is cached)
                OutlinedButton(
                    onClick = {
                        viewModel.setBeforeAfterOpen(true)
                        onDismissRequest()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pipeline_compare_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE5A93B)),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFE5A93B))
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                        contentDescription = "Compare",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Before/After", fontSize = 12.sp)
                }

                // Save Custom Preset
                Button(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pipeline_save_preset_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE5A93B),
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "Save",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Preset", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Reset Parameters
                IconButton(
                    onClick = { viewModel.resetActivePresetParams() },
                    modifier = Modifier
                        .background(Color(0xFF262A35), CircleShape)
                        .testTag("pipeline_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }

                // Delete custom preset if active
                if (!activePreset.isBuiltIn) {
                    IconButton(
                        onClick = { viewModel.deletePipelineCustomPreset(activePreset.id) },
                        modifier = Modifier
                            .background(Color(0xFF421C1C), CircleShape)
                            .testTag("pipeline_delete_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color(0xFFFF6B6B)
                        )
                    }
                }
            }
        }
    }

    // Save Custom Preset Dialog
    if (showSaveDialog) {
        var presetName by remember { mutableStateOf("") }
        var presetDesc by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = Color(0xFF1E222A),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("Save Custom Preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Save current pipeline parameters as a personal preset:",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.7f))
                    )
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("Preset Name (e.g. Vintage Warmth)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = presetDesc,
                        onValueChange = { presetDesc = it },
                        label = { Text("Description") },
                        singleLine = false,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetName.isNotBlank()) {
                            viewModel.savePipelineCustomPreset(presetName.trim(), presetDesc.trim())
                            showSaveDialog = false
                        }
                    },
                    enabled = presetName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5A93B), contentColor = Color.Black)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }
}

@Composable
private fun ToneControls(
    params: CustomPipelineParams,
    onParamsChanged: (CustomPipelineParams) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PipelineSliderItem(
            label = "Exposure",
            value = params.exposure,
            valueRange = -2.0f..2.0f,
            valueFormat = "%.2f EV",
            onValueChange = { onParamsChanged(params.copy(exposure = it)) }
        )
        PipelineSliderItem(
            label = "Contrast",
            value = params.contrast,
            valueRange = 0.50f..1.50f,
            valueFormat = "%.2fx",
            onValueChange = { onParamsChanged(params.copy(contrast = it)) }
        )
        PipelineSliderItem(
            label = "Highlights",
            value = params.highlights,
            valueRange = -100f..100f,
            valueFormat = "%+.0f",
            onValueChange = { onParamsChanged(params.copy(highlights = it)) }
        )
        PipelineSliderItem(
            label = "Shadows",
            value = params.shadows,
            valueRange = -100f..100f,
            valueFormat = "%+.0f",
            onValueChange = { onParamsChanged(params.copy(shadows = it)) }
        )
        PipelineSliderItem(
            label = "Whites",
            value = params.whites,
            valueRange = -100f..100f,
            valueFormat = "%+.0f",
            onValueChange = { onParamsChanged(params.copy(whites = it)) }
        )
        PipelineSliderItem(
            label = "Blacks",
            value = params.blacks,
            valueRange = -100f..100f,
            valueFormat = "%+.0f",
            onValueChange = { onParamsChanged(params.copy(blacks = it)) }
        )
        PipelineSliderItem(
            label = "Highlight Roll-Off",
            value = params.highlightRollOff,
            valueRange = 0f..100f,
            valueFormat = "%.0f%%",
            onValueChange = { onParamsChanged(params.copy(highlightRollOff = it)) }
        )
        PipelineSliderItem(
            label = "Shadow Recovery",
            value = params.shadowRecovery,
            valueRange = 0f..100f,
            valueFormat = "%.0f%%",
            onValueChange = { onParamsChanged(params.copy(shadowRecovery = it)) }
        )
        PipelineSliderItem(
            label = "Local Tone Mapping / HDR",
            value = params.localToneMapping,
            valueRange = 0f..100f,
            valueFormat = "%.0f%%",
            onValueChange = { onParamsChanged(params.copy(localToneMapping = it)) }
        )
    }
}

@Composable
private fun ColorControls(
    params: CustomPipelineParams,
    onParamsChanged: (CustomPipelineParams) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Color Matrix Selector
        Text(
            text = "COLOR MATRIX",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.7f)
            )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ColorMatrixPreset.values().forEach { matrix ->
                val isSelected = params.colorMatrixPreset == matrix
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) Color(0xFFE5A93B) else Color(0xFF262A35),
                    modifier = Modifier.clickable {
                        onParamsChanged(params.copy(colorMatrixPreset = matrix))
                    }
                ) {
                    Text(
                        text = matrix.displayName,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isSelected) Color.Black else Color.White,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        PipelineSliderItem(
            label = "Color Temperature",
            value = params.temperature,
            valueRange = -100f..100f,
            valueFormat = "%+.0f",
            onValueChange = { onParamsChanged(params.copy(temperature = it)) }
        )
        PipelineSliderItem(
            label = "Tint (Green / Magenta)",
            value = params.tint,
            valueRange = -100f..100f,
            valueFormat = "%+.0f",
            onValueChange = { onParamsChanged(params.copy(tint = it)) }
        )
        PipelineSliderItem(
            label = "Saturation",
            value = params.saturation,
            valueRange = -100f..100f,
            valueFormat = "%+.0f",
            onValueChange = { onParamsChanged(params.copy(saturation = it)) }
        )
        PipelineSliderItem(
            label = "Vibrance (Skin Safe)",
            value = params.vibrance,
            valueRange = -100f..100f,
            valueFormat = "%+.0f",
            onValueChange = { onParamsChanged(params.copy(vibrance = it)) }
        )
    }
}

@Composable
private fun DetailControls(
    params: CustomPipelineParams,
    onParamsChanged: (CustomPipelineParams) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PipelineSliderItem(
            label = "Sharpness (Halo-Free)",
            value = params.sharpness,
            valueRange = 0f..100f,
            valueFormat = "%.0f",
            onValueChange = { onParamsChanged(params.copy(sharpness = it)) }
        )
        PipelineSliderItem(
            label = "Micro-Contrast / Clarity",
            value = params.microContrast,
            valueRange = -100f..100f,
            valueFormat = "%+.0f",
            onValueChange = { onParamsChanged(params.copy(microContrast = it)) }
        )
        PipelineSliderItem(
            label = "Texture Preservation",
            value = params.texture,
            valueRange = -100f..100f,
            valueFormat = "%+.0f",
            onValueChange = { onParamsChanged(params.copy(texture = it)) }
        )
        PipelineSliderItem(
            label = "Noise Reduction",
            value = params.noiseReduction,
            valueRange = 0f..100f,
            valueFormat = "%.0f%%",
            onValueChange = { onParamsChanged(params.copy(noiseReduction = it)) }
        )
        PipelineSliderItem(
            label = "Detail Threshold",
            value = params.detailPreservation,
            valueRange = 0f..100f,
            valueFormat = "%.0f%%",
            onValueChange = { onParamsChanged(params.copy(detailPreservation = it)) }
        )
    }
}

@Composable
private fun PipelineSliderItem(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueFormat: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            )
            Text(
                text = String.format(Locale.US, valueFormat, value),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE5A93B),
                    fontSize = 12.sp
                )
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFE5A93B),
                activeTrackColor = Color(0xFFE5A93B),
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
        )
    }
}
