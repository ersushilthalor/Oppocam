package com.example.camera.jpegpipeline

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
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
import com.example.camera.ui.components.FrostedGlassBox

/**
 * Live Viewfinder HUD Indicator for JPEG Pipeline Video.
 *
 * Displays current photo-style ISP rendering profile and permits real-time switching
 * between iPhone Style, Samsung Style, OPPO Style, and Standard JPEG Pipeline Style.
 */
@Composable
fun JpegPipelineLiveIndicator(
    isEnabled: Boolean,
    isRecording: Boolean,
    currentProfile: JpegPipelineProfile,
    capabilities: JpegPipelineCapabilities,
    onSelectProfile: (JpegPipelineProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isEnabled) return

    var isExpanded by remember { mutableStateOf(false) }

    val dotColor = when {
        isRecording -> Color(0xFFFF5252) // Recording Red
        currentProfile == JpegPipelineProfile.IPHONE -> Color(0xFF64B5F6) // Apple blue/cyan
        currentProfile == JpegPipelineProfile.SAMSUNG -> Color(0xFF81C784) // Vivid Samsung green
        currentProfile == JpegPipelineProfile.OPPO -> Color(0xFFFFB74D) // Warm golden OPPO
        else -> Color(0xFFFFD54F) // Standard amber
    }

    Column(
        modifier = modifier.testTag("jpeg_pipeline_live_indicator"),
        horizontalAlignment = Alignment.End
    ) {
        // Pill Button
        FrostedGlassBox(
            shape = RoundedCornerShape(14.dp),
            baseAlpha = 0.70f,
            elevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Colored status indicator dot
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                Text(
                    text = if (isRecording) "JPEG REC" else "JPEG ISP",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                Text(
                    text = currentProfile.shortLabel,
                    color = dotColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Expanded Profile Selection Sheet
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            FrostedGlassBox(
                shape = RoundedCornerShape(18.dp),
                baseAlpha = 0.88f,
                elevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 260.dp, max = 310.dp)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "JPEG PIPELINE PROFILES",
                            color = Color(0xFFFFD54F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Sensor → ISP → MP4",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        text = "Camera photo-style rendering applied directly in YUV stream before video encoding.",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    JpegPipelineProfile.values().forEach { profile ->
                        val isSelected = profile == currentProfile
                        val profileColor = when (profile) {
                            JpegPipelineProfile.IPHONE -> Color(0xFF64B5F6)
                            JpegPipelineProfile.SAMSUNG -> Color(0xFF81C784)
                            JpegPipelineProfile.OPPO -> Color(0xFFFFB74D)
                            JpegPipelineProfile.STANDARD -> Color(0xFFFFD54F)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) profileColor.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
                                .border(
                                    width = if (isSelected) 1.dp else 0.5.dp,
                                    color = if (isSelected) profileColor.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.10f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    onSelectProfile(profile)
                                    isExpanded = false
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = profile.title,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Active",
                                            tint = profileColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = profile.subtitle,
                                    color = Color.White.copy(alpha = 0.60f),
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }

                    // ISP diagnostic summary
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "ISP: ${capabilities.tonemapStatusLabel} · ${capabilities.edgeStatusLabel}",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 12.sp
                        )
                    }
                }
            }
        }
    }
}
