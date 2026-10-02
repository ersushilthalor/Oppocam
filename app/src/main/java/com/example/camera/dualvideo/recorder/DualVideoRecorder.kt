package com.example.camera.dualvideo.recorder

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class DualVideoRecorder(
    private val context: Context,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val frameRate: Int = 30,
    private val isAudioEnabled: Boolean = true,
    private val orientationHint: Int = 90
) {
    companion object {
        private const val TAG = "DualVideoRecorder"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNEL_COUNT = 2
    }

    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false
    private var videoFormatAddedTimeMs = 0L

    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private var audioThread: Thread? = null

    private var outputFile: File? = null
    private var outputUri: Uri? = null

    val isRecordingActive: Boolean get() = isRecording.get()

    fun prepare(): Surface {
        // 1. Configure Video Encoder
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, videoWidth, videoHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, calculateBitRate(videoWidth, videoHeight, frameRate))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframes
        }

        val vEncoder = MediaCodec.createEncoderByType(VIDEO_MIME)
        vEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surf = vEncoder.createInputSurface()
        inputSurface = surf
        videoEncoder = vEncoder

        // 2. Prepare Temp Video File
        val dir = File(context.cacheDir, "dual_video_temp").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tempFile = File(dir, "DUAL_VID_${timeStamp}.mp4")
        outputFile = tempFile

        mediaMuxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(orientationHint)
        }

        // 3. Configure Audio Encoder if enabled
        if (isAudioEnabled) {
            try {
                configureAudio()
            } catch (t: Throwable) {
                Log.w(TAG, "Audio recording initialization failed, proceeding with video-only", t)
            }
        }

        return surf
    }

    @SuppressLint("MissingPermission")
    private fun configureAudio() {
        val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val aEncoder = MediaCodec.createEncoderByType(AUDIO_MIME)
        aEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder = aEncoder

        val minBufSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize * 2
        )
    }

    fun start() {
        if (!isRecording.compareAndSet(false, true)) return

        videoEncoder?.start()
        audioEncoder?.start()
        try {
            audioRecord?.startRecording()
        } catch (ignored: Exception) {}

        // Video drain thread
        recordingThread = Thread({
            drainVideoEncoder()
        }, "DualVideoMuxerThread").apply { start() }

        // Audio capture & encode thread
        if (isAudioEnabled && audioRecord != null && audioEncoder != null) {
            audioThread = Thread({
                recordAudio()
            }, "DualAudioThread").apply { start() }
        }
    }

    private fun drainVideoEncoder() {
        val encoder = videoEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording.get()) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) {
                    throw RuntimeException("Format changed twice")
                }
                val newFormat = encoder.outputFormat
                videoTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                checkAndStartMuxer()
            } else if (status >= 0) {
                val encodedData = encoder.getOutputBuffer(status) ?: continue

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0 && isMuxerStarted) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    synchronized(this) {
                        try {
                            mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to write video sample", e)
                        }
                    }
                }

                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    private fun recordAudio() {
        val recorder = audioRecord ?: return
        val encoder = audioEncoder ?: return
        val audioBuffer = ByteBuffer.allocateDirect(4096)
        val bufferInfo = MediaCodec.BufferInfo()
        var audioPtsUs = 0L

        while (isRecording.get()) {
            audioBuffer.clear()
            val readBytes = recorder.read(audioBuffer, audioBuffer.capacity())
            if (readBytes > 0) {
                val inputIndex = encoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuf = encoder.getInputBuffer(inputIndex)
                    inputBuf?.clear()
                    audioBuffer.position(0)
                    inputBuf?.put(audioBuffer)
                    encoder.queueInputBuffer(inputIndex, 0, readBytes, audioPtsUs, 0)
                    audioPtsUs += (readBytes * 1_000_000L) / (AUDIO_SAMPLE_RATE * AUDIO_CHANNEL_COUNT * 2)
                }
            }

            // Drain audio encoder output
            while (true) {
                val status = encoder.dequeueOutputBuffer(bufferInfo, 0)
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    audioTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                    checkAndStartMuxer()
                } else if (status >= 0) {
                    val encoded = encoder.getOutputBuffer(status) ?: break
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0 && isMuxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        synchronized(this) {
                            try {
                                mediaMuxer?.writeSampleData(audioTrackIndex, encoded, bufferInfo)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to write audio sample", e)
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(status, false)
                } else {
                    break
                }
            }
        }
    }

    @Synchronized
    private fun checkAndStartMuxer() {
        if (!isMuxerStarted && videoTrackIndex >= 0) {
            if (videoFormatAddedTimeMs == 0L) {
                videoFormatAddedTimeMs = System.currentTimeMillis()
            }
            val audioTimedOut = (System.currentTimeMillis() - videoFormatAddedTimeMs > 600)
            // If audio enabled, wait for audio track before starting muxer (or timeout after 600ms)
            if (isAudioEnabled && audioEncoder != null && audioTrackIndex < 0 && !audioTimedOut) {
                return
            }
            try {
                mediaMuxer?.start()
                isMuxerStarted = true
                Log.i(TAG, "MediaMuxer started with videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex (audioTimedOut=$audioTimedOut)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start MediaMuxer", t)
            }
        }
    }

    fun stop(): Uri? {
        if (!isRecording.compareAndSet(true, false)) return outputUri

        try {
            audioRecord?.stop()
        } catch (ignored: Throwable) {}

        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (ignored: Throwable) {}

        try {
            recordingThread?.join(2000)
            audioThread?.join(1000)
        } catch (ignored: InterruptedException) {}

        try {
            if (isMuxerStarted) {
                mediaMuxer?.stop()
                isMuxerStarted = false
            }
            mediaMuxer?.release()
            mediaMuxer = null
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping MediaMuxer", t)
        }

        try {
            videoEncoder?.stop()
            videoEncoder?.release()
            videoEncoder = null
        } catch (ignored: Throwable) {}

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
            audioEncoder = null
        } catch (ignored: Throwable) {}

        try {
            audioRecord?.release()
            audioRecord = null
        } catch (ignored: Throwable) {}

        inputSurface?.release()
        inputSurface = null

        // Save recorded temp file to Android MediaStore
        val temp = outputFile
        if (temp != null && temp.exists() && temp.length() > 0) {
            val uri = saveToMediaStore(temp)
            outputUri = uri
            return uri
        }
        return null
    }

    private fun saveToMediaStore(file: File): Uri? {
        val resolver = context.contentResolver
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "DUAL_VID_${timeStamp}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CameraPro")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            try { file.delete() } catch (ignored: Exception) {}
            Log.i(TAG, "Dual video saved successfully to MediaStore: $uri")
            return uri
        } catch (t: Throwable) {
            Log.e(TAG, "Failed saving dual video to MediaStore", t)
            return null
        }
    }

    private fun calculateBitRate(width: Int, height: Int, fps: Int): Int {
        val pixels = width * height
        return when {
            pixels >= 1920 * 1080 -> 18_000_000 // 18 Mbps for 1080p
            pixels >= 1280 * 720 -> 10_000_000  // 10 Mbps for 720p
            else -> 4_000_000                   // 4 Mbps for 480p
        }
    }
}
