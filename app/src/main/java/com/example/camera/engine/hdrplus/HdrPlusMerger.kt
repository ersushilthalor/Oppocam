package com.example.camera.engine.hdrplus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.pow

/**
 * Executes 3-Frame Exposure Fusion (Mertens et al. algorithm) combining Underexposed (-2 EV),
 * Normal (0 EV), and Overexposed (+2 EV) frames into a single, perfectly balanced photo with
 * natural colors, detailed shadows, and recovered highlights.
 *
 * Fully resolves orientation issues by rotating output pixels upright and stamping EXIF
 * with ORIENTATION_NORMAL, and provides failsafe, leak-free JPEG generation.
 */
class HdrPlusMerger(private val context: Context? = null) {

    companion object {
        private const val TAG = "HdrPlusMerger"
        private const val SHOULDER_KNEE = 0.72f
    }

    private val fusionEngine = ExposureFusionEngine(
        contrastWeight = 1.0f,
        saturationWeight = 1.0f,
        wellExposednessWeight = 1.0f,
        sigma = 0.2f
    )

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
     * Smooth filmic shoulder compression that maps [0, +inf) into [0, 1.0)
     * without hard clipping recovered highlights, while gently lifting deep shadows.
     */
    private inline fun applyToneCurveChannel(v: Float): Float {
        val x = v.coerceAtLeast(0.0f)
        // Gentle shadow toe preservation
        val shadowAdjusted = if (x in 0.0005f..0.18f) {
            val t = x / 0.18f
            val lift = 0.024f * t * (1.0f - t) * (1.0f - 0.35f * t)
            x + lift
        } else {
            x
        }

        // True asymptotic rational shoulder above SHOULDER_KNEE
        return if (shadowAdjusted > SHOULDER_KNEE) {
            val headroom = 1.0f - SHOULDER_KNEE
            val excess = shadowAdjusted - SHOULDER_KNEE
            SHOULDER_KNEE + headroom * (excess / (excess + headroom * 1.30f))
        } else {
            shadowAdjusted
        }
    }

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

        val refSignal = 0.65f * maxCh + 0.35f * luma
        val mappedSignal = applyToneCurveChannel(refSignal)
        val scale = if (refSignal > 1e-5f) mappedSignal / refSignal else 1.0f

        val ratioR = cleanR * scale
        val ratioG = cleanG * scale
        val ratioB = cleanB * scale

        val chR = applyToneCurveChannel(cleanR)
        val chG = applyToneCurveChannel(cleanG)
        val chB = applyToneCurveChannel(cleanB)

