package com.example.camera.hdr.queue

import android.content.Context
import android.util.Log
import com.example.camera.hdr.data.HdrVideoRepository
import com.example.camera.hdr.data.db.HdrVideoJobEntity
import com.example.camera.hdr.model.*
import com.example.camera.hdr.pipeline.HdrFusionProcessor
import com.example.camera.hdr.pipeline.HdrHardwareEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robust background queue and processing engine for Adaptive Dual-Exposure HDR videos.
 * Features:
 * - Decoupled producer-consumer pipeline: Camera capture NEVER waits for HDR processing.
 * - Managed worker pool with single-job active concurrency to avoid OOM / thermal throttle.
 * - Dynamic priority (Maximum, Balanced, Battery Saver).
 * - Automatic crash recovery for interrupted jobs upon app initialization.
 * - Automatic cleanup of intermediate spool files upon verified final MP4 encoding.
 */
class HdrVideoQueueManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "HdrVideoQueueManager"

        @Volatile
        private var INSTANCE: HdrVideoQueueManager? = null

        fun getInstance(context: Context): HdrVideoQueueManager {
            return INSTANCE ?: synchronized(this) {
                val instance = HdrVideoQueueManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private val repository = HdrVideoRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeProgressMap = MutableStateFlow<Map<String, HdrProcessingProgress>>(emptyMap())
    val activeProgressMap: StateFlow<Map<String, HdrProcessingProgress>> = _activeProgressMap.asStateFlow()

    private val pausedJobs = ConcurrentHashMap.newKeySet<String>()
    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()
    private val isWorkerLoopRunning = AtomicBoolean(false)
    private var workerJob: Job? = null

    init {
        // Recover any pending or interrupted jobs from database upon startup
        recoverInterruptedJobs()
        startQueueWorker()
    }

    /**
     * Scans storage and DB for incomplete jobs left behind if app crashed or was closed.
     */
    private fun recoverInterruptedJobs() {
        scope.launch {
            try {
                val incomplete = repository.getIncompleteJobs()
                for (job in incomplete) {
                    if (job.status != HdrJobStatus.COMPLETE.name && job.status != HdrJobStatus.CANCELLED.name) {
                        Log.i(TAG, "Recovered interrupted HDR job ${job.id}, marking QUEUED")
                        repository.updateProgress(job.id, HdrJobStatus.QUEUED.name, job.progressPercent, 0, 0f)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recovering incomplete jobs", e)
            }
        }
    }

    private fun startQueueWorker() {
        if (isWorkerLoopRunning.compareAndSet(false, true)) {
            workerJob = scope.launch {
                while (isActive) {
                    try {
                        val incomplete = repository.getIncompleteJobs()
                        val nextJob = incomplete.firstOrNull {
                            it.status == HdrJobStatus.QUEUED.name && !pausedJobs.contains(it.id)
                        }

                        if (nextJob != null) {
                            processJob(nextJob)
                        } else {
                            delay(1000)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Queue worker error", e)
                        delay(2000)
                    }
                }
            }
        }
    }

    /**
     * Executes the end-to-end HDR merge and hardware encoding pipeline for a given job.
     */
    private suspend fun processJob(job: HdrVideoJobEntity) = withContext(Dispatchers.IO) {
        val jobId = job.id
        Log.i(TAG, "Starting processing for HDR job: $jobId (${job.title})")

        val rawDir = File(job.rawDirectory)
        val finalFile = File(job.finalHdrVideoUri)
        val priority = try {
            ProcessingPriority.valueOf(job.priority)
        } catch (e: Exception) {
            ProcessingPriority.BALANCED
        }

        // Check if raw directory exists
        if (!rawDir.exists() || !rawDir.isDirectory) {
            Log.e(TAG, "Raw capture directory missing for job $jobId: ${rawDir.absolutePath}")
            repository.updateProgress(jobId, HdrJobStatus.FAILED.name, 0, 0, 0f)
            return@withContext
        }

        // List frame spool files
        // Format: frame_0001_A.yuv, frame_0001_B.yuv, ...
        val aFiles = rawDir.listFiles { file -> file.name.endsWith("_A.yuv") }?.sortedBy { it.name } ?: emptyList()
        val bFiles = rawDir.listFiles { file -> file.name.endsWith("_B.yuv") }?.sortedBy { it.name } ?: emptyList()

        val pairCount = minOf(aFiles.size, bFiles.size)
        if (pairCount == 0) {
            Log.w(TAG, "No valid dual-exposure frame pairs found in $rawDir, marking complete")
            repository.markComplete(jobId, HdrJobStatus.COMPLETE.name, finalFile.length())
            return@withContext
        }

        val width = job.width
        val height = job.height
        val fps = job.fps
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)

        // Update status to PROCESSING
        updateJobProgress(
            jobId = jobId,
            status = HdrJobStatus.PROCESSING,
            progress = 5,
            remainingSec = pairCount / maxOf(1, fps),
            speedFps = 0f,
            sourceSize = job.originalSizeBytes,
            finalSize = 0L,
            currentFrame = 0,
            totalFrames = pairCount
        )

        // Initialize computational fusion engine and hardware encoder
        val fusionProcessor = HdrFusionProcessor(width, height)
        val output10BitFrame = HdrFusionProcessor.Hdr10BitFrame(width, height)
        val encoder = HdrHardwareEncoder(
            width = width,
            height = height,
            fps = fps,
            bitrate = 35_000_000,
            is10BitRequested = (job.bitDepth == 10)
        )

        finalFile.parentFile?.mkdirs()
        encoder.init(finalFile)

        val pair = HdrExposurePair(
            shortExposureNs = job.shortExposureNs,
            shortIso = job.shortIso,
            longExposureNs = job.longExposureNs,
            longIso = job.longIso,
            evDelta = job.evDelta
        )

        val yBufferA = ByteArray(ySize)
        val uBufferA = ByteArray(uvSize)
        val vBufferA = ByteArray(uvSize)

        val yBufferB = ByteArray(ySize)
        val uBufferB = ByteArray(uvSize)
        val vBufferB = ByteArray(uvSize)

        val startTime = System.currentTimeMillis()
        var processedPairs = 0

        try {
            for (i in 0 until pairCount) {
                // Check pause or cancellation
                if (cancelledJobs.contains(jobId)) {
                    Log.i(TAG, "Job $jobId was cancelled")
                    encoder.finish()
                    finalFile.delete()
                    repository.updateProgress(jobId, HdrJobStatus.CANCELLED.name, 0, 0, 0f)
                    return@withContext
                }

                while (pausedJobs.contains(jobId)) {
                    delay(500)
                }

                // Read Short Frame A
                val fileA = aFiles[i]
                FileInputStream(fileA).use { stream ->
                    stream.read(yBufferA)
                    stream.read(uBufferA)
                    stream.read(vBufferA)
                }

                // Read Long Frame B
                val fileB = bFiles[i]
                FileInputStream(fileB).use { stream ->
                    stream.read(yBufferB)
                    stream.read(uBufferB)
                    stream.read(vBufferB)
                }

                val frameDataA = HdrFusionProcessor.FrameData(
                    yPlane = yBufferA,
                    uPlane = uBufferA,
                    vPlane = vBufferA,
                    width = width,
                    height = height,
                    isShortExposure = true,
                    exposureNs = job.shortExposureNs,
                    iso = job.shortIso,
                    timestampNs = i * (1_000_000_000L / fps)
                )

                val frameDataB = HdrFusionProcessor.FrameData(
                    yPlane = yBufferB,
                    uPlane = uBufferB,
                    vPlane = vBufferB,
                    width = width,
                    height = height,
                    isShortExposure = false,
                    exposureNs = job.longExposureNs,
                    iso = job.longIso,
                    timestampNs = i * (1_000_000_000L / fps)
                )

                // Computational HDR exposure fusion & tone mapping
                fusionProcessor.mergePair(frameDataA, frameDataB, pair, output10BitFrame)

                // Hardware 10-bit encode
                val isLast = (i == pairCount - 1)
                encoder.encodeFrame(output10BitFrame, isLastFrame = isLast)

                processedPairs++

                // Calculate progress and dynamic metrics
                val elapsedMs = maxOf(1L, System.currentTimeMillis() - startTime)
                val currentFps = (processedPairs * 1000f) / elapsedMs
                val remainingFrames = pairCount - processedPairs
                val remainingSec = if (currentFps > 0) (remainingFrames / currentFps).toInt() else 0
                val percent = 5 + ((processedPairs * 90) / pairCount)

                val currentStage = if (percent < 70) HdrJobStatus.HDR_MERGE else HdrJobStatus.ENCODING
                updateJobProgress(
                    jobId = jobId,
                    status = currentStage,
                    progress = percent,
                    remainingSec = remainingSec,
                    speedFps = currentFps,
                    sourceSize = job.originalSizeBytes,
                    finalSize = finalFile.length(),
                    currentFrame = processedPairs,
                    totalFrames = pairCount
                )

                // Thermal & battery throttling according to priority
                when (priority) {
                    ProcessingPriority.MAXIMUM -> {
                        // Minimal yield to keep UI responsive
                        if (i % 20 == 0) yield()
                    }
                    ProcessingPriority.BALANCED -> {
                        if (i % 5 == 0) delay(2)
                    }
                    ProcessingPriority.BATTERY_SAVER -> {
                        delay(12) // Lower clock consumption and temperature
                    }
                }
            }

            encoder.finish()

            // Verify final output file
            if (finalFile.exists() && finalFile.length() > 1024) {
                val finalSize = finalFile.length()
                Log.i(TAG, "HDR Job $jobId successfully encoded! Final size: $finalSize bytes")

                // Cleanup intermediate spool directory unless user explicitly marked to keep original
                if (!job.isOriginalKept) {
                    deleteRawSpoolDirectory(rawDir)
                }

                updateJobProgress(
                    jobId = jobId,
                    status = HdrJobStatus.COMPLETE,
                    progress = 100,
                    remainingSec = 0,
                    speedFps = 0f,
                    sourceSize = job.originalSizeBytes,
                    finalSize = finalSize,
                    currentFrame = pairCount,
                    totalFrames = pairCount
                )
                repository.markComplete(jobId, HdrJobStatus.COMPLETE.name, finalSize)
            } else {
                throw IllegalStateException("Generated HDR video file is missing or empty")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed processing HDR video job $jobId", e)
            encoder.finish()
            repository.updateProgress(jobId, HdrJobStatus.FAILED.name, 0, 0, 0f)
            updateJobProgress(
                jobId = jobId,
                status = HdrJobStatus.FAILED,
                progress = 0,
                remainingSec = 0,
                speedFps = 0f,
                sourceSize = job.originalSizeBytes,
                finalSize = 0L,
                currentFrame = 0,
                totalFrames = pairCount,
                error = e.message
            )
        }
    }

    private fun updateJobProgress(
        jobId: String,
        status: HdrJobStatus,
        progress: Int,
        remainingSec: Int,
        speedFps: Float,
        sourceSize: Long,
        finalSize: Long,
        currentFrame: Int,
        totalFrames: Int,
        error: String? = null
    ) {
        val current = _activeProgressMap.value.toMutableMap()
        current[jobId] = HdrProcessingProgress(
            jobId = jobId,
            status = status,
            progressPercent = progress,
            estimatedRemainingSec = remainingSec,
            processingSpeedFps = speedFps,
            originalSourceSizeBytes = sourceSize,
            finalHdrSizeBytes = finalSize,
            currentFrameIndex = currentFrame,
            totalFrames = totalFrames,
            errorDescription = error
        )
        _activeProgressMap.value = current
    }

    private fun deleteRawSpoolDirectory(dir: File) {
        try {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning raw spool directory: ${dir.absolutePath}", e)
        }
    }

    // --- User Actions from Gallery UI ---

    fun processNow(jobId: String) {
        pausedJobs.remove(jobId)
        scope.launch {
            repository.updateProgress(jobId, HdrJobStatus.QUEUED.name, 0, 0, 0f)
        }
    }

    fun pauseJob(jobId: String) {
        pausedJobs.add(jobId)
        scope.launch {
            val job = repository.getJobById(jobId)
            if (job != null) {
                repository.updateProgress(jobId, HdrJobStatus.PAUSED.name, job.progressPercent, 0, 0f)
            }
        }
    }

    fun resumeJob(jobId: String) {
        pausedJobs.remove(jobId)
        scope.launch {
            val job = repository.getJobById(jobId)
            if (job != null) {
                repository.updateProgress(jobId, HdrJobStatus.QUEUED.name, job.progressPercent, 0, 0f)
            }
        }
    }

    fun cancelJob(jobId: String) {
        cancelledJobs.add(jobId)
        pausedJobs.remove(jobId)
        scope.launch {
            repository.updateProgress(jobId, HdrJobStatus.CANCELLED.name, 0, 0, 0f)
        }
    }

    fun deleteOriginalCapture(jobId: String) {
        scope.launch {
            val job = repository.getJobById(jobId)
            if (job != null) {
                deleteRawSpoolDirectory(File(job.rawDirectory))
                repository.saveJob(job.copy(isOriginalKept = false, originalSizeBytes = 0L))
            }
        }
    }

    fun keepOriginalCapture(jobId: String) {
        scope.launch {
            val job = repository.getJobById(jobId)
            if (job != null) {
                repository.saveJob(job.copy(isOriginalKept = true))
            }
        }
    }

    fun reprocessJob(jobId: String) {
        pausedJobs.remove(jobId)
        cancelledJobs.remove(jobId)
        scope.launch {
            repository.updateProgress(jobId, HdrJobStatus.QUEUED.name, 0, 0, 0f)
        }
    }

    fun setPriority(jobId: String, priority: ProcessingPriority) {
        scope.launch {
            val job = repository.getJobById(jobId)
            if (job != null) {
                repository.saveJob(job.copy(priority = priority.name))
            }
        }
    }
}
