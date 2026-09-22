package com.example.camera.hdr.pipeline

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.example.camera.hdr.data.HdrVideoRepository
import com.example.camera.hdr.data.db.HdrVideoJobEntity
import com.example.camera.hdr.model.*
import com.example.camera.hdr.queue.HdrVideoQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-speed 60 FPS alternating A/B capture controller for genuine dual-exposure HDR video.
 * Adheres strictly to the requirement: CAPTURE MUST NEVER WAIT FOR HDR PROCESSING.
 * - Manages ring buffer and asynchronous non-blocking I/O spool thread.
 * - Configures Camera2 repeating bursts with manual shutter & ISO alternating per request.
 * - Transitions seamlessly back to preview upon capture completion.
 */
class DualExposureCaptureController(private val context: Context) {

    companion object {
        private const val TAG = "DualExpCaptureCtrl"
    }

    private val repository = HdrVideoRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var imageReader: ImageReader? = null
    private var spoolThread: HandlerThread? = null
    private var spoolHandler: Handler? = null

    private val isRecordingActive = AtomicBoolean(false)
    private var currentJobId: String? = null
    private var currentSpoolDir: File? = null
    private var currentDestMp4: File? = null
    private var currentExposurePair: HdrExposurePair? = null

    private var captureWidth = 1920
    private var captureHeight = 1080
    private var captureFps = 30 // Target HDR output FPS (from 60 sensor captures/sec)
    private val frameCounter = AtomicInteger(0)
    private var totalBytesSpooling = 0L

    // Non-blocking queue for spooling to disk without stalling the camera pipeline
    private class SpooledFrame(
        val dataY: ByteArray,
        val dataU: ByteArray,
        val dataV: ByteArray,
        val isExposureA: Boolean,
        val pairIndex: Int
    )

    private val spoolQueue = LinkedBlockingQueue<SpooledFrame>(64)
    private var isSpoolWorkerRunning = AtomicBoolean(false)

    /**
     * Initializes the high-speed capture surface.
     */
    fun setupCaptureSurface(width: Int, height: Int): Surface {
        captureWidth = width
        captureHeight = height

        imageReader?.close()
        // Use triple/quad buffer (8 images) to prevent any frame drops at 60 FPS
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 8)
        imageReader = reader

        spoolThread?.quitSafely()
        val ht = HandlerThread("HdrSpoolThread", Thread.MAX_PRIORITY)
        ht.start()
        spoolThread = ht
        val handler = Handler(ht.looper)
        spoolHandler = handler

