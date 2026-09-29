package com.example.camera.depth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

data class DepthInferenceResult(
    val depthMap: FloatArray, // Normalized [0.0 = far background, 1.0 = closest foreground]
    val width: Int,
    val height: Int,
    val modelType: DepthModelType,
    val inferenceTimeMs: Long,
    val activeDelegateName: String
)

/**
 * Hardware-accelerated AI Depth Inference Engine supporting both TFLite and ONNX Runtime models
 * (Depth Anything V2 & MediaSWLF-I / MiDaS v2.1).
 *
 * Inspects model input/output tensor shapes dynamically (NHWC or NCHW, Float32 or UInt8)
 * and normalizes raw inverse-depth predictions with robust 2nd-98th percentile scaling.
 */
class DepthInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "DepthInferenceEngine"
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private val modelManager = DepthModelManager.getInstance(context)
    private val mutex = Mutex()

    private var cachedFilePath: String? = null
    private var cachedBackend: DepthHardwareBackend? = null
    private var tfliteInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var ortSession: OrtSession? = null
    private var activeDelegateLabel: String = "CPU (XNNPACK)"

    fun hasVerifiedInstalledModel(): Boolean {
        return modelManager.getActiveInstalledModelFile() != null
    }

    suspend fun estimateDepth(
        sourceBitmap: Bitmap,
        targetWidth: Int = sourceBitmap.width,
        targetHeight: Int = sourceBitmap.height
    ): DepthInferenceResult? = withContext(Dispatchers.Default) {
        val activePair = modelManager.getActiveInstalledModelFile() ?: return@withContext null
        val (modelType, modelFile) = activePair
        val backend = modelManager.hardwareBackend.value

        mutex.withLock {
            try {
                val startMs = android.os.SystemClock.elapsedRealtime()
                ensureSessionLoaded(modelFile, backend)

                val rawPred: Pair<FloatArray, Pair<Int, Int>> = when {
                    tfliteInterpreter != null -> runTfliteInference(tfliteInterpreter!!, sourceBitmap)
                    ortSession != null -> runOnnxInference(ortSession!!, sourceBitmap)
                    else -> return@withContext null
                }

                val (rawDepth, dims) = rawPred
                val (outW, outH) = dims

                // Normalize raw disparity/inverse-depth using 2nd..98th percentile to prevent outlier spikes
                val normalizedSmall = normalizeDisparityMap(rawDepth, outW, outH)

                val finalDepth = if (outW == targetWidth && outH == targetHeight) {
                    normalizedSmall
                } else {
                    upsampleBilinear(normalizedSmall, outW, outH, targetWidth, targetHeight)
                }

                val elapsedMs = (android.os.SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                DepthInferenceResult(
                    depthMap = finalDepth,
                    width = targetWidth,
                    height = targetHeight,
                    modelType = modelType,
                    inferenceTimeMs = elapsedMs,
                    activeDelegateName = activeDelegateLabel
                )
            } catch (t: Throwable) {
                Log.e(TAG, "AI depth inference failed for ${modelType.displayName}", t)
                releaseSessionInternal()
                null
            }
        }
    }

    private fun ensureSessionLoaded(modelFile: File, backend: DepthHardwareBackend) {
        if (cachedFilePath == modelFile.absolutePath &&
            cachedBackend == backend &&
            (tfliteInterpreter != null || ortSession != null)
        ) {
            return
        }

        releaseSessionInternal()

        val isTflite = try {
            RandomAccessFile(modelFile, "r").use { raf ->
                val header = ByteArray(8)
                if (raf.read(header) == 8) {
                    String(header, 4, 4, Charsets.US_ASCII) == "TFL3"
                } else false
            }
        } catch (e: Exception) {
            false
        }

        if (isTflite) {
            loadTfliteInterpreter(modelFile, backend)
        } else {
            loadOnnxSession(modelFile, backend)
        }

        cachedFilePath = modelFile.absolutePath
        cachedBackend = backend
    }

    private fun loadTfliteInterpreter(modelFile: File, backend: DepthHardwareBackend) {
        val mapped = FileInputStream(modelFile).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length()).apply {
                order(ByteOrder.nativeOrder())
            }
        }

        // Try requested hardware acceleration first, then gracefully fallback to CPU XNNPACK
        if (backend == DepthHardwareBackend.AUTO || backend == DepthHardwareBackend.GPU) {
            try {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                    val options = Interpreter.Options().apply {
                        addDelegate(delegate)
                        setNumThreads(4)
                    }
                    tfliteInterpreter = Interpreter(mapped, options)
                    gpuDelegate = delegate
                    activeDelegateLabel = "TFLite GPU Delegate"
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "GPU delegate init failed, falling back", t)
                try { gpuDelegate?.close() } catch (_: Exception) {}
                gpuDelegate = null
            }
        }

        if (backend == DepthHardwareBackend.AUTO || backend == DepthHardwareBackend.NNAPI) {
            try {
                val options = Interpreter.Options().apply {
                    setUseNNAPI(true)
                    setNumThreads(4)
                }
                mapped.rewind()
                tfliteInterpreter = Interpreter(mapped, options)
                activeDelegateLabel = "TFLite NNAPI / NPU"
                return
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI delegate init failed, falling back to CPU", t)
            }
        }

        mapped.rewind()
        val cpuOptions = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        tfliteInterpreter = Interpreter(mapped, cpuOptions)
        activeDelegateLabel = "TFLite CPU (XNNPACK 4T)"
    }

    private fun loadOnnxSession(modelFile: File, backend: DepthHardwareBackend) {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            if (backend == DepthHardwareBackend.NNAPI || backend == DepthHardwareBackend.AUTO) {
                try {
                    addNnapi()
                    activeDelegateLabel = "ONNX NNAPI + CPU"
                } catch (_: Throwable) {
                    activeDelegateLabel = "ONNX CPU (4T)"
                }
            } else {
                activeDelegateLabel = "ONNX CPU (4T)"
            }
        }
        ortSession = env.createSession(modelFile.absolutePath, opts)
    }

    private fun runTfliteInference(
        interpreter: Interpreter,
        bitmap: Bitmap
    ): Pair<FloatArray, Pair<Int, Int>> {
        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape() // e.g. [1, 518, 518, 3] or [1, 3, 256, 256]
        val inType = inTensor.dataType()

        val isNchw = inShape.size == 4 && inShape[1] == 3
        val inH = if (isNchw) inShape[2] else inShape[1]
        val inW = if (isNchw) inShape[3] else inShape[2]

        val scaledBmp = if (bitmap.width == inW && bitmap.height == inH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inW, inH, true)
        }

        val pixels = IntArray(inW * inH)
        scaledBmp.getPixels(pixels, 0, inW, 0, 0, inW, inH)
        if (scaledBmp != bitmap && !scaledBmp.isRecycled) {
            scaledBmp.recycle()
        }

        val inputBuffer: ByteBuffer = if (inType == DataType.UINT8) {
            val buf = ByteBuffer.allocateDirect(inW * inH * 3).order(ByteOrder.nativeOrder())
            if (isNchw) {
                for (c in 0..2) {
                    val shift = (2 - c) * 8
                    for (i in 0 until inW * inH) {
                        buf.put(((pixels[i] shr shift) and 0xFF).toByte())
                    }
                }
            } else {
                for (i in 0 until inW * inH) {
                    val p = pixels[i]
                    buf.put(((p shr 16) and 0xFF).toByte())
                    buf.put(((p shr 8) and 0xFF).toByte())
                    buf.put((p and 0xFF).toByte())
                }
            }
            buf.rewind()
            buf
        } else {
            val buf = ByteBuffer.allocateDirect(inW * inH * 3 * 4).order(ByteOrder.nativeOrder())
            val floatBuf = buf.asFloatBuffer()
            if (isNchw) {
                val planeSize = inW * inH
                for (i in 0 until planeSize) {
                    val p = pixels[i]
                    val r = ((p shr 16) and 0xFF) / 255f
                    val g = ((p shr 8) and 0xFF) / 255f
                    val b = (p and 0xFF) / 255f
                    floatBuf.put(i, (r - IMAGENET_MEAN[0]) / IMAGENET_STD[0])
                    floatBuf.put(planeSize + i, (g - IMAGENET_MEAN[1]) / IMAGENET_STD[1])
                    floatBuf.put(2 * planeSize + i, (b - IMAGENET_MEAN[2]) / IMAGENET_STD[2])
                }
            } else {
                for (i in 0 until inW * inH) {
                    val p = pixels[i]
                    val r = ((p shr 16) and 0xFF) / 255f
                    val g = ((p shr 8) and 0xFF) / 255f
                    val b = (p and 0xFF) / 255f
                    floatBuf.put((r - IMAGENET_MEAN[0]) / IMAGENET_STD[0])
                    floatBuf.put((g - IMAGENET_MEAN[1]) / IMAGENET_STD[1])
                    floatBuf.put((b - IMAGENET_MEAN[2]) / IMAGENET_STD[2])
                }
            }
            buf.rewind()
            buf
        }

        val outTensor = interpreter.getOutputTensor(0)
        val outShape = outTensor.shape()
        val outType = outTensor.dataType()
        val outElements = outShape.fold(1) { acc, v -> acc * v }

        val nonUnitDims = outShape.filter { it > 1 }
        val outH = if (nonUnitDims.size >= 2) nonUnitDims[0] else inH
        val outW = if (nonUnitDims.size >= 2) nonUnitDims[1] else inW

        val rawFloats = FloatArray(outW * outH)
        if (outType == DataType.UINT8) {
            val outBuf = ByteBuffer.allocateDirect(outElements).order(ByteOrder.nativeOrder())
            interpreter.run(inputBuffer, outBuf)
            outBuf.rewind()
            val limit = min(rawFloats.size, outElements)
            for (i in 0 until limit) {
                rawFloats[i] = (outBuf.get().toInt() and 0xFF).toFloat()
            }
        } else {
            val outBuf = ByteBuffer.allocateDirect(outElements * 4).order(ByteOrder.nativeOrder())
            interpreter.run(inputBuffer, outBuf)
            outBuf.rewind()
            val fBuf = outBuf.asFloatBuffer()
            val limit = min(rawFloats.size, outElements)
            fBuf.get(rawFloats, 0, limit)
        }

        return rawFloats to (outW to outH)
    }

    private fun runOnnxInference(
        session: OrtSession,
        bitmap: Bitmap
    ): Pair<FloatArray, Pair<Int, Int>> {
        val env = OrtEnvironment.getEnvironment()
        val inputName = session.inputNames.first()
        val inH = 256
        val inW = 256

        val scaledBmp = if (bitmap.width == inW && bitmap.height == inH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inW, inH, true)
        }

        val pixels = IntArray(inW * inH)
        scaledBmp.getPixels(pixels, 0, inW, 0, 0, inW, inH)
        if (scaledBmp != bitmap && !scaledBmp.isRecycled) {
            scaledBmp.recycle()
        }

        val planeSize = inW * inH
        val floatData = FloatArray(3 * planeSize)
        for (i in 0 until planeSize) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            floatData[i] = (r - IMAGENET_MEAN[0]) / IMAGENET_STD[0]
            floatData[planeSize + i] = (g - IMAGENET_MEAN[1]) / IMAGENET_STD[1]
            floatData[2 * planeSize + i] = (b - IMAGENET_MEAN[2]) / IMAGENET_STD[2]
        }

        val shape = longArrayOf(1L, 3L, inH.toLong(), inW.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatData), shape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { results ->
                val outTensor = results[0] as OnnxTensor
                val outBuf = outTensor.floatBuffer
                outBuf.rewind()
                val outInfo = outTensor.info.shape
                val nonUnit = outInfo.filter { it > 1L }.map { it.toInt() }
                val outH = if (nonUnit.size >= 2) nonUnit[nonUnit.size - 2] else inH
                val outW = if (nonUnit.size >= 2) nonUnit[nonUnit.size - 1] else inW
                val outArr = FloatArray(outW * outH)
                outBuf.get(outArr, 0, min(outArr.size, outBuf.remaining()))
                return outArr to (outW to outH)
            }
        }
    }

    /**
     * Normalizes raw monocular inverse-depth output to [0.0 (far) .. 1.0 (near/close)]
     * using 2nd and 98th percentile clipping and checks polarity so foreground is consistently 1.0.
     */
    private fun normalizeDisparityMap(raw: FloatArray, w: Int, h: Int): FloatArray {
        if (raw.isEmpty()) return FloatArray(w * h)

        // Subsample for fast percentile calculation
        val step = max(1, raw.size / 2048)
        val samples = FloatArray((raw.size + step - 1) / step)
        var sIdx = 0
        for (i in raw.indices step step) {
            val v = raw[i]
            samples[sIdx++] = if (v.isFinite()) v else 0f
        }
        samples.sort(0, sIdx)

        val pLow = samples[(sIdx * 0.02f).toInt().coerceIn(0, max(0, sIdx - 1))]
        val pHigh = samples[(sIdx * 0.98f).toInt().coerceIn(0, max(0, sIdx - 1))]
        val span = (pHigh - pLow).takeIf { it > 1e-5f } ?: 1f

        val normalized = FloatArray(raw.size)
        for (i in raw.indices) {
            val v = if (raw[i].isFinite()) raw[i] else pLow
            normalized[i] = ((v - pLow) / span).coerceIn(0f, 1f)
        }

        // Polarity sanity check: in portrait scenes, center-lower subject region is closer than top corners
        var centerSum = 0f
        var centerCount = 0
        var cornerSum = 0f
        var cornerCount = 0

        for (y in 0 until h) {
            val ny = y.toFloat() / h
            for (x in 0 until w) {
                val nx = x.toFloat() / w
                val v = normalized[y * w + x]
                if (nx in 0.30f..0.70f && ny in 0.30f..0.80f) {
                    centerSum += v
                    centerCount++
                } else if (ny < 0.20f && (nx < 0.20f || nx > 0.80f)) {
                    cornerSum += v
                    cornerCount++
                }
            }
        }

        val centerAvg = if (centerCount > 0) centerSum / centerCount else 0.5f
        val cornerAvg = if (cornerCount > 0) cornerSum / cornerCount else 0.3f

        // If model outputs metric depth (small = close, large = far), invert to inverse-depth (1.0 = close, 0.0 = far)
        if (centerAvg < cornerAvg - 0.08f) {
            for (i in normalized.indices) {
                normalized[i] = 1f - normalized[i]
            }
        }

        return normalized
    }

    private fun upsampleBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val dst = FloatArray(dw * dh)
        val xRatio = max(1, sw - 1).toFloat() / max(1, dw).toFloat()
        val yRatio = max(1, sh - 1).toFloat() / max(1, dh).toFloat()

        for (y in 0 until dh) {
            val srcY = y * yRatio
            val y1 = srcY.toInt().coerceIn(0, sh - 1)
            val y2 = (y1 + 1).coerceAtMost(sh - 1)
            val yDiff = srcY - y1
            val y1Offset = y1 * sw
            val y2Offset = y2 * sw
            val dstOffset = y * dw

            for (x in 0 until dw) {
                val srcX = x * xRatio
                val x1 = srcX.toInt().coerceIn(0, sw - 1)
                val x2 = (x1 + 1).coerceAtMost(sw - 1)
                val xDiff = srcX - x1

                val a = src[y1Offset + x1]
                val b = src[y1Offset + x2]
                val c = src[y2Offset + x1]
                val d = src[y2Offset + x2]

                dst[dstOffset + x] = (
                    a * (1f - xDiff) * (1f - yDiff) +
                    b * xDiff * (1f - yDiff) +
                    c * (1f - xDiff) * yDiff +
                    d * xDiff * yDiff
                ).coerceIn(0f, 1f)
            }
        }
        return dst
    }

    fun release() {
        releaseSessionInternal()
    }

    private fun releaseSessionInternal() {
        try { tfliteInterpreter?.close() } catch (_: Exception) {}
        tfliteInterpreter = null
        try { gpuDelegate?.close() } catch (_: Exception) {}
        gpuDelegate = null
        try { ortSession?.close() } catch (_: Exception) {}
        ortSession = null
        cachedFilePath = null
    }
}
