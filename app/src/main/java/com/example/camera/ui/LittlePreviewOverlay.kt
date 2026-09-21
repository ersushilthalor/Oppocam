package com.example.camera.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.camera.model.BackgroundCameraStatus

/**
 * Picture-in-Picture Little Preview Overlay for Motorola Instant Camera Switching.
 *
 * Displays live hardware streams of background-prepared cameras:
 * - Ultra-Wide Little Preview: Live 0.5× stream while shooting on 1×.
 * - Front Camera Little Preview: Live selfie stream while shooting on rear.
 *
 * Tapping any preview triggers an instantaneous switch to that camera.
 */
@Composable
fun LittlePreviewOverlay(
    showUltraWidePreview: Boolean,
    showFrontPreview: Boolean,
    ultraWideStatus: BackgroundCameraStatus,
    frontStatus: BackgroundCameraStatus,
    onUltraWideSurfaceTextureAvailable: (SurfaceTexture?) -> Unit,
    onFrontSurfaceTextureAvailable: (SurfaceTexture?) -> Unit,
    onUltraWideClick: () -> Unit,
    onFrontClick: () -> Unit,
    onCloseUltraWidePreview: () -> Unit,
    onCloseFrontPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 96.dp, end = 16.dp, start = 16.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            // 1. Ultra-Wide Little Preview Card
            AnimatedVisibility(
                visible = showUltraWidePreview,
                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                exit = fadeOut() + scaleOut(targetScale = 0.85f)
            ) {
                LittlePreviewCard(
                    title = "0.5× ULTRA-WIDE",
                    subtitle = "Tap to switch",
                    tag = "ultrawide_little_preview",
                    status = ultraWideStatus,
                    onSurfaceTextureAvailable = onUltraWideSurfaceTextureAvailable,
                    onClick = onUltraWideClick,
                    onClose = onCloseUltraWidePreview
                )
            }

            // 2. Front Camera Little Preview Card
            AnimatedVisibility(
                visible = showFrontPreview,
                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                exit = fadeOut() + scaleOut(targetScale = 0.85f)
            ) {
                LittlePreviewCard(
                    title = "FRONT SELFIE",
                    subtitle = "Tap to switch",
                    tag = "front_little_preview",
                    status = frontStatus,
                    onSurfaceTextureAvailable = onFrontSurfaceTextureAvailable,
                    onClick = onFrontClick,
                    onClose = onCloseFrontPreview
                )
            }
        }
    }
}

@Composable
private fun LittlePreviewCard(
    title: String,
    subtitle: String,
    tag: String,
    status: BackgroundCameraStatus,
    onSurfaceTextureAvailable: (SurfaceTexture?) -> Unit,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(112.dp)
            .height(148.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141414))
            .border(
                1.5.dp,
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF00E676),
                        Color(0x66FFFFFF),
                        Color(0x22FFFFFF)
                    )
                ),
                RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .testTag(tag)
    ) {
        // Native TextureView rendering the background live stream directly
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            onSurfaceTextureAvailable(st)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            onSurfaceTextureAvailable(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top Gradient Scrim for readable badges
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xCC000000), Color.Transparent)
                    )
                )
        )

        // Header with Live Indicator & Dismiss Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Glowing Emerald Live Dot
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(Color(0xFF00E676), CircleShape)
                )
                Text(
                    text = "LIVE",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            // Close button to dismiss preview
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0x88000000))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close preview",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Bottom Gradient Scrim for title & instant switch hint
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xDD000000))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Text(
                    text = subtitle,
                    color = Color(0xFFB0BEC5),
                    fontSize = 8.sp,
                    maxLines = 1
                )
            }
        }
    }
}
