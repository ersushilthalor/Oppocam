package com.example.camera.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val HALF_CIRCLE_TOTAL_TICKS = 100
const val HALF_CIRCLE_TRACK_WIDTH_MULTIPLIER = 1.5f

/**
 * Translucent, arched zoom ruler for the Oppo-style camera UI.
 *
 * The center readout stays fixed while the scale slides underneath it, so this
 * replaces the old horizontal pill without changing the camera's zoom callback.
 * The range is supplied by the camera UI and should already be limited to the
 * real lens minimum and the device-supported maximum (no higher than 10x).
 */
@Composable
fun HalfCircleZoomSlider(
    currentZoom: Float,
    minZoom: Float = 0.5f,
    maxZoom: Float = 10.0f,
    onZoomChange: (Float) -> Unit,
    onClose: () -> Unit,
    isExternalDragging: Boolean = false,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var autoHideJob by remember { mutableStateOf<Job?>(null) }
    val onCloseState = rememberUpdatedState(onClose)

    fun restartAutoHideTimer() {
        autoHideJob?.cancel()
        autoHideJob = coroutineScope.launch {
            delay(4000L)
            onCloseState.value()
        }
    }

    LaunchedEffect(Unit) { restartAutoHideTimer() }
    DisposableEffect(Unit) {
        onDispose { autoHideJob?.cancel() }
    }

    val safeMinZoom = minZoom.coerceAtLeast(0.3f)
    val safeMaxZoom = maxZoom.coerceAtLeast(safeMinZoom + 0.05f)
    val density = LocalDensity.current
    val tickStrokePx = with(density) { 1.dp.toPx() }
    val majorTickLengthPx = with(density) { 15.dp.toPx() }
    val minorTickLengthPx = with(density) { 8.dp.toPx() }
    val labelOffsetPx = with(density) { 7.dp.toPx() }
    val arcBorderPx = with(density) { 1.dp.toPx() }
    val centerTextPadding = 22.dp
    val onZoomChangeState = rememberUpdatedState(onZoomChange)
    val minZoomState = rememberUpdatedState(safeMinZoom)
    val maxZoomState = rememberUpdatedState(safeMaxZoom)

    var isDragging by remember { mutableStateOf(false) }
    var activeZoom by remember {
        mutableFloatStateOf(currentZoom.coerceIn(safeMinZoom, safeMaxZoom))
    }
    var lastUserDragEndTimeMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(safeMinZoom, safeMaxZoom) {
        activeZoom = activeZoom.coerceIn(safeMinZoom, safeMaxZoom)
    }

    LaunchedEffect(currentZoom, isExternalDragging) {
        if (isExternalDragging) {
            restartAutoHideTimer()
            activeZoom = currentZoom.coerceIn(safeMinZoom, safeMaxZoom)
            lastUserDragEndTimeMs = SystemClock.uptimeMillis()
        } else if (!isDragging) {
            val isWithinCooldown =
                SystemClock.uptimeMillis() - lastUserDragEndTimeMs < 220L
            if (!isWithinCooldown) {
                activeZoom = currentZoom.coerceIn(safeMinZoom, safeMaxZoom)
            }
        }
    }

    val activeZoomState = rememberUpdatedState(activeZoom)

    Box(
        modifier = modifier
            .widthIn(min = 280.dp, max = 380.dp)
            .fillMaxWidth()
            .height(220.dp)
            .testTag("half_circle_zoom_slider")
            .pointerInput(HALF_CIRCLE_TOTAL_TICKS) {
                val totalRulerWidthPx = size.width * HALF_CIRCLE_TRACK_WIDTH_MULTIPLIER
                val dragThresholdPx = 1.5f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    restartAutoHideTimer()
                    val downX = down.position.x
                    var lastX = downX
                    var hasDragged = false
                    val pointerId = down.id
                    var accumulatedNorm = zoomToNormalizedLog(
                        activeZoomState.value,
                        minZoomState.value,
                        maxZoomState.value
                    )

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                            ?: break

                        if (!change.pressed) {
                            change.consume()
                            break
                        }

                        val currentX = change.position.x
                        val totalDx = currentX - downX

                        if (!hasDragged && abs(totalDx) >= dragThresholdPx) {
                            hasDragged = true
                            isDragging = true
                            val minZ = minZoomState.value
                            val maxZ = maxZoomState.value
                            val deltaNorm =
                                (-totalDx * ZOOM_SLIDER_DRAG_SENSITIVITY) / totalRulerWidthPx
                            accumulatedNorm = (accumulatedNorm + deltaNorm).coerceIn(0f, 1f)
                            val newZoom = normalizedToZoomLog(accumulatedNorm, minZ, maxZ)
                            activeZoom = newZoom
                            lastX = currentX
                            change.consume()
                            restartAutoHideTimer()
                            onZoomChangeState.value(newZoom)
                        } else if (hasDragged) {
                            val stepDx = currentX - lastX
                            lastX = currentX
                            if (abs(stepDx) > 0.01f) {
                                val minZ = minZoomState.value
                                val maxZ = maxZoomState.value
                                val deltaNorm =
                                    (-stepDx * ZOOM_SLIDER_DRAG_SENSITIVITY) / totalRulerWidthPx
                                accumulatedNorm = (accumulatedNorm + deltaNorm).coerceIn(0f, 1f)
                                val newZoom = normalizedToZoomLog(accumulatedNorm, minZ, maxZ)
                                activeZoom = newZoom
                                restartAutoHideTimer()
                                onZoomChangeState.value(newZoom)
                            }
                            change.consume()
                        }
                    }

                    isDragging = false
                    lastUserDragEndTimeMs = SystemClock.uptimeMillis()
                    restartAutoHideTimer()
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            // Use a true 180-degree circular arc, not a quadratic/parabolic curve.
            // The larger canvas gives the semicircle enough vertical room while keeping
            // its endpoints close to the viewfinder's left and right edges.
            val baseline = height - arcBorderPx
            val centerX = width / 2f
            val radius = width / 2f
            val trackWidth = width * HALF_CIRCLE_TRACK_WIDTH_MULTIPLIER
            val currentProgress = zoomToNormalizedLog(activeZoom, safeMinZoom, safeMaxZoom)

            fun curveY(x: Float): Float {
                val dx = (x.coerceIn(0f, width) - centerX)
                return baseline - sqrt((radius * radius - dx * dx).coerceAtLeast(0f))
            }

            fun tangentSlope(x: Float): Float {
                val dx = x.coerceIn(0f, width) - centerX
                val denominator = sqrt((radius * radius - dx * dx).coerceAtLeast(1f))
                return dx / denominator
            }

            // Fill the translucent dial beneath the exact upper semicircle.
            val circleBounds = Rect(
                left = centerX - radius,
                top = baseline - radius,
                right = centerX + radius,
                bottom = baseline + radius
            )
            val dome = Path().apply {
                moveTo(0f, baseline)
                arcTo(circleBounds, 180f, 180f, false)
                lineTo(width, height)
                lineTo(0f, height)
                close()
            }
            drawPath(dome, Color(0xB3444548))
            val arch = Path().apply {
                moveTo(0f, baseline)
                arcTo(circleBounds, 180f, 180f, false)
            }
            drawPath(
                path = arch,
                color = Color.White.copy(alpha = 0.24f),
                style = Stroke(width = arcBorderPx, cap = StrokeCap.Round)
            )

            val extraTicks = ceil(width / (trackWidth / HALF_CIRCLE_TOTAL_TICKS)).toInt()
            for (tick in -HALF_CIRCLE_TOTAL_TICKS..(HALF_CIRCLE_TOTAL_TICKS * 2)) {
                val tickProgress = tick.toFloat() / HALF_CIRCLE_TOTAL_TICKS
                val x = centerX + (tickProgress - currentProgress) * trackWidth
                if (x < -12f || x > width + 12f) continue

                val y = curveY(x)
                val slope = tangentSlope(x)
                val invLength = 1f / sqrt(slope * slope + 1f)
                // The tick's inward normal points down toward the center of the translucent dial.
                val normalX = -slope * invLength
                val normalY = invLength
                val isMajor = tick % 4 == 0
                val tickLength = if (isMajor) majorTickLengthPx else minorTickLengthPx
                val alpha = (1f - abs(x - centerX) / (width * 0.62f)).coerceIn(0.25f, 1f)
                val color = if (isMajor) {
                    Color.White.copy(alpha = 0.82f * alpha)
                } else {
                    Color.White.copy(alpha = 0.43f * alpha)
                }

                drawLine(
                    color = color,
                    start = Offset(x, y),
                    end = Offset(x + normalX * tickLength, y + normalY * tickLength),
                    strokeWidth = if (isMajor) tickStrokePx * 1.35f else tickStrokePx,
                    cap = StrokeCap.Round
                )
            }

            // Draw focal zoom markings on the curved scale; labels roll with the ticks.
            val labelZooms = listOf(
                safeMinZoom, 0.7f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f,
                5.0f, 7.0f, 8.0f, 10.0f, safeMaxZoom
            ).filter { it >= safeMinZoom && it <= safeMaxZoom }
                .distinct()
                .sorted()
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = with(density) { 12.sp.toPx() }
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                color = android.graphics.Color.WHITE
            }
            labelZooms.forEach { zoom ->
                val progress = zoomToNormalizedLog(zoom, safeMinZoom, safeMaxZoom)
                val x = centerX + (progress - currentProgress) * trackWidth
                if (x < 15.dp.toPx() || x > width - 15.dp.toPx()) return@forEach
                // Keep the active live readout clear at the top center of the arc.
                if (abs(x - centerX) < with(density) { 18.dp.toPx() }) return@forEach

                val y = curveY(x)
                val slope = tangentSlope(x)
                val invLength = 1f / sqrt(slope * slope + 1f)
                val normalX = -slope * invLength
                val normalY = invLength
                val labelX = x + normalX * (majorTickLengthPx + labelOffsetPx)
                val labelY = y + normalY * (majorTickLengthPx + labelOffsetPx)
                val rotation = (atan(slope) * 180.0 / PI).toFloat()
                val isCurrentMark = abs(progress - currentProgress) < 0.012f
                labelPaint.color = if (isCurrentMark) {
                    android.graphics.Color.rgb(255, 146, 24)
                } else {
                    android.graphics.Color.WHITE
                }
                val label = if (zoom < 1f) {
                    String.format(Locale.US, "%.1f", zoom)
                } else if (abs(zoom - zoom.roundToInt()) < 0.01f) {
                    zoom.roundToInt().toString()
                } else {
                    String.format(Locale.US, "%.1f", zoom)
                }

                nativeCanvas.save()
                nativeCanvas.rotate(rotation, labelX, labelY)
                nativeCanvas.drawText(label, labelX, labelY + labelPaint.textSize * 0.34f, labelPaint)
                nativeCanvas.restore()
            }

            // A thin, warm center marker keeps the live zoom position easy to read.
            val markerY = curveY(centerX)
            val markerSlope = tangentSlope(centerX)
            val markerInvLength = 1f / sqrt(markerSlope * markerSlope + 1f)
            val markerNormalX = -markerSlope * markerInvLength
            val markerNormalY = markerInvLength
            drawLine(
                color = Color(0xFFFF9418),
                start = Offset(x = centerX, y = markerY),
                end = Offset(
                    centerX + markerNormalX * with(density) { 19.dp.toPx() },
                    markerY + markerNormalY * with(density) { 19.dp.toPx() }
                ),
                strokeWidth = with(density) { 2.dp.toPx() },
                cap = StrokeCap.Round
            )
        }

        val zoomText = when {
            activeZoom < 1f -> String.format(Locale.US, "%.1fx", activeZoom)
            abs(activeZoom - activeZoom.roundToInt()) < 0.05f ->
                String.format(Locale.US, "%dx", activeZoom.roundToInt())
            else -> String.format(Locale.US, "%.1fx", activeZoom)
        }
        Text(
            text = zoomText,
            color = Color(0xFFFF9418),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = centerTextPadding)
                .testTag("half_circle_zoom_value")
        )
    }
}
