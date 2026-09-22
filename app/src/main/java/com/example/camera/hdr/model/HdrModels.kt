package com.example.camera.hdr.model

import android.util.Range

/**
 * Operating mode for the Adaptive Dual-Exposure HDR Video system.
 */
enum class AdaptiveHdrMode {
    OFF,
    AUTO,
    ALWAYS_ON
}

/**
 * Strength multiplier for dual-exposure separation.
 */
enum class HdrExposureStrength(val minEvDelta: Float, val maxEvDelta: Float) {
    AUTO(1.0f, 3.5f),
    MILD(0.8f, 1.6f),
    STANDARD(1.4f, 2.6f),
    HIGH(2.0f, 3.5f),
    MAXIMUM(2.5f, 4.5f)
}

/**
 * Background queue worker priority.
 */
enum class ProcessingPriority {
    MAXIMUM,
    BALANCED,
    BATTERY_SAVER
}

/**
 * Source capture stream preference.
 */
enum class HdrSourceCapture {
    RAW_IF_SUPPORTED,
    HIGHEST_QUALITY_SUPPORTED,
    AUTOMATIC
}

/**
 * Output encoding format.
 */
enum class HdrOutputFormat {
    HDR_10BIT,
    HDR_PLUS_SDR_COMPAT
}

/**
 * Lifecycle status of an HDR video job.
 */
enum class HdrJobStatus {
    CAPTURING,
    QUEUED,
    PROCESSING,
    HDR_MERGE,
    ENCODING,
    COMPLETE,
    PAUSED,
    FAILED,
    CANCELLED
}

/**
 * Calculated dual-exposure parameters for Short (A) and Long (B) exposures.
 */
data class HdrExposurePair(
    val shortExposureNs: Long,
    val shortIso: Int,
    val longExposureNs: Long,
    val longIso: Int,
    val evDelta: Float,
    val confidence: Float = 1.0f,
    val sceneDynamicRangeEv: Float = 6.0f
)

/**
 * Real-time scene analysis metrics derived from the low-resolution metering pipeline.
 */
data class SceneAnalysisMetrics(
    val overallLuminance: Float = 0.5f,
    val medianLuminance: Float = 0.5f,
    val highlightClippingPercent: Float = 0f,
    val shadowUnderexposurePercent: Float = 0f,
    val estimatedDynamicRangeEv: Float = 6.0f,
    val extremeHighlightPercent: Float = 0f,
    val extremeShadowPercent: Float = 0f,
    val histogram: IntArray = IntArray(64),
    val significantChangeDetected: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SceneAnalysisMetrics
        return overallLuminance == other.overallLuminance &&
                medianLuminance == other.medianLuminance &&
                highlightClippingPercent == other.highlightClippingPercent &&
                shadowUnderexposurePercent == other.shadowUnderexposurePercent &&
                estimatedDynamicRangeEv == other.estimatedDynamicRangeEv &&
                extremeHighlightPercent == other.extremeHighlightPercent &&
                extremeShadowPercent == other.extremeShadowPercent &&
                significantChangeDetected == other.significantChangeDetected
    }

    override fun hashCode(): Int {
        var result = overallLuminance.hashCode()
        result = 31 * result + medianLuminance.hashCode()
        result = 31 * result + highlightClippingPercent.hashCode()
        result = 31 * result + shadowUnderexposurePercent.hashCode()
        result = 31 * result + estimatedDynamicRangeEv.hashCode()
        result = 31 * result + extremeHighlightPercent.hashCode()
        result = 31 * result + extremeShadowPercent.hashCode()
        result = 31 * result + significantChangeDetected.hashCode()
        return result
    }
}

/**
 * Real-time progress update for background processing.
 */
data class HdrProcessingProgress(
    val jobId: String,
    val status: HdrJobStatus,
    val progressPercent: Int = 0,
    val estimatedRemainingSec: Int = 0,
    val processingSpeedFps: Float = 0f,
    val originalSourceSizeBytes: Long = 0L,
    val finalHdrSizeBytes: Long = 0L,
    val currentFrameIndex: Int = 0,
    val totalFrames: Int = 0,
    val errorDescription: String? = null
)

/**
 * Hardware capability probe results for the active sensor and platform.
 */
data class HdrDeviceCapabilities(
    val supports60Fps: Boolean = true,
    val supportsManualExposure: Boolean = true,
    val supportsRaw: Boolean = false,
    val supportsRawAt60Fps: Boolean = false,
    val supportsYuvHighSpeed: Boolean = true,
    val exposureTimeRangeNs: Range<Long> = Range(10_000L, 1_000_000_000L),
    val sensitivityRange: Range<Int> = Range(100, 3200),
    val supportsHevc10Bit: Boolean = true,
    val isGenuineDualExposureSupported: Boolean = true,
    val recommendedSource: HdrSourceCapture = HdrSourceCapture.HIGHEST_QUALITY_SUPPORTED
)