        reader.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!isRecordingActive.get()) {
                img.close()
                return@setOnImageAvailableListener
            }

            try {
                val planes = img.planes
                val yPlane = planes[0].buffer
                val uPlane = planes[1].buffer
                val vPlane = planes[2].buffer

                val ySize = width * height
                val uvSize = (width / 2) * (height / 2)

                val yBytes = ByteArray(ySize)
                val uBytes = ByteArray(uvSize)
                val vBytes = ByteArray(uvSize)

                // Copy plane data quickly
                val yRowStride = planes[0].rowStride
                if (yRowStride == width) {
                    yPlane.get(yBytes)
                } else {
                    for (row in 0 until height) {
                        yPlane.position(row * yRowStride)
                        yPlane.get(yBytes, row * width, width)
                    }
                }

                val uvPixelStride = planes[1].pixelStride
                val uvRowStride = planes[1].rowStride

                if (uvPixelStride == 1 && uvRowStride == width / 2) {
                    uPlane.get(uBytes)
                    vPlane.get(vBytes)
                } else {
                    for (row in 0 until height / 2) {
                        for (col in 0 until width / 2) {
                            val uPos = row * uvRowStride + col * uvPixelStride
                            val vPos = row * planes[2].rowStride + col * planes[2].pixelStride
                            if (uPos < uPlane.limit()) uBytes[row * (width / 2) + col] = uPlane.get(uPos)
                            if (vPos < vPlane.limit()) vBytes[row * (width / 2) + col] = vPlane.get(vPos)
                        }
                    }
                }

                val frameIdx = frameCounter.getAndIncrement()
                val isShortA = (frameIdx % 2 == 0)
                val pairIdx = frameIdx / 2

                val spooled = SpooledFrame(yBytes, uBytes, vBytes, isShortA, pairIdx)
                // Offer into queue without blocking; if queue full, drop oldest to protect capture FPS
                if (!spoolQueue.offer(spooled)) {
                    spoolQueue.poll()
                    spoolQueue.offer(spooled)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring capture frame", e)
            } finally {
                img.close() // ALWAYS close immediately to release buffer back to Camera2 sensor pipeline!
            }
        }, handler)

        return reader.surface
    }

    /**
     * Builds alternating repeating burst requests:
     * Request A (Short Exposure) -> Request B (Long Exposure)
     */
    fun createDualExposureBurst(
        camera: CameraDevice,
        previewSurface: Surface,
        captureSurface: Surface,
        exposurePair: HdrExposurePair
    ): List<CaptureRequest> {
        currentExposurePair = exposurePair

        // Request A: Short exposure (Highlight preservation)
        val requestA = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(captureSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposurePair.shortExposureNs)
            set(CaptureRequest.SENSOR_SENSITIVITY, exposurePair.shortIso)
            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
        }.build()

        // Request B: Long exposure (Shadow preservation)
        val requestB = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(captureSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposurePair.longExposureNs)
            set(CaptureRequest.SENSOR_SENSITIVITY, exposurePair.longIso)
            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
        }.build()

        return listOf(requestA, requestB)
    }

    /**
     * Starts dual-exposure video recording.
     */
    fun startRecording(
        width: Int,
        height: Int,
        fps: Int,
        exposurePair: HdrExposurePair,
        priority: ProcessingPriority
    ): String {
        val jobId = UUID.randomUUID().toString()
        currentJobId = jobId
        currentExposurePair = exposurePair
        captureWidth = width
        captureHeight = height
        captureFps = fps
        frameCounter.set(0)
        totalBytesSpooling = 0L

        val spoolDir = File(context.cacheDir, "hdr_spool_$jobId").apply { mkdirs() }
        currentSpoolDir = spoolDir

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputDir = File(context.filesDir, "hdr_videos").apply { mkdirs() }
        val finalMp4 = File(outputDir, "HDR_10BIT_$timeStamp.mp4")
        currentDestMp4 = finalMp4

        isRecordingActive.set(true)
        startSpoolWorker()

        // Insert initial job entity into database
        val entity = HdrVideoJobEntity(
            id = jobId,
            title = "HDR Video ($width x $height)",
            timestamp = System.currentTimeMillis(),
            status = HdrJobStatus.CAPTURING.name,
            progressPercent = 0,
            estimatedRemainingSec = 0,
            processingSpeedFps = 0f,
            rawDirectory = spoolDir.absolutePath,
            finalHdrVideoUri = finalMp4.absolutePath,
            thumbnailUri = "",
            durationSeconds = 0,
            width = width,
            height = height,
            fps = fps,
            bitDepth = 10,
            originalSizeBytes = 0L,
            finalHdrSizeBytes = 0L,
            priority = priority.name,
            isOriginalKept = false,
            shortIso = exposurePair.shortIso,
            shortExposureNs = exposurePair.shortExposureNs,
            longIso = exposurePair.longIso,
            longExposureNs = exposurePair.longExposureNs,
            evDelta = exposurePair.evDelta
        )

        scope.launch {
            repository.saveJob(entity)
        }

        return jobId
    }

    private fun startSpoolWorker() {
        if (isSpoolWorkerRunning.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                while (isRecordingActive.get() || spoolQueue.isNotEmpty()) {
                    val frame = spoolQueue.poll()
                    if (frame != null) {
                        val dir = currentSpoolDir ?: continue
                        val tag = if (frame.isExposureA) "A" else "B"
                        val fileName = String.format(Locale.US, "frame_%06d_%s.yuv", frame.pairIndex, tag)
                        val outFile = File(dir, fileName)
                        try {
                            FileOutputStream(outFile).use { fos ->
                                fos.write(frame.dataY)
                                fos.write(frame.dataU)
                                fos.write(frame.dataV)
                            }
                            totalBytesSpooling += (frame.dataY.size + frame.dataU.size + frame.dataV.size)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error spooling frame to disk", e)
                        }
                    } else {
                        kotlinx.coroutines.delay(10)
                    }
                }
                isSpoolWorkerRunning.set(false)
            }
        }
    }

    /**
     * Stops dual-exposure recording, creates queued job, and hands off to background worker.
     */
    fun stopRecording(): String? {
        val jobId = currentJobId ?: return null
        isRecordingActive.set(false)

        val recordedFrames = frameCounter.get()
        val totalPairs = recordedFrames / 2
        val durationSec = if (captureFps > 0) totalPairs / captureFps else 0

        scope.launch {
            // Drain remaining spool queue
            while (spoolQueue.isNotEmpty() || isSpoolWorkerRunning.get()) {
                kotlinx.coroutines.delay(50)
            }

            val job = repository.getJobById(jobId)
            if (job != null) {
                val updated = job.copy(
                    status = HdrJobStatus.QUEUED.name,
                    durationSeconds = durationSec,
                    originalSizeBytes = totalBytesSpooling
                )
                repository.saveJob(updated)
                // Notify queue manager to begin background processing automatically
                HdrVideoQueueManager.getInstance(context).resumeJob(jobId)
            }
        }

        currentJobId = null
        return jobId
    }

    fun release() {
        isRecordingActive.set(false)
        imageReader?.close()
        imageReader = null
        spoolThread?.quitSafely()
        spoolThread = null
    }
}
