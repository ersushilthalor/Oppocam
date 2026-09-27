package com.example.camera.zoom.ai

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "ZoomAiModelRepository"
private const val PREF_KEY_HAT_MODELS = "pref_zoom_ai_hat_models_json"
private const val PREF_KEY_BSRGAN_MODELS = "pref_zoom_ai_bsrgan_models_json"
private const val PREF_KEY_SELECTED_HAT_ID = "pref_zoom_ai_selected_hat_id"
private const val PREF_KEY_SELECTED_BSRGAN_ID = "pref_zoom_ai_selected_bsrgan_id"
private const val PREF_KEY_RECONSTRUCTION_MODE = "pref_zoom_reconstruction_mode"
private const val PREF_KEY_KEEP_ORIGINAL_IMAGE = "pref_zoom_keep_original_image"

/**
 * Singleton repository and state manager for Zoom Enhanced AI Reconstruction models (HAT and BSRGAN).
 *
 * Responsibilities:
 * - Separate model import, validation, persistence, and selection for HAT and BSRGAN.
 * - Keeps imported model files in internal app storage (`filesDir/zoom_ai_models/hat` & `bsrgan`)
 *   so they remain available across sessions without repeated imports.
 * - Validates architecture, format, and GPU/NPU compatibility before loading.
 * - Exposes live StateFlows for UI display: model name, file size, loading status, selected model,
 *   active GPU/NPU execution device, and clear error messages.
 */
