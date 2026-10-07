package com.example.camera.engine.prores

import android.content.Context
import android.graphics.ImageFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.camera.model.CinemaColorProfile
import com.example.camera.model.CinemaColorSpace
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages an active Apple ProRes 422 software recording session.
 *
 * Implements:
 * 1. ImageReader-based frame acquisition from Camera2 session.
 * 2. Background thread ProRes 422 10-bit intra-frame DCT encoding via [ProResEncoder].
 * 3. QuickTime MOV container muxing via [QuickTimeProResMuxer].
 * 4. High-fidelity 48kHz stereo uncompressed PCM audio recording.
 * 5. Robust error propagation, consecutive failure detection, and MOV container validation.
 */
class ProResSoftwareRecordingSession(
    private val context: Context,
    private val destFile: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    val isAudioEnabled: Boolean,
    val colorProfile: CinemaColorProfile,
    val colorSpace: CinemaColorSpace,
    val isSource10Bit: Boolean = false,
    val sourceBufferWidth: Int = width,
    val sourceBufferHeight: Int = height,
    val onError: ((Throwable) -> Unit)? = null
) {
    companion object {
        private const val TAG = "ProResSoftwareSession"
        private const val MAX_CONSECUTIVE_ERRORS = 3
    }

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val encodedFramesCount = AtomicInteger(0)
    private val consecutiveErrors = AtomicInteger(0)
    @Volatile
    private var fatalError: Throwable? = null

    fun pause() {
        if (isRecording.get() && isPaused.compareAndSet(false, true)) {
            Log.i(TAG, "ProRes software recording session paused")
        }
    }

    fun resume() {
        if (isRecording.get() && isPaused.compareAndSet(true, false)) {
            Log.i(TAG, "ProRes software recording session resumed")
        }
    }

    var actualIsSource10Bit: Boolean = isSource10Bit
        private set

    private var imageReader: ImageReader? = null
    private var imageReaderThread: HandlerThread? = null
    private var imageReaderHandler: Handler? = null

    private var proresEncoder: ProResEncoder? = null
    private var proresMuxer: QuickTimeProResMuxer? = null

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    /**
     * Initializes the session, starts the muxer and audio recorder, and returns the recording [Surface].
     */
    fun start(): Surface {
        isRecording.set(true)
        encodedFramesCount.set(0)
        consecutiveErrors.set(0)
        fatalError = null

        val isRec2020 = (colorSpace == CinemaColorSpace.REC_2020)
        val isHlg = (colorProfile == CinemaColorProfile.HLG10)

        val readerW = if (sourceBufferWidth > 0) sourceBufferWidth else width
        val readerH = if (sourceBufferHeight > 0) sourceBufferHeight else height

        // Attempt highest-bit-depth camera source stream: YCBCR_P010 on Android 13+ (API 33+)
        var reader: ImageReader? = null
        var configured10BitSource = false

        if (isSource10Bit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                reader = ImageReader.newInstance(readerW, readerH, ImageFormat.YCBCR_P010, 4)
                configured10BitSource = true
                Log.i(TAG, "Configured 10-bit YCBCR_P010 camera source ImageReader (${readerW}x${readerH})")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed creating YCBCR_P010 ImageReader, falling back to 8-bit YUV_420_888", t)
                reader = null
                configured10BitSource = false
            }
        }

        if (reader == null) {
            reader = ImageReader.newInstance(readerW, readerH, ImageFormat.YUV_420_888, 4)
            configured10BitSource = false
            Log.i(TAG, "Configured 8-bit YUV_420_888 camera source ImageReader (${readerW}x${readerH})")
        }

        actualIsSource10Bit = configured10BitSource

        proresEncoder = ProResEncoder(
            width = width,
            height = height,
            isRec2020 = isRec2020,
            isHlg = isHlg,
            isSource10Bit = configured10BitSource
        )

        val muxer = QuickTimeProResMuxer(
            outputFile = destFile,
            width = width,
            height = height,
            fps = fps,
            isAudioEnabled = isAudioEnabled,
            isRec2020 = isRec2020,
            isHlg = isHlg
        )
        try {
            muxer.start()
            proresMuxer = muxer
        } catch (e: Exception) {
            isRecording.set(false)
            Log.e(TAG, "Failed to start QuickTimeProResMuxer", e)
            throw IllegalStateException("Failed to initialize QuickTime MOV muxer: ${e.message}", e)
        }

        val thread = HandlerThread("ProResWorkerThread").apply { start() }
        imageReaderThread = thread
        val handler = Handler(thread.looper)
        imageReaderHandler = handler

        imageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            if (!isRecording.get() || fatalError != null) return@setOnImageAvailableListener
            val img = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            if (isPaused.get()) {
                try { img.close() } catch (ignored: Exception) {}
                return@setOnImageAvailableListener
            }

            try {
                processImageFrame(img)
            } catch (e: Throwable) {
                val errCount = consecutiveErrors.incrementAndGet()
                Log.e(TAG, "Error encoding ProRes frame (error count = $errCount)", e)
                if (errCount >= MAX_CONSECUTIVE_ERRORS || e is OutOfMemoryError) {
                    fatalError = e
                    isRecording.set(false)
                    onError?.invoke(e)
                }
            } finally {
                try { img.close() } catch (ignored: Exception) {}
            }
        }, handler)

        if (isAudioEnabled) {
            startAudioRecording()
        }

        Log.i(TAG, "ProRes 422 software recording session started: ${width}x${height} @ ${fps}fps (source10Bit=$actualIsSource10Bit)")
        return reader.surface
    }

    /**
     * Encodes a single test/dummy frame manually. Useful for testing and pipeline verification.
     */
    fun encodeManualFrame(
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        isSource10Bit: Boolean = actualIsSource10Bit
    ) {
        val encoder = proresEncoder ?: throw IllegalStateException("Encoder not initialized")
        val muxer = proresMuxer ?: throw IllegalStateException("Muxer not initialized")

        val proresFrame = encoder.encodeFrame(
            yPlane = yBytes,
            uPlane = uBytes,
            vPlane = vBytes,
            yRowStride = yRowStride,
            uRowStride = uRowStride,
            vRowStride = vRowStride,
            uPixelStride = uPixelStride,
            vPixelStride = vPixelStride,
            isSource10Bit = isSource10Bit
        )
        muxer.writeVideoFrame(proresFrame)
        encodedFramesCount.incrementAndGet()
    }

    private fun processImageFrame(image: Image) {
        val encoder = proresEncoder ?: return
        val muxer = proresMuxer ?: return

        val isImage10Bit = if (image.format == ImageFormat.YUV_420_888) {
            false
        } else {
            image.format == ImageFormat.YCBCR_P010 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && image.format == 0x36) ||
            actualIsSource10Bit
        }

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val imgW = image.width
        val imgH = image.height

        val (yBytes, uBytes, vBytes, yRowStride, uRowStride, vRowStride, uPixelStride, vPixelStride) = if (
            (imgW != width || imgH != height) && imgW >= width && imgH >= height
        ) {
            val cropX = ((imgW - width) / 2) and 1.inv()
            val cropY = ((imgH - height) / 2) and 1.inv()
            val bytesPerSample = if (isImage10Bit) 2 else 1

            val croppedY = ByteArray(width * height * bytesPerSample)
            val croppedU = ByteArray((width / 2) * (height / 2) * bytesPerSample)
            val croppedV = ByteArray((width / 2) * (height / 2) * bytesPerSample)

            val yStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            val uStride = uPlane.rowStride
            val uPixStride = uPlane.pixelStride
            val vStride = vPlane.rowStride
            val vPixStride = vPlane.pixelStride

            // Crop Luma (Y)
            for (r in 0 until height) {
                val srcRowByteOffset = (cropY + r) * yStride + cropX * yPixStride
                val dstRowByteOffset = r * width * bytesPerSample
                if (yPixStride == bytesPerSample) {
                    yBuf.position(srcRowByteOffset)
                    yBuf.get(croppedY, dstRowByteOffset, width * bytesPerSample)
                } else {
                    for (c in 0 until width) {
                        for (b in 0 until bytesPerSample) {
                            croppedY[dstRowByteOffset + c * bytesPerSample + b] = yBuf.get(srcRowByteOffset + c * yPixStride + b)
                        }
                    }
                }
            }

            // Crop Chroma (U & V)
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            val chromaCropX = cropX / 2
            val chromaCropY = cropY / 2

            for (r in 0 until chromaHeight) {
                val uSrcRowByteOffset = (chromaCropY + r) * uStride + chromaCropX * uPixStride
                val vSrcRowByteOffset = (chromaCropY + r) * vStride + chromaCropX * vPixStride
                val dstRowByteOffset = r * chromaWidth * bytesPerSample
                if (uPixStride == bytesPerSample && vPixStride == bytesPerSample) {
                    uBuf.position(uSrcRowByteOffset)
                    uBuf.get(croppedU, dstRowByteOffset, chromaWidth * bytesPerSample)
                    vBuf.position(vSrcRowByteOffset)
                    vBuf.get(croppedV, dstRowByteOffset, chromaWidth * bytesPerSample)
                } else {
                    for (c in 0 until chromaWidth) {
                        for (b in 0 until bytesPerSample) {
                            croppedU[dstRowByteOffset + c * bytesPerSample + b] = uBuf.get(uSrcRowByteOffset + c * uPixStride + b)
                            croppedV[dstRowByteOffset + c * bytesPerSample + b] = vBuf.get(vSrcRowByteOffset + c * vPixStride + b)
                        }
                    }
                }
            }

            val finalYRowStride = width * bytesPerSample
            val finalUVRowStride = chromaWidth * bytesPerSample
            val finalUVPixelStride = bytesPerSample

            ImageCropResult(
                yBytes = croppedY,
                uBytes = croppedU,
                vBytes = croppedV,
                yRowStride = finalYRowStride,
                uRowStride = finalUVRowStride,
                vRowStride = finalUVRowStride,
                uPixelStride = finalUVPixelStride,
                vPixelStride = finalUVPixelStride
            )
        } else {
            val yBytes = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
            val uBytes = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
            val vBytes = ByteArray(vBuf.remaining()).also { vBuf.get(it) }

            ImageCropResult(
                yBytes = yBytes,
                uBytes = uBytes,
                vBytes = vBytes,
                yRowStride = yPlane.rowStride,
                uRowStride = uPlane.rowStride,
                vRowStride = vPlane.rowStride,
                uPixelStride = uPlane.pixelStride,
                vPixelStride = vPlane.pixelStride
            )
        }

        val proresFrame = encoder.encodeFrame(
            yPlane = yBytes,
            uPlane = uBytes,
            vPlane = vBytes,
            yRowStride = yRowStride,
            uRowStride = uRowStride,
            vRowStride = vRowStride,
            uPixelStride = uPixelStride,
            vPixelStride = vPixelStride,
            isSource10Bit = isImage10Bit
        )

        muxer.writeVideoFrame(proresFrame)
        encodedFramesCount.incrementAndGet()
        consecutiveErrors.set(0)
    }

    private data class ImageCropResult(
        val yBytes: ByteArray,
        val uBytes: ByteArray,
        val vBytes: ByteArray,
        val yRowStride: Int,
        val uRowStride: Int,
        val vRowStride: Int,
        val uPixelStride: Int,
        val vPixelStride: Int
    )

    private fun startAudioRecording() {
        try {
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufSize * 2, 8192)

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record.startRecording()
                audioRecord = record

                val t = Thread({
                    val pcmBuffer = ByteArray(4096)
                    while (isRecording.get() && fatalError == null) {
                        if (isPaused.get()) {
                            try {
                                record.read(pcmBuffer, 0, pcmBuffer.size)
                                Thread.sleep(15)
                            } catch (_: Throwable) {}
                            continue
                        }
                        val read = record.read(pcmBuffer, 0, pcmBuffer.size)
                        if (read > 0) {
                            proresMuxer?.writeAudioChunk(pcmBuffer, read)
                        }
                    }
                }, "ProResAudioRecorder")
                audioThread = t
                t.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio recording initialization skipped/failed: ${e.message}")
        }
    }

    /**
     * Stops the session, finishes the QuickTime MOV container, verifies its integrity,
     * and returns the recorded file, or null if encoding/finalization failed.
     */
    fun stop(): File? {
        val wasRecording = isRecording.getAndSet(false)
        if (!wasRecording && !destFile.exists()) return null

        // 1. Stop audio recording
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
        try { audioThread?.join(1500) } catch (ignored: Exception) {}
        audioThread = null

        // 2. Stop image acquisition
        imageReader?.setOnImageAvailableListener(null, null)
        try { imageReader?.close() } catch (ignored: Exception) {}
        imageReader = null

        imageReaderThread?.quitSafely()
        try { imageReaderThread?.join(1500) } catch (ignored: Exception) {}
        imageReaderThread = null
        imageReaderHandler = null

        // 3. Check for fatal errors or empty recording
        val err = fatalError
        val frameCount = encodedFramesCount.get()

        if (err != null) {
            Log.e(TAG, "ProRes recording aborted due to fatal error: ${err.message}")
            cleanupFailedOutput()
            return null
        }

        if (frameCount == 0) {
            Log.w(TAG, "ProRes recording aborted: 0 video frames were successfully encoded")
            cleanupFailedOutput()
            return null
        }

        // 4. Finalize QuickTime container
        val finalizedOk = try {
            proresMuxer?.finish() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing QuickTime ProRes muxer", e)
            false
        }
        proresMuxer = null
        proresEncoder = null

        if (!finalizedOk || !validateMovFile(destFile)) {
            Log.e(TAG, "ProRes recording validation failed or output file is corrupt")
            cleanupFailedOutput()
            return null
        }

        Log.i(TAG, "ProRes 422 recording finalized successfully: ${destFile.length()} bytes, $frameCount frames")
        return destFile
    }

    private fun cleanupFailedOutput() {
        try {
            proresMuxer?.finish()
        } catch (ignored: Exception) {}
        proresMuxer = null
        proresEncoder = null
        try {
            if (destFile.exists()) {
                destFile.delete()
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Validates that the output file is a compliant QuickTime MOV file containing 'ftyp', 'mdat', and 'moov'.
     */
    private fun validateMovFile(file: File): Boolean {
        if (!file.exists() || file.length() < 32) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                // Check ftyp header
                val ftypSize = raf.readInt()
                val ftypType = raf.readInt()
                if (ftypType != 0x66747970 || ftypSize < 16) return@use false

                // Scan atoms to find 'mdat' and 'moov'
                var foundMdat = false
                var foundMoov = false
                val fileLen = raf.length()
                var pos = ftypSize.toLong()

                while (pos + 8 <= fileLen) {
                    raf.seek(pos)
                    val rawSize = raf.readInt().toLong() and 0xFFFFFFFFL
                    val type = raf.readInt()

                    val actualSize = when (rawSize) {
                        1L -> {
                            if (pos + 16 > fileLen) break
                            raf.readLong()
                        }
                        0L -> fileLen - pos
                        else -> rawSize
                    }

                    if (type == 0x6D646174) foundMdat = true // 'mdat'
                    if (type == 0x6D6F6F76) foundMoov = true // 'moov'

                    if (actualSize <= 0 || pos + actualSize > fileLen && rawSize != 0L) {
                        break
                    }
                    pos += actualSize
                }
                foundMdat && foundMoov
            }
        } catch (e: Exception) {
            Log.w(TAG, "Validation of MOV file ${file.name} failed with exception: ${e.message}")
            false
        }
    }
}
