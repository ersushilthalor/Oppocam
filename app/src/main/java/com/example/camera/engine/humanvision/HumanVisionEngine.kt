package com.example.camera.engine.humanvision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Master Pipeline Orchestrator for Human Vision / Natural Perspective Mode.
 *
 * Implements:
 * 1. Asynchronous multi-camera capture & processing (never freezes the viewfinder preview).
 * 2. Real-time progress updates via StateFlow.
 * 3. Fallback resiliency: Automatically degrades gracefully to available optical sensors
 *    and saves the best possible photo on any hardware failure.
 */
class HumanVisionEngine(private val context: Context) {

    companion object {
        private const val TAG = "HumanVisionEngine"
    }

    private val depthEngine = HumanVisionDepthEngine()
    private val alignmentEngine = HumanVisionAlignmentEngine()
    private val mosaicEngine = HumanVisionMosaicEngine()
    private val fusionEngine = HumanVisionFusionEngine()
    private val perspectiveCorrector = HumanVisionPerspectiveCorrector()

    private val _progressState = MutableStateFlow(HumanVisionProgress())
    val progressState: StateFlow<HumanVisionProgress> = _progressState.asStateFlow()

    /**
     * Executes the computational photography pipeline from a set of captured frames.
     *
     * @param frameSet Captured raw frames (0.5x, 1x burst, 3x tiles).
     * @param config Pipeline tuning parameters.
     * @param onSaved Callback with Uri of saved photo or null if failed.
     */
    suspend fun processHumanVisionCapture(
        frameSet: CapturedFrameSet,
        config: HumanVisionConfig = HumanVisionConfig(),
        onSaved: (Uri?) -> Unit
    ) = withContext(Dispatchers.Default) {
        var finalBitmap: Bitmap? = null
        try {
            updateProgress(isProcessing = true, progress = 0.05f, status = "Initializing Human Vision pipeline...")

            // 1. Determine master dimensions from 0.5x reference or first 1x frame
            val masterFrame = frameSet.ultraWideReference ?: frameSet.mainFrames.firstOrNull()
            if (masterFrame == null) {
                Log.e(TAG, "No frames provided to Human Vision pipeline")
                updateProgress(isProcessing = false, progress = 0.0f, status = "Capture failed")
                withContext(Dispatchers.Main) { onSaved(null) }
                return@withContext
            }

            val masterWidth = masterFrame.width
            val masterHeight = masterFrame.height

            // 2. Optical distortion correction on 0.5x reference if present
            updateProgress(isProcessing = true, progress = 0.15f, status = "Calibrating optics & correcting lens distortion...")
            val correctedUltraWide = frameSet.ultraWideReference?.let {
                alignmentEngine.correctUltraWideDistortion(it)
            }

            // 3. Motion detection across frames to protect moving subjects from ghosting
            updateProgress(isProcessing = true, progress = 0.28f, status = "Detecting motion & protecting subjects...")
            val primaryMainFrame = frameSet.mainFrames.firstOrNull() ?: masterFrame
            val motionMask = if (config.movingObjectProtection && frameSet.mainFrames.size > 1) {
                alignmentEngine.detectMotionMask(
                    reference = primaryMainFrame,
                    secondaryFrames = frameSet.mainFrames.drop(1),
                    targetWidth = masterWidth,
                    targetHeight = masterHeight
                )
            } else {
                FloatArray(masterWidth * masterHeight)
            }

            // 4. Dense depth field estimation & 4-zone scene classification
            updateProgress(isProcessing = true, progress = 0.42f, status = "Estimating depth field (Near, Mid, Far, Horizon)...")
            val depthField = depthEngine.estimateSceneDepth(
                ultraWideRef = correctedUltraWide,
                mainFrame = primaryMainFrame,
                targetWidth = masterWidth,
                targetHeight = masterHeight
            )

            // 5. Stitching overlapping 3x distant tiles into a high-acuity mosaic
            updateProgress(isProcessing = true, progress = 0.58f, status = "Synthesizing 3× distant acuity mosaic...")
            val distantMosaic = if (frameSet.zoomTiles.isNotEmpty()) {
                mosaicEngine.stitchDistantTiles(
                    tiles = frameSet.zoomTiles,
                    targetWidth = masterWidth,
                    targetHeight = masterHeight
                )
            } else null

            // 6. Smart Depth-Aware 3-Layer Fusion
            updateProgress(isProcessing = true, progress = 0.72f, status = "Executing depth-aware multi-layer fusion...")
            val fusedBitmap = fusionEngine.fuseLayers(
                ultraWideRef = correctedUltraWide ?: primaryMainFrame,
                mainFrames = frameSet.mainFrames.ifEmpty { listOf(primaryMainFrame) },
                distantMosaic = distantMosaic,
                depthField = depthField,
                motionMask = motionMask,
                config = config
            )

            // 7. Human Perspective Perception Correction (geometry-preserving ARAP grid deformation)
            updateProgress(isProcessing = true, progress = 0.86f, status = "Applying natural human perspective correction...")
            finalBitmap = perspectiveCorrector.applyNaturalPerspective(
                fusedImage = fusedBitmap,
                depthField = depthField,
                acuityStrength = config.perspectiveAcuity
            )

            if (fusedBitmap != finalBitmap) {
                fusedBitmap.recycle()
            }
            distantMosaic?.mosaicBitmap?.recycle()
            correctedUltraWide?.recycle()

            // 8. Save photograph to MediaStore
            updateProgress(isProcessing = true, progress = 0.95f, status = "Finalizing photo...")
            val savedUri = saveBitmapToGallery(finalBitmap)

            updateProgress(isProcessing = false, progress = 1.0f, status = "Complete")
            withContext(Dispatchers.Main) {
                onSaved(savedUri)
            }

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error during Human Vision processing, executing graceful fallback", e)
            updateProgress(isProcessing = false, progress = 0.0f, status = "Saved fallback photo")

            // RESILIENT FALLBACK: Save best available single frame so user photo is NEVER lost!
            val fallback = frameSet.mainFrames.firstOrNull() ?: frameSet.ultraWideReference
            val fallbackUri = fallback?.let { saveBitmapToGallery(it) }
            withContext(Dispatchers.Main) {
                onSaved(fallbackUri)
            }
        } finally {
            finalBitmap?.recycle()
        }
    }

    private fun updateProgress(isProcessing: Boolean, progress: Float, status: String) {
        _progressState.value = HumanVisionProgress(
            isProcessing = isProcessing,
            progress = progress,
            statusText = status
        )
    }

    /**
     * Persists photo directly into the Android MediaStore DCIM/Camera directory.
     */
    private suspend fun saveBitmapToGallery(bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_HUMAN_VISION_${timeStamp}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                Log.d(TAG, "Successfully saved Human Vision photo to $uri")
                return@withContext uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write photo stream to MediaStore", e)
                resolver.delete(uri, null, null)
            }
        }
        null
    }
}
