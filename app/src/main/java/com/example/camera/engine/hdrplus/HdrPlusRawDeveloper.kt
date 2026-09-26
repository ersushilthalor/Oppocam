package com.example.camera.engine.hdrplus

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image
import android.util.Rational
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance developer for Camera2 ImageFormat.RAW_SENSOR frames.
 * Directly reads uncompressed Bayer CFA samples with exact rowStride/pixelStride geometry,
 * subtracts per-channel hardware black levels, performs full-resolution edge-directed
 * Hamilton-Adams + constant-hue color-difference demosaicing across all 4 CFA patterns,
 * applies white-balance and sensor-to-sRGB Color Correction Matrix (CCM), and normalizes
 * radiance while preserving highlight headroom and shadow gradation.
 */
class HdrPlusRawDeveloper {

    companion object {
        private const val TAG = "HdrPlusRawDev"

        // CFA Arrangement constants matching CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
        const val CFA_RGGB = 0
        const val CFA_GRBG = 1
        const val CFA_GBRG = 2
        const val CFA_BGGR = 3

        // Standard CIE XYZ (D50) to Linear sRGB (D65 Bradford-adapted) 3x3 matrix
        private val XYZ_D50_TO_SRGB = floatArrayOf(
             3.1338561f, -1.6168667f, -0.4906146f,
            -0.9787684f,  1.9161415f,  0.0334540f,
             0.0719453f, -0.2289914f,  1.4052427f
        )

        // Default white-point-preserving sensor-to-linear-sRGB CCM when device metadata is absent
        private val DEFAULT_SENSOR_CCM = floatArrayOf(
             1.32f, -0.24f, -0.08f,
            -0.16f,  1.28f, -0.12f,
            -0.06f, -0.30f,  1.36f
        )

        /**
         * Maps a pixel coordinate (x, y) and CFA arrangement to the canonical RGGB channel index:
         * 0 = Red, 1 = GreenEven (row with R), 2 = GreenOdd (row with B), 3 = Blue.
         */
        @JvmStatic
        fun cfaChannelAt(x: Int, y: Int, cfaPattern: Int): Int {
            val xOdd = x and 1
            val yOdd = y and 1
            return when (cfaPattern) {
                CFA_RGGB -> {
                    if (yOdd == 0) {
                        if (xOdd == 0) 0 else 1
                    } else {
                        if (xOdd == 0) 2 else 3
                    }
                }
                CFA_GRBG -> {
                    if (yOdd == 0) {
                        if (xOdd == 0) 1 else 0
                    } else {
                        if (xOdd == 0) 3 else 2
                    }
                }
                CFA_GBRG -> {
                    if (yOdd == 0) {
                        if (xOdd == 0) 2 else 3
                    } else {
                        if (xOdd == 0) 0 else 1
                    }
                }
                CFA_BGGR -> {
                    if (yOdd == 0) {
                        if (xOdd == 0) 3 else 2
                    } else {
                        if (xOdd == 0) 1 else 0
                    }
                }
                else -> {
                    if (yOdd == 0) {
                        if (xOdd == 0) 0 else 1
                    } else {
                        if (xOdd == 0) 2 else 3
                    }
                }
            }
        }
    }

