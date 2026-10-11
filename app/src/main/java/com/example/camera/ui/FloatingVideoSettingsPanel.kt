package com.example.camera.ui

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.CameraResolution
import com.example.camera.ui.components.FloatingSelectorDefaults
import com.example.camera.ui.components.HorizontalFloatingSelectorCard

/**
 * Video Mode Resolution & FPS Floating Window
 * Recreates the compact horizontal floating-card design:
 * - Rounded frosted glass card floating over the preview
 * - Row 1: Size → UHD → 2K → FHD → HD (8K completely removed, 2K added)
 * - Row 2: FPS → Shows only FPS options actually supported by selected resolution & camera configuration
 * - Row 3: Bottom centered descriptive text ("Ultra HD resolution", "2K QHD resolution", etc.)
 * - Active selections highlighted in yellow/gold
 */
@Composable
fun FloatingVideoSettingsPanel(
    isOpen: Boolean,
    currentResolution: CameraResolution?,
    currentFps: Int,
    isUltraStabilizationEnabled: Boolean = false,
    supportedFpsProvider: ((CameraResolution) -> List<Int>)? = null,
    onResolutionSelected: (CameraResolution) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onUltraStabilizationToggle: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUhd = currentResolution?.let { (it.width == 3840 || it.height == 3840) } == true
    val is2K = currentResolution?.let { (it.width == 2560 || it.height == 2560) } == true
    val isFhd = currentResolution?.let { (it.width == 1920 || it.height == 1920) } == true
    val isHd = currentResolution?.let { (it.width == 1280 || it.height == 1280) } == true
    val isSelectedUhd = isUhd || (!is2K && !isFhd && !isHd)

    val descriptionText = when {
        is2K -> "2K QHD resolution"
        isFhd -> "Full HD resolution"
        isHd -> "HD resolution"
        else -> "Ultra HD resolution"
    }

    val effectiveRes = currentResolution ?: CameraResolution(3840, 2160)
    val supportedFpsList = supportedFpsProvider?.invoke(effectiveRes) ?: listOf(60, 30)

    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(150)) +
                slideInVertically(initialOffsetY = { -it / 2 }),
        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(120)) +
                slideOutVertically(targetOffsetY = { -it / 2 }),
        modifier = modifier
    ) {
        HorizontalFloatingSelectorCard(
            modifier = Modifier.testTag("floating_video_settings_panel")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                // Row 1: Size -> UHD -> 2K -> FHD -> HD
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Size",
                        color = FloatingSelectorDefaults.LabelColor,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(46.dp)
                    )

                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val sizeOptions = listOf(
                            Triple("UHD", CameraResolution(3840, 2160), isSelectedUhd),
                            Triple("2K", CameraResolution(2560, 1440), is2K),
                            Triple("FHD", CameraResolution(1920, 1080), isFhd),
                            Triple("HD", CameraResolution(1280, 720), isHd)
                        )

                        sizeOptions.forEach { (label, res, isSelected) ->
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) FloatingSelectorDefaults.GoldAccent else FloatingSelectorDefaults.TextColor,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    letterSpacing = 0.2.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(bounded = false, radius = 22.dp)
                                        ) {
                                            val supportedForRes = supportedFpsProvider?.invoke(res) ?: listOf(60, 30)
                                            if (!supportedForRes.contains(currentFps)) {
                                                val fallbackFps = supportedForRes.firstOrNull() ?: 30
                                                onFpsSelected(fallbackFps)
                                            }
                                            onResolutionSelected(res)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 5.dp)
                                        .testTag("res_option_$label")
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 2: FPS -> Show only supported FPS options for the selected resolution & camera configuration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FPS",
                        color = FloatingSelectorDefaults.LabelColor,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(46.dp)
                    )

                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        supportedFpsList.forEach { fps ->
                            val isSelected = (currentFps == fps) || (!supportedFpsList.contains(currentFps) && fps == supportedFpsList.firstOrNull())
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$fps",
                                    color = if (isSelected) FloatingSelectorDefaults.GoldAccent else FloatingSelectorDefaults.TextColor,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    letterSpacing = 0.2.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(bounded = false, radius = 22.dp)
                                        ) {
                                            onFpsSelected(fps)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 5.dp)
                                        .testTag("fps_option_$fps")
                                )
                            }
                        }

                        // Align columns under Size options by filling any remaining slots
                        repeat(maxOf(0, 4 - supportedFpsList.size)) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Row 3: Descriptive text matching selected resolution
                Text(
                    text = descriptionText,
                    color = FloatingSelectorDefaults.DescColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.2.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("video_resolution_description")
                )
            }
        }
    }
}
