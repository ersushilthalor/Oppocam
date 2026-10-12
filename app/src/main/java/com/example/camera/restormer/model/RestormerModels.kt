package com.example.camera.restormer.model

import com.squareup.moshi.JsonClass

/**
 * Tasks supported by the official Restormer architecture (CVPR 2022).
 */
enum class RestormerTask(val displayName: String, val description: String, val badge: String) {
    REAL_DENOISING(
        displayName = "Real Denoising",
        description = "Removes real sensor noise from low-light / high-ISO photos (SIDD / DND benchmark)",
        badge = "DENOISE"
    ),
    MOTION_DEBLURRING(
        displayName = "Motion Deblurring",
        description = "Restores sharp details from camera handshake or fast subject motion (GoPro benchmark)",
        badge = "DEBLUR"
    ),
    DEFOCUS_DEBLURRING(
        displayName = "Defocus Deblurring",
        description = "Corrects optical defocus blur from shallow depth of field (DPDD benchmark)",
        badge = "DEFOCUS"
    ),
    DERAINING(
        displayName = "Deraining",
        description = "Clears heavy rain streaks, haze, and rain droplets from outdoor photos",
        badge = "DERAIN"
    ),
    GAUSSIAN_DENOISING(
        displayName = "Gaussian Denoising",
        description = "Removes synthetic and color electronic noise across all color channels",
        badge = "COLOR NR"
    ),
    CUSTOM(
        displayName = "Custom AI Restoration",
        description = "User-imported compatible Restormer model",
        badge = "CUSTOM"
    )
}

/**
 * Execution backends for model inference.
 */
enum class ModelBackend(val displayName: String) {
    ONNX_RUNTIME("ONNX Runtime (Mobile)"),
    TENSORFLOW_LITE("TensorFlow Lite"),
    NATIVE_ENGINE("Restormer Transformer Engine")
}

/**
 * Data layout of input/output tensors.
 */
enum class TensorLayout {
    NCHW, // Channels first: [Batch, Channels, Height, Width] (Standard PyTorch / ONNX)
    NHWC  // Channels last:  [Batch, Height, Width, Channels] (Standard TFLite)
}

/**
 * Hardware execution provider preference.
 */
enum class HardwareDelegateOption(val displayName: String, val description: String) {
    AUTO("Auto (Optimal)", "Selects best available hardware accelerator"),
    GPU("GPU Acceleration", "High throughput OpenCL / Vulkan shader delegate"),
    NNAPI("NNAPI (NPU / DSP)", "Android Neural Networks API on-chip NPU"),
    CPU("Multi-Threaded CPU", "Consistent CPU execution with optimized NEON kernels")
}

/**
 * Metadata for a Restormer model loaded into the AI Model Manager.
 */
@JsonClass(generateAdapter = true)
data class RestormerModelInfo(
    val id: String,
    val name: String,
    val task: RestormerTask,
    val backend: ModelBackend,
    val filePath: String? = null,
    val assetPath: String? = null,
    val inputHeight: Int = 128,
    val inputWidth: Int = 128,
    val inputChannels: Int = 3,
    val tensorLayout: TensorLayout = TensorLayout.NCHW,
    val isBuiltIn: Boolean = false,
    val fileSizeBytes: Long = 0L,
    val isCompatible: Boolean = true,
    val dateImported: Long = System.currentTimeMillis(),
    val validationSummary: String = "Verified Restormer compatible",
    val benchmarkLatencyMs: Long = 0L
)

/**
 * Configuration for Restormer inference and memory-efficient tiling.
 */
@JsonClass(generateAdapter = true)
data class RestormerConfig(
    val isEnabled: Boolean = false,
    val activeModelId: String = "restormer_real_denoise_official",
    val tileSize: Int = 128,          // Tile size in pixels (e.g. 128 or 256)
    val overlap: Int = 16,            // Overlap margin between neighboring tiles (e.g. 16 or 32)
    val restorationStrength: Float = 1.0f, // 0.0f..1.0f blend with original
    val hardwareDelegate: HardwareDelegateOption = HardwareDelegateOption.AUTO,
    val cpuThreadCount: Int = 4,
    val enableHannFeathering: Boolean = true // Seamless boundary feathering to eliminate seams
)

/**
 * Result returned after performing tiled inference on a high-resolution photo.
 */
data class RestormerInferenceResult(
    val outputBitmap: android.graphics.Bitmap,
    val modelId: String,
    val modelName: String,
    val durationMs: Long,
    val tilesProcessed: Int,
    val originalWidth: Int,
    val originalHeight: Int,
    val avgTileMs: Float
)
