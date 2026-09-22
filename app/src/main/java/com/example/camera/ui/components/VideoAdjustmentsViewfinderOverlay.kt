package com.example.camera.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.camera.model.VideoAdjustments
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Real-time GPU/Canvas overlay rendering spatial video effects directly onto the Viewfinder
 * for Normal Video Mode (Vignette, Film Grain, Bloom, Soft Light, Flash, Halation).
 */
@Composable
fun VideoAdjustmentsViewfinderOverlay(
    adjustments: VideoAdjustments,
    modifier: Modifier = Modifier
) {
    if (adjustments.isDefault) return

    val infiniteTransition = rememberInfiniteTransition(label = "GrainAnimation")
    val frameSeed by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GrainSeed"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val radius = max(w, h) * 0.75f

        // 1. Spatial Vignette
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

        // 2. Soft Light / Optical Pro-Mist Diffusion
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

        // 3. Bloom Specular Highlight Diffusion
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

        // 4. Anamorphic Flash Streak
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

        // 5. Film Grain Synthesis
        val totalGrain = adjustments.grain + adjustments.textureFilmGrain
        if (totalGrain > 0f) {
            val gAlpha = (totalGrain / 100f).coerceIn(0f, 0.35f) * 0.45f
            val step = 4
            val rng = Random((frameSeed * 100f).toLong())

            // High-performance dotted noise grid
            val cols = (w / step).toInt().coerceAtMost(180)
            val rows = (h / step).toInt().coerceAtMost(320)
            val colStep = w / cols
            val rowStep = h / rows

            for (i in 0 until cols step 2) {
                for (j in 0 until rows step 2) {
                    if (rng.nextFloat() > 0.45f) {
                        val px = i * colStep + rng.nextFloat() * colStep
                        val py = j * rowStep + rng.nextFloat() * rowStep
                        val isLight = rng.nextBoolean()
                        val dotColor = if (isLight) Color.White.copy(alpha = gAlpha) else Color.Black.copy(alpha = gAlpha * 0.7f)
                        drawCircle(
                            color = dotColor,
                            radius = 1.2f,
                            center = Offset(px, py),
                            blendMode = if (isLight) BlendMode.Overlay else BlendMode.Darken
                        )
                    }
                }
            }
        }

        // 6. Halation (Emulsion red edge bleed)
        val halation = adjustments.textureHalation
        if (halation > 0f) {
            val hAlpha = (halation / 100f) * 0.16f
            drawRect(
                brush = Brush.radialGradient(
                    0.0f to Color.Transparent,
                    0.7f to Color(0xFFFF3333).copy(alpha = hAlpha * 0.4f),
                    1.0f to Color(0xFFFF1111).copy(alpha = hAlpha),
                    center = center,
                    radius = radius
                ),
                size = size,
                blendMode = BlendMode.Screen
            )
        }
    }
}
