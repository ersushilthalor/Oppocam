package com.example.camera.engine.hdrplus

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.pow

/**
 * Executes highlight-only multi-frame merging with conservative motion-aware ghosting reduction
 * and natural filmic tone mapping to output a single high-quality JPEG image.
 */
class HdrPlusMerger {

    companion object {
        private const val TAG = "HdrPlusMerger"
        private const val HIGHLIGHT_THRESHOLD = 0.72f // Only pixels above 0.72 luma receive secondary details
        private const val FULL_CLIP_THRESHOLD = 0.96f
    }

    // 4096-entry precomputed linear-to-sRGB gamma LUT for ultra-fast conversion
    private val linearToSrgbLut = FloatArray(4096) { idx ->
        val linear = idx / 4095.0f
        if (linear <= 0.0031308f) {
            12.92f * linear
        } else {
            1.055f * linear.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
        }.coerceIn(0.0f, 1.0f)
    }

    private inline fun toSrgb(linear: Float): Int {
        val clamped = linear.coerceIn(0.0f, 1.0f)
        val lutIdx = (clamped * 4095.0f).toInt().coerceIn(0, 4095)
        return (linearToSrgbLut[lutIdx] * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * Merges developed linear bracket frames into a single master JPEG image.
     */
    suspend fun mergeFrames(
        baseFrame: HdrPlusDevelopedImage,
        secondaryFrames: List<Pair<HdrPlusDevelopedImage, HdrPlusAlignmentResult>>,
        jpegQuality: Int = 98
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

        // Process row by row for cache efficiency
        for (y in 0 until height) {
            val rowOffset = y * width

            for (x in 0 until width) {
                val pIdx = (rowOffset + x) * 3
                val r1 = baseRgb[pIdx]
                val g1 = baseRgb[pIdx + 1]
                val b1 = baseRgb[pIdx + 2]

                // Perceived luminance of base frame
                val luma1 = 0.2126f * r1 + 0.7152f * g1 + 0.0722f * b1

                // 1. Highlight-Only Gate:
                // Pixels below threshold are 100% preserved from Frame 1 (no changes to shadows, skin tones, or subjects)
                if (luma1 < HIGHLIGHT_THRESHOLD || secondaryFrames.isEmpty()) {
                    val sR = toSrgb(r1)
                    val sG = toSrgb(g1)
                    val sB = toSrgb(b1)
                    outPixels[rowOffset + x] = (0xFF shl 24) or (sR shl 16) or (sG shl 8) or sB
                    continue
                }

                // 2. Smooth adaptive blending weight in highlight roll-off zone
                val normHighlight = ((luma1 - HIGHLIGHT_THRESHOLD) / (FULL_CLIP_THRESHOLD - HIGHLIGHT_THRESHOLD)).coerceIn(0f, 1f)
                val baseBlendWeight = normHighlight * normHighlight * (3f - 2f * normHighlight) // Hermite curve

                var blendedR = r1
                var blendedG = g1
                var blendedB = b1

                if (sec1Img != null) {
                    val sx1 = x + sec1Align.shiftX
                    val sy1 = y + sec1Align.shiftY

                    if (sx1 in 0 until width && sy1 in 0 until height) {
                        val sIdx1 = (sy1 * width + sx1) * 3
                        val rSec1 = sec1Img.rgbLinear[sIdx1]
                        val gSec1 = sec1Img.rgbLinear[sIdx1 + 1]
                        val bSec1 = sec1Img.rgbLinear[sIdx1 + 2]
                        val lumaSec1 = 0.2126f * rSec1 + 0.7152f * gSec1 + 0.0722f * bSec1

                        // Motion & Ghosting Detection:
                        // Compare radiance-scaled secondary luminance with base frame
                        val expectedLuma = (lumaSec1 * sec1ExpRatio).coerceIn(0f, 1.5f)
                        val motionDelta = abs(luma1 - expectedLuma)
                        val motionSuppression = (1.0f - ((motionDelta - 0.22f) / 0.35f)).coerceIn(0.0f, 1.0f)
                        val effectiveWeight1 = baseBlendWeight * motionSuppression * sec1Align.confidence

                        val scaledSec1R = (rSec1 * sec1ExpRatio)
                        val scaledSec1G = (gSec1 * sec1ExpRatio)
                        val scaledSec1B = (bSec1 * sec1ExpRatio)

                        // If 3-frame mode: check if extreme highlight frame (Sec 2) should contribute
                        if (sec2Img != null && luma1 > 0.90f) {
                            val sx2 = x + sec2Align.shiftX
                            val sy2 = y + sec2Align.shiftY
                            if (sx2 in 0 until width && sy2 in 0 until height) {
                                val sIdx2 = (sy2 * width + sx2) * 3
                                val rSec2 = sec2Img.rgbLinear[sIdx2]
                                val gSec2 = sec2Img.rgbLinear[sIdx2 + 1]
                                val bSec2 = sec2Img.rgbLinear[sIdx2 + 2]
                                val lumaSec2 = 0.2126f * rSec2 + 0.7152f * gSec2 + 0.0722f * bSec2

                                val expectedLuma2 = (lumaSec2 * sec2ExpRatio).coerceIn(0f, 2.0f)
                                val motionDelta2 = abs(luma1 - expectedLuma2)
                                val motionSuppression2 = (1.0f - ((motionDelta2 - 0.25f) / 0.40f)).coerceIn(0.0f, 1.0f)
                                val extremeHighlightRatio = ((luma1 - 0.90f) / 0.09f).coerceIn(0f, 1f)
                                val effectiveWeight2 = extremeHighlightRatio * motionSuppression2 * sec2Align.confidence

                                val scaledSec2R = (rSec2 * sec2ExpRatio)
                                val scaledSec2G = (gSec2 * sec2ExpRatio)
                                val scaledSec2B = (bSec2 * sec2ExpRatio)

                                // Combine moderate and extreme highlights
                                val w2 = effectiveWeight2 * 0.75f
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

                // 3. Filmic Highlight Compression (Shoulder Tone Mapping):
                // Preserves natural highlights and clouds with smooth roll-off instead of harsh clamping
                val mappedR = if (blendedR > 0.85f) {
                    0.85f + (blendedR - 0.85f) / (1.0f + 0.45f * (blendedR - 0.85f))
                } else blendedR

                val mappedG = if (blendedG > 0.85f) {
                    0.85f + (blendedG - 0.85f) / (1.0f + 0.45f * (blendedG - 0.85f))
                } else blendedG

                val mappedB = if (blendedB > 0.85f) {
                    0.85f + (blendedB - 0.85f) / (1.0f + 0.45f * (blendedB - 0.85f))
                } else blendedB

                val sR = toSrgb(mappedR)
                val sG = toSrgb(mappedG)
                val sB = toSrgb(mappedB)

                outPixels[rowOffset + x] = (0xFF shl 24) or (sR shl 16) or (sG shl 8) or sB
            }
        }

        outBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)

        val byteStream = ByteArrayOutputStream()
        outBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(80, 100), byteStream)
        outBitmap.recycle()

        byteStream.toByteArray()
    }
}
