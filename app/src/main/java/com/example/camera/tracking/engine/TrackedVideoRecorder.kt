package com.example.camera.tracking.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.example.camera.tracking.model.CropWindow
import com.example.camera.tracking.model.VideoResolution
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance hardware video recorder that encodes the EXACT 3x tracked crop
 * visible in the viewfinder directly into an MP4 file.
 */
class TrackedVideoRecorder {

    companion object {
        private const val TAG = "TrackedVideoRecorder"
        private const val MIME_TYPE = "video/avc" // H.264
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1 // 1 second keyframe
    }

    private var activeWidth = 1080
    private var activeHeight = 1920
    private var activeBitRate = 8_000_000

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var muxerPfd: ParcelFileDescriptor? = null
    private var inputSurface: Surface? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false
    private val isRecording = AtomicBoolean(false)
    private var outputFile: File? = null
    private var startTimeNs: Long = 0
    private var frameIndex: Long = 0

    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    fun isRecordingActive(): Boolean = isRecording.get()

    /**
     * Prepares and starts video recording into [destinationFile] with the chosen [resolution] and [targetAspect].
     */
    @Synchronized
    fun start(
        destinationFile: File,
        resolution: VideoResolution = VideoResolution.FHD_1080P,
        targetAspect: Float? = null
    ): Boolean {
        try {
            outputFile = destinationFile

            // Calculate output width and height matching selected aspect ratio and resolution
            val baseDim = minOf(resolution.width, resolution.height)
            val maxDim = maxOf(resolution.width, resolution.height)

            if (targetAspect != null) {
                if (targetAspect > 1.3f) { // Landscape e.g. 16:9
                    activeWidth = maxDim
                    activeHeight = ((maxDim / targetAspect).toInt() / 16) * 16
                } else if (targetAspect < 0.8f) { // Portrait e.g. 9:16
                    activeWidth = baseDim
                    activeHeight = ((baseDim / targetAspect).toInt() / 16) * 16
                } else if (targetAspect in 0.95f..1.05f) { // 1:1 Square
                    val sq = (baseDim / 16) * 16
                    activeWidth = sq
                    activeHeight = sq
                } else { // 4:3 or other
                    activeWidth = (baseDim / 16) * 16
                    activeHeight = ((baseDim / targetAspect).toInt() / 16) * 16
                }
            } else {
                activeWidth = baseDim
                activeHeight = maxDim
            }

            // Ensure dimensions are positive multiples of 16 for H.264
            activeWidth = (activeWidth.coerceAtLeast(320) / 16) * 16
            activeHeight = (activeHeight.coerceAtLeast(320) / 16) * 16
            activeBitRate = resolution.bitRate

            Log.d(TAG, "Configuring VideoCodec with resolution: ${activeWidth}x${activeHeight} @ ${activeBitRate} bps")

            val format = MediaFormat.createVideoFormat(MIME_TYPE, activeWidth, activeHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, activeBitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            destinationFile.parentFile?.mkdirs()
            if (destinationFile.exists()) destinationFile.delete()
            destinationFile.createNewFile()

            mediaCodec = codec
            mediaMuxer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pfd = ParcelFileDescriptor.open(destinationFile, ParcelFileDescriptor.MODE_READ_WRITE)
                muxerPfd = pfd
                MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                MediaMuxer(destinationFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            videoTrackIndex = -1
            isMuxerStarted = false
            frameIndex = 0
            startTimeNs = System.nanoTime()
            isRecording.set(true)

            Log.d(TAG, "Recording started to ${destinationFile.absolutePath} (${activeWidth}x${activeHeight})")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            release()
            return false
        }
    }

    /**
     * Encodes a single video frame containing the EXACT cropped camera frame.
     */
    fun recordFrame(frameBitmap: Bitmap, cropWindow: CropWindow) {
        if (!isRecording.get()) return
        val surface = inputSurface ?: return
        val codec = mediaCodec ?: return

        try {
            // Draw exact 3x crop into the encoder surface
            val canvas: Canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                surface.lockHardwareCanvas()
            } else {
                surface.lockCanvas(null)
            }

            try {
                canvas.drawColor(Color.BLACK)
                val targetAspect = activeWidth.toFloat() / activeHeight.toFloat()
                val srcCropRect = AspectRatioCropEngine.calculateSourceCropRect(
                    frameBitmap.width,
                    frameBitmap.height,
                    cropWindow,
                    targetAspect
                )
                val dstRect = Rect(0, 0, activeWidth, activeHeight)
                canvas.drawBitmap(frameBitmap, srcCropRect, dstRect, paint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }

            drainEncoder(endOfStream = false)
        } catch (e: Exception) {
            Log.w(TAG, "Error recording frame: ${e.message}")
        }
    }

    /**
     * Drains encoded packets from MediaCodec to MediaMuxer.
     */
    private fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        if (endOfStream) {
            try {
                codec.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.w(TAG, "Error signalling end of stream: ${e.message}")
            }
        }

        while (true) {
            val encoderStatus = codec.dequeueOutputBuffer(bufferInfo, 2000L)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) {
                    Log.w(TAG, "Format changed after muxer started")
                } else {
                    val newFormat = codec.outputFormat
                    videoTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    isMuxerStarted = true
                    Log.d(TAG, "Muxer started with track index $videoTrackIndex")
                }
            } else if (encoderStatus >= 0) {
                val encodedData = codec.getOutputBuffer(encoderStatus)
                if (encodedData != null) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0 && isMuxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }

                    codec.releaseOutputBuffer(encoderStatus, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    /**
     * Stops video recording and closes the MP4 file cleanly.
     */
    @Synchronized
    fun stop(): File? {
        if (!isRecording.getAndSet(false)) return outputFile

        try {
            drainEncoder(endOfStream = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error draining on stop: ${e.message}")
        } finally {
            release()
        }
        return outputFile
    }

    private fun release() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing codec: ${e.message}")
        }
        mediaCodec = null

        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing input surface: ${e.message}")
        }
        inputSurface = null

        try {
            if (isMuxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing muxer: ${e.message}")
        }
        mediaMuxer = null
        try {
            muxerPfd?.close()
        } catch (ignored: Exception) {}
        muxerPfd = null
        isMuxerStarted = false
        videoTrackIndex = -1
    }
}
