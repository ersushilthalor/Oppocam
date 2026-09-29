package com.cinedepth.pro.ui.blur

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.cinedepth.pro.ui.BlurPreviewParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.media.ExifInterface
import java.io.IOException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

sealed interface DepthBlurExportResult {
    data class Success(val uri: Uri) : DepthBlurExportResult
    data class Error(val message: String) : DepthBlurExportResult
}

data class DepthBlurRenderOutput(
    val sourceBitmap: Bitmap,
    val bitmap: Bitmap,
    val depthMapBitmap: Bitmap,
    val depthInferenceMs: Long = 0L,
    val shaderPassMs: Long = 0L,
    val totalRenderMs: Long = 0L
)

object DepthBlurEngine {

    private var cachedEstimator: DepthEstimator? = null
    private var cachedSegmenter: PortraitSegmenter? = null
    private var injectedDepth: Bitmap? = null
    private var modifiedDepth: Bitmap? = null
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    var lastDepthInferenceMs: Long = 0L; private set
    var lastShaderPassMs: Long = 0L; private set
    var lastTotalRenderMs: Long = 0L; private set

    var cachedPreviewUriString: String? = null; private set
    var cachedPreviewHighQualityDepth: Boolean = false; private set
    var cachedPreviewUsesInjectedDepth: Boolean = false; private set
    var cachedPreviewSource: Bitmap? = null; private set
    var cachedPreviewRendered: Bitmap? = null; private set
    var cachedPreviewDepth: Bitmap? = null; private set

    fun setModifiedDepth(bitmap: Bitmap?) {
        if (modifiedDepth !== bitmap) {
            modifiedDepth?.recycle()
        }
        modifiedDepth = bitmap
    }

    fun setInjectedDepth(bitmap: Bitmap?) {
        if (injectedDepth !== bitmap) {
            injectedDepth?.recycle()
        }
        injectedDepth = bitmap
    }

    fun getModifiedDepth(): Bitmap? = modifiedDepth

    fun getInjectedDepth(): Bitmap? = injectedDepth

    fun getPreferredDepthOverride(): Bitmap? = modifiedDepth ?: injectedDepth

    fun clearInjectedDepth() {
        injectedDepth?.recycle()
        injectedDepth = null
    }

    fun clearPreviewCache() {
        cachedPreviewSource?.recycle()
        cachedPreviewSource = null
        cachedPreviewRendered?.recycle()
        cachedPreviewRendered = null
        cachedPreviewDepth?.recycle()
        cachedPreviewDepth = null
        cachedPreviewUriString = null
        cachedPreviewHighQualityDepth = false
        cachedPreviewUsesInjectedDepth = false
    }

    fun clearTransientState() {
        injectedDepth?.recycle()
        injectedDepth = null
        modifiedDepth?.recycle()
        modifiedDepth = null

        clearPreviewCache()
    }

    fun getEstimator(context: Context): DepthEstimator {
        if (cachedEstimator == null) {
            cachedEstimator = DepthEstimator(context.applicationContext)
        }
        return cachedEstimator!!
    }

    fun getSegmenter(): PortraitSegmenter {
        if (cachedSegmenter == null) {
            cachedSegmenter = PortraitSegmenter()
        }
        return cachedSegmenter!!
    }

    suspend fun renderPreview(
        context: Context,
        uri: Uri,
        params: BlurPreviewParams,
        maxDimension: Int = 480,
        overrideDepth: Bitmap? = null,
        highQualityDepth: Boolean = false,
        usesInjectedDepth: Boolean = false,
        updateCache: Boolean = true
    ): DepthBlurRenderOutput = withContext(Dispatchers.Default) {
        val source = decodeScaledBitmap(context, uri, maxDimension)
        val estimator = getEstimator(context)
        val result = renderDepthAware(
            source,
            params,
            estimator,
            overrideDepth = overrideDepth,
            fastContourMatte = !highQualityDepth
        )
        if (updateCache) {
            updatePreviewCache(uri.toString(), result, highQualityDepth, usesInjectedDepth)
        }
        result
    }