    /**
     * Extracts physical RAW sensor data from an ImageFormat.RAW_SENSOR Image,
     * honoring rowStride and pixelStride to produce a clean, un-sheared width x height buffer.
     */
    fun extractRawFrame(
        image: Image,
        result: CaptureResult?,
        chars: CameraCharacteristics?,
        role: HdrPlusRole,
        evDelta: Float,
        fallbackExpTimeNs: Long = 33_333_333L,
        fallbackIso: Int = 100,
        fallbackResult: CaptureResult? = null
    ): HdrPlusRawFrame {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride.coerceAtLeast(width * 2)
        val pixelStride = plane.pixelStride.coerceAtLeast(2)

        // Extract tightly-packed width * height 16-bit Bayer samples respecting rowStride and pixelStride
        val rawData = ShortArray(width * height)
        if (pixelStride == 2 && rowStride == width * 2) {
            val shortBuf = buffer.asShortBuffer()
            val toRead = min(shortBuf.remaining(), rawData.size)
            shortBuf.get(rawData, 0, toRead)
        } else if (pixelStride == 2 && (rowStride and 1) == 0) {
            val shortBuf = buffer.asShortBuffer()
            val rowStrideShorts = rowStride shr 1
            val rowLimit = shortBuf.limit()
            for (y in 0 until height) {
                val srcPos = y * rowStrideShorts
                if (srcPos >= rowLimit) break
                val count = min(width, rowLimit - srcPos)
                shortBuf.position(srcPos)
                shortBuf.get(rawData, y * width, count)
            }
        } else {
            val byteLimit = buffer.limit()
            for (y in 0 until height) {
                val rowByteOffset = y * rowStride
                val dstRowOffset = y * width
                for (x in 0 until width) {
                    val byteIdx = rowByteOffset + x * pixelStride
                    if (byteIdx + 1 < byteLimit) {
                        rawData[dstRowOffset + x] = buffer.getShort(byteIdx)
                    }
                }
            }
        }

        return buildRawFrameWithMetadata(
            rawData = rawData,
            width = width,
            height = height,
            rowStride = width * 2,
            pixelStride = 2,
            timestampNs = image.timestamp,
            result = result,
            chars = chars,
            role = role,
            evDelta = evDelta,
            fallbackExpTimeNs = fallbackExpTimeNs,
            fallbackIso = fallbackIso,
            fallbackResult = fallbackResult
        )
    }

    /**
     * Enriches or constructs an HdrPlusRawFrame using CaptureResult and CameraCharacteristics metadata.
     */
    fun buildRawFrameWithMetadata(
        rawData: ShortArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestampNs: Long,
        result: CaptureResult?,
        chars: CameraCharacteristics?,
        role: HdrPlusRole,
        evDelta: Float,
        fallbackExpTimeNs: Long = 33_333_333L,
        fallbackIso: Int = 100,
        fallbackResult: CaptureResult? = null
    ): HdrPlusRawFrame {
        val calibResult = result ?: fallbackResult
        val whiteLevel = chars?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val cfa = chars?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: CFA_RGGB

        // Extract per-channel black levels in canonical [R, Gr, Gb, B] order
        val dynamicBlack = calibResult?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val staticBlackPattern = chars?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevelsRggb = FloatArray(4)

        if (dynamicBlack != null && dynamicBlack.size >= 4) {
            blackLevelsRggb[0] = dynamicBlack[0]
            blackLevelsRggb[1] = dynamicBlack[1]
            blackLevelsRggb[2] = dynamicBlack[2]
            blackLevelsRggb[3] = dynamicBlack[3]
        } else if (staticBlackPattern != null) {
            // Map 2x2 Bayer coordinates (col, row) to their canonical RGGB channel
            for (row in 0..1) {
                for (col in 0..1) {
                    val ch = cfaChannelAt(col, row, cfa)
                    blackLevelsRggb[ch] = staticBlackPattern.getOffsetForIndex(col, row).toFloat()
                }
            }
        } else {
            val defaultBl = if (whiteLevel > 4095) 256f else 64f
            for (i in 0..3) blackLevelsRggb[i] = defaultBl
        }

        val scalarBlackLevel = ((blackLevelsRggb[0] + blackLevelsRggb[1] + blackLevelsRggb[2] + blackLevelsRggb[3]) * 0.25f).toInt()

        // Read per-frame exposure and sensitivity strictly from frame's own CaptureResult,
        // falling back to the planned spec exposure/ISO so secondary bracket frames never inherit preview exposure.
        val expTimeNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ?: if (role == HdrPlusRole.BASE_PRIMARY) {
                fallbackResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: fallbackExpTimeNs
            } else {
                fallbackExpTimeNs
            }
        val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY)
            ?: if (role == HdrPlusRole.BASE_PRIMARY) {
                fallbackResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: fallbackIso
            } else {
                fallbackIso
            }
        val postRawBoost = result?.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
            ?: calibResult?.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
            ?: 100

