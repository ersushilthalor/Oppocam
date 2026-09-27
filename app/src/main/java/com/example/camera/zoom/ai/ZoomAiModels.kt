package com.example.camera.zoom.ai

import java.util.Locale

/**
 * Reconstruction modes available in the Zoom Enhanced feature.
 * Preserves the existing Traditional Multi-Frame Lanczos-3 pipeline while adding
 * genuine HAT (Hybrid Attention Transformer) and BSRGAN (Blind Super-Resolution GAN) AI reconstruction.
 */
enum class ZoomReconstructionMode(
    val title: String,
    val shortBadge: String,
    val description: String,
    val isAiModel: Boolean
) {
    TRADITIONAL(
        title = "Standard (Lanczos-3)",
        shortBadge = "LANCZOS",
        description = "Multi-frame sub-pixel alignment, Lanczos-3 sinc interpolation & halo-free micro-detail recovery",
        isAiModel = false
    ),
    HAT(
        title = "HAT AI Reconstruction",
        shortBadge = "HAT AI",
        description = "XPixelGroup/HAT: Hybrid Attention Transformer combining Channel Attention, Window Self-Attention & Overlapping Cross-Attention (OCAB)",
        isAiModel = true
    ),
    BSRGAN(
        title = "BSRGAN AI Reconstruction",
        shortBadge = "BSRGAN AI",
        description = "cszn/BSRGAN: 23-Block RRDBNet Deep Blind Super-Resolution GAN for realistic degradation removal & fine texture synthesis",
        isAiModel = true
    )
}

/**
 * Target AI model architecture family based on official repositories:
 * - HAT: https://github.com/XPixelGroup/HAT
 * - BSRGAN: https://github.com/cszn/BSRGAN
 */
enum class ZoomAiModelArchitecture(
    val displayName: String,
    val officialRepoUrl: String,
    val generatorName: String,
    val defaultWindowSize: Int,
    val supportedScales: List<Int>,
    val preprocessingSpec: String
) {
    HAT(
        displayName = "HAT (Hybrid Attention Transformer)",
        officialRepoUrl = "https://github.com/XPixelGroup/HAT",
        generatorName = "HAT (RHAG + HAB [W-MSA/SW-MSA + CAB] + OCAB + PixelShuffle)",
        defaultWindowSize = 16,
        supportedScales = listOf(2, 3, 4),
        preprocessingSpec = "RGB float32 [0, 1], NCHW (1,3,H,W), reflection pad H,W to multiple of window_size=16"
    ),
    BSRGAN(
        displayName = "BSRGAN (Blind Super-Resolution GAN)",
        officialRepoUrl = "https://github.com/cszn/BSRGAN",
        generatorName = "RRDBNet (in_nc=3, out_nc=3, nf=64, nb=23, gc=32, ResidualDenseBlock_5C)",
        defaultWindowSize = 2,
        supportedScales = listOf(2, 4),
        preprocessingSpec = "RGB float32 [0, 1], NCHW (1,3,H,W), BSRGANx2 uses 2x2 pixel-unshuffle (12ch input)"
    )
}

/**
 * Supported model weight & graph container formats on Android.
 */
enum class ZoomAiModelFormat(
    val label: String,
    val extensions: List<String>
) {
    ONNX("ONNX Graph (.onnx / .ort)", listOf("onnx", "ort")),
    TFLITE("TensorFlow Lite (.tflite)", listOf("tflite")),
    PYTORCH_WEIGHTS("PyTorch Pretrained Weights (.pth / .pt / .safetensors)", listOf("pth", "pt", "safetensors"))
}

/**
 * Active GPU / NPU hardware execution provider used for model inference.
 * CPU fallback is strictly prohibited.
 */
enum class ZoomAiExecutionProvider(
    val displayName: String,
    val shortLabel: String,
    val deviceCategory: String, // "NPU" or "GPU" or "NONE"
    val isAccelerated: Boolean
) {
    NPU_NNAPI(
        displayName = "NPU (Android NNAPI Hardware Accelerator · CPU Disabled)",
        shortLabel = "NPU · NNAPI",
        deviceCategory = "NPU",
        isAccelerated = true
    ),
    GPU_ONNX_NNAPI(
        displayName = "GPU (ONNX Runtime Hardware NNAPI GPU · CPU Disabled)",
        shortLabel = "GPU · ORT-NNAPI",
        deviceCategory = "GPU",
        isAccelerated = true
    ),
    GPU_TFLITE_DELEGATE(
        displayName = "GPU (TFLite OpenCL / OpenGL ES 3.1 GPU Delegate)",
        shortLabel = "GPU · TFLite",
        deviceCategory = "GPU",
        isAccelerated = true
    ),
    GPU_GLES31_COMPUTE(
        displayName = "GPU (OpenGL ES 3.1 Hardware Compute Shaders · SSBO FP16/FP32)",
        shortLabel = "GPU · GLES 3.1 Compute",
        deviceCategory = "GPU",
        isAccelerated = true
    ),
    UNAVAILABLE(
        displayName = "Unavailable (No Compatible GPU/NPU Accelerator Found)",
        shortLabel = "UNAVAILABLE",
        deviceCategory = "NONE",
        isAccelerated = false
    )
}

