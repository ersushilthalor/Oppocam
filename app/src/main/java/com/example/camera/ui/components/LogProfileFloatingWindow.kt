package com.example.camera.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.*

/**
 * Dedicated Pro Video Log Profiles Floating Window.
 * Recreates the horizontal floating-stripe design shown in Screenshot_20261005-105536_YouTube~4.jpg:
 * - Tune options button on left
 * - Horizontally swipeable options: Natural → Flat Log → HDR Log → Rec.2020 → HLG10 → Apple Log 2 → Samsung APV
 * - Active selection highlighted in yellow/gold
 * - Right close 'X' button
 * - Preserves bit depth control (Linear, 8-bit, 10-bit) via the tune toggle
 */
@Composable
fun LogProfileFloatingWindow(
    config: CinemaConfig,
    capabilities: CinemaHardwareCapabilities,
    onSelectProfile: (CinemaColorProfile) -> Unit,
    onSelectBitDepth: (LogBitDepth) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isBitDepthExpanded by remember { mutableStateOf(false) }

    val profiles = remember {
        listOf(
            CinemaColorProfile.NATIVE to "Natural",
            CinemaColorProfile.S_LOG to "S-Log",
            CinemaColorProfile.N_LOG to "N-Log",
            CinemaColorProfile.HLG10 to "HLG10",
            CinemaColorProfile.HLG_2 to "HLG 2",
            CinemaColorProfile.APPLE_LOG_2 to "Apple Log 2",
            CinemaColorProfile.SAMSUNG_APV_LOG to "Samsung APV",
            CinemaColorProfile.PROCESSED_JPEG to "Photo JPEG"
        )
    }

    val currentPair = remember(config.colorProfile) {
        profiles.firstOrNull { it.first == config.colorProfile } ?: (CinemaColorProfile.NATIVE to "Natural")
    }

    HorizontalFloatingSelectorStripe(
        items = profiles,
        selectedItem = currentPair,
        itemLabel = { it.second },
        onSelectItem = { pair ->
            onSelectProfile(pair.first)
        },
        onDismiss = onDismissRequest,
        leadingIcon = Icons.Outlined.Tune,
        leadingIconContentDescription = "Log Bit Depth Options",
        onLeadingIconClick = { isBitDepthExpanded = !isBitDepthExpanded },
        isLeadingIconActive = isBitDepthExpanded,
        testTagPrefix = "log_profile_floating_window",
        modifier = modifier,
        expandedContent = {
            // Secondary compact drawer for Bit Depth Selection (Linear / 8-bit / 10-bit)
            val availableDepths = capabilities.getSupportedBitDepthsForCodec(config.codec)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Log Bit Depth",
                    color = FloatingSelectorDefaults.LabelColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    availableDepths.forEach { depth ->
                        val isSelected = config.logBitDepth == depth
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) FloatingSelectorDefaults.GoldAccent.copy(alpha = 0.22f)
                                    else Color.White.copy(alpha = 0.06f)
                                )
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) FloatingSelectorDefaults.GoldAccent else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectBitDepth(depth) }
                                .padding(vertical = 5.dp)
                                .testTag("log_depth_${depth.name}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (depth) {
                                    LogBitDepth.OFF -> "Linear"
                                    LogBitDepth.BIT_8 -> "8-bit"
                                    LogBitDepth.BIT_10 -> "10-bit"
                                },
                                color = if (isSelected) FloatingSelectorDefaults.GoldAccent else Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    )
}