        // Read white-balance gains from COLOR_CORRECTION_GAINS or SENSOR_NEUTRAL_COLOR_POINT
        val (rGain, gGain, bGain) = extractWhiteBalanceGains(calibResult)

        // Extract 3x3 sensor-to-linear-sRGB Color Correction Matrix
        val ccm = extractColorCorrectionMatrix(calibResult, chars)

        return HdrPlusRawFrame(
            rawData = rawData,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            cfaPattern = cfa,
            whiteLevel = whiteLevel,
            blackLevel = scalarBlackLevel,
            rGain = rGain,
            gGain = gGain,
            bGain = bGain,
            exposureTimeNs = expTimeNs,
            iso = iso,
            timestampNs = timestampNs,
            role = role,
            evDelta = evDelta,
            blackLevelPattern = blackLevelsRggb,
            colorCorrectionMatrix = ccm,
            postRawSensitivityBoost = postRawBoost
        )
    }

    private fun extractWhiteBalanceGains(result: CaptureResult?): Triple<Float, Float, Float> {
        // 1. Prefer SENSOR_NEUTRAL_COLOR_POINT if valid (defined as neutral white in sensor RGB space)
        val neutralPoint: Array<Rational>? = result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)

        if (gains != null) {
            val r = gains.red
            val g = (gains.greenEven + gains.greenOdd) * 0.5f
            val b = gains.blue
            if (r.isFinite() && g.isFinite() && b.isFinite() && r > 0.1f && g > 0.1f && b > 0.1f) {
                // Verify gains are not a degenerate (1, 1, 1) placeholder when neutralPoint has real values
                val isUnityPlaceholder = abs(r - 1.0f) < 0.02f && abs(g - 1.0f) < 0.02f && abs(b - 1.0f) < 0.02f
                if (!isUnityPlaceholder || neutralPoint == null) {
                    return Triple(r / g, 1.0f, b / g)
                }
            }
        }

        if (neutralPoint != null && neutralPoint.size >= 3) {
            val nR = neutralPoint[0].toFloat()
            val nG = neutralPoint[1].toFloat()
            val nB = neutralPoint[2].toFloat()
            if (nR > 1e-4f && nG > 1e-4f && nB > 1e-4f) {
                val rG = (nG / nR).coerceIn(0.4f, 4.5f)
                val bG = (nG / nB).coerceIn(0.4f, 4.5f)
                return Triple(rG, 1.0f, bG)
            }
        }

        return Triple(1.85f, 1.0f, 1.55f)
    }

    private fun extractColorCorrectionMatrix(
        result: CaptureResult?,
        chars: CameraCharacteristics?
    ): FloatArray {
        // 1. CaptureResult.COLOR_CORRECTION_TRANSFORM maps white-balanced sensor RGB to linear sRGB
        val dynTransform = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        if (dynTransform != null) {
            val m = transformToMatrix(dynTransform)
            if (isValidCcm(m)) {
                return normalizeCcmRows(m)
            }
        }

        // 2. Fallback to CameraCharacteristics.SENSOR_FORWARD_MATRIX1 (maps WB sensor RGB -> XYZ D50)
        val forwardMatrix1 = chars?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
        if (forwardMatrix1 != null) {
            val fwd = transformToMatrix(forwardMatrix1)
            val srgbMat = multiply3x3(XYZ_D50_TO_SRGB, fwd)
            if (isValidCcm(srgbMat)) {
                return normalizeCcmRows(srgbMat)
            }
        }

        return normalizeCcmRows(DEFAULT_SENSOR_CCM.copyOf())
    }

    private fun transformToMatrix(transform: ColorSpaceTransform): FloatArray {
        val out = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                out[row * 3 + col] = transform.getElement(col, row).toFloat()
            }
        }
        return out
    }

    private fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0..2) {
            for (c in 0..2) {
                out[r * 3 + c] =
                    a[r * 3 + 0] * b[0 * 3 + c] +
                    a[r * 3 + 1] * b[1 * 3 + c] +
                    a[r * 3 + 2] * b[2 * 3 + c]
            }
        }
        return out
    }

    private fun isValidCcm(m: FloatArray): Boolean {
        if (m.size < 9) return false
        for (v in m) {
            if (!v.isFinite() || abs(v) > 8.0f) return false
        }
        // Main diagonal must be strongly positive to prevent channel inversion
        if (m[0] < 0.4f || m[4] < 0.4f || m[8] < 0.4f) return false
        // Each row sum should be positive
        val r0 = m[0] + m[1] + m[2]
        val r1 = m[3] + m[4] + m[5]
        val r2 = m[6] + m[7] + m[8]
        return r0 > 0.25f && r1 > 0.25f && r2 > 0.25f
    }

    /**
     * Normalizes each row of the 3x3 CCM to sum to 1.0 so that neutral white [1, 1, 1]
     * after white balance is strictly preserved as [1, 1, 1] in linear sRGB.
     */
    private fun normalizeCcmRows(m: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0..2) {
            val idx = row * 3
            val sum = m[idx] + m[idx + 1] + m[idx + 2]
            if (abs(sum) > 1e-4f) {
                val inv = 1.0f / sum
                out[idx] = m[idx] * inv
                out[idx + 1] = m[idx + 1] * inv
                out[idx + 2] = m[idx + 2] * inv
            } else {
                out[idx] = if (row == 0) 1f else 0f
                out[idx + 1] = if (row == 1) 1f else 0f
                out[idx + 2] = if (row == 2) 1f else 0f
            }
        }
        return out
    }

    /**
     * Demosaics a RAW sensor frame into a full-resolution linear sRGB float buffer.
     * Uses Hamilton-Adams edge-directed Green interpolation followed by constant-hue
     * color-difference (R - G, B - G) interpolation, white-balance normalization,
     * highlight-safe sensor CCM conversion, and post-RAW sensitivity boost compensation.
     */
    fun developRawToLinearRgb(
        frame: HdrPlusRawFrame,
        referenceGains: Triple<Float, Float, Float>? = null,
        referenceCcm: FloatArray? = null,
        baseExposureGain: Float = 1.0f
    ): HdrPlusDevelopedImage {
        val width = frame.width
        val height = frame.height
        val raw = frame.rawData
        val cfa = frame.cfaPattern

        // Support both tightly-packed buffers and strided buffers passed directly in tests
        val pixelStep = if (frame.pixelStride >= 2) (frame.pixelStride shr 1).coerceAtLeast(1) else 1
        val strideShorts = if (frame.rowStride >= width * 2) (frame.rowStride shr 1) else width
        val isStridedBuffer = strideShorts > width &&
                raw.size >= (height - 1) * strideShorts + width * pixelStep
        val rowStep = if (isStridedBuffer) strideShorts else width
        val colStep = if (isStridedBuffer) pixelStep else 1

        val wLevel = frame.whiteLevel.toFloat().coerceAtLeast(64f)
        val blPattern = if (frame.blackLevelPattern.size >= 4) {
            frame.blackLevelPattern
        } else {
            val bl = frame.blackLevel.toFloat()
            floatArrayOf(bl, bl, bl, bl)
        }

        val invRanges = FloatArray(4) { ch ->
            1.0f / (wLevel - blPattern[ch]).coerceAtLeast(1.0f)
        }

        // Normalize reference white-balance gains so green gain = 1.0
        val (rawRG, rawGG, rawBG) = referenceGains ?: Triple(frame.rGain, frame.gGain, frame.bGain)
        val safeGG = if (rawGG.isFinite() && rawGG > 1e-4f) rawGG else 1.0f
        val rGain = (rawRG / safeGG).coerceIn(0.25f, 5.0f)
        val gGain = 1.0f
        val bGain = (rawBG / safeGG).coerceIn(0.25f, 5.0f)
        val channelWbGains = floatArrayOf(rGain, gGain, gGain, bGain)

        // Step 0: Build white-balanced normalized Bayer plane and track raw sensor saturation
        val totalPixels = width * height
        val wbBayer = FloatArray(totalPixels)
        val rawPreWb = FloatArray(totalPixels)

        for (y in 0 until height) {
            val srcRow = y * rowStep
            val dstRow = y * width
            for (x in 0 until width) {
                val srcIdx = srcRow + x * colStep
                val sample = if (srcIdx in raw.indices) (raw[srcIdx].toInt() and 0xFFFF).toFloat() else 0f
                val ch = cfaChannelAt(x, y, cfa)
                val linearNorm = ((sample - blPattern[ch]) * invRanges[ch]).coerceAtLeast(0.0f)
                val dstIdx = dstRow + x
                rawPreWb[dstIdx] = linearNorm.coerceAtMost(1.2f)
                wbBayer[dstIdx] = linearNorm * channelWbGains[ch]
            }
        }

        // Step 1: Full-resolution Green plane via Hamilton-Adams gradient-directed interpolation
        val greenPlane = FloatArray(totalPixels)
        for (y in 0 until height) {
            val rowOff = y * width
            val ym1 = max(0, y - 1) * width
            val yp1 = min(height - 1, y + 1) * width
            val ym2 = max(0, y - 2) * width
            val yp2 = min(height - 1, y + 2) * width

            for (x in 0 until width) {
                val ch = cfaChannelAt(x, y, cfa)
                val idx = rowOff + x
                if (ch == 1 || ch == 2) {
                    // Exact green sample from Gr or Gb pixel
                    greenPlane[idx] = wbBayer[idx]
                } else {
                    val xm1 = max(0, x - 1)
                    val xp1 = min(width - 1, x + 1)
                    val xm2 = max(0, x - 2)
                    val xp2 = min(width - 1, x + 2)

                    val gLeft = wbBayer[rowOff + xm1]
                    val gRight = wbBayer[rowOff + xp1]
                    val gUp = wbBayer[ym1 + x]
                    val gDown = wbBayer[yp1 + x]

                    val cCenter = wbBayer[idx]
                    val cLeft2 = wbBayer[rowOff + xm2]
                    val cRight2 = wbBayer[rowOff + xp2]
                    val cUp2 = wbBayer[ym2 + x]
                    val cDown2 = wbBayer[yp2 + x]

                    val lapH = 2.0f * cCenter - cLeft2 - cRight2
                    val lapV = 2.0f * cCenter - cUp2 - cDown2

                    val gradH = abs(gLeft - gRight) + abs(lapH) * 0.5f
                    val gradV = abs(gUp - gDown) + abs(lapV) * 0.5f

                    val minH = min(gLeft, gRight)
                    val maxH = max(gLeft, gRight)
                    val minV = min(gUp, gDown)
                    val maxV = max(gUp, gDown)

                    val gH = (0.5f * (gLeft + gRight) + 0.25f * lapH).coerceIn(minH, maxH)
                    val gV = (0.5f * (gUp + gDown) + 0.25f * lapV).coerceIn(minV, maxV)

                    val gradSum = gradH + gradV
                    greenPlane[idx] = if (gradSum > 1e-5f) {
                        (gradV * gH + gradH * gV) / gradSum
                    } else {
                        0.5f * (gH + gV)
                    }
                }
            }
        }

        // Determine active 3x3 CCM (sensor WB RGB -> linear sRGB)
        val activeCcm = referenceCcm ?: frame.colorCorrectionMatrix
        val ccm = if (activeCcm != null && isValidCcm(activeCcm)) {
            normalizeCcmRows(activeCcm)
        } else {
            // Identity when no CCM was provided in synthetic tests, or default sensor CCM when from camera
            null
        }

        val postBoostScale = (frame.postRawSensitivityBoost.coerceAtLeast(100).toFloat() / 100.0f) *
                baseExposureGain.coerceAtLeast(0.1f)

        // Step 2: Constant-hue color-difference (C - G) interpolation for Red & Blue + CCM & highlight handling
        val rgbLinear = FloatArray(totalPixels * 3)

        // Determine for the current CFA whether Red is on even or odd rows/cols
        val rRowParity = when (cfa) {
            CFA_RGGB, CFA_GRBG -> 0
            else -> 1
        }

        for (y in 0 until height) {
            val rowOff = y * width
            val ym1 = if (y > 0) (y - 1) * width else min(height - 1, y + 1) * width
            val yp1 = if (y + 1 < height) (y + 1) * width else max(0, y - 1) * width
            val isRedRow = (y and 1) == rRowParity

            for (x in 0 until width) {
                val xm1 = if (x > 0) x - 1 else min(width - 1, x + 1)
                val xp1 = if (x + 1 < width) x + 1 else max(0, x - 1)

                val idx = rowOff + x
                val ch = cfaChannelAt(x, y, cfa)
                val gVal = greenPlane[idx]

                var rVal: Float
                var bVal: Float

                when (ch) {
                    0 -> {
                        // Red site: R is exact, B is at 4 diagonals
                        rVal = wbBayer[idx]
                        bVal = interpolateDiagonalColorDiff(
                            wbBayer, greenPlane, gVal,
                            ym1 + xm1, ym1 + xp1, yp1 + xm1, yp1 + xp1
                        )
                    }
                    3 -> {
                        // Blue site: B is exact, R is at 4 diagonals
                        bVal = wbBayer[idx]
                        rVal = interpolateDiagonalColorDiff(
                            wbBayer, greenPlane, gVal,
                            ym1 + xm1, ym1 + xp1, yp1 + xm1, yp1 + xp1
                        )
                    }
                    else -> {
                        // Green site (Gr or Gb): one of R/B is horizontal, the other is vertical
                        val hDiff = 0.5f * ((wbBayer[rowOff + xm1] - greenPlane[rowOff + xm1]) +
                                (wbBayer[rowOff + xp1] - greenPlane[rowOff + xp1]))
                        val vDiff = 0.5f * ((wbBayer[ym1 + x] - greenPlane[ym1 + x]) +
                                (wbBayer[yp1 + x] - greenPlane[yp1 + x]))

                        val hEst = (gVal + hDiff).coerceAtLeast(0.0f)
                        val vEst = (gVal + vDiff).coerceAtLeast(0.0f)

                        if (isRedRow) {
                            rVal = hEst
                            bVal = vEst
                        } else {
                            rVal = vEst
                            bVal = hEst
                        }
                    }
                }

                // Step 3: Sensor saturation & highlight neutrality protection
                // Check local 2x2 maximum pre-WB raw sensor level to detect physical photodiode clipping
                val localRawMax = maxOf(
                    rawPreWb[idx],
                    rawPreWb[rowOff + xm1],
                    rawPreWb[ym1 + x]
                )

                // Apply sensor-to-sRGB CCM if available
                var outR = rVal
                var outG = gVal
                var outB = bVal

                if (ccm != null) {
                    val ccmR = (ccm[0] * rVal + ccm[1] * gVal + ccm[2] * bVal).coerceAtLeast(0.0f)
                    val ccmG = (ccm[3] * rVal + ccm[4] * gVal + ccm[5] * bVal).coerceAtLeast(0.0f)
                    val ccmB = (ccm[6] * rVal + ccm[7] * gVal + ccm[8] * bVal).coerceAtLeast(0.0f)

                    // Smoothly protect near-clipped sensor pixels from negative off-diagonal CCM tinting
                    if (localRawMax > 0.92f) {
                        val clipT = ((localRawMax - 0.92f) / 0.07f).coerceIn(0.0f, 1.0f)
                        val neutralLuma = 0.2126f * ccmR + 0.7152f * ccmG + 0.0722f * ccmB
                        outR = ccmR * (1.0f - clipT) + neutralLuma * clipT
                        outG = ccmG * (1.0f - clipT) + neutralLuma * clipT
                        outB = ccmB * (1.0f - clipT) + neutralLuma * clipT
                    } else {
                        outR = ccmR
                        outG = ccmG
                        outB = ccmB
                    }
                } else if (localRawMax > 0.96f) {
                    // Even without CCM, neutralize fully clipped raw specular highlights
                    val clipT = ((localRawMax - 0.96f) / 0.04f).coerceIn(0.0f, 1.0f)
                    val neutralLuma = 0.2126f * outR + 0.7152f * outG + 0.0722f * outB
                    outR = outR * (1.0f - clipT) + neutralLuma * clipT
                    outG = outG * (1.0f - clipT) + neutralLuma * clipT
                    outB = outB * (1.0f - clipT) + neutralLuma * clipT
                }

                val pIdx = idx * 3
                rgbLinear[pIdx] = (outR * postBoostScale).coerceIn(0.0f, 8.0f)
                rgbLinear[pIdx + 1] = (outG * postBoostScale).coerceIn(0.0f, 8.0f)
                rgbLinear[pIdx + 2] = (outB * postBoostScale).coerceIn(0.0f, 8.0f)
            }
        }

        return HdrPlusDevelopedImage(
            rgbLinear = rgbLinear,
            width = width,
            height = height,
            exposureTimeNs = frame.exposureTimeNs,
            iso = frame.iso,
            role = frame.role,
            evDelta = frame.evDelta,
            postRawSensitivityBoost = frame.postRawSensitivityBoost,
            baseExposureGain = baseExposureGain
        )
    }

    private inline fun interpolateDiagonalColorDiff(
        wbBayer: FloatArray,
        greenPlane: FloatArray,
        gCenter: Float,
        idxNW: Int,
        idxNE: Int,
        idxSW: Int,
        idxSE: Int
    ): Float {
        val cNW = wbBayer[idxNW]
        val cNE = wbBayer[idxNE]
        val cSW = wbBayer[idxSW]
        val cSE = wbBayer[idxSE]

        val dNW = cNW - greenPlane[idxNW]
        val dNE = cNE - greenPlane[idxNE]
        val dSW = cSW - greenPlane[idxSW]
        val dSE = cSE - greenPlane[idxSE]

        val gradNWSE = abs(cNW - cSE) + abs(greenPlane[idxNW] - greenPlane[idxSE])
        val gradNESW = abs(cNE - cSW) + abs(greenPlane[idxNE] - greenPlane[idxSW])

        val estNWSE = gCenter + 0.5f * (dNW + dSE)
        val estNESW = gCenter + 0.5f * (dNE + dSW)

        val gradSum = gradNWSE + gradNESW
        val blended = if (gradSum > 1e-5f) {
            (gradNESW * estNWSE + gradNWSE * estNESW) / gradSum
        } else {
            0.5f * (estNWSE + estNESW)
        }
        return blended.coerceAtLeast(0.0f)
    }

    /**
     * Creates a box-filtered downsampled 1-channel luminance thumbnail for accurate alignment and motion analysis.
     */
    fun createLumaThumbnail(developed: HdrPlusDevelopedImage, thumbW: Int = 160, thumbH: Int = 120): FloatArray {
        val safeThumbW = thumbW.coerceAtLeast(16)
        val safeThumbH = thumbH.coerceAtLeast(16)
        val out = FloatArray(safeThumbW * safeThumbH)
        val w = developed.width
        val h = developed.height
        val rgb = developed.rgbLinear

        val stepX = w.toFloat() / safeThumbW
        val stepY = h.toFloat() / safeThumbH

        for (ty in 0 until safeThumbH) {
            val y0 = (ty * stepY).toInt().coerceIn(0, h - 1)
            val y1 = ((ty + 0.5f) * stepY).toInt().coerceIn(0, h - 1)
            val outOffset = ty * safeThumbW

            for (tx in 0 until safeThumbW) {
                val x0 = (tx * stepX).toInt().coerceIn(0, w - 1)
                val x1 = ((tx + 0.5f) * stepX).toInt().coerceIn(0, w - 1)

                val idx00 = (y0 * w + x0) * 3
                val idx11 = (y1 * w + x1) * 3

                val l0 = 0.2126f * rgb[idx00] + 0.7152f * rgb[idx00 + 1] + 0.0722f * rgb[idx00 + 2]
                val l1 = 0.2126f * rgb[idx11] + 0.7152f * rgb[idx11 + 1] + 0.0722f * rgb[idx11 + 2]
                out[outOffset + tx] = 0.5f * (l0 + l1)
            }
        }
        return out
    }
}
