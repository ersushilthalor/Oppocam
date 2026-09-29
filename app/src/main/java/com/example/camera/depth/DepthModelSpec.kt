package com.example.camera.depth

/**
 * Supported AI Monocular Depth Estimation Models inspired by PhotonCamera's Virtual Aperture system.
 * Models are downloaded on-demand from verified HuggingFace repositories and never bundled in the APK.
 */
enum class DepthModelType(
    val id: String,
    val displayName: String,
    val shortName: String,
    val version: String,
    val runtimeFormat: String,
    val fileName: String,
    val description: String,
    val sourceRepo: String,
    val downloadUrls: List<String>,
    val minValidBytes: Long
) {
    DEPTH_ANYTHING_V2(
        id = "depth_anything_v2",
        displayName = "Depth Anything V2",
        shortName = "DepthAnything V2",
        version = "v2.0-Small (ViT-S)",
        runtimeFormat = "TFLite / ONNX",
        fileName = "depth_anything_v2.model",
        description = "High-precision monocular depth estimation (ViT-S backbone) used in PhotonCamera Virtual Aperture for fine hair & complex edge depth transitions.",
        sourceRepo = "qualcomm/Depth-Anything-V2 (HuggingFace)",
        downloadUrls = listOf(
            "https://huggingface.co/qualcomm/Depth-Anything-V2/resolve/main/Depth-Anything-V2.tflite?download=true",
            "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_quantized.onnx?download=true",
            "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx?download=true"
        ),
        minValidBytes = 10_000_000L // > 10 MB real model weights
    ),

    MIDAS_V2_SWLF_I(
        id = "midas_v2_swlf_i",
        displayName = "MediaSWLF-I (MiDaS v2.1 Small)",
        shortName = "MediaSWLF-I / MiDaS",
        version = "v2.1-SWLF-I",
        runtimeFormat = "TFLite / ONNX",
        fileName = "midas_v2_swlf_i.model",
        description = "Fast mobile monocular inverse-depth model (Qualcomm / LiteRT MiDaS v2.1 Small) optimized for low-latency Virtual Aperture depth map generation.",
        sourceRepo = "qualcomm/Midas-V2 & litert-community/MiDaS (HuggingFace)",
        downloadUrls = listOf(
            "https://huggingface.co/qualcomm/Midas-V2/resolve/main/Midas-V2.tflite?download=true",
            "https://huggingface.co/litert-community/MiDaS/resolve/main/midas.tflite?download=true",
            "https://huggingface.co/julienkay/sentis-MiDaS/resolve/main/onnx/midas_v21_small_256.onnx?download=true"
        ),
        minValidBytes = 5_000_000L // > 5 MB real model weights
    );

    companion object {
        fun fromId(id: String?): DepthModelType {
            return entries.firstOrNull { it.id == id || it.name == id } ?: DEPTH_ANYTHING_V2
        }
    }
}

enum class DepthHardwareBackend(val label: String, val description: String) {
    AUTO("Auto (NPU/GPU/CPU)", "Automatically selects GPU/NNAPI acceleration with multi-threaded CPU fallback"),
    GPU("GPU / Delegate", "Prioritizes OpenGL/OpenCL GPU delegate for fast dense tensor inference"),
    NNAPI("NNAPI / NPU", "Uses Android Neural Networks API for hardware NPU acceleration"),
    CPU("CPU (XNNPACK 4-Thread)", "High-compatibility multi-threaded ARM NEON / XNNPACK execution")
}

sealed class DepthModelInstallState {
    data object NotInstalled : DepthModelInstallState()

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressFraction: Float,
        val speedBytesPerSec: Long = 0L
    ) : DepthModelInstallState()

    data object Verifying : DepthModelInstallState()

    data class Installed(
        val fileSizeBytes: Long,
        val verifiedFormat: String,
        val inputShapeSummary: String,
        val lastModifiedMs: Long
    ) : DepthModelInstallState()

    data class Failed(
        val errorMessage: String,
        val partialBytes: Long = 0L
    ) : DepthModelInstallState()
}

data class DepthModelStatusInfo(
    val modelType: DepthModelType,
    val state: DepthModelInstallState,
    val remoteSizeBytes: Long? = null,
    val isSelected: Boolean = false
)

data class DepthStorageSummary(
    val totalModelsBytes: Long = 0L,
    val availableDeviceBytes: Long = 0L,
    val totalDeviceBytes: Long = 0L
)
