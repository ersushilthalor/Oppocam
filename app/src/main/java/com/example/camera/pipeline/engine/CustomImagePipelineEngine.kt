package com.example.camera.pipeline.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.util.Log
import com.example.camera.pipeline.model.CustomPipelineParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.*

private const val TAG = "CustomPipelineEngine"

/**
 * High-performance, uncompressed image processing pipeline.
 *
 * Architecture:
 * Camera sensor → RAW / YUV_420_888 ImageReader → Uncompressed Linear RGB
 *   → Exposure & White Balance (Temp/Tint)
 *   → 3x3 Color Matrix (Sensor chromatic adaptation)
 *   → Local Tone Mapping / HDR dynamic compression
 *   → Highlight Roll-Off & Shadow Recovery
 *   → Perceptual Contrast, Whites & Blacks
 *   → Edge-Preserving Noise Reduction
 *   → Texture & Micro-Contrast (Clarity)
 *   → Adaptive Sharpness
 *   → Vibrance & Smart Skin Protection
 *   → Direct High-Quality JPEG / HEIF Encoding
 *
 * Crucially: The default JPEG renderer is NEVER used as an intermediate step.
 */
class CustomImagePipelineEngine(private val context: Context) {

    /**
     * Converts an unprocessed Camera2 Image (RAW_SENSOR or YUV_420_888) directly to a Bitmap
     * with orientation corrected, without any prior JPEG encoding.
     */
    fun convertCameraImageToUncompressedBitmap(
        image: Image,
        rotationDegrees: Int = 0,
        isFrontFacing: Boolean = false,
        saveMirrored: Boolean = false
    ): Bitmap {
        val width = image.width
        val height = image.height

        val rawBitmap = when (image.format) {
            ImageFormat.YUV_420_888 -> {
                convertYuv420ToRgbBitmap(image)
            }
            ImageFormat.RAW_SENSOR -> {
                convertRawSensorToRgbBitmap(image)
            }
            else -> {
                // Fallback for direct buffer
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
        }

        // Apply rotation and selfie mirror if needed
        val matrix = Matrix()
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        if (isFrontFacing && saveMirrored) {
            matrix.postScale(-1f, 1f)
        }

        return if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(
                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
            )
            if (transformed != rawBitmap) {
                rawBitmap.recycle()
            }
            transformed
        } else {
            rawBitmap
        }
    }

    /**
     * High-speed YUV_420_888 to ARGB_8888 conversion directly from sensor planes.
     */
    private fun convertYuv420ToRgbBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val outPixels = IntArray(width * height)

        // Select colorimetry: HD/UHD resolutions (>= 720p) strictly use ITU-R BT.709
        // This eliminates magenta/purple casts caused by BT.601 green-channel underestimation
        val isHd = width >= 1280 || height >= 720

