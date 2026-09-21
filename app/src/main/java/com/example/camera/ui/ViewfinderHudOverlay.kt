package com.example.camera.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.CameraMode
import com.example.camera.model.HardwareCapabilities
import com.example.camera.model.StorageStats
import com.example.camera.model.UiTemplateType
import kotlin.math.roundToInt

/**
 * Live Viewfinder HUD Overlay providing distinct, premium viewfinder experiences
 * tailored to each Camera UI Template:
 * 1. STOCK_PIXEL: Vertical dual exposure / brightness slider on right edge with tactile sun icon.
 * 2. MINIMAL_PRO: Precision monochrome telemetry strip (ISO, Shutter, f/, EV, WB, shots) + EV dial.
 * 3. FUTURISTIC_GLASS: Cyber neon brackets, digital gyro horizon line & audio dB meter bars.
 * 4. DSLR_PRO: Top OLED display band, side exposure ladder, and tactile parameter wheels.
 * 5. IMMERSIVE_EDGE: Dual edge-zone sliders (exposure on left, focal length on right).
 */
@Composable
fun ViewfinderHudOverlay(
    templateType: UiTemplateType,
    cameraMode: CameraMode,
    exposureCompensation: Int,
    onExposureChange: (Int) -> Unit,
    currentZoom: Float,
    onZoomChange: (Float) -> Unit,
    manualIso: Int?,
    manualShutterSpeedNs: Long?,
    storageStats: StorageStats,
    capabilities: HardwareCapabilities,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (templateType) {
            UiTemplateType.STOCK_PIXEL -> {
                // Exposure slider removed per user request; auto exposure operates internally
            }

            UiTemplateType.MINIMAL_PRO -> {
                // Minimalist Leica-inspired Telemetry Strip at Top of Viewfinder
                MinimalProTelemetryStrip(
                    exposureCompensation = exposureCompensation,
                    manualIso = manualIso,
                    manualShutterSpeedNs = manualShutterSpeedNs,
                    storageStats = storageStats,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp)
                )

                // Precision Corner Framing Marks & Center Crosshair
                MinimalProFramingHud(
                    modifier = Modifier.fillMaxSize()
                )
            }

            UiTemplateType.FUTURISTIC_GLASS -> {
                // Cyberpunk Neon Brackets & Digital Gyro Leveler
                CyberGlassHud(
                    modifier = Modifier.fillMaxSize()
                )
            }

            UiTemplateType.DSLR_PRO -> {
                // Top OLED Info Ribbon (Shutter, Aperture, ISO, EV Meter, Remaining shots)
                DslrOledInfoRibbon(
                    exposureCompensation = exposureCompensation,
                    manualIso = manualIso,
                    manualShutterSpeedNs = manualShutterSpeedNs,
                    storageStats = storageStats,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp)
                )
            }

            UiTemplateType.IMMERSIVE_EDGE -> {
                // Edge gesture indicators: Right for Zoom, exposure slider removed
                ImmersiveEdgeControls(
                    exposureCompensation = exposureCompensation,
                    onExposureChange = onExposureChange,
                    currentZoom = currentZoom,
                    onZoomChange = onZoomChange,
                    enableExposure = false,
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                // Exposure slider removed; auto exposure operates internally
            }
        }
    }
}

/**
 * Google Pixel Style Vertical Exposure / Brightness Slider
 * Matches the reference screenshot: tactile vertical track with sun icon and smooth thumb pill.
 */
@Composable
fun PixelVerticalExposureSlider(
    exposureCompensation: Int,
    onExposureChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    val sliderHeight = 180.dp
    val minEv = -12 // corresponding to -4.0 EV in 1/3 steps
    val maxEv = 12  // corresponding to +4.0 EV in 1/3 steps

    // Fraction from 0.0 (bottom, -4 EV) to 1.0 (top, +4 EV)
    val fraction = ((exposureCompensation - minEv).toFloat() / (maxEv - minEv)).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .testTag("pixel_vertical_exposure_slider"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Live EV badge when dragging or active
        AnimatedVisibility(
            visible = isDragging || exposureCompensation != 0,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xCC1A1A1E),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                val evValue = exposureCompensation / 3.0f
                Text(
                    text = if (evValue >= 0f) "+%.1f".format(evValue) else "%.1f".format(evValue),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        // Tactile vertical slider container
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(sliderHeight)
                .clip(RoundedCornerShape(21.dp))
                .background(Color(0x551E1E24))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(21.dp))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val deltaFraction = -dragAmount / 350f
                            val newFraction = (fraction + deltaFraction).coerceIn(0f, 1f)
                            val newEv = (minEv + newFraction * (maxEv - minEv)).roundToInt()
                            if (newEv != exposureCompensation) {
                                onExposureChange(newEv)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Track line
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(sliderHeight - 44.dp)
                    .background(Color.White.copy(alpha = 0.25f))
            )

            // Sun icon at top
            Icon(
                imageVector = Icons.Outlined.WbSunny,
                contentDescription = "Brightness",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .size(16.dp)
            )

            // Tactile thumb pill
            val thumbOffsetY = (1f - fraction) * (180f - 48f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (thumbOffsetY + 16f).dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color(0xFF8AB4F8), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E2638))
                )
            }
        }
    }
}

