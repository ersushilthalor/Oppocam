package com.example.camera.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.SoundEffectConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

const val HALF_CIRCLE_TOTAL_TICKS = 80
const val HALF_CIRCLE_TRACK_WIDTH_MULTIPLIER = 1.5f

/**
 * Flagship Oppo-style semicircular zoom slider matching the reference UI:
 * - Large, genuine, smooth 180° circular arc (not a parabola or stretched ellipse).
 * - Translucent dark-grey frosted dome background revealing the live preview.
 * - Radial tick marks with clean hierarchy: major ticks at preset zooms, intermediate ticks,
 *   and fine minor tick marks pointing radially toward the circle center.
 * - Horizontal, upright numerical markings (0.5, 1, 2, 3, 5, 10) following the curvature.
 * - Top-center apex needle in vibrant Oppo Orange with matching bold live zoom readout (e.g. "20x" / "2x").
 * - Range strictly bounded to 0.5x – 10x with smooth, accurate drag scrubbing and haptic feedback.
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
    val hostView = LocalView.current

    val tickStrokePx = with(density) { 1.dp.toPx() }
    val majorTickStrokePx = with(density) { 1.5.dp.toPx() }
    val majorTickLengthPx = with(density) { 15.dp.toPx() }
    val mediumTickLengthPx = with(density) { 10.dp.toPx() }
    val minorTickLengthPx = with(density) { 6.5.dp.toPx() }
    val arcBorderPx = with(density) { 1.2.dp.toPx() }
    val centerNeedleLengthPx = with(density) { 20.dp.toPx() }
    val centerNeedleStrokePx = with(density) { 2.4.dp.toPx() }
    val centerTextTopPadding = 24.dp

    val onZoomChangeState = rememberUpdatedState(onZoomChange)
    val minZoomState = rememberUpdatedState(safeMinZoom)
    val maxZoomState = rememberUpdatedState(safeMaxZoom)

    var isDragging by remember { mutableStateOf(false) }
    var activeZoom by remember {
        mutableFloatStateOf(currentZoom.coerceIn(safeMinZoom, safeMaxZoom))
    }
    var lastUserDragEndTimeMs by remember { mutableLongStateOf(0L) }
    var lastSoundStep by remember {
        mutableIntStateOf((zoomToNormalizedLog(activeZoom, safeMinZoom, safeMaxZoom) * 40f).roundToInt())
    }
    var lastSoundTimeMs by remember { mutableLongStateOf(0L) }

    // Subtle Oppo-style camera click at discrete zoom intervals
    fun playZoomTickIfNeeded(zoom: Float) {
        val step = (zoomToNormalizedLog(zoom, safeMinZoom, safeMaxZoom) * 40f).roundToInt()
        if (step != lastSoundStep) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSoundTimeMs >= 45L) {
                hostView.playSoundEffect(SoundEffectConstants.CLICK)
                lastSoundTimeMs = now
            }
            lastSoundStep = step
        }
    }

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
            .fillMaxWidth()
            .height(205.dp)
            .testTag("half_circle_zoom_slider")
            .pointerInput(safeMinZoom, safeMaxZoom) {
                val dragThresholdPx = 1.5f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    restartAutoHideTimer()
                    val downX = down.position.x
                    val downY = down.position.y
                    var lastX = downX
                    var hasDragged = false
                    val pointerId = down.id

                    val minZ = minZoomState.value
                    val maxZ = maxZoomState.value
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val baseline = h - arcBorderPx
                    val cx = w / 2f
                    val radius = min(w / 2f - with(density) { 4.dp.toPx() }, baseline - with(density) { 20.dp.toPx() })
                    // Angular span covering the zoom range
                    val trackAngleSpan = 2.45f // ~140 degrees along the semicircle
                    val totalTrackWidthPx = radius * trackAngleSpan

                    var accumulatedNorm = zoomToNormalizedLog(
                        activeZoomState.value,
                        minZ,
                        maxZ
                    )

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                            ?: break

                        if (!change.pressed) {
                            // If released without significant drag, treat as direct arc tap
                            if (!hasDragged && abs(change.position.x - downX) < 12f && abs(change.position.y - downY) < 12f) {
                                val tapX = change.position.x
                                val tapY = change.position.y
                                val angleFromApex = atan2(tapX - cx, baseline - tapY)
                                if (abs(angleFromApex) < 1.35f) {
                                    val deltaFromTap = (angleFromApex / trackAngleSpan).coerceIn(-0.5f, 0.5f)
                                    val tappedNorm = (accumulatedNorm + deltaFromTap).coerceIn(0f, 1f)
                                    val newZoom = normalizedToZoomLog(tappedNorm, minZ, maxZ)
                                    activeZoom = newZoom
                                    playZoomTickIfNeeded(newZoom)
                                    onZoomChangeState.value(newZoom)
                                    change.consume()
                                }
                            }
                            break
                        }

                        val currentX = change.position.x
                        val totalDx = currentX - downX

                        if (!hasDragged && abs(totalDx) >= dragThresholdPx) {
                            hasDragged = true
                            isDragging = true
                            val deltaNorm = (-totalDx * ZOOM_SLIDER_DRAG_SENSITIVITY) / totalTrackWidthPx
                            accumulatedNorm = (accumulatedNorm + deltaNorm).coerceIn(0f, 1f)
                            val newZoom = normalizedToZoomLog(accumulatedNorm, minZ, maxZ)
                            activeZoom = newZoom
                            playZoomTickIfNeeded(newZoom)
                            lastX = currentX
                            change.consume()
                            restartAutoHideTimer()
                            onZoomChangeState.value(newZoom)
                        } else if (hasDragged) {
                            val stepDx = currentX - lastX
                            lastX = currentX
                            if (abs(stepDx) > 0.01f) {
                                val deltaNorm = (-stepDx * ZOOM_SLIDER_DRAG_SENSITIVITY) / totalTrackWidthPx
                                accumulatedNorm = (accumulatedNorm + deltaNorm).coerceIn(0f, 1f)
                                val newZoom = normalizedToZoomLog(accumulatedNorm, minZ, maxZ)
                                activeZoom = newZoom
                                playZoomTickIfNeeded(newZoom)
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
            val baseline = height - arcBorderPx
            val centerX = width / 2f
            // True geometric circle radius: identical width and height radii
            val radius = min(width / 2f - with(density) { 4.dp.toPx() }, baseline - with(density) { 20.dp.toPx() })

            // 1. Draw large, translucent dark-grey semicircular dome background
            val circleBounds = Rect(
                left = centerX - radius,
                top = baseline - radius,
                right = centerX + radius,
                bottom = baseline + radius
            )
            val domePath = Path().apply {
                moveTo(centerX - radius, baseline)
                arcTo(circleBounds, 180f, 180f, false)
                lineTo(centerX + radius, baseline)
                close()
            }
            // Translucent dark-grey glass fill matching Oppo reference screenshot
            drawPath(domePath, Color(0x8C1B1C22))

            // 2. Delicate top rim border along the smooth arc
            val rimPath = Path().apply {
                moveTo(centerX - radius, baseline)
                arcTo(circleBounds, 180f, 180f, false)
            }
            drawPath(
                path = rimPath,
                color = Color.White.copy(alpha = 0.22f),
                style = Stroke(width = arcBorderPx, cap = StrokeCap.Round)
            )

            val currentProgress = zoomToNormalizedLog(activeZoom, safeMinZoom, safeMaxZoom)
            val trackAngleSpan = 2.45f // Total angular spread of the zoom scale across the arc

            // 3. Draw fine minor and medium ticks along the true circular arc
            val totalTicks = HALF_CIRCLE_TOTAL_TICKS
            for (tick in 0..totalTicks) {
                val tickNorm = tick.toFloat() / totalTicks
                val phi = (tickNorm - currentProgress) * trackAngleSpan
                // Clip ticks outside the visible semicircle
                if (phi < -1.35f || phi > 1.35f) continue

                val x = centerX + radius * sin(phi)
                val y = baseline - radius * cos(phi)

                // Inward normal vector pointing radially toward the circle center
                val normalX = -sin(phi)
                val normalY = cos(phi)

                val isMedium = tick % 5 == 0
                val tickLength = if (isMedium) mediumTickLengthPx else minorTickLengthPx
                val tickAlpha = (1f - abs(phi) / 1.45f).coerceIn(0.25f, 1f)
                val color = if (isMedium) {
                    Color.White.copy(alpha = 0.68f * tickAlpha)
                } else {
                    Color.White.copy(alpha = 0.38f * tickAlpha)
                }

                drawLine(
                    color = color,
                    start = Offset(x, y),
                    end = Offset(x + normalX * tickLength, y + normalY * tickLength),
                    strokeWidth = if (isMedium) tickStrokePx * 1.2f else tickStrokePx,
                    cap = StrokeCap.Round
                )
            }

            // 4. Draw major ticks and upright numerical labels for primary focal points
            // Required labels: 0.5x, 1x, 2x, 3x, 5x, 10x
            val majorZoomValues = listOf(
                safeMinZoom,
                1.0f,
                2.0f,
                3.0f,
                5.0f,
                safeMaxZoom
            ).filter { it in safeMinZoom..safeMaxZoom }.distinct().sorted()

            val nativeCanvas = drawContext.canvas.nativeCanvas
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = with(density) { 13.sp.toPx() }
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                color = android.graphics.Color.WHITE
            }

            majorZoomValues.forEach { zoomVal ->
                val zoomNorm = zoomToNormalizedLog(zoomVal, safeMinZoom, safeMaxZoom)
                val phi = (zoomNorm - currentProgress) * trackAngleSpan
                if (phi < -1.35f || phi > 1.35f) return@forEach

                val x = centerX + radius * sin(phi)
                val y = baseline - radius * cos(phi)

                val normalX = -sin(phi)
                val normalY = cos(phi)

                // Draw prominent major tick
                val edgeFade = (1f - abs(phi) / 1.45f).coerceIn(0.3f, 1f)
                drawLine(
                    color = Color.White.copy(alpha = 0.88f * edgeFade),
                    start = Offset(x, y),
                    end = Offset(x + normalX * majorTickLengthPx, y + normalY * majorTickLengthPx),
                    strokeWidth = majorTickStrokePx,
                    cap = StrokeCap.Round
                )

                // Draw horizontal, upright numerical label inward from the major tick
                val labelDistancePx = majorTickLengthPx + with(density) { 9.dp.toPx() }
                val labelX = x + normalX * labelDistancePx
                val labelY = y + normalY * labelDistancePx

                // Smoothly fade out white scale label when under the orange center readout
                val centerProximityAlpha = (abs(phi) / 0.22f).coerceIn(0f, 1f)
                if (centerProximityAlpha > 0.05f) {
                    val labelText = when {
                        zoomVal < 1f -> String.format(Locale.US, "%.1f", zoomVal)
                        abs(zoomVal - zoomVal.roundToInt()) < 0.01f -> zoomVal.roundToInt().toString()
                        else -> String.format(Locale.US, "%.1f", zoomVal)
                    }
                    labelPaint.alpha = (235 * centerProximityAlpha * edgeFade).roundToInt().coerceIn(0, 255)
                    nativeCanvas.drawText(
                        labelText,
                        labelX,
                        labelY + labelPaint.textSize * 0.35f,
                        labelPaint
                    )
                }
            }

            // 5. Prominent Oppo Orange selected zoom indicator needle at the apex (top center)
            val apexX = centerX
            val apexY = baseline - radius
            drawLine(
                color = Color(0xFFFF9418),
                start = Offset(apexX, apexY),
                end = Offset(apexX, apexY + centerNeedleLengthPx),
                strokeWidth = centerNeedleStrokePx,
                cap = StrokeCap.Round
            )
        }

        // 6. Selected zoom value readout in vibrant Oppo Orange (e.g. "20x", "2x", "1x")
        // Positioned right beneath the center needle inside the translucent dome
        val zoomText = when {
            activeZoom < 1f -> String.format(Locale.US, "%.1fx", activeZoom)
            abs(activeZoom - activeZoom.roundToInt()) < 0.05f ->
                String.format(Locale.US, "%dx", activeZoom.roundToInt())
            else -> String.format(Locale.US, "%.1fx", activeZoom)
        }
        Text(
            text = zoomText,
            color = Color(0xFFFF9418),
            fontSize = 16.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = centerTextTopPadding)
                .testTag("half_circle_zoom_value")
        )
    }
}
