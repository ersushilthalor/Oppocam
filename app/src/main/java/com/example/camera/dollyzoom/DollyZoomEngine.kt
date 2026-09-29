package com.example.camera.dollyzoom

import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.params.Face
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * Dolly Zoom Engine implementing the computer vision algorithm from:
 * https://github.com/kailau02/Dolly-Zoom (Frame.py & main.py)
 *
 * Requirements & Features:
 * 1. Tracks the primary face or user-selected subject smoothly and continuously.
 * 2. Employs the reference repository's geometric aspect-ratio equalization equations
 *    to compute a real-time dynamic crop window that holds the subject's apparent size
 *    strictly constant (ZOOM = 0.35) while physical camera distance shifts perspective.
 * 3. 2-tier lerp smoothing (alpha: 0.35 position, 0.25 size) to prevent any jitter, sudden
 *    zoom changes, or framing oscillation.
 * 4. Records high-precision trajectory points during video recording for synchronized
 *    post-processing in the final saved MP4 file.
 */
class DollyZoomEngine {

    companion object {
        private const val TAG = "DollyZoomEngine"
        // Target ratio of subject size relative to frame dimension (from reference repo ZOOM = 0.25..0.40)
        private const val DEFAULT_TARGET_SUBJECT_ZOOM = 0.35f
        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 5.0f
    }

    private val _cropStateFlow = MutableStateFlow(DollyCropState())
    val cropStateFlow: StateFlow<DollyCropState> = _cropStateFlow.asStateFlow()

    private val currentBox = DollyBoundingBox(0.35f, 0.30f, 0.30f, 0.40f)
    private var isTrackingInitialized = false
    private var isUserLocked = false
    private var userLockNormX = 0.5f
    private var userLockNormY = 0.5f
    private var missedFrames = 0

    // Video recording trajectory
    private val isRecordingTrajectory = java.util.concurrent.atomic.AtomicBoolean(false)
    private val recordedTrajectory = CopyOnWriteArrayList<DollyTrajectoryPoint>()

    @Volatile
    private var isEngineRunning = false

    fun isRunning(): Boolean = isEngineRunning

    fun start() {
        isEngineRunning = true
        isTrackingInitialized = false
        missedFrames = 0
        Log.i(TAG, "DollyZoomEngine started")
        _cropStateFlow.value = _cropStateFlow.value.copy(
            isActive = true,
            statusMessage = "DOLLY ZOOM • ACTIVE"
        )
    }

    fun stop() {
        isEngineRunning = false
        isUserLocked = false
        isTrackingInitialized = false
        isRecordingTrajectory.set(false)
        recordedTrajectory.clear()
        Log.i(TAG, "DollyZoomEngine stopped")
        _cropStateFlow.value = DollyCropState(isActive = false)
    }

    /**
     * User tapped a specific point on the viewfinder to manually lock onto that subject.
     */
    fun lockSubjectAt(normX: Float, normY: Float) {
        val safeX = normX.coerceIn(0.1f, 0.9f)
        val safeY = normY.coerceIn(0.1f, 0.9f)
        isUserLocked = true
        userLockNormX = safeX
        userLockNormY = safeY

        // Re-center bounding box at tap with initial framing
        val initialW = 0.28f
        val initialH = 0.35f
        currentBox.x = (safeX - initialW / 2f).coerceIn(0.05f, 1f - initialW)
        currentBox.y = (safeY - initialH / 2f).coerceIn(0.05f, 1f - initialH)
        currentBox.w = initialW
        currentBox.h = initialH
        isTrackingInitialized = true
        missedFrames = 0

        updateCalculations(1080f, 1920f)
        Log.i(TAG, "Dolly Zoom subject locked at ($safeX, $safeY)")
    }

