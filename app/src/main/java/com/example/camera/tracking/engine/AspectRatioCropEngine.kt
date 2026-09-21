package com.example.camera.tracking.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.example.camera.tracking.model.CropWindow
import com.example.camera.tracking.model.NormalizedRect
import kotlin.math.roundToInt

/**
 * High-precision coordinate transformer and crop engine.
 * Ensures ABSOLUTELY ZERO horizontal or vertical stretching.
 * Maintains aspect-ratio preservation and handles all boundary constraints.
 */
object AspectRatioCropEngine {

    /**
     * Calculates the sub-rectangle within a source bitmap (or frame)
     * corresponding to the given [CropWindow], preserving the aspect ratio [targetAspect]
     * (targetAspect = width / height).
     */
    fun calculateSourceCropRect(
        srcWidth: Int,
        srcHeight: Int,
        cropWindow: CropWindow,
        targetAspect: Float? = null
    ): Rect {
        val srcW = srcWidth.toFloat()
        val srcH = srcHeight.toFloat()
        val baseSrcAspect = srcW / srcH

        // Normalized crop window dimensions
        var normW = cropWindow.width
        var normH = cropWindow.height

        // If a specific output aspect ratio is enforced (e.g. 9:16 or 3:4),
        // adjust normW or normH so (normW * srcW) / (normH * srcH) == targetAspect
        if (targetAspect != null && targetAspect > 0f) {
            val desiredPixelAspect = targetAspect
            val currentPixelAspect = (normW * srcW) / (normH * srcH)

            if (currentPixelAspect > desiredPixelAspect) {
                // Current is too wide -> reduce normW
                normW = (normH * srcH * desiredPixelAspect) / srcW
            } else {
                // Current is too tall -> reduce normH
                normH = (normW * srcW / desiredPixelAspect) / srcH
            }
        }

        val safeW = if (normW.isFinite()) normW.coerceIn(0.1f, 1f) else 1f / 3f
        val safeH = if (normH.isFinite()) normH.coerceIn(0.1f, 1f) else 1f / 3f

        val halfW = safeW / 2f
        val halfH = safeH / 2f

        // Center point clamped so crop rect never exceeds [0..1]
        val safeCenterX = if (cropWindow.centerX.isFinite()) cropWindow.centerX else 0.5f
        val safeCenterY = if (cropWindow.centerY.isFinite()) cropWindow.centerY else 0.5f

        val cx = safeCenterX.coerceIn(halfW, 1f - halfW)
        val cy = safeCenterY.coerceIn(halfH, 1f - halfH)

        val leftPx = ((cx - halfW) * srcW).roundToInt().coerceIn(0, (srcWidth - 2).coerceAtLeast(0))
        val topPx = ((cy - halfH) * srcH).roundToInt().coerceIn(0, (srcHeight - 2).coerceAtLeast(0))
        val rightPx = ((cx + halfW) * srcW).roundToInt().coerceIn(leftPx + 1, srcWidth)
        val bottomPx = ((cy + halfH) * srcH).roundToInt().coerceIn(topPx + 1, srcHeight)

        return Rect(leftPx, topPx, rightPx, bottomPx)
    }

    /**
     * Crops a source [Bitmap] to the exact 3x tracking region with zero distortion.
     * Uniformly crops the pixels from the source bitmap.
     */
    fun cropBitmap(
        source: Bitmap,
        cropWindow: CropWindow,
        targetAspect: Float? = null
    ): Bitmap {
        val cropRect = calculateSourceCropRect(source.width, source.height, cropWindow, targetAspect)
        val safeLeft = cropRect.left.coerceIn(0, (source.width - 2).coerceAtLeast(0))
        val safeTop = cropRect.top.coerceIn(0, (source.height - 2).coerceAtLeast(0))
        val safeW = cropRect.width().coerceIn(1, source.width - safeLeft)
        val safeH = cropRect.height().coerceIn(1, source.height - safeTop)

        val cropped = Bitmap.createBitmap(
            source,
            safeLeft,
            safeTop,
            safeW,
            safeH
        )
        return cropped
    }

    /**
     * Computes the letterbox/pillarbox destination rectangle on a target canvas/viewfinder
     * to draw the cropped source frame with perfect physical proportions.
     */
    fun calculateAspectPreservingDstRect(
        viewWidth: Float,
        viewHeight: Float,
        cropAspect: Float
    ): RectF {
        val viewAspect = viewWidth / viewHeight
        return if (viewAspect > cropAspect) {
            // View is wider than image -> Pillarbox (vertical full height, centered horizontally)
            val dstW = viewHeight * cropAspect
            val left = (viewWidth - dstW) / 2f
            RectF(left, 0f, left + dstW, viewHeight)
        } else {
            // View is taller than image -> Letterbox (horizontal full width, centered vertically)
            val dstH = viewWidth / cropAspect
            val top = (viewHeight - dstH) / 2f
            RectF(0f, top, viewWidth, top + dstH)
        }
    }
}
