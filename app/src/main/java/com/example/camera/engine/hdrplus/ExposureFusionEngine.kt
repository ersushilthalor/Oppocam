package com.example.camera.engine.hdrplus

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Android implementation of the Exposure Fusion algorithm by Tom Mertens, Jan Kautz,
 * and Frank Van Reeth (Pacific Graphics 2007), adapted from peterhyun/ExposureFusion_Android.
 *
 * Merges 3 bracketed exposure images (Underexposed, Normal, Overexposed) into a single,
 * seamlessly balanced image with natural colors, recovered highlights, and detailed shadows.
 *
 * Steps:
 * 1. Computes per-pixel quality measures: Contrast (Laplacian filter), Saturation (standard deviation),
 *    and Well-Exposedness (Gaussian curve around 0.5).
 * 2. Computes normalized weight maps across the 3 exposures.
 * 3. Decomposes weight maps into a Gaussian pyramid and color images into a Laplacian pyramid.
 * 4. Blends pyramids at each scale level to eliminate seam artifacts and halos.
 * 5. Reconstructs the final fused full-resolution image.
 */
class ExposureFusionEngine(
    val contrastWeight: Float = 1.0f,
    val saturationWeight: Float = 1.0f,
    val wellExposednessWeight: Float = 1.0f,
    val sigma: Float = 0.2f
) {

    companion object {
        private const val TAG = "ExposureFusionEngine"
        private const val EPSILON = 1e-6f
        private const val MAX_PYRAMID_LEVELS = 5
        private const val MIN_PYRAMID_DIM = 16

        // 1D 5-tap separable Burt-Adelson binomial filter: [1, 4, 6, 4, 1] / 16
        private val KERNEL_1D = floatArrayOf(0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f)
    }

    /**
     * Container representing a single multi-channel 2D float image in planar (R, G, B) format.
     */
    class ImageChannelPlanes(
        val width: Int,
        val height: Int,
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray
    ) {
        val size: Int get() = width * height
    }

    /**
     * Container representing a single 2D float weight map.
     */
    class FloatPlane(
        val width: Int,
        val height: Int,
        val data: FloatArray
    ) {
        val size: Int get() = width * height
    }

    /**
     * Fuses 3 developed exposure images (Under-exposed, Normal reference, Over-exposed)
     * using Mertens Exposure Fusion into an output float array (interleaved RGB [0, 1]).
     */
    suspend fun fuseExposures(
        underImage: HdrPlusDevelopedImage,
        normalImage: HdrPlusDevelopedImage,
        overImage: HdrPlusDevelopedImage,
        underAlignment: HdrPlusAlignmentResult = HdrPlusAlignmentResult(),
        overAlignment: HdrPlusAlignmentResult = HdrPlusAlignmentResult()
    ): FloatArray = withContext(Dispatchers.Default) {
        val width = normalImage.width
        val height = normalImage.height

        // 1. Convert developed images to aligned planar Float representation
        val planesUnder = createAlignedPlanes(underImage, width, height, underAlignment)
        val planesNormal = createDirectPlanes(normalImage, width, height)
        val planesOver = createAlignedPlanes(overImage, width, height, overAlignment)

        val inputImages = listOf(planesUnder, planesNormal, planesOver)
        val numImages = inputImages.size

        // 2. Compute Quality Measures and Normalized Weight Maps
        val normalizedWeights = computeNormalizedWeightMaps(inputImages, width, height)

        // 3. Determine number of pyramid levels based on resolution
        val numLevels = calculatePyramidLevels(width, height)

        // 4. Construct Gaussian Pyramids for normalized weight maps
        val weightPyramids = normalizedWeights.map { weightMap ->
            buildGaussianPyramid(weightMap, numLevels)
        }

        // 5. Construct Laplacian Pyramids for input color images
        val imageLaplacianPyramids = inputImages.map { img ->
            buildLaplacianPyramid(img, numLevels)
        }

        // 6. Fuse Laplacian Pyramids at each level
        val fusedLaplacianPyramid = ArrayList<ImageChannelPlanes>(numLevels)
        for (lvl in 0 until numLevels) {
            val lvlW = weightPyramids[0][lvl].width
            val lvlH = weightPyramids[0][lvl].height
            val lvlSize = lvlW * lvlH

            val fusedR = FloatArray(lvlSize)
            val fusedG = FloatArray(lvlSize)
            val fusedB = FloatArray(lvlSize)

            for (k in 0 until numImages) {
                val wData = weightPyramids[k][lvl].data
                val lapImg = imageLaplacianPyramids[k][lvl]
                val lapR = lapImg.r
                val lapG = lapImg.g
                val lapB = lapImg.b

                for (i in 0 until lvlSize) {
                    val w = wData[i]
                    fusedR[i] += w * lapR[i]
                    fusedG[i] += w * lapG[i]
                    fusedB[i] += w * lapB[i]
                }
            }

            fusedLaplacianPyramid.add(ImageChannelPlanes(lvlW, lvlH, fusedR, fusedG, fusedB))
        }

        // 7. Reconstruct Fused Image from Laplacian Pyramid (coarsest level to level 0)
        var currentReconstruction = fusedLaplacianPyramid[numLevels - 1]
        for (lvl in (numLevels - 2) downTo 0) {
            val targetLevel = fusedLaplacianPyramid[lvl]
            val upsampled = upsampleImage(currentReconstruction, targetLevel.width, targetLevel.height)

            val curR = targetLevel.r
            val curG = targetLevel.g
            val curB = targetLevel.b
            val upR = upsampled.r
            val upG = upsampled.g
            val upB = upsampled.b

            val size = targetLevel.size
            for (i in 0 until size) {
                curR[i] = (curR[i] + upR[i]).coerceIn(0.0f, 1.0f)
                curG[i] = (curG[i] + upG[i]).coerceIn(0.0f, 1.0f)
                curB[i] = (curB[i] + upB[i]).coerceIn(0.0f, 1.0f)
            }
            currentReconstruction = targetLevel
        }

        // 8. Convert output planar ImageChannelPlanes to interleaved RGB FloatArray
        val outInterleaved = FloatArray(width * height * 3)
        val finalR = currentReconstruction.r
        val finalG = currentReconstruction.g
        val finalB = currentReconstruction.b

        for (i in 0 until (width * height)) {
            val idx = i * 3
            outInterleaved[idx] = finalR[i].coerceIn(0.0f, 1.0f)
            outInterleaved[idx + 1] = finalG[i].coerceIn(0.0f, 1.0f)
            outInterleaved[idx + 2] = finalB[i].coerceIn(0.0f, 1.0f)
        }

        outInterleaved
    }

    /**
     * Computes Mertens Contrast, Saturation, and Well-Exposedness metrics and normalizes weights.
     * Memory-optimized for mobile devices (eliminates redundant rawWeights and luminance buffers).
     */
    private fun computeNormalizedWeightMaps(
        images: List<ImageChannelPlanes>,
        width: Int,
        height: Int
    ): List<FloatPlane> {
        val numImages = images.size
        val totalPixels = width * height
        val normalizedMaps = Array(numImages) { FloatArray(totalPixels) }

        val twoSigmaSq = 2.0f * sigma * sigma // 2 * 0.04 = 0.08
        val invTwoSigmaSq = 1.0f / twoSigmaSq // 12.5f

        for (k in 0 until numImages) {
            val img = images[k]
            val rCh = img.r
            val gCh = img.g
            val bCh = img.b
            val weights = normalizedMaps[k]

            // Quality measures per pixel with zero-allocation on-the-fly luminance calculation
            for (y in 0 until height) {
                val rowOffset = y * width
                val yPrev = if (y > 0) y - 1 else y
                val yNext = if (y < height - 1) y + 1 else y
                val rowPrev = yPrev * width
                val rowNext = yNext * width

                for (x in 0 until width) {
                    val idx = rowOffset + x
                    val xPrev = if (x > 0) x - 1 else x
                    val xNext = if (x < width - 1) x + 1 else x

                    val r = rCh[idx]
                    val g = gCh[idx]
                    val b = bCh[idx]

                    // a. Contrast Measure: Absolute Laplacian of Grayscale Luminance
                    val lCenter = 0.299f * r + 0.587f * g + 0.114f * b
                    val topIdx = rowPrev + x
                    val lTop = 0.299f * rCh[topIdx] + 0.587f * gCh[topIdx] + 0.114f * bCh[topIdx]
                    val botIdx = rowNext + x
                    val lBottom = 0.299f * rCh[botIdx] + 0.587f * gCh[botIdx] + 0.114f * bCh[botIdx]
                    val leftIdx = rowOffset + xPrev
                    val lLeft = 0.299f * rCh[leftIdx] + 0.587f * gCh[leftIdx] + 0.114f * bCh[leftIdx]
                    val rightIdx = rowOffset + xNext
                    val lRight = 0.299f * rCh[rightIdx] + 0.587f * gCh[rightIdx] + 0.114f * bCh[rightIdx]
                    val contrast = abs(lTop + lBottom + lLeft + lRight - 4.0f * lCenter)

                    // b. Saturation Measure: Standard deviation across R, G, B
                    val mu = (r + g + b) * 0.33333334f
                    val diffR = r - mu
                    val diffG = g - mu
                    val diffB = b - mu
                    val saturation = sqrt((diffR * diffR + diffG * diffG + diffB * diffB) * 0.33333334f)

                    // c. Well-Exposedness Measure: Gaussian weighting centered at 0.5
                    val devR = r - 0.5f
                    val devG = g - 0.5f
                    val devB = b - 0.5f
                    val expR = exp(-devR * devR * invTwoSigmaSq)
                    val expG = exp(-devG * devG * invTwoSigmaSq)
                    val expB = exp(-devB * devB * invTwoSigmaSq)
                    val wellExposedness = expR * expG * expB

                    // d. Combined Quality Weight
                    var w = 1.0f
                    if (contrastWeight > 0f) {
                        w *= (contrast + EPSILON)
                    }
                    if (saturationWeight > 0f) {
                        w *= (saturation + EPSILON)
                    }
                    if (wellExposednessWeight > 0f) {
                        w *= (wellExposedness + EPSILON)
                    }

                    weights[idx] = w + EPSILON
                }
            }
        }

        // 3. Normalize weights in-place per pixel so sum over all 3 exposures is 1.0
        for (i in 0 until totalPixels) {
            var sumW = 0f
            for (k in 0 until numImages) {
                sumW += normalizedMaps[k][i]
            }
            val invSum = if (sumW > 1e-8f) 1.0f / sumW else (1.0f / numImages)
            for (k in 0 until numImages) {
                normalizedMaps[k][i] *= invSum
            }
        }

        return normalizedMaps.map { FloatPlane(width, height, it) }
    }

    /**
     * Calculates optimal pyramid depth according to image dimensions.
     */
    private fun calculatePyramidLevels(width: Int, height: Int): Int {
        val minDim = min(width, height)
        val maxPossible = (log2(minDim.toDouble()) - 2.0).toInt().coerceAtLeast(1)
        return min(MAX_PYRAMID_LEVELS, maxPossible)
    }

    /**
     * Builds a Gaussian pyramid from a 2D float plane.
     */
    private fun buildGaussianPyramid(base: FloatPlane, levels: Int): List<FloatPlane> {
        val pyramid = ArrayList<FloatPlane>(levels)
        pyramid.add(base)

        var current = base
        for (i in 1 until levels) {
            current = downsamplePlane(current)
            pyramid.add(current)
        }
        return pyramid
    }

    /**
     * Builds a Laplacian pyramid from a color image.
     */
    private fun buildLaplacianPyramid(base: ImageChannelPlanes, levels: Int): List<ImageChannelPlanes> {
        // 1. Build Gaussian pyramid of images
        val gaussianPyramid = ArrayList<ImageChannelPlanes>(levels)
        gaussianPyramid.add(base)

        var current = base
        for (i in 1 until levels) {
            current = downsampleImage(current)
            gaussianPyramid.add(current)
        }

        // 2. Build Laplacian pyramid (difference between Gaussian levels and upsampled successors)
        val laplacianPyramid = ArrayList<ImageChannelPlanes>(levels)
        for (i in 0 until (levels - 1)) {
            val gCurrent = gaussianPyramid[i]
            val gNext = gaussianPyramid[i + 1]
            val upsampledNext = upsampleImage(gNext, gCurrent.width, gCurrent.height)

            val size = gCurrent.size
            val lapR = FloatArray(size)
            val lapG = FloatArray(size)
            val lapB = FloatArray(size)

            val curR = gCurrent.r
            val curG = gCurrent.g
            val curB = gCurrent.b
            val upR = upsampledNext.r
            val upG = upsampledNext.g
            val upB = upsampledNext.b

            for (p in 0 until size) {
                lapR[p] = curR[p] - upR[p]
                lapG[p] = curG[p] - upG[p]
                lapB[p] = curB[p] - upB[p]
            }

            laplacianPyramid.add(ImageChannelPlanes(gCurrent.width, gCurrent.height, lapR, lapG, lapB))
        }

        // 3. Coarsest level is the residual Gaussian image directly
        laplacianPyramid.add(gaussianPyramid[levels - 1])
        return laplacianPyramid
    }

    /**
     * Downsamples a single 2D float plane using separable 5-tap Burt-Adelson filtering.
     */
    private fun downsamplePlane(src: FloatPlane): FloatPlane {
        val w = src.width
        val h = src.height
        val dstW = (w + 1) / 2
        val dstH = (h + 1) / 2
        val srcData = src.data

        // 1. Horizontal filtering pass
        val temp = FloatArray(dstW * h)
        for (y in 0 until h) {
            val rowOffset = y * w
            val tempRowOffset = y * dstW
            for (x in 0 until dstW) {
                val srcX = x * 2
                var sum = 0f
                for (k in -2..2) {
                    val sampleX = (srcX + k).coerceIn(0, w - 1)
                    sum += srcData[rowOffset + sampleX] * KERNEL_1D[k + 2]
                }
                temp[tempRowOffset + x] = sum
            }
        }

        // 2. Vertical filtering pass
        val dstData = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val srcY = y * 2
            val dstRowOffset = y * dstW
            for (x in 0 until dstW) {
                var sum = 0f
                for (k in -2..2) {
                    val sampleY = (srcY + k).coerceIn(0, h - 1)
                    sum += temp[sampleY * dstW + x] * KERNEL_1D[k + 2]
                }
                dstData[dstRowOffset + x] = sum
            }
        }

        return FloatPlane(dstW, dstH, dstData)
    }

    /**
     * Downsamples a color image (3 channels).
     */
    private fun downsampleImage(src: ImageChannelPlanes): ImageChannelPlanes {
        val rPlane = downsamplePlane(FloatPlane(src.width, src.height, src.r))
        val gPlane = downsamplePlane(FloatPlane(src.width, src.height, src.g))
        val bPlane = downsamplePlane(FloatPlane(src.width, src.height, src.b))
        return ImageChannelPlanes(rPlane.width, rPlane.height, rPlane.data, gPlane.data, bPlane.data)
    }

    /**
     * Upsamples a single 2D float plane with 5-tap separable interpolation.
     */
    private fun upsamplePlane(src: FloatPlane, targetW: Int, targetH: Int): FloatPlane {
        val srcW = src.width
        val srcH = src.height
        val srcData = src.data

        // 1. Horizontal upsampling pass (insert zeros & convolve * 2)
        val temp = FloatArray(targetW * srcH)
        for (y in 0 until srcH) {
            val srcRowOffset = y * srcW
            val tempRowOffset = y * targetW
            for (x in 0 until targetW) {
                var sum = 0f
                for (k in -2..2) {
                    val tapPos = x + k
                    if ((tapPos and 1) == 0) {
                        val sampleSrcX = (tapPos / 2).coerceIn(0, srcW - 1)
                        sum += srcData[srcRowOffset + sampleSrcX] * KERNEL_1D[k + 2]
                    }
                }
                temp[tempRowOffset + x] = sum * 2.0f
            }
        }

        // 2. Vertical upsampling pass
        val dstData = FloatArray(targetW * targetH)
        for (y in 0 until targetH) {
            val dstRowOffset = y * targetW
            for (x in 0 until targetW) {
                var sum = 0f
                for (k in -2..2) {
                    val tapPos = y + k
                    if ((tapPos and 1) == 0) {
                        val sampleSrcY = (tapPos / 2).coerceIn(0, srcH - 1)
                        sum += temp[sampleSrcY * targetW + x] * KERNEL_1D[k + 2]
                    }
                }
                dstData[dstRowOffset + x] = sum * 2.0f
            }
        }

        return FloatPlane(targetW, targetH, dstData)
    }

    /**
     * Upsamples a color image (3 channels).
     */
    private fun upsampleImage(src: ImageChannelPlanes, targetW: Int, targetH: Int): ImageChannelPlanes {
        val rPlane = upsamplePlane(FloatPlane(src.width, src.height, src.r), targetW, targetH)
        val gPlane = upsamplePlane(FloatPlane(src.width, src.height, src.g), targetW, targetH)
        val bPlane = upsamplePlane(FloatPlane(src.width, src.height, src.b), targetW, targetH)
        return ImageChannelPlanes(targetW, targetH, rPlane.data, gPlane.data, bPlane.data)
    }

    private fun createDirectPlanes(img: HdrPlusDevelopedImage, w: Int, h: Int): ImageChannelPlanes {
        val total = w * h
        val r = FloatArray(total)
        val g = FloatArray(total)
        val b = FloatArray(total)
        val rgb = img.rgbLinear

        for (i in 0 until total) {
            val idx = i * 3
            r[i] = rgb[idx]
            g[i] = rgb[idx + 1]
            b[i] = rgb[idx + 2]
        }
        return ImageChannelPlanes(w, h, r, g, b)
    }

    private fun createAlignedPlanes(
        img: HdrPlusDevelopedImage,
        w: Int,
        h: Int,
        alignment: HdrPlusAlignmentResult
    ): ImageChannelPlanes {
        val total = w * h
        val r = FloatArray(total)
        val g = FloatArray(total)
        val b = FloatArray(total)
        val rgb = img.rgbLinear

        val dx = alignment.subpixelShiftX
        val dy = alignment.subpixelShiftY

        if (abs(dx) < 0.01f && abs(dy) < 0.01f && alignment.tileShiftsX == null) {
            return createDirectPlanes(img, w, h)
        }

        val shiftBuf = FloatArray(2)
        val sampleBuf = FloatArray(3)

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                getSubpixelShiftAt(x, y, w, h, alignment, shiftBuf)
                val sx = x.toFloat() + shiftBuf[0]
                val sy = y.toFloat() + shiftBuf[1]

                if (sampleBilinear(rgb, w, h, sx, sy, sampleBuf)) {
                    val pIdx = rowOffset + x
                    r[pIdx] = sampleBuf[0]
                    g[pIdx] = sampleBuf[1]
                    b[pIdx] = sampleBuf[2]
                } else {
                    val pIdx = rowOffset + x
                    val directIdx = pIdx * 3
                    r[pIdx] = rgb[directIdx]
                    g[pIdx] = rgb[directIdx + 1]
                    b[pIdx] = rgb[directIdx + 2]
                }
            }
        }

        return ImageChannelPlanes(w, h, r, g, b)
    }

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

    private fun sampleBilinear(
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
}
