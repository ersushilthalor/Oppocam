package com.camerapro.camera.dollyzoom

import android.graphics.RectF

/**
 * BoundingBox representation directly adapted from the reference repository (kailau02/Dolly-Zoom Frame.py).
 * Maintains [x, y, w, h] coordinates with linear interpolation and aspect-ratio geometry.
 */
class DollyBoundingBox(
    var x: Float = -1f,
    var y: Float = -1f,
    var w: Float = -1f,
    var h: Float = -1f
) {
    val dim: FloatArray
        get() = floatArrayOf(x, y, w, h)

    val isInitialized: Boolean
        get() = x >= 0f && w > 0f && h > 0f

    /**
     * Frame.py:
     * def lerp(a, b, c):
     *     return int((c * a) + ((1 - c) * b))
     */
    companion object {
        fun lerp(a: Float, b: Float, c: Float): Float = (c * a) + ((1f - c) * b)

        /**
         * Frame.py:
         * def largestBox(boxes):
         *     lrg_width = 0
         *     lrg_box = None
         *     for box in boxes:
         *         if box[2] > lrg_width:
         *             lrg_box = BoundingBox(box[0], box[1], box[2], box[3])
         *             lrg_width = box[2]
         *     return lrg_box
         */
        fun largestBox(boxes: List<DollyBoundingBox>): DollyBoundingBox? {
            var lrgWidth = 0f
            var lrgBox: DollyBoundingBox? = null
            for (box in boxes) {
                if (box.w > lrgWidth) {
                    lrgBox = DollyBoundingBox(box.x, box.y, box.w, box.h)
                    lrgWidth = box.w
                }
            }
            return lrgBox
        }
    }

    /**
     * Frame.py:
     * def lerpShape(self, newBox):
     *     for i in range(2):
     *         self.dim[i] = lerp(self.dim[i], newBox.dim[i], 0.4)
     *     for i in range(2):
     *         j = i + 2
     *         self.dim[j] = lerp(self.dim[j], newBox.dim[j], 0.7)
     */
    fun lerpShape(newBox: DollyBoundingBox) {
        x = lerp(x, newBox.x, 0.4f)
        y = lerp(y, newBox.y, 0.4f)
        w = lerp(w, newBox.w, 0.7f)
        h = lerp(h, newBox.h, 0.7f)
    }

    fun copy(): DollyBoundingBox = DollyBoundingBox(x, y, w, h)

    fun toRectF(): RectF = RectF(x, y, x + w, y + h)
}

/**
 * Real-time crop and transform state computed by DollyZoomEngine based on Frame.py filter().
 */
data class DollyCropState(
    val isActive: Boolean = false,
    val isTracking: Boolean = false,
    val isLocked: Boolean = false,
    val scaleFactor: Float = 1.0f,
    val focusNormX: Float = 0.5f,
    val focusNormY: Float = 0.5f,
    val cropRectNorm: RectF = RectF(0f, 0f, 1f, 1f),
    val subjectBoundsNorm: RectF = RectF(0.35f, 0.35f, 0.65f, 0.65f),
    val postFilterBoxNorm: RectF = RectF(0.35f, 0.35f, 0.65f, 0.65f),
    val apparentSubjectRatio: Float = 0.25f,
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
