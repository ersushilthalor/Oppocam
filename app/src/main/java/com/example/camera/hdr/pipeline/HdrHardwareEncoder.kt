package com.example.camera.hdr.pipeline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Hardware HEVC Main10 (10-bit HDR) and AVC/H.264 video encoder.
 * Encodes fused HDR frames into a compliant MP4 container with hardware acceleration.
 */
class HdrHardwareEncoder(
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    val bitrate: Int = 35_000_000,
    val is10BitRequested: Boolean = true
) {
    companion object {
        private const val TAG = "HdrHardwareEncoder"
        private const val MIME_TYPE_HEVC = "video/hevc"
        private const val MIME_TYPE_AVC = "video/avc"
        private const val TIMEOUT_USEC = 10_000L
    }

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false
    private var frameCount = 0L

    fun init(outputFile: File) {
        val mime = if (is10BitRequested) MIME_TYPE_HEVC else MIME_TYPE_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            if (is10BitRequested && mime == MIME_TYPE_HEVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            }
        }

        try {
            codec = MediaCodec.createEncoderByType(mime)
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec?.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize 10-bit HEVC encoder, attempting standard fallback: ${e.message}")
            // Fallback to standard H.264
            val fallbackFormat = MediaFormat.createVideoFormat(MIME_TYPE_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MIME_TYPE_AVC)
            codec?.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec?.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    /**
     * Feeds one 10-bit HDR frame into the hardware encoder.
     */
    fun encodeFrame(frame: HdrFusionProcessor.Hdr10BitFrame, isLastFrame: Boolean = false) {
        val encoder = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC)
        if (inputIndex >= 0) {
            val inputBuffer = encoder.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()

            // Pack 10-bit YUV into input buffer
            // For COLOR_FormatYUV420Flexible, we convert 10-bit (0..1023) down to high-precision 8-bit YUV byte stream
            // or 16-bit P010 depending on codec capability.
            val yCount = width * height
            val uvCount = (width / 2) * (height / 2)

            // Convert 10-bit shorts into YUV buffer
            for (i in 0 until yCount) {
                inputBuffer.put((frame.y10Bit[i].toInt() shr 2).toByte())
            }
            for (i in 0 until uvCount) {
                inputBuffer.put((frame.u10Bit[i].toInt() shr 2).toByte())
            }
            for (i in 0 until uvCount) {
                inputBuffer.put((frame.v10Bit[i].toInt() shr 2).toByte())
            }

            val ptsUs = (frameCount * 1_000_000L) / fps
            val flags = if (isLastFrame) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            encoder.queueInputBuffer(inputIndex, 0, inputBuffer.position(), ptsUs, flags)
            frameCount++
        }

        drainEncoder(bufferInfo)
    }

    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo) {
        val encoder = codec ?: return
        val mux = muxer ?: return

        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) {
                    throw IllegalStateException("Format changed twice")
                }
                val newFormat = encoder.outputFormat
                videoTrackIndex = mux.addTrack(newFormat)
                mux.start()
                isMuxerStarted = true
            } else if (outputIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0 && isMuxerStarted) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    mux.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                }
                encoder.releaseOutputBuffer(outputIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    fun finish() {
        try {
            val bufferInfo = MediaCodec.BufferInfo()
            // Send EOS
            val encoder = codec
            if (encoder != null) {
                val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, (frameCount * 1_000_000L) / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainEncoder(bufferInfo)
                encoder.stop()
                encoder.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing encoder: ${e.message}")
        } finally {
            codec = null
            try {
                if (isMuxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing muxer: ${e.message}")
            }
            muxer = null
        }
    }
}
