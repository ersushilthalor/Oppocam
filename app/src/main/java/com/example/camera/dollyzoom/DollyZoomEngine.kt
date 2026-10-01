package com.example.camera.dollyzoom

import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.params.Face
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dolly Zoom Engine implementing the computer vision algorithm directly adapted from:
 * https://github.com/kailau02/Dolly-Zoom (Frame.py & main.py)
 *
 * Algorithm details from ZIP repository:
 * 1. Face detection identifies face bounding boxes; largestBox(boxes) finds the closest face.
 * 2. If box is uninitialized (dim == -1), box = boxLrg; else box.lerpShape(boxLrg).
 *    Position lerp factor = 0.4, dimension lerp factor = 0.7.
 * 3. Frame.filter() geometric equalized distance calculation:
 *    - distX1, distY1, distX2, distY2 to screen borders.
 *    - Equalize distX, distY to shortest length.
 *    - Trim sides to match original aspect ratio.
 *    - Enforce constant subject ratio (DEFAULT_ZOOM = 0.25).
 *    - Crop window [newX, newY, newW, newH] and resize percentage screenWidth / newW.
 *    - Post-filter box scaled to match output framing with subject size held invariant.
 * 4. Trajectory recording during video recording for synchronized post-processing.
 */
class DollyZoomEngine {

    companion object {
        private const val TAG = "DollyZoomEngine"
        // From reference repository main.py:
        // ZOOM = 0.25 (Medium = 0.2 to 0.3, Close = 0.35 to 0.5)
        const val DEFAULT_ZOOM = 0.25f
        const val MIN_SCALE = 1.0f
        const val MAX_SCALE = 5.0f
    }

    private val _cropStateFlow = MutableStateFlow(DollyCropState())
    val cropStateFlow: StateFlow<DollyCropState> = _cropStateFlow.asStateFlow()

    // BoundingBox(-1, -1, -1, -1) from reference repo main.py
    private var box = DollyBoundingBox(-1f, -1f, -1f, -1f)

    // ZOOM from reference repo main.py
    private var currentZoomSetting: Float = DEFAULT_ZOOM

    @Volatile
    private var isEngineRunning = false

    // Video recording trajectory
    private val isRecordingTrajectory = AtomicBoolean(false)
    private val recordedTrajectory = CopyOnWriteArrayList<DollyTrajectoryPoint>()

    fun isRunning(): Boolean = isEngineRunning

    fun setZoom(amount: Float) {
        currentZoomSetting = amount.coerceIn(0.01f, 0.99f)
        updateCalculations(1080f, 1920f)
    }

    fun getZoom(): Float = currentZoomSetting

    fun start() {
        isEngineRunning = true
        // Reset bounding box to uninitialized state, matching main.py
        box = DollyBoundingBox(-1f, -1f, -1f, -1f)
        Log.i(TAG, "DollyZoomEngine started with reference repo algorithms")
        _cropStateFlow.value = DollyCropState(
            isActive = true,
            statusMessage = "DOLLY ZOOM • ACTIVE"
        )
    }

    fun stop() {
        isEngineRunning = false
        box = DollyBoundingBox(-1f, -1f, -1f, -1f)
        isRecordingTrajectory.set(false)
        recordedTrajectory.clear()
        Log.i(TAG, "DollyZoomEngine stopped")
        _cropStateFlow.value = DollyCropState(isActive = false)
    }

    /**
     * User tap on viewfinder to lock onto subject or re-initialize detection box.
     */
    fun lockSubjectAt(normX: Float, normY: Float) {
        val safeX = normX.coerceIn(0.1f, 0.9f)
        val safeY = normY.coerceIn(0.1f, 0.9f)
        val initialW = currentZoomSetting
        val initialH = currentZoomSetting

        box = DollyBoundingBox(
            x = (safeX - initialW / 2f).coerceIn(0.05f, 0.95f - initialW),
            y = (safeY - initialH / 2f).coerceIn(0.05f, 0.95f - initialH),
            w = initialW,
            h = initialH
        )

        updateCalculations(1080f, 1920f)
        Log.i(TAG, "Dolly Zoom subject anchored at ($safeX, $safeY)")
    }

