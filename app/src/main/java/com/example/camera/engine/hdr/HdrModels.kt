package com.example.camera.engine.hdr

/**
 * Role of each exposure in the computational HDR bracket.
 */
enum class FrameRole {
    /**
     * Primary reference exposure (0 EV) providing natural midtones and geometric reference.
     */
    REFERENCE_BASE,

    /**
     * Short-exposure frame (-1.5 to -2.0 EV) capturing pristine unclipped highlight details
     * (clouds, skies, bright windows, specular reflections, neon signs).
     */
    SHORT_HIGHLIGHT,

    /**
     * Long-exposure frame (+1.3 to +1.7 EV) improving SNR in deep shadows.
     */
    LONG_SHADOW
}

/**
 * Type of HDR capture dynamically decided by HdrCapturePlanner.
 */
enum class HdrBracketType {
    /**
     * Single-frame capture when scene dynamic range is low, flash is active, or extreme motion exists.
     */
    SINGLE_FRAME,

    /**
     * 2-frame bracket (Base + Short Highlight) for moderate dynamic range with highlight risk.
     */
    TWO_FRAME_HIGHLIGHT,

    /**
     * 3-frame bracket (Base + Short Highlight + Long Shadow) for extreme high dynamic range scenes.
     */
    THREE_FRAME_FULL
}

/**
 * Specification for an individual exposure in the bracket.
 */
data class HdrExposureSpec(
    val role: FrameRole,
    val evOffset: Float,
    val aeCompIndex: Int,
    val isReference: Boolean
)

/**
 * Deterministic capture plan generated before firing the bracket burst.
 */
data class HdrCapturePlan(
    val bracketType: HdrBracketType,
    val specs: List<HdrExposureSpec>,
    val reason: String,
    val sceneDynamicRange: Float = 0.5f,
    val hasBlownHighlights: Boolean = false,
    val hasDeepShadows: Boolean = false,
    val estimatedIso: Int = 100,
    val estimatedExpTimeNs: Long = 33_333_333L
)

/**
 * Raw captured image container with exact per-frame hardware sensor exposure metadata and gyro motion state.
 */
data class HdrInputFrame(
    val jpegBytes: ByteArray,
    val role: FrameRole,
    val evOffset: Float,
    val exposureTimeNs: Long,
    val iso: Int,
    val timestampNs: Long,
    val gyroYawSpeed: Float = 0f,
    val gyroPitchSpeed: Float = 0f,
    val gyroRollSpeed: Float = 0f,
    val actualAeCompensation: Int = 0
) {
    /**
     * Physical sensor exposure product (ISO * exposure time in nanoseconds).
     * Used for exact radiance normalization rather than relying on nominal EV assumptions.
     */
    val exposureProduct: Double
        get() = (iso.coerceAtLeast(1).toDouble() * exposureTimeNs.coerceAtLeast(1000L).toDouble())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HdrInputFrame
        return timestampNs == other.timestampNs && role == other.role
    }

    override fun hashCode(): Int {
        var result = timestampNs.hashCode()
        result = 31 * result + role.hashCode()
        return result
    }
}

/**
 * 2D Local alignment mesh providing dense patch-level translation vectors across the frame.
 * Captures handheld micro-rotation, lens perspective warp, and local parallax without tearing.
 */
class HdrLocalMesh(
    val cols: Int,
    val rows: Int,
    private val dxGrid: FloatArray,
    private val dyGrid: FloatArray
) {
    /**
     * Samples dense interpolated local offset delta (dx, dy) at normalized coordinates [0.0 .. 1.0].
     */
    fun getLocalOffset(normX: Float, normY: Float): Pair<Float, Float> {
        val px = (normX * (cols - 1)).coerceIn(0f, (cols - 1).toFloat())
        val py = (normY * (rows - 1)).coerceIn(0f, (rows - 1).toFloat())

        val x0 = px.toInt()
        val y0 = py.toInt()
        val x1 = (x0 + 1).coerceAtMost(cols - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)

        val fx = px - x0
        val fy = py - y0

        val idx00 = y0 * cols + x0
        val idx10 = y0 * cols + x1
        val idx01 = y1 * cols + x0
        val idx11 = y1 * cols + x1

        val dxTop = dxGrid[idx00] * (1f - fx) + dxGrid[idx10] * fx
        val dxBottom = dxGrid[idx01] * (1f - fx) + dxGrid[idx11] * fx
        val dx = dxTop * (1f - fy) + dxBottom * fy

        val dyTop = dyGrid[idx00] * (1f - fx) + dyGrid[idx10] * fx
        val dyBottom = dyGrid[idx01] * (1f - fx) + dyGrid[idx11] * fx
        val dy = dyTop * (1f - fy) + dyBottom * fy

        return Pair(dx, dy)
    }
}

/**
 * Global and local alignment result for secondary frames against the base reference frame.
 */
data class HdrAlignmentResult(
    val shiftX: Float,
    val shiftY: Float,
    val confidence: Float,
    val isAligned: Boolean = true,
    val localMesh: HdrLocalMesh? = null
) {
    /**
     * Evaluates total displacement (global shift + local mesh) at normalized coordinate (normX, normY)
     * in full-resolution image pixels.
     */
    fun getTotalDisplacement(normX: Float, normY: Float): Pair<Float, Float> {
        if (localMesh == null) {
            return Pair(shiftX, shiftY)
        }
        val (localDx, localDy) = localMesh.getLocalOffset(normX, normY)
        return Pair(shiftX + localDx, shiftY + localDy)
    }
}

/**
 * Spatially varying continuous motion confidence mask [0.0 = static scene, 1.0 = moving subject].
 */
class HdrMotionMask(
    val width: Int,
    val height: Int,
    val mask: FloatArray
) {
    fun sampleBilinear(normX: Float, normY: Float): Float {
        val px = (normX * (width - 1)).coerceIn(0f, (width - 1).toFloat())
        val py = (normY * (height - 1)).coerceIn(0f, (height - 1).toFloat())

        val x0 = px.toInt()
        val y0 = py.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)

        val fx = px - x0
        val fy = py - y0

        val v00 = mask[y0 * width + x0]
        val v10 = mask[y0 * width + x1]
        val v01 = mask[y1 * width + x0]
        val v11 = mask[y1 * width + x1]

        val top = v00 * (1f - fx) + v10 * fx
        val bottom = v01 * (1f - fx) + v11 * fx
        return top * (1f - fy) + bottom * fy
    }
}

/**
 * Diagnostic metrics for performance and image quality monitoring.
 */
data class HdrProcessingDiagnostics(
    val plan: HdrCapturePlan,
    val alignedFramesCount: Int,
    val motionAreaRatio: Float,
    val executionTimeMs: Long,
    val highlightsRecovered: Boolean,
    val shadowsRecovered: Boolean
)
