package com.example.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.CinemaConfig
import com.example.camera.model.ZebraThreshold

/**
 * Cinema Viewfinder Assistance Overlays:
 * 1. Live Luminance Waveform Parade
 * 2. Focus Peaking highlight boundary
 * 3. Exposure Zebras (70 IRE / 100 IRE)
 */
@Composable
fun CinemaAssistOverlays(
    cinemaConfig: CinemaConfig,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 1. Exposure Zebras Overlay
        if (cinemaConfig.zebraThreshold != ZebraThreshold.OFF) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val threshold = cinemaConfig.zebraThreshold.thresholdIre
                val stripeSpacing = 24.dp.toPx()
                val strokeWidth = 2.dp.toPx()

                // Draw diagonal indicator stripes in the upper exposure highlight zones
                val pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                val color = if (threshold == 100) Color(0xFFFF5252).copy(alpha = 0.55f) else Color(0xFFFFD54F).copy(alpha = 0.45f)

                // Top highlight warning band
                var x = -size.height
                while (x < size.width + size.height) {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x + size.height * 0.35f, size.height * 0.35f),
                        strokeWidth = strokeWidth,
                        pathEffect = pathEffect
                    )
                    x += stripeSpacing
                }
            }
        }

        // 2. Focus Peaking Viewfinder Indicators
        if (cinemaConfig.isFocusPeakingEnabled) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width * 0.28f
                // Focus peaking reticle with high-contrast peaking green
                val peakingColor = Color(0xFF00E676).copy(alpha = 0.85f)
                drawCircle(
                    color = peakingColor,
                    radius = radius,
                    center = center,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                )

                // Crosshairs
                val chLen = 20.dp.toPx()
                drawLine(peakingColor, Offset(center.x - chLen, center.y), Offset(center.x + chLen, center.y), strokeWidth = 1.5.dp.toPx())
                drawLine(peakingColor, Offset(center.x, center.y - chLen), Offset(center.x, center.y + chLen), strokeWidth = 1.5.dp.toPx())
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 70.dp, start = 16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .border(1.dp, Color(0xFF00E676), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "PEAKING ACTIVE",
                    color = Color(0xFF00E676),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 3. Miniature Real-Time Luminance Waveform Box
        if (cinemaConfig.isWaveformEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 100.dp, end = 16.dp)
                    .size(width = 130.dp, height = 75.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xD90A0A0E))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(4.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Grid lines for 100 IRE, 70 IRE, 0 IRE
                    val strokeGrid = 0.8.dp.toPx()
                    drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, 0f), Offset(w, 0f), strokeWidth = strokeGrid)
                    drawLine(Color(0xFFFFD54F).copy(alpha = 0.35f), Offset(0f, h * 0.30f), Offset(w, h * 0.30f), strokeWidth = strokeGrid)
                    drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, h), Offset(w, h), strokeWidth = strokeGrid)

                    // Simulated live luminance parade
                    val path = Path()
                    val step = w / 32f
                    path.moveTo(0f, h * 0.7f)
                    for (i in 0..32) {
                        val px = i * step
                        val wave = kotlin.math.sin(i * 0.45f) * (h * 0.25f)
                        val py = (h * 0.55f + wave).coerceIn(h * 0.1f, h * 0.9f)
                        path.lineTo(px, py.toFloat())
                    }
                    drawPath(
                        path,
                        color = Color(0xFF00E5FF).copy(alpha = 0.80f),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }

                Text(
                    text = "WAVEFORM",
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp)
                )
            }
        }

        // 4. Cinema Pipeline Status Badge
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 70.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC000000))
                .border(1.dp, Color(0xFFFFD54F).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFFFFD54F))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${cinemaConfig.colorProfile.label.uppercase()} · ${cinemaConfig.logBitDepth.label}",
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
