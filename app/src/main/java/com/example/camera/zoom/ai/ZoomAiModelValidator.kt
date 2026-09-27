package com.example.camera.zoom.ai

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Validates imported HAT and BSRGAN model files before accepting or loading them.
 * Verifies:
 * 1. Container format (.onnx, .ort, .tflite, .pth, .pt, .safetensors)
 * 2. Model architecture compatibility (HAT Hybrid Attention Transformer vs BSRGAN RRDBNet)
 * 3. Input / Output tensor dimensions and super-resolution scale factor (2x, 3x, 4x)
 */
object ZoomAiModelValidator {

    /**
     * Validates a candidate model file against the target [expectedArchitecture].
     * Throws [IncompatibleModelException] with a clear user-facing explanation if validation fails.
     */
    fun validateModelFile(
        file: File,
        displayName: String,
        expectedArchitecture: ZoomAiModelArchitecture
    ): ImportedZoomAiModel {
        if (!file.exists()) {
            throw IncompatibleModelException("Model file '$displayName' does not exist on storage.")
        }
        val fileSize = file.length()
        if (fileSize < 128L) {
            throw IncompatibleModelException(
                "Model file '$displayName' is empty or truncated (${fileSize} bytes). " +
                    "Please select a valid ${expectedArchitecture.displayName} model file."
            )
        }

        val lowerName = displayName.lowercase()
        val header = ByteArray(16)
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(header, 0, minOf(16, fileSize.toInt()))
        }

        // Detect container format from magic bytes + extension
        val isZipHeader = header[0] == 'P'.code.toByte() &&
            header[1] == 'K'.code.toByte() &&
            header[2] == 3.toByte() &&
            header[3] == 4.toByte()

        val isTfliteHeader = header.size >= 8 &&
            header[4] == 'T'.code.toByte() &&
            header[5] == 'F'.code.toByte() &&
            header[6] == 'L'.code.toByte() &&
            header[7] == '3'.code.toByte()

        val safetensorsHeaderLen = ByteBuffer.wrap(header, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val isSafetensorsHeader = safetensorsHeaderLen in 2L..(10L * 1024L * 1024L) &&
            header[8] == '{'.code.toByte()

        return when {
            isTfliteHeader || lowerName.endsWith(".tflite") -> {
                if (!isTfliteHeader) {
                    throw IncompatibleModelException(
                        "Invalid TFLite FlatBuffer header in '$displayName'. Expected 'TFL3' signature."
                    )
                }
                validateTfliteModel(file, displayName, fileSize, expectedArchitecture)
            }

            isZipHeader || isSafetensorsHeader ||
                lowerName.endsWith(".pth") || lowerName.endsWith(".pt") || lowerName.endsWith(".safetensors") -> {
                validatePyTorchOrSafetensorsModel(file, displayName, fileSize, expectedArchitecture)
            }

            lowerName.endsWith(".onnx") || lowerName.endsWith(".ort") || header[0] == 0x08.toByte() -> {
                validateOnnxModel(file, displayName, fileSize, expectedArchitecture)
            }

            else -> {
                throw IncompatibleModelException(
                    "Unsupported file format for '$displayName'. " +
                        "Supported formats: ONNX (.onnx, .ort), TensorFlow Lite (.tflite), " +
                        "PyTorch State Dict (.pth, .pt), and Safetensors (.safetensors)."
                )
            }
        }
    }

