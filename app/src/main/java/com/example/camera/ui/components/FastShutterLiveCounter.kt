package com.example.camera.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Minimal center-viewfinder live frame counter for Fast Shutter continuous capture.
 * Displays only the simple number (1, 2, 3...) with zero banners, zero cards, zero animations.
 * Resets and disappears immediately upon release.
 */
@Composable
fun FastShutterLiveCounter(
    visible: Boolean,
    frameCount: Int,
    modifier: Modifier = Modifier
) {
    if (!visible || frameCount <= 0) return

    Box(
        modifier = modifier.testTag("fast_shutter_frame_counter"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$frameCount",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.85f),
                    offset = Offset(0f, 2f),
                    blurRadius = 6f
                )
            )
        )
    }
}
