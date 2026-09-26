package com.example.camera.engine.hdrplus

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Executes highlight-aware multi-frame merging with subpixel bilinear interpolation,
 * motion-aware ghosting suppression, shadow-preserving toe gradation, and hue-preserving
 * filmic shoulder tone mapping to output a single high-quality JPEG image.
 */
class HdrPlusMerger {

    companion object {
        private const val TAG = "HdrPlusMerger"
        private const val HIGHLIGHT_THRESHOLD = 0.65f
        private const val FULL_CLIP_THRESHOLD = 0.94f
        private const val SHOULDER_KNEE = 0.72f
    }

    // 8192-entry precomputed linear-to-sRGB gamma LUT for high-precision shadow and highlight encoding
    private val linearToSrgbLut = FloatArray(8192) { idx ->
        val linear = idx / 8191.0f
        if (linear <= 0.0031308f) {
            12.92f * linear
        } else {
            1.055f * linear.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
        }.coerceIn(0.0f, 1.0f)
    }

    private inline fun toSrgb(linear: Float): Int {
        val clamped = linear.coerceIn(0.0f, 1.0f)
        val lutIdx = (clamped * 8191.0f + 0.5f).toInt().coerceIn(0, 8191)
        return (linearToSrgbLut[lutIdx] * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * Smooth asymptotic filmic shoulder compression that maps [0, +inf) into [0, 1.0)
     * without hard clipping recovered highlights above 1.0, while gently lifting deep shadows
     * to prevent black crush.
     */
    private inline fun applyToneCurveChannel(v: Float): Float {
        val x = v.coerceAtLeast(0.0f)
        // 1. Gentle shadow toe preservation (prevents black crush in 0.002..0.16 linear range)
        val shadowAdjusted = if (x in 0.0005f..0.18f) {
            val t = x / 0.18f
            val lift = 0.024f * t * (1.0f - t) * (1.0f - 0.35f * t)
            x + lift
        } else {
            x
        }

        // 2. True asymptotic rational shoulder above SHOULDER_KNEE (0.72f -> 1.0f)
        return if (shadowAdjusted > SHOULDER_KNEE) {
            val headroom = 1.0f - SHOULDER_KNEE // 0.28f
            val excess = shadowAdjusted - SHOULDER_KNEE
            SHOULDER_KNEE + headroom * (excess / (excess + headroom * 1.30f))
        } else {
            shadowAdjusted
        }
    }

    /**
     * Hue-preserving tone mapping that preserves R:G:B color ratios across the shoulder
     * so bright yellow, red, or sky-blue objects never shift hue.
     */
    private inline fun toneMapRgb(r: Float, g: Float, b: Float, outRgb: FloatArray) {
        val cleanR = r.coerceAtLeast(0.0f)
        val cleanG = g.coerceAtLeast(0.0f)
        val cleanB = b.coerceAtLeast(0.0f)

        val luma = 0.2126f * cleanR + 0.7152f * cleanG + 0.0722f * cleanB
        val maxCh = maxOf(cleanR, cleanG, cleanB)

        if (maxCh <= 1e-6f) {
            outRgb[0] = 0f
            outRgb[1] = 0f
            outRgb[2] = 0f
            return
        }

        // Compute luminance-driven ratio scale + per-channel curve blend
        val refSignal = 0.65f * maxCh + 0.35f * luma
        val mappedSignal = applyToneCurveChannel(refSignal)
        val scale = if (refSignal > 1e-5f) mappedSignal / refSignal else 1.0f

        val ratioR = cleanR * scale
        val ratioG = cleanG * scale
        val ratioB = cleanB * scale

        val chR = applyToneCurveChannel(cleanR)
        val chG = applyToneCurveChannel(cleanG)
        val chB = applyToneCurveChannel(cleanB)

        // Blend 80% hue-preserving ratio with 20% per-channel roll-off for natural filmic highlights
        outRgb[0] = (0.80f * ratioR + 0.20f * chR).coerceIn(0.0f, 1.0f)
        outRgb[1] = (0.80f * ratioG + 0.20f * chG).coerceIn(0.0f, 1.0f)
        outRgb[2] = (0.80f * ratioB + 0.20f * chB).coerceIn(0.0f, 1.0f)
    }

    /**
     * Evaluates local sub-pixel alignment shift (dx, dy) at pixel (x, y) by bilinearly
     * interpolating the alignment tile grid if present, or using the global sub-pixel shift.
     */
    private fun getSubpixelShiftAt(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        align: HdrPlusAlignmentResult,
        outShift: FloatArray
    ) {
        val tilesX = align.tileShiftsX
        val tilesY = align.tileShiftsY
        val cols = align.gridCols
        val rows = align.gridRows

        if (tilesX == null || tilesY == null || cols <= 1 || rows <= 1 || tilesX.size < cols * rows) {
            outShift[0] = align.subpixelShiftX
            outShift[1] = align.subpixelShiftY
            return
        }

        val gx = ((x.toFloat() / max(1, width - 1)) * (cols - 1)).coerceIn(0f, (cols - 1).toFloat())
        val gy = ((y.toFloat() / max(1, height - 1)) * (rows - 1)).coerceIn(0f, (rows - 1).toFloat())

        val c0 = floor(gx).toInt().coerceIn(0, cols - 1)
        val r0 = floor(gy).toInt().coerceIn(0, rows - 1)
        val c1 = min(cols - 1, c0 + 1)
        val r1 = min(rows - 1, r0 + 1)

        val fx = gx - c0
        val fy = gy - r0

        val idx00 = r0 * cols + c0
        val idx10 = r0 * cols + c1
        val idx01 = r1 * cols + c0
        val idx11 = r1 * cols + c1

        val sx0 = tilesX[idx00] * (1f - fx) + tilesX[idx10] * fx
        val sx1 = tilesX[idx01] * (1f - fx) + tilesX[idx11] * fx
        val sy0 = tilesY[idx00] * (1f - fx) + tilesY[idx10] * fx
        val sy1 = tilesY[idx01] * (1f - fx) + tilesY[idx11] * fx

        outShift[0] = sx0 * (1f - fy) + sx1 * fy
        outShift[1] = sy0 * (1f - fy) + sy1 * fy
    }

    /**
     * Bilinearly samples an RGB pixel from a developed image at sub-pixel coordinates (fx, fy).
     */
    private fun sampleBilinearRgb(
        rgb: FloatArray,
        width: Int,
        height: Int,
        fx: Float,
        fy: Float,
        outSample: FloatArray
    ): Boolean {
        if (fx < 0f || fx > (width - 1).toFloat() || fy < 0f || fy > (height - 1).toFloat()) {
            return false
        }
        val x0 = floor(fx).toInt().coerceIn(0, width - 1)
        val y0 = floor(fy).toInt().coerceIn(0, height - 1)
        val x1 = min(width - 1, x0 + 1)
        val y1 = min(height - 1, y0 + 1)

        val wx = fx - x0
        val wy = fy - y0
        val w00 = (1f - wx) * (1f - wy)
        val w10 = wx * (1f - wy)
        val w01 = (1f - wx) * wy
        val w11 = wx * wy

        val i00 = (y0 * width + x0) * 3
        val i10 = (y0 * width + x1) * 3
        val i01 = (y1 * width + x0) * 3
        val i11 = (y1 * width + x1) * 3

        outSample[0] = rgb[i00] * w00 + rgb[i10] * w10 + rgb[i01] * w01 + rgb[i11] * w11
        outSample[1] = rgb[i00 + 1] * w00 + rgb[i10 + 1] * w10 + rgb[i01 + 1] * w01 + rgb[i11 + 1] * w11
        outSample[2] = rgb[i00 + 2] * w00 + rgb[i10 + 2] * w10 + rgb[i01 + 2] * w01 + rgb[i11 + 2] * w11
        return true
    }

    /**
     * Computes motion / ghosting suppression weight in [0.0, 1.0].
     * Distinguishes true highlight clipping recovery (where secondary scaled radiance >= clipped base)
     * from moving object occlusion (where secondary scaled radiance drops significantly below base).
     */
    private fun computeGhostSuppressionWeight(
        lumaBase: Float,
        maxChBase: Float,
        rBase: Float,
        gBase: Float,
        bBase: Float,
        rSecScaled: Float,
        gSecScaled: Float,
        bSecScaled: Float
    ): Float {
        val lumaSecScaled = 0.2126f * rSecScaled + 0.7152f * gSecScaled + 0.0722f * bSecScaled

        // If the secondary frame is much darker than the base frame in a highlight region,
        // an object moved across the scene -> suppress ghosting strongly!
        if (lumaSecScaled < lumaBase) {
            val darkDrop = lumaBase - lumaSecScaled
            return (1.0f - ((darkDrop - 0.16f) / 0.28f)).coerceIn(0.0f, 1.0f)
        }

        // When base frame is near saturation (maxChBase > 0.88f), secondary scaled radiance is
        // expected to be equal to or brighter than the clipped base frame (recovered highlight headroom).
        if (maxChBase >= 0.88f || lumaBase >= 0.85f) {
            return 1.0f
        }

        // In the unclipped highlight transition zone (0.65..0.88), verify radiance and chromatic consistency
        val lumaDiff = abs(lumaBase - lumaSecScaled)
        val sumBase = (rBase + gBase + bBase).coerceAtLeast(1e-4f)
        val sumSec = (rSecScaled + gSecScaled + bSecScaled).coerceAtLeast(1e-4f)
        val chromaDiff = abs(rBase / sumBase - rSecScaled / sumSec) +
                abs(gBase / sumBase - gSecScaled / sumSec)

        val combinedDelta = lumaDiff + 0.5f * chromaDiff
        return (1.0f - ((combinedDelta - 0.22f) / 0.35f)).coerceIn(0.0f, 1.0f)
    }

    /**
     * Merges developed linear bracket frames into a single master JPEG image.
     */
    suspend fun mergeFrames(
        baseFrame: HdrPlusDevelopedImage,
        secondaryFrames: List<Pair<HdrPlusDevelopedImage, HdrPlusAlignmentResult>>,
        jpegQuality: Int = 98,
        orientationDegrees: Int = 0
    ): ByteArray = withContext(Dispatchers.Default) {
        val width = baseFrame.width
        val height = baseFrame.height
        val baseRgb = baseFrame.rgbLinear
        val totalPixels = width * height

        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(totalPixels)

        val sec1 = secondaryFrames.getOrNull(0)
        val sec2 = secondaryFrames.getOrNull(1)

        val sec1Img = sec1?.first
        val sec1Align = sec1?.second ?: HdrPlusAlignmentResult()
        val sec1ExpRatio = if (sec1Img != null) {
            (baseFrame.exposureProduct / sec1Img.exposureProduct.coerceAtLeast(1.0)).toFloat().coerceIn(1.0f, 32.0f)
        } else 1.0f

        val sec2Img = sec2?.first
        val sec2Align = sec2?.second ?: HdrPlusAlignmentResult()
        val sec2ExpRatio = if (sec2Img != null) {
            (baseFrame.exposureProduct / sec2Img.exposureProduct.coerceAtLeast(1.0)).toFloat().coerceIn(1.0f, 64.0f)
        } else 1.0f

        val shiftBuf1 = FloatArray(2)
        val shiftBuf2 = FloatArray(2)
        val sampleBuf1 = FloatArray(3)
        val sampleBuf2 = FloatArray(3)
        val mappedRgb = FloatArray(3)

        // Process row by row for cache efficiency
        for (y in 0 until height) {
            val rowOffset = y * width

            for (x in 0 until width) {
                val pIdx = (rowOffset + x) * 3
                val r1 = baseRgb[pIdx]
                val g1 = baseRgb[pIdx + 1]
                val b1 = baseRgb[pIdx + 2]

                val luma1 = 0.2126f * r1 + 0.7152f * g1 + 0.0722f * b1
                val maxCh1 = maxOf(r1, g1, b1)
                // Use both luminance and peak channel so single-channel clipping on saturated objects is recovered
                val highlightSignal = max(luma1, maxCh1 * 0.92f)

                // 1. Highlight-Only Gate:
                // Shadows, midtones, and unclipped subjects come 100% from Frame 1
                if (highlightSignal < HIGHLIGHT_THRESHOLD || secondaryFrames.isEmpty()) {
                    toneMapRgb(r1, g1, b1, mappedRgb)
                    val sR = toSrgb(mappedRgb[0])
                    val sG = toSrgb(mappedRgb[1])
                    val sB = toSrgb(mappedRgb[2])
                    outPixels[rowOffset + x] = (0xFF shl 24) or (sR shl 16) or (sG shl 8) or sB
                    continue
                }

                // 2. Smooth Hermite blending weight in highlight roll-off zone
                val normHighlight = ((highlightSignal - HIGHLIGHT_THRESHOLD) / (FULL_CLIP_THRESHOLD - HIGHLIGHT_THRESHOLD)).coerceIn(0f, 1f)
                val baseBlendWeight = normHighlight * normHighlight * (3f - 2f * normHighlight)

                var blendedR = r1
                var blendedG = g1
                var blendedB = b1

                if (sec1Img != null) {
                    getSubpixelShiftAt(x, y, width, height, sec1Align, shiftBuf1)
                    val sx1 = x.toFloat() + shiftBuf1[0]
                    val sy1 = y.toFloat() + shiftBuf1[1]

                    if (sampleBilinearRgb(sec1Img.rgbLinear, width, height, sx1, sy1, sampleBuf1)) {
                        val scaledSec1R = sampleBuf1[0] * sec1ExpRatio
                        val scaledSec1G = sampleBuf1[1] * sec1ExpRatio
                        val scaledSec1B = sampleBuf1[2] * sec1ExpRatio

                        val motionSuppression1 = computeGhostSuppressionWeight(
                            luma1, maxCh1, r1, g1, b1,
                            scaledSec1R, scaledSec1G, scaledSec1B
                        )
                        val effectiveWeight1 = baseBlendWeight * motionSuppression1 * sec1Align.confidence

                        // If 3-frame mode: check if extreme highlight frame (Sec 2) should contribute
                        val sec1MaxRaw = maxOf(sampleBuf1[0], sampleBuf1[1], sampleBuf1[2])
                        if (sec2Img != null && (highlightSignal > 0.88f || sec1MaxRaw > 0.85f)) {
                            getSubpixelShiftAt(x, y, width, height, sec2Align, shiftBuf2)
                            val sx2 = x.toFloat() + shiftBuf2[0]
                            val sy2 = y.toFloat() + shiftBuf2[1]

                            if (sampleBilinearRgb(sec2Img.rgbLinear, width, height, sx2, sy2, sampleBuf2)) {
                                val scaledSec2R = sampleBuf2[0] * sec2ExpRatio
                                val scaledSec2G = sampleBuf2[1] * sec2ExpRatio
                                val scaledSec2B = sampleBuf2[2] * sec2ExpRatio

                                val motionSuppression2 = computeGhostSuppressionWeight(
                                    luma1, maxCh1, r1, g1, b1,
                                    scaledSec2R, scaledSec2G, scaledSec2B
                                )
                                val extremeHighlightRatio = max(
                                    ((highlightSignal - 0.88f) / 0.10f).coerceIn(0f, 1f),
                                    ((sec1MaxRaw - 0.85f) / 0.12f).coerceIn(0f, 1f)
                                )
                                val effectiveWeight2 = extremeHighlightRatio * motionSuppression2 * sec2Align.confidence

                                val w2 = effectiveWeight2 * 0.80f
                                val w1 = effectiveWeight1 * (1.0f - w2)
                                val wBase = (1.0f - w1 - w2).coerceAtLeast(0f)

                                blendedR = r1 * wBase + scaledSec1R * w1 + scaledSec2R * w2
                                blendedG = g1 * wBase + scaledSec1G * w1 + scaledSec2G * w2
                                blendedB = b1 * wBase + scaledSec1B * w1 + scaledSec2B * w2
                            } else {
                                blendedR = r1 * (1f - effectiveWeight1) + scaledSec1R * effectiveWeight1
                                blendedG = g1 * (1f - effectiveWeight1) + scaledSec1G * effectiveWeight1
                                blendedB = b1 * (1f - effectiveWeight1) + scaledSec1B * effectiveWeight1
                            }
                        } else {
                            blendedR = r1 * (1f - effectiveWeight1) + scaledSec1R * effectiveWeight1
                            blendedG = g1 * (1f - effectiveWeight1) + scaledSec1G * effectiveWeight1
                            blendedB = b1 * (1f - effectiveWeight1) + scaledSec1B * effectiveWeight1
                        }
                    }
                }

                // 3. Hue-Preserving Filmic Highlight Compression & Shadow Preservation
                toneMapRgb(blendedR, blendedG, blendedB, mappedRgb)

                val sR = toSrgb(mappedRgb[0])
                val sG = toSrgb(mappedRgb[1])
                val sB = toSrgb(mappedRgb[2])

                outPixels[rowOffset + x] = (0xFF shl 24) or (sR shl 16) or (sG shl 8) or sB
            }
        }

        outBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)

        val finalBitmap = if (orientationDegrees != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(orientationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(outBitmap, 0, 0, width, height, matrix, true)
            if (rotated != outBitmap) {
                outBitmap.recycle()
            }
            rotated
        } else {
            outBitmap
        }

        val byteStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(80, 100), byteStream)
        finalBitmap.recycle()

        byteStream.toByteArray()
    }
}
