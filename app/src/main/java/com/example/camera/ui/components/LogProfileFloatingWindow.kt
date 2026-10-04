package com.example.camera.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
 * Displays all available Log Profiles (Natural, Flat Log, HDR Log, Rec.2020 HDR, HLG10 HDR, Apple Log 2, Samsung APV Log).
 * Allows direct selection and bit depth control.
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
    val accentColor = Color(0xFFFFD54F)

    FrostedGlassBox(
        modifier = modifier
            .wrapContentWidth()
            .widthIn(min = 280.dp, max = 340.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("log_profile_floating_window"),
        shape = RoundedCornerShape(22.dp),
        elevation = 16.dp,
        baseAlpha = 0.88f,
        baseTint = Color(0xFF0C0E17)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: Title, Active Badge & Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "LOG PROFILES",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accentColor.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = config.colorProfile.label.uppercase(),
                            color = accentColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onDismissRequest() }
                        .testTag("log_profile_window_close"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close Log Window",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Log Bit Depth Selector (8-bit vs 10-bit)
            val availableDepths = capabilities.getSupportedBitDepthsForCodec(config.codec)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                availableDepths.forEach { depth ->
                    val isSelected = config.logBitDepth == depth
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) accentColor.copy(alpha = 0.22f)
                                else Color.Transparent
                            )
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) accentColor.copy(alpha = 0.7f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectBitDepth(depth) }
                            .padding(vertical = 6.dp)
                            .testTag("log_depth_${depth.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (depth) {
                                LogBitDepth.OFF -> "Linear (Off)"
                                LogBitDepth.BIT_8 -> "8-bit Log"
                                LogBitDepth.BIT_10 -> "10-bit Log"
                            },
                            color = if (isSelected) accentColor else Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // List of Log Profiles
            val logProfiles = listOf(
                LogProfileItem(
                    profile = CinemaColorProfile.NATIVE,
                    title = "Natural (Native)",
                    subtitle = "True-to-life colors, balanced contrast & intelligent shadow recovery",
                    badge = "Standard"
                ),
                LogProfileItem(
                    profile = CinemaColorProfile.FLAT_LOG,
                    title = "Flat Log",
                    subtitle = "Logarithmic dynamic range curve optimized for color grading",
                    badge = "Grading"
                ),
                LogProfileItem(
                    profile = CinemaColorProfile.HDR_LOG,
                    title = "HDR Log",
                    subtitle = "Extended highlight latitude & shadow retention with natural roll-off",
                    badge = "HDR Log"
                ),
                LogProfileItem(
                    profile = CinemaColorProfile.REC_2020,
                    title = "Rec.2020 HDR",
                    subtitle = "ITU-R BT.2020 wide color gamut transfer curve with deep chroma",
                    badge = "BT.2020"
                ),
                LogProfileItem(
                    profile = CinemaColorProfile.HLG10,
                    title = "HLG10 HDR",
                    subtitle = "ARIB STD-B67 10-bit Hybrid Log-Gamma with Rec.2020 gamut",
                    badge = "ARIB B67"
                ),
                LogProfileItem(
                    profile = CinemaColorProfile.APPLE_LOG_2,
                    title = "Apple Log 2",
                    subtitle = "Wide-gamut log transfer curve with parabolic shadow retention",
                    badge = "Apple Log"
                ),
                LogProfileItem(
                    profile = CinemaColorProfile.SAMSUNG_APV_LOG,
                    title = "Samsung APV Log",
                    subtitle = "Advanced Professional Video Log with clean shadow-to-highlight roll-off",
                    badge = "APV Master"
                )
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                logProfiles.forEach { item ->
                    val isSelected = config.colorProfile == item.profile
                    Surface(
                        onClick = { onSelectProfile(item.profile) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) Color(0xFF1E2435) else Color.White.copy(alpha = 0.05f),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) accentColor else Color.White.copy(alpha = 0.10f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("log_profile_card_${item.profile.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.title,
                                        color = if (isSelected) accentColor else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) accentColor.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)
                                    ) {
                                        Text(
                                            text = item.badge,
                                            color = if (isSelected) accentColor else Color.White.copy(alpha = 0.6f),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.subtitle,
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }

                            if (isSelected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(accentColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.Black,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class LogProfileItem(
    val profile: CinemaColorProfile,
    val title: String,
    val subtitle: String,
    val badge: String
)
