package com.example.camera.ultrafast.engine

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe memory pool of pre-allocated byte buffers for high-speed sensor frame acquisition.
 * Eliminates garbage collection pauses and dynamic heap allocations during 5-20 FPS bursts.
 */
class UltraFastFramePool(
    private val frameByteSize: Int,
    private val maxPoolCapacity: Int = 30
) {
    private val pool = ConcurrentLinkedQueue<ByteArray>()

    init {
        // Pre-allocate initial batch of buffers if memory permits
        val initialBatch = minOf(maxPoolCapacity / 2, 10)
        try {
            for (i in 0 until initialBatch) {
                if (isMemorySafe()) {
                    pool.offer(ByteArray(frameByteSize))
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.w("UltraFastFramePool", "Initial buffer pre-allocation reduced due to memory limit")
        }
    }

    /**
     * Obtains an available byte buffer from the pool, or allocates a new one if memory allows.
     */
    fun acquire(): ByteArray? {
        val pooled = pool.poll()
        if (pooled != null && pooled.size == frameByteSize) {
            return pooled
        }

        if (!isMemorySafe()) {
            Log.w("UltraFastFramePool", "Low memory condition detected - buffer allocation throttled")
            return null
        }

        return try {
            ByteArray(frameByteSize)
        } catch (oom: OutOfMemoryError) {
            Log.e("UltraFastFramePool", "OOM when allocating frame buffer", oom)
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
