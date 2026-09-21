package com.example.camera.pipeline.engine

import android.graphics.Bitmap
import android.net.Uri
import com.example.camera.model.LensInfo
import com.example.camera.pipeline.model.CustomPipelineParams
import com.example.camera.pipeline.model.PipelinePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Encapsulates the pristine, uncompressed camera capture source (prior to any JPEG encoding).
 */
data class PipelineCaptureSource(
    val id: String = System.currentTimeMillis().toString(),
    val fullResBitmap: Bitmap,             // Pristine uncompressed sensor data (from RAW/YUV)
    val previewBitmap: Bitmap,             // Scaled fast proxy for live 60fps interactive slider adjustments
    val orientationDegrees: Int,
    val isFrontFacing: Boolean,
    val lensInfo: LensInfo?,
    val timestamp: Long = System.currentTimeMillis(),
    val appliedPreset: PipelinePreset,
    val appliedParams: CustomPipelineParams,
    var savedUri: Uri? = null
)

/**
 * In-memory cache holding the latest captured pristine camera frame.
 * This enables:
 * 1. Instant Before/After split comparison
 * 2. Real-time preset switching without re-taking the photo
 * 3. Re-rendering high-resolution output with adjusted pipeline parameters
 */
object PipelineCaptureCache {

    private val _latestCapture = MutableStateFlow<PipelineCaptureSource?>(null)
    val latestCapture: StateFlow<PipelineCaptureSource?> = _latestCapture.asStateFlow()

    @Synchronized
    fun setCapture(source: PipelineCaptureSource) {
        val old = _latestCapture.value
        _latestCapture.value = source
        // Recycle old bitmaps if they are not the same instance
        if (old != null && old.id != source.id) {
            if (!old.fullResBitmap.isRecycled && old.fullResBitmap != source.fullResBitmap) {
                old.fullResBitmap.recycle()
            }
            if (!old.previewBitmap.isRecycled && old.previewBitmap != source.previewBitmap) {
                old.previewBitmap.recycle()
            }
        }
    }

    @Synchronized
    fun getCapture(): PipelineCaptureSource? = _latestCapture.value

    @Synchronized
    fun updateSavedUri(uri: Uri?) {
        _latestCapture.value?.savedUri = uri
    }

    @Synchronized
    fun clear() {
        val current = _latestCapture.value
        _latestCapture.value = null
        if (current != null) {
            if (!current.fullResBitmap.isRecycled) current.fullResBitmap.recycle()
            if (!current.previewBitmap.isRecycled) current.previewBitmap.recycle()
        }
    }
}
