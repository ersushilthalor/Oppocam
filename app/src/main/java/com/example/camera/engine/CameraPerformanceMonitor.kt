package com.example.camera.engine

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight performance monitor measuring Camera preview FPS, UI frame times,
 * active YUV burst throughput, and system resource utilization without overhead.
 */
object CameraPerformanceMonitor {
    private const val TAG = "CameraPerf"
    private const val LOG_INTERVAL_MS = 2500L

    private val previewFrameCounter = AtomicInteger(0)
    private val yuvFrameCounter = AtomicInteger(0)

    @Volatile private var lastReportTimeMs = SystemClock.elapsedRealtime()
    @Volatile private var activeOutputsDescription = "[PREVIEW]"

    @Volatile var currentPreviewFps = 0.0f
        private set
    @Volatile var currentYuvFps = 0.0f
        private set
    @Volatile var currentUiFrameTimeMs = 16.6f
        private set

    private var lastUiFrameNanos = 0L
    private val uiFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastUiFrameNanos > 0L) {
                val deltaMs = (frameTimeNanos - lastUiFrameNanos) / 1_000_000f
                if (deltaMs in 1.0f..100.0f) {
                    currentUiFrameTimeMs = currentUiFrameTimeMs * 0.9f + deltaMs * 0.1f
                }
            }
            lastUiFrameNanos = frameTimeNanos
            if (isMonitoring) {
                try {
                    Choreographer.getInstance().postFrameCallback(this)
                } catch (ignored: Throwable) {}
            }
        }
    }

    @Volatile private var isMonitoring = false

    fun start() {
        if (isMonitoring) return
        isMonitoring = true
        lastReportTimeMs = SystemClock.elapsedRealtime()
        previewFrameCounter.set(0)
        yuvFrameCounter.set(0)
        try {
            Choreographer.getInstance().postFrameCallback(uiFrameCallback)
        } catch (ignored: Throwable) {}
    }

    fun stop() {
        isMonitoring = false
        try {
            Choreographer.getInstance().removeFrameCallback(uiFrameCallback)
        } catch (ignored: Throwable) {}
    }

    fun onPreviewFrame() {
        previewFrameCounter.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastReportTimeMs
        if (elapsed >= LOG_INTERVAL_MS) {
            synchronized(this) {
                val delta = now - lastReportTimeMs
                if (delta >= LOG_INTERVAL_MS) {
                    val pCount = previewFrameCounter.getAndSet(0)
                    val yCount = yuvFrameCounter.getAndSet(0)
                    lastReportTimeMs = now
                    currentPreviewFps = (pCount * 1000f) / delta
                    currentYuvFps = (yCount * 1000f) / delta

                    val runtime = Runtime.getRuntime()
                    val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

                    Log.i(
                        TAG,
                        String.format(
                            "Preview: %.1f FPS | UI: %.1f ms | YUV: %.1f FPS | Outputs: %s | UsedHeap: %dMB",
                            currentPreviewFps,
                            currentUiFrameTimeMs,
                            currentYuvFps,
                            activeOutputsDescription,
                            usedMemMb
                        )
                    )
                }
            }
        }
    }

    fun onYuvFrame() {
        yuvFrameCounter.incrementAndGet()
    }

    fun setActiveOutputs(outputs: List<String>) {
        activeOutputsDescription = outputs.joinToString(prefix = "[", postfix = "]")
    }
}
