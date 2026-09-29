package com.cinedepth.pro.ui.blur

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * Portrait segmentation using ML Kit Selfie Segmentation.
 *
 * Produces a high-resolution alpha mask where:
 *   1.0 = definitely person (skin, hair, glasses)
 *   0.0 = definitely background
 *
 * This solves the hair halo problem that depth maps cannot fix:
 * - Depth tells you "how far" each pixel is
 * - This mask tells you "how much of each pixel is person"
 * - Hair strands that are 40% person / 60% sky get a ~0.4 alpha
 *   instead of being forced to foreground OR background by depth
 */
class PortraitSegmenter {

    private val segmenter: Segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
    )

    /**
     * Segment a bitmap and return a float mask at the target dimensions.
     * Each value is in [0, 1] representing person confidence.
     */
    suspend fun segment(
        bitmap: Bitmap,
        targetWidth: Int = bitmap.width,
        targetHeight: Int = bitmap.height
    ): FloatArray = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        segmenter.process(inputImage)
            .addOnSuccessListener { segmentationMask ->
                try {
                    val maskBuffer: ByteBuffer = segmentationMask.buffer
                    val maskWidth = segmentationMask.width
                    val maskHeight = segmentationMask.height

                    maskBuffer.rewind()

                    // ML Kit returns confidence as floats in the buffer
                    val rawMask = FloatArray(maskWidth * maskHeight)
                    for (i in rawMask.indices) {
                        rawMask[i] = maskBuffer.float
                    }

                    // Resize to target dimensions if needed
                    val result = if (maskWidth == targetWidth && maskHeight == targetHeight) {
                        rawMask
                    } else {
                        resizeMask(rawMask, maskWidth, maskHeight, targetWidth, targetHeight)
                    }

                    cont.resume(result)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    /**
     * Segment and return as a grayscale Bitmap (for shader input).
     */
    suspend fun segmentToBitmap(
        bitmap: Bitmap,
        targetWidth: Int = bitmap.width,
        targetHeight: Int = bitmap.height
    ): Bitmap {
        val mask = segment(bitmap, targetWidth, targetHeight)
        return maskToBitmap(mask, targetWidth, targetHeight)
    }

    /**
     * Bilinear resize of a float mask.
     */
    private fun resizeMask(
        src: FloatArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): FloatArray {
        val dst = FloatArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW.toFloat()
        val yRatio = srcH.toFloat() / dstH.toFloat()

        for (y in 0 until dstH) {
            val srcY = y * yRatio
            val y0 = srcY.toInt().coerceIn(0, srcH - 1)
            val y1 = min(y0 + 1, srcH - 1)
            val fy = srcY - y0

            for (x in 0 until dstW) {
                val srcX = x * xRatio
                val x0 = srcX.toInt().coerceIn(0, srcW - 1)
                val x1 = min(x0 + 1, srcW - 1)
                val fx = srcX - x0

                val v00 = src[y0 * srcW + x0]
                val v10 = src[y0 * srcW + x1]
                val v01 = src[y1 * srcW + x0]
                val v11 = src[y1 * srcW + x1]

                val top = v00 + (v10 - v00) * fx
                val bot = v01 + (v11 - v01) * fx
                dst[y * dstW + x] = top + (bot - top) * fy
            }
        }
        return dst
    }

    private fun maskToBitmap(mask: FloatArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (i in mask.indices) {
            val v = (mask[i].coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun close() {
        segmenter.close()
    }
}
