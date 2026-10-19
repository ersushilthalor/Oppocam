package com.example.camera.engine

import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min

/**
 * In-memory raw/uncompressed focus plane frame captured during instant refocus burst.
 */
data class RawRefocusFrame(
    val index: Int,
    val focusDiopter: Float,
    val width: Int,
    val height: Int,
    val nv21Bytes: ByteArray? = null,
    val jpegBytes: ByteArray? = null,
    val timestampNs: Long = 0L,
    val format: Int = if (nv21Bytes != null) ImageFormat.NV21 else ImageFormat.JPEG
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RawRefocusFrame
        return index == other.index && timestampNs == other.timestampNs
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + timestampNs.hashCode()
        return result
    }
}

/**
 * Thread-safe memory pool of pre-allocated byte buffers for high-speed refocus burst acquisition.
 * Prevents memory thrashing, GC pauses, and buffer starvation during 3F, 5F, and 7F bursts.
 */
class RefocusBufferPool(
    val frameByteSize: Int,
    val maxPoolCapacity: Int = 10
) {
    companion object {
        private const val TAG = "RefocusBufferPool"
    }

    private val pool = ConcurrentLinkedQueue<ByteArray>()
    private val allocatedCount = AtomicInteger(0)

    fun acquire(): ByteArray? {
        val pooled = pool.poll()
        if (pooled != null && pooled.size == frameByteSize) {
            return pooled
        }

        if (!isMemorySafe()) {
            Log.w(TAG, "Low memory headroom detected - refocus frame allocation throttled")
            return null
        }

        return try {
            allocatedCount.incrementAndGet()
            ByteArray(frameByteSize)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM allocating refocus buffer", oom)
            null
        }
    }

    fun recycle(buffer: ByteArray) {
        if (buffer.size == frameByteSize && pool.size < maxPoolCapacity) {
            pool.offer(buffer)
        }
    }

    fun clear() {
        pool.clear()
        allocatedCount.set(0)
    }

    private fun isMemorySafe(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val availableMemory = maxMemory - (totalMemory - freeMemory)
        return availableMemory > 48 * 1024 * 1024
    }
}

/**
 * High-performance utilities for non-blocking RAW / uncompressed ImageReader acquisition
 * and cross-plane image alignment.
 */
object RefocusImageUtils {
    private const val TAG = "RefocusImageUtils"

    /**
     * Fast, stride-aware extraction of NV21 byte buffer from Camera2 YUV_420_888 Image.
     */
    fun extractNv21(
        image: Image,
        outBuffer: ByteArray,
        width: Int,
        height: Int
    ): Boolean {
        return try {
            val planes = image.planes
            if (planes.size < 3) return false

            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            var offset = 0

            // Copy Y plane
            if (yRowStride == width && yPixelStride == 1) {
                yBuffer.position(0)
                yBuffer.get(outBuffer, 0, width * height)
                offset = width * height
            } else {
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    if (yPixelStride == 1) {
                        yBuffer.get(outBuffer, offset, width)
                        offset += width
                    } else {
                        for (col in 0 until width) {
                            outBuffer[offset++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                        }
                    }
                }
            }

            // Copy NV21 interleaved VU plane (V first, then U)
            val uvHeight = height / 2
            val uvWidth = width / 2

            for (row in 0 until uvHeight) {
                val vRowOffset = row * vRowStride
                val uRowOffset = row * uRowStride
                for (col in 0 until uvWidth) {
                    outBuffer[offset++] = vBuffer.get(vRowOffset + col * uvPixelStride)
                    outBuffer[offset++] = uBuffer.get(uRowOffset + col * uvPixelStride)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting NV21 from Camera2 Image", e)
            false
        }
    }

    /**
     * Extracts downsampled normalized luma directly from an NV21 byte buffer without Bitmap decoding.
     */
    fun extractDownsampledY(
        nv21: ByteArray,
        fullW: Int,
        fullH: Int,
        targetW: Int,
        targetH: Int
    ): FloatArray {
        val result = FloatArray(targetW * targetH)
        val xStep = fullW.toFloat() / targetW
        val yStep = fullH.toFloat() / targetH

        for (ty in 0 until targetH) {
            val sy = (ty * yStep).toInt().coerceIn(0, fullH - 1)
            val rowOffset = sy * fullW
            val outOffset = ty * targetW
            for (tx in 0 until targetW) {
                val sx = (tx * xStep).toInt().coerceIn(0, fullW - 1)
                val byteVal = nv21[rowOffset + sx].toInt() and 0xFF
                result[outOffset + tx] = byteVal.toFloat()
            }
        }
        return result
    }

    /**
     * Estimates rigid translation shift (dx, dy) between a reference luma grid and a target luma grid.
     */
    fun computeAlignmentShift(
        refLuma: FloatArray,
        targetLuma: FloatArray,
        w: Int,
        h: Int,
        searchRadius: Int = 4
    ): Pair<Float, Float> {
        if (refLuma.size != w * h || targetLuma.size != w * h || w < 16 || h < 16) {
            return Pair(0f, 0f)
        }

        var bestDx = 0
        var bestDy = 0
        var minSad = Float.MAX_VALUE

        val step = 2
        for (dy in -searchRadius..searchRadius step step) {
            for (dx in -searchRadius..searchRadius step step) {
                var sad = 0f
                var count = 0
                for (y in searchRadius until (h - searchRadius) step 4) {
                    val refRow = y * w
                    val targetRow = (y + dy) * w
                    for (x in searchRadius until (w - searchRadius) step 4) {
                        val refVal = refLuma[refRow + x]
                        val targetVal = targetLuma[targetRow + (x + dx)]
                        sad += abs(refVal - targetVal)
                        count++
                    }
                }
                if (count > 0) {
                    val normalizedSad = sad / count
                    val isBetter = normalizedSad < minSad - 1e-4f ||
                        (abs(normalizedSad - minSad) <= 1e-4f && (dx * dx + dy * dy < bestDx * bestDx + bestDy * bestDy))
                    if (isBetter) {
                        minSad = normalizedSad
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
        }
        return Pair(bestDx.toFloat(), bestDy.toFloat())
    }
}
