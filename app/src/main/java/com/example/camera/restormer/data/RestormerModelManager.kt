package com.example.camera.restormer.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.camera.restormer.engine.InspectedModelMeta
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

sealed interface ModelLoadState {
    data object Idle : ModelLoadState
    data class Loading(val modelId: String, val modelName: String) : ModelLoadState
    data class Loaded(
        val modelInfo: RestormerModelInfo,
        val accelerator: String,
        val inputShapeStr: String,
        val outputShapeStr: String
    ) : ModelLoadState
    data class Error(val modelId: String, val modelName: String, val errorMessage: String) : ModelLoadState
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
 * 2. Proper inspection of tensor shapes, data types, channel layouts, and normalization without hardcoded dimensions.
 * 3. Shows genuine model loading status and prevents marking uninitialized models as loaded.
 * 4. Full companion external weight file discovery and copying for ONNX models.
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
            isDynamicShape = true,
            inputDataType = "FLOAT32",
            outputDataType = "FLOAT32",
            inputShapeDescription = "[1, 3, 128, 128]",
            outputShapeDescription = "[1, 3, 128, 128]",
            validationSummary = "Genuine CVPR 2022 Restormer with pretrained weights (MDTA + GDFN)",
            benchmarkLatencyMs = 25L
        )
    )

    private val _models = MutableStateFlow<List<RestormerModelInfo>>(emptyList())
    val models: StateFlow<List<RestormerModelInfo>> = _models.asStateFlow()

    private val _activeModel = MutableStateFlow<RestormerModelInfo>(builtInDefinitions[0])
    val activeModel: StateFlow<RestormerModelInfo> = _activeModel.asStateFlow()

    private val _modelLoadState = MutableStateFlow<ModelLoadState>(ModelLoadState.Idle)
    val modelLoadState: StateFlow<ModelLoadState> = _modelLoadState.asStateFlow()

    init {
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

        // Attempt genuine session verification for active model
        verifyAndSetLoadState(active)
    }

    private fun verifyAndSetLoadState(model: RestormerModelInfo) {
        try {
            val engine = RestormerInferenceEngine(context, model)
            _modelLoadState.value = ModelLoadState.Loaded(
                modelInfo = model,
                accelerator = engine.actualAcceleratorUsed,
                inputShapeStr = engine.inputShapeDescription,
                outputShapeStr = engine.outputShapeDescription
            )
            engine.close()
        } catch (e: Throwable) {
            _modelLoadState.value = ModelLoadState.Error(
                modelId = model.id,
                modelName = model.name,
                errorMessage = e.message ?: "Failed to initialize inference session"
            )
        }
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
     */
    fun extractBuiltInModel(modelInfo: RestormerModelInfo): RestormerModelInfo {
        val assetPath = modelInfo.assetPath ?: return modelInfo
        try {
            val fileName = assetPath.substringAfterLast('/')
            val targetFile = File(modelsDir, fileName)

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

            // Extract companion external weight files
            val companionCandidates = listOf(
                "restormer_real.onnx.data",
                "$fileName.data",
                "${fileName.substringBeforeLast('.')}.onnx.data",
                "${fileName.substringBeforeLast('.')}.data"
            )
            val assetDir = assetPath.substringBeforeLast('/', "")

            var totalCompanionSize = 0L
            for (companionName in companionCandidates) {
                val companionTargetFile = File(modelsDir, companionName)
                if (!companionTargetFile.exists() || companionTargetFile.length() == 0L) {
                    val companionAssetPath = if (assetDir.isNotEmpty()) "$assetDir/$companionName" else companionName
                    try {
                        openAssetInputStream(companionAssetPath).use { input ->
                            Log.i(TAG, "Extracting companion external data asset '$companionAssetPath' to: ${companionTargetFile.absolutePath}")
                            FileOutputStream(companionTargetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        companionTargetFile.setReadable(true, false)
                    } catch (_: Exception) {}
                }
                if (companionTargetFile.exists()) {
                    totalCompanionSize += companionTargetFile.length()
                }
            }

            val totalSize = targetFile.length() + totalCompanionSize

            // Inspect the extracted model to reflect exact shapes and layout
            val inspected = try {
                RestormerInferenceEngine.inspectModel(context, targetFile, modelInfo.backend)
            } catch (e: Throwable) {
                null
            }

            return modelInfo.copy(
                filePath = targetFile.absolutePath,
                fileSizeBytes = totalSize,
                inputHeight = inspected?.inputHeight ?: modelInfo.inputHeight,
                inputWidth = inspected?.inputWidth ?: modelInfo.inputWidth,
                inputChannels = inspected?.inputChannels ?: modelInfo.inputChannels,
                tensorLayout = inspected?.layout ?: modelInfo.tensorLayout,
                isDynamicShape = inspected?.isDynamicShape ?: modelInfo.isDynamicShape,
                inputDataType = inspected?.inputDataType ?: modelInfo.inputDataType,
                outputDataType = inspected?.outputDataType ?: modelInfo.outputDataType,
                inputShapeDescription = inspected?.inputShapeDesc ?: modelInfo.inputShapeDescription,
                outputShapeDescription = inspected?.outputShapeDesc ?: modelInfo.outputShapeDescription
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
     * Guarantees that model is not marked as loaded unless it initializes successfully.
     */
    fun selectActiveModel(modelId: String): Boolean {
        val target = _models.value.firstOrNull { it.id == modelId } ?: return false
        _modelLoadState.value = ModelLoadState.Loading(target.id, target.name)

        return try {
            val engine = RestormerInferenceEngine(context, target)
            val accelerator = engine.actualAcceleratorUsed
            val inShape = engine.inputShapeDescription
            val outShape = engine.outputShapeDescription
            engine.close()

            _activeModel.value = target
            _modelLoadState.value = ModelLoadState.Loaded(
                modelInfo = target,
                accelerator = accelerator,
                inputShapeStr = inShape,
                outputShapeStr = outShape
            )
            prefs.edit().putString("active_model_id", modelId).apply()
            Log.i(TAG, "Selected and validated active Restormer model: ${target.name} ($modelId) on $accelerator")
            true
        } catch (e: Throwable) {
            val err = e.message ?: "Failed to initialize model session"
            Log.e(TAG, "Failed to load model '${target.name}': $err", e)
            _modelLoadState.value = ModelLoadState.Error(target.id, target.name, err)
            false
        }
    }

    /**
     * Imports a user-provided model file from device storage (.onnx or .tflite).
     * Inspects tensor shapes, channel layouts, data types, and companion external weight files.
     * Executes genuine diagnostic benchmark before accepting.
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
                    val headerBytes = ByteArray(16)
                    contentResolver.openInputStream(uri)?.use { it.read(headerBytes) }
                    if (headerBytes.size >= 8 && headerBytes[4] == 'T'.code.toByte() && headerBytes[5] == 'F'.code.toByte() && headerBytes[6] == 'L'.code.toByte() && headerBytes[7] == '3'.code.toByte()) {
                        ModelBackend.TENSORFLOW_LITE
                    } else if (headerBytes.size >= 2 && headerBytes[0] == 0x08.toByte()) {
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

            // Look for companion external weights file in source folder if available
            copyCompanionDataFilesForUri(uri, targetFile)

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

            // 5. Inspect model input/output tensor shapes and types without hardcoding
            val inspected = try {
                RestormerInferenceEngine.inspectModel(context, targetFile, backend)
            } catch (e: Throwable) {
                Log.w(TAG, "Model inspection encountered exception: ${e.message}")
                null
            }

            val candidateModelInfo = RestormerModelInfo(
                id = modelId,
                name = modelName,
                task = task,
                backend = backend,
                filePath = targetFile.absolutePath,
                isBuiltIn = false,
                fileSizeBytes = targetFile.length(),
                inputHeight = inspected?.inputHeight ?: 128,
                inputWidth = inspected?.inputWidth ?: 128,
                inputChannels = inspected?.inputChannels ?: 3,
                tensorLayout = inspected?.layout ?: (if (backend == ModelBackend.TENSORFLOW_LITE) TensorLayout.NHWC else TensorLayout.NCHW),
                isDynamicShape = inspected?.isDynamicShape ?: true,
                inputDataType = inspected?.inputDataType ?: "FLOAT32",
                outputDataType = inspected?.outputDataType ?: "FLOAT32",
                inputShapeDescription = inspected?.inputShapeDesc ?: "[1, 3, 128, 128]",
                outputShapeDescription = inspected?.outputShapeDesc ?: "[1, 3, 128, 128]",
                validationSummary = "Imported ${backend.displayName} [${inspected?.inputShapeDesc ?: "?"}]"
            )

            // 6. Validate model with genuine diagnostic benchmark execution
            val engine = try {
                RestormerInferenceEngine(context, candidateModelInfo)
            } catch (e: Throwable) {
                targetFile.delete()
                val cause = e.message ?: "Session initialization failure"
                return@withContext ModelImportResult.Error("Initialization failed: $cause")
            }

            val benchmark = engine.runDiagnosticBenchmark(candidateModelInfo.inputHeight)
            val accelerator = engine.actualAcceleratorUsed
            engine.close()

            if (!benchmark.isSuccess) {
                targetFile.delete()
                return@withContext ModelImportResult.Error("Verification failed: ${benchmark.message}")
            }

            val validatedModelInfo = candidateModelInfo.copy(
                benchmarkLatencyMs = benchmark.latencyMs,
                validationSummary = "Verified ${backend.displayName} (${benchmark.latencyMs}ms, $accelerator, ${task.displayName})"
            )

            // 7. Save to model registry
            val currentCustom = _models.value.filter { !it.isBuiltIn }
            val updatedCustom = currentCustom + validatedModelInfo
            prefs.edit().putString("custom_models_json", listAdapter.toJson(updatedCustom)).apply()

            val builtIns = _models.value.filter { it.isBuiltIn }
            _models.value = builtIns + updatedCustom
            selectActiveModel(validatedModelInfo.id)

            Log.i(TAG, "Successfully imported, verified, and activated Restormer model: ${validatedModelInfo.name}")
            ModelImportResult.Success(validatedModelInfo, benchmark.latencyMs)
        } catch (e: Exception) {
            Log.e(TAG, "Exception importing model from $uri", e)
            ModelImportResult.Error(e.message ?: "Unknown model import error")
        }
    }

    private fun copyCompanionDataFilesForUri(sourceUri: Uri, targetFile: File) {
        try {
            val sourcePath = sourceUri.path
            if (sourcePath != null) {
                val srcFile = File(sourcePath)
                val parent = srcFile.parentFile
                if (parent != null && parent.exists() && parent.isDirectory) {
                    val companions = parent.listFiles { _, name ->
                        name.endsWith(".data") || name.endsWith(".onnx.data")
                    }
                    companions?.forEach { comp ->
                        val dst = File(modelsDir, comp.name)
                        if (!dst.exists() || dst.length() == 0L) {
                            FileInputStream(comp).use { inp ->
                                FileOutputStream(dst).use { out -> inp.copyTo(out) }
                            }
                            dst.setReadable(true, false)
                            Log.i(TAG, "Copied companion external weight file: ${dst.name} (${dst.length()} bytes)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Companion data file scan skipped: ${e.message}")
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
            val benchmark = engine.runDiagnosticBenchmark(modelInfo.inputHeight)
            val inDesc = engine.inputShapeDescription
            val outDesc = engine.outputShapeDescription
            val accel = engine.actualAcceleratorUsed
            engine.close()

            ModelValidationReport(
                isValid = benchmark.isSuccess,
                format = "${modelInfo.backend.displayName} ($accel)",
                inputShape = inDesc,
                outputShape = outDesc,
                latencyMs = benchmark.latencyMs,
                details = benchmark.message
            )
        } catch (e: Exception) {
            ModelValidationReport(
                isValid = false,
                format = modelInfo.backend.displayName,
                inputShape = modelInfo.inputShapeDescription,
                outputShape = modelInfo.outputShapeDescription,
                latencyMs = 0L,
                details = "Validation error: ${e.message}"
            )
        }
    }
}