class ZoomAiModelRepository private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: ZoomAiModelRepository? = null

        fun getInstance(context: Context): ZoomAiModelRepository {
            return instance ?: synchronized(this) {
                instance ?: ZoomAiModelRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences("pro_camera_user_prefs", Context.MODE_PRIVATE)
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val hatDir = File(context.filesDir, "zoom_ai_models/hat").apply { mkdirs() }
    private val bsrganDir = File(context.filesDir, "zoom_ai_models/bsrgan").apply { mkdirs() }

    val hatEngine = HatBsrganInferenceEngine(context)
    val bsrganEngine = HatBsrganInferenceEngine(context)

    private val _reconstructionMode = MutableStateFlow(loadSavedReconstructionMode())
    val reconstructionMode: StateFlow<ZoomReconstructionMode> = _reconstructionMode.asStateFlow()

    private val _keepOriginalImage = MutableStateFlow(prefs.getBoolean(PREF_KEY_KEEP_ORIGINAL_IMAGE, true))
    val keepOriginalImage: StateFlow<Boolean> = _keepOriginalImage.asStateFlow()

    private val _importedHatModels = MutableStateFlow<List<ImportedZoomAiModel>>(emptyList())
    val importedHatModels: StateFlow<List<ImportedZoomAiModel>> = _importedHatModels.asStateFlow()

    private val _importedBsrganModels = MutableStateFlow<List<ImportedZoomAiModel>>(emptyList())
    val importedBsrganModels: StateFlow<List<ImportedZoomAiModel>> = _importedBsrganModels.asStateFlow()

    private val _selectedHatModel = MutableStateFlow<ImportedZoomAiModel?>(null)
    val selectedHatModel: StateFlow<ImportedZoomAiModel?> = _selectedHatModel.asStateFlow()

    private val _selectedBsrganModel = MutableStateFlow<ImportedZoomAiModel?>(null)
    val selectedBsrganModel: StateFlow<ImportedZoomAiModel?> = _selectedBsrganModel.asStateFlow()

    private val _hatLoadingStatus = MutableStateFlow(ZoomAiLoadingStatus.NOT_IMPORTED)
    val hatLoadingStatus: StateFlow<ZoomAiLoadingStatus> = _hatLoadingStatus.asStateFlow()

    private val _bsrganLoadingStatus = MutableStateFlow(ZoomAiLoadingStatus.NOT_IMPORTED)
    val bsrganLoadingStatus: StateFlow<ZoomAiLoadingStatus> = _bsrganLoadingStatus.asStateFlow()

    private val _hatErrorMessage = MutableStateFlow<String?>(null)
    val hatErrorMessage: StateFlow<String?> = _hatErrorMessage.asStateFlow()

    private val _bsrganErrorMessage = MutableStateFlow<String?>(null)
    val bsrganErrorMessage: StateFlow<String?> = _bsrganErrorMessage.asStateFlow()

    private val _hardwareStatus = MutableStateFlow(ZoomAiHardwareStatus())
    val hardwareStatus: StateFlow<ZoomAiHardwareStatus> = _hardwareStatus.asStateFlow()

    private val _lastReconstructionSummary = MutableStateFlow<String?>(null)
    val lastReconstructionSummary: StateFlow<String?> = _lastReconstructionSummary.asStateFlow()

    init {
        loadSavedModelsFromPrefs()
        repoScope.launch {
            refreshHardwareCapabilities()
            // Automatically warm up selected models onto GPU/NPU if previously imported
            _selectedHatModel.value?.let { warmUpModelInternal(it) }
            _selectedBsrganModel.value?.let { warmUpModelInternal(it) }
        }
    }

    fun refreshHardwareCapabilities(): ZoomAiHardwareStatus {
        val status = hatEngine.gpuComputeExecutor.detectHardwareCapabilities()
        _hardwareStatus.value = status
        return status
    }

    fun setReconstructionMode(mode: ZoomReconstructionMode) {
        _reconstructionMode.value = mode
        prefs.edit().putString(PREF_KEY_RECONSTRUCTION_MODE, mode.name).apply()
    }

    fun setKeepOriginalImage(keep: Boolean) {
        _keepOriginalImage.value = keep
        prefs.edit().putBoolean(PREF_KEY_KEEP_ORIGINAL_IMAGE, keep).apply()
    }

    fun clearErrorMessage(architecture: ZoomAiModelArchitecture) {
        if (architecture == ZoomAiModelArchitecture.HAT) {
            _hatErrorMessage.value = null
        } else {
            _bsrganErrorMessage.value = null
        }
    }

    /**
     * Imports a HAT or BSRGAN model file selected by the user from device storage via [uri].
     * Validates format, architecture, and GPU/NPU compatibility before activating.
     */
    suspend fun importModelFromUri(
        uri: Uri,
        targetArchitecture: ZoomAiModelArchitecture
    ): Result<ImportedZoomAiModel> = withContext(Dispatchers.IO) {
        setStatus(targetArchitecture, ZoomAiLoadingStatus.VALIDATING, null)

        val resolvedDisplayName = resolveFileName(uri)
            ?: "${targetArchitecture.name}_model_${System.currentTimeMillis()}.pth"

        val targetDir = if (targetArchitecture == ZoomAiModelArchitecture.HAT) hatDir else bsrganDir
        val sanitizedName = resolvedDisplayName.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        val stagedFile = File(targetDir, "${System.currentTimeMillis()}_$sanitizedName")

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IncompatibleModelException("Could not open selected file from device storage.")

            inputStream.use { input ->
                stagedFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            importStagedFileInternal(stagedFile, resolvedDisplayName, targetArchitecture)
        } catch (e: Throwable) {
            try { stagedFile.delete() } catch (_: Throwable) {}
            val errMsg = e.message ?: "Failed to import ${targetArchitecture.displayName} model."
            Log.e(TAG, "Model import failed for $targetArchitecture: $errMsg", e)
            setStatus(
                targetArchitecture,
                if (getModelsFor(targetArchitecture).isEmpty()) ZoomAiLoadingStatus.ERROR else ZoomAiLoadingStatus.ERROR,
                errMsg
            )
            Result.failure(e)
        }
    }

    /**
     * Imports a model directly from a local [file] (used by file import or weight preparation).
     */
    suspend fun importModelFromFile(
        file: File,
        displayName: String = file.name,
        targetArchitecture: ZoomAiModelArchitecture
    ): Result<ImportedZoomAiModel> = withContext(Dispatchers.IO) {
        setStatus(targetArchitecture, ZoomAiLoadingStatus.VALIDATING, null)
        val targetDir = if (targetArchitecture == ZoomAiModelArchitecture.HAT) hatDir else bsrganDir
        val destFile = if (file.parentFile?.absolutePath == targetDir.absolutePath) {
            file
        } else {
            val copied = File(targetDir, "${System.currentTimeMillis()}_${file.name}")
            file.copyTo(copied, overwrite = true)
            copied
        }

        try {
            importStagedFileInternal(destFile, displayName, targetArchitecture)
        } catch (e: Throwable) {
            if (destFile != file) {
                try { destFile.delete() } catch (_: Throwable) {}
            }
            val errMsg = e.message ?: "Incompatible ${targetArchitecture.displayName} model file."
            setStatus(targetArchitecture, ZoomAiLoadingStatus.ERROR, errMsg)
            Result.failure(e)
        }
    }

    /**
     * Prepares and imports a compatible pretrained architecture weight file (`.pth` or `.safetensors`)
     * matching the official `XPixelGroup/HAT` or `cszn/BSRGAN` specification into storage.
     */
    suspend fun preparePretrainedArchitectureModel(
        architecture: ZoomAiModelArchitecture,
        scaleFactor: Int = 2,
        useSafetensors: Boolean = false
    ): Result<ImportedZoomAiModel> = withContext(Dispatchers.IO) {
        setStatus(architecture, ZoomAiLoadingStatus.VALIDATING, null)
        try {
            val targetDir = if (architecture == ZoomAiModelArchitecture.HAT) hatDir else bsrganDir
            val ext = if (useSafetensors) "safetensors" else "pth"
            val officialFileName = when (architecture) {
                ZoomAiModelArchitecture.HAT -> "HAT_SRx${scaleFactor}_ImageNet-pretrain.$ext"
                ZoomAiModelArchitecture.BSRGAN -> if (scaleFactor == 2) "BSRGANx2.$ext" else "BSRGAN.$ext"
            }
            val outFile = File(targetDir, officialFileName)
            ZoomAiWeightParser.exportPretrainedWeightsFile(outFile, architecture, scaleFactor)
            importStagedFileInternal(outFile, officialFileName, architecture)
        } catch (e: Throwable) {
            val errMsg = e.message ?: "Failed to prepare ${architecture.displayName} weights."
            setStatus(architecture, ZoomAiLoadingStatus.ERROR, errMsg)
            Result.failure(e)
        }
    }

    private fun importStagedFileInternal(
        stagedFile: File,
        displayName: String,
        targetArchitecture: ZoomAiModelArchitecture
    ): Result<ImportedZoomAiModel> {
        // 1. Validate architecture, format, and tensor shapes
        val validatedModel = ZoomAiModelValidator.validateModelFile(
            file = stagedFile,
            displayName = displayName,
            expectedArchitecture = targetArchitecture
        )

        // 2. Load onto GPU / NPU accelerator (strictly disallowing silent CPU fallback)
        setStatus(targetArchitecture, ZoomAiLoadingStatus.LOADING_ACCELERATOR, null)
        val engine = if (targetArchitecture == ZoomAiModelArchitecture.HAT) hatEngine else bsrganEngine
        val hwStatus = engine.loadModelToAccelerator(validatedModel)
        _hardwareStatus.value = hwStatus

        // 3. Persist in repository list & set as active model for this architecture
        if (targetArchitecture == ZoomAiModelArchitecture.HAT) {
            val updated = (_importedHatModels.value.filterNot { it.name == validatedModel.name } + validatedModel)
            _importedHatModels.value = updated
            _selectedHatModel.value = validatedModel
            prefs.edit().putString(PREF_KEY_SELECTED_HAT_ID, validatedModel.id).apply()
            persistModelList(PREF_KEY_HAT_MODELS, updated)
            _hatLoadingStatus.value = ZoomAiLoadingStatus.LOADED_READY
            _hatErrorMessage.value = null
        } else {
            val updated = (_importedBsrganModels.value.filterNot { it.name == validatedModel.name } + validatedModel)
            _importedBsrganModels.value = updated
            _selectedBsrganModel.value = validatedModel
            prefs.edit().putString(PREF_KEY_SELECTED_BSRGAN_ID, validatedModel.id).apply()
            persistModelList(PREF_KEY_BSRGAN_MODELS, updated)
            _bsrganLoadingStatus.value = ZoomAiLoadingStatus.LOADED_READY
            _bsrganErrorMessage.value = null
        }

        return Result.success(validatedModel)
    }

    /**
     * Selects a previously imported model for [architecture] and loads its weights onto the GPU/NPU.
     */
    suspend fun selectModel(
        architecture: ZoomAiModelArchitecture,
        modelId: String
    ): Result<ImportedZoomAiModel> = withContext(Dispatchers.IO) {
        val list = getModelsFor(architecture)
        val found = list.find { it.id == modelId }
            ?: return@withContext Result.failure(IncompatibleModelException("Selected model not found."))

        try {
            setStatus(architecture, ZoomAiLoadingStatus.LOADING_ACCELERATOR, null)
            val engine = if (architecture == ZoomAiModelArchitecture.HAT) hatEngine else bsrganEngine
            val hwStatus = engine.loadModelToAccelerator(found)
            _hardwareStatus.value = hwStatus

            if (architecture == ZoomAiModelArchitecture.HAT) {
                _selectedHatModel.value = found
                prefs.edit().putString(PREF_KEY_SELECTED_HAT_ID, found.id).apply()
                _hatLoadingStatus.value = ZoomAiLoadingStatus.LOADED_READY
                _hatErrorMessage.value = null
            } else {
                _selectedBsrganModel.value = found
                prefs.edit().putString(PREF_KEY_SELECTED_BSRGAN_ID, found.id).apply()
                _bsrganLoadingStatus.value = ZoomAiLoadingStatus.LOADED_READY
                _bsrganErrorMessage.value = null
            }
            Result.success(found)
        } catch (e: Throwable) {
            val msg = e.message ?: "Failed to load model onto GPU/NPU."
            setStatus(architecture, ZoomAiLoadingStatus.ERROR, msg)
            Result.failure(e)
        }
    }

    /**
     * Deletes an imported model from storage and updates selection state.
     */
    fun deleteModel(architecture: ZoomAiModelArchitecture, modelId: String) {
        if (architecture == ZoomAiModelArchitecture.HAT) {
            val target = _importedHatModels.value.find { it.id == modelId }
            if (target != null) {
                try { File(target.filePath).delete() } catch (_: Throwable) {}
            }
            val remaining = _importedHatModels.value.filterNot { it.id == modelId }
            _importedHatModels.value = remaining
            persistModelList(PREF_KEY_HAT_MODELS, remaining)
            if (_selectedHatModel.value?.id == modelId) {
                val next = remaining.lastOrNull()
                _selectedHatModel.value = next
                prefs.edit().putString(PREF_KEY_SELECTED_HAT_ID, next?.id ?: "").apply()
                if (next == null) {
                    hatEngine.closeActiveSession()
                    _hatLoadingStatus.value = ZoomAiLoadingStatus.NOT_IMPORTED
                } else {
                    repoScope.launch { warmUpModelInternal(next) }
                }
            }
        } else {
            val target = _importedBsrganModels.value.find { it.id == modelId }
            if (target != null) {
                try { File(target.filePath).delete() } catch (_: Throwable) {}
            }
            val remaining = _importedBsrganModels.value.filterNot { it.id == modelId }
            _importedBsrganModels.value = remaining
            persistModelList(PREF_KEY_BSRGAN_MODELS, remaining)
            if (_selectedBsrganModel.value?.id == modelId) {
                val next = remaining.lastOrNull()
                _selectedBsrganModel.value = next
                prefs.edit().putString(PREF_KEY_SELECTED_BSRGAN_ID, next?.id ?: "").apply()
                if (next == null) {
                    bsrganEngine.closeActiveSession()
                    _bsrganLoadingStatus.value = ZoomAiLoadingStatus.NOT_IMPORTED
                } else {
                    repoScope.launch { warmUpModelInternal(next) }
                }
            }
        }
    }

    /**
     * Runs genuine GPU/NPU AI reconstruction using the currently selected HAT or BSRGAN model.
     */
    suspend fun reconstructWithSelectedAiModel(
        inputBitmap: android.graphics.Bitmap,
        zoomRatio: Float,
        architecture: ZoomAiModelArchitecture,
        onProgress: (Float) -> Unit
    ): ZoomAiReconstructionResult {
        val selectedModel = if (architecture == ZoomAiModelArchitecture.HAT) {
            _selectedHatModel.value
        } else {
            _selectedBsrganModel.value
        } ?: throw IncompatibleModelException(
            "No ${architecture.displayName} model is loaded. Please import a ${architecture.name} model in Zoom Enhanced settings."
        )

        setStatus(architecture, ZoomAiLoadingStatus.RECONSTRUCTING, null)
        try {
            val engine = if (architecture == ZoomAiModelArchitecture.HAT) hatEngine else bsrganEngine
            val result = engine.reconstructZoomedImage(
                inputBitmap = inputBitmap,
                zoomRatio = zoomRatio,
                model = selectedModel,
                onProgress = onProgress
            )
            setStatus(architecture, ZoomAiLoadingStatus.LOADED_READY, null)
            _lastReconstructionSummary.value =
                "${architecture.name} (${selectedModel.variantName}) · ${result.outputWidth}x${result.outputHeight} · " +
                    "${result.executionProvider.shortLabel} · ${result.inferenceTimeMs}ms"
            return result
        } catch (e: Throwable) {
            val msg = e.message ?: "AI reconstruction failed on GPU/NPU."
            setStatus(architecture, ZoomAiLoadingStatus.ERROR, msg)
            throw e
        }
    }

    private fun warmUpModelInternal(model: ImportedZoomAiModel) {
        try {
            val engine = if (model.architecture == ZoomAiModelArchitecture.HAT) hatEngine else bsrganEngine
            val hw = engine.loadModelToAccelerator(model)
            _hardwareStatus.value = hw
            setStatus(model.architecture, ZoomAiLoadingStatus.LOADED_READY, null)
        } catch (e: Throwable) {
            setStatus(model.architecture, ZoomAiLoadingStatus.ERROR, e.message)
        }
    }

    private fun getModelsFor(architecture: ZoomAiModelArchitecture): List<ImportedZoomAiModel> {
        return if (architecture == ZoomAiModelArchitecture.HAT) {
            _importedHatModels.value
        } else {
            _importedBsrganModels.value
        }
    }

    private fun setStatus(
        architecture: ZoomAiModelArchitecture,
        status: ZoomAiLoadingStatus,
        errorMsg: String?
    ) {
        if (architecture == ZoomAiModelArchitecture.HAT) {
            _hatLoadingStatus.value = status
            _hatErrorMessage.value = errorMsg
        } else {
            _bsrganLoadingStatus.value = status
            _bsrganErrorMessage.value = errorMsg
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        return try {
            var name: String? = null
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        name = it.getString(idx)
                    }
                }
            }
            name ?: uri.lastPathSegment?.substringAfterLast('/')
        } catch (_: Throwable) {
            uri.lastPathSegment?.substringAfterLast('/')
        }
    }

    private fun loadSavedReconstructionMode(): ZoomReconstructionMode {
        val raw = prefs.getString(PREF_KEY_RECONSTRUCTION_MODE, ZoomReconstructionMode.TRADITIONAL.name)
            ?: ZoomReconstructionMode.TRADITIONAL.name
        return try {
            ZoomReconstructionMode.valueOf(raw)
        } catch (_: Exception) {
            ZoomReconstructionMode.TRADITIONAL
        }
    }

    private fun loadSavedModelsFromPrefs() {
        val hatList = parseModelListJson(prefs.getString(PREF_KEY_HAT_MODELS, null))
        val bsrganList = parseModelListJson(prefs.getString(PREF_KEY_BSRGAN_MODELS, null))

        _importedHatModels.value = hatList
        _importedBsrganModels.value = bsrganList

        val savedHatId = prefs.getString(PREF_KEY_SELECTED_HAT_ID, null)
        val savedBsrganId = prefs.getString(PREF_KEY_SELECTED_BSRGAN_ID, null)

        _selectedHatModel.value = hatList.find { it.id == savedHatId } ?: hatList.lastOrNull()
        _selectedBsrganModel.value = bsrganList.find { it.id == savedBsrganId } ?: bsrganList.lastOrNull()

        _hatLoadingStatus.value = if (_selectedHatModel.value != null) {
            ZoomAiLoadingStatus.LOADED_READY
        } else {
            ZoomAiLoadingStatus.NOT_IMPORTED
        }
        _bsrganLoadingStatus.value = if (_selectedBsrganModel.value != null) {
            ZoomAiLoadingStatus.LOADED_READY
        } else {
            ZoomAiLoadingStatus.NOT_IMPORTED
        }
    }

    private fun persistModelList(prefKey: String, models: List<ImportedZoomAiModel>) {
        val arr = JSONArray()
        for (m in models) {
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                    put("architecture", m.architecture.name)
                    put("variantName", m.variantName)
                    put("format", m.format.name)
                    put("filePath", m.filePath)
                    put("fileSizeBytes", m.fileSizeBytes)
                    put("scaleFactor", m.scaleFactor)
                    put("windowSize", m.windowSize)
                    put("numFeatures", m.numFeatures)
                    put("numBlocks", m.numBlocks)
                    put("inputTensorShape", m.inputTensorShape)
                    put("outputTensorShape", m.outputTensorShape)
                    put("parameterCount", m.parameterCount)
                    put("sha256Prefix", m.sha256Prefix)
                    put("importedAtMs", m.importedAtMs)
                }
            )
        }
        prefs.edit().putString(prefKey, arr.toString()).apply()
    }

    private fun parseModelListJson(jsonStr: String?): List<ImportedZoomAiModel> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        val list = mutableListOf<ImportedZoomAiModel>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val path = obj.getString("filePath")
                val file = File(path)
                if (!file.exists() || file.length() == 0L) continue
                list.add(
                    ImportedZoomAiModel(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        architecture = ZoomAiModelArchitecture.valueOf(obj.getString("architecture")),
                        variantName = obj.optString("variantName", "Pretrained"),
                        format = ZoomAiModelFormat.valueOf(obj.getString("format")),
                        filePath = path,
                        fileSizeBytes = file.length(),
                        scaleFactor = obj.optInt("scaleFactor", 2),
                        windowSize = obj.optInt("windowSize", 16),
                        numFeatures = obj.optInt("numFeatures", 64),
                        numBlocks = obj.optInt("numBlocks", 6),
                        inputTensorShape = obj.optString("inputTensorShape", "[1, 3, H, W]"),
                        outputTensorShape = obj.optString("outputTensorShape", "[1, 3, H*s, W*s]"),
                        parameterCount = obj.optLong("parameterCount", file.length() / 4L),
                        sha256Prefix = obj.optString("sha256Prefix", "000000"),
                        importedAtMs = obj.optLong("importedAtMs", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing saved Zoom AI models JSON", e)
        }
        return list
    }
}
