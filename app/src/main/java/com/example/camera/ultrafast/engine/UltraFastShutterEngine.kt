package com.example.camera.ultrafast.engine

import android.content.Context
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import com.example.camera.burst.engine.BurstEngine
import com.example.camera.ultrafast.data.UltraFastBurstRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade forwarding calls to the dedicated [BurstEngine].
 * Replaces the legacy implementation with the Galaxy Camera proven architecture.
 */
class UltraFastShutterEngine(
    context: Context,
    burstRepository: UltraFastBurstRepository
) {
    val burstEngine = BurstEngine(context, burstRepository)

    val liveFrameCount: StateFlow<Int> = burstEngine.liveCaptureCount
    val isHolding: StateFlow<Boolean> = burstEngine.isHolding
    val isProcessingQueue: StateFlow<Boolean> = burstEngine.isProcessingQueue

    fun getCaptureHandler(): Handler? = burstEngine.getCaptureHandler()

    fun configureFramePool(width: Int, height: Int) {
        burstEngine.configureBufferPool(width, height)
    }

    fun startContinuousBurst(
        burstId: String,
        fps: Int,
        onComplete: (Uri?) -> Unit
    ) {
        burstEngine.startBurst(burstId, fps, onComplete)
    }

    fun stopContinuousBurst() {
        burstEngine.stopBurst()
    }

    fun isBurstActive(): Boolean = burstEngine.isBurstActive()

    fun onSensorImageAvailable(
        reader: ImageReader,
        sensorOrientation: Int,
        isFrontFacing: Boolean,
        saveMirrored: Boolean
    ) {
        burstEngine.onSensorImageAvailable(reader, sensorOrientation, isFrontFacing, saveMirrored)
    }

    fun reset() {
        burstEngine.reset()
    }

    fun release() {
        burstEngine.release()
    }
}
