package com.example.camera.motionphoto

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
import java.io.File
import kotlin.math.roundToLong

/**
 * Encodes a sequence of MotionFrames into an MP4 video clip using Android's
 * hardware-accelerated MediaCodec (H.264/AVC) and MediaMuxer.
 */
class MotionPhotoVideoEncoder {

    companion object {
        private const val TAG = "MotionPhotoEncoder"
        private const val MIME_TYPE = "video/avc" // H.264
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
        private const val BIT_RATE = 10_000_000 // 10 Mbps for crisp 1080p motion
    }

    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    /**
     * Encodes the provided [frames] into [outputFile] at 30fps.
     *
     * @param frames Ordered list of frames to encode.
     * @param outputFile Temporary MP4 file target.
     * @param targetWidth Desired video width (must be multiple of 16).
     * @param targetHeight Desired video height (must be multiple of 16).
     * @param orientationDegrees Device orientation hint for the MP4 container.
     * @return true if encoding succeeded and outputFile is non-empty.
     */
    fun encodeFrames(
        frames: List<MotionFrame>,
        outputFile: File,
        targetWidth: Int = 1080,
        targetHeight: Int = 1920,
        orientationDegrees: Int = 0
    ): Boolean {
        if (frames.isEmpty()) {
            Log.w(TAG, "No frames provided to encode")
            return false
        }

        // Align dimensions to multiples of 16 for H.264 hardware encoders
        val width = ((targetWidth.coerceAtLeast(320) + 15) / 16) * 16
        val height = ((targetHeight.coerceAtLeast(320) + 15) / 16) * 16

        var mediaCodec: MediaCodec? = null
        var mediaMuxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        var muxerPfd: ParcelFileDescriptor? = null

        try {
            if (outputFile.exists()) outputFile.delete()
            outputFile.parentFile?.mkdirs()
            outputFile.createNewFile()

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec.createInputSurface()
            mediaCodec.start()

            mediaMuxer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pfd = ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE)
                muxerPfd = pfd
                MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            if (orientationDegrees in listOf(0, 90, 180, 270)) {
                mediaMuxer.setOrientationHint(orientationDegrees)
            }

            var videoTrackIndex = -1
            var isMuxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            var sampleIndex = 0L
            val frameIntervalUs = (1_000_000L / FRAME_RATE)

            fun drainEncoder(endOfStream: Boolean) {
                val codec = mediaCodec ?: return
                val muxer = mediaMuxer ?: return

                if (endOfStream) {
                    try {
                        codec.signalEndOfInputStream()
                    } catch (e: Exception) {
                        Log.w(TAG, "signalEndOfInputStream: ${e.message}")
                    }
                }

                while (true) {
                    val status = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 15000L else 2000L)
                    if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!isMuxerStarted) {
                            val newFormat = codec.outputFormat
                            videoTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            isMuxerStarted = true
                            Log.d(TAG, "Muxer started with track index: $videoTrackIndex")
                        }
                    } else if (status >= 0) {
                        val encodedData = codec.getOutputBuffer(status)
                        if (encodedData != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size != 0 && isMuxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                // Assign strictly sequential 30 fps presentation timestamps
                                bufferInfo.presentationTimeUs = sampleIndex++ * frameIntervalUs
                                muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                            codec.releaseOutputBuffer(status, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    }
                }
            }

            // Render each frame into inputSurface with hardware pacing
            for (i in frames.indices) {
                val frame = frames[i]
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    inputSurface.lockHardwareCanvas()
                } else {
                    inputSurface.lockCanvas(null)
                }

                try {
                    canvas.drawColor(Color.BLACK)
                    val bmp = frame.bitmap
                    if (!bmp.isRecycled) {
                        val srcRect = Rect(0, 0, bmp.width, bmp.height)
                        val dstRect = Rect(0, 0, width, height)
                        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
                    }
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                drainEncoder(endOfStream = false)
                // Pacing to prevent hardware encoder queue overflow
                try {
                    Thread.sleep(6)
                } catch (ignored: Exception) {}
            }

            // Finish stream
            drainEncoder(endOfStream = true)

            Log.i(TAG, "Successfully encoded ${frames.size} frames to MP4 (${outputFile.length()} bytes)")
            return outputFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed encoding motion video", e)
            return false
        } finally {
            try { mediaCodec?.stop() } catch (ignored: Exception) {}
            try { mediaCodec?.release() } catch (ignored: Exception) {}
            try { mediaMuxer?.stop() } catch (ignored: Exception) {}
            try { mediaMuxer?.release() } catch (ignored: Exception) {}
            try { muxerPfd?.close() } catch (ignored: Exception) {}
            try { inputSurface?.release() } catch (ignored: Exception) {}
        }
    }
}
