package com.example.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.camera.engine.VideoAdjustmentsPipeline
import com.example.camera.model.VideoAdjustments
import kotlin.math.max

/**
 * Spatial Video Effects Viewfinder Overlay.
 * When running on Android 13+ (including Android 16), all spatial effects (Film Grain, Vignette,
 * Soft Light, Bloom, Flash, Halation) and tonal adjustments are executed 100% on the GPU
 * via the AGSL RuntimeShader directly on the TextureView preview.
 *
 * For legacy Android versions (< API 33), this provides a lightweight hardware-accelerated
 * Brush fallback with ZERO CPU per-frame loops or allocations.
 */
@Composable
fun VideoAdjustmentsViewfinderOverlay(
    adjustments: VideoAdjustments,
    modifier: Modifier = Modifier
) {
    // If GPU RuntimeShader is active on the TextureView, all spatial effects and grain
    // are rendered directly on the GPU in a single pass. Skip redundant Compose drawing.
    if (VideoAdjustmentsPipeline.isGpuShaderSupported) {
        return
    }

    if (adjustments.isDefault || !VideoAdjustmentsPipeline.hasSpatialEffects(adjustments)) {
        return
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val radius = max(w, h) * 0.75f

        // 1. Spatial Vignette (GPU Radial Gradient)
        val vignette = adjustments.vignette
        if (vignette > 0f) {
            val vAlpha = (vignette / 100f).coerceIn(0f, 0.92f)
            val vignetteBrush = Brush.radialGradient(
                0.0f to Color.Transparent,
                0.55f to Color.Black.copy(alpha = vAlpha * 0.35f),
                1.0f to Color.Black.copy(alpha = vAlpha),
                center = center,
                radius = radius
            )
            drawRect(
                brush = vignetteBrush,
                size = size,
                blendMode = BlendMode.Multiply
            )
        }

        // 2. Soft Light / Optical Pro-Mist Diffusion (GPU Radial Gradient)
        val softLight = adjustments.lightFxSoftLight
        if (softLight > 0f) {
            val sAlpha = (softLight / 100f) * 0.18f
            val softBrush = Brush.radialGradient(
                0.0f to Color(0xFFFFFAF0).copy(alpha = sAlpha),
                0.8f to Color(0xFFF0E6D2).copy(alpha = sAlpha * 0.4f),
                1.0f to Color.Transparent,
                center = center,
                radius = radius
            )
            drawRect(
                brush = softBrush,
                size = size,
                blendMode = BlendMode.Screen
            )
        }

        // 3. Bloom Specular Highlight Diffusion (GPU Radial Gradient)
        val bloom = adjustments.lightFxBloom
        if (bloom > 0f) {
            val bAlpha = (bloom / 100f) * 0.22f
            val bloomBrush = Brush.radialGradient(
                0.0f to Color(0xFFFFFBEA).copy(alpha = bAlpha),
                0.45f to Color(0xFFFFD54F).copy(alpha = bAlpha * 0.35f),
                1.0f to Color.Transparent,
                center = Offset(w * 0.5f, h * 0.42f),
                radius = radius * 0.65f
            )
            drawRect(
                brush = bloomBrush,
                size = size,
                blendMode = BlendMode.Screen
            )
        }

        // 4. Anamorphic Flash Streak (GPU Linear Gradient)
        val flash = adjustments.lightFxFlash
        if (flash > 0f) {
            val fAlpha = (flash / 100f) * 0.35f
            val streakHeight = 24f + (flash / 100f) * 40f
            val streakY = h * 0.48f

            drawRect(
                brush = Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    0.35f to Color(0xFF90CAF9).copy(alpha = fAlpha * 0.5f),
                    0.50f to Color.White.copy(alpha = fAlpha),
                    0.65f to Color(0xFF90CAF9).copy(alpha = fAlpha * 0.5f),
                    1.0f to Color.Transparent
                ),
                topLeft = Offset(0f, streakY - streakHeight / 2f),
                size = Size(w, streakHeight),
                blendMode = BlendMode.Screen
            )
        }

        // 5. Halation (Emulsion red edge bleed)
        val halation = adjustments.textureHalation
        if (halation > 0f) {
            val hAlpha = (halation / 100f) * 0.16f
            drawRect(
                brush = Brush.radialGradient(
                    0.0f to Color.Transparent,
                    0.65f to Color(0xFFFF1744).copy(alpha = hAlpha * 0.4f),
                    1.0f to Color(0xFFFF1744).copy(alpha = hAlpha),
                    center = center,
                    radius = radius
                ),
                size = size,
                blendMode = BlendMode.Screen
            )
        }
    }
}
