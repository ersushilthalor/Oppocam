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
 * Recreates the exact compact horizontal floating-card design shown in Screenshot_20261005-105022_YouTube~2.jpg:
 * - Rounded frosted glass card floating over the preview
 * - Row 1: Size → 8K → UHD → FHD → HD
 * - Row 2: FPS → 60 → 30 (aligned right underneath 8K and UHD)
 * - Row 3: Bottom centered descriptive text ("Ultra HD resolution", etc.)
 * - Active selections highlighted in yellow/gold
 */
@Composable
fun FloatingVideoSettingsPanel(
    isOpen: Boolean,
    currentResolution: CameraResolution?,
    currentFps: Int,
    isUltraStabilizationEnabled: Boolean = false,
    selectedVideoPipeline: com.example.camera.videopipeline.VideoPipelineType = com.example.camera.videopipeline.VideoPipelineType.NORMAL,
    onResolutionSelected: (CameraResolution) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onUltraStabilizationToggle: () -> Unit = {},
    onVideoPipelineSelected: (com.example.camera.videopipeline.VideoPipelineType) -> Unit = {},
    onOpenCustomPipelineSettings: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val is8K = currentResolution?.let { (it.width == 7680 || it.height == 7680) } == true
    val isFhd = currentResolution?.let { (it.width == 1920 || it.height == 1920) } == true
    val isHd = currentResolution?.let { (it.width == 1280 || it.height == 1280) } == true
    val isUhd = (currentResolution?.let { (it.width == 3840 || it.height == 3840) } == true) || (!is8K && !isFhd && !isHd)

    val descriptionText = when {
        is8K -> "8K UHD resolution"
        isFhd -> "Full HD resolution"
        isHd -> "HD resolution"
        else -> "Ultra HD resolution"
    }

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
                // Row 1: Size -> 8K -> UHD -> FHD -> HD
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
                            Triple("8K", CameraResolution(7680, 4320), is8K),
                            Triple("UHD", CameraResolution(3840, 2160), isUhd),
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

                // Row 2: FPS -> 60 -> 30 (strictly aligned in columns under 8K and UHD)
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
                        // Slot 1 (under 8K): 60 FPS
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val is60 = currentFps == 60
                            Text(
                                text = "60",
                                color = if (is60) FloatingSelectorDefaults.GoldAccent else FloatingSelectorDefaults.TextColor,
                                fontSize = 14.5.sp,
                                fontWeight = if (is60) FontWeight.Bold else FontWeight.SemiBold,
                                letterSpacing = 0.2.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = false, radius = 22.dp)
                                    ) {
                                        onFpsSelected(60)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 5.dp)
                                    .testTag("fps_option_60")
                            )
                        }

                        // Slot 2 (under UHD): 30 FPS
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val is30 = currentFps != 60 // defaults to 30 as shown in reference
                            Text(
                                text = "30",
                                color = if (is30) FloatingSelectorDefaults.GoldAccent else FloatingSelectorDefaults.TextColor,
                                fontSize = 14.5.sp,
                                fontWeight = if (is30) FontWeight.Bold else FontWeight.SemiBold,
                                letterSpacing = 0.2.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = false, radius = 22.dp)
                                    ) {
                                        onFpsSelected(30)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 5.dp)
                                    .testTag("fps_option_30")
                            )
                        }

                        // Slot 3 (under FHD): Spacer for alignment
                        Spacer(modifier = Modifier.weight(1f))

                        // Slot 4 (under HD): Spacer for alignment
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Row 3: Descriptive text exactly matching reference screenshot
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
