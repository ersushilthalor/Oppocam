package com.example.camera.engine.prores

import android.content.Context
import android.graphics.ImageFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.camera.model.CinemaColorProfile
import com.example.camera.model.CinemaColorSpace
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages an active Apple ProRes 422 software recording session.
 *
 * Implements:
 * 1. ImageReader-based frame acquisition from Camera2 session.
 * 2. Background thread ProRes 422 10-bit intra-frame DCT encoding via [ProResEncoder].
 * 3. QuickTime MOV container muxing via [QuickTimeProResMuxer].
 * 4. High-fidelity 48kHz stereo uncompressed PCM audio recording.
 */
class ProResSoftwareRecordingSession(
    private val context: Context,
    private val destFile: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    val isAudioEnabled: Boolean,
    val colorProfile: CinemaColorProfile,
    val colorSpace: CinemaColorSpace
) {
    companion object {
        private const val TAG = "ProResSoftwareSession"
    }

    private val isRecording = AtomicBoolean(false)
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

        val isRec2020 = (colorSpace == CinemaColorSpace.REC_2020) || (colorProfile == CinemaColorProfile.REC_2020)
        val isHlg = (colorProfile == CinemaColorProfile.HLG10)

        proresEncoder = ProResEncoder(
            width = width,
            height = height,
            isRec2020 = isRec2020,
            isHlg = isHlg
        )

        proresMuxer = QuickTimeProResMuxer(
            outputFile = destFile,
            width = width,
            height = height,
            fps = fps,
            isAudioEnabled = isAudioEnabled
        ).apply {
            start()
        }

        val thread = HandlerThread("ProResWorkerThread").apply { start() }
        imageReaderThread = thread
        val handler = Handler(thread.looper)
        imageReaderHandler = handler

        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
        imageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            if (!isRecording.get()) return@setOnImageAvailableListener
            val img = try { ir.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                processImageFrame(img)
            } catch (e: Exception) {
                Log.w(TAG, "Error encoding ProRes frame", e)
            } finally {
                try { img.close() } catch (ignored: Exception) {}
            }
        }, handler)

        if (isAudioEnabled) {
            startAudioRecording()
        }

        Log.i(TAG, "ProRes 422 software recording session started: ${width}x${height} @ ${fps}fps")
        return reader.surface
    }

    private fun processImageFrame(image: Image) {
        val encoder = proresEncoder ?: return
        val muxer = proresMuxer ?: return

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yBytes = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
        val uBytes = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
        val vBytes = ByteArray(vBuf.remaining()).also { vBuf.get(it) }

        val proresFrame = encoder.encodeFrame(
            yPlane = yBytes,
            uPlane = uBytes,
            vPlane = vBytes,
            yRowStride = yPlane.rowStride,
            uRowStride = uPlane.rowStride,
            vRowStride = vPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vPixelStride = vPlane.pixelStride
        )

        muxer.writeVideoFrame(proresFrame)
    }

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
                    while (isRecording.get()) {
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
     * Stops the session, finishes the QuickTime MOV container, and returns the recorded file.
     */
    fun stop(): File? {
        if (!isRecording.getAndSet(false)) return destFile

        // Stop audio
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
        try { audioThread?.join(1500) } catch (ignored: Exception) {}
        audioThread = null

        // Stop image acquisition
        imageReader?.setOnImageAvailableListener(null, null)
        try { imageReader?.close() } catch (ignored: Exception) {}
        imageReader = null

        imageReaderThread?.quitSafely()
        try { imageReaderThread?.join(1500) } catch (ignored: Exception) {}
        imageReaderThread = null
        imageReaderHandler = null

        // Finalize QuickTime container
        try {
            proresMuxer?.finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing QuickTime ProRes muxer", e)
        }
        proresMuxer = null
        proresEncoder = null

        return if (destFile.exists() && destFile.length() > 0L) {
            Log.i(TAG, "ProRes 422 recording finalized: ${destFile.length()} bytes")
            destFile
        } else {
            null
        }
    }
}
