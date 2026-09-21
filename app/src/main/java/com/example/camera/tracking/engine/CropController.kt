package com.example.camera.tracking.engine

import android.graphics.PointF
import com.example.camera.tracking.model.CropWindow
import com.example.camera.tracking.model.NormalizedRect
import kotlin.math.hypot

/**
 * Manages the real-time 3x digital crop window.
 * Strictly maintains uniform aspect ratio, clamps to source boundaries [0, 1],
 * and uses adaptive velocity-dependent smoothing with motion prediction.
 */
class CropController(
    var targetZoom: Float = 3.0f
) {
    // Current smoothed center in normalized source space [0..1]
    private var currentCenterX = 0.5f
    private var currentCenterY = 0.5f

    // Current smoothed zoom
    private var currentZoom = 1.0f

    // Smoothed velocities (units / second)
    private var velocityX = 0f
    private var velocityY = 0f

    // Target subject center
    private var targetCenterX = 0.5f
    private var targetCenterY = 0.5f

    // Motion prediction lead time in seconds
    private val predictionLeadSec = 0.05f

    // Tracking intensity multiplier (0.2x = ultra smooth, 1.0x = normal, 3.0x = ultra fast)
    var trackingIntensity: Float = 1.0f

    // Digital Gimbal real-time inverse compensation offsets
    var gimbalOffsetX: Float = 0f
    var gimbalOffsetY: Float = 0f
    private var smoothGimbalX: Float = 0f
    private var smoothGimbalY: Float = 0f

    // Cinematic Pan Configuration
    var isCinematicPanActive: Boolean = false
        private set
    var cinematicPanDurationSec: Float = 5.0f
    var cinematicPanProgress: Float = 0f
        private set
    var cinematicPanLeftToRight: Boolean = true
    var onCinematicPanFinished: (() -> Unit)? = null

    fun startCinematicPan(durationSec: Float = 5.0f, leftToRight: Boolean = true, onFinished: (() -> Unit)? = null) {
        isCinematicPanActive = true
        cinematicPanDurationSec = durationSec.coerceAtLeast(1.0f)
        cinematicPanLeftToRight = leftToRight
        cinematicPanProgress = 0f
        onCinematicPanFinished = onFinished

        targetZoom = 3.0f
        currentZoom = 3.0f
        val halfW = (1f / 3.0f) / 2f
        currentCenterX = if (leftToRight) halfW else (1f - halfW)
        currentCenterY = 0.5f
        targetCenterX = currentCenterX
        targetCenterY = currentCenterY
        velocityX = 0f
        velocityY = 0f
    }

    fun stopCinematicPan() {
        isCinematicPanActive = false
        cinematicPanProgress = 0f
    }

    /**
     * Updates the target subject location.
     * When tracking is active, targetZoom defaults to 3.0f.
     */
    fun setTarget(subjectCenter: PointF?, activeTracking: Boolean, desiredZoom: Float = 3.0f) {
        if (isCinematicPanActive) return // Don't interrupt automated cinematic pan

        if (activeTracking && subjectCenter != null) {
            targetZoom = desiredZoom
            targetCenterX = subjectCenter.x
            targetCenterY = subjectCenter.y
        } else if (!activeTracking) {
            // Revert back to 1.0x wide frame
            targetZoom = desiredZoom.coerceAtMost(1.0f)
            targetCenterX = 0.5f
            targetCenterY = 0.5f
        }
    }

    /**
     * Executes one motion smoothing step.
     * Returns the updated CropWindow in normalized source coordinates.
     */
    fun update(deltaTimeSec: Float): CropWindow {
        val dt = deltaTimeSec.coerceIn(0.001f, 0.1f)

        // 1. Smooth zoom transition (smooth exponential approach)
        val zoomAlpha = (dt * 8f * trackingIntensity.coerceIn(0.5f, 2.5f)).coerceIn(0.05f, 0.7f)
        currentZoom += (targetZoom - currentZoom) * zoomAlpha

        val cropWidth = 1f / currentZoom
        val cropHeight = 1f / currentZoom
        val halfW = cropWidth / 2f
        val halfH = cropHeight / 2f

        // Handle Automated Cinematic Pan
        if (isCinematicPanActive) {
            cinematicPanProgress += dt / cinematicPanDurationSec
            val startX = if (cinematicPanLeftToRight) halfW else 1f - halfW
            val endX = if (cinematicPanLeftToRight) 1f - halfW else halfW
            val clampedProgress = cinematicPanProgress.coerceIn(0f, 1f)

            // Linear constant-velocity traversal from left to right
            currentCenterX = startX + (endX - startX) * clampedProgress
            currentCenterY = 0.5f

            if (cinematicPanProgress >= 1f) {
                isCinematicPanActive = false
                onCinematicPanFinished?.invoke()
            }
        } else {
            // Single Authoritative Adaptive Temporal Filter for subject center movement.
            // X and Y use the exact same symmetrical tracking model with zero deadband loss,
            // strong jitter suppression when stationary, and rapid low-latency response during movement.
            val clampedTargetX = targetCenterX.coerceIn(halfW, 1f - halfW)
            val clampedTargetY = targetCenterY.coerceIn(halfH, 1f - halfH)

            val diffX = clampedTargetX - currentCenterX
            val diffY = clampedTargetY - currentCenterY
            val dist = hypot(diffX.toDouble(), diffY.toDouble()).toFloat()

            if (dist > 0.0005f) {
                // Adaptive response: smoothly accelerates cutoff frequency as distance/speed increases
                // Eliminates sensor/detector flutter near rest, while tracking rapid movement without lag or overshoot
                val speedFactor = (1.0f + (dist / 0.035f).let { it * it }).coerceIn(1.0f, 6.0f)
                val k = (4.5f * trackingIntensity.coerceIn(0.5f, 2.5f) * speedFactor).coerceIn(3.5f, 24.0f)
                val blend = (1f - kotlin.math.exp(-k * dt).toFloat()).coerceIn(0.04f, 0.70f)

                currentCenterX += diffX * blend
                currentCenterY += diffY * blend
            }
        }

        // Apply Digital Gimbal inverse compensation offsets smoothly without twitching
        val safeGimbalX = if (gimbalOffsetX.isFinite()) gimbalOffsetX else 0f
        val safeGimbalY = if (gimbalOffsetY.isFinite()) gimbalOffsetY else 0f
        val safeCenterX = if (currentCenterX.isFinite()) currentCenterX else 0.5f
        val safeCenterY = if (currentCenterY.isFinite()) currentCenterY else 0.5f

        val gimbalBlend = (dt * 12f).coerceIn(0.1f, 0.6f)
        smoothGimbalX += (safeGimbalX - smoothGimbalX) * gimbalBlend
        smoothGimbalY += (safeGimbalY - smoothGimbalY) * gimbalBlend

        val stabilizedCenterX = (safeCenterX + smoothGimbalX).coerceIn(halfW, 1f - halfW)
        val stabilizedCenterY = (safeCenterY + smoothGimbalY).coerceIn(halfH, 1f - halfH)

        val left = (stabilizedCenterX - halfW).coerceIn(0f, (1f - cropWidth).coerceAtLeast(0f))
        val top = (stabilizedCenterY - halfH).coerceIn(0f, (1f - cropHeight).coerceAtLeast(0f))
        val right = (left + cropWidth).coerceIn(left + 0.001f, 1f)
        val bottom = (top + cropHeight).coerceIn(top + 0.001f, 1f)

        return CropWindow(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            zoomFactor = currentZoom
        )
    }

    /**
     * Maps a tap coordinate in normalized viewfinder space [0..1]
     * to the corresponding coordinate in normalized source frame space [0..1].
     */
    fun mapViewfinderToSource(vfX: Float, vfY: Float, currentCrop: CropWindow): PointF {
        val srcX = currentCrop.left + vfX * currentCrop.width
        val srcY = currentCrop.top + vfY * currentCrop.height
        return PointF(srcX.coerceIn(0f, 1f), srcY.coerceIn(0f, 1f))
    }

    /**
     * Maps a normalized source frame coordinate [0..1]
     * to normalized viewfinder space [0..1].
     */
    fun mapSourceToViewfinder(srcX: Float, srcY: Float, currentCrop: CropWindow): PointF {
        val vfX = (srcX - currentCrop.left) / currentCrop.width
        val vfY = (srcY - currentCrop.top) / currentCrop.height
        return PointF(vfX, vfY)
    }

    /**
     * Maps a source bounding box to viewfinder normalized coordinates.
     */
    fun mapSourceRectToViewfinder(sourceRect: NormalizedRect, currentCrop: CropWindow): NormalizedRect {
        val topLeft = mapSourceToViewfinder(sourceRect.left, sourceRect.top, currentCrop)
        val bottomRight = mapSourceToViewfinder(sourceRect.right, sourceRect.bottom, currentCrop)
        return NormalizedRect(
            left = topLeft.x,
            top = topLeft.y,
            right = bottomRight.x,
            bottom = bottomRight.y
        )
    }

    /**
     * Immediately snaps the crop center without transition (e.g. for initial lock or reset).
     */
    fun snapTo(cx: Float, cy: Float, zoom: Float) {
        targetZoom = zoom
        currentZoom = zoom
        val halfW = (1f / zoom) / 2f
        val halfH = (1f / zoom) / 2f
        currentCenterX = cx.coerceIn(halfW, 1f - halfW)
        currentCenterY = cy.coerceIn(halfH, 1f - halfH)
        targetCenterX = currentCenterX
        targetCenterY = currentCenterY
        velocityX = 0f
        velocityY = 0f
        smoothGimbalX = 0f
        smoothGimbalY = 0f
    }
}
