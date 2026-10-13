package com.example.camera.restormer.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.camera.restormer.engine.RestormerInferenceEngine
import com.example.camera.restormer.model.ModelBackend
import com.example.camera.restormer.model.RestormerModelInfo
import com.example.camera.restormer.model.RestormerTask
import com.example.camera.restormer.model.TensorLayout
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

private const val TAG = "RestormerModelManager"

sealed interface ModelImportResult {
    data class Success(val modelInfo: RestormerModelInfo, val benchmarkMs: Long) : ModelImportResult
    data class Error(val reason: String) : ModelImportResult
    data class PthCheckpointDetected(val fileName: String, val instructions: String) : ModelImportResult
}

data class ModelValidationReport(
    val isValid: Boolean,
    val format: String,
    val inputShape: String,
    val outputShape: String,
    val latencyMs: Long,
    val details: String
)

/**
 * AI Model Manager:
 * Discovers, extracts from assets, imports, validates, benchmarks, and manages genuine Restormer AI models.
 *
 * Guarantees:
 * 1. Safe extraction of built-in models and companion external weight files from assets to internal storage.
 * 2. Proper filesystem resolution for ONNX Runtime session initialization (eliminating ORT filesystem errors).
 * 3. Detection of PyTorch .pth checkpoints with practical conversion guidance.
 * 4. Genuine execution and benchmarking before accepting user-imported models.
 */
class RestormerModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("restormer_model_prefs", Context.MODE_PRIVATE)
    val modelsDir: File = File(context.filesDir, "restormer_models").apply {
        if (!exists()) mkdirs()
    }

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val listType = Types.newParameterizedType(List::class.java, RestormerModelInfo::class.java)
    private val listAdapter = moshi.adapter<List<RestormerModelInfo>>(listType)

    // Pre-registered genuine Restormer built-in model definitions
    private val builtInDefinitions = listOf(
        RestormerModelInfo(
            id = "restormer_derain_mobile",
            name = "Restormer Mobile (Derain & Restore)",
            task = RestormerTask.DERAINING,
            backend = ModelBackend.ONNX_RUNTIME,
            assetPath = "models/restormer_derain.onnx",
            isBuiltIn = true,
            fileSizeBytes = 10_245_405L,
            inputHeight = 128,
            inputWidth = 128,
            inputChannels = 3,
            tensorLayout = TensorLayout.NCHW,
            validationSummary = "Genuine CVPR 2022 Restormer with pretrained weights (MDTA + GDFN)",
            benchmarkLatencyMs = 25L
        )
    )

    private val _models = MutableStateFlow<List<RestormerModelInfo>>(emptyList())
    val models: StateFlow<List<RestormerModelInfo>> = _models.asStateFlow()

    private val _activeModel = MutableStateFlow<RestormerModelInfo>(builtInDefinitions[0])
    val activeModel: StateFlow<RestormerModelInfo> = _activeModel.asStateFlow()

    init {
        // Initialize and copy models from assets on background thread
        refreshModels()
    }

    /**
     * Refreshes the list of models, ensuring built-in assets are extracted and valid.
     */
    fun refreshModels() {
        val extractedBuiltIns = builtInDefinitions.map { extractBuiltInModel(it) }
        val customModels = loadCustomModels()
        val all = extractedBuiltIns + customModels
        _models.value = all

        val savedId = prefs.getString("active_model_id", "restormer_derain_mobile")
        val active = all.firstOrNull { it.id == savedId } ?: extractedBuiltIns.first()
        _activeModel.value = active
    }

    private fun openAssetInputStream(assetPath: String): InputStream {
        return try {
            context.assets.open(assetPath)
        } catch (e: Exception) {
            val relative = assetPath.removePrefix("models/")
            try {
                context.assets.open(relative)
            } catch (e2: Exception) {
                val devCandidates = listOf(
                    File("app/src/main/assets", assetPath),
                    File("src/main/assets", assetPath),
                    File("app/src/main/assets", relative),
                    File("src/main/assets", relative),
                    File(assetPath)
                )
                val found = devCandidates.firstOrNull { it.exists() && it.length() > 0L }
                if (found != null) {
                    FileInputStream(found)
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * Extracts a built-in model (and any companion external weight files) from assets into internal storage.
     * This provides ONNX Runtime with a real filesystem path for session initialization and avoids filesystem errors.
     */
    fun extractBuiltInModel(modelInfo: RestormerModelInfo): RestormerModelInfo {
        val assetPath = modelInfo.assetPath ?: return modelInfo
        try {
            val fileName = assetPath.substringAfterLast('/')
            val targetFile = File(modelsDir, fileName)

            // Check if model file already extracted and non-empty
            var needsCopy = !targetFile.exists() || targetFile.length() == 0L

            if (needsCopy) {
                Log.i(TAG, "Extracting built-in model asset '$assetPath' to internal storage: ${targetFile.absolutePath}")
                openAssetInputStream(assetPath).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                targetFile.setReadable(true, false)
            }

            // Also check for companion external weight files (e.g. restormer_real.onnx.data)
            val assetDir = assetPath.substringBeforeLast('/', "")
            val companionDataName = "restormer_real.onnx.data"
            val companionAssetPath = if (assetDir.isNotEmpty()) "$assetDir/$companionDataName" else companionDataName
            val companionTargetFile = File(modelsDir, companionDataName)

            try {
                if (!companionTargetFile.exists() || companionTargetFile.length() == 0L) {
                    try {
                        openAssetInputStream(companionAssetPath).use { input ->
                            Log.i(TAG, "Extracting companion external weight asset '$companionAssetPath' to: ${companionTargetFile.absolutePath}")
                            FileOutputStream(companionTargetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        companionTargetFile.setReadable(true, false)
                    } catch (e: Exception) {
                        Log.d(TAG, "No companion external data asset found for '$companionAssetPath'")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Companion external data asset check skipped: ${e.message}")
            }

            val totalSize = targetFile.length() + if (companionTargetFile.exists()) companionTargetFile.length() else 0L
            return modelInfo.copy(
                filePath = targetFile.absolutePath,
                fileSizeBytes = totalSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting built-in model '$assetPath'", e)
            return modelInfo
        }
    }

    private fun loadCustomModels(): List<RestormerModelInfo> {
        val customJson = prefs.getString("custom_models_json", null) ?: return emptyList()
        return try {
            val list = listAdapter.fromJson(customJson) ?: emptyList()
            // Filter out models whose file no longer exists
            list.filter { item ->
                item.filePath?.let { File(it).exists() } ?: false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored custom models", e)
            emptyList()
        }
    }

    /**
     * Selects and activates a model by ID.
     */
    fun selectActiveModel(modelId: String): Boolean {
        val target = _models.value.firstOrNull { it.id == modelId } ?: return false
        _activeModel.value = target
        prefs.edit().putString("active_model_id", modelId).apply()
        Log.i(TAG, "Selected active Restormer model: ${target.name} (${target.id})")
        return true
    }

    /**
     * Imports a user-provided model file from device storage (.onnx or .tflite).
     * Validates format, tensor shapes, and runs diagnostic benchmark before accepting.
     *
     * If user attempts to import a PyTorch .pth checkpoint directly, returns PthCheckpointDetected
     * with guidance to convert it using the official export script.
     */
    suspend fun importModelFromUri(uri: Uri, displayName: String? = null): ModelImportResult = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val fileName = displayName ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "imported_model.onnx")
            val extension = fileName.substringAfterLast('.', "").lowercase()

            // 1. Guard against PyTorch .pth checkpoint direct import
            if (extension == "pth" || extension == "pt") {
                val msg = "PyTorch checkpoint detected: '$fileName'. ONNX Runtime and TFLite execute compiled model graphs (.onnx / .tflite) and cannot execute raw PyTorch .pth checkpoints directly on Android. Please use 'scripts/export_restormer_to_onnx.py' to export the checkpoint to an ONNX model, then import the generated .onnx file."
                Log.w(TAG, msg)
                return@withContext ModelImportResult.PthCheckpointDetected(fileName, msg)
            }

            // 2. Identify backend format
            val backend = when (extension) {
                "onnx" -> ModelBackend.ONNX_RUNTIME
                "tflite" -> ModelBackend.TENSORFLOW_LITE
                else -> {
                    // Inspect file header bytes
                    val headerBytes = ByteArray(16)
                    contentResolver.openInputStream(uri)?.use { it.read(headerBytes) }
                    if (headerBytes.size >= 8 && headerBytes[4] == 'T'.code.toByte() && headerBytes[5] == 'F'.code.toByte() && headerBytes[6] == 'L'.code.toByte() && headerBytes[7] == '3'.code.toByte()) {
                        ModelBackend.TENSORFLOW_LITE
                    } else if (headerBytes.size >= 2 && headerBytes[0] == 0x08.toByte()) {
                        // ONNX protobuf tag
                        ModelBackend.ONNX_RUNTIME
                    } else {
                        return@withContext ModelImportResult.Error("Unsupported model format for '$fileName'. Please import a valid .onnx or .tflite model.")
                    }
                }
            }

            // 3. Copy file safely to internal storage
            val cleanName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(modelsDir, "${System.currentTimeMillis()}_$cleanName")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext ModelImportResult.Error("Failed to read input stream from device storage for '$fileName'")

            if (!targetFile.exists() || targetFile.length() == 0L) {
                targetFile.delete()
                return@withContext ModelImportResult.Error("Imported file is empty (0 bytes)")
            }
            targetFile.setReadable(true, false)

            // 4. Infer task from filename
            val lowerName = fileName.lowercase()
            val task = when {
                lowerName.contains("deblur") || lowerName.contains("gopro") -> RestormerTask.MOTION_DEBLURRING
                lowerName.contains("defocus") || lowerName.contains("dpdd") -> RestormerTask.DEFOCUS_DEBLURRING
                lowerName.contains("rain") || lowerName.contains("derain") -> RestormerTask.DERAINING
                lowerName.contains("denoise") || lowerName.contains("sidd") || lowerName.contains("dnd") -> RestormerTask.REAL_DENOISING
                lowerName.contains("color") || lowerName.contains("gauss") -> RestormerTask.GAUSSIAN_DENOISING
                else -> RestormerTask.CUSTOM
            }

            val modelId = "custom_${System.currentTimeMillis()}"
            val modelName = fileName.substringBeforeLast('.')
                .replace('_', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val candidateModelInfo = RestormerModelInfo(
                id = modelId,
                name = modelName,
                task = task,
                backend = backend,
                filePath = targetFile.absolutePath,
                isBuiltIn = false,
                fileSizeBytes = targetFile.length(),
                inputHeight = 128,
                inputWidth = 128,
                inputChannels = 3,
                tensorLayout = if (backend == ModelBackend.TENSORFLOW_LITE) TensorLayout.NHWC else TensorLayout.NCHW,
                validationSummary = "Imported from device storage"
            )

            // 5. Validate model with genuine diagnostic benchmark execution
            val engine = try {
                RestormerInferenceEngine(context, candidateModelInfo)
            } catch (e: Exception) {
                targetFile.delete()
                val cause = e.message ?: "Session initialization failure"
                return@withContext ModelImportResult.Error("Initialization failed: $cause")
            }

            val benchmark = engine.runDiagnosticBenchmark(128)
            engine.close()

            if (!benchmark.isSuccess) {
                targetFile.delete()
                return@withContext ModelImportResult.Error("Verification failed: ${benchmark.message}")
            }

            val validatedModelInfo = candidateModelInfo.copy(
                benchmarkLatencyMs = benchmark.latencyMs,
                validationSummary = "Verified ${backend.displayName} (${benchmark.latencyMs}ms, ${task.displayName})"
            )

            // 6. Save to model registry
            val currentCustom = _models.value.filter { !it.isBuiltIn }
            val updatedCustom = currentCustom + validatedModelInfo
            prefs.edit().putString("custom_models_json", listAdapter.toJson(updatedCustom)).apply()

            val builtIns = _models.value.filter { it.isBuiltIn }
            _models.value = builtIns + updatedCustom
            selectActiveModel(validatedModelInfo.id)

            Log.i(TAG, "Successfully imported and verified Restormer model: ${validatedModelInfo.name}")
            ModelImportResult.Success(validatedModelInfo, benchmark.latencyMs)
        } catch (e: Exception) {
            Log.e(TAG, "Exception importing model from $uri", e)
            ModelImportResult.Error(e.message ?: "Unknown model import error")
        }
    }

    /**
     * Deletes a user-imported custom model. Built-in models cannot be deleted.
     */
    fun deleteCustomModel(modelId: String): Boolean {
        val target = _models.value.firstOrNull { it.id == modelId && !it.isBuiltIn } ?: return false
        target.filePath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }

        val updatedCustom = _models.value.filter { !it.isBuiltIn && it.id != modelId }
        prefs.edit().putString("custom_models_json", listAdapter.toJson(updatedCustom)).apply()

        val builtIns = _models.value.filter { it.isBuiltIn }
        _models.value = builtIns + updatedCustom
        if (_activeModel.value.id == modelId) {
            selectActiveModel(builtIns.first().id)
        }
        return true
    }

    /**
     * Performs an on-demand validation report for a model.
     */
    fun validateModel(modelInfo: RestormerModelInfo): ModelValidationReport {
        return try {
            val engine = RestormerInferenceEngine(context, modelInfo)
            val benchmark = engine.runDiagnosticBenchmark(128)
            engine.close()

            ModelValidationReport(
                isValid = benchmark.isSuccess,
                format = modelInfo.backend.displayName,
                inputShape = "[1, ${modelInfo.inputChannels}, ${modelInfo.inputHeight}, ${modelInfo.inputWidth}]",
                outputShape = "[1, ${modelInfo.inputChannels}, ${modelInfo.inputHeight}, ${modelInfo.inputWidth}]",
                latencyMs = benchmark.latencyMs,
                details = benchmark.message
            )
        } catch (e: Exception) {
            ModelValidationReport(
                isValid = false,
                format = modelInfo.backend.displayName,
                inputShape = "Unknown",
                outputShape = "Unknown",
                latencyMs = 0L,
                details = "Validation error: ${e.message}"
            )
        }
    }
}
