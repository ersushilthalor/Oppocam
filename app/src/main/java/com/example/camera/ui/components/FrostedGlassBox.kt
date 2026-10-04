package com.example.camera.ui.components

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.camera.model.FloatingWindowGlassStyle

/**
 * Authentic Glass Container with Physical Glass Styling:
 * Controlled by Settings:
 * - Liquid Glass: dynamic organic light sheen, chromatic specular reflection, smooth liquid gloss
 * - Frosted Glass: soft diffuse frosted overlay, ambient diffusion, elegant translucent depth
 * - Transparent Glass: ultra-clear high-transparency glass, minimal tint, crisp fine hairline border
 * - Subtle Frost / Deep Frost / Solid Dark: tailored opacity and diffusion layers
 *
 * Glass effect is applied STRICTLY to the floating window UI overlay itself;
 * the underlying viewfinder remains single-source, 100% sharp, and free of latency.
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
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val effectiveScale = if (applyWindowScale) appearance.windowScale.coerceIn(0.75f, 1.25f) else 1.0f
    val scaledDensity = remember(baseDensity, effectiveScale) {
        androidx.compose.ui.unit.Density(
            density = baseDensity.density * effectiveScale,
            fontScale = baseDensity.fontScale * effectiveScale
        )
    }

    val style = appearance.glassStyle
    val userTransparency = baseAlpha ?: appearance.transparency

    // Style-specific substrate tint opacity & specular gradients
    val (tintAlpha, sheenAlpha, borderBrush) = remember(style, userTransparency, borderColor) {
        when (style) {
            FloatingWindowGlassStyle.TRANSPARENT_GLASS -> {
                val tAlpha = ((1.0f - userTransparency) * 0.35f).coerceIn(0.08f, 0.32f)
                val sAlpha = 0.12f
                val brush = if (borderColor != null) SolidColor(borderColor) else Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.40f),
                        Color.White.copy(alpha = 0.12f),
                        Color.Transparent
                    )
                )
                Triple(tAlpha, sAlpha, brush)
            }
            FloatingWindowGlassStyle.LIQUID_GLASS -> {
                val tAlpha = (1.0f - userTransparency).coerceIn(0.20f, 0.70f)
                val sAlpha = 0.22f
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
                val tAlpha = (1.0f - userTransparency).coerceIn(0.35f, 0.85f)
                val sAlpha = 0.16f
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
                val tAlpha = (1.0f - userTransparency).coerceIn(0.25f, 0.65f)
                val sAlpha = 0.14f
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
                val tAlpha = (1.0f - userTransparency).coerceIn(0.50f, 0.90f)
                val sAlpha = 0.18f
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
                val tAlpha = (1.0f - userTransparency).coerceIn(0.70f, 0.96f)
                val sAlpha = 0.10f
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
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.65f)
                )
                .clip(shape)
                .border(
                    width = borderWidth,
                    brush = borderBrush,
                    shape = shape
                )
        ) {
            // 1. Translucent Tinted Glass Substrate:
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                baseTint.copy(alpha = tintAlpha),
                                Color(0xFF07090F).copy(alpha = (tintAlpha + 0.10f).coerceAtMost(0.98f))
                            )
                        )
                    )
            )

            // 2. Specular light sheen refraction layer:
            if (sheenAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            if (style == FloatingWindowGlassStyle.LIQUID_GLASS) {
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0x33FFD54F),
                                        Color.White.copy(alpha = sheenAlpha),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.18f)
                                    )
                                )
                            } else {
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = sheenAlpha),
                                        Color.White.copy(alpha = sheenAlpha * 0.25f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.18f)
                                    )
                                )
                            }
                        )
                )
            }

            // 3. Floating Window Content
            content()

            // 4. Top specular highlight rim (cut glass edge)
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
                                    Color.White.copy(alpha = 0.45f),
                                    Color.White.copy(alpha = 0.15f),
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

