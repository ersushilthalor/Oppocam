package com.cinedepth.pro.ui.blur

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class DepthEstimator(context: Context) {
    companion object {
        const val INPUT_IMAGE_SIZE = 518
    }
    private var interpreter: Interpreter? = null
    private val modelFileName = "Depth-Anything-V2.tflite"

    private val inputImageSize = INPUT_IMAGE_SIZE
    private var isInitialized = false

    // Pre-allocate for performance (Float32: 1 * 518 * 518 * 3 channels * 4 bytes)
    private val inputBuffer = ByteBuffer.allocateDirect(1 * inputImageSize * inputImageSize * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    // Output (Float32: 1 * 518 * 518 * 1 channel * 4 bytes)
    private val outputBuffer = ByteBuffer.allocateDirect(1 * inputImageSize * inputImageSize * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    init {
        try {
            val modelBuffer = loadModelFile(context, modelFileName)
            if (modelBuffer != null) {
                val compatList = CompatibilityList()
                val options = Interpreter.Options()

                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegateOptions = GpuDelegate.Options().apply {
                        // FP16 precision is much faster on mobile GPUs with minimal quality loss for depth
                        setPrecisionLossAllowed(true)
                        setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    }
                    val gpuDelegate = GpuDelegate(delegateOptions)
                    options.addDelegate(gpuDelegate)
                } else {
                    options.setNumThreads(4)
                }

                interpreter = Interpreter(modelBuffer, options)
                isInitialized = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isReady(): Boolean = isInitialized

    fun estimateDepth(bitmap: Bitmap): FloatArray? {
        if (!isInitialized || interpreter == null) return null

        val scaled = if (bitmap.width == inputImageSize && bitmap.height == inputImageSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputImageSize, inputImageSize, true)
        }

        val pixels = IntArray(inputImageSize * inputImageSize)
        scaled.getPixels(pixels, 0, inputImageSize, 0, 0, inputImageSize, inputImageSize)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        inputBuffer.rewind()
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }
        inputBuffer.rewind()
        outputBuffer.rewind()

        try {
            interpreter?.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        outputBuffer.rewind()
        val floatArray = FloatArray(inputImageSize * inputImageSize)
        outputBuffer.asFloatBuffer().get(floatArray)

        // Optimized normalization loop (single pass for min/max)
        var minV = Float.MAX_VALUE
        var maxV = Float.MIN_VALUE
        for (i in floatArray.indices) {
            val v = floatArray[i]
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }

        val range = if (maxV - minV > 0) maxV - minV else 1f
        val invRange = 1.0f / range

        for (i in floatArray.indices) {
            floatArray[i] = (floatArray[i] - minV) * invRange
        }

        return floatArray
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer? {
        try {
            val assetFileDescriptor = context.assets.openFd(modelName)
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (_: Exception) {}

        try {
            val modelsDir = java.io.File(context.filesDir, "depth_ai_models")
            val candidateFile = java.io.File(modelsDir, "depth_anything_v2.model").takeIf { it.exists() && it.length() > 1000 }
                ?: java.io.File(modelsDir, modelName).takeIf { it.exists() && it.length() > 1000 }
                ?: java.io.File(context.filesDir, modelName).takeIf { it.exists() && it.length() > 1000 }
            if (candidateFile != null) {
                FileInputStream(candidateFile).channel.use { channel ->
                    return channel.map(FileChannel.MapMode.READ_ONLY, 0, candidateFile.length())
                }
            }
        } catch (_: Exception) {}

        return null
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
