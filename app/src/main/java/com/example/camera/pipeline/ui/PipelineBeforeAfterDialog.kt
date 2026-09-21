package com.example.camera.pipeline.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.camera.pipeline.model.PipelinePreset
import com.example.camera.viewmodel.CameraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun PipelineBeforeAfterDialog(
    viewModel: CameraViewModel,
    onDismissRequest: () -> Unit
) {
    val latestCapture by viewModel.latestPipelineCapture.collectAsState()
    val activePreset by viewModel.activePipelinePreset.collectAsState()
    val activeParams by viewModel.activePipelineParams.collectAsState()
    val customPresets by viewModel.customPresets.collectAsState()
    val isReprocessing by viewModel.isReprocessing.collectAsState()

    val scope = rememberCoroutineScope()
    var processedPreview by remember { mutableStateOf<Bitmap?>(null) }
    var splitFraction by remember { mutableFloatStateOf(0.50f) }

    val allPresets = remember(customPresets) {
        PipelinePreset.BUILT_IN_PRESETS + customPresets
    }

    // Re-render preview bitmap whenever activeParams or latestCapture changes
    LaunchedEffect(latestCapture, activeParams) {
        val capture = latestCapture ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val bmp = viewModel.engine.customImagePipelineEngine.processImage(
                source = capture.previewBitmap,
                params = activeParams,
                isFastPreview = true
            )
            withContext(Dispatchers.Main) {
                processedPreview = bmp
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.testTag("before_after_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Split Before / After",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "Unprocessed Sensor vs ${activePreset.displayName}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFE5A93B),
                                fontSize = 11.sp
                            )
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.reprocessLatestCaptureWithCurrentParams {
                                onDismissRequest()
                            }
                        },
                        enabled = latestCapture != null && !isReprocessing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE5A93B),
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.testTag("before_after_save")
                    ) {
                        if (isReprocessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (latestCapture == null) {
                    // Empty State
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Photo Captured Yet",
                            style = MaterialTheme.typography.titleMedium.copy(color = Color.White)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Take a photo in Photo mode to compare unprocessed RAW/YUV sensor data with Custom Pipeline presets.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color.White.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    val rawProxy = latestCapture!!.previewBitmap
                    val processedProxy = processedPreview ?: rawProxy

                    // Interactive Split Image Viewport
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    val newFraction = (change.position.x / size.width).coerceIn(0.05f, 0.95f)
                                    splitFraction = newFraction
                                }
                            }
                    ) {
                        val canvasWidth = constraints.maxWidth.toFloat()
                        val canvasHeight = constraints.maxHeight.toFloat()
                        val dividerX = (canvasWidth * splitFraction)

                        // Render Canvas with Split Clip
                        val rawImageBmp = remember(rawProxy) { rawProxy.asImageBitmap() }
                        val processedImageBmp = remember(processedProxy) { processedProxy.asImageBitmap() }

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val splitPx = w * splitFraction

                            // 1. Draw "After" (Custom Pipeline Rendered) on the whole canvas
                            drawImage(
                                image = processedImageBmp,
                                dstSize = IntSize(w.roundToInt(), h.roundToInt())
                            )

                            // 2. Clip and draw "Before" (Raw Sensor Data) on the left side
                            clipRect(left = 0f, top = 0f, right = splitPx, bottom = h) {
                                drawImage(
                                    image = rawImageBmp,
                                    dstSize = IntSize(w.roundToInt(), h.roundToInt())
                                )
                            }

                            // 3. Draw vertical divider bar
                            drawLine(
                                color = Color.White,
                                start = Offset(splitPx, 0f),
                                end = Offset(splitPx, h),
                                strokeWidth = 2.5.dp.toPx()
                            )
                        }

                        // Draggable Handle Pill in the center
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        x = (dividerX - 20.dp.toPx()).roundToInt(),
                                        y = (canvasHeight / 2 - 20.dp.toPx()).roundToInt()
                                    )
                                }
                                .size(40.dp)
                                .background(Color.White, CircleShape)
                                .border(2.dp, Color(0xFFE5A93B), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                                contentDescription = "Drag to compare",
                                tint = Color.Black,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Labels: Before vs After
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black.copy(alpha = 0.65f),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "RAW / SENSOR",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    letterSpacing = 1.sp
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFE5A93B).copy(alpha = 0.85f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                        ) {
                            Text(
                                text = activePreset.displayName.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    letterSpacing = 1.sp
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Bottom Floating Strip for Instant Preset Switching Without Re-shooting
                    Surface(
                        color = Color(0xCC16181D),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SWITCH PRESET ON CAPTURE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE5A93B),
                                        letterSpacing = 1.sp
                                    )
                                )

                                TextButton(
                                    onClick = {
                                        viewModel.setPipelineSheetOpen(true)
                                        onDismissRequest()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Fine-tune",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Fine-tune", color = Color.White, fontSize = 12.sp)
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                allPresets.forEach { preset ->
                                    val isSelected = preset.id == activePreset.id
                                    val bg = if (isSelected) Color(0xFFE5A93B) else Color(0xFF262A35)
                                    val textCol = if (isSelected) Color.Black else Color.White

                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = bg,
                                        modifier = Modifier
                                            .testTag("before_after_preset_${preset.id}")
                                            .clickable {
                                                viewModel.selectPipelinePreset(preset)
                                            }
                                    ) {
                                        Text(
                                            text = preset.displayName,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = textCol,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
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
