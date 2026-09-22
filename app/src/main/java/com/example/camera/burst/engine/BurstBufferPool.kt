package com.example.camera.burst.engine

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe memory pool of pre-allocated byte buffers for high-speed sensor frame acquisition.
 * Eliminates garbage collection pauses and dynamic heap allocations during 5-20 FPS bursts.
 */
class BurstBufferPool(
    val frameByteSize: Int,
    val maxPoolCapacity: Int = 30
) {
    companion object {
        private const val TAG = "BurstBufferPool"
    }

    private val pool = ConcurrentLinkedQueue<ByteArray>()
    private val allocatedCount = java.util.concurrent.atomic.AtomicInteger(0)

    // No eager allocation in init: buffers are allocated strictly on-demand during active capture
    // to keep memory footprint near zero during normal camera preview.

    /**
     * Obtains an available byte buffer from the pool, or allocates a new one if memory allows.
     */
    fun acquire(): ByteArray? {
        val pooled = pool.poll()
        if (pooled != null && pooled.size == frameByteSize) {
            return pooled
        }

        if (!isMemorySafe()) {
            Log.w(TAG, "Low memory condition detected - buffer allocation throttled")
            return null
        }

        return try {
            ByteArray(frameByteSize)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM when allocating frame buffer", oom)
            null
        }
    }

    /**
     * Returns a consumed byte buffer back to the pool for reuse.
     */
    fun recycle(buffer: ByteArray) {
        if (buffer.size == frameByteSize && pool.size < maxPoolCapacity) {
            pool.offer(buffer)
        }
    }

    /**
     * Clears all pooled buffers to release native memory when idle.
     */
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
        // Keep at least 64MB headroom
        return availableMemory > 64 * 1024 * 1024
    }
}