    fun setSubjectBox(newBox: DollyBoundingBox) {
        box = newBox
        updateCalculations(1080f, 1920f)
    }

    /**
     * Feeds camera capture faces from hardware capture result.
     * Mirrors face detection and largestBox(boxes) + box.lerpShape from reference repo main.py.
     */
    fun onFrameFaces(
        faces: Array<Face>,
        sensorRect: Rect?,
        sensorOrientation: Int = 90,
        isFrontFacing: Boolean = false
    ) {
        if (!isEngineRunning) return

        val detectedBoxes = mutableListOf<DollyBoundingBox>()

        if (sensorRect != null && sensorRect.width() > 0 && sensorRect.height() > 0 && faces.isNotEmpty()) {
            val sW = sensorRect.width().toFloat()
            val sH = sensorRect.height().toFloat()

            for (face in faces) {
                val b = face.bounds
                if (b.width() <= 0 || b.height() <= 0) continue

                // Transform sensor active array coordinates to normalized [0..1] upright frame coordinates
                val normBox = when (sensorOrientation) {
                    90 -> {
                        // Portrait mode on standard back camera (sensor is landscape, rotated 90 deg)
                        val cropH = sW * 9f / 16f
                        val topMargin = (sH - cropH) / 2f
                        val normY = (b.left.toFloat() / sW).coerceIn(0f, 1f)
                        val normH = (b.width().toFloat() / sW).coerceIn(0.01f, 1f)
                        val normX = ((sH - topMargin - b.bottom).toFloat() / cropH).coerceIn(0f, 1f)
                        val normW = (b.height().toFloat() / cropH).coerceIn(0.01f, 1f)
                        DollyBoundingBox(normX, normY, normW, normH)
                    }
                    270 -> {
                        // Front camera portrait
                        val cropH = sW * 9f / 16f
                        val topMargin = (sH - cropH) / 2f
                        val normY = ((sW - b.right).toFloat() / sW).coerceIn(0f, 1f)
                        val normH = (b.width().toFloat() / sW).coerceIn(0.01f, 1f)
                        val normX = ((b.top - topMargin).toFloat() / cropH).coerceIn(0f, 1f)
                        val normW = (b.height().toFloat() / cropH).coerceIn(0.01f, 1f)
                        DollyBoundingBox(normX, normY, normW, normH)
                    }
                    else -> {
                        val normX = (b.left.toFloat() / sW).coerceIn(0f, 1f)
                        val normY = (b.top.toFloat() / sH).coerceIn(0f, 1f)
                        val normW = (b.width().toFloat() / sW).coerceIn(0.01f, 1f)
                        val normH = (b.height().toFloat() / sH).coerceIn(0.01f, 1f)
                        DollyBoundingBox(normX, normY, normW, normH)
                    }
                }
                detectedBoxes.add(normBox)
            }
        }

        // Logic from reference repo main.py:
        // if boxes.size > 0:
        //     boxLrg = largestBox(boxes)
        //     if box.dim[0] == -1:
        //         box = boxLrg
        //     else:
        //         box.lerpShape(boxLrg)
        if (detectedBoxes.isNotEmpty()) {
            val boxLrg = DollyBoundingBox.largestBox(detectedBoxes)
            if (boxLrg != null) {
                if (!box.isInitialized) {
                    box = boxLrg.copy()
                } else {
                    box.lerpShape(boxLrg)
                }
            }
        } else {
            // If no face was detected in this frame, retain previous box (coasting)
            if (!box.isInitialized) {
                // Initial centered framing
                box = DollyBoundingBox(0.35f, 0.35f, 0.30f, 0.30f)
            }
        }

        updateCalculations(1080f, 1920f)
    }

