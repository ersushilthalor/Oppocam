package com.example.camera.depth

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Manages real on-demand AI depth model downloads, HTTP resume, integrity & tensor verification,
 * storage usage tracking, and model deletion.
 */
class DepthModelManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DepthModelManager"
        private const val MODELS_DIR_NAME = "depth_ai_models"
        private const val PREFS_NAME = "depth_model_manager_prefs"
        private const val KEY_SELECTED_MODEL = "selected_depth_model_id"
        private const val KEY_HARDWARE_BACKEND = "selected_hardware_backend"

        @Volatile
        private var instance: DepthModelManager? = null

        fun getInstance(context: Context): DepthModelManager {
            return instance ?: synchronized(this) {
                instance ?: DepthModelManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelsDir: File = File(context.filesDir, MODELS_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    private val activeDownloadJobs = mutableMapOf<DepthModelType, Job>()

    private val _selectedModel = MutableStateFlow(
        DepthModelType.fromId(prefs.getString(KEY_SELECTED_MODEL, DepthModelType.DEPTH_ANYTHING_V2.id))
    )
    val selectedModel: StateFlow<DepthModelType> = _selectedModel.asStateFlow()

    private val _hardwareBackend = MutableStateFlow(
        try {
            DepthHardwareBackend.valueOf(
                prefs.getString(KEY_HARDWARE_BACKEND, DepthHardwareBackend.AUTO.name)
                    ?: DepthHardwareBackend.AUTO.name
            )
        } catch (e: Exception) {
            DepthHardwareBackend.AUTO
        }
    )
    val hardwareBackend: StateFlow<DepthHardwareBackend> = _hardwareBackend.asStateFlow()

    private val _modelStatuses = MutableStateFlow<Map<DepthModelType, DepthModelStatusInfo>>(emptyMap())
    val modelStatuses: StateFlow<Map<DepthModelType, DepthModelStatusInfo>> = _modelStatuses.asStateFlow()

    private val _storageSummary = MutableStateFlow(DepthStorageSummary())
    val storageSummary: StateFlow<DepthStorageSummary> = _storageSummary.asStateFlow()

    init {
        refreshAllStatuses()
        probeRemoteSizesAsync()
    }

    fun getModelFile(type: DepthModelType): File = File(modelsDir, type.fileName)
    private fun getTempFile(type: DepthModelType): File = File(modelsDir, "${type.fileName}.part")
    private fun getMetaFormatKey(type: DepthModelType) = "meta_format_${type.id}"
    private fun getMetaShapeKey(type: DepthModelType) = "meta_shape_${type.id}"
    private fun getRemoteSizeKey(type: DepthModelType) = "remote_size_${type.id}"

    fun selectModel(type: DepthModelType) {
        _selectedModel.value = type
        prefs.edit().putString(KEY_SELECTED_MODEL, type.id).apply()
        refreshAllStatuses()
    }

    fun selectHardwareBackend(backend: DepthHardwareBackend) {
        _hardwareBackend.value = backend
        prefs.edit().putString(KEY_HARDWARE_BACKEND, backend.name).apply()
    }

    fun isModelInstalledAndVerified(type: DepthModelType): Boolean {
        val status = _modelStatuses.value[type]?.state
        if (status is DepthModelInstallState.Installed) {
            val file = getModelFile(type)
            return file.exists() && file.length() >= type.minValidBytes
        }
        return false
    }

    fun getActiveInstalledModelFile(): Pair<DepthModelType, File>? {
        val preferred = _selectedModel.value
        val preferredFile = getModelFile(preferred)
        if (preferredFile.exists() && preferredFile.length() >= preferred.minValidBytes && isModelInstalledAndVerified(preferred)) {
            return preferred to preferredFile
        }
        // Fallback to any other verified installed model
        for (type in DepthModelType.entries) {
            val f = getModelFile(type)
            if (f.exists() && f.length() >= type.minValidBytes && isModelInstalledAndVerified(type)) {
                return type to f
            }
        }
        return null
    }

    fun refreshAllStatuses() {
        val currentMap = _modelStatuses.value
        val updated = mutableMapOf<DepthModelType, DepthModelStatusInfo>()
        val selected = _selectedModel.value

        for (type in DepthModelType.entries) {
            val existingState = currentMap[type]?.state
            val remoteSize = prefs.getLong(getRemoteSizeKey(type), -1L).takeIf { it > 0L }
            if (existingState is DepthModelInstallState.Downloading || existingState is DepthModelInstallState.Verifying) {
                updated[type] = DepthModelStatusInfo(
                    modelType = type,
                    state = existingState,
                    remoteSizeBytes = remoteSize,
                    isSelected = (type == selected)
                )
                continue
            }

            val modelFile = getModelFile(type)
            val partFile = getTempFile(type)
            val state = if (modelFile.exists() && modelFile.length() >= type.minValidBytes) {
                val savedFormat = prefs.getString(getMetaFormatKey(type), null)
                val savedShape = prefs.getString(getMetaShapeKey(type), null)
                if (savedFormat != null && savedShape != null) {
                    DepthModelInstallState.Installed(
                        fileSizeBytes = modelFile.length(),
                        verifiedFormat = savedFormat,
                        inputShapeSummary = savedShape,
                        lastModifiedMs = modelFile.lastModified()
                    )
                } else {
                    val verification = verifyModelFile(modelFile, type)
                    if (verification != null) {
                        prefs.edit()
                            .putString(getMetaFormatKey(type), verification.first)
                            .putString(getMetaShapeKey(type), verification.second)
                            .apply()
                        DepthModelInstallState.Installed(
                            fileSizeBytes = modelFile.length(),
                            verifiedFormat = verification.first,
                            inputShapeSummary = verification.second,
                            lastModifiedMs = modelFile.lastModified()
                        )
                    } else {
                        modelFile.delete()
                        DepthModelInstallState.NotInstalled
                    }
                }
            } else if (existingState is DepthModelInstallState.Failed) {
                existingState.copy(partialBytes = if (partFile.exists()) partFile.length() else 0L)
            } else {
                DepthModelInstallState.NotInstalled
            }

            updated[type] = DepthModelStatusInfo(
                modelType = type,
                state = state,
                remoteSizeBytes = remoteSize,
                isSelected = (type == selected)
            )
        }

        _modelStatuses.value = updated
        updateStorageSummary()
    }

    private fun updateStorageSummary() {
        try {
            var modelsBytes = 0L
            modelsDir.listFiles()?.forEach { file ->
                if (file.isFile) modelsBytes += file.length()
            }
            val stat = StatFs(context.filesDir.absolutePath)
            val avail = stat.availableBytes
            val total = stat.totalBytes
            _storageSummary.value = DepthStorageSummary(
                totalModelsBytes = modelsBytes,
                availableDeviceBytes = avail,
                totalDeviceBytes = total
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read storage stats", e)
        }
    }

    private fun probeRemoteSizesAsync() {
        scope.launch {
            for (type in DepthModelType.entries) {
                if (prefs.getLong(getRemoteSizeKey(type), -1L) > 0L) continue
                for (urlStr in type.downloadUrls) {
                    val contentLength = fetchRemoteContentLength(urlStr)
                    if (contentLength >= type.minValidBytes) {
                        prefs.edit().putLong(getRemoteSizeKey(type), contentLength).apply()
                        _modelStatuses.update { map ->
                            val cur = map[type] ?: return@update map
                            map + (type to cur.copy(remoteSizeBytes = contentLength))
                        }
                        break
                    }
                }
            }
        }
    }

    private fun fetchRemoteContentLength(urlString: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            var currentUrl = urlString
            var redirects = 0
            while (redirects < 6) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "CameraPro-Android/1.0")
                }
                val code = conn.responseCode
                if (code in 301..308) {
                    val loc = conn.getHeaderField("Location") ?: break
                    currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                    conn.disconnect()
                    redirects++
                } else if (code in 200..299) {
                    val len = conn.contentLengthLong
                    return len
                } else {
                    break
                }
            }
            -1L
        } catch (e: Exception) {
            -1L
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun startDownload(type: DepthModelType) {
        if (activeDownloadJobs[type]?.isActive == true) return

        val job = scope.launch {
            downloadModelInternal(type)
        }
        activeDownloadJobs[type] = job
    }

    fun cancelDownload(type: DepthModelType) {
        activeDownloadJobs[type]?.cancel()
        activeDownloadJobs.remove(type)
        val partFile = getTempFile(type)
        val partialLen = if (partFile.exists()) partFile.length() else 0L
        updateModelState(
            type,
            if (partialLen > 0L) {
                DepthModelInstallState.Failed("Download paused (${formatBytes(partialLen)} saved — tap Retry to resume)", partialLen)
            } else {
                DepthModelInstallState.NotInstalled
            }
        )
        updateStorageSummary()
    }

    fun deleteModel(type: DepthModelType): Boolean {
        activeDownloadJobs[type]?.cancel()
        activeDownloadJobs.remove(type)

        val modelFile = getModelFile(type)
        val partFile = getTempFile(type)
        var deleted = false
        if (modelFile.exists()) {
            deleted = modelFile.delete() || deleted
        }
        if (partFile.exists()) {
            deleted = partFile.delete() || deleted
        }
        prefs.edit()
            .remove(getMetaFormatKey(type))
            .remove(getMetaShapeKey(type))
            .apply()

        // If another model is installed and this was selected, switch automatically
        if (_selectedModel.value == type) {
            val alternative = DepthModelType.entries.firstOrNull { it != type && getModelFile(it).exists() }
            if (alternative != null) {
                _selectedModel.value = alternative
                prefs.edit().putString(KEY_SELECTED_MODEL, alternative.id).apply()
            }
        }
        refreshAllStatuses()
        return deleted
    }

    private suspend fun downloadModelInternal(type: DepthModelType) = withContext(Dispatchers.IO) {
        val partFile = getTempFile(type)
        val destFile = getModelFile(type)
        var lastError = "Unable to reach model server"

        for ((urlIndex, candidateUrl) in type.downloadUrls.withIndex()) {
            if (!isActive) return@withContext
            // If switching to a fallback URL, reset incompatible partial file
            if (urlIndex > 0 && partFile.exists()) {
                partFile.delete()
            }

            var connection: HttpURLConnection? = null
            try {
                val existingBytes = if (partFile.exists()) partFile.length() else 0L
                val stat = StatFs(modelsDir.absolutePath)
                if (stat.availableBytes < 60_000_000L) {
                    updateModelState(
                        type,
                        DepthModelInstallState.Failed("Insufficient device storage (< 60 MB free)", existingBytes)
                    )
                    return@withContext
                }

                updateModelState(
                    type,
                    DepthModelInstallState.Downloading(
                        downloadedBytes = existingBytes,
                        totalBytes = prefs.getLong(getRemoteSizeKey(type), -1L).coerceAtLeast(existingBytes),
                        progressFraction = 0f
                    )
                )

                // Follow redirects manually to preserve Range header accurately across HuggingFace CDN
                var currentUrl = candidateUrl
                var redirects = 0
                while (redirects < 8) {
                    connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 20000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "CameraPro-Android/1.0")
                        if (existingBytes > 0L) {
                            setRequestProperty("Range", "bytes=$existingBytes-")
                        }
                    }
                    val responseCode = connection.responseCode
                    if (responseCode in 301..308) {
                        val location = connection.getHeaderField("Location")
                            ?: throw IllegalStateException("Redirect missing Location header")
                        currentUrl = if (location.startsWith("http")) {
                            location
                        } else {
                            URL(URL(currentUrl), location).toString()
                        }
                        connection.disconnect()
                        redirects++
                    } else {
                        break
                    }
                }

                val conn = connection ?: throw IllegalStateException("Failed to open connection")
                val code = conn.responseCode

                val isResume = (code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    lastError = "HTTP $code from model mirror"
                    conn.disconnect()
                    continue
                }

                val contentLength = conn.contentLengthLong
                val totalBytes = when {
                    isResume && contentLength > 0L -> existingBytes + contentLength
                    contentLength > 0L -> contentLength
                    else -> prefs.getLong(getRemoteSizeKey(type), -1L)
                }

                if (totalBytes > 0L && totalBytes < type.minValidBytes) {
                    lastError = "Remote file size ($totalBytes bytes) smaller than minimum valid model"
                    conn.disconnect()
                    continue
                }

                if (totalBytes > 0L) {
                    prefs.edit().putLong(getRemoteSizeKey(type), totalBytes).apply()
                }

                var downloaded = if (isResume) existingBytes else 0L
                if (!isResume && partFile.exists()) {
                    partFile.delete()
                }

                RandomAccessFile(partFile, "rw").use { raf ->
                    if (isResume) {
                        raf.seek(existingBytes)
                    } else {
                        raf.setLength(0L)
                    }

                    conn.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var lastEmitTime = System.currentTimeMillis()
                        var bytesSinceLastEmit = 0L
                        var currentSpeed = 0L

                        while (isActive) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            downloaded += read
                            bytesSinceLastEmit += read

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastEmitTime
                            if (elapsed >= 120L) {
                                currentSpeed = (bytesSinceLastEmit * 1000L) / elapsed.coerceAtLeast(1L)
                                lastEmitTime = now
                                bytesSinceLastEmit = 0L
                                val progress = if (totalBytes > 0L) {
                                    (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)
                                } else 0f
                                updateModelState(
                                    type,
                                    DepthModelInstallState.Downloading(
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes,
                                        progressFraction = progress,
                                        speedBytesPerSec = currentSpeed
                                    )
                                )
                            }
                        }
                    }
                }

                if (!isActive) return@withContext

                // Verify downloaded file size and actual TFLite / ONNX tensor loadability
                updateModelState(type, DepthModelInstallState.Verifying)

                if (!partFile.exists() || partFile.length() < type.minValidBytes) {
                    lastError = "Downloaded file incomplete (${partFile.length()} bytes)"
                    continue
                }

                val verification = verifyModelFile(partFile, type)
                if (verification == null) {
                    lastError = "Model tensor verification failed"
                    partFile.delete()
                    continue
                }

                if (destFile.exists()) destFile.delete()
                val renamed = partFile.renameTo(destFile)
                if (!renamed) {
                    partFile.copyTo(destFile, overwrite = true)
                    partFile.delete()
                }

                prefs.edit()
                    .putString(getMetaFormatKey(type), verification.first)
                    .putString(getMetaShapeKey(type), verification.second)
                    .putLong(getRemoteSizeKey(type), destFile.length())
                    .apply()

                // Automatically set as selected model if none was installed before
                _selectedModel.value = type
                prefs.edit().putString(KEY_SELECTED_MODEL, type.id).apply()

                refreshAllStatuses()
                return@withContext
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "Download attempt failed for ${type.displayName} from $candidateUrl", t)
                lastError = t.localizedMessage ?: "Network connection interrupted"
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }

        val partialLen = if (partFile.exists()) partFile.length() else 0L
        updateModelState(
            type,
            DepthModelInstallState.Failed(
                errorMessage = lastError,
                partialBytes = partialLen
            )
        )
        updateStorageSummary()
    }

    /**
     * Verifies that the downloaded file is a genuine, loadable TFLite or ONNX neural network
     * and returns Pair(verifiedRuntimeFormat, inputOutputTensorSummary) or null if invalid.
     */
    fun verifyModelFile(file: File, type: DepthModelType): Pair<String, String>? {
        if (!file.exists() || file.length() < type.minValidBytes) return null

        // Check header bytes for TFLite ("TFL3" at offset 4) or try TFLite Interpreter first
        val isTfliteHeader = try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(8)
                if (raf.read(header) == 8) {
                    val magic = String(header, 4, 4, Charsets.US_ASCII)
                    magic == "TFL3"
                } else false
            }
        } catch (e: Exception) {
            false
        }

        if (isTfliteHeader) {
            try {
                val options = Interpreter.Options().apply { setNumThreads(2) }
                FileInputStream(file).channel.use { channel ->
                    val mapped: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                    mapped.order(ByteOrder.nativeOrder())
                    Interpreter(mapped, options).use { interpreter ->
                        val inTensor = interpreter.getInputTensor(0)
                        val outTensor = interpreter.getOutputTensor(0)
                        val inShape = inTensor.shape().joinToString("×")
                        val outShape = outTensor.shape().joinToString("×")
                        val dtype = inTensor.dataType().name
                        return "TFLite ($dtype)" to "In [$inShape] → Out [$outShape]"
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "TFLite verification failed for ${file.name}", t)
            }
        }

        // Try ONNX Runtime verification
        try {
            val env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(2)
                env.createSession(file.absolutePath, opts).use { session ->
                    val inputInfo = session.inputInfo.entries.firstOrNull()
                    val outputInfo = session.outputInfo.entries.firstOrNull()
                    if (inputInfo != null && outputInfo != null) {
                        return "ONNX Runtime" to "In: ${inputInfo.key} → Out: ${outputInfo.key}"
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ONNX verification failed for ${file.name}", t)
        }

        return null
    }

    private fun updateModelState(type: DepthModelType, state: DepthModelInstallState) {
        val remoteSize = prefs.getLong(getRemoteSizeKey(type), -1L).takeIf { it > 0L }
        _modelStatuses.update { current ->
            current + (type to DepthModelStatusInfo(
                modelType = type,
                state = state,
                remoteSizeBytes = remoteSize,
                isSelected = (_selectedModel.value == type)
            ))
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1000.0) {
            String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }
}
