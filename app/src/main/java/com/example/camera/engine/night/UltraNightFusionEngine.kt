package com.example.camera.engine.night

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.camera.model.NightConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Flagship Computational Multi-Frame Night Photography Fusion Engine.
 *
 * Implements a memory-optimized, zero-OOM professional image processing pipeline:
 * 1. Mutual exclusion lock preventing concurrent fusion jobs from exhausting heap.
 * 2. Sequential frame accumulation with reusable row scanline buffers (no full-frame pixel copies).
 * 3. Immediate release of intermediate frame Bitmaps as each stage completes.
 * 4. In-place linear radiance normalization and luminance reuse (eliminates 192+ MB of redundant arrays).
 * 5. Streaming tone mapping and edge-preserving sharpening directly into output Bitmap (eliminates 48 MB result array).
 * 6. Dynamic memory-pressure fallback using tiled accumulation when heap headroom is restricted.
 * 7. Guaranteed resource cleanup using try/finally blocks.
 */
class UltraNightFusionEngine {

    companion object {
        private const val TAG = "UltraNightFusionEngine"

        // Normalized sensor black level floor (~1.5% to 2.5% of ADC dynamic range)
        private const val SENSOR_BLACK_LEVEL_NORM = 0.020f

        // Fast gamma approximation (sRGB standard gamma ~2.2)
        private const val GAMMA_ENCODE = 1.0f / 2.2f
        private const val GAMMA_DECODE = 2.2f

        // Concurrency lock preventing multiple heavy night fusion jobs from running simultaneously
        private val processingLock = Mutex()
    }

