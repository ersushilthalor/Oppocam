package com.example.camera.dollyzoom

import android.graphics.RectF

/**
 * Bounding box representation mirroring the reference repository (kailau02/Dolly-Zoom).
 * Coordinates are normalized in [0.0..1.0] relative to frame width and height.
 */
class DollyBoundingBox(
    var x: Float = 0.35f,
    var y: Float = 0.35f,
    var w: Float = 0.30f,
    var h: Float = 0.30f
) {
    /**
     * Linearly interpolates shape towards [newBox].
     * Reference repo Frame.py:
     * - Position alpha = 0.4
     * - Size alpha = 0.7
     */
    fun lerpShape(newBox: DollyBoundingBox, posAlpha: Float = 0.35f, sizeAlpha: Float = 0.25f) {
        x += (newBox.x - x) * posAlpha.coerceIn(0.01f, 1f)
        y += (newBox.y - y) * posAlpha.coerceIn(0.01f, 1f)
        w += (newBox.w - w) * sizeAlpha.coerceIn(0.01f, 1f)
        h += (newBox.h - h) * sizeAlpha.coerceIn(0.01f, 1f)
    }

    fun toRectF(): RectF = RectF(x, y, x + w, y + h)

    fun copy(): DollyBoundingBox = DollyBoundingBox(x, y, w, h)
}

/**
 * Real-time crop and transform state computed by DollyZoomEngine.
 */
data class DollyCropState(
    val isActive: Boolean = false,
    val isLocked: Boolean = false,
    val scaleFactor: Float = 1.0f,
    val focusNormX: Float = 0.5f,
    val focusNormY: Float = 0.5f,
    val cropRectNorm: RectF = RectF(0f, 0f, 1f, 1f),
    val subjectBoundsNorm: RectF = RectF(0.35f, 0.35f, 0.65f, 0.65f),
    val apparentSubjectRatio: Float = 0.35f,
    val statusMessage: String = "DOLLY ZOOM"
)

/**
 * Trajectory point recorded during video recording for synchronized frame-by-frame post-processing.
 */
data class DollyTrajectoryPoint(
    val timestampUs: Long,
    val scaleFactor: Float,
    val focusNormX: Float,
    val focusNormY: Float,
    val cropLeft: Float,
    val cropTop: Float,
    val cropWidth: Float,
    val cropHeight: Float
)
