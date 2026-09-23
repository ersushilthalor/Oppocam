package com.example.camera.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * Flagship Horizontal Ruler Zoom Slider matching the reference camera UI:
 * - Floating live zoom text in prominent gold/yellow (e.g. "5.0 x") centered above the capsule.
 * - Dark translucent horizontal pill with rounded corners.
 * - Continuous horizontal tick-mark ruler with major and minor markings.
 * - Prominent vertical yellow center indicator line marking the active zoom level.
 * - Circular dismiss ('✕') button on the right edge.
 * - Smooth logarithmic zoom mapping from minZoom (e.g. 0.5x) to maxZoom (e.g. 10x/20x/30x).
 */
@Composable
fun HorizontalRulerZoomSlider(
    currentZoom: Float,
    minZoom: Float = 0.5f,
    maxZoom: Float = 10.0f,
    onZoomChange: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var autoHideJob by remember { mutableStateOf<Job?>(null) }

    fun restartAutoHideTimer() {
        autoHideJob?.cancel()
        autoHideJob = coroutineScope.launch {
            delay(4000L) // Auto-hide after 4 seconds of inactivity
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        restartAutoHideTimer()
    }

    DisposableEffect(Unit) {
        onDispose {
            autoHideJob?.cancel()
        }
    }

    val safeMinZoom = minZoom.coerceAtLeast(0.3f)
    val safeMaxZoom = maxZoom.coerceAtLeast(safeMinZoom + 1.0f)

    val currentZoomState = rememberUpdatedState(currentZoom)
    val onZoomChangeState = rememberUpdatedState(onZoomChange)
    val safeMinZoomState = rememberUpdatedState(safeMinZoom)
    val safeMaxZoomState = rememberUpdatedState(safeMaxZoom)

    // Logarithmic zoom mapping for natural lens and digital zoom distribution
    fun zoomToNormalized(zoom: Float, minZ: Float, maxZ: Float): Float {
        val logMin = ln(minZ.toDouble())
        val logMax = ln(maxZ.toDouble())
        val logZ = ln(zoom.coerceIn(minZ, maxZ).toDouble())
        return ((logZ - logMin) / (logMax - logMin)).toFloat().coerceIn(0f, 1f)
    }

    fun normalizedToZoom(t: Float, minZ: Float, maxZ: Float): Float {
        val logMin = ln(minZ.toDouble())
        val logMax = ln(maxZ.toDouble())
        val logZ = logMin + t.coerceIn(0f, 1f) * (logMax - logMin)
        val rawZoom = exp(logZ).toFloat()
        // Maintain continuous Float precision (0.01x) without crude 0.1x gesture stepping
        val highPrecision = (rawZoom * 100f).roundToInt() / 100f
        return highPrecision.coerceIn(minZ, maxZ)
    }

    val density = LocalDensity.current
    val totalTicks = 90
    val tickSpacingDp = 7.dp
    val tickSpacingPx = with(density) { tickSpacingDp.toPx() }
    val totalRulerWidthPx = totalTicks * tickSpacingPx
    // High-sensitivity drag travel: comfortable single-stroke span across the full zoom range
    val dragTravelDistancePx = with(density) { 260.dp.toPx() }

    var accumulatedNorm by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .widthIn(min = 320.dp, max = 380.dp)
            .padding(horizontal = 8.dp)
            .testTag("horizontal_ruler_zoom_slider"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Live Zoom Indicator (formatted to 1 decimal place e.g. "5.0 x")
        Text(
            text = "%.1f x".format(currentZoom),
            color = Color(0xFFFFD54F),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .testTag("zoom_slider_value_text")
        )

        // 2. Translucent Ruler Capsule Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xCC18191E))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
                .padding(start = 12.dp, end = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Interactive Tick Ruler
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectTapGestures { tapOffset ->
                                restartAutoHideTimer()
                                val minZ = safeMinZoomState.value
                                val maxZ = safeMaxZoomState.value
                                val centerX = size.width / 2f
                                val deltaX = tapOffset.x - centerX
                                val currentNorm = zoomToNormalized(currentZoomState.value, minZ, maxZ)
                                val newNorm = (currentNorm + deltaX / dragTravelDistancePx).coerceIn(0f, 1f)
                                val newZoom = normalizedToZoom(newNorm, minZ, maxZ)
                                onZoomChangeState.value(newZoom)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    restartAutoHideTimer()
                                    val minZ = safeMinZoomState.value
                                    val maxZ = safeMaxZoomState.value
                                    accumulatedNorm = zoomToNormalized(currentZoomState.value, minZ, maxZ)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    restartAutoHideTimer()
                                    val minZ = safeMinZoomState.value
                                    val maxZ = safeMaxZoomState.value
                                    // Swipe right → left (dragAmount.x < 0) zooms in
                                    // Swipe left → right (dragAmount.x > 0) zooms out
                                    val deltaNorm = -dragAmount.x / dragTravelDistancePx
                                    accumulatedNorm = (accumulatedNorm + deltaNorm).coerceIn(0f, 1f)
                                    val newZoom = normalizedToZoom(accumulatedNorm, minZ, maxZ)
                                    if (abs(newZoom - currentZoomState.value) >= 0.005f) {
                                        onZoomChangeState.value(newZoom)
                                    }
                                },
                                onDragEnd = {
                                    restartAutoHideTimer()
                                },
                                onDragCancel = {
                                    restartAutoHideTimer()
                                }
                            )
                        }
                        .testTag("zoom_ruler_canvas_container")
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f

                        val currentNorm = zoomToNormalized(currentZoom, safeMinZoom, safeMaxZoom)
                        val currentScrollPx = currentNorm * totalRulerWidthPx

                        // Draw moving ticks
                        for (i in 0..totalTicks) {
                            val tickX = centerX - currentScrollPx + (i * tickSpacingPx)

                            // Only draw visible ticks inside canvas
                            if (tickX in -10f..(size.width + 10f)) {
                                val distFromCenter = abs(tickX - centerX) / (size.width / 2f)
                                val alpha = (1f - distFromCenter.pow(1.8f)).coerceIn(0f, 1f)

                                if (alpha > 0.02f) {
                                    val isMajor = (i % 5 == 0)
                                    val tickHeight = if (isMajor) 18.dp.toPx() else 11.dp.toPx()
                                    val strokeW = if (isMajor) 1.5.dp.toPx() else 1.0.dp.toPx()
                                    val tickColor = if (isMajor) {
                                        Color.White.copy(alpha = alpha * 0.95f)
                                    } else {
                                        Color.White.copy(alpha = alpha * 0.45f)
                                    }

                                    drawLine(
                                        color = tickColor,
                                        start = Offset(tickX, centerY - tickHeight / 2f),
                                        end = Offset(tickX, centerY + tickHeight / 2f),
                                        strokeWidth = strokeW,
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                        }

                        // Fixed Center Indicator (Yellow vertical bar matching reference image)
                        val indicatorHeight = 24.dp.toPx()
                        val indicatorWidth = 2.5.dp.toPx()
                        drawLine(
                            color = Color(0xFFFFD54F),
                            start = Offset(centerX, centerY - indicatorHeight / 2f),
                            end = Offset(centerX, centerY + indicatorHeight / 2f),
                            strokeWidth = indicatorWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Dismiss / Close Button ('✕')
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            onClose()
                        }
                        .testTag("zoom_slider_close_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Zoom Slider",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