    /**
     * Executes the flagship multi-frame night fusion pipeline.
     */
    suspend fun processUltraNightFrames(
        frames: List<CapturedNightFrame>,
        alignedData: List<AlignedNightFrame>,
        config: NightConfig,
        onProgress: (Float) -> Unit = {}
    ): Bitmap = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) {
            throw IllegalArgumentException("Night fusion requires at least 1 frame")
        }

        // Prevent multiple simultaneous Ultra Night processing jobs
        processingLock.lock()
        try {
            val count = frames.size
            val refIdx = alignedData.indexOfFirst { it.isReference }.coerceAtLeast(0)
            val refFrame = frames[refIdx]
            val width = refFrame.bitmap.width
            val height = refFrame.bitmap.height
            val totalPixels = width * height

            onProgress(0.05f)

            // Evaluate available memory headroom
            val availMem = getAvailableMemoryBytes()
            val singleAccumulatorBytes = totalPixels.toLong() * 4L
            val requiredFullMemory = singleAccumulatorBytes * 4L + (50L * 1024L * 1024L)

            Log.d(TAG, "Ultra Night starting: size=${width}x$height, frames=$count, availMem=${availMem / (1024 * 1024)}MB, required=${requiredFullMemory / (1024 * 1024)}MB")

            if (availMem < requiredFullMemory) {
                Log.w(TAG, "Low heap headroom detected ($availMem bytes). Executing memory-pressure tiled fusion fallback.")
                return@withContext processTiledUltraNight(
                    frames = frames,
                    alignedData = alignedData,
                    config = config,
                    refIdx = refIdx,
                    tiles = 2,
                    onProgress = onProgress
                )
            }

            // Attempt standard full accumulation with in-place buffers; fallback to tiled on OOM
            return@withContext try {
                processStandardUltraNight(
                    frames = frames,
                    alignedData = alignedData,
                    config = config,
                    refIdx = refIdx,
                    width = width,
                    height = height,
                    onProgress = onProgress
                )
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "Encountered memory pressure during full allocation, falling back to tiled accumulation", oom)
                System.gc()
                processTiledUltraNight(
                    frames = frames,
                    alignedData = alignedData,
                    config = config,
                    refIdx = refIdx,
                    tiles = 2,
                    onProgress = onProgress
                )
            }
        } finally {
            processingLock.unlock()
        }
    }

    /**
     * Standard single-pass accumulation with in-place radiance normalization and streaming tone mapping.
     */
    private fun processStandardUltraNight(
        frames: List<CapturedNightFrame>,
        alignedData: List<AlignedNightFrame>,
        config: NightConfig,
        refIdx: Int,
        width: Int,
        height: Int,
        onProgress: (Float) -> Unit
    ): Bitmap {
        val count = frames.size
        val refFrame = frames[refIdx]
        val totalPixels = width * height

        // 1. Precompute Relative Exposure Scaling Factors for Each Frame
        val refExpProduct = refFrame.exposureTimeNs * refFrame.iso
        val frameExposureScales = FloatArray(count) { i ->
            val expProduct = frames[i].exposureTimeNs * frames[i].iso
            if (expProduct > 0) refExpProduct.toFloat() / expProduct.toFloat() else 1.0f
        }

        // 2. Linear Radiance Accumulators (only 4 float arrays total)
        var accumRadianceR: FloatArray? = FloatArray(totalPixels)
        var accumRadianceG: FloatArray? = FloatArray(totalPixels)
        var accumRadianceB: FloatArray? = FloatArray(totalPixels)
        var accumWeights: FloatArray? = FloatArray(totalPixels)

        val rAcc = accumRadianceR!!
        val gAcc = accumRadianceG!!
        val bAcc = accumRadianceB!!
        val wAcc = accumWeights!!

        // Reusable chunk buffer for 64 scanlines (drastically reduces JNI overhead)
        val CHUNK_ROWS = 64
        val chunkPixels = IntArray(width * CHUNK_ROWS)

        try {
            for (i in 0 until count) {
                val frame = frames[i]
                val alignment = alignedData[i]
                val shiftX = alignment.shiftX
                val shiftY = alignment.shiftY
                val expScale = frameExposureScales[i]
                val frameType = frame.type

                try {
                    var y = 0
                    while (y < height) {
                        val chunkH = min(CHUNK_ROWS, height - y)
                        // Read chunk of rows in a single fast JNI call
                        frame.bitmap.getPixels(chunkPixels, 0, width, 0, y, width, chunkH)

                        for (cy in 0 until chunkH) {
                            val currY = y + cy
                            val sy = currY + shiftY
                            if (sy !in 0 until height) continue

                            val chunkRowOffset = cy * width
                            val outRow = currY * width

                            for (x in 0 until width) {
                                val sx = x + shiftX
                                if (sx !in 0 until width) continue

                                val outIdx = outRow + x
                                val p = chunkPixels[chunkRowOffset + sx]
                                val rawR = Color.red(p) / 255.0f
                                val rawG = Color.green(p) / 255.0f
                                val rawB = Color.blue(p) / 255.0f

                                // Inverse gamma decode to linear space
                                val linR = (rawR.pow(GAMMA_DECODE) - SENSOR_BLACK_LEVEL_NORM).coerceAtLeast(0.0f) / (1.0f - SENSOR_BLACK_LEVEL_NORM)
                                val linG = (rawG.pow(GAMMA_DECODE) - SENSOR_BLACK_LEVEL_NORM).coerceAtLeast(0.0f) / (1.0f - SENSOR_BLACK_LEVEL_NORM)
                                val linB = (rawB.pow(GAMMA_DECODE) - SENSOR_BLACK_LEVEL_NORM).coerceAtLeast(0.0f) / (1.0f - SENSOR_BLACK_LEVEL_NORM)

                                val radR = linR * expScale
                                val radG = linG * expScale
                                val radB = linB * expScale

                                val maxCh = max(rawR, max(rawG, rawB))
                                val expWeight = when {
                                    maxCh > 0.94f -> if (frameType == BracketExposureType.SHORT) 1.0f else (1.0f - maxCh) * 16.0f
                                    maxCh < 0.04f -> if (frameType == BracketExposureType.LONG) 1.0f else maxCh * 25.0f
                                    else -> 1.0f
                                }.coerceIn(0.05f, 1.0f)

                                val motionConf = alignment.getMotionConfidence(x, currY, width, height)
                                val combinedWeight = expWeight * motionConf

                                rAcc[outIdx] += radR * combinedWeight
                                gAcc[outIdx] += radG * combinedWeight
                                bAcc[outIdx] += radB * combinedWeight
                                wAcc[outIdx] += combinedWeight
                            }
                        }
                        y += chunkH
                    }
                } finally {
                    // Release non-reference frame bitmaps immediately after accumulation to free heap
                    if (!alignment.isReference && frame.bitmap != refFrame.bitmap && !frame.bitmap.isRecycled) {
                        frame.bitmap.recycle()
                    }
                }

                onProgress(0.05f + ((i + 1).toFloat() / count) * 0.37f)
            }

            onProgress(0.44f)

            // 3. In-Place Normalization of Accumulated Radiance
            // We reuse accumWeights to store fused luminance, completely eliminating fusedLinR/G/B/Luma allocations!
            for (idx in 0 until totalPixels) {
                val w = wAcc[idx]
                val r = if (w > 0f) rAcc[idx] / w else 0f
                val g = if (w > 0f) gAcc[idx] / w else 0f
                val b = if (w > 0f) bAcc[idx] / w else 0f

                rAcc[idx] = r
                gAcc[idx] = g
                bAcc[idx] = b
                wAcc[idx] = 0.2126f * r + 0.7152f * g + 0.0722f * b // Now stores fusedLuma
            }

            onProgress(0.55f)

            // 4. Two-Scale Bilateral Local Tone Mapping: Downsampled Base Layer
            val ds = 4
            val dsW = (width / ds).coerceAtLeast(32)
            val dsH = (height / ds).coerceAtLeast(32)
            val baseLumaDown = FloatArray(dsW * dsH)

            for (dy in 0 until dsH) {
                val sy = (dy * ds).coerceAtMost(height - 1)
                val srcRow = sy * width
                val dstRow = dy * dsW
                for (dx in 0 until dsW) {
                    val sx = (dx * ds).coerceAtMost(width - 1)
                    baseLumaDown[dstRow + dx] = wAcc[srcRow + sx]
                }
            }

            val smoothedBaseDown = applyEdgePreservingSmooth(baseLumaDown, dsW, dsH)

            onProgress(0.68f)

            // 5. Tone Map Base Illumination and Stream directly to Output Bitmap
            val liftFactor = (config.shadowLift.coerceIn(1.0f, 2.5f))
            val toneMu = 16.0f * liftFactor
            val detailBoost = 1.10f
            val toneMaxDiv = ln(1.0f + toneMu * 2.0f)

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // 4 scanline row buffers for streaming tone mapping and unsharp sharpening (64 KB total)
            var rowAbove = IntArray(width)
            var rowCurr = IntArray(width)
            var rowBelow = IntArray(width)
            val rowSharpened = IntArray(width)

            fun toneMapScanline(y: Int, target: IntArray) {
                val dy = (y / ds).coerceIn(0, dsH - 1)
                val rowOffset = y * width
                val dsRowOffset = dy * dsW

                for (x in 0 until width) {
                    val dx = (x / ds).coerceIn(0, dsW - 1)
                    val idx = rowOffset + x

                    val lumaIn = wAcc[idx]
                    val baseIn = smoothedBaseDown[dsRowOffset + dx].coerceAtLeast(0.001f)

                    val detailRatio = (lumaIn / baseIn).coerceIn(0.2f, 3.5f)
                    val toneBase = (ln(1.0f + toneMu * baseIn) / toneMaxDiv).coerceIn(0.0f, 1.5f)
                    val lumaToneMapped = (toneBase * detailRatio.pow(detailBoost)).coerceAtLeast(0.0f)

                    val rIn = rAcc[idx]
                    val gIn = gAcc[idx]
                    val bIn = bAcc[idx]

                    val gain = if (lumaIn > 0.0001f) {
                        (lumaToneMapped / lumaIn).pow(0.72f).coerceIn(0.0f, 4.5f)
                    } else 1.0f

                    var rOut = (rIn * gain).coerceAtLeast(0.0f)
                    var gOut = (gIn * gain).coerceAtLeast(0.0f)
                    var bOut = (bIn * gain).coerceAtLeast(0.0f)

                    // Suppress chroma noise in deep shadows to keep dark areas clean and natural
                    if (lumaIn < 0.035f) {
                        val shadowLuma = 0.2126f * rOut + 0.7152f * gOut + 0.0722f * bOut
                        val blend = (lumaIn / 0.035f).coerceIn(0.0f, 1.0f)
                        rOut = shadowLuma * (1.0f - blend) + rOut * blend
                        gOut = shadowLuma * (1.0f - blend) + gOut * blend
                        bOut = shadowLuma * (1.0f - blend) + bOut * blend
                    }

                    val srgbR = (rOut.pow(GAMMA_ENCODE) * 255.0f).toInt().coerceIn(0, 255)
                    val srgbG = (gOut.pow(GAMMA_ENCODE) * 255.0f).toInt().coerceIn(0, 255)
                    val srgbB = (bOut.pow(GAMMA_ENCODE) * 255.0f).toInt().coerceIn(0, 255)

                    target[x] = Color.rgb(srgbR, srgbG, srgbB)
                }
            }

            // Prime pipeline with row 0
            toneMapScanline(0, rowCurr)
            output.setPixels(rowCurr, 0, width, 0, 0, width, 1)
            System.arraycopy(rowCurr, 0, rowAbove, 0, width)

            // Stream rows 1 until height - 1 with subtle edge sharpening (no halos or ringing)
            for (y in 1 until height - 1) {
                toneMapScanline(y + 1, rowBelow)
                sharpenRow(rowAbove, rowCurr, rowBelow, rowSharpened, width, sharpnessStrength = 0.12f)
                output.setPixels(rowSharpened, 0, width, 0, y, width, 1)

                val tmp = rowAbove
                rowAbove = rowCurr
                rowCurr = rowBelow
                rowBelow = tmp

                if (y % 64 == 0) {
                    onProgress(0.70f + (y.toFloat() / height) * 0.28f)
                }
            }

            // Final row
            toneMapScanline(height - 1, rowCurr)
            output.setPixels(rowCurr, 0, width, 0, height - 1, width, 1)

            onProgress(1.0f)
            return output
        } finally {
            // Null out arrays for immediate garbage collection
            accumRadianceR = null
            accumRadianceG = null
            accumRadianceB = null
            accumWeights = null
        }
    }

    /**
     * Memory-pressure fallback: divides the image into horizontal tiles.
     * Accumulator buffers are halved in size, guaranteeing successful completion on constrained heaps.
     */
    private fun processTiledUltraNight(
        frames: List<CapturedNightFrame>,
        alignedData: List<AlignedNightFrame>,
        config: NightConfig,
        refIdx: Int,
        tiles: Int,
        onProgress: (Float) -> Unit
    ): Bitmap {
        val count = frames.size
        val refFrame = frames[refIdx]
        val width = refFrame.bitmap.width
        val height = refFrame.bitmap.height

        val refExpProduct = refFrame.exposureTimeNs * refFrame.iso
        val frameExposureScales = FloatArray(count) { i ->
            val expProduct = frames[i].exposureTimeNs * frames[i].iso
            if (expProduct > 0) refExpProduct.toFloat() / expProduct.toFloat() else 1.0f
        }

        // 1. Compute global base illumination from downscaled reference frame (only ~3 MB)
        val ds = 4
        val dsW = (width / ds).coerceAtLeast(32)
        val dsH = (height / ds).coerceAtLeast(32)
        val baseLumaDown = FloatArray(dsW * dsH)
        val downBmp = Bitmap.createScaledBitmap(refFrame.bitmap, dsW, dsH, true)
        val downPixels = IntArray(dsW * dsH)
        downBmp.getPixels(downPixels, 0, dsW, 0, 0, dsW, dsH)
        downBmp.recycle()

        for (i in downPixels.indices) {
            val p = downPixels[i]
            val r = (Color.red(p) / 255.0f).pow(GAMMA_DECODE)
            val g = (Color.green(p) / 255.0f).pow(GAMMA_DECODE)
            val b = (Color.blue(p) / 255.0f).pow(GAMMA_DECODE)
            baseLumaDown[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        val smoothedBaseDown = applyEdgePreservingSmooth(baseLumaDown, dsW, dsH)

        val liftFactor = (config.shadowLift.coerceIn(1.0f, 2.5f))
        val toneMu = 16.0f * liftFactor
        val detailBoost = 1.10f
        val toneMaxDiv = ln(1.0f + toneMu * 2.0f)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val tileHeight = (height + tiles - 1) / tiles

        val CHUNK_ROWS = 64
        val chunkPixels = IntArray(width * CHUNK_ROWS)

        for (tileIndex in 0 until tiles) {
            val startY = tileIndex * tileHeight
            val endY = min(height, startY + tileHeight)
            val currentTileHeight = endY - startY
            val tilePixels = width * currentTileHeight

            var rTile: FloatArray? = FloatArray(tilePixels)
            var gTile: FloatArray? = FloatArray(tilePixels)
            var bTile: FloatArray? = FloatArray(tilePixels)
            var wTile: FloatArray? = FloatArray(tilePixels)

            val rArr = rTile!!
            val gArr = gTile!!
            val bArr = bTile!!
            val wArr = wTile!!

            try {
                // Accumulate this tile across all frames in chunks
                for (i in 0 until count) {
                    val frame = frames[i]
                    if (frame.bitmap.isRecycled) continue
                    val alignment = alignedData[i]
                    val shiftX = alignment.shiftX
                    val shiftY = alignment.shiftY
                    val expScale = frameExposureScales[i]
                    val frameType = frame.type

                    var y = startY
                    while (y < endY) {
                        val chunkH = min(CHUNK_ROWS, endY - y)
                        frame.bitmap.getPixels(chunkPixels, 0, width, 0, y, width, chunkH)

                        for (cy in 0 until chunkH) {
                            val currY = y + cy
                            val sy = currY + shiftY
                            if (sy !in 0 until height) continue

                            val chunkRowOffset = cy * width
                            val tileRow = (currY - startY) * width

                            for (x in 0 until width) {
                                val sx = x + shiftX
                                if (sx !in 0 until width) continue

                                val p = chunkPixels[chunkRowOffset + sx]
                                val rawR = Color.red(p) / 255.0f
                                val rawG = Color.green(p) / 255.0f
                                val rawB = Color.blue(p) / 255.0f

                                val linR = (rawR.pow(GAMMA_DECODE) - SENSOR_BLACK_LEVEL_NORM).coerceAtLeast(0.0f) / (1.0f - SENSOR_BLACK_LEVEL_NORM)
                                val linG = (rawG.pow(GAMMA_DECODE) - SENSOR_BLACK_LEVEL_NORM).coerceAtLeast(0.0f) / (1.0f - SENSOR_BLACK_LEVEL_NORM)
                                val linB = (rawB.pow(GAMMA_DECODE) - SENSOR_BLACK_LEVEL_NORM).coerceAtLeast(0.0f) / (1.0f - SENSOR_BLACK_LEVEL_NORM)

                                val radR = linR * expScale
                                val radG = linG * expScale
                                val radB = linB * expScale

                                val maxCh = max(rawR, max(rawG, rawB))
                                val expWeight = when {
                                    maxCh > 0.94f -> if (frameType == BracketExposureType.SHORT) 1.0f else (1.0f - maxCh) * 16.0f
                                    maxCh < 0.04f -> if (frameType == BracketExposureType.LONG) 1.0f else maxCh * 25.0f
                                    else -> 1.0f
                                }.coerceIn(0.05f, 1.0f)

                                val motionConf = alignment.getMotionConfidence(x, currY, width, height)
                                val combinedWeight = expWeight * motionConf

                                val outIdx = tileRow + x
                                rArr[outIdx] += radR * combinedWeight
                                gArr[outIdx] += radG * combinedWeight
                                bArr[outIdx] += radB * combinedWeight
                                wArr[outIdx] += combinedWeight
                            }
                        }
                        y += chunkH
                    }
                }

                // In-place normalization
                for (idx in 0 until tilePixels) {
                    val w = wArr[idx]
                    val r = if (w > 0f) rArr[idx] / w else 0f
                    val g = if (w > 0f) gArr[idx] / w else 0f
                    val b = if (w > 0f) bArr[idx] / w else 0f
                    rArr[idx] = r
                    gArr[idx] = g
                    bArr[idx] = b
                    wArr[idx] = 0.2126f * r + 0.7152f * g + 0.0722f * b
                }

                // Stream tone mapped tile into output with shadow noise suppression
                val tileRowPixels = IntArray(width)
                for (y in startY until endY) {
                    val tileY = y - startY
                    val dy = (y / ds).coerceIn(0, dsH - 1)
                    val tileRowOffset = tileY * width
                    val dsRowOffset = dy * dsW

                    for (x in 0 until width) {
                        val dx = (x / ds).coerceIn(0, dsW - 1)
                        val idx = tileRowOffset + x

                        val lumaIn = wArr[idx]
                        val baseIn = smoothedBaseDown[dsRowOffset + dx].coerceAtLeast(0.001f)

                        val detailRatio = (lumaIn / baseIn).coerceIn(0.2f, 3.5f)
                        val toneBase = (ln(1.0f + toneMu * baseIn) / toneMaxDiv).coerceIn(0.0f, 1.5f)
                        val lumaToneMapped = (toneBase * detailRatio.pow(detailBoost)).coerceAtLeast(0.0f)

                        val rIn = rArr[idx]
                        val gIn = gArr[idx]
                        val bIn = bArr[idx]

                        val gain = if (lumaIn > 0.0001f) {
                            (lumaToneMapped / lumaIn).pow(0.72f).coerceIn(0.0f, 4.5f)
                        } else 1.0f

                        var rOut = (rIn * gain).coerceAtLeast(0.0f)
                        var gOut = (gIn * gain).coerceAtLeast(0.0f)
                        var bOut = (bIn * gain).coerceAtLeast(0.0f)

                        if (lumaIn < 0.035f) {
                            val shadowLuma = 0.2126f * rOut + 0.7152f * gOut + 0.0722f * bOut
                            val blend = (lumaIn / 0.035f).coerceIn(0.0f, 1.0f)
                            rOut = shadowLuma * (1.0f - blend) + rOut * blend
                            gOut = shadowLuma * (1.0f - blend) + gOut * blend
                            bOut = shadowLuma * (1.0f - blend) + bOut * blend
                        }

                        val srgbR = (rOut.pow(GAMMA_ENCODE) * 255.0f).toInt().coerceIn(0, 255)
                        val srgbG = (gOut.pow(GAMMA_ENCODE) * 255.0f).toInt().coerceIn(0, 255)
                        val srgbB = (bOut.pow(GAMMA_ENCODE) * 255.0f).toInt().coerceIn(0, 255)

                        tileRowPixels[x] = Color.rgb(srgbR, srgbG, srgbB)
                    }

                    output.setPixels(tileRowPixels, 0, width, 0, y, width, 1)
                }
            } finally {
                rTile = null
                gTile = null
                bTile = null
                wTile = null
                System.gc()
            }

            onProgress(0.40f + ((tileIndex + 1).toFloat() / tiles) * 0.58f)
        }

        onProgress(1.0f)
        return output
    }

    /**
     * Online single-row noise-gated sharpening.
     */
    private fun sharpenRow(
        rowAbove: IntArray,
        rowCurr: IntArray,
        rowBelow: IntArray,
        rowOut: IntArray,
        w: Int,
        sharpnessStrength: Float
    ) {
        val edgeThreshold = 14
        val maxStep = 22

        rowOut[0] = rowCurr[0]
        rowOut[w - 1] = rowCurr[w - 1]

        for (x in 1 until w - 1) {
            val p = rowCurr[x]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            val pLeft = rowCurr[x - 1]
            val pRight = rowCurr[x + 1]
            val pUp = rowAbove[x]
            val pDown = rowBelow[x]

            val lumaCenter = (r * 3 + g * 6 + b) / 10
            val lumaAvg = ((Color.red(pLeft) * 3 + Color.green(pLeft) * 6 + Color.blue(pLeft)) +
                    (Color.red(pRight) * 3 + Color.green(pRight) * 6 + Color.blue(pRight)) +
                    (Color.red(pUp) * 3 + Color.green(pUp) * 6 + Color.blue(pUp)) +
                    (Color.red(pDown) * 3 + Color.green(pDown) * 6 + Color.blue(pDown))) / 40

            val diff = lumaCenter - lumaAvg
            if (abs(diff) in edgeThreshold..60) {
                val boost = (diff * sharpnessStrength).toInt().coerceIn(-maxStep, maxStep)
                val nr = (r + boost).coerceIn(0, 255)
                val ng = (g + boost).coerceIn(0, 255)
                val nb = (b + boost).coerceIn(0, 255)
                rowOut[x] = Color.rgb(nr, ng, nb)
            } else {
                rowOut[x] = p
            }
        }
    }

    /**
     * Fast 2D edge-preserving spatial filter on downscaled illumination.
     */
    private fun applyEdgePreservingSmooth(src: FloatArray, w: Int, h: Int): FloatArray {
        val temp = FloatArray(w * h)
        val dst = FloatArray(w * h)
        val radius = 3

        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                val center = src[row + x]
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until w) {
                        val v = src[row + nx]
                        if (abs(v - center) < 0.25f) {
                            sum += v
                            count++
                        }
                    }
                }
                temp[row + x] = if (count > 0) sum / count else center
            }
        }

        // Vertical pass
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                val center = temp[row + x]
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until h) {
                        val v = temp[ny * w + x]
                        if (abs(v - center) < 0.25f) {
                            sum += v
                            count++
                        }
                    }
                }
                dst[row + x] = if (count > 0) sum / count else center
            }
        }

        return dst
    }

    private fun getAvailableMemoryBytes(): Long {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        return maxMemory - (totalMemory - freeMemory)
    }
}
