package com.camerapro.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.camerapro.camera.model.FloatingWindowGlassStyle
import kotlin.math.roundToInt

/**
 * Safely coerce a Float into [minimumValue, maximumValue].
 * If minimumValue > maximumValue (e.g. during scroll when bounds change rapidly),
 * the bounds are safely swapped/ordered so the range is never invalid or empty.
 */
fun Float.safeCoerceIn(minimumValue: Float, maximumValue: Float): Float {
    if (this.isNaN()) return minimumValue
    val safeMin = if (minimumValue <= maximumValue) minimumValue else maximumValue
    val safeMax = if (minimumValue <= maximumValue) maximumValue else minimumValue
    return this.coerceIn(safeMin, safeMax)
}

/**
 * Safely coerce an Int into [minimumValue, maximumValue].
 * If minimumValue > maximumValue, the bounds are safely swapped/ordered.
 */
fun Int.safeCoerceIn(minimumValue: Int, maximumValue: Int): Int {
    val safeMin = if (minimumValue <= maximumValue) minimumValue else maximumValue
    val safeMax = if (minimumValue <= maximumValue) maximumValue else minimumValue
    return this.coerceIn(safeMin, safeMax)
}

/**
 * Authentic Glass Container with Physical Glass Styling:
 * Controlled by Settings:
 * - Liquid Glass: dynamic organic light sheen, chromatic specular reflection, smooth liquid gloss
 * - Frosted Glass: soft diffuse frosted overlay, ambient diffusion, elegant translucent depth
 * - Transparent Glass: ultra-clear high-transparency glass, minimal tint, crisp fine hairline border
 * - Subtle Frost / Deep Frost / Solid Dark: tailored opacity and diffusion layers
 *
 * Real-time Blur Intensity & Transparency controls are applied directly to the floating-window
 * background & UI overlay in real-time, providing immediate visual feedback.
 */
