package com.example.camera.hdr.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.hdr.model.AdaptiveHdrMode
import com.example.camera.hdr.model.HdrExposurePair
import com.example.camera.hdr.model.SceneAnalysisMetrics
import com.example.camera.ui.components.FrostedGlassBox
import java.util.Locale

/**
 * Professional, clean stock camera live-view indicator for Adaptive Dual-Exposure HDR Video.
 * Displays state (HDR AUTO / ON / ACTIVE) and concise photographic exposure metrics.
 */
@Composable
fun HdrLiveIndicator(
    mode: AdaptiveHdrMode,
    isRecording: Boolean,
    exposurePair: HdrExposurePair?,
    metrics: SceneAnalysisMetrics?,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (mode == AdaptiveHdrMode.OFF && !isRecording) return

    var isExpanded by remember { mutableStateOf(false) }

    val statusBadgeText = when {
        isRecording -> "HDR REC"
        mode == AdaptiveHdrMode.ALWAYS_ON -> "HDR ON"
        else -> "HDR AUTO"
    }

    val badgeColor = when {
        isRecording -> Color(0xFFFF5252) // Bright Recording Red
        mode == AdaptiveHdrMode.ALWAYS_ON -> Color(0xFFFFD54F) // Pro Yellow
        else -> Color(0xFF64B5F6) // Auto Blue
    }

    Column(
        modifier = modifier.testTag("hdr_live_indicator"),
        horizontalAlignment = Alignment.End
    ) {
        // Pill Button
        FrostedGlassBox(
            shape = RoundedCornerShape(14.dp),
            baseAlpha = 0.65f,
            elevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Colored dot
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(3.5.dp))
                        .background(badgeColor)
                )

                Text(
                    text = statusBadgeText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                if (exposurePair != null) {
                    Text(
                        text = "ΔEV ${String.format(Locale.US, "%.1f", exposurePair.evDelta)}",
                        color = Color(0xFFFFD54F),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Expanded Professional Telemetry Drawer (Pro camera style)
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            FrostedGlassBox(
                shape = RoundedCornerShape(14.dp),
                baseAlpha = 0.85f,
                elevation = 8.dp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(220.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Dual-Exposure Pipeline",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "60 → 30 FPS",
                            color = Color(0xFFFFD54F),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (metrics != null) {
                        MetricRow(
                            label = "Dynamic Range",
                            value = "${String.format(Locale.US, "%.1f", metrics.estimatedDynamicRangeEv)} EV"
                        )
                    }

                    if (exposurePair != null) {
                        MetricRow(
                            label = "Short A (Highlights)",
                            value = "${formatShutterSpeed(exposurePair.shortExposureNs)} · ISO ${exposurePair.shortIso}"
                        )
                        MetricRow(
                            label = "Long B (Shadows)",
                            value = "${formatShutterSpeed(exposurePair.longExposureNs)} · ISO ${exposurePair.longIso}"
                        )
                        MetricRow(
                            label = "EV Separation",
                            value = "+${String.format(Locale.US, "%.1f", exposurePair.evDelta)} EV"
                        )
                    }

                    MetricRow(
                        label = "Encoding",
                        value = "10-bit HEVC Main10"
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // Tap to cycle mode
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { onToggleMode() }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Tap to switch: ${mode.name}",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatShutterSpeed(exposureNs: Long): String {
    if (exposureNs <= 0) return "1/60"
    val secFraction = 1_000_000_000.0 / exposureNs
    return if (secFraction >= 1.0) {
        "1/${secFraction.toInt()}"
    } else {
        String.format(Locale.US, "%.1fs", exposureNs / 1_000_000_000.0)
    }
}