    /**
     * Feeds camera capture faces from Camera2 hardware capture result.
     * Mirrors largestBox(boxes) from reference repo main.py.
     */
    fun onFrameFaces(faces: Array<Face>, sensorRect: Rect?) {
        if (!isEngineRunning) return

        if (sensorRect != null && sensorRect.width() > 0 && sensorRect.height() > 0 && faces.isNotEmpty()) {
            // Find largest face (closest to camera, matching reference repo largestBox)
            val largestFace = faces.maxByOrNull { it.bounds.width() * it.bounds.height() }
            if (largestFace != null) {
                val sW = sensorRect.width().toFloat()
                val sH = sensorRect.height().toFloat()

                // Sensor to normalized [0..1]
                val faceBounds = largestFace.bounds
                val normX = (faceBounds.left.toFloat() / sW).coerceIn(0f, 1f)
                val normY = (faceBounds.top.toFloat() / sH).coerceIn(0f, 1f)
                val normW = (faceBounds.width().toFloat() / sW).coerceIn(0.05f, 1f)
                val normH = (faceBounds.height().toFloat() / sH).coerceIn(0.05f, 1f)

                val targetBox = DollyBoundingBox(normX, normY, normW, normH)

                if (!isTrackingInitialized) {
                    currentBox.x = targetBox.x
                    currentBox.y = targetBox.y
                    currentBox.w = targetBox.w
                    currentBox.h = targetBox.h
                    isTrackingInitialized = true
                } else {
                    // Smooth tracking using lerpShape from reference repo
                    currentBox.lerpShape(targetBox, posAlpha = 0.35f, sizeAlpha = 0.20f)
                }
                missedFrames = 0
            }
        } else {
            // If no face was detected in this frame, gently coast with last known position
            missedFrames++
            if (!isTrackingInitialized) {
                // Initialize to center frame
                currentBox.x = 0.35f
                currentBox.y = 0.30f
                currentBox.w = 0.30f
                currentBox.h = 0.40f
                isTrackingInitialized = true
            }
        }

        updateCalculations(1080f, 1920f)
    }

