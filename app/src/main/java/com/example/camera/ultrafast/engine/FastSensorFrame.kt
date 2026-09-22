package com.example.camera.ultrafast.engine

/**
 * Lightweight, in-memory representation of an uncompressed real sensor frame.
 * Holds planar NV21 byte array from pre-allocated memory pool without object bloat.
 */
class FastSensorFrame(
    val burstId: String,
    val frameIndex: Int,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val sensorOrientation: Int,
    val isFrontFacing: Boolean,
    val saveMirrored: Boolean,
    val yuvData: ByteArray,
    val targetFps: Int
)
