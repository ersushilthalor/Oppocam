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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

/**
 * Flagship Gesture-based Half-Circle Arc Zoom Slider.
 *
 * Provides a minimal, translucent frosted-glass curved HUD dial that reveals
 * upon horizontal swipe gestures across the viewfinder, smoothly tracking
 * continuous zoom across the full device capability range.
 */
@Composable
fun HalfCircleZoomSlider(
    visible: Boolean,
    currentZoom: Float,
    minZoom: Float = 0.5f,
    maxZoom: Float = 10.0f,
    onZoomChange: (Float) -> Unit,
    onZoomPresetTap: (Float) -> Unit,
    onInteraction: () -> Unit = {},
    onInteractionEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.88f),
        exit = fadeOut(tween(320)) + scaleOut(tween(320), targetScale = 0.92f),
        modifier = modifier
    ) {
        val density = LocalDensity.current
        val widthDp = 300.dp
        val heightDp = 130.dp

        val widthPx = with(density) { widthDp.toPx() }
        val heightPx = with(density) { heightDp.toPx() }

        // Arc Geometry
        val cxPx = widthPx / 2f
        val cyPx = heightPx + with(density) { 10.dp.toPx() }
        val rPx = with(density) { 105.dp.toPx() }

        val startAngle = 195f
        val sweepAngle = 150f

        // Available Presets within device range
        val presets = remember(minZoom, maxZoom) {
            val candidatePresets = listOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f, 10.0f)
            candidatePresets.filter { it in minZoom..maxZoom }
        }

        val safeMinZoom = minZoom.coerceAtLeast(0.1f)
        val safeMaxZoom = maxZoom.coerceAtLeast(safeMinZoom + 0.5f)

        fun zoomToProgress(zoom: Float): Float {
            val logMin = ln(safeMinZoom.toDouble())
            val logMax = ln(safeMaxZoom.toDouble())
            val logVal = ln(zoom.coerceIn(safeMinZoom, safeMaxZoom).toDouble())
            return ((logVal - logMin) / (logMax - logMin)).toFloat().coerceIn(0f, 1f)
        }

        fun progressToZoom(p: Float): Float {
            val clampedP = p.coerceIn(0f, 1f)
            val logMin = ln(safeMinZoom.toDouble())
            val logMax = ln(safeMaxZoom.toDouble())
            val logVal = logMin + clampedP * (logMax - logMin)
            return exp(logVal).toFloat().coerceIn(safeMinZoom, safeMaxZoom)
        }

        val currentProgress = zoomToProgress(currentZoom)
        val activeSweep = currentProgress * sweepAngle

        Box(
            modifier = Modifier
                .width(widthDp)
                .height(heightDp)
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(topStart = 150.dp, topEnd = 150.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                )
                .clip(RoundedCornerShape(topStart = 150.dp, topEnd = 150.dp, bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(Color(0xD90E131E)) // Translucent frosted deep charcoal glass
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.28f),
                            Color(0xFFFFD54F).copy(alpha = 0.20f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    ),
                    shape = RoundedCornerShape(topStart = 150.dp, topEnd = 150.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                )
                .pointerInput(safeMinZoom, safeMaxZoom, currentZoom) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onInteraction()
                            val angleRad = atan2(offset.y - cyPx, offset.x - cxPx)
                            var angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
                            if (angleDeg < 0) angleDeg += 360f
                            if (angleDeg in 185f..355f) {
                                val p = ((angleDeg - startAngle) / sweepAngle).coerceIn(0f, 1f)
                                val newZoom = progressToZoom(p)
                                val rounded = (newZoom * 10f).roundToInt() / 10f
                                onZoomChange(rounded)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onInteraction()
                            // Continuous swipe sensitivity:
                            // swipe right -> left (dragAmount.x < 0) = zoom in
                            // swipe left -> right (dragAmount.x > 0) = zoom out
                            if (abs(dragAmount.x) > abs(dragAmount.y) * 0.7f) {
                                val factor = 1.0f - (dragAmount.x / 240f)
                                val newZoom = (currentZoom * factor).coerceIn(safeMinZoom, safeMaxZoom)
                                val rounded = (newZoom * 10f).roundToInt() / 10f
                                onZoomChange(rounded)
                            } else {
                                val angleRad = atan2(change.position.y - cyPx, change.position.x - cxPx)
                                var angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
                                if (angleDeg < 0) angleDeg += 360f
                                if (angleDeg in 180f..360f) {
                                    val p = ((angleDeg - startAngle) / sweepAngle).coerceIn(0f, 1f)
                                    val newZoom = progressToZoom(p)
                                    val rounded = (newZoom * 10f).roundToInt() / 10f
                                    onZoomChange(rounded)
                                }
                            }
                        },
                        onDragEnd = { onInteractionEnd() },
                        onDragCancel = { onInteractionEnd() }
                    )
                }
                .testTag("half_circle_zoom_slider")
                .testTag("viewfinder_minimal_zoom_bar")
        ) {
            // 1. Curved Canvas Arc & Optical Ticks
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 3.dp.toPx()
                val arcTopLeft = Offset(cxPx - rPx, cyPx - rPx)
                val arcSize = Size(2f * rPx, 2f * rPx)

                // Inactive Background Arc Track
                drawArc(
                    color = Color.White.copy(alpha = 0.20f),
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Active Progress Glow Arc Track
                if (activeSweep > 0.5f) {
                    drawArc(
                        color = Color(0xFFFFD54F),
                        startAngle = startAngle,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth + 1.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Intermediate subtle ticks (25 ticks across the arc)
                val totalTicks = 24
                for (i in 0..totalTicks) {
                    val angleDeg = startAngle + (i.toFloat() / totalTicks) * sweepAngle
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val cosP = cos(angleRad).toFloat()
                    val sinP = sin(angleRad).toFloat()

                    val isQuarter = (i % 6 == 0)
                    val tickLen = if (isQuarter) 5.dp.toPx() else 3.dp.toPx()
                    val alpha = if (isQuarter) 0.40f else 0.20f

                    val p1 = Offset(cxPx + (rPx - tickLen) * cosP, cyPx + (rPx - tickLen) * sinP)
                    val p2 = Offset(cxPx + (rPx + tickLen) * cosP, cyPx + (rPx + tickLen) * sinP)

                    drawLine(
                        color = Color.White.copy(alpha = alpha),
                        start = p1,
                        end = p2,
                        strokeWidth = if (isQuarter) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                }

                // Major Preset Ticks
                presets.forEach { preset ->
                    val p = zoomToProgress(preset)
                    val angleDeg = startAngle + p * sweepAngle
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val cosP = cos(angleRad).toFloat()
                    val sinP = sin(angleRad).toFloat()

                    val isClosest = (currentZoom - preset).absoluteValue < 0.2f
                    val tickLen = 6.dp.toPx()

                    val p1 = Offset(cxPx + (rPx - tickLen) * cosP, cyPx + (rPx - tickLen) * sinP)
                    val p2 = Offset(cxPx + (rPx + tickLen) * cosP, cyPx + (rPx + tickLen) * sinP)

                    drawLine(
                        color = if (isClosest) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.70f),
                        start = p1,
                        end = p2,
                        strokeWidth = 2.dp.toPx()
                    )
                }

                // Glowing Indicator Thumb Bead
                val thumbAngleDeg = startAngle + activeSweep
                val thumbAngleRad = Math.toRadians(thumbAngleDeg.toDouble())
                val thumbX = cxPx + rPx * cos(thumbAngleRad).toFloat()
                val thumbY = cyPx + rPx * sin(thumbAngleRad).toFloat()
                val thumbOffset = Offset(thumbX, thumbY)

                drawCircle(
                    color = Color(0x38FFD54F),
                    radius = 11.dp.toPx(),
                    center = thumbOffset
                )
                drawCircle(
                    color = Color(0xFFFFD54F),
                    radius = 6.dp.toPx(),
                    center = thumbOffset
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = thumbOffset
                )
            }

            // 2. Preset Clickable Badges along the inner curve
            val chipRadiusDp = 76.dp
            val chipRadiusPx = with(density) { chipRadiusDp.toPx() }

            presets.forEach { preset ->
                val p = zoomToProgress(preset)
                val angleDeg = startAngle + p * sweepAngle
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val cosP = cos(angleRad).toFloat()
                val sinP = sin(angleRad).toFloat()

                val chipCenterX = cxPx + chipRadiusPx * cosP
                val chipCenterY = cyPx + chipRadiusPx * sinP

                val chipLeftDp = with(density) { (chipCenterX - with(density) { 17.dp.toPx() }).toDp() }
                val chipTopDp = with(density) { (chipCenterY - with(density) { 12.dp.toPx() }).toDp() }

                val isExactMatch = (currentZoom - preset).absoluteValue < 0.2f

                Box(
                    modifier = Modifier
                        .offset(x = chipLeftDp, y = chipTopDp)
                        .size(width = 34.dp, height = 24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isExactMatch) Color(0x66FFD54F) else Color(0x28FFFFFF)
                        )
                        .border(
                            width = if (isExactMatch) 1.dp else 0.5.dp,
                            color = if (isExactMatch) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onInteraction()
                            onZoomPresetTap(preset)
                            onInteractionEnd()
                        }
                        .testTag("preset_button_${preset}"),
                    contentAlignment = Alignment.Center
                ) {
                    val label = when {
                        preset == 0.5f -> "0.5×"
                        preset % 1.0f == 0f -> "${preset.toInt()}×"
                        else -> String.format(java.util.Locale.US, "%.1f×", preset)
                    }
                    Text(
                        text = label,
                        color = if (isExactMatch) Color(0xFFFFD54F) else Color.White,
                        fontSize = 10.sp,
                        fontWeight = if (isExactMatch) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 3. Central Prominent Zoom Readout Pill
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC090D14))
                    .border(1.dp, Color(0xFFFFD54F).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .testTag("zoom_readout_pill")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f×", currentZoom),
                        color = Color(0xFFFFD54F),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.45f))
                    )
                    Text(
                        text = when {
                            currentZoom < 0.9f -> "ULTRA WIDE"
                            currentZoom in 0.9f..1.9f -> "WIDE 1×"
                            currentZoom in 2.0f..4.9f -> "TELE 2×"
                            currentZoom >= 5.0f -> "SUPER TELE"
                            else -> "ZOOM"
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}
