package com.example.camera.motionphoto

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main coordinator for Motion Photo capture, pre/post shutter frame buffering,
 * hardware encoding, and Google Photos XMP packaging.
 */
class MotionPhotoEngine(private val context: Context) {

    companion object {
        private const val TAG = "MotionPhotoEngine"
        private const val FRAME_INTERVAL_NS = 30_000_000L // 30ms throttle (~30 FPS sampling)
    }

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val frameBuffer = MotionPhotoFrameBuffer(maxBeforeDurationMs = 1000L)
    private val videoEncoder = MotionPhotoVideoEncoder()

    private val _isRecordingPostShutter = MutableStateFlow(false)
    val isRecordingPostShutter: StateFlow<Boolean> = _isRecordingPostShutter.asStateFlow()

    private val _isProcessingMotionPhoto = MutableStateFlow(false)
    val isProcessingMotionPhoto: StateFlow<Boolean> = _isProcessingMotionPhoto.asStateFlow()

    private val isCapturing = AtomicBoolean(false)
    private val postShutterFrames = Collections.synchronizedList(mutableListOf<MotionFrame>())

    private var activeShutterTimestampNs: Long = 0L
    private var activePreShutterFrames: List<MotionFrame> = emptyList()
    private var lastSampleTimestampNs = 0L

    /**
     * Called by the camera viewfinder / preview loop on each rendered frame.
     */
    fun onPreviewFrame(
        bitmap: Bitmap,
        orientationDegrees: Int,
        isFrontCamera: Boolean,
        isEnabled: Boolean
    ) {
        if (!isEnabled || bitmap.isRecycled) return

        val nowNs = System.nanoTime()
        if (nowNs - lastSampleTimestampNs < FRAME_INTERVAL_NS) {
            return
        }
        lastSampleTimestampNs = nowNs

        if (isCapturing.get()) {
            // Post-shutter collection
            val frameCopy = MotionFrame(
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                timestampNs = nowNs,
                orientationDegrees = orientationDegrees,
                isFrontCamera = isFrontCamera
            )
            postShutterFrames.add(frameCopy)
        } else {
            // Pre-shutter ring buffer
            frameBuffer.addFrame(
                sourceBitmap = bitmap,
                timestampNs = nowNs,
                orientationDegrees = orientationDegrees,
                isFrontCamera = isFrontCamera
            )
        }
    }

    /**
     * Begins the Motion Photo shutter capture sequence:
     * - Freezes pre-shutter frames from the circular buffer.
     * - Records post-shutter frames for [duration.afterDurationMs].
     */
    fun beginMotionCapture(
        duration: MotionPhotoDuration,
        shutterTimestampNs: Long = System.nanoTime(),
        onPostShutterFinished: () -> Unit
    ) {
        activeShutterTimestampNs = shutterTimestampNs
        frameBuffer.updateMaxDuration(duration.beforeDurationMs)

        // Snapshot pre-shutter frames
        activePreShutterFrames = frameBuffer.extractPreShutterFrames(
            shutterTimestampNs = shutterTimestampNs,
            durationMs = duration.beforeDurationMs
        )
        postShutterFrames.clear()

        isCapturing.set(true)
        _isRecordingPostShutter.value = true

        engineScope.launch {
            delay(duration.afterDurationMs)
            isCapturing.set(false)
            _isRecordingPostShutter.value = false
            withContext(Dispatchers.Main) {
                onPostShutterFinished()
            }
        }
    }

    /**
     * Combines the captured still photo JPEG bytes with the recorded motion clip,
     * builds Google Photos XMP metadata, and returns the single Motion Photo JPEG file bytes.
     */
    suspend fun finalizeMotionPhoto(
        stillJpegBytes: ByteArray,
        duration: MotionPhotoDuration,
        orientationDegrees: Int
    ): ByteArray = withContext(Dispatchers.Default) {
        _isProcessingMotionPhoto.value = true
        try {
            // Assemble ordered frame sequence
            val allFrames = mutableListOf<MotionFrame>()
            allFrames.addAll(activePreShutterFrames)
            allFrames.addAll(postShutterFrames)

            val preCount = activePreShutterFrames.size

            if (allFrames.isEmpty()) {
                val stillBmp = android.graphics.BitmapFactory.decodeByteArray(stillJpegBytes, 0, stillJpegBytes.size)
                if (stillBmp != null) {
                    val frameCount = (duration.totalDurationMs * 30 / 1000).toInt().coerceAtLeast(30)
                    val baseNs = System.nanoTime()
                    for (i in 0 until frameCount) {
                        allFrames.add(
                            MotionFrame(
                                bitmap = stillBmp,
                                timestampNs = baseNs + i * 33_333_333L,
                                orientationDegrees = orientationDegrees
                            )
                        )
                    }
                }
            }

            if (allFrames.isEmpty()) {
                Log.w(TAG, "No motion frames captured, returning plain still photo")
                return@withContext stillJpegBytes
            }

            // Sort by timestamp
            allFrames.sortBy { it.timestampNs }

            val frameIntervalUs = 1_000_000L / 30L
            val presentationTimestampUs = (preCount.toLong() * frameIntervalUs).coerceIn(0L, (allFrames.size.toLong() * frameIntervalUs))

            val tempVideoFile = File(context.cacheDir, "motion_temp_${System.currentTimeMillis()}.mp4")
            val firstBmp = allFrames.first().bitmap
            val targetW = if (firstBmp.width > 0) firstBmp.width else 1080
            val targetH = if (firstBmp.height > 0) firstBmp.height else 1920

            val encodeSuccess = videoEncoder.encodeFrames(
                frames = allFrames,
                outputFile = tempVideoFile,
                targetWidth = targetW,
                targetHeight = targetH,
                orientationDegrees = orientationDegrees
            )

            if (!encodeSuccess || !tempVideoFile.exists() || tempVideoFile.length() <= 0L) {
                Log.w(TAG, "Video encoding failed, returning plain still photo")
                return@withContext stillJpegBytes
            }

            val videoBytes = tempVideoFile.readBytes()
            tempVideoFile.delete()

            // Pack single JPEG Motion Photo with embedded XMP and appended MP4 video
            val packedBytes = MotionPhotoXmpPacker.packMotionPhoto(
                stillJpegBytes = stillJpegBytes,
                motionMp4Bytes = videoBytes,
                presentationTimestampUs = presentationTimestampUs
            )

            // Clean up frame bitmaps
            postShutterFrames.forEach { frame ->
                if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            }
            postShutterFrames.clear()
            activePreShutterFrames = emptyList()

            packedBytes
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing Motion Photo", e)
            stillJpegBytes
        } finally {
            _isProcessingMotionPhoto.value = false
        }
    }

    fun clearBuffer() {
        frameBuffer.clear()
        postShutterFrames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        postShutterFrames.clear()
        activePreShutterFrames = emptyList()
        isCapturing.set(false)
        _isRecordingPostShutter.value = false
        _isProcessingMotionPhoto.value = false
    }
}