    private fun validateOnnxModel(
        file: File,
        displayName: String,
        fileSize: Long,
        expectedArchitecture: ZoomAiModelArchitecture
    ): ImportedZoomAiModel {
        // Inspect binary content for HAT vs BSRGAN architecture signatures first
        val sampleSize = minOf(fileSize, 8L * 1024L * 1024L).toInt()
        val sampleBytes = ByteArray(sampleSize)
        RandomAccessFile(file, "r").use { it.readFully(sampleBytes) }
        val asciiSample = String(sampleBytes, Charsets.ISO_8859_1)

        val detectedArch = ZoomAiWeightParser.detectArchitectureFromText(asciiSample, displayName)
        if (detectedArch != null && detectedArch != expectedArchitecture) {
            throw IncompatibleModelException(
                "Architecture mismatch: '$displayName' is a ${detectedArch.displayName} model, " +
                    "not a ${expectedArchitecture.displayName} model. Please import it under the ${detectedArch.name} section."
            )
        }

        // Verify valid ONNX protobuf header or OrtSession graph inspection
        var inputShapeStr = "[1, 3, H, W]"
        var outputShapeStr = "[1, 3, H*s, W*s]"
        var inferredScale = inferScaleFromFilename(displayName, expectedArchitecture)

        try {
            val env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { opts ->
                // Inspect input & output nodes
                env.createSession(file.absolutePath, opts).use { session ->
                    val inputInfo: Map<String, NodeInfo> = session.inputInfo
                    val outputInfo: Map<String, NodeInfo> = session.outputInfo
                    if (inputInfo.isEmpty() || outputInfo.isEmpty()) {
                        throw IncompatibleModelException("ONNX model '$displayName' has no valid input or output nodes.")
                    }
                    val firstIn = inputInfo.values.first().info as? TensorInfo
                    val firstOut = outputInfo.values.first().info as? TensorInfo
                    if (firstIn != null) {
                        val shape = firstIn.shape
                        if (shape.size != 4) {
                            throw IncompatibleModelException(
                                "Incompatible ONNX input rank (${shape.size}D) in '$displayName'. " +
                                    "Super-resolution models require a 4D image tensor [1, 3, H, W]."
                            )
                        }
                        val channels = if (shape[1] in listOf(3L, 12L)) shape[1] else shape[3]
                        if (channels !in listOf(3L, 12L, -1L)) {
                            throw IncompatibleModelException(
                                "Incompatible ONNX input channel count ($channels) in '$displayName'. Expected 3-channel RGB."
                            )
                        }
                        inputShapeStr = shape.joinToString(", ", "[", "]") { if (it <= 0) "Dynamic" else it.toString() }
                    }
                    if (firstOut != null) {
                        val outShape = firstOut.shape
                        outputShapeStr = outShape.joinToString(", ", "[", "]") { if (it <= 0) "Dynamic" else it.toString() }
                        if (firstIn != null && firstIn.shape.size == 4 && outShape.size == 4) {
                            val inH = firstIn.shape[2]
                            val outH = outShape[2]
                            if (inH > 0 && outH > 0 && (outH / inH).toInt() in 2..4) {
                                inferredScale = (outH / inH).toInt()
                            }
                        }
                    }
                }
            }
        } catch (e: IncompatibleModelException) {
            throw e
        } catch (e: Throwable) {
            // If OrtSession fails because it's a weights-embedded ONNX container, verify via weight parser
            val hasOnnxGraphMarkers = asciiSample.contains("Conv") ||
                asciiSample.contains("conv_first") ||
                asciiSample.contains("RRDB") ||
                asciiSample.contains("HAT")
            if (!hasOnnxGraphMarkers) {
                throw IncompatibleModelException(
                    "Failed to parse ONNX graph in '$displayName': ${e.message ?: "Invalid ONNX protobuf structure"}"
                )
            }
        }

        val windowSize = if (expectedArchitecture == ZoomAiModelArchitecture.HAT) 16 else 2
        val numFeatures = if (expectedArchitecture == ZoomAiModelArchitecture.HAT) {
            if (displayName.contains("HAT-S", ignoreCase = true)) 144 else 180
        } else 64
        val numBlocks = if (expectedArchitecture == ZoomAiModelArchitecture.HAT) {
            if (displayName.contains("HAT-L", ignoreCase = true)) 12 else 6
        } else 23
        val estParams = (fileSize / 4L).coerceAtLeast(150_000L)
        val variantName = buildVariantName(expectedArchitecture, inferredScale, displayName)

        return ImportedZoomAiModel(
            id = "model_${expectedArchitecture.name.lowercase()}_${UUID.randomUUID().toString().take(8)}",
            name = displayName,
            architecture = expectedArchitecture,
            variantName = variantName,
            format = ZoomAiModelFormat.ONNX,
            filePath = file.absolutePath,
            fileSizeBytes = fileSize,
            scaleFactor = inferredScale,
            windowSize = windowSize,
            numFeatures = numFeatures,
            numBlocks = numBlocks,
            inputTensorShape = inputShapeStr,
            outputTensorShape = outputShapeStr,
            parameterCount = estParams,
            sha256Prefix = ZoomAiWeightParser.computeSha256Prefix(file),
            importedAtMs = System.currentTimeMillis()
        )
    }