@Composable
fun FrostedGlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    elevation: Dp = 16.dp,
    baseAlpha: Float? = null,
    baseTint: Color = Color(0xFF0F121C),
    borderWidth: Dp = 1.dp,
    borderColor: Color? = null,
    showTopHighlightRim: Boolean = true,
    blurStrengthOverride: Float? = null,
    applyWindowScale: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val appearance = LocalFloatingWindowAppearance.current
    val frameTick = BackdropBlurManager.frameTickState.longValue
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val effectiveScale = if (applyWindowScale) appearance.windowScale.safeCoerceIn(0.75f, 1.25f) else 1.0f
    val scaledDensity = remember(baseDensity, effectiveScale) {
        androidx.compose.ui.unit.Density(
            density = baseDensity.density * effectiveScale,
            fontScale = baseDensity.fontScale * effectiveScale
        )
    }

    val style = appearance.glassStyle

    // 1. Transparency factor (0.0 = completely opaque, 1.0 = highly transparent)
    val userTransparency = appearance.transparency.safeCoerceIn(0f, 1f)
    // Opacity modulation: 0.0 transparency -> 1.0x opacity; 1.0 transparency -> 0.10x opacity
    val opacityMultiplier = (1.0f - userTransparency * 0.90f)

    // Base substrate alpha influenced by both custom baseAlpha and user transparency
    val nominalBaseAlpha = baseAlpha ?: 0.85f
    val finalSubstrateAlpha = (nominalBaseAlpha * opacityMultiplier).safeCoerceIn(0.06f, 0.98f)

    // 2. Blur intensity (0.0 dp = razor clear, 50.0 dp = heavy frosted blur)
    val blurStrength = (blurStrengthOverride ?: appearance.blurStrength).safeCoerceIn(0f, 50f)
    val effectiveBlurDp = blurStrength.dp
    // Frosted glass physical light diffusion (scales directly with blur intensity)
    val blurDiffusionAlpha = ((blurStrength / 50f) * 0.38f * (0.35f + 0.65f * opacityMultiplier)).safeCoerceIn(0f, 0.42f)
    val blurGlowAlpha = ((blurStrength / 50f) * 0.22f).safeCoerceIn(0f, 0.25f)

    // Style-specific substrate tint opacity & specular gradients modulated by user transparency
    val (tintAlpha, sheenAlpha, borderBrush) = remember(style, userTransparency, borderColor, opacityMultiplier, finalSubstrateAlpha) {
        when (style) {
            FloatingWindowGlassStyle.TRANSPARENT_GLASS -> {
                val tAlpha = (0.28f * opacityMultiplier).safeCoerceIn(0.05f, 0.50f)
                val sAlpha = 0.10f * (0.3f + 0.7f * opacityMultiplier)
                val brush = if (borderColor != null) SolidColor(borderColor) else Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent
                    )
                )
                Triple(tAlpha, sAlpha, brush)
            }
            FloatingWindowGlassStyle.LIQUID_GLASS -> {
                val tAlpha = (finalSubstrateAlpha * 0.92f).safeCoerceIn(0.08f, 0.95f)
                val sAlpha = 0.24f * (0.4f + 0.6f * opacityMultiplier)
                val brush = if (borderColor != null) SolidColor(borderColor) else Brush.linearGradient(
                    colors = listOf(
                        Color(0x99FFD54F),
                        Color(0x66FFFFFF),
                        Color(0x18FFFFFF)
                    )
                )
                Triple(tAlpha, sAlpha, brush)
            }
            FloatingWindowGlassStyle.FROSTED_GLASS -> {
                val tAlpha = (finalSubstrateAlpha * 0.95f).safeCoerceIn(0.08f, 0.96f)
                val sAlpha = 0.18f * (0.4f + 0.6f * opacityMultiplier)
                val brush = if (borderColor != null) SolidColor(borderColor) else Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.48f),
                        Color.White.copy(alpha = 0.18f),
                        Color.White.copy(alpha = 0.05f)
                    )
                )
                Triple(tAlpha, sAlpha, brush)
            }
            FloatingWindowGlassStyle.SUBTLE_FROST -> {
                val tAlpha = (finalSubstrateAlpha * 0.80f).safeCoerceIn(0.06f, 0.90f)
                val sAlpha = 0.14f * (0.4f + 0.6f * opacityMultiplier)
                val brush = if (borderColor != null) SolidColor(borderColor) else Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.38f),
                        Color.White.copy(alpha = 0.14f),
                        Color.Transparent
                    )
                )
                Triple(tAlpha, sAlpha, brush)
            }
            FloatingWindowGlassStyle.DEEP_FROST -> {
                val tAlpha = (finalSubstrateAlpha * 1.05f).safeCoerceIn(0.10f, 0.98f)
                val sAlpha = 0.20f * (0.4f + 0.6f * opacityMultiplier)
                val brush = if (borderColor != null) SolidColor(borderColor) else Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.08f)
                    )
                )
                Triple(tAlpha, sAlpha, brush)
            }
            FloatingWindowGlassStyle.SOLID_DARK -> {
                val tAlpha = (finalSubstrateAlpha * 1.15f).safeCoerceIn(0.12f, 0.98f)
                val sAlpha = 0.08f * (0.4f + 0.6f * opacityMultiplier)
                val brush = if (borderColor != null) SolidColor(borderColor) else Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.25f),
                        Color.White.copy(alpha = 0.08f),
                        Color.Transparent
                    )
                )
                Triple(tAlpha, sAlpha, brush)
            }
        }
    }

    val compactHorizontalInset = if (applyWindowScale && effectiveScale < 1.0f) {
        ((1.0f - effectiveScale) * 64f).dp
    } else {
        0.dp
    }

    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides scaledDensity
    ) {
        Box(
            modifier = modifier
                .then(
                    if (compactHorizontalInset > 0.dp) {
                        Modifier.padding(horizontal = compactHorizontalInset)
                    } else {
                        Modifier
                    }
                )
                .shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = (0.35f * opacityMultiplier).safeCoerceIn(0.10f, 0.40f)),
                    spotColor = Color.Black.copy(alpha = (0.65f * opacityMultiplier).safeCoerceIn(0.20f, 0.70f))
                )
                .clip(shape)
                .border(
                    width = borderWidth,
                    brush = borderBrush,
                    shape = shape
                )
        ) {
            // Layer 1: Real Optical Backdrop Blur via GPU
            var windowBoundsInRoot by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .onGloballyPositioned { coordinates ->
                        if (!coordinates.isAttached) return@onGloballyPositioned
                        val pos = coordinates.positionInRoot()
                        val sz = coordinates.size
                        val w = maxOf(0, sz.width).toFloat()
                        val h = maxOf(0, sz.height).toFloat()
                        val left = minOf(pos.x, pos.x + w)
                        val right = maxOf(pos.x, pos.x + w)
                        val top = minOf(pos.y, pos.y + h)
                        val bottom = maxOf(pos.y, pos.y + h)
                        windowBoundsInRoot = androidx.compose.ui.geometry.Rect(left, top, right, bottom)
                    }
            ) {
                if (size.width <= 0f || size.height <= 0f) {
                    return@Canvas
                }

                // Reading frameTick ensures GPU blur re-renders in lockstep with every camera preview frame
                @Suppress("UNUSED_VARIABLE")
                val tick = frameTick

                val bounds = windowBoundsInRoot
                var drawn = false
                if (bounds != null && blurStrength > 0.1f && bounds.width > 0f && bounds.height > 0f) {
                    drawIntoCanvas { canvas ->
                        drawn = BackdropBlurManager.drawGpuBlur(
                            canvas = canvas.nativeCanvas,
                            windowBoundsInRoot = bounds,
                            width = size.width,
                            height = size.height,
                            blurStrength = blurStrength
                        )
                    }
                }

                if (!drawn) {
                    // Graceful solid glass substrate fallback when camera preview has not started or in testing
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF141824).copy(alpha = 0.85f),
                                Color(0xFF0A0C14).copy(alpha = 0.95f)
                            )
                        )
                    )
                }
            }

            // Layer 2: Translucent Tinted Glass Substrate (responds directly to Transparency slider)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                baseTint.copy(alpha = tintAlpha),
                                Color(0xFF07090F).copy(alpha = (tintAlpha + 0.08f).coerceAtMost(0.98f))
                            )
                        )
                    )
            )

            // Layer 3: Frosted Glass Physical Light Diffusion (responds directly to Blur Intensity slider)
            if (blurDiffusionAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = blurDiffusionAlpha * 0.55f),
                                    Color.White.copy(alpha = blurDiffusionAlpha * 0.15f),
                                    Color.White.copy(alpha = blurDiffusionAlpha * 0.35f)
                                )
                            )
                        )
                )
            }

            // Layer 4: Specular Light Sheen Refraction Layer
            if (sheenAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            if (style == FloatingWindowGlassStyle.LIQUID_GLASS) {
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0x33FFD54F).copy(alpha = 0.20f * opacityMultiplier),
                                        Color.White.copy(alpha = sheenAlpha),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.18f * opacityMultiplier)
                                    )
                                )
                            } else {
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = sheenAlpha),
                                        Color.White.copy(alpha = sheenAlpha * 0.25f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.18f * opacityMultiplier)
                                    )
                                )
                            }
                        )
                )
            }

            // Layer 5: Floating Window Content (Stays 100% crisp and readable)
            content()

            // Layer 6: Top Specular Highlight Rim (Cut glass edge)
            if (showTopHighlightRim) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = (0.45f * (0.5f + 0.5f * opacityMultiplier))),
                                    Color.White.copy(alpha = (0.15f * (0.5f + 0.5f * opacityMultiplier))),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
        }
    }
}

/**
 * Extension modifier to apply frosted glass styling directly to any component.
 */
fun Modifier.frostedGlass(
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 12.dp,
    baseAlpha: Float = 0.72f,
    baseTint: Color = Color(0xFF141724),
    borderWidth: Dp = 1.dp,
    borderColor: Color? = null
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.35f),
        spotColor = Color.Black.copy(alpha = 0.65f)
    )
    .clip(shape)
    .background(
        Brush.verticalGradient(
            colors = listOf(
                baseTint.copy(alpha = baseAlpha),
                Color(0xFF0A0C13).copy(alpha = (baseAlpha + 0.12f).coerceAtMost(0.96f))
            )
        )
    )
    .background(
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.03f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.20f)
            )
        )
    )
    .border(
        width = borderWidth,
        brush = if (borderColor != null) {
            SolidColor(borderColor)
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.40f),
                    Color.White.copy(alpha = 0.14f),
                    Color.White.copy(alpha = 0.04f)
                )
            )
        },
        shape = shape
    )
