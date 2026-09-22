package com.example.camera.ultrafast.model

import android.net.Uri

/**
 * Real-time capture and asynchronous background processing progress state for Ultra Fast Shutter.
 */
data class UltraFastProgressState(
    val isCapturing: Boolean = false,
    val isProcessing: Boolean = false,
    val isContinuousHolding: Boolean = false,
    val burstId: String? = null,
    val targetFps: Int = 15,
    val totalFrames: Int = 0,
    val acquiredFrames: Int = 0,
    val processedFrames: Int = 0,
    val statusText: String = "",
    val latestSavedUri: Uri? = null
) {
    val captureProgress: Float
        get() = if (totalFrames > 0) (acquiredFrames.toFloat() / totalFrames).coerceIn(0f, 1f) else 0f

    val processingProgress: Float
        get() = if (totalFrames > 0) (processedFrames.toFloat() / totalFrames).coerceIn(0f, 1f) else 0f

    val isIdle: Boolean
        get() = !isCapturing && !isProcessing && !isContinuousHolding
}