        for (y in 0 until height) {
            val yOffset = y * yRowStride
            val uvOffset = (y shr 1) * uvRowStride

            for (x in 0 until width) {
                val yIndex = yOffset + x * yPixelStride
                val yVal = (yBuffer.get(yIndex).toInt() and 0xFF)

                val uvIndex = uvOffset + (x shr 1) * uvPixelStride
                val uVal = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                var r: Int
                var g: Int
                var b: Int

                if (isHd) {
                    // ITU-R BT.709 Color Space (HD/UHD camera streams)
                    r = (yVal + 1.5748f * vVal).toInt()
                    g = (yVal - 0.1873f * uVal - 0.4681f * vVal).toInt()
                    b = (yVal + 1.8556f * uVal).toInt()
                } else {
                    // ITU-R BT.601 (SD legacy)
                    r = (yVal + 1.402f * vVal).toInt()
                    g = (yVal - 0.344136f * uVal - 0.714136f * vVal).toInt()
                    b = (yVal + 1.772f * uVal).toInt()
                }

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                outPixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Demosaics RAW_SENSOR Bayer CFA data into RGB bitmap.
     */
    private fun convertRawSensorToRgbBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val buffer = image.planes[0].buffer
        val rowStride = image.planes[0].rowStride

        // Fast bilinear Bayer demosaicing for RAW sensor
        val outPixels = IntArray(width * height)
        val shortBuffer = buffer.asShortBuffer()
        val rawData = ShortArray(shortBuffer.remaining())
        shortBuffer.get(rawData)

        val blackLevel = 64
        val whiteLevel = 1023 // 10-bit raw sensor typical range

        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                // Bayer RGGB quad
                val r0 = ((rawData[y * width + x].toInt() and 0xFFFF) - blackLevel).coerceAtLeast(0)
                val g0 = ((rawData[y * width + (x + 1)].toInt() and 0xFFFF) - blackLevel).coerceAtLeast(0)
                val g1 = ((rawData[(y + 1) * width + x].toInt() and 0xFFFF) - blackLevel).coerceAtLeast(0)
                val b0 = ((rawData[(y + 1) * width + (x + 1)].toInt() and 0xFFFF) - blackLevel).coerceAtLeast(0)

                val avgG = ((g0 + g1) shr 1)

                val rNorm = (r0 * 255 / (whiteLevel - blackLevel)).coerceIn(0, 255)
                val gNorm = (avgG * 255 / (whiteLevel - blackLevel)).coerceIn(0, 255)
                val bNorm = (b0 * 255 / (whiteLevel - blackLevel)).coerceIn(0, 255)

                val color = (0xFF shl 24) or (rNorm shl 16) or (gNorm shl 8) or bNorm

                outPixels[y * width + x] = color
                outPixels[y * width + (x + 1)] = color
                outPixels[(y + 1) * width + x] = color
                outPixels[(y + 1) * width + (x + 1)] = color
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Executes the full uncompressed custom pipeline on a source bitmap with given parameters.
     * Operates in multi-threaded parallel horizontal bands for zero-lag and high performance.
     */
    suspend fun processImage(
        source: Bitmap,
        params: CustomPipelineParams,
        isFastPreview: Boolean = false
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = source.width
        val height = source.height

        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Precalculate Global Adjustment Multipliers & Curves
        val exposureGain = 2.0.pow(params.exposure.toDouble()).toFloat()

        // Temperature & Tint gains
        val tempVal = params.temperature / 100.0f
        val tintVal = params.tint / 100.0f
        val rGain = 1.0f + tempVal * 0.25f + tintVal * 0.12f
        val gGain = 1.0f - tintVal * 0.25f
        val bGain = 1.0f - tempVal * 0.25f + tintVal * 0.12f

        // 3x3 Color Rendering Matrix
        val colorMatrix = params.to3x3Matrix()
        val m00 = colorMatrix[0]; val m01 = colorMatrix[1]; val m02 = colorMatrix[2]
        val m10 = colorMatrix[3]; val m11 = colorMatrix[4]; val m12 = colorMatrix[5]
        val m20 = colorMatrix[6]; val m21 = colorMatrix[7]; val m22 = colorMatrix[8]

        // Tone & Contrast parameters
        val contrastFactor = params.contrast
        val highlightsAdj = params.highlights / 100.0f
        val shadowsAdj = params.shadows / 100.0f
        val whitesAdj = params.whites / 100.0f
        val blacksAdj = params.blacks / 100.0f
        val rollOffStrength = params.highlightRollOff / 100.0f
        val shadowRecoveryStrength = params.shadowRecovery / 100.0f
        val localToneMappingStrength = params.localToneMapping / 100.0f

        // Saturation & Vibrance
        val satMultiplier = 1.0f + (params.saturation / 100.0f)
        val vibranceAmt = params.vibrance / 100.0f

        // Detail, Texture & Sharpness
        val sharpnessAmt = if (isFastPreview) params.sharpness * 0.5f else params.sharpness
        val microContrastAmt = params.microContrast / 100.0f
        val textureAmt = params.texture / 100.0f
        val noiseReductionAmt = params.noiseReduction / 100.0f
        val detailPreserveAmt = params.detailPreservation / 100.0f

        // Parallel processing across CPU cores in horizontal chunks
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val chunkHeight = (height + cores - 1) / cores

        coroutineScope {
            val deferreds = (0 until cores).map { coreIdx ->
                async(Dispatchers.Default) {
                    val startY = coreIdx * chunkHeight
                    val endY = min(height, startY + chunkHeight)

                    for (y in startY until endY) {
                        val rowOffset = y * width
                        for (x in 0 until width) {
                            val idx = rowOffset + x
                            val pixel = pixels[idx]

                            var r = ((pixel shr 16) and 0xFF) / 255.0f
                            var g = ((pixel shr 8) and 0xFF) / 255.0f
                            var b = (pixel and 0xFF) / 255.0f

                            // 1. Exposure & White Balance Adjustment in Linear Space
                            r = (r * exposureGain * rGain)
                            g = (g * exposureGain * gGain)
                            b = (b * exposureGain * bGain)

                            // 2. 3x3 Color Matrix Transformation
                            val mr = m00 * r + m01 * g + m02 * b
                            val mg = m10 * r + m11 * g + m12 * b
                            val mb = m20 * r + m21 * g + m22 * b
                            r = mr; g = mg; b = mb

                            // 3. Luminance calculation
                            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b

                            // 4. Highlight Roll-off (Smooth shoulder curve preventing digital clipping)
                            if (rollOffStrength > 0.01f && luma > 0.65f) {
                                val hlFactor = (luma - 0.65f) / 0.35f
                                // Soft shoulder curve: compresses brightest tones smoothly
                                val rollOff = 1.0f - exp(-hlFactor * 2.2f) * 0.35f
                                val blend = hlFactor * rollOffStrength * 0.45f
                                r *= (1.0f - blend) + blend * rollOff
                                g *= (1.0f - blend) + blend * rollOff
                                b *= (1.0f - blend) + blend * rollOff
                            }

                            // 5. Shadow Recovery (Toe lifting without muddling true black)
                            if (shadowRecoveryStrength > 0.01f && luma < 0.40f && luma > 0.02f) {
                                val shFactor = (1.0f - luma / 0.40f)
                                val lift = shFactor * shadowRecoveryStrength * 0.35f
                                r += lift * (1.0f - r) * 0.3f
                                g += lift * (1.0f - g) * 0.3f
                                b += lift * (1.0f - b) * 0.3f
                            }

                            // 6. Highlights & Shadows Adjustments
                            if (highlightsAdj != 0.0f && luma > 0.50f) {
                                val factor = ((luma - 0.50f) / 0.50f) * highlightsAdj * 0.4f
                                r += factor * r
                                g += factor * g
                                b += factor * b
                            }
                            if (shadowsAdj != 0.0f && luma < 0.50f) {
                                val factor = ((0.50f - luma) / 0.50f) * shadowsAdj * 0.4f
                                r += factor * (1.0f - r)
                                g += factor * (1.0f - g)
                                b += factor * (1.0f - b)
                            }

                            // 7. Whites & Blacks Point Adjustments
                            if (whitesAdj != 0.0f) {
                                r *= (1.0f + whitesAdj * 0.25f)
                                g *= (1.0f + whitesAdj * 0.25f)
                                b *= (1.0f + whitesAdj * 0.25f)
                            }
                            if (blacksAdj != 0.0f && luma < 0.30f) {
                                val bFactor = (1.0f - luma / 0.30f) * blacksAdj * 0.20f
                                r += bFactor
                                g += bFactor
                                b += bFactor
                            }

                            // 8. Contrast (S-Curve centered at perceptual midtone)
                            if (contrastFactor != 1.0f) {
                                r = ((r - 0.5f) * contrastFactor + 0.5f)
                                g = ((g - 0.5f) * contrastFactor + 0.5f)
                                b = ((b - 0.5f) * contrastFactor + 0.5f)
                            }

                            // 9. Local Tone Mapping / HDR Compression
                            if (localToneMappingStrength > 0.01f) {
                                val normLuma = (r * 0.299f + g * 0.587f + b * 0.114f).coerceIn(0.001f, 1.0f)
                                val compressedLuma = normLuma / (1.0f + normLuma * (localToneMappingStrength * 0.8f))
                                val ratio = (compressedLuma / normLuma).coerceIn(0.5f, 2.0f)
                                val blendHdr = localToneMappingStrength * 0.5f
                                r = r * (1.0f - blendHdr) + (r * ratio) * blendHdr
                                g = g * (1.0f - blendHdr) + (g * ratio) * blendHdr
                                b = b * (1.0f - blendHdr) + (b * ratio) * blendHdr
                            }

                            // Clamp intermediate RGB to [0.0, 1.0]
                            r = r.coerceIn(0.0f, 1.0f)
                            g = g.coerceIn(0.0f, 1.0f)
                            b = b.coerceIn(0.0f, 1.0f)

                            // 10. Vibrance & Saturation (Protects human skin tones & prevents oversaturation)
                            val maxVal = max(r, max(g, b))
                            val minVal = min(r, min(g, b))
                            val currentSat = if (maxVal > 0.001f) (maxVal - minVal) / maxVal else 0.0f
                            val currentLuma = 0.299f * r + 0.587f * g + 0.114f * b

                            // Human skin tone detection in RGB
                            val isSkinTone = (r > g) && (g > b) && (r - g > 0.03f) && (r - g < 0.35f) && (currentSat in 0.15f..0.75f)

                            var finalSatMultiplier = satMultiplier
                            if (vibranceAmt != 0.0f) {
                                // Boost desaturated colors more; preserve skin tones and avoid clipping already saturated tones
                                val skinProtection = if (isSkinTone) 0.35f else 1.0f
                                val vibranceBoost = (1.0f - currentSat) * vibranceAmt * skinProtection
                                finalSatMultiplier += vibranceBoost
                            }

                            if (abs(finalSatMultiplier - 1.0f) > 0.005f) {
                                r = (currentLuma + (r - currentLuma) * finalSatMultiplier).coerceIn(0.0f, 1.0f)
                                g = (currentLuma + (g - currentLuma) * finalSatMultiplier).coerceIn(0.0f, 1.0f)
                                b = (currentLuma + (b - currentLuma) * finalSatMultiplier).coerceIn(0.0f, 1.0f)
                            }

                            val ir = (r * 255.0f).roundToInt().coerceIn(0, 255)
                            val ig = (g * 255.0f).roundToInt().coerceIn(0, 255)
                            val ib = (b * 255.0f).roundToInt().coerceIn(0, 255)

                            pixels[idx] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
                        }
                    }
                }
            }
            deferreds.awaitAll()
        }

        // 11. Spatial Detail, Texture, Micro-contrast & Sharpness (2D Convolution Pass)
        if (sharpnessAmt > 1.0f || abs(microContrastAmt) > 0.01f || abs(textureAmt) > 0.01f || noiseReductionAmt > 0.05f) {
            applySpatialDetailProcessing(
                pixels = pixels,
                width = width,
                height = height,
                sharpness = sharpnessAmt,
                microContrast = microContrastAmt,
                texture = textureAmt,
                noiseReduction = noiseReductionAmt,
                detailPreservation = detailPreserveAmt
            )
        }

        outBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        outBitmap
    }

    /**
     * Edge-preserving detail enhancement and spatial smoothing.
     */
    private fun applySpatialDetailProcessing(
        pixels: IntArray,
        width: Int,
        height: Int,
        sharpness: Float,
        microContrast: Float,
        texture: Float,
        noiseReduction: Float,
        detailPreservation: Float
    ) {
        val sharpWeight = (sharpness / 100.0f) * 0.45f
        val microWeight = microContrast * 0.35f
        val textWeight = texture * 0.30f
        val nrStrength = noiseReduction * 0.30f

        var prevRow = IntArray(width)
        var currRow = IntArray(width)
        var nextRow = IntArray(width)

        System.arraycopy(pixels, 0, prevRow, 0, width)
        System.arraycopy(pixels, width, currRow, 0, width)

        for (y in 1 until height - 1) {
            val nextRowOffset = (y + 1) * width
            System.arraycopy(pixels, nextRowOffset, nextRow, 0, width)
            val rowOffset = y * width

            for (x in 1 until width - 1) {
                val pC = currRow[x]
                val pT = prevRow[x]
                val pB = nextRow[x]
                val pL = currRow[x - 1]
                val pR = currRow[x + 1]

                val lumaC = ((pC shr 16) and 0xFF) * 0.299f + ((pC shr 8) and 0xFF) * 0.587f + (pC and 0xFF) * 0.114f
                val lumaT = ((pT shr 16) and 0xFF) * 0.299f + ((pT shr 8) and 0xFF) * 0.587f + (pT and 0xFF) * 0.114f
                val lumaB = ((pB shr 16) and 0xFF) * 0.299f + ((pB shr 8) and 0xFF) * 0.587f + (pB and 0xFF) * 0.114f
                val lumaL = ((pL shr 16) and 0xFF) * 0.299f + ((pL shr 8) and 0xFF) * 0.587f + (pL and 0xFF) * 0.114f
                val lumaR = ((pR shr 16) and 0xFF) * 0.299f + ((pR shr 8) and 0xFF) * 0.587f + (pR and 0xFF) * 0.114f

                // Laplacian high-pass for fine texture & sharpness
                val laplacian = 4.0f * lumaC - (lumaT + lumaB + lumaL + lumaR)
                val edgeGradient = abs(lumaR - lumaL) + abs(lumaB - lumaT)

                // High-contrast edge thresholding to prevent harsh white halos
                val edgeAdaptiveFactor = (1.0f - (edgeGradient / 255.0f)).coerceIn(0.2f, 1.0f)

                var delta = laplacian * (sharpWeight + textWeight) * edgeAdaptiveFactor

                // Micro-contrast / clarity (mid-frequency boost)
                if (microWeight != 0.0f) {
                    val localAvg = (lumaT + lumaB + lumaL + lumaR + lumaC) / 5.0f
                    val midDiff = lumaC - localAvg
                    delta += midDiff * microWeight
                }

                // Noise reduction on flat areas with low gradient
                if (nrStrength > 0.01f && edgeGradient < (35.0f * (1.0f - detailPreservation * 0.5f))) {
                    val smoothFactor = nrStrength * (1.0f - edgeGradient / 35.0f)
                    val localAvg = (lumaT + lumaB + lumaL + lumaR) / 4.0f
                    delta -= (lumaC - localAvg) * smoothFactor
                }

                var r = ((pC shr 16) and 0xFF) + delta
                var g = ((pC shr 8) and 0xFF) + delta
                var b = (pC and 0xFF) + delta

                r = r.coerceIn(0f, 255f)
                g = g.coerceIn(0f, 255f)
                b = b.coerceIn(0f, 255f)

                pixels[rowOffset + x] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }

            val temp = prevRow
            prevRow = currRow
            currRow = nextRow
            nextRow = temp
        }
    }

    /**
     * Renders directly to high-quality JPEG byte array without intermediate decoding.
     */
    suspend fun processAndEncodeToJpegBytes(
        source: Bitmap,
        params: CustomPipelineParams,
        jpegQuality: Int = 98
    ): ByteArray = withContext(Dispatchers.Default) {
        val processedBitmap = processImage(source, params, isFastPreview = false)
        val stream = ByteArrayOutputStream()
        processedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(80, 100), stream)
        if (processedBitmap != source) {
            processedBitmap.recycle()
        }
        stream.toByteArray()
    }
}