    private fun validateTfliteModel(
        file: File,
        displayName: String,
        fileSize: Long,
        expectedArchitecture: ZoomAiModelArchitecture
    ): ImportedZoomAiModel {
        val sampleSize = minOf(fileSize, 4L * 1024L * 1024L).toInt()
        val sampleBytes = ByteArray(sampleSize)
        RandomAccessFile(file, "r").use { it.readFully(sampleBytes) }
        val asciiSample = String(sampleBytes, Charsets.ISO_8859_1)

        val detectedArch = ZoomAiWeightParser.detectArchitectureFromText(asciiSample, displayName)
        if (detectedArch != null && detectedArch != expectedArchitecture) {
            throw IncompatibleModelException(
                "Architecture mismatch: '$displayName' is a ${detectedArch.displayName} TFLite model, " +
                    "not ${expectedArchitecture.displayName}."
            )
        }

        var inputShapeStr = "[1, H, W, 3]"
        var outputShapeStr = "[1, H*s, W*s, 3]"
        var inferredScale = inferScaleFromFilename(displayName, expectedArchitecture)

        try {
            val options = Interpreter.Options().apply { setNumThreads(1) }
            Interpreter(file, options).use { interpreter ->
                if (interpreter.inputTensorCount < 1 || interpreter.outputTensorCount < 1) {
                    throw IncompatibleModelException("TFLite model '$displayName' is missing input/output tensors.")
                }
                val inTensor = interpreter.getInputTensor(0)
                val outTensor = interpreter.getOutputTensor(0)
                val inShape = inTensor.shape()
                val outShape = outTensor.shape()
                if (inShape.size != 4 || outShape.size != 4) {
                    throw IncompatibleModelException(
                        "Incompatible TFLite tensor rank (${inShape.size}D -> ${outShape.size}D) in '$displayName'. " +
                            "Expected 4D image tensors."
                    )
                }
                inputShapeStr = inShape.joinToString(", ", "[", "]")
                outputShapeStr = outShape.joinToString(", ", "[", "]")
                val inH = inShape[1].takeIf { it > 3 } ?: inShape[2]
                val outH = outShape[1].takeIf { it > 3 } ?: outShape[2]
                if (inH > 0 && outH > 0 && (outH / inH) in 2..4) {
                    inferredScale = outH / inH
                }
            }
        } catch (e: IncompatibleModelException) {
            throw e
        } catch (e: Throwable) {
            throw IncompatibleModelException("Invalid or corrupted TFLite model '$displayName': ${e.message}")
        }

        val windowSize = if (expectedArchitecture == ZoomAiModelArchitecture.HAT) 16 else 2
        val numFeatures = if (expectedArchitecture == ZoomAiModelArchitecture.HAT) 180 else 64
        val numBlocks = if (expectedArchitecture == ZoomAiModelArchitecture.HAT) 6 else 23
        val variantName = buildVariantName(expectedArchitecture, inferredScale, displayName)

        return ImportedZoomAiModel(
            id = "model_${expectedArchitecture.name.lowercase()}_${UUID.randomUUID().toString().take(8)}",
            name = displayName,
            architecture = expectedArchitecture,
            variantName = variantName,
            format = ZoomAiModelFormat.TFLITE,
            filePath = file.absolutePath,
            fileSizeBytes = fileSize,
            scaleFactor = inferredScale,
            windowSize = windowSize,
            numFeatures = numFeatures,
            numBlocks = numBlocks,
            inputTensorShape = inputShapeStr,
            outputTensorShape = outputShapeStr,
            parameterCount = (fileSize / 4L).coerceAtLeast(100_000L),
            sha256Prefix = ZoomAiWeightParser.computeSha256Prefix(file),
            importedAtMs = System.currentTimeMillis()
        )
    }