    suspend fun exportBlurredImage(
        context: Context,
        uri: Uri,
        params: BlurPreviewParams,
        maxDimension: Int = 1280,
        overrideDepth: Bitmap? = null,
        highQualityDepth: Boolean = false
    ): DepthBlurExportResult = withContext(Dispatchers.IO) {
        try {
            val source = decodeScaledBitmap(context, uri, maxDimension)
            val estimator = getEstimator(context)
            val rendered = renderDepthAware(
                source,
                params,
                estimator,
                overrideDepth = overrideDepth,
                fastContourMatte = !highQualityDepth
            )
            val savedUri = saveBitmapToGallery(context, rendered.bitmap)
            copyExifData(context, sourceUri = uri, destUri = savedUri)
            rendered.sourceBitmap.recycle()
            rendered.depthMapBitmap.recycle()
            rendered.bitmap.recycle()
            DepthBlurExportResult.Success(savedUri)
        } catch (t: Throwable) {
            DepthBlurExportResult.Error(t.message ?: "Unknown save error")
        }
    }

    suspend fun rerenderPreviewFromCache(
        sourceBitmap: Bitmap,
        depthBitmap: Bitmap,
        params: BlurPreviewParams
    ): Bitmap = withContext(Dispatchers.Default) {
        val result = renderDepthAware(
            source = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false),
            params = params,
            overrideDepth = depthBitmap
        )
        result.sourceBitmap.recycle()
        result.depthMapBitmap.recycle()
        result.bitmap
    }

    suspend fun renderDepthAware(
        source: Bitmap,
        params: BlurPreviewParams,
        depthEstimator: DepthEstimator? = null,
        overrideDepth: Bitmap? = null,
        fastContourMatte: Boolean = false
    ): DepthBlurRenderOutput = withContext(Dispatchers.Default) {
        val totalStart = System.nanoTime()
        val src = ensureArgb8888(source)
        val width = src.width
        val height = src.height
        val sourceBitmap = src

        val depthStart = System.nanoTime()

        // Run depth estimation and portrait segmentation in PARALLEL
        val depthDeferred = async {
            if (overrideDepth != null) {
                val scaledDepth = if (overrideDepth.width != width || overrideDepth.height != height) {
                    Bitmap.createScaledBitmap(overrideDepth, width, height, true)
                } else {
                    overrideDepth
                }
                val floatData = extractDepthFromBitmap(scaledDepth)
                if (scaledDepth !== overrideDepth) scaledDepth.recycle()
                floatData
            } else if (depthEstimator != null && depthEstimator.isReady()) {
                val raw = depthEstimator.estimateDepth(sourceBitmap)
                if (raw != null) {
                    resizeDepthArray(raw, DepthEstimator.INPUT_IMAGE_SIZE, DepthEstimator.INPUT_IMAGE_SIZE, width, height)
                } else {
                    null
                }
            } else {
                null
            }
        }

        val segDeferred = async(Dispatchers.Default) {
            try {
                getSegmenter().segmentToBitmap(sourceBitmap, width, height)
            } catch (_: Exception) {
                // Segmentation is best-effort; return neutral gray (0.5) mask
                null
            }
        }

        val segMaskBitmap: Bitmap? = segDeferred.await()
        val estimatedDepth = depthDeferred.await()
        val rawDepth = estimatedDepth ?: deriveDepthFromSegMask(segMaskBitmap, width, height)

        val depthInferenceMs = (System.nanoTime() - depthStart) / 1_000_000L
        val shaderStart = System.nanoTime()

        val lensEffect = params.lensEffect
        val focus = (params.focusDepth.coerceIn(0f, 255f) / 255f)
        val blurScale = params.blurStrength.coerceIn(0f, 1f)
        val transition = params.blurFalloff.coerceIn(0f, 1f)
        val falloffWidth = 0.035f + transition * 0.72f
        val edgeSoftness = params.edgeSoftness.coerceIn(0f, 1f)
        val focusDeadZone = 0.012f + edgeSoftness * 0.030f + transition * 0.030f

        val depthMapBitmap = depthToBitmap(rawDepth, width, height)

        val output: Bitmap
        val refinedDepthBitmap: Bitmap

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Pass 1: Refine Depth on GPU
            val refineShader = android.graphics.RuntimeShader(REFINEMENT_SHADER_SRC)
            refineShader.setInputShader("image", android.graphics.BitmapShader(sourceBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            refineShader.setInputShader("depthMap", android.graphics.BitmapShader(depthMapBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            refineShader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            refineShader.setFloatUniform("edgeRefine", params.edgeRefine.coerceIn(0f, 1f))
            
            val refinePaint = android.graphics.Paint()
            refinePaint.shader = refineShader
            refinedDepthBitmap = renderShaderToBitmap(width, height, refinePaint)
            // Create a neutral seg mask fallback (0.5 = no opinion) if segmentation failed
            val hasSegmentation = segMaskBitmap != null
            val effectiveSegMask = segMaskBitmap ?: createNeutralMask(width, height)

            val contourMatteBitmap = renderContourMatteBitmap(
                source = sourceBitmap,
                refinedDepthBitmap = refinedDepthBitmap,
                segMaskBitmap = effectiveSegMask,
                hasSegMask = hasSegmentation,
                focus = focus,
                focusDeadZone = focusDeadZone,
                falloffWidth = falloffWidth,
                edgeSoftness = edgeSoftness,
                edgeExpand = params.edgeExpand,
                edgeRefine = params.edgeRefine,
                maxWorkDimension = when {
                    fastContourMatte && max(width, height) >= 1600 -> 640
                    fastContourMatte && max(width, height) >= 1200 -> 720
                    fastContourMatte -> 768
                    max(width, height) >= 1800 -> 900
                    max(width, height) >= 1400 -> 960
                    max(width, height) >= 1100 -> 1024
                    else -> max(width, height)
                },
                fastMode = fastContourMatte
            )

            // Pass 2: Bokeh Blur on GPU using Refined Depth + Seg Mask
            val bokehShader = android.graphics.RuntimeShader(BOKEH_SHADER_SRC)
            bokehShader.setInputShader("image", android.graphics.BitmapShader(sourceBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            bokehShader.setInputShader("refinedDepthMap", android.graphics.BitmapShader(refinedDepthBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            bokehShader.setInputShader("contourMatte", createMappedBitmapShader(contourMatteBitmap, width, height))
            bokehShader.setInputShader("segMask", android.graphics.BitmapShader(effectiveSegMask, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            bokehShader.setFloatUniform("hasSegMask", if (hasSegmentation) 1.0f else 0.0f)
            bokehShader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            bokehShader.setFloatUniform("focus", focus)
            bokehShader.setFloatUniform("focusDeadZone", focusDeadZone)
            bokehShader.setFloatUniform("falloffWidth", falloffWidth)
            bokehShader.setFloatUniform("edgeExpand", params.edgeExpand)
            
            val effectRadiusScale = when (lensEffect) {
                com.cinedepth.pro.ui.LensEffect.Classic -> 0.76f
                com.cinedepth.pro.ui.LensEffect.Creamy -> 0.84f
                com.cinedepth.pro.ui.LensEffect.Bubble -> 0.68f
                com.cinedepth.pro.ui.LensEffect.Bloom -> 0.78f
                com.cinedepth.pro.ui.LensEffect.Star -> 0.62f
                com.cinedepth.pro.ui.LensEffect.Hexagon -> 0.72f
                com.cinedepth.pro.ui.LensEffect.Anamorphic -> 0.82f
            }
            // Cap the top end so strong blur still reads like lens defocus instead of a smeared filter.
            val maxRadius = (max(width, height) * 0.021f * blurScale * effectRadiusScale).coerceIn(0f, 118f)
            bokehShader.setFloatUniform("maxBlurRadius", maxRadius)
            
            val effectTypeInt = when (lensEffect) {
                com.cinedepth.pro.ui.LensEffect.Classic -> 0
                com.cinedepth.pro.ui.LensEffect.Creamy -> 1
                com.cinedepth.pro.ui.LensEffect.Bubble -> 2
                com.cinedepth.pro.ui.LensEffect.Bloom -> 3
                com.cinedepth.pro.ui.LensEffect.Star -> 4
                com.cinedepth.pro.ui.LensEffect.Hexagon -> 5
                com.cinedepth.pro.ui.LensEffect.Anamorphic -> 6
            }
            bokehShader.setIntUniform("lensEffectType", effectTypeInt)
            
            val highlightBoost = params.highlightBoost.coerceIn(0f, 1f) * (0.16f + params.flareStrength * 0.72f)
            bokehShader.setFloatUniform("highlightBoost", highlightBoost)
            bokehShader.setFloatUniform("vignetteStrength", params.vignetteStrength)
            
            // For 4K-ish exports, skip part of the spiral instead of pushing the full cost.
            val resScale = if (width > 2000) 2.5f else 1.0f
            bokehShader.setFloatUniform("highResScale", resScale)

            val bokehPaint = android.graphics.Paint()
            bokehPaint.shader = bokehShader
            
            output = renderShaderToBitmap(width, height, bokehPaint)
            contourMatteBitmap.recycle()
            effectiveSegMask.recycle()
        } else {
            // Fallback for older devices
            output = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
            refinedDepthBitmap = depthMapBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        // Aggressive recycling of intermediate buffers
        if (depthMapBitmap !== refinedDepthBitmap) depthMapBitmap.recycle()

        val shaderPassMs = (System.nanoTime() - shaderStart) / 1_000_000L
        val totalRenderMs = (System.nanoTime() - totalStart) / 1_000_000L

        DepthBlurRenderOutput(
            sourceBitmap = sourceBitmap,
            bitmap = output,
            depthMapBitmap = refinedDepthBitmap,
            depthInferenceMs = depthInferenceMs,
            shaderPassMs = shaderPassMs,
            totalRenderMs = totalRenderMs
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun renderShaderToBitmap(width: Int, height: Int, paint: android.graphics.Paint): Bitmap = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            cont.resumeWithException(IllegalStateException("HardwareRenderer requires API 29+"))
            return@suspendCancellableCoroutine
        }

        val imageReader = android.media.ImageReader.newInstance(
            width, height,
            android.graphics.PixelFormat.RGBA_8888,
            1,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )

        val hwRenderer = android.graphics.HardwareRenderer()
        hwRenderer.setSurface(imageReader.surface)

        cont.invokeOnCancellation {
            hwRenderer.destroy()
            imageReader.close()
        }

        val renderNode = android.graphics.RenderNode("agsl_blur")
        renderNode.setPosition(0, 0, width, height)
        val canvas = renderNode.beginRecording()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        renderNode.endRecording()

        hwRenderer.setContentRoot(renderNode)

        imageReader.setOnImageAvailableListener({ reader ->
            try {
                if (cont.isCancelled) return@setOnImageAvailableListener
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val hwBuffer = image.hardwareBuffer
                    if (hwBuffer != null) {
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                        val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap?.recycle()
                        hwBuffer.close()
                        image.close()

                        hwRenderer.destroy()
                        reader.close()

                        if (swBitmap != null) {
                            if (cont.isActive) cont.resume(swBitmap) { swBitmap.recycle() }
                        } else {
                            if (cont.isActive) cont.resumeWithException(RuntimeException("Failed to copy hardware bitmap"))
                        }
                    } else {
                        image.close()
                        hwRenderer.destroy()
                        reader.close()
                        if (cont.isActive) cont.resumeWithException(RuntimeException("HardwareBuffer is null"))
                    }
                } else {
                    hwRenderer.destroy()
                    reader.close()
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Image is null"))
                }
            } catch (e: Exception) {
                hwRenderer.destroy()
                reader.close()
                if (cont.isActive) cont.resumeWithException(e)
            }
        }, mainHandler)

        hwRenderer.createRenderRequest().syncAndDraw()
    }

    /**
     * Refines the low-resolution depth map by snapping its edges to the high-resolution
     * source image using a fast joint bilateral refinement.
     */
    private suspend fun refineDepthWithOriginal(
        depth: FloatArray,
        srcPixels: IntArray,
        width: Int,
        height: Int
    ): FloatArray = withContext(Dispatchers.Default) {
        val output = FloatArray(width * height)
        val radius = 2
        val rangeSigma = 0.12f 
        val rangeSigmaSq = 2f * rangeSigma * rangeSigma
        
        // Split the work into chunks for parallel execution
        val numCores = Runtime.getRuntime().availableProcessors()
        val chunkSize = (height + numCores - 1) / numCores

        val jobs = (0 until numCores).map { coreIdx ->
            async {
                val startY = coreIdx * chunkSize
                val endY = min((coreIdx + 1) * chunkSize, height)
                
                for (y in startY until endY) {
                    val rowOffset = y * width
                    for (x in 0 until width) {
                        val idx = rowOffset + x
                        val centerPixel = srcPixels[idx]
                        
                        // Inline luma for performance: (0.299R + 0.587G + 0.114B) / 255
                        val cr = (centerPixel shr 16) and 0xFF
                        val cg = (centerPixel shr 8) and 0xFF
                        val cb = centerPixel and 0xFF
                        val centerLuma = (0.299f * cr + 0.587f * cg + 0.114f * cb) / 255f

                        var weightedSum = 0f
                        var totalWeight = 0f

                        for (dy in -radius..radius) {
                            val ny = (y + dy).coerceIn(0, height - 1)
                            val nRowOffset = ny * width
                            for (dx in -radius..radius) {
                                val nx = (x + dx).coerceIn(0, width - 1)
                                val nIdx = nRowOffset + nx
                                
                                val nPixel = srcPixels[nIdx]
                                val nr = (nPixel shr 16) and 0xFF
                                val ng = (nPixel shr 8) and 0xFF
                                val nb = nPixel and 0xFF
                                val sampleLuma = (0.299f * nr + 0.587f * ng + 0.114f * nb) / 255f
                                
                                val lumaDiff = sampleLuma - centerLuma
                                val weight = exp(-(lumaDiff * lumaDiff) / rangeSigmaSq)
                                
                                weightedSum += depth[nIdx] * weight
                                totalWeight += weight
                            }
                        }
                        
                        output[idx] = if (totalWeight > 0f) weightedSum / totalWeight else depth[idx]
                    }
                }
            }
        }
        jobs.awaitAll()
        output
    }

    private fun extractDepthFromBitmap(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val depth = FloatArray(w * h)
        for (i in pixels.indices) {
            depth[i] = android.graphics.Color.red(pixels[i]) / 255f
        }
        return depth
    }

    fun findSimilarDepthRegion(
        depth: FloatArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        threshold: Float
    ): BooleanArray {
        val mask = BooleanArray(width * height)
        val stack = mutableListOf<Pair<Int, Int>>()

        val seedIdx = startY * width + startX
        if (seedIdx !in depth.indices) return mask

        var seedDepthSum = 0f
        var seedDepthCount = 0
        var seedDepthMin = 1f
        var seedDepthMax = 0f
        for (sampleY in (startY - 1)..(startY + 1)) {
            for (sampleX in (startX - 1)..(startX + 1)) {
                if (sampleX in 0 until width && sampleY in 0 until height) {
                    val sampleDepth = depth[sampleY * width + sampleX]
                    seedDepthSum += sampleDepth
                    seedDepthCount += 1
                    seedDepthMin = minOf(seedDepthMin, sampleDepth)
                    seedDepthMax = maxOf(seedDepthMax, sampleDepth)
                }
            }
        }
        val seedDepth = if (seedDepthCount > 0) seedDepthSum / seedDepthCount else depth[seedIdx]
        val baseThreshold = threshold.coerceAtLeast(0.02f)
        val neighborThreshold = (baseThreshold * 0.22f).coerceAtLeast(0.01f)
        val seedRangePadding = (baseThreshold * 0.6f).coerceAtLeast(0.015f)
        val allowedDepthMin = seedDepthMin - seedRangePadding
        val allowedDepthMax = seedDepthMax + seedRangePadding
        stack.add(startX to startY)
        mask[seedIdx] = true

        val dx = intArrayOf(0, 0, 1, -1, 1, 1, -1, -1)
        val dy = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1)

        while (stack.isNotEmpty()) {
            val (cx, cy) = stack.removeAt(stack.size - 1)
            val currentDepth = depth[cy * width + cx]

            for (i in dx.indices) {
                val nx = cx + dx[i]
                val ny = cy + dy[i]
                if (nx in 0 until width && ny in 0 until height) {
                    val nIdx = ny * width + nx
                    val neighborDepth = depth[nIdx]
                    val matchesBand = neighborDepth in allowedDepthMin..allowedDepthMax
                    val matchesSeed = abs(neighborDepth - seedDepth) <= baseThreshold
                    val matchesCurrent = abs(neighborDepth - currentDepth) <= neighborThreshold
                    if (!mask[nIdx] && matchesBand && (matchesSeed || matchesCurrent)) {
                        mask[nIdx] = true
                        stack.add(nx to ny)
                    }
                }
            }
        }
        return mask
    }

    fun maskToBitmap(mask: BooleanArray, width: Int, height: Int, color: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (i in mask.indices) {
            pixels[i] = if (mask[i]) color else 0
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        return if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else if (!bitmap.isMutable) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
    }

    private fun updatePreviewCache(
        uriString: String,
        output: DepthBlurRenderOutput,
        highQualityDepth: Boolean,
        usesInjectedDepth: Boolean
    ) {
        cachedPreviewSource?.recycle()
        cachedPreviewRendered?.recycle()
        cachedPreviewDepth?.recycle()
        cachedPreviewUriString = uriString
        cachedPreviewHighQualityDepth = highQualityDepth
        cachedPreviewUsesInjectedDepth = usesInjectedDepth
        cachedPreviewSource = output.sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        cachedPreviewRendered = output.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        cachedPreviewDepth = output.depthMapBitmap.copy(Bitmap.Config.ARGB_8888, false)
        lastDepthInferenceMs = output.depthInferenceMs
        lastShaderPassMs = output.shaderPassMs
        lastTotalRenderMs = output.totalRenderMs
    }

    private fun fillForegroundForBlur(
        pixels: IntArray,
        fgMask: FloatArray,
        imgW: Int,
        imgH: Int
    ) {
        val filled = BooleanArray(imgW * imgH) { fgMask[it] < 0.5f }
        val queue = ArrayDeque<Int>(imgW * 2)

        for (i in pixels.indices) {
            if (!filled[i]) continue
            val x = i % imgW
            val y = i / imgW
            if ((x > 0 && !filled[i - 1]) || (x < imgW - 1 && !filled[i + 1]) ||
                (y > 0 && !filled[i - imgW]) || (y < imgH - 1 && !filled[i + imgW])
            ) {
                queue.addLast(i)
            }
        }

        val dx = intArrayOf(-1, 1, 0, 0)
        val dy = intArrayOf(0, 0, -1, 1)
        while (queue.isNotEmpty()) {
            val ci = queue.removeFirst()
            val cx = ci % imgW
            val cy = ci / imgW
            for (d in 0 until 4) {
                val nx = cx + dx[d]
                val ny = cy + dy[d]
                if (nx in 0 until imgW && ny in 0 until imgH) {
                    val ni = ny * imgW + nx
                    if (!filled[ni]) {
                        var rSum = 0
                        var gSum = 0
                        var bSum = 0
                        var count = 0
                        for (dd in 0 until 4) {
                            val nnx = nx + dx[dd]
                            val nny = ny + dy[dd]
                            if (nnx in 0 until imgW && nny in 0 until imgH) {
                                val nni = nny * imgW + nnx
                                if (filled[nni]) {
                                    val c = pixels[nni]
                                    rSum += (c shr 16) and 0xFF
                                    gSum += (c shr 8) and 0xFF
                                    bSum += c and 0xFF
                                    count++
                                }
                            }
                        }
                        if (count > 0) {
                            pixels[ni] = (0xFF shl 24) or
                                ((rSum / count) shl 16) or
                                ((gSum / count) shl 8) or
                                (bSum / count)
                        }
                        filled[ni] = true
                        queue.addLast(ni)
                    }
                }
            }
        }
    }

    private suspend fun resizeDepthArray(
        src: FloatArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): FloatArray = withContext(Dispatchers.Default) {
        val dst = FloatArray(dstW * dstH)
        val numCores = Runtime.getRuntime().availableProcessors()
        val chunkSize = (dstH + numCores - 1) / numCores

        val jobs = (0 until numCores).map { coreIdx ->
            async {
                val startY = coreIdx * chunkSize
                val endY = min((coreIdx + 1) * chunkSize, dstH)
                
                for (y in startY until endY) {
                    val srcY = (y.toFloat() / dstH) * srcH
                    val y0 = srcY.toInt().coerceIn(0, srcH - 1)
                    val y1 = min(y0 + 1, srcH - 1)
                    val dy = srcY - y0

                    val rowOffset = y * dstW
                    for (x in 0 until dstW) {
                        val srcX = (x.toFloat() / dstW) * srcW
                        val x0 = srcX.toInt().coerceIn(0, srcW - 1)
                        val x1 = min(x0 + 1, srcW - 1)
                        val dx = srcX - x0

                        val v00 = src[y0 * srcW + x0]
                        val v10 = src[y0 * srcW + x1]
                        val v01 = src[y1 * srcW + x0]
                        val v11 = src[y1 * srcW + x1]

                        val v0 = v00 + (v10 - v00) * dx
                        val v1 = v01 + (v11 - v01) * dx
                        dst[rowOffset + x] = v0 + (v1 - v0) * dy
                    }
                }
            }
        }
        jobs.awaitAll()
        dst
    }

    private fun depthToBitmap(depth: FloatArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (i in depth.indices) {
            val v = (depth[i] * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private suspend fun renderContourMatteBitmap(
        source: Bitmap,
        refinedDepthBitmap: Bitmap,
        segMaskBitmap: Bitmap,
        hasSegMask: Boolean,
        focus: Float,
        focusDeadZone: Float,
        falloffWidth: Float,
        edgeSoftness: Float,
        edgeExpand: Float,
        edgeRefine: Float,
        maxWorkDimension: Int,
        fastMode: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = source.width
        val height = source.height
        val workScale = min(1f, maxWorkDimension.toFloat() / max(width, height).toFloat())
        val workSource = if (workScale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                max(1, (width * workScale).toInt()),
                max(1, (height * workScale).toInt()),
                true
            )
        } else {
            source
        }
        val workDepth = if (workScale < 1f) {
            Bitmap.createScaledBitmap(
                refinedDepthBitmap,
                workSource.width,
                workSource.height,
                true
            )
        } else {
            refinedDepthBitmap
        }
        val workSeg = if (workScale < 1f) {
            Bitmap.createScaledBitmap(
                segMaskBitmap,
                workSource.width,
                workSource.height,
                true
            )
        } else {
            segMaskBitmap
        }
        val workWidth = workSource.width
        val workHeight = workSource.height
        try {
            val matteShader = android.graphics.RuntimeShader(CONTOUR_MATTE_SHADER_SRC)
            matteShader.setInputShader(
                "image",
                android.graphics.BitmapShader(
                    workSource,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP
                )
            )
            matteShader.setInputShader(
                "refinedDepthMap",
                android.graphics.BitmapShader(
                    workDepth,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP
                )
            )
            matteShader.setInputShader(
                "segMask",
                android.graphics.BitmapShader(
                    workSeg,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP
                )
            )
            matteShader.setFloatUniform("hasSegMask", if (hasSegMask) 1.0f else 0.0f)
            matteShader.setFloatUniform("resolution", workWidth.toFloat(), workHeight.toFloat())
            matteShader.setFloatUniform("focus", focus)
            matteShader.setFloatUniform("focusDeadZone", focusDeadZone)
            matteShader.setFloatUniform("falloffWidth", falloffWidth)
            matteShader.setFloatUniform("edgeSoftness", edgeSoftness)
            matteShader.setFloatUniform("edgeExpand", edgeExpand)
            matteShader.setFloatUniform("quality", if (fastMode) 0.35f else 1.0f)
            matteShader.setFloatUniform("refineStrength", edgeRefine.coerceIn(0f, 1f))

            val mattePaint = android.graphics.Paint()
            mattePaint.shader = matteShader
            renderShaderToBitmap(workWidth, workHeight, mattePaint)
        } catch (_: Throwable) {
            buildFallbackContourMatteBitmap(
                depthBitmap = workDepth,
                focus = focus,
                focusDeadZone = focusDeadZone,
                falloffWidth = falloffWidth,
                edgeSoftness = edgeSoftness,
                edgeExpand = edgeExpand
            )
        }.also { matteBitmap ->
            if (workSource !== source) workSource.recycle()
            if (workDepth !== refinedDepthBitmap) workDepth.recycle()
            if (workSeg !== segMaskBitmap) workSeg.recycle()
            return@withContext matteBitmap
        }
    }

    private fun createNeutralMask(width: Int, height: Int): Bitmap {
        // Neutral mask: 0.0 everywhere (no person detected)
        val pixels = IntArray(width * height)
        val v = 0
        val pixel = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        pixels.fill(pixel)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun createMappedBitmapShader(
        bitmap: Bitmap,
        outputWidth: Int,
        outputHeight: Int
    ): android.graphics.BitmapShader {
        val shader = android.graphics.BitmapShader(
            bitmap,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP
        )
        if (bitmap.width != outputWidth || bitmap.height != outputHeight) {
            val matrix = android.graphics.Matrix()
            matrix.setScale(
                bitmap.width.toFloat() / outputWidth.toFloat(),
                bitmap.height.toFloat() / outputHeight.toFloat()
            )
            shader.setLocalMatrix(matrix)
        }
        return shader
    }

    private fun buildFallbackContourMatteBitmap(
        depthBitmap: Bitmap,
        focus: Float,
        focusDeadZone: Float,
        falloffWidth: Float,
        edgeSoftness: Float,
        edgeExpand: Float
    ): Bitmap {
        val width = depthBitmap.width
        val height = depthBitmap.height
        val pixels = IntArray(width * height)
        depthBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = IntArray(width * height)
        val transitionStart = max(0f, focusDeadZone * 0.55f - edgeExpand * 0.02f)
        val transitionEnd = focusDeadZone + falloffWidth * (0.28f + edgeSoftness * 0.22f) + edgeExpand * 0.035f

        for (i in pixels.indices) {
            val depth = ((pixels[i] shr 16) and 0xFF) / 255f
            val dist = abs(depth - focus)
            val alpha = 1f - smoothstep(transitionStart, transitionEnd, dist)
            val value = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }

        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    fun decodeScaledBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, maxDimension.toFloat() / max(w, h).toFloat())
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            }
        }
    }

    private val EXIF_TAGS_TO_COPY = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_SOFTWARE
    )

    private fun copyExifData(context: Context, sourceUri: Uri, destUri: Uri) {
        try {
            val srcExif = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                ExifInterface(input)
            } ?: return

            val destFd = context.contentResolver.openFileDescriptor(destUri, "rw") ?: return
            destFd.use { fd ->
                val destExif = ExifInterface(fd.fileDescriptor)
                for (tag in EXIF_TAGS_TO_COPY) {
                    val value = srcExif.getAttribute(tag)
                    if (value != null) {
                        destExif.setAttribute(tag, value)
                    }
                }
                destExif.setAttribute(ExifInterface.TAG_SOFTWARE, "CineDepth Pro")
                destExif.saveAttributes()
            }
        } catch (_: Exception) {
            // EXIF copy is best-effort; don't fail the save
        }
    }

    private fun deriveDepthFromSegMask(segBitmap: Bitmap?, width: Int, height: Int): FloatArray {
        val depth = FloatArray(width * height)
        if (segBitmap != null) {
            val pixels = IntArray(width * height)
            val w = segBitmap.width
            val h = segBitmap.height
            if (w == width && h == height) {
                segBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            } else {
                val scaled = Bitmap.createScaledBitmap(segBitmap, width, height, true)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                scaled.recycle()
            }
            for (i in pixels.indices) {
                val personConfidence = (pixels[i] and 0xFF) / 255f
                // Foreground person is near (0.25f depth), background falls off into distance (0.85f depth)
                depth[i] = 0.85f - (personConfidence * 0.60f)
            }
        } else {
            for (y in 0 until height) {
                val ny = (y.toFloat() / height - 0.5f) * 2f
                val row = y * width
                for (x in 0 until width) {
                    val nx = (x.toFloat() / width - 0.5f) * 2f
                    val dist = kotlin.math.sqrt(nx * nx + ny * ny).coerceIn(0f, 1f)
                    depth[row + x] = 0.35f + dist * 0.50f
                }
            }
        }
        return depth
    }

    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        relativeFolder: String = "DCIM/Camera"
    ): Uri = withContext(Dispatchers.IO) {
        val filename = "CineDepth_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create MediaStore entry")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                    throw IOException("Failed to compress bitmap")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, ready, null, null)
            }
            return@withContext uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
