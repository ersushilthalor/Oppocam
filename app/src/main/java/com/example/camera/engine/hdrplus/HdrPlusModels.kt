package com.example.camera.engine.hdrplus

/**
 * Frame count configuration for the 3-Frame Exposure Fusion pipeline.
 * Exclusively uses exactly 3 frames: Underexposed (-2 EV), Normal (0 EV), and Overexposed (+2 EV).
 */
enum class HdrPlusFrameCount(val count: Int, val label: String) {
    THREE_FRAMES(3, "3-Frame Exposure Fusion");

    companion object {
        fun fromInt(value: Int): HdrPlusFrameCount {
            return THREE_FRAMES
        }
    }
}

/**
 * Role of each exposure in the 3-Frame Exposure Fusion bracket.
 * Exactly 3 frames: Underexposed, Normal exposure, and Overexposed.
 */
enum class HdrPlusRole {
    /**
     * Underexposed frame (-1.5 to -2.5 EV) for recovering sky, bright clouds, specular highlights,
     * sun disks, and light sources without sensor clipping.
     */
    UNDER_EXPOSED,

    /**
     * Normal reference exposure (0 EV / metered AE) for midtones, base scene structure,
     * skin tones, and overall natural geometry.
     */
    NORMAL_EXPOSURE,

    /**
     * Overexposed frame (+1.5 to +2.5 EV) for lifting deep shadows, revealing dark textures,
     * and boosting low-light signal-to-noise ratio.
     */
    OVER_EXPOSED;

    companion object {
        // Backwards-compatible aliases
        val BASE_PRIMARY get() = NORMAL_EXPOSURE
        val SECONDARY_MODERATE_HIGHLIGHT get() = UNDER_EXPOSED
        val SECONDARY_EXTREME_HIGHLIGHT get() = OVER_EXPOSED
    }
}

/**
 * Planned exposure specification for an individual RAW frame in the 3-frame bracket.
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
    val evDelta: Float,
    val blackLevelPattern: FloatArray = floatArrayOf(
        blackLevel.toFloat(),
        blackLevel.toFloat(),
        blackLevel.toFloat(),
        blackLevel.toFloat()
    ),
    val colorCorrectionMatrix: FloatArray? = null,
    val postRawSensitivityBoost: Int = 100
) {
    val exposureProduct: Double
        get() = iso.coerceAtLeast(1).toDouble() *
                exposureTimeNs.coerceAtLeast(1000L).toDouble() *
                (postRawSensitivityBoost.coerceAtLeast(100).toDouble() / 100.0)

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
    val rgbLinear: FloatArray, // Interleaved R, G, B floats in linear sRGB radiance space
    val width: Int,
    val height: Int,
    val exposureTimeNs: Long,
    val iso: Int,
    val role: HdrPlusRole,
    val evDelta: Float,
    val postRawSensitivityBoost: Int = 100,
    val baseExposureGain: Float = 1.0f
) {
    val exposureProduct: Double
        get() = iso.coerceAtLeast(1).toDouble() *
                exposureTimeNs.coerceAtLeast(1000L).toDouble() *
                (postRawSensitivityBoost.coerceAtLeast(100).toDouble() / 100.0)

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
    val confidence: Float = 1.0f,
    val subpixelShiftX: Float = shiftX.toFloat(),
    val subpixelShiftY: Float = shiftY.toFloat(),
    val tileShiftsX: FloatArray? = null,
    val tileShiftsY: FloatArray? = null,
    val gridCols: Int = 1,
    val gridRows: Int = 1
)