/**
 * Loading and runtime state for each AI model family.
 */
enum class ZoomAiLoadingStatus(
    val label: String,
    val isReady: Boolean,
    val isBusy: Boolean,
    val isError: Boolean
) {
    NOT_IMPORTED("No Model Imported", isReady = false, isBusy = false, isError = false),
    VALIDATING("Validating Model Architecture & Weights...", isReady = false, isBusy = true, isError = false),
    LOADING_ACCELERATOR("Loading Weights to GPU/NPU Accelerator...", isReady = false, isBusy = true, isError = false),
    LOADED_READY("Loaded & Ready on GPU/NPU", isReady = true, isBusy = false, isError = false),
    RECONSTRUCTING("Reconstructing Image on GPU/NPU...", isReady = true, isBusy = true, isError = false),
    ERROR("Model Load / Acceleration Error", isReady = false, isBusy = false, isError = true)
}

/**
 * Metadata and validated architecture specifications for an imported HAT or BSRGAN model file.
 */
data class ImportedZoomAiModel(
    val id: String,
    val name: String,
    val architecture: ZoomAiModelArchitecture,
    val variantName: String,
    val format: ZoomAiModelFormat,
    val filePath: String,
    val fileSizeBytes: Long,
    val scaleFactor: Int,
    val windowSize: Int,
    val numFeatures: Int,
    val numBlocks: Int,
    val inputTensorShape: String,
    val outputTensorShape: String,
    val parameterCount: Long,
    val sha256Prefix: String,
    val importedAtMs: Long
) {
    val formattedFileSize: String
        get() = formatByteSize(fileSizeBytes)

    val formattedParamCount: String
        get() = when {
            parameterCount >= 1_000_000L -> String.format(Locale.US, "%.2fM params", parameterCount / 1_000_000.0)
            parameterCount >= 1_000L -> String.format(Locale.US, "%.1fK params", parameterCount / 1_000.0)
            else -> "$parameterCount params"
        }

    companion object {
        fun formatByteSize(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
                bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}

/**
 * Real-time hardware acceleration telemetry for the device's GPU and NPU.
 */
data class ZoomAiHardwareStatus(
    val activeProvider: ZoomAiExecutionProvider = ZoomAiExecutionProvider.GPU_GLES31_COMPUTE,
    val activeDeviceSummary: String = "Detecting GPU/NPU...",
    val gpuRenderer: String = "Unknown GPU",
    val gpuVendor: String = "Unknown Vendor",
    val glEsVersion: String = "OpenGL ES 3.1",
    val npuAvailable: Boolean = false,
    val npuAcceleratorName: String = "None",
    val onnxNnapiSupported: Boolean = false,
    val tfliteGpuSupported: Boolean = false,
    val glesComputeSupported: Boolean = true,
    val maxWorkGroupInvocations: Int = 256,
    val availableMemoryMb: Long = 512L,
    val recommendedTileSize: Int = 192
) {
    val isHardwareAccelerated: Boolean
        get() = activeProvider.isAccelerated && (npuAvailable || onnxNnapiSupported || tfliteGpuSupported || glesComputeSupported)
}

/**
 * Result of a single zoomed image AI reconstruction run.
 */
data class ZoomAiReconstructionResult(
    val reconstructedBitmap: android.graphics.Bitmap,
    val architecture: ZoomAiModelArchitecture,
    val modelName: String,
    val executionProvider: ZoomAiExecutionProvider,
    val executionDeviceName: String,
    val scaleFactor: Int,
    val tilesProcessed: Int,
    val inferenceTimeMs: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int
)

/**
 * Thrown when GPU/NPU acceleration is unavailable or when a model fails hardware execution.
 * CPU fallback is explicitly forbidden by specification.
 */
class GpuNpuAccelerationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when an imported model file fails architecture, format, or tensor compatibility validation.
 */
class IncompatibleModelException(message: String, cause: Throwable? = null) : Exception(message, cause)