        outRgb[0] = (0.80f * ratioR + 0.20f * chR).coerceIn(0.0f, 1.0f)
        outRgb[1] = (0.80f * ratioG + 0.20f * chG).coerceIn(0.0f, 1.0f)
        outRgb[2] = (0.80f * ratioB + 0.20f * chB).coerceIn(0.0f, 1.0f)
    }

    /**
     * Fuses 3 bracketed exposure frames (Underexposed, Normal, Overexposed) using the
     * Mertens Exposure Fusion algorithm and outputs a stamped JPEG byte array.
     */
    suspend fun merge3FramesExposureFusion(
        underFrame: HdrPlusDevelopedImage,
        normalFrame: HdrPlusDevelopedImage,
        overFrame: HdrPlusDevelopedImage,
        underAlignment: HdrPlusAlignmentResult = HdrPlusAlignmentResult(),
        overAlignment: HdrPlusAlignmentResult = HdrPlusAlignmentResult(),
        jpegQuality: Int = 98,
        orientationDegrees: Int = 0
    ): ByteArray = withContext(Dispatchers.Default) {
        try {
            val width = normalFrame.width
            val height = normalFrame.height
            val totalPixels = width * height

            // 1. Run Exposure Fusion algorithm across the 3 aligned exposures
            val fusedLinearRgb = fusionEngine.fuseExposures(
                underImage = underFrame,
                normalImage = normalFrame,
                overImage = overFrame,
                underAlignment = underAlignment,
                overAlignment = overAlignment
            )

            // 2. Convert linear float RGB to tone-mapped sRGB integer pixels
            val outPixels = IntArray(totalPixels)
            val mappedRgb = FloatArray(3)

            for (i in 0 until totalPixels) {
                val idx = i * 3
                val r = fusedLinearRgb[idx]
                val g = fusedLinearRgb[idx + 1]
                val b = fusedLinearRgb[idx + 2]

                toneMapRgb(r, g, b, mappedRgb)

                val sR = toSrgb(mappedRgb[0])
                val sG = toSrgb(mappedRgb[1])
                val sB = toSrgb(mappedRgb[2])

                outPixels[i] = (0xFF shl 24) or (sR shl 16) or (sG shl 8) or sB
            }

            val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            outBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)

            // 3. Fix 90° Image Rotation Issue:
            // Physically transform bitmap upright according to capture sensor orientation
            val finalBitmap = if (orientationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(orientationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(outBitmap, 0, 0, width, height, matrix, true)
                if (rotated != outBitmap) {
                    outBitmap.recycle()
                }
                rotated
            } else {
                outBitmap
            }

            val finalW = finalBitmap.width
            val finalH = finalBitmap.height

            // 4. Encode to high-quality JPEG
            val byteStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(85, 100), byteStream)
            finalBitmap.recycle()

            val rawJpeg = byteStream.toByteArray()

            // 5. Stamp EXIF metadata with ORIENTATION_NORMAL (1) and exact upright dimensions
            val cacheDir = context?.cacheDir
            val stampedBytes = try {
                val tempFile = if (cacheDir != null && cacheDir.exists()) {
                    File.createTempFile("exposure_fusion_out", ".jpg", cacheDir)
                } else {
                    File.createTempFile("exposure_fusion_out", ".jpg")
                }
                tempFile.writeBytes(rawJpeg)
                val exif = ExifInterface(tempFile.absolutePath)
                exif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString()
                )
                exif.setAttribute(
                    ExifInterface.TAG_IMAGE_WIDTH,
                    finalW.toString()
                )
                exif.setAttribute(
                    ExifInterface.TAG_IMAGE_LENGTH,
                    finalH.toString()
                )
                exif.saveAttributes()
                val stamped = tempFile.readBytes()
                tempFile.delete()
                stamped
            } catch (e: Exception) {
                Log.w(TAG, "EXIF orientation stamping notice", e)
                rawJpeg
            }

            stampedBytes
        } catch (t: Throwable) {
            Log.e(TAG, "Exposure fusion pipeline failure, invoking failsafe to normal reference frame", t)
            encodeSingleFrame(normalFrame, jpegQuality, orientationDegrees)
        }
    }

    /**
     * Backward-compatible dispatcher for multi-frame merging.
     * Automatically maps frames to Underexposed, Normal, Overexposed and executes Exposure Fusion.
     */
    suspend fun mergeFrames(
        baseFrame: HdrPlusDevelopedImage,
        secondaryFrames: List<Pair<HdrPlusDevelopedImage, HdrPlusAlignmentResult>>,
        jpegQuality: Int = 98,
        orientationDegrees: Int = 0
    ): ByteArray = withContext(Dispatchers.Default) {
        if (secondaryFrames.isEmpty()) {
            // Single frame fallback
            return@withContext encodeSingleFrame(baseFrame, jpegQuality, orientationDegrees)
        }

        val allImages = mutableListOf<Pair<HdrPlusDevelopedImage, HdrPlusAlignmentResult>>()
        allImages.add(Pair(baseFrame, HdrPlusAlignmentResult()))
        allImages.addAll(secondaryFrames)

        // Identify Under-exposed, Normal reference, and Over-exposed frames
        val normalPair = allImages.firstOrNull { it.first.role == HdrPlusRole.NORMAL_EXPOSURE }
            ?: allImages.first()
        val underPair = allImages.firstOrNull { it.first.role == HdrPlusRole.UNDER_EXPOSED }
            ?: allImages.firstOrNull { it.first !== normalPair.first && it.first.evDelta < 0f }
            ?: allImages.minByOrNull { it.first.exposureProduct }
            ?: normalPair

        val overPair = allImages.firstOrNull { it.first.role == HdrPlusRole.OVER_EXPOSED }
            ?: allImages.firstOrNull { it.first !== normalPair.first && it.first !== underPair.first }
            ?: allImages.maxByOrNull { it.first.exposureProduct }
            ?: normalPair

        merge3FramesExposureFusion(
            underFrame = underPair.first,
            normalFrame = normalPair.first,
            overFrame = overPair.first,
            underAlignment = underPair.second,
            overAlignment = overPair.second,
            jpegQuality = jpegQuality,
            orientationDegrees = orientationDegrees
        )
    }

    fun encodeSingleFrame(
        frame: HdrPlusDevelopedImage,
        jpegQuality: Int,
        orientationDegrees: Int
    ): ByteArray {
        val width = frame.width
        val height = frame.height
        val totalPixels = width * height
        val rgb = frame.rgbLinear
        val outPixels = IntArray(totalPixels)
        val mappedRgb = FloatArray(3)

        for (i in 0 until totalPixels) {
            val idx = i * 3
            toneMapRgb(rgb[idx], rgb[idx + 1], rgb[idx + 2], mappedRgb)
            val sR = toSrgb(mappedRgb[0])
            val sG = toSrgb(mappedRgb[1])
            val sB = toSrgb(mappedRgb[2])
            outPixels[i] = (0xFF shl 24) or (sR shl 16) or (sG shl 8) or sB
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(outPixels, 0, width, 0, 0, width, height)

        val finalBitmap = if (orientationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(orientationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } else {
            bitmap
        }

        val byteStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(85, 100), byteStream)
        finalBitmap.recycle()
        return byteStream.toByteArray()
    }
}