    /**
     * Executes Frame.py filter() algorithm line-for-line adapted for Android.
     */
    fun updateCalculations(screenWidth: Float, screenHeight: Float) {
        if (!isEngineRunning) return

        val screenRatio = screenWidth / screenHeight

        val boxX = box.x * screenWidth
        val boxY = box.y * screenHeight
        val boxW = (box.w * screenWidth).coerceAtLeast(10f)
        val boxH = (box.h * screenHeight).coerceAtLeast(10f)

        var distX1 = boxX
        var distY1 = boxY
        val distX2 = screenWidth - distX1 - boxW
        val distY2 = screenHeight - distY1 - boxH

        // Equalize x's and y's to shortest length
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

        // Make screen to box ratio constant (ZOOM)
        val zoom = currentZoomSetting
        if (screenWidth > screenHeight) {
            distX = minOf(0.5f * ((boxW / zoom) - boxW), distX)
            distY = minOf(((1.0f / screenRatio) * (distX + (boxW / 2.0f))) - (boxH / 2.0f), distY)
        } else {
            distY = minOf(0.5f * ((boxH / zoom) - boxH), distY)
            distX = minOf((screenRatio * (distY + (boxH / 2.0f))) - (boxW / 2.0f), distX)
        }

        distX = distX.coerceAtLeast(0f)
        distY = distY.coerceAtLeast(0f)

        // Crop window to match distance values from Frame.py
        val newX = (boxX - distX).coerceIn(0f, screenWidth - 10f)
        val newY = (boxY - distY).coerceIn(0f, screenHeight - 10f)
        val newW = (2f * distX + boxW).coerceIn(10f, screenWidth - newX)
        val newH = (2f * distY + boxH).coerceIn(10f, screenHeight - newY)

        // Resize percentage from Frame.py: float(screenWidth) / newW
        val resizePercentage = screenWidth / newW

        // postFilterBox from Frame.py:
        // postFilterBox.dim[0] -= x
        // postFilterBox.dim[1] -= y
        // postFilterBox.dim[i] = int(postFilterBox.dim[i] * resizePercentage)
        val postFilterX = (boxX - newX) * resizePercentage
        val postFilterY = (boxY - newY) * resizePercentage
        val postFilterW = boxW * resizePercentage
        val postFilterH = boxH * resizePercentage

        val cropRectNorm = RectF(
            newX / screenWidth,
            newY / screenHeight,
            (newX + newW) / screenWidth,
            (newY + newH) / screenHeight
        )

        val postFilterBoxNorm = RectF(
            (postFilterX / screenWidth).coerceIn(0f, 1f),
            (postFilterY / screenHeight).coerceIn(0f, 1f),
            ((postFilterX + postFilterW) / screenWidth).coerceIn(0f, 1f),
            ((postFilterY + postFilterH) / screenHeight).coerceIn(0f, 1f)
        )

        val scaleFactor = resizePercentage.coerceIn(MIN_SCALE, MAX_SCALE)
        val focusNormX = ((newX + newW / 2.0f) / screenWidth).coerceIn(0.05f, 0.95f)
        val focusNormY = ((newY + newH / 2.0f) / screenHeight).coerceIn(0.05f, 0.95f)

        val state = DollyCropState(
            isActive = true,
            isTracking = box.isInitialized,
            isLocked = box.isInitialized,
            scaleFactor = scaleFactor,
            focusNormX = focusNormX,
            focusNormY = focusNormY,
            cropRectNorm = cropRectNorm,
            subjectBoundsNorm = box.toRectF(),
            postFilterBoxNorm = postFilterBoxNorm,
            apparentSubjectRatio = zoom,
            statusMessage = if (box.isInitialized) "DOLLY ZOOM • TRACKING" else "DOLLY ZOOM • ACTIVE"
        )
        _cropStateFlow.value = state

        // Record trajectory point if video recording is active
        if (isRecordingTrajectory.get()) {
            val nowUs = System.nanoTime() / 1000L
            recordedTrajectory.add(
                DollyTrajectoryPoint(
                    timestampUs = nowUs,
                    scaleFactor = scaleFactor,
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

    fun stopRecordingTrajectory(): List<DollyTrajectoryPoint> {
        isRecordingTrajectory.set(false)
        val list = ArrayList(recordedTrajectory)
        Log.i(TAG, "Stopped recording Dolly Zoom trajectory. Samples collected: ${list.size}")
        return list
    }
}
