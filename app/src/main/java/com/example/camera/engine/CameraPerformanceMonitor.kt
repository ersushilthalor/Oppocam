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
    private val recordingFrameCounter = AtomicInteger(0)

    @Volatile private var lastReportTimeMs = SystemClock.elapsedRealtime()
    @Volatile private var activeOutputsDescription = "[PREVIEW]"

    @Volatile var currentPreviewFps = 0.0f
        private set
    @Volatile var currentRecordingFps = 0.0f
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
        recordingFrameCounter.set(0)
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

    val primaryDroppedFrames = AtomicInteger(0)
    val standbyDroppedFrames = AtomicInteger(0)
    val standbySessionInitCount = AtomicInteger(0)
    val standbySessionReuseCount = AtomicInteger(0)
    val lastLensSwitchLatencyMs = java.util.concurrent.atomic.AtomicLong(0L)

    data class PerformanceSnapshot(
        val previewFps: Float,
        val uiFrameTimeMs: Float,
        val primaryDroppedFrames: Int,
        val standbyDroppedFrames: Int,
        val standbySessionInitCount: Int,
        val standbySessionReuseCount: Int,
        val lastLensSwitchLatencyMs: Long,
        val usedHeapMb: Long
    )

    fun recordPrimaryDroppedFrame() {
        primaryDroppedFrames.incrementAndGet()
    }

    fun recordStandbyDroppedFrame() {
        standbyDroppedFrames.incrementAndGet()
    }

    fun recordStandbySessionInit() {
        standbySessionInitCount.incrementAndGet()
    }

    fun recordStandbySessionReuse() {
        standbySessionReuseCount.incrementAndGet()
    }

    fun recordLensSwitchLatency(latencyMs: Long) {
        lastLensSwitchLatencyMs.set(latencyMs)
    }

    fun getPerformanceSnapshot(): PerformanceSnapshot {
        val runtime = Runtime.getRuntime()
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        return PerformanceSnapshot(
            previewFps = currentPreviewFps,
            uiFrameTimeMs = currentUiFrameTimeMs,
            primaryDroppedFrames = primaryDroppedFrames.get(),
            standbyDroppedFrames = standbyDroppedFrames.get(),
            standbySessionInitCount = standbySessionInitCount.get(),
            standbySessionReuseCount = standbySessionReuseCount.get(),
            lastLensSwitchLatencyMs = lastLensSwitchLatencyMs.get(),
            usedHeapMb = usedMemMb
        )
    }

    fun resetDiagnostics() {
        primaryDroppedFrames.set(0)
        standbyDroppedFrames.set(0)
        standbySessionInitCount.set(0)
        standbySessionReuseCount.set(0)
        lastLensSwitchLatencyMs.set(0L)
        previewFrameCounter.set(0)
        lastReportTimeMs = SystemClock.elapsedRealtime()
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
                    val rCount = recordingFrameCounter.getAndSet(0)
                    lastReportTimeMs = now
                    currentPreviewFps = (pCount * 1000f) / delta
                    currentYuvFps = (yCount * 1000f) / delta
                    currentRecordingFps = (rCount * 1000f) / delta

                    val runtime = Runtime.getRuntime()
                    val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

                    val recFpsStr = if (rCount > 0 || currentRecordingFps > 0f) String.format(" | Rec: %.1f FPS", currentRecordingFps) else ""
                    Log.i(
                        TAG,
                        String.format(
                            "Preview: %.1f FPS%s | UI: %.1f ms | Drops: [pri=%d, sby=%d] | SbyInits: %d | SbyReuses: %d | SwitchLat: %dms | UsedHeap: %dMB",
                            currentPreviewFps,
                            recFpsStr,
                            currentUiFrameTimeMs,
                            primaryDroppedFrames.get(),
                            standbyDroppedFrames.get(),
                            standbySessionInitCount.get(),
                            standbySessionReuseCount.get(),
                            lastLensSwitchLatencyMs.get(),
                            usedMemMb
                        )
                    )
                }
            }
        }
    }

    fun onRecordingFrame() {
        recordingFrameCounter.incrementAndGet()
    }

    fun onYuvFrame() {
        yuvFrameCounter.incrementAndGet()
    }

    fun setActiveOutputs(outputs: List<String>) {
        activeOutputsDescription = outputs.joinToString(prefix = "[", postfix = "]")
    }
}
