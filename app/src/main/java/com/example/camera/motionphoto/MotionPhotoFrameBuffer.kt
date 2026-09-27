package com.example.camera.motionphoto

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Continuous circular ring buffer for pre-shutter motion frames.
 *
 * Maintains a sliding temporal window of preview frames (0.5s or 1.0s before shutter press)
 * using an object pool of reusable Bitmaps to eliminate GC pauses and memory allocations.
 */
class MotionPhotoFrameBuffer(
    private var maxBeforeDurationMs: Long = 1000L
) {
    companion object {
        private const val TAG = "MotionPhotoBuffer"
        private const val MAX_POOL_SIZE = 45
    }

    private val frameQueue = ArrayDeque<MotionFrame>()
    private val bitmapPool = ArrayDeque<Bitmap>()
    private val lock = Any()

    fun updateMaxDuration(durationMs: Long) {
        synchronized(lock) {
            maxBeforeDurationMs = durationMs
            trimOldFramesLocked(System.nanoTime())
        }
    }

    /**
     * Adds a newly sampled preview frame to the circular buffer.
     * Older frames outside the beforeDuration window are evicted and returned to the bitmap pool.
     */
    fun addFrame(
        sourceBitmap: Bitmap,
        timestampNs: Long,
        orientationDegrees: Int,
        isFrontCamera: Boolean
    ) {
        synchronized(lock) {
            trimOldFramesLocked(timestampNs)

            val width = sourceBitmap.width
            val height = sourceBitmap.height
            if (width <= 0 || height <= 0) return

            // Acquire or create a pooled bitmap
            var targetBmp = bitmapPool.pollFirst()
            if (targetBmp == null || targetBmp.width != width || targetBmp.height != height || targetBmp.isRecycled) {
                targetBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }

            // Copy pixels into target bitmap
            val canvas = android.graphics.Canvas(targetBmp)
            canvas.drawBitmap(sourceBitmap, 0f, 0f, null)

            val frame = MotionFrame(
                bitmap = targetBmp,
                timestampNs = timestampNs,
                orientationDegrees = orientationDegrees,
                isFrontCamera = isFrontCamera
            )
            frameQueue.addLast(frame)
        }
    }

    /**
     * Extracts all buffered frames occurring within [durationMs] prior to the shutter press timestamp.
     * The extracted frames are detached from the ring buffer.
     */
    fun extractPreShutterFrames(shutterTimestampNs: Long, durationMs: Long): List<MotionFrame> {
        val cutoffNs = shutterTimestampNs - (durationMs * 1_000_000L)
        synchronized(lock) {
            val result = mutableListOf<MotionFrame>()
            while (frameQueue.isNotEmpty()) {
                val frame = frameQueue.removeFirst()
                if (frame.timestampNs >= cutoffNs && frame.timestampNs <= shutterTimestampNs) {
                    result.add(frame)
                } else if (frame.timestampNs < cutoffNs) {
                    recycleBitmapLocked(frame.bitmap)
                } else {
                    // Frame after shutter timestamp (can happen if rapid)
                    result.add(frame)
                }
            }
            return result
        }
    }

    private fun trimOldFramesLocked(currentTimestampNs: Long) {
        val cutoffNs = currentTimestampNs - (maxBeforeDurationMs * 1_000_000L)
        while (frameQueue.isNotEmpty() && frameQueue.first.timestampNs < cutoffNs) {
            val oldFrame = frameQueue.removeFirst()
            recycleBitmapLocked(oldFrame.bitmap)
        }
    }

    private fun recycleBitmapLocked(bmp: Bitmap) {
        if (!bmp.isRecycled && bitmapPool.size < MAX_POOL_SIZE) {
            bitmapPool.addLast(bmp)
        } else if (!bmp.isRecycled) {
            bmp.recycle()
        }
    }

    fun clear() {
        synchronized(lock) {
            while (frameQueue.isNotEmpty()) {
                val f = frameQueue.removeFirst()
                if (!f.bitmap.isRecycled) f.bitmap.recycle()
            }
            while (bitmapPool.isNotEmpty()) {
                val b = bitmapPool.removeFirst()
                if (!b.isRecycled) b.recycle()
            }
        }
    }

    val currentFrameCount: Int
        get() = synchronized(lock) { frameQueue.size }
}
