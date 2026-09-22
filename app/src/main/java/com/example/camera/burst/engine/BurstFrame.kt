package com.example.camera.burst.engine

/**
 * High-speed sensor frame model for Fast Shutter / Burst mode.
 * Holds planar NV21 byte array from pre-allocated memory pool.
 * Preserves burstSessionId, frameIndex, and timestamp for strict ordering.
 */
class BurstFrame(
    val burstSessionId: String,
    val frameIndex: Int,
    val timestampNs: Long,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val sensorOrientation: Int,
    val isFrontFacing: Boolean,
    val saveMirrored: Boolean,
    val yuvData: ByteArray,
    val targetFps: Int
)