    /**
     * Updates Dolly Zoom geometric equations matching Frame.py filter() from reference repo:
     *
     * 1. Equalize X and Y distances from box edges to screen boundaries.
     * 2. Trim sides to match original aspect ratio.
     * 3. Set constant screen-to-box ratio (ZOOM).
     * 4. Compute crop window (newX, newY, newW, newH) and scaleFactor.
     */
    private fun updateCalculations(screenWidth: Float, screenHeight: Float) {
        val screenRatio = screenWidth / screenHeight

        val boxX = currentBox.x * screenWidth
        val boxY = currentBox.y * screenHeight
        val boxW = (currentBox.w * screenWidth).coerceAtLeast(10f)
        val boxH = (currentBox.h * screenHeight).coerceAtLeast(10f)

        var distX1 = boxX
        var distY1 = boxY
        val distX2 = screenWidth - distX1 - boxW
        val distY2 = screenHeight - distY1 - boxH

        // Equalize x's and y's to shortest length so box remains centered
        if (distX1 > distX2) distX1 = distX2
        if (distY1 > distY2) distY1 = distY2

        var distX = distX1.coerceAtLeast(0f)
        var distY = distY1.coerceAtLeast(0f)

        // Trim sides to match original aspect ratio
        val centerX = distX + (boxW / 2.0f)
        val centerY = distY + (boxH / 2.0f)
        val distsRatio = if (centerY > 0.0001f) centerX / centerY else screenRatio

        if (screenRatio < distsRatio) {
            val offset = centerX - (centerY * screenRatio)
            distX -= offset
        } else if (screenRatio > distsRatio) {
            val offset = centerY - (centerX / screenRatio)
            distY -= offset
        }

        distX = distX.coerceAtLeast(0f)
        distY = distY.coerceAtLeast(0f)

        // Make screen to box ratio constant (DEFAULT_TARGET_SUBJECT_ZOOM = 0.35)
        val zoom = DEFAULT_TARGET_SUBJECT_ZOOM
        if (screenWidth > screenHeight) {
            distX = min(0.5f * ((boxW / zoom) - boxW), distX)
            distY = min(((1.0f / screenRatio) * (distX + (boxW / 2.0f))) - (boxH / 2.0f), distY)
        } else {
            distY = min(0.5f * ((boxH / zoom) - boxH), distY)
            distX = min((screenRatio * (distY + (boxH / 2.0f))) - (boxW / 2.0f), distX)
        }

        distX = distX.coerceAtLeast(0f)
        distY = distY.coerceAtLeast(0f)

        // Crop window to match distance values (from Frame.py)
        val newX = (boxX - distX).coerceIn(0f, screenWidth - 10f)
        val newY = (boxY - distY).coerceIn(0f, screenHeight - 10f)
        val newW = (2f * distX + boxW).coerceIn(10f, screenWidth - newX)
        val newH = (2f * distY + boxH).coerceIn(10f, screenHeight - newY)

        // Desired scale factor = screenWidth / newW (>= 1.0f)
        val rawScale = (screenWidth / newW).coerceIn(MIN_SCALE, MAX_SCALE)

        // Smooth scale approach to eliminate any stepping
        val prevScale = _cropStateFlow.value.scaleFactor
        val smoothedScale = prevScale + (rawScale - prevScale) * 0.25f

        val focusNormX = ((newX + newW / 2f) / screenWidth).coerceIn(0.1f, 0.9f)
        val focusNormY = ((newY + newH / 2f) / screenHeight).coerceIn(0.1f, 0.9f)

        val cropRectNorm = RectF(
            newX / screenWidth,
            newY / screenHeight,
            (newX + newW) / screenWidth,
            (newY + newH) / screenHeight
        )

        val subjectBoundsNorm = currentBox.toRectF()

        val state = DollyCropState(
            isActive = true,
            isLocked = isUserLocked || isTrackingInitialized,
            scaleFactor = smoothedScale,
            focusNormX = focusNormX,
            focusNormY = focusNormY,
            cropRectNorm = cropRectNorm,
            subjectBoundsNorm = subjectBoundsNorm,
            apparentSubjectRatio = currentBox.w * smoothedScale,
            statusMessage = if (isUserLocked) "DOLLY ZOOM • LOCKED" else "DOLLY ZOOM • TRACKING"
        )
        _cropStateFlow.value = state

        // Record trajectory point if video recording is active
        if (isRecordingTrajectory.get()) {
            val nowUs = System.nanoTime() / 1000L
            recordedTrajectory.add(
                DollyTrajectoryPoint(
                    timestampUs = nowUs,
                    scaleFactor = smoothedScale,
                    focusNormX = focusNormX,
                    focusNormY = focusNormY,
                    cropLeft = cropRectNorm.left,
                    cropTop = cropRectNorm.top,
                    cropWidth = cropRectNorm.width(),
                    cropHeight = cropRectNorm.height()
                )
            )
        }
    }

    fun startRecordingTrajectory() {
        recordedTrajectory.clear()
        isRecordingTrajectory.set(true)
        Log.i(TAG, "Started recording Dolly Zoom trajectory for video")
    }

    fun recordCurrentFrame(timestampUs: Long) {
        if (!isRecordingTrajectory.get()) return
        val current = _cropStateFlow.value
        recordedTrajectory.add(
            DollyTrajectoryPoint(
                timestampUs = timestampUs,
                scaleFactor = current.scaleFactor,
                focusNormX = current.focusNormX,
                focusNormY = current.focusNormY,
                cropLeft = current.cropRectNorm.left,
                cropTop = current.cropRectNorm.top,
                cropWidth = current.cropRectNorm.width(),
                cropHeight = current.cropRectNorm.height()
            )
        )
    }

    fun stopRecordingTrajectory(): List<DollyTrajectoryPoint> {
        isRecordingTrajectory.set(false)
        val list = ArrayList(recordedTrajectory)
        Log.i(TAG, "Stopped recording Dolly Zoom trajectory. Samples collected: ${list.size}")
        return list
    }
}
