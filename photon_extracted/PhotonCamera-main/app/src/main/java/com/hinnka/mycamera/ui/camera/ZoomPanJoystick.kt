package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * On-screen joystick for moving a digital zoom crop.
 * The thumb springs back to centre when released; the selected camera framing stays put.
 */
@Composable
internal fun ZoomPanJoystick(
    modifier: Modifier = Modifier,
    onPanBy: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val maxTravelPx = with(density) { 34.dp.toPx() }
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    val currentOnPanBy by rememberUpdatedState(onPanBy)

    // Keep panning while the thumb is held off-centre, rather than only while the finger moves.
    LaunchedEffect(isDragging) {
        while (isDragging) {
            val x = (knobOffset.x / maxTravelPx).coerceIn(-1f, 1f)
            val y = (knobOffset.y / maxTravelPx).coerceIn(-1f, 1f)
            if (abs(x) > 0.04f || abs(y) > 0.04f) {
                currentOnPanBy(x * 0.006f, y * 0.006f)
            }
            delay(16L)
        }
    }

    Box(
        modifier = modifier
            .size(116.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.72f))
            .border(1.5.dp, Color.White.copy(alpha = 0.88f), CircleShape)
            .pointerInput(maxTravelPx) {
                fun clampOffset(offset: Offset): Offset {
                    val distance = hypot(offset.x, offset.y)
                    if (distance <= maxTravelPx || distance == 0f) return offset
                    val scale = maxTravelPx / distance
                    return Offset(offset.x * scale, offset.y * scale)
                }
                detectDragGestures(
                    onDragStart = { start ->
                        knobOffset = clampOffset(start - Offset(size.width / 2f, size.height / 2f))
                        isDragging = true
                    },
                    onDragEnd = {
                        isDragging = false
                        knobOffset = Offset.Zero
                    },
                    onDragCancel = {
                        isDragging = false
                        knobOffset = Offset.Zero
                    },
                ) { change, dragAmount ->
                    change.consume()
                    knobOffset = clampOffset(knobOffset + dragAmount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("▲", modifier = Modifier.align(Alignment.TopCenter).padding(top = 7.dp), color = Color.White.copy(alpha = 0.86f), fontSize = 11.sp)
        Text("▼", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp), color = Color.White.copy(alpha = 0.86f), fontSize = 11.sp)
        Text("◀", modifier = Modifier.align(Alignment.CenterStart).padding(start = 7.dp), color = Color.White.copy(alpha = 0.86f), fontSize = 11.sp)
        Text("▶", modifier = Modifier.align(Alignment.CenterEnd).padding(end = 7.dp), color = Color.White.copy(alpha = 0.86f), fontSize = 11.sp)
        Box(
            modifier = Modifier
                .size(25.dp)
                .border(2.dp, Color.White.copy(alpha = 0.92f), CircleShape)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) },
        )
    }
}
