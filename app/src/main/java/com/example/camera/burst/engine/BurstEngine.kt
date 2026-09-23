package com.example.camera.burst.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageProxy
import com.example.camera.sound.CameraSoundManager
import com.example.camera.ultrafast.data.UltraFastBurstRepository
import com.example.camera.ultrafast.model.UltraFastBurstEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated high-speed continuous Burst Mode engine.
 *
 * Pipeline architecture:
 * Continuous Camera Sensor stream (YUV_420_888 at maximum supported native 12MP resolution)
 *   → Rapid shutter audio trigger (machine-gun style SoundPool)
 *   → FPS-gated frame selection (5–20 FPS)
 *   → Preallocated buffer pool (BurstBufferPool)
 *   → Bounded producer/consumer queue (ArrayBlockingQueue)
 *   → Parallel background JPEG workers (2–4 threads)
 *   → Standard Google Camera XMP (GCamera:BurstID, BurstPrimary, BurstIndex) + Exif subsecond tags
 *   → MediaStore DCIM/Camera storage recognition as native Google Photos Burst stack
 */
class BurstEngine(
    private val context: Context,
    private val burstRepository: UltraFastBurstRepository
) {
    companion object {
        private const val TAG = "BurstEngine"
        private const val QUEUE_CAPACITY = 20
        private const val MAX_POOL_CAPACITY = 16
        private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
    }

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Live state flows for UI overlay
    private val _liveCaptureCount = MutableStateFlow(0)
    val liveCaptureCount: StateFlow<Int> = _liveCaptureCount.asStateFlow()

    private val _isHolding = MutableStateFlow(false)
    val isHolding: StateFlow<Boolean> = _isHolding.asStateFlow()

    private val _isProcessingQueue = MutableStateFlow(false)
    val isProcessingQueue: StateFlow<Boolean> = _isProcessingQueue.asStateFlow()

    // Bounded producer/consumer queue
    private val frameQueue = ArrayBlockingQueue<BurstFrame>(QUEUE_CAPACITY)

    // Parallel background workers for JPEG encoding and disk I/O
    private val workerCount = minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors()))
    private val backgroundProcessor = Executors.newFixedThreadPool(workerCount) { runnable ->
        Thread(runnable, "BurstWorker").apply {
            priority = Process.THREAD_PRIORITY_BACKGROUND
        }
    }

    // High-priority capture thread for ultra-low latency ImageReader acquisition
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // Preallocated byte buffer pool for uncompressed planar frames
    private var bufferPool: BurstBufferPool? = null
    private var poolDimensions: Size? = null

    // Session control state
    private val isBurstActive = AtomicBoolean(false)
    private val acquiredFrameCounter = AtomicInteger(0)
    private val processedFrameCounter = AtomicInteger(0)

    @Volatile private var activeBurstSessionId: String = ""
    @Volatile private var sessionDateString: String = ""
    @Volatile private var activeFps: Int = 15
    @Volatile private var activeWidth: Int = 0
    @Volatile private var activeHeight: Int = 0
    @Volatile private var lastAcceptedTimestampNs: Long = 0L
    @Volatile private var coverPhotoUri: Uri? = null

    // Thread-safe map preserving exact frame order by frameIndex
    private val savedUrisMap = ConcurrentSkipListMap<Int, String>()
    private var burstCompletionCallback: ((Uri?) -> Unit)? = null

    init {
        startConsumerWorkers()
    }

    private fun startCaptureThread() {
        if (captureThread == null) {
            captureThread = HandlerThread("BurstCaptureThread", Process.THREAD_PRIORITY_DEFAULT).apply {
                start()
                captureHandler = Handler(looper)
            }
        }
    }

    fun getCaptureHandler(): Handler? {
        if (captureHandler == null) {
            startCaptureThread()
        }
        return captureHandler
    }

    private fun startConsumerWorkers() {
        for (i in 0 until workerCount) {
            backgroundProcessor.execute {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val frame = frameQueue.take()
                        processQueuedFrame(frame)
                    } catch (e: InterruptedException) {
                        break
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error in burst worker thread", t)
                    }
                }
            }
        }
    }

    fun configureBufferPool(width: Int, height: Int) {
        val yuvByteSize = width * height * 3 / 2
        if (poolDimensions?.width != width || poolDimensions?.height != height || bufferPool == null) {
            poolDimensions = Size(width, height)
            bufferPool = BurstBufferPool(yuvByteSize, maxPoolCapacity = MAX_POOL_CAPACITY)
            Log.i(TAG, "Configured native Burst buffer pool: ${width}x$height ($yuvByteSize bytes/frame)")
        }
    }

    /**
     * Starts continuous burst capture at selected FPS (5–20 FPS).
     * Triggered on shutter press and hold.
     */
    fun startBurst(
        burstId: String,
        fps: Int,
        onComplete: (Uri?) -> Unit
    ) {
        activeBurstSessionId = burstId
        sessionDateString = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        activeFps = fps.coerceIn(5, 20)
        burstCompletionCallback = onComplete
        savedUrisMap.clear()
        coverPhotoUri = null
        acquiredFrameCounter.set(0)
        processedFrameCounter.set(0)
        lastAcceptedTimestampNs = 0L

        isBurstActive.set(true)
        _isHolding.value = true
        _liveCaptureCount.value = 0
        _isProcessingQueue.value = false

        Log.i(TAG, "Burst capture session started: id=$burstId, date=$sessionDateString, targetFps=$activeFps")
    }

    /**
     * Stops continuous burst capture immediately.
     * Stops accepting new frames, immediately resets and hides the live counter,
     * and allows background workers to drain remaining queued frames.
     */
    fun stopBurst() {
        if (!isBurstActive.getAndSet(false)) return

        // Immediately hide and reset viewfinder counter
        _isHolding.value = false
        _liveCaptureCount.value = 0

        val totalAcquired = acquiredFrameCounter.get()
        Log.i(TAG, "Burst capture stopped. Total acquired: $totalAcquired frames. Draining queue in background...")

        if (totalAcquired == 0) {
            val cb = burstCompletionCallback
            burstCompletionCallback = null
            cb?.invoke(null)
        }
    }

    fun isBurstActive(): Boolean = isBurstActive.get()

    /**
     * Producer: Camera2 ImageReader continuous stream callback.
     * Acquires real sensor frame at maximum native resolution (12MP), gates by target FPS,
     * copies into preallocated buffer, closes camera image immediately (< 1ms),
     * fires non-blocking burst shutter audio, updates live counter and offers to bounded queue.
     */
    fun onSensorImageAvailable(
        reader: ImageReader,
        sensorOrientation: Int,
        isFrontFacing: Boolean,
        saveMirrored: Boolean
    ) {
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        if (!isBurstActive.get()) {
            image.close()
            return
        }

        val frameTimestampNs = image.timestamp
        val minIntervalNs = 1_000_000_000L / activeFps

        // FPS-gated frame selection with 10% jitter tolerance; never duplicates frames
        if (lastAcceptedTimestampNs > 0L && (frameTimestampNs - lastAcceptedTimestampNs) < (minIntervalNs * 0.90)) {
            image.close()
            return
        }

        lastAcceptedTimestampNs = frameTimestampNs

        val width = image.width
        val height = image.height
        activeWidth = width
        activeHeight = height

        val expectedSize = width * height * 3 / 2
        var pool = bufferPool
        if (pool == null || poolDimensions?.width != width || poolDimensions?.height != height) {
            configureBufferPool(width, height)
            pool = bufferPool
        }

        val pooledBuffer = pool?.acquire()
        if (pooledBuffer == null) {
            Log.w(TAG, "Buffer pool starved or low memory; dropping sensor frame to prevent stutter")
            image.close()
            return
        }

        // Copy planar YUV data to pooled buffer
        val success = extractNv21FromImage(image, pooledBuffer, width, height)
        image.close() // Close ImageReader buffer immediately (< 1ms)

        if (!success) {
            pool.recycle(pooledBuffer)
            return
        }

        val frameIdx = acquiredFrameCounter.incrementAndGet()

        val burstFrame = BurstFrame(
            burstSessionId = activeBurstSessionId,
            frameIndex = frameIdx,
            timestampNs = frameTimestampNs,
            timestampMs = System.currentTimeMillis(),
            width = width,
            height = height,
            sensorOrientation = sensorOrientation,
            isFrontFacing = isFrontFacing,
            saveMirrored = saveMirrored,
            yuvData = pooledBuffer,
            targetFps = activeFps
        )

        // Offer to bounded queue; safely drop frame if queue is full to avoid OOM
        val enqueued = frameQueue.offer(burstFrame)
        if (enqueued) {
            _liveCaptureCount.value = frameIdx
            // Trigger rapid continuous machine-gun shutter audio for every captured frame
            CameraSoundManager.playBurstShutter()
        } else {
            Log.w(TAG, "Consumer queue full ($QUEUE_CAPACITY). Dropping frame $frameIdx to prevent OOM")
            pool.recycle(pooledBuffer)
        }
    }

    /**
     * Producer: CameraX ImageAnalysis continuous stream callback.
     */
    fun onImageProxyAvailable(
        imageProxy: ImageProxy,
        sensorOrientation: Int,
        isFrontFacing: Boolean,
        saveMirrored: Boolean
    ) {
        if (!isBurstActive.get()) {
            imageProxy.close()
            return
        }

        val frameTimestampNs = imageProxy.imageInfo.timestamp
        val minIntervalNs = 1_000_000_000L / activeFps

        if (lastAcceptedTimestampNs > 0L && (frameTimestampNs - lastAcceptedTimestampNs) < (minIntervalNs * 0.90)) {
            imageProxy.close()
            return
        }

        lastAcceptedTimestampNs = frameTimestampNs

        val width = imageProxy.width
        val height = imageProxy.height
        activeWidth = width
        activeHeight = height

        var pool = bufferPool
        if (pool == null || poolDimensions?.width != width || poolDimensions?.height != height) {
            configureBufferPool(width, height)
            pool = bufferPool
        }

        val pooledBuffer = pool?.acquire()
        if (pooledBuffer == null) {
            imageProxy.close()
            return
        }

        val success = extractNv21FromImageProxy(imageProxy, pooledBuffer, width, height)
        imageProxy.close()

        if (!success) {
            pool.recycle(pooledBuffer)
            return
        }

        val frameIdx = acquiredFrameCounter.incrementAndGet()

        val burstFrame = BurstFrame(
            burstSessionId = activeBurstSessionId,
            frameIndex = frameIdx,
            timestampNs = frameTimestampNs,
            timestampMs = System.currentTimeMillis(),
            width = width,
            height = height,
            sensorOrientation = sensorOrientation,
            isFrontFacing = isFrontFacing,
            saveMirrored = saveMirrored,
            yuvData = pooledBuffer,
            targetFps = activeFps
        )

        val enqueued = frameQueue.offer(burstFrame)
        if (enqueued) {
            _liveCaptureCount.value = frameIdx
            CameraSoundManager.playBurstShutter()
        } else {
            Log.w(TAG, "Consumer queue full ($QUEUE_CAPACITY). Dropping frame $frameIdx to prevent OOM")
            pool.recycle(pooledBuffer)
        }
    }

    /**
     * Consumer: Background worker processing frame from bounded queue.
     * Converts NV21 to native JPEG, injects Google Camera XMP packet (GCamera:BurstID, BurstPrimary, BurstIndex)
     * so Google Photos recognizes all frames as an individual stacked burst group, saves to MediaStore,
     * writes EXIF timestamps, updates Room entity progressively, and finishes session once drained.
     */
    private fun processQueuedFrame(frame: BurstFrame) {
        _isProcessingQueue.value = true
        try {
            val yuvImage = YuvImage(frame.yuvData, ImageFormat.NV21, frame.width, frame.height, null)
            val jpegStream = ByteArrayOutputStream(maxOf(1024 * 1024, frame.width * frame.height / 3))
            yuvImage.compressToJpeg(Rect(0, 0, frame.width, frame.height), 95, jpegStream)

            // Immediately recycle YUV byte array back to pool
            bufferPool?.recycle(frame.yuvData)

            val rawJpegBytes = jpegStream.toByteArray()
            val uri = saveJpegToStorage(
                jpegBytes = rawJpegBytes,
                burstId = frame.burstSessionId,
                frameIndex = frame.frameIndex,
                frameTimestampMs = frame.timestampMs,
                orientation = frame.sensorOrientation,
                isFront = frame.isFrontFacing,
                saveMirrored = frame.saveMirrored,
                width = frame.width,
                height = frame.height
            )

            if (uri != null) {
                savedUrisMap[frame.frameIndex] = uri.toString()
                if (coverPhotoUri == null) {
                    coverPhotoUri = uri
                }
                updateBurstEntityProgressive(frame.burstSessionId)
            }

            val processed = processedFrameCounter.incrementAndGet()
            val acquired = acquiredFrameCounter.get()

            // If capture has stopped and all queued frames are processed, complete session
            if (!isBurstActive.get() && processed >= acquired && acquired > 0 && frameQueue.isEmpty()) {
                completeBurstSequence(frame.burstSessionId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to process burst frame ${frame.frameIndex}", t)
            bufferPool?.recycle(frame.yuvData)
        }
    }

    private fun updateBurstEntityProgressive(burstId: String) {
        val uris = savedUrisMap.values.toList()
        if (uris.isEmpty()) return

        val cover = coverPhotoUri?.toString() ?: uris.first()
        val count = uris.size

        engineScope.launch(Dispatchers.IO) {
            val entity = UltraFastBurstEntity(
                burstId = burstId,
                coverUri = cover,
                photoUrisJson = UltraFastBurstEntity.createJsonFromUris(uris),
                frameCount = count,
                fps = activeFps,
                timestamp = System.currentTimeMillis(),
                width = activeWidth,
                height = activeHeight,
                title = "Burst ${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}",
                isCompleted = false
            )
            burstRepository.saveBurst(entity)
        }
    }

    private fun completeBurstSequence(burstId: String) {
        val orderedUris = savedUrisMap.values.toList()
        val cover = coverPhotoUri ?: (if (orderedUris.isNotEmpty()) Uri.parse(orderedUris.first()) else null)

        engineScope.launch(Dispatchers.IO) {
            if (orderedUris.isNotEmpty()) {
                val entity = UltraFastBurstEntity(
                    burstId = burstId,
                    coverUri = cover?.toString() ?: orderedUris.first(),
                    photoUrisJson = UltraFastBurstEntity.createJsonFromUris(orderedUris),
                    frameCount = orderedUris.size,
                    fps = activeFps,
                    timestamp = System.currentTimeMillis(),
                    width = activeWidth,
                    height = activeHeight,
                    title = "Burst ${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}",
                    isCompleted = true
                )
                burstRepository.saveBurst(entity)
                Log.i(TAG, "Burst sequence complete: id=$burstId, totalSaved=${orderedUris.size}")
            }

            _isProcessingQueue.value = false
            bufferPool?.clear()

            withContext(Dispatchers.Main) {
                val cb = burstCompletionCallback
                burstCompletionCallback = null
                cb?.invoke(cover)
            }
        }
    }

    /**
     * Embeds Google Camera XMP metadata into the JPEG bytes.
     * Google Photos checks the http://ns.google.com/photos/1.0/camera/ namespace for:
     * - GCamera:BurstID (UUID linking all photos in the burst)
     * - GCamera:BurstPrimary (1 for the primary cover photo, 0 otherwise)
     * - GCamera:BurstIndex (0-based frame index)
     */
    private fun embedXmpInJpeg(jpegBytes: ByteArray, xmpXml: String): ByteArray {
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            return jpegBytes
        }
        val xmpPayload = xmpXml.toByteArray(Charsets.UTF_8)
        val segmentLength = 2 + XMP_HEADER.size + xmpPayload.size
        if (segmentLength > 65535) {
            return jpegBytes
        }

        // Insert position: right after SOI (offset 2) or after existing Exif APP1
        var insertPos = 2
        if (jpegBytes.size > 6 && jpegBytes[2] == 0xFF.toByte() && jpegBytes[3] == 0xE1.toByte()) {
            val exifLen = ((jpegBytes[4].toInt() and 0xFF) shl 8) or (jpegBytes[5].toInt() and 0xFF)
            val afterExif = 4 + exifLen
            if (afterExif in 6 until jpegBytes.size) {
                insertPos = afterExif
            }
        }

        val out = ByteArrayOutputStream(jpegBytes.size + segmentLength + 2)
        out.write(jpegBytes, 0, insertPos)

        // Write XMP APP1 Marker
        out.write(0xFF)
        out.write(0xE1)
        out.write((segmentLength shr 8) and 0xFF)
        out.write(segmentLength and 0xFF)
        out.write(XMP_HEADER)
        out.write(xmpPayload)

        out.write(jpegBytes, insertPos, jpegBytes.size - insertPos)
        return out.toByteArray()
    }

    private fun saveJpegToStorage(
        jpegBytes: ByteArray,
        burstId: String,
        frameIndex: Int,
        frameTimestampMs: Long,
        orientation: Int,
        isFront: Boolean,
        saveMirrored: Boolean,
        width: Int,
        height: Int
    ): Uri? {
        val isPrimary = (frameIndex == 1)
        val burstIndex = frameIndex - 1
        val dateBase = sessionDateString.ifEmpty {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(frameTimestampMs))
        }

        // Google Camera & Google Photos standard burst naming format:
        // Cover: IMG_YYYYMMDD_HHMMSS_BURST000_COVER.jpg
        // Consecutive: IMG_YYYYMMDD_HHMMSS_BURST001.jpg
        val seqStr = String.format(Locale.US, "%03d", burstIndex)
        val fileName = if (isPrimary) {
            "IMG_${dateBase}_BURST${seqStr}_COVER.jpg"
        } else {
            "IMG_${dateBase}_BURST${seqStr}.jpg"
        }

        val rotatedBytes: ByteArray = if (isFront && saveMirrored && orientation != 0) {
            try {
                val original = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (original != null) {
                    val matrix = Matrix().apply {
                        postScale(-1f, 1f)
                        postRotate(orientation.toFloat())
                    }
                    val transformed = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                    val out = ByteArrayOutputStream()
                    transformed.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    original.recycle()
                    transformed.recycle()
                    out.toByteArray()
                } else jpegBytes
            } catch (e: Throwable) {
                jpegBytes
            }
        } else jpegBytes

        // Embed standard Google Camera XMP packet so Google Photos stacks the photos as a single Burst group
        val xmpXml = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:BurstID="$burstId" GCamera:BurstPrimary="${if (isPrimary) "1" else "0"}" GCamera:BurstIndex="$burstIndex" /></rdf:RDF></x:xmpmeta>"""
        val finalBytes = embedXmpInJpeg(rotatedBytes, xmpXml)

        val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val exifDateStr = exifDateFormat.format(Date(frameTimestampMs))
        val subSecStr = String.format(Locale.US, "%03d", frameTimestampMs % 1000)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(finalBytes)
                    os.flush()
                }

                try {
                    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        val exifOrientation = when (orientation) {
                            90 -> ExifInterface.ORIENTATION_ROTATE_90
                            180 -> ExifInterface.ORIENTATION_ROTATE_180
                            270 -> ExifInterface.ORIENTATION_ROTATE_270
                            else -> ExifInterface.ORIENTATION_NORMAL
                        }
                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateStr)
                        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSecStr)
                        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "BurstID=$burstId;BurstIndex=$burstIndex;BurstPrimary=${if (isPrimary) 1 else 0}")
                        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Burst photo")
                        exif.saveAttributes()
                    }
                } catch (ignored: Exception) {}

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri
        } else {
            val dir = File(context.getExternalFilesDir(null), "Burst")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { os ->
                os.write(finalBytes)
                os.flush()
            }
            try {
                val exif = ExifInterface(file.absolutePath)
                val exifOrientation = when (orientation) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateStr)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSecStr)
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "BurstID=$burstId;BurstIndex=$burstIndex;BurstPrimary=${if (isPrimary) 1 else 0}")
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Burst photo")
                exif.saveAttributes()
            } catch (ignored: Exception) {}

            Uri.fromFile(file)
        }
    }

    private fun extractNv21FromImage(
        image: Image,
        nv21Buffer: ByteArray,
        width: Int,
        height: Int
    ): Boolean {
        return try {
            val planes = image.planes
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            var offset = 0

            // Copy Y plane
            if (yRowStride == width && yPixelStride == 1) {
                yBuffer.position(0)
                yBuffer.get(nv21Buffer, 0, width * height)
                offset = width * height
            } else {
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    if (yPixelStride == 1) {
                        yBuffer.get(nv21Buffer, offset, width)
                        offset += width
                    } else {
                        for (col in 0 until width) {
                            nv21Buffer[offset++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                        }
                    }
                }
            }

            // Copy NV21 interleaved VU plane (V first, then U)
            val uvHeight = height / 2
            val uvWidth = width / 2

            for (row in 0 until uvHeight) {
                val vRowOffset = row * vRowStride
                val uRowOffset = row * uRowStride
                for (col in 0 until uvWidth) {
                    nv21Buffer[offset++] = vBuffer.get(vRowOffset + col * uvPixelStride)
                    nv21Buffer[offset++] = uBuffer.get(uRowOffset + col * uvPixelStride)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting NV21 from Image", e)
            false
        }
    }

    private fun extractNv21FromImageProxy(
        image: ImageProxy,
        nv21Buffer: ByteArray,
        width: Int,
        height: Int
    ): Boolean {
        return try {
            val planes = image.planes
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            var offset = 0

            if (yRowStride == width && yPixelStride == 1) {
                yBuffer.position(0)
                yBuffer.get(nv21Buffer, 0, width * height)
                offset = width * height
            } else {
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    if (yPixelStride == 1) {
                        yBuffer.get(nv21Buffer, offset, width)
                        offset += width
                    } else {
                        for (col in 0 until width) {
                            nv21Buffer[offset++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                        }
                    }
                }
            }

            val uvHeight = height / 2
            val uvWidth = width / 2

            for (row in 0 until uvHeight) {
                val vRowOffset = row * vRowStride
                val uRowOffset = row * uRowStride
                for (col in 0 until uvWidth) {
                    nv21Buffer[offset++] = vBuffer.get(vRowOffset + col * uvPixelStride)
                    nv21Buffer[offset++] = uBuffer.get(uRowOffset + col * uvPixelStride)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting NV21 from ImageProxy", e)
            false
        }
    }

    fun reset() {
        isBurstActive.set(false)
        _isHolding.value = false
        _liveCaptureCount.value = 0
        _isProcessingQueue.value = false
        frameQueue.clear()
        savedUrisMap.clear()
        bufferPool?.clear()
        burstCompletionCallback = null
    }

    fun release() {
        reset()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        backgroundProcessor.shutdown()
        bufferPool?.clear()
        bufferPool = null
        engineScope.cancel()
    }
}
