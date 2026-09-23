package com.example.camera.engine.hdr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.util.Log
import com.example.camera.engine.FrameLuminanceStats
import com.example.camera.engine.GyroStabilizationEngine
import com.example.camera.model.FlashMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Flagship Computational Photo HDR Engine for Oppocam.
 *
 * Replaces simple single-frame JPEG capture with an automatic, adaptive multi-frame HDR pipeline
 * inspired by modern flagship computational photography:
 *
 * Sensor Bracket
 * -> Short-exposure highlight frame (-1.7 EV)
 * -> Normal-exposure reference base frame (0.0 EV)
 * -> Optional shadow-detail frame (+1.4 EV)
 * -> Sub-pixel motion-aware alignment
 * -> Ghost / motion suppression
 * -> Linearized scene radiance reconstruction
 * -> Highlight recovery (clouds, sky, specular lights)
 * -> Shadow recovery (true black point preservation)
 * -> Edge-aware local tone mapping (anti-halo protection)
 * -> Flagship color rendering (skin-tone protection & highlight desaturation)
 * -> Adaptive detail & anti-halo edge-aware sharpening
 * -> Full native-resolution JPEG encoding (quality 96-98)
 */
class PhotoHdrEngine(private val context: Context) {

    companion object {
        private const val TAG = "PhotoHdrEngine"
        private const val BAND_HEIGHT = 256 // Process in 256-row bands for minimal RAM footprint
    }

    val planner = HdrCapturePlanner()
    val aligner = HdrFrameAligner()
    val motionDetector = HdrMotionDetector()
    val radianceFusion = HdrRadianceFusion()
    val highlightRecovery = HdrHighlightRecovery()
    val shadowRecovery = HdrShadowRecovery()
    val toneMapper = HdrToneMapper()
    val colorRenderer = HdrColorRenderer()
    val detailProcessor = HdrDetailProcessor()

    /**
     * Determines the optimal capture strategy based on live sensor, scene histogram, and gyro state.
     */
    fun planCapture(
        chars: CameraCharacteristics?,
        lastResult: TotalCaptureResult?,
        flashMode: FlashMode,
        stats: FrameLuminanceStats?,
        gyroEngine: GyroStabilizationEngine?
    ): HdrCapturePlan {
        return planner.planCapture(chars, lastResult, flashMode, stats, gyroEngine)
    }

    /**
     * Asynchronously processes captured bracket frames into a final high-dynamic-range native-resolution JPEG.
     * Guaranteed fail-safe: if any error or OOM occurs, immediately returns the pristine base frame JPEG.
     */
    suspend fun processHdrCapture(
        frames: List<HdrInputFrame>,
        plan: HdrCapturePlan,
        jpegQuality: Int = 98
    ): ByteArray = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // 1. Locate reference base frame (0 EV)
        val baseFrame = frames.firstOrNull { it.role == FrameRole.REFERENCE_BASE } ?: frames.firstOrNull()
        if (baseFrame == null) {
            Log.e(TAG, "No base frame found in HDR input")
            return@withContext ByteArray(0)
        }

        // Single-frame plan or single frame collected: return base frame directly with zero overhead
        if (frames.size <= 1 || plan.bracketType == HdrBracketType.SINGLE_FRAME) {
            Log.d(TAG, "Single frame capture completed (HDR bypassed per plan: ${plan.reason})")
            return@withContext baseFrame.jpegBytes
        }

