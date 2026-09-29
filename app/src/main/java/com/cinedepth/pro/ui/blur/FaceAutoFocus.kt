package com.cinedepth.pro.ui.blur

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Lightweight face detector for auto-focus.
 * Uses ML Kit's on-device face detection (fast mode, no landmarks/contours)
 * to find the most prominent face, then samples the depth map at that point.
 *
 * Returns the normalized (x, y) center of the largest face and its depth value (0-255),
 * or null if no face is found.
 */
data class FaceAutoFocusResult(
    val normalizedX: Float,   // 0..1 within the image
    val normalizedY: Float,   // 0..1 within the image
    val depthValue: Int        // 0-255 sampled from depth map
)

object FaceAutoFocus {

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.12f) // detect faces >= 12% of image width
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * Detect faces in [sourceBitmap] and sample depth from [depthBitmap].
     * Returns the focus result for the largest face, or null if no face found.
     *
     * Both bitmaps must have the same aspect ratio (they typically do since depth
     * is derived from the source).
     */
    suspend fun detectAndSampleDepth(
        sourceBitmap: Bitmap,
        depthBitmap: Bitmap
    ): FaceAutoFocusResult? = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(sourceBitmap, 0)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    cont.resume(null)
                    return@addOnSuccessListener
                }

                // Pick the largest face by bounding box area
                val largest = faces.maxByOrNull {
                    it.boundingBox.width() * it.boundingBox.height()
                } ?: run {
                    cont.resume(null)
                    return@addOnSuccessListener
                }

                val box = largest.boundingBox
                val centerX = (box.left + box.right) / 2f
                val centerY = (box.top + box.bottom) / 2f

                // Normalize to 0..1
                val normX = (centerX / sourceBitmap.width).coerceIn(0f, 1f)
                val normY = (centerY / sourceBitmap.height).coerceIn(0f, 1f)

                // Sample depth at the face center
                val depthX = (normX * depthBitmap.width).toInt()
                    .coerceIn(0, depthBitmap.width - 1)
                val depthY = (normY * depthBitmap.height).toInt()
                    .coerceIn(0, depthBitmap.height - 1)
                val pixel = depthBitmap.getPixel(depthX, depthY)
                val depthValue = android.graphics.Color.red(pixel)

                cont.resume(
                    FaceAutoFocusResult(
                        normalizedX = normX,
                        normalizedY = normY,
                        depthValue = depthValue
                    )
                )
            }
            .addOnFailureListener {
                cont.resume(null)
            }
    }
}
