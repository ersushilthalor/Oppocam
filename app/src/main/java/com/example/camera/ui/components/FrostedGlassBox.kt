package com.example.camera.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    content: @Composable BoxScope.() -> Unit
) {
    val appearance = LocalFloatingWindowAppearance.current

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

    Box(
        modifier = modifier
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
        // Renders the live blurred background corresponding strictly to this window's screen rect.
        val backdropBitmap = BackdropBlurManager.blurredBackdropState.value
        if (backdropBitmap != null && !backdropBitmap.isRecycled && windowBoundsInRoot != null && rootSize != null) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val bounds = windowBoundsInRoot ?: return@Canvas
                val rSize = rootSize ?: return@Canvas
                if (rSize.width > 0 && rSize.height > 0) {
                    val srcLeft = ((bounds.left / rSize.width.toFloat()) * backdropBitmap.width).toInt().coerceIn(0, backdropBitmap.width - 1)
                    val srcTop = ((bounds.top / rSize.height.toFloat()) * backdropBitmap.height).toInt().coerceIn(0, backdropBitmap.height - 1)
                    val srcRight = ((bounds.right / rSize.width.toFloat()) * backdropBitmap.width).toInt().coerceIn(srcLeft + 1, backdropBitmap.width)
                    val srcBottom = ((bounds.bottom / rSize.height.toFloat()) * backdropBitmap.height).toInt().coerceIn(srcTop + 1, backdropBitmap.height)

                    val srcRect = android.graphics.Rect(srcLeft, srcTop, srcRight, srcBottom)
                    val dstRect = android.graphics.Rect(0, 0, size.width.toInt(), size.height.toInt())

                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            isFilterBitmap = true // Hardware-accelerated bilinear filtering
                            isAntiAlias = true
                        }
                        canvas.nativeCanvas.drawBitmap(backdropBitmap, srcRect, dstRect, paint)
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