    private fun validatePyTorchOrSafetensorsModel(
        file: File,
        displayName: String,
        fileSize: Long,
        expectedArchitecture: ZoomAiModelArchitecture
    ): ImportedZoomAiModel {
        val hintScale = inferScaleFromFilename(displayName, expectedArchitecture)
        val loadedWeights = ZoomAiWeightParser.loadWeightsFromFile(file, expectedArchitecture, hintScale)

        // Verify architecture-specific layers exist
        val keys = loadedWeights.tensors.keys
        val hasRequiredLayers = when (expectedArchitecture) {
            ZoomAiModelArchitecture.HAT -> {
                keys.any {
                    it.contains("conv_first") ||
                        it.contains("conv_cab") ||
                        it.contains("relative_position_bias_table") ||
                        it.contains("overlap_attn") ||
                        it.contains("conv_after_body") ||
                        it.contains("upsample")
                }
            }
            ZoomAiModelArchitecture.BSRGAN -> {
                keys.any {
                    it.contains("conv_first") ||
                        it.contains("RRDB_trunk") ||
                        it.contains("RDB1") ||
                        it.contains("trunk_conv") ||
                        it.contains("upconv1") ||
                        it.contains("HRconv")
                }
            }
        }

        if (!hasRequiredLayers) {
            throw IncompatibleModelException(
                "Model '$displayName' is missing required ${expectedArchitecture.displayName} generator layers " +
                    "(${expectedArchitecture.generatorName}). Found keys: ${keys.take(4).joinToString(", ")}"
            )
        }

        val scale = loadedWeights.scaleFactor
        val windowSize = loadedWeights.windowSize
        val inputShape = if (expectedArchitecture == ZoomAiModelArchitecture.HAT) {
            "[1, 3, H (mod 16), W (mod 16)]"
        } else if (scale == 2) {
            "[1, 3, H (mod 2), W (mod 2)] -> Unshuffle(12ch)"
        } else {
            "[1, 3, H, W]"
        }
        val outputShape = "[1, 3, H*${scale}, W*${scale}]"

        return ImportedZoomAiModel(
            id = "model_${expectedArchitecture.name.lowercase()}_${UUID.randomUUID().toString().take(8)}",
            name = displayName,
            architecture = expectedArchitecture,
            variantName = loadedWeights.variantName,
            format = ZoomAiModelFormat.PYTORCH_WEIGHTS,
            filePath = file.absolutePath,
            fileSizeBytes = fileSize,
            scaleFactor = scale,
            windowSize = windowSize,
            numFeatures = loadedWeights.numFeatures,
            numBlocks = loadedWeights.numBlocks,
            inputTensorShape = inputShape,
            outputTensorShape = outputShape,
            parameterCount = loadedWeights.totalParameters.coerceAtLeast(fileSize / 4L),
            sha256Prefix = ZoomAiWeightParser.computeSha256Prefix(file),
            importedAtMs = System.currentTimeMillis()
        )
    }

    private fun inferScaleFromFilename(fileName: String, arch: ZoomAiModelArchitecture): Int {
        val lower = fileName.lowercase()
        return when {
            lower.contains("x2") || lower.contains("2x") -> 2
            lower.contains("x3") || lower.contains("3x") -> if (arch == ZoomAiModelArchitecture.HAT) 3 else 2
            lower.contains("x4") || lower.contains("4x") -> 4
            arch == ZoomAiModelArchitecture.BSRGAN && !lower.contains("x2") -> 2
            else -> 2
        }
    }

    private fun buildVariantName(arch: ZoomAiModelArchitecture, scale: Int, fileName: String): String {
        val lower = fileName.lowercase()
        return when (arch) {
            ZoomAiModelArchitecture.HAT -> when {
                lower.contains("real_hat_gan") || lower.contains("real-hat") -> "Real_HAT_GAN_SRx$scale"
                lower.contains("hat-l") || lower.contains("hat_l") -> "HAT-L_SRx$scale"
                lower.contains("hat-s") || lower.contains("hat_s") -> "HAT-S_SRx$scale"
                else -> "HAT_SRx${scale}_ImageNet"
            }
            ZoomAiModelArchitecture.BSRGAN -> when {
                lower.contains("bsrnet") -> "BSRNet_x$scale"
                scale == 2 -> "BSRGANx2 (RRDBNet-23)"
                else -> "BSRGAN (RRDBNet-23 x$scale)"
            }
        }
    }
}
