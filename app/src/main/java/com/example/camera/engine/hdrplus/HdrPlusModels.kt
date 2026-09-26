package com.example.camera.engine.hdrplus

/**
 * User-configurable frame count for the advanced RAW HDR+ pipeline.
 */
enum class HdrPlusFrameCount(val count: Int, val label: String) {
    TWO_FRAMES(2, "2 Frames"),
    THREE_FRAMES(3, "3 Frames");

    companion object {
        fun fromInt(value: Int): HdrPlusFrameCount {
            return when (value) {
                3 -> THREE_FRAMES
                else -> TWO_FRAMES
            }
        }
    }
}

/**
 * Role of each exposure in the RAW HDR+ capture bracket.
 */
enum class HdrPlusRole {
    /**
     * Primary reference exposure captured at user's selected main exposure (or AE metering).
     * Provides geometry, shadows, midtones, skin tones, and overall scene aesthetics.
     */
    BASE_PRIMARY,

    /**
     * Secondary underexposed frame (-1.5 to -2.0 EV) capturing cloud texture, sky gradient,
     * and moderate highlights without clipping.
     */
    SECONDARY_MODERATE_HIGHLIGHT,

    /**
     * Strongly underexposed frame (-3.0 to -4.0 EV) in 3-frame mode for extreme highlights,
     * bright sun disks, specular glints, and direct light sources.
     */
    SECONDARY_EXTREME_HIGHLIGHT
}

/**
 * Planned exposure specification for an individual RAW frame.
 */
data class HdrPlusExposureSpec(
    val role: HdrPlusRole,
    val exposureTimeNs: Long,
    val iso: Int,
    val evDelta: Float,
    val aeCompIndex: Int = 0
)

/**
 * Precomputed exposure prediction generated continuously in background before shutter press.
 */
data class HdrPlusPrediction(
    val specs: List<HdrPlusExposureSpec>,
    val highlightPressure: Float = 0f,
    val sceneDynamicRange: Float = 0.5f,
    val hasClippedHighlights: Boolean = false,
    val isOutdoorSkyDetected: Boolean = false,
    val summary: String = ""
)

/**
 * Container for high-fidelity uncompressed RAW sensor data (ImageFormat.RAW_SENSOR)
 * with calibration and per-frame hardware metadata.
 */
data class HdrPlusRawFrame(
    val rawData: ShortArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val cfaPattern: Int, // 0: RGGB, 1: GRBG, 2: GBRG, 3: BGGR
    val whiteLevel: Int,
    val blackLevel: Int,
    val rGain: Float,
    val gGain: Float,
    val bGain: Float,
    val exposureTimeNs: Long,
    val iso: Int,
    val timestampNs: Long,
    val role: HdrPlusRole,
    val evDelta: Float
) {
    val exposureProduct: Double
        get() = iso.coerceAtLeast(1).toDouble() * exposureTimeNs.coerceAtLeast(1000L).toDouble()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HdrPlusRawFrame
        return timestampNs == other.timestampNs && role == other.role
    }

    override fun hashCode(): Int {
        var result = timestampNs.hashCode()
        result = 31 * result + role.hashCode()
        return result
    }
}

/**
 * Demosaiced linear RGB float representation of a RAW frame.
 */
data class HdrPlusDevelopedImage(
    val rgbLinear: FloatArray, // Interleaved R, G, B floats in [0.0, 1.0]
    val width: Int,
    val height: Int,
    val exposureTimeNs: Long,
    val iso: Int,
    val role: HdrPlusRole,
    val evDelta: Float
) {
    val exposureProduct: Double
        get() = iso.coerceAtLeast(1).toDouble() * exposureTimeNs.coerceAtLeast(1000L).toDouble()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HdrPlusDevelopedImage
        return width == other.width && height == other.height && role == other.role
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + role.hashCode()
        return result
    }
}

/**
 * Multi-scale displacement and alignment confidence between secondary frame and base frame.
 */
data class HdrPlusAlignmentResult(
    val shiftX: Int = 0,
    val shiftY: Int = 0,
    val confidence: Float = 1.0f
)