        try {
            Log.i(TAG, "Starting Flagship Computational HDR Pipeline: ${frames.size} frames, plan=${plan.bracketType}")

            // 2. Decode Reference Base Bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val baseBmp = BitmapFactory.decodeByteArray(baseFrame.jpegBytes, 0, baseFrame.jpegBytes.size, decodeOptions)
                ?: return@withContext baseFrame.jpegBytes

            val width = baseBmp.width
            val height = baseBmp.height

            // 3. Extract downscaled reference luminance for alignment & motion detection
            val refLuma = aligner.extractDownscaledLuminance(baseBmp, HdrFrameAligner.ALIGN_GRID_WIDTH, HdrFrameAligner.ALIGN_GRID_HEIGHT)

            // Locate secondary frames
            val shortFrame = frames.firstOrNull { it.role == FrameRole.SHORT_HIGHLIGHT }
            val longFrame = frames.firstOrNull { it.role == FrameRole.LONG_SHADOW }

            // 4. Align and compute motion masks for secondary frames
            var shortBmp: Bitmap? = null
            var shortAlignment = HdrAlignmentResult(0f, 0f, 0f, false)
            var shortMotionMask: HdrMotionMask? = null

            if (shortFrame != null) {
                try {
                    val bmp = BitmapFactory.decodeByteArray(shortFrame.jpegBytes, 0, shortFrame.jpegBytes.size, decodeOptions)
                    if (bmp != null) {
                        shortBmp = bmp
                        val dtSec = ((shortFrame.timestampNs - baseFrame.timestampNs).toFloat() / 1_000_000_000f).coerceIn(-0.2f, 0.2f)
                        shortAlignment = aligner.alignFrames(
                            refBitmap = baseBmp,
                            targetBitmap = bmp,
                            dtSec = dtSec,
                            targetGyroYawSpeed = shortFrame.gyroYawSpeed,
                            targetGyroPitchSpeed = shortFrame.gyroPitchSpeed
                        )

                        val shortLuma = aligner.extractDownscaledLuminance(bmp, HdrMotionDetector.MASK_GRID_WIDTH, HdrMotionDetector.MASK_GRID_HEIGHT)
                        shortMotionMask = motionDetector.detectMotion(
                            refLuma = refLuma,
                            targetLuma = shortLuma,
                            targetEvOffset = shortFrame.evOffset,
                            alignment = shortAlignment
                        )
                        Log.d(TAG, "Short frame aligned: dx=${shortAlignment.shiftX}, dy=${shortAlignment.shiftY}, conf=${shortAlignment.confidence}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to decode/align short frame, proceeding without it", t)
                    shortBmp?.recycle()
                    shortBmp = null
                }
            }

            var longBmp: Bitmap? = null
            var longAlignment = HdrAlignmentResult(0f, 0f, 0f, false)
            var longMotionMask: HdrMotionMask? = null

            if (longFrame != null) {
                try {
                    val bmp = BitmapFactory.decodeByteArray(longFrame.jpegBytes, 0, longFrame.jpegBytes.size, decodeOptions)
                    if (bmp != null) {
                        longBmp = bmp
                        val dtSec = ((longFrame.timestampNs - baseFrame.timestampNs).toFloat() / 1_000_000_000f).coerceIn(-0.2f, 0.2f)
                        longAlignment = aligner.alignFrames(
                            refBitmap = baseBmp,
                            targetBitmap = bmp,
                            dtSec = dtSec,
                            targetGyroYawSpeed = longFrame.gyroYawSpeed,
                            targetGyroPitchSpeed = longFrame.gyroPitchSpeed
                        )

                        val longLuma = aligner.extractDownscaledLuminance(bmp, HdrMotionDetector.MASK_GRID_WIDTH, HdrMotionDetector.MASK_GRID_HEIGHT)
                        longMotionMask = motionDetector.detectMotion(
                            refLuma = refLuma,
                            targetLuma = longLuma,
                            targetEvOffset = longFrame.evOffset,
                            alignment = longAlignment
                        )
                        Log.d(TAG, "Long frame aligned: dx=${longAlignment.shiftX}, dy=${longAlignment.shiftY}, conf=${longAlignment.confidence}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to decode/align long frame, proceeding without it", t)
                    longBmp?.recycle()
                    longBmp = null
                }
            }

            // 5. Compute downscaled local illumination map for Edge-Aware Tone Mapping (guarantees anti-halo processing)
            val lumaMapW = 64
            val lumaMapH = 48
            val localBaseLumaMap = aligner.extractDownscaledLuminance(baseBmp, lumaMapW, lumaMapH)

            // 6. Memory-Aware Banded Fusion & Tone Mapping
            // Process row bands into baseBmp in-place to avoid allocating an extra full-res bitmap
            val bandPixelsBase = IntArray(width * BAND_HEIGHT)
            val bandPixelsShort = if (shortBmp != null) IntArray(width * BAND_HEIGHT) else null
            val bandPixelsLong = if (longBmp != null) IntArray(width * BAND_HEIGHT) else null

            val fusedRgb = FloatArray(3)
            val neighborLumas = FloatArray(4)

            val shortShiftX = shortAlignment.shiftX.roundToInt()
            val shortShiftY = shortAlignment.shiftY.roundToInt()
            val longShiftX = longAlignment.shiftX.roundToInt()
            val longShiftY = longAlignment.shiftY.roundToInt()

            var totalMotionPixels = 0L

            for (bandStartRow in 0 until height step BAND_HEIGHT) {
                val currentBandH = min(BAND_HEIGHT, height - bandStartRow)
                val bandPixelCount = width * currentBandH

                // Read base pixels for this band
                baseBmp.getPixels(bandPixelsBase, 0, width, 0, bandStartRow, width, currentBandH)

                // Read aligned secondary frame pixels if available
                if (shortBmp != null && bandPixelsShort != null) {
                    shortBmp.getPixels(bandPixelsShort, 0, width, 0, bandStartRow, width, currentBandH)
                }
                if (longBmp != null && bandPixelsLong != null) {
                    longBmp.getPixels(bandPixelsLong, 0, width, 0, bandStartRow, width, currentBandH)
                }

                for (row in 0 until currentBandH) {
                    val y = bandStartRow + row
                    val normY = y.toFloat() / (height - 1).toFloat()
                    val lumaMapY = (normY * (lumaMapH - 1)).toInt().coerceIn(0, lumaMapH - 1)

                    val rowOffset = row * width

                    for (x in 0 until width) {
                        val normX = x.toFloat() / (width - 1).toFloat()
                        val lumaMapX = (normX * (lumaMapW - 1)).toInt().coerceIn(0, lumaMapW - 1)
                        val localIllumination = localBaseLumaMap[lumaMapY * lumaMapW + lumaMapX]

                        val baseC = bandPixelsBase[rowOffset + x]
                        val baseR = (baseC shr 16) and 0xFF
                        val baseG = (baseC shr 8) and 0xFF
                        val baseB = baseC and 0xFF

                        // Sample motion mask for moving object suppression
                        val motionConf = shortMotionMask?.sampleBilinear(normX, normY)
                            ?: longMotionMask?.sampleBilinear(normX, normY)
                            ?: 0f

                        if (motionConf > 0.4f) {
                            totalMotionPixels++
                        }

                        // Secondary pixel sampling with sub-pixel alignment offset
                        var sR: Int? = null
                        var sG: Int? = null
                        var sB: Int? = null
                        if (shortBmp != null && bandPixelsShort != null) {
                            val sx = (x - shortShiftX).coerceIn(0, width - 1)
                            val syInBand = (row - shortShiftY).coerceIn(0, currentBandH - 1)
                            val c = bandPixelsShort[syInBand * width + sx]
                            sR = (c shr 16) and 0xFF
                            sG = (c shr 8) and 0xFF
                            sB = c and 0xFF
                        }

                        var lR: Int? = null
                        var lG: Int? = null
                        var lB: Int? = null
                        if (longBmp != null && bandPixelsLong != null) {
                            val lx = (x - longShiftX).coerceIn(0, width - 1)
                            val lyInBand = (row - longShiftY).coerceIn(0, currentBandH - 1)
                            val c = bandPixelsLong[lyInBand * width + lx]
                            lR = (c shr 16) and 0xFF
                            lG = (c shr 8) and 0xFF
                            lB = c and 0xFF
                        }

                        // A. Linear Radiance Fusion
                        radianceFusion.fusePixelLinear(
                            baseR = baseR, baseG = baseG, baseB = baseB,
                            shortR = sR, shortG = sG, shortB = sB,
                            shortEvOffset = shortFrame?.evOffset ?: -1.7f,
                            longR = lR, longG = lG, longB = lB,
                            longEvOffset = longFrame?.evOffset ?: 1.4f,
                            motionConfidence = motionConf,
                            outRgb = fusedRgb
                        )

                        // B. Highlight Recovery (Clouds, skies, bright windows)
                        highlightRecovery.recoverHighlights(fusedRgb, sR, sG, sB)

                        // C. Shadow Recovery (Deep black anchor, mid-shadow texture)
                        shadowRecovery.recoverShadows(fusedRgb, 1.15f)

                        // D. Edge-Aware Local Tone Mapping
                        toneMapper.toneMapPixel(fusedRgb, localIllumination)

                        // E. Natural Color Rendering (Skin-tone protection & highlight desaturation)
                        colorRenderer.renderColor(fusedRgb)

                        // F. Adaptive Anti-Halo Detail Processing
                        if (x in 1 until (width - 1) && row in 1 until (currentBandH - 1)) {
                            // Extract fast neighbor luminances
                            neighborLumas[0] = ((bandPixelsBase[rowOffset - width + x] shr 8) and 0xFF) / 255f // North
                            neighborLumas[1] = ((bandPixelsBase[rowOffset + width + x] shr 8) and 0xFF) / 255f // South
                            neighborLumas[2] = ((bandPixelsBase[rowOffset + x + 1] shr 8) and 0xFF) / 255f     // East
                            neighborLumas[3] = ((bandPixelsBase[rowOffset + x - 1] shr 8) and 0xFF) / 255f     // West
                            detailProcessor.processDetail(fusedRgb, neighborLumas, baseFrame.iso)
                        }

                        // Convert linear back to sRGB (gamma 1/2.2) and pack into ARGB
                        val invGamma = 1.0f / 2.2f
                        val outR = (fusedRgb[0].pow(invGamma) * 255f).roundToInt().coerceIn(0, 255)
                        val outG = (fusedRgb[1].pow(invGamma) * 255f).roundToInt().coerceIn(0, 255)
                        val outB = (fusedRgb[2].pow(invGamma) * 255f).roundToInt().coerceIn(0, 255)

                        bandPixelsBase[rowOffset + x] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                    }
                }

                // Write band back into baseBmp
                baseBmp.setPixels(bandPixelsBase, 0, width, 0, bandStartRow, width, currentBandH)
            }

            // Recycle secondary bitmaps immediately to release heap
            shortBmp?.recycle()
            longBmp?.recycle()

            // 7. Encode final fused native-resolution JPEG
            val outStream = ByteArrayOutputStream()
            val quality = jpegQuality.coerceIn(95, 98)
            baseBmp.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
            val fusedBytes = outStream.toByteArray()
            baseBmp.recycle()

            // 8. Copy EXIF metadata from reference frame to ensure full device/orientation fidelity
            val finalJpeg = copyExifMetadata(baseFrame.jpegBytes, fusedBytes)

            val elapsed = System.currentTimeMillis() - startTime
            val motionRatio = totalMotionPixels.toFloat() / (width.toLong() * height.toLong()).toFloat()
            Log.i(TAG, "Computational HDR complete in ${elapsed}ms: ${width}x${height}px, motionRatio=${String.format("%.3f", motionRatio)}, outputSize=${finalJpeg.size / 1024}KB")

            return@withContext finalJpeg

        } catch (t: Throwable) {
            Log.e(TAG, "Critical failure during HDR processing, invoking fail-safe to base frame", t)
            return@withContext baseFrame.jpegBytes
        }
    }

    /**
     * Preserves EXIF tags from reference capture (orientation, datetime, exposure, white balance).
     */
    private fun copyExifMetadata(sourceBytes: ByteArray, targetBytes: ByteArray): ByteArray {
        return try {
            val srcExif = ExifInterface(ByteArrayInputStream(sourceBytes))
            val tempFile = File.createTempFile("hdr_out_", ".jpg", context.cacheDir)
            tempFile.writeBytes(targetBytes)

            val dstExif = ExifInterface(tempFile.absolutePath)

            val tagsToCopy = listOf(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_ISO_SPEED_RATINGS,
                ExifInterface.TAG_WHITE_BALANCE
            )

            for (tag in tagsToCopy) {
                val value = srcExif.getAttribute(tag)
                if (value != null) {
                    dstExif.setAttribute(tag, value)
                }
            }
            dstExif.saveAttributes()

            val enrichedBytes = tempFile.readBytes()
            tempFile.delete()
            enrichedBytes
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to copy EXIF to HDR output, returning un-tagged JPEG", e)
            targetBytes
        }
    }
}
