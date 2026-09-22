package com.example.camera.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ultra-lightweight, non-blocking real-time frame counter overlay for Fast Shutter bursts.
 * Rendered at the exact optical center of the viewfinder without affecting camera preview smoothness.
 */
@Composable
fun FastShutterLiveCounter(
    visible: Boolean,
    frameCount: Int,
    targetFps: Int,
    isProcessing: Boolean = false,
    processedCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.75f),
        exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.85f),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.testTag("fast_shutter_live_counter_container")
        ) {
            Box(
                modifier = Modifier
                    .testTag("fast_shutter_live_counter")
                    .size(102.dp)
                    .clip(CircleShape)
                    .background(Color(0xE60D1117))
                    .border(
                        width = 3.dp,
                        color = if (isProcessing) Color(0xFF64B5F6) else Color(0xFFFFB300),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Subtle pulse ring during active sensor hold
                if (!isProcessing && frameCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(102.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .border(1.5.dp, Color(0x66FFB300), CircleShape)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (frameCount > 0) "$frameCount" else "●",
                        color = Color.White,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 40.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "${targetFps} FPS",
                        color = if (isProcessing) Color(0xFF64B5F6) else Color(0xFFFFB300),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Real-time burst mode status tag below circular counter
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xD9000000))
                    .border(
                        1.dp,
                        if (isProcessing) Color(0x6664B5F6) else Color(0x66FFB300),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                if (isProcessing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF64B5F6),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = if (frameCount > 0) "SAVING $processedCount/$frameCount" else "SAVING...",
                            color = Color(0xFF64B5F6),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                    }
                } else {
                    Text(
                        text = "RAW BURST",
                        color = Color(0xFFFFB300),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }
    }
}

