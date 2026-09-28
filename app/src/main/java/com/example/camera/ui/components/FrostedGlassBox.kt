package com.example.camera.ui.components

import android.graphics.Bitmap
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Authentic Frosted Glass Container with Real Physical Properties & Live Backdrop Blur:
 * - Real live camera background blur rendered strictly within window boundaries
 * - Transparency control defining how much blurred background shows through
 * - Natural depth drop shadow
 * - Translucent tinted glass substrate
 * - Specular directional light sheen gradient
 * - Refractive translucent glass border
 * - Top-edge specular highlight rim
 */
@Composable
fun FrostedGlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    elevation: Dp = 20.dp,
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

    // Transparency: 0.0 (solid/opaque) -> 1.0 (crystal clear / maximum background visibility)
    val effectiveTransparency = baseAlpha ?: appearance.transparency
    val tintAlpha = (1.0f - effectiveTransparency).coerceIn(0.08f, 0.95f)

    var windowBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    var rootSize by remember { mutableStateOf<IntSize?>(null) }

    val borderBrush = if (borderColor != null) {
        SolidColor(borderColor)
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.45f),
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.04f)
            )
        )
    }

    val compactHorizontalInset = if (applyWindowScale && effectiveScale < 1.0f) {
        ((1.0f - effectiveScale) * 96f).dp
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
                .onGloballyPositioned { coordinates ->
                    val pos = coordinates.positionInRoot()
                    val size = coordinates.size
                    windowBoundsInRoot = Rect(pos.x, pos.y, pos.x + size.width, pos.y + size.height)
                    rootSize = coordinates.findRootCoordinates().size
                }
                .shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.75f)
                )
                .clip(shape)
                // Physical glass refractive border
                .border(
                    width = borderWidth,
                    brush = borderBrush,
                    shape = shape
                )
        ) {
            // 1. Live Backdrop Blur Layer:
            // Uses exact float matrix transformation (zero integer Rect rounding zoom-in or pixel stepping).
            val backdropBitmap = BackdropBlurManager.blurredBackdropState.value
            if (backdropBitmap != null && !backdropBitmap.isRecycled && windowBoundsInRoot != null && rootSize != null) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val bounds = windowBoundsInRoot ?: return@Canvas
                    val rSize = rootSize ?: return@Canvas
                    if (rSize.width > 0 && rSize.height > 0 && bounds.width > 0f && bounds.height > 0f && size.width > 0f && size.height > 0f) {
                        val bmpW = backdropBitmap.width.toFloat()
                        val bmpH = backdropBitmap.height.toFloat()
                        val rootW = rSize.width.toFloat()
                        val rootH = rSize.height.toFloat()

                        val srcLeftF = (bounds.left / rootW) * bmpW
                        val srcTopF = (bounds.top / rootH) * bmpH
                        val srcWidthF = ((bounds.width / rootW) * bmpW).coerceAtLeast(1f)
                        val srcHeightF = ((bounds.height / rootH) * bmpH).coerceAtLeast(1f)

                        val drawMatrix = android.graphics.Matrix().apply {
                            setTranslate(-srcLeftF, -srcTopF)
                            postScale(size.width / srcWidthF, size.height / srcHeightF)
                        }

                        drawIntoCanvas { canvas ->
                            val paint = android.graphics.Paint(
                                android.graphics.Paint.FILTER_BITMAP_FLAG or
                                        android.graphics.Paint.ANTI_ALIAS_FLAG or
                                        android.graphics.Paint.DITHER_FLAG
                            )
                            canvas.nativeCanvas.drawBitmap(backdropBitmap, drawMatrix, paint)
                        }
                    }
                }
            }

            // 2. Tinted Liquid Glass Substrate:
            // Alpha is inversely proportional to transparency so user slider directly governs show-through.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                baseTint.copy(alpha = tintAlpha),
                                Color(0xFF07090F).copy(alpha = (tintAlpha + 0.10f).coerceAtMost(0.96f))
                            )
                        )
                    )
            )

            // 3. Physical specular light sheen refraction across surface
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.14f * (effectiveTransparency + 0.4f).coerceAtMost(1f)),
                                Color.White.copy(alpha = 0.03f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.22f)
                            )
                        )
                    )
            )

            // 4. Window Content
            content()

            // 5. Top specular highlight rim (hairline light reflection on cut glass edge)
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
                                    Color.White.copy(alpha = 0.50f),
                                    Color.White.copy(alpha = 0.18f),
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
    shape: Shape = RoundedCornerShape(24.dp),
    elevation: Dp = 16.dp,
    baseAlpha: Float = 0.72f,
    baseTint: Color = Color(0xFF141724),
    borderWidth: Dp = 1.dp,
    borderColor: Color? = null
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.40f),
        spotColor = Color.Black.copy(alpha = 0.70f)
    )
    .clip(shape)
    .background(
        Brush.verticalGradient(
            colors = listOf(
                baseTint.copy(alpha = baseAlpha),
                Color(0xFF0A0C13).copy(alpha = (baseAlpha + 0.14f).coerceAtMost(0.96f))
            )
        )
    )
    .background(
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.03f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.22f)
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
                    Color.White.copy(alpha = 0.42f),
                    Color.White.copy(alpha = 0.14f),
                    Color.White.copy(alpha = 0.05f)
                )
            )
        },
        shape = shape
    )