/**
 * Minimalist Pro Leica-Style Live Telemetry Strip
 */
@Composable
fun MinimalProTelemetryStrip(
    exposureCompensation: Int,
    manualIso: Int?,
    manualShutterSpeedNs: Long?,
    storageStats: StorageStats,
    modifier: Modifier = Modifier
) {
    val evValue = exposureCompensation / 3.0f
    val evFormatted = if (evValue >= 0f) "+%.1f".format(evValue) else "%.1f".format(evValue)
    val isoFormatted = manualIso?.let { "ISO $it" } ?: "ISO AUTO"
    val shutterFormatted = manualShutterSpeedNs?.let { ns ->
        val sec = ns / 1_000_000_000.0
        if (sec >= 1.0) "%.1fs".format(sec) else "1/%d".format((1.0 / sec).roundToInt())
    } ?: "1/125s"

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xCC000000),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF)),
        modifier = modifier
            .testTag("minimal_pro_telemetry_strip")
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = isoFormatted,
                color = Color(0xFFE53935),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text("·", color = Color.Gray, fontSize = 11.sp)
            Text(
                text = shutterFormatted,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text("·", color = Color.Gray, fontSize = 11.sp)
            Text(
                text = "f/1.8",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text("·", color = Color.Gray, fontSize = 11.sp)
            Text(
                text = "EV $evFormatted",
                color = Color(0xFFFFD54F),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Minimalist Corner Framing HUD & Subtle Center Level Crosshair
 */
@Composable
fun MinimalProFramingHud(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.padding(24.dp)) {
        val w = size.width
        val h = size.height
        val cornerLen = 22.dp.toPx()
        val strokeColor = Color(0x66FFFFFF)
        val strokeWidth = 1.5.dp.toPx()

        // Top-Left corner
        drawLine(strokeColor, Offset(0f, 0f), Offset(cornerLen, 0f), strokeWidth)
        drawLine(strokeColor, Offset(0f, 0f), Offset(0f, cornerLen), strokeWidth)

        // Top-Right corner
        drawLine(strokeColor, Offset(w, 0f), Offset(w - cornerLen, 0f), strokeWidth)
        drawLine(strokeColor, Offset(w, 0f), Offset(w, cornerLen), strokeWidth)

        // Bottom-Left corner
        drawLine(strokeColor, Offset(0f, h), Offset(cornerLen, h), strokeWidth)
        drawLine(strokeColor, Offset(0f, h), Offset(0f, h - cornerLen), strokeWidth)

        // Bottom-Right corner
        drawLine(strokeColor, Offset(w, h), Offset(w - cornerLen, h), strokeWidth)
        drawLine(strokeColor, Offset(w, h), Offset(w, h - cornerLen), strokeWidth)

        // Center Level Crosshair
        val cx = w / 2f
        val cy = h / 2f
        val crossSize = 10.dp.toPx()
        drawLine(Color(0x55E53935), Offset(cx - crossSize, cy), Offset(cx + crossSize, cy), 1.dp.toPx())
        drawLine(Color(0x55E53935), Offset(cx, cy - crossSize), Offset(cx, cy + crossSize), 1.dp.toPx())
    }
}

/**
 * Minimal Pro EV Dial with click notches
 */
@Composable
fun MinimalProEvDial(
    exposureCompensation: Int,
    onExposureChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = listOf(6, 3, 0, -3, -6)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC111111))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("EV", color = Color(0xFFE53935), fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        steps.forEach { step ->
            val isSelected = (exposureCompensation / 3) == (step / 3)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFFE53935) else Color.Transparent)
                    .clickable { onExposureChange(step) },
                contentAlignment = Alignment.Center
            ) {
                val label = if (step > 0) "+${step/3}" else "${step/3}"
                Text(
                    text = label,
                    color = if (isSelected) Color.White else Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Futuristic Cyber Glass HUD with Neon Brackets and Digital Gyro
 */
@Composable
fun CyberGlassHud(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.padding(20.dp)) {
        val w = size.width
        val h = size.height
        val neonCyan = Color(0xFF00E5FF)

        // Futuristic bracket corners
        val bLen = 28.dp.toPx()
        val sWidth = 2.dp.toPx()

        // Top Left
        drawLine(neonCyan, Offset(0f, 0f), Offset(bLen, 0f), sWidth)
        drawLine(neonCyan, Offset(0f, 0f), Offset(0f, bLen), sWidth)
        drawCircle(neonCyan, radius = 3.dp.toPx(), center = Offset(bLen + 6f, 0f))

        // Top Right
        drawLine(neonCyan, Offset(w, 0f), Offset(w - bLen, 0f), sWidth)
        drawLine(neonCyan, Offset(w, 0f), Offset(w, bLen), sWidth)
        drawCircle(neonCyan, radius = 3.dp.toPx(), center = Offset(w - bLen - 6f, 0f))

        // Bottom Left
        drawLine(neonCyan, Offset(0f, h), Offset(bLen, h), sWidth)
        drawLine(neonCyan, Offset(0f, h), Offset(0f, h - bLen), sWidth)

        // Bottom Right
        drawLine(neonCyan, Offset(w, h), Offset(w - bLen, h), sWidth)
        drawLine(neonCyan, Offset(w, h), Offset(w, h - bLen), sWidth)

        // Digital Gyro Horizon Target
        val cx = w / 2f
        val cy = h / 2f
        drawCircle(neonCyan.copy(alpha = 0.35f), radius = 32.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
        drawLine(neonCyan.copy(alpha = 0.6f), Offset(cx - 50.dp.toPx(), cy), Offset(cx - 20.dp.toPx(), cy), 2.dp.toPx())
        drawLine(neonCyan.copy(alpha = 0.6f), Offset(cx + 20.dp.toPx(), cy), Offset(cx + 50.dp.toPx(), cy), 2.dp.toPx())
    }
}

/**
 * Cyber Exposure Slider with Neon Glow
 */
@Composable
fun CyberExposureSlider(
    exposureCompensation: Int,
    onExposureChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val neonCyan = Color(0xFF00E5FF)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC050A14))
            .border(1.5.dp, neonCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.FlashOn,
            contentDescription = null,
            tint = neonCyan,
            modifier = Modifier.size(16.dp)
        )
        IconButton(
            onClick = { onExposureChange((exposureCompensation + 2).coerceAtMost(12)) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(Icons.Default.Add, null, tint = neonCyan, modifier = Modifier.size(16.dp))
        }
        Text(
            text = "%.1f".format(exposureCompensation / 3f),
            color = neonCyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
        IconButton(
            onClick = { onExposureChange((exposureCompensation - 2).coerceAtLeast(-12)) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(Icons.Default.Remove, null, tint = neonCyan, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * DSLR Top OLED Display Band
 */
@Composable
fun DslrOledInfoRibbon(
    exposureCompensation: Int,
    manualIso: Int?,
    manualShutterSpeedNs: Long?,
    storageStats: StorageStats,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFF0F1215),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF2A313D)),
        modifier = modifier
            .testTag("dslr_oled_info_ribbon")
            .padding(horizontal = 14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SHUTTER", color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text("1/250", color = Color(0xFFFFB300), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("APERTURE", color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text("F 2.8", color = Color(0xFFFFB300), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ISO", color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text("AUTO", color = Color(0xFFFFB300), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("EV", color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text("±0.0", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("REMAINING", color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text("[RAW] 2,410", color = Color(0xFF81C784), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * DSLR Exposure Ladder (+3 ... 0 ... -3)
 */
@Composable
fun DslrExposureLadder(
    exposureCompensation: Int,
    onExposureChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xE6121418))
            .border(1.dp, Color(0xFF333D4A), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("+3", color = if (exposureCompensation >= 9) Color(0xFFFFB300) else Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("+2", color = if (exposureCompensation in 4..8) Color(0xFFFFB300) else Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("+1", color = if (exposureCompensation in 1..3) Color(0xFFFFB300) else Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (exposureCompensation == 0) Color(0xFFFFB300) else Color.Gray))
        Text("-1", color = if (exposureCompensation in -3..-1) Color(0xFFFFB300) else Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("-2", color = if (exposureCompensation in -8..-4) Color(0xFFFFB300) else Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("-3", color = if (exposureCompensation <= -9) Color(0xFFFFB300) else Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Immersive Edge Gestures and Touch Zones
 */
@Composable
fun ImmersiveEdgeControls(
    exposureCompensation: Int,
    onExposureChange: (Int) -> Unit,
    currentZoom: Float,
    onZoomChange: (Float) -> Unit,
    enableExposure: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Left Edge Gesture Zone for Brightness (omitted when enableExposure is false)
        if (enableExposure) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(0.5f)
                    .width(40.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            val delta = -dragAmount / 30f
                            val newEv = (exposureCompensation + delta.roundToInt()).coerceIn(-12, 12)
                            onExposureChange(newEv)
                        }
                    }
            )
        }

        // Right Edge Gesture Zone for Zoom
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(0.5f)
                .width(40.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val delta = -dragAmount / 80f
                        val newZoom = (currentZoom + delta).coerceIn(0.5f, 10.0f)
                        onZoomChange(newZoom)
                    }
                }
        )
    }
}
