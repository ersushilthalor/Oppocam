package com.example.camera.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Transparent floating EV (Exposure Compensation) adjustment overlay for Video Mode and Cinema Mode.
 * Appears above the viewfinder without blocking the camera preview and allows smooth horizontal
 * swipe/drag gestures to bias Camera2 Auto Exposure (CONTROL_AE_EXPOSURE_COMPENSATION) in real time.
 */
@Composable
fun FloatingEvControlWindow(
    currentEvIndex: Int,
    minEvIndex: Int,
    maxEvIndex: Int,
    evStepSize: Float,
    accentColor: Color = Color(0xFFFFD54F),
    onEvIndexChange: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val safeMinIndex = if (minEvIndex < maxEvIndex) minEvIndex else -6
    val safeMaxIndex = if (maxEvIndex > minEvIndex) maxEvIndex else 6
    val safeStep = if (evStepSize > 0.01f) evStepSize else (1f / 3f)

    val clampedIndex = currentEvIndex.coerceIn(safeMinIndex, safeMaxIndex)
    val evValue = clampedIndex * safeStep

    // Smooth continuous drag state so swiping feels 100% fluid without discrete jumps
    var continuousDragIndex by remember { mutableFloatStateOf(clampedIndex.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(clampedIndex, isDragging) {
        if (!isDragging) {
            continuousDragIndex = clampedIndex.toFloat()
        }
    }

    val displayedEvValue = if (isDragging) {
        (continuousDragIndex * safeStep)
    } else {
        evValue
    }

    val formattedEv = remember(displayedEvValue) {
        val rounded = (displayedEvValue * 10f).roundToInt() / 10f
        when {
            abs(rounded) < 0.05f -> "0.0 EV"
            rounded > 0f -> String.format(Locale.US, "+%.1f EV", rounded)
            else -> String.format(Locale.US, "%.1f EV", rounded)
        }
    }

    val isModified = clampedIndex != 0
    val valueHighlightColor by animateColorAsState(
        targetValue = if (isModified || isDragging) accentColor else Color.White,
        label = "evValueColor"
    )

    // Transparent floating glass container so the live camera preview remains clearly visible underneath
    Box(
        modifier = modifier
            .widthIn(min = 280.dp, max = 340.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0x9910131A),
                        Color(0x800A0C10)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        if (isModified) accentColor.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.28f),
                        Color.White.copy(alpha = 0.10f)
                    )
                ),
                shape = RoundedCornerShape(22.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("floating_ev_control_window")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Row: Title + AE Active Indicator + Current EV Readout + Reset/Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Exposure,
                        contentDescription = "Exposure Compensation",
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "EXPOSURE",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x334CAF50))
                            .border(0.8.dp, Color(0xFF81C784).copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = "AE AUTO",
                            color = Color(0xFF81C784),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.4.sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Quick Decrement button
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable {
                                val newIdx = (clampedIndex - 1).coerceIn(safeMinIndex, safeMaxIndex)
                                if (newIdx != clampedIndex) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onEvIndexChange(newIdx)
                                }
                            }
                            .testTag("floating_ev_minus_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = "Decrease EV",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(12.dp)
                        )
                    }

                    Text(
                        text = formattedEv,
                        color = valueHighlightColor,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("floating_ev_value_text")
                    )

                    // Quick Increment button
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable {
                                val newIdx = (clampedIndex + 1).coerceIn(safeMinIndex, safeMaxIndex)
                                if (newIdx != clampedIndex) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onEvIndexChange(newIdx)
                                }
                            }
                            .testTag("floating_ev_plus_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Increase EV",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(12.dp)
                        )
                    }

                    if (isModified) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.14f))
                                .clickable {
                                    continuousDragIndex = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onReset()
                                }
                                .testTag("floating_ev_reset_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Reset EV to 0",
                                tint = accentColor,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { onDismiss() }
                            .testTag("floating_ev_close_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close EV Control",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            // Interactive Horizontal Swipe/Drag Dial & Scale
            var sliderWidthPx by remember { mutableFloatStateOf(1f) }
            val totalSteps = (safeMaxIndex - safeMinIndex).coerceAtLeast(1)

            val animatedNormPosition by animateFloatAsState(
                targetValue = ((if (isDragging) continuousDragIndex else clampedIndex.toFloat()) - safeMinIndex.toFloat()) / totalSteps.toFloat(),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = if (isDragging) Spring.StiffnessHigh else Spring.StiffnessMediumLow
                ),
                label = "evDialNormPos"
            )

            val currentClampedRef by rememberUpdatedState(clampedIndex)
            val onEvIndexChangeRef by rememberUpdatedState(onEvIndexChange)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .onSizeChanged { size ->
                        sliderWidthPx = size.width.toFloat().coerceAtLeast(1f)
                    }
                    .pointerInput(safeMinIndex, safeMaxIndex) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downX = down.position.x
                            var lastX = downX
                            var hasDragged = false
                            val pointerId = down.id
                            val horizontalPaddingPx = 14.dp.toPx()
                            val usableWidth = (sliderWidthPx - horizontalPaddingPx * 2f).coerceAtLeast(1f)

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

                                if (!hasDragged && abs(totalDx) >= 1.5f) {
                                    hasDragged = true
                                    isDragging = true
                                    lastX = currentX
                                    change.consume()
                                } else if (hasDragged) {
                                    val dragAmount = currentX - lastX
                                    lastX = currentX
                                    val deltaIndex = (dragAmount / usableWidth) * (safeMaxIndex - safeMinIndex).toFloat()
                                    val updatedFloat = (continuousDragIndex + deltaIndex).coerceIn(
                                        safeMinIndex.toFloat(),
                                        safeMaxIndex.toFloat()
                                    )
                                    continuousDragIndex = updatedFloat
                                    val snappedIdx = updatedFloat.roundToInt().coerceIn(safeMinIndex, safeMaxIndex)
                                    if (snappedIdx != currentClampedRef) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onEvIndexChangeRef(snappedIdx)
                                    }
                                    change.consume()
                                }
                            }

                            if (hasDragged) {
                                isDragging = false
                                continuousDragIndex = currentClampedRef.toFloat()
                            } else {
                                // Tap on dial track to jump directly to tapped EV position
                                val fraction = ((downX - horizontalPaddingPx) / usableWidth).coerceIn(0f, 1f)
                                val targetFloat = safeMinIndex + fraction * (safeMaxIndex - safeMinIndex)
                                val newIdx = targetFloat.roundToInt().coerceIn(safeMinIndex, safeMaxIndex)
                                continuousDragIndex = newIdx.toFloat()
                                if (newIdx != currentClampedRef) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onEvIndexChangeRef(newIdx)
                                }
                            }
                        }
                    }
                    .testTag("floating_ev_dial_slider"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val padX = 14.dp.toPx()
                    val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
                    val centerY = size.height * 0.42f
                    val zeroNorm = ((0f - safeMinIndex.toFloat()) / totalSteps.toFloat()).coerceIn(0f, 1f)
                    val zeroX = padX + zeroNorm * usableW
                    val activeX = padX + animatedNormPosition.coerceIn(0f, 1f) * usableW

                    // Subtle track bar
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.14f),
                        topLeft = Offset(padX, centerY - 1.5.dp.toPx()),
                        size = Size(usableW, 3.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )

                    // Active bias fill from 0 EV to current EV
                    val fillStart = minOf(zeroX, activeX)
                    val fillEnd = maxOf(zeroX, activeX)
                    if (fillEnd - fillStart > 1f) {
                        drawRoundRect(
                            color = accentColor.copy(alpha = 0.85f),
                            topLeft = Offset(fillStart, centerY - 2.dp.toPx()),
                            size = Size(fillEnd - fillStart, 4.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )
                    }

                    // Draw ticks for each hardware EV step
                    for (idx in safeMinIndex..safeMaxIndex) {
                        val norm = (idx - safeMinIndex).toFloat() / totalSteps.toFloat()
                        val x = padX + norm * usableW
                        val evAtTick = idx * safeStep
                        val isZero = idx == 0
                        val isWholeEv = abs(evAtTick - evAtTick.roundToInt()) < 0.05f

                        val tickHalfHeight = when {
                            isZero -> 10.dp.toPx()
                            isWholeEv -> 7.dp.toPx()
                            else -> 4.dp.toPx()
                        }
                        val tickStrokeWidth = when {
                            isZero -> 2.2.dp.toPx()
                            isWholeEv -> 1.6.dp.toPx()
                            else -> 1.dp.toPx()
                        }
                        val isBetweenZeroAndActive = (idx in minOf(0, clampedIndex)..maxOf(0, clampedIndex))
                        val tickColor = when {
                            isZero -> Color.White
                            isBetweenZeroAndActive -> accentColor
                            isWholeEv -> Color.White.copy(alpha = 0.7f)
                            else -> Color.White.copy(alpha = 0.35f)
                        }

                        drawLine(
                            color = tickColor,
                            start = Offset(x, centerY - tickHalfHeight),
                            end = Offset(x, centerY + tickHalfHeight),
                            strokeWidth = tickStrokeWidth,
                            cap = StrokeCap.Round
                        )
                    }

                    // Current EV thumb indicator
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f),
                        radius = 9.5.dp.toPx(),
                        center = Offset(activeX, centerY)
                    )
                    drawCircle(
                        color = accentColor,
                        radius = 7.5.dp.toPx(),
                        center = Offset(activeX, centerY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = Offset(activeX, centerY)
                    )
                }

                // Scale labels along bottom of the dial (-2.0, -1.0, 0, +1.0, +2.0)
                val minEvVal = safeMinIndex * safeStep
                val maxEvVal = safeMaxIndex * safeStep
                val midNegVal = minEvVal / 2f
                val midPosVal = maxEvVal / 2f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val labels = listOf(
                        String.format(Locale.US, "%.1f", minEvVal),
                        String.format(Locale.US, "%.1f", midNegVal),
                        "0",
                        String.format(Locale.US, "+%.1f", midPosVal),
                        String.format(Locale.US, "+%.1f", maxEvVal)
                    )
                    labels.forEach { label ->
                        Text(
                            text = label,
                            color = if (label == "0") Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.6f),
                            fontSize = 9.5.sp,
                            fontWeight = if (label == "0") FontWeight.Bold else FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
