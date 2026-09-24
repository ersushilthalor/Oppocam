package com.example.camera.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.opengl.GLUtils
import com.example.camera.model.VideoAdjustments
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * High-performance hardware video mirror and color-grading transcoder.
 * Mirrors videos horizontally and/or bakes authentic 3D LUTs and 5-stage Cinema color grades
 * while preserving full frame rate, bit rate, resolution, color space, and original uncompressed audio fidelity.
 */
object VideoMirrorTranscoder {

    private const val TAG = "VideoMirrorTranscoder"
    private const val TIMEOUT_USEC = 10000L

    /**
     * Transcodes video from inputFile to outputFile with optional horizontal mirroring
     * and optional ColorMatrix filter transformation.
     * Audio track is passed through sample-by-sample without lossy re-encoding.
     */
    fun mirrorVideo(inputFile: File, outputFile: File): Boolean {
        return transcodeVideo(inputFile, outputFile, isMirrored = true, colorMatrix = null)
    }

    /**
     * Validates that an exported video file is physically valid, has reasonable size,
     * contains a readable video track, non-zero duration, and valid dimensions.
     */
    fun validateVideoFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "validateVideoFile: File does not exist: ${file.absolutePath}")
            return false
        }
        val size = file.length()
        // Reasonable size check: a valid MP4 with video frames must not be empty or tiny (< 50 KB)
        if (size < 50_000L) {
            Log.w(TAG, "validateVideoFile: File size is unreasonably small: $size bytes (threshold 50KB)")
            return false
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val width = widthStr?.toIntOrNull() ?: 0
            val height = heightStr?.toIntOrNull() ?: 0

            if (hasVideo == null && (width <= 0 || height <= 0)) {
                Log.w(TAG, "validateVideoFile: No video track found in ${file.name}")
                return false
            }
            if (durationMs <= 0L) {
                Log.w(TAG, "validateVideoFile: Invalid duration ($durationMs ms) in ${file.name}")
                return false
            }
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "validateVideoFile: Invalid video dimensions: ${width}x${height} in ${file.name}")
                return false
            }
            Log.d(TAG, "validateVideoFile: PASSED (${width}x${height}, ${durationMs}ms, $size bytes)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "validateVideoFile: MediaMetadataRetriever exception for ${file.name}", e)
            false
        } finally {
            try { retriever.release() } catch (ignored: Exception) {}
        }
    }

    fun transcodeVideo(
        inputFile: File,
        outputFile: File,
        isMirrored: Boolean,
        colorMatrix: FloatArray? = null,
        lutStripBitmap: Bitmap? = null,
        lutSize: Int = 33,
        lutIntensity: Float = 1.0f,
        orientationHint: Int? = null,
        vignette: Float = 0f,
        grain: Float = 0f,
        softLight: Float = 0f,
        videoAdjustments: VideoAdjustments? = null
    ): Boolean {
        if (!inputFile.exists() || inputFile.length() == 0L) {
            Log.e(TAG, "Input file does not exist or is empty")
            return false
        }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var eglHelper: EglSurfaceHelper? = null

        var decodedFrameCount = 0
        var renderedFrameCount = 0
        var encodedFrameCount = 0

        return try {
            extractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
            val trackCount = extractor.trackCount

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex < 0) {
                    videoTrackIndex = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                Log.e(TAG, "No video track found in input file")
                return false
            }

            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val bitrate = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
            } else {
                val pixelCount = width.toLong() * height.toLong()
                when {
                    pixelCount >= 3840L * 2160L -> 45_000_000
                    pixelCount >= 1920L * 1080L -> 20_000_000
                    else -> 12_000_000
                }
            }
            val frameRate = try {
                if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try {
                        videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                    } catch (e: Exception) {
                        videoFormat.getFloat(MediaFormat.KEY_FRAME_RATE).toInt()
                    }
                } else 30
            } catch (e: Exception) {
                30
            }.coerceIn(15, 120)

            // Accurate rotation detection: explicit orientation hint > MediaMetadataRetriever > format key
            val rotation = orientationHint ?: run {
                var detectedRot = 0
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(inputFile.absolutePath)
                    val rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    detectedRot = rotStr?.toIntOrNull() ?: 0
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read orientation from retriever", e)
                } finally {
                    try { retriever.release() } catch (ignored: Exception) {}
                }
                if (detectedRot == 0 && videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                    videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                } else {
                    detectedRot
                }
            }

            val isSourceRotatedPortrait = (rotation == 90 || rotation == 270)
            val isSourceNativePortrait = (width < height)
            val isPortraitOutput = isSourceRotatedPortrait || isSourceNativePortrait

            val outWidth = if (isPortraitOutput) minOf(width, height) else maxOf(width, height)
            val outHeight = if (isPortraitOutput) maxOf(width, height) else minOf(width, height)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // Output frames are directly rendered into their upright, natural orientation in OpenGL,
            // so container orientation metadata is always 0.
            muxer.setOrientationHint(0)

            val targetEncoderMime = try {
                val testCodec = MediaCodec.createEncoderByType(videoMime)
                testCodec.release()
                videoMime
            } catch (e: Exception) {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }

            // Configure encoder
            val encFormat = MediaFormat.createVideoFormat(targetEncoderMime, outWidth, outHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (videoFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, videoFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                }
                if (videoFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                    setInteger(MediaFormat.KEY_COLOR_RANGE, videoFormat.getInteger(MediaFormat.KEY_COLOR_RANGE))
                }
                if (videoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, videoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                }
            }

            encoder = MediaCodec.createEncoderByType(targetEncoderMime)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // Resolve actual 3D LUT size from bitmap if present
            val effectiveLutSize = if (lutStripBitmap != null && !lutStripBitmap.isRecycled) {
                lutStripBitmap.height
            } else {
                lutSize.coerceIn(2, 65)
            }

            // Setup EGL on input surface
            eglHelper = EglSurfaceHelper(inputSurface, outWidth, outHeight, lutStripBitmap, effectiveLutSize, lutIntensity)
            eglHelper.makeCurrent()

            // Configure decoder with SurfaceTexture
            val surfaceTexture = eglHelper.surfaceTexture
            val decoderSurface = Surface(surfaceTexture)

            // Prevent MediaCodec decoder from rotating frames before rendering onto SurfaceTexture.
            // muxer.setOrientationHint(rotation) will set the correct orientation metadata in the output container.
            videoFormat.setInteger(MediaFormat.KEY_ROTATION, 0)

            decoder = MediaCodec.createDecoderByType(videoMime)
            decoder.configure(videoFormat, decoderSurface, null, 0)
            decoder.start()

            extractor.selectTrack(videoTrackIndex)

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false

            // If there's an audio track, prepare muxer track
            if (audioTrackIndex >= 0 && audioFormat != null) {
                muxerAudioTrack = muxer.addTrack(audioFormat)
            }

            var decoderDone = false
            var extractorDone = false
            var encoderDone = false
            var consecutiveWaitCount = 0
            val bufferInfo = MediaCodec.BufferInfo()

            while (!encoderDone) {
                // 1. Feed extractor to decoder
                if (!extractorDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                    if (inputBufIndex >= 0) {
                        val buf = decoder.getInputBuffer(inputBufIndex)
                        if (buf != null) {
                            val sampleSize = extractor.readSampleData(buf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                extractorDone = true
                                Log.d(TAG, "Extractor completed, queued EOS to decoder")
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                val flags = extractor.sampleFlags
                                decoder.queueInputBuffer(
                                    inputBufIndex, 0, sampleSize, presentationTimeUs, flags
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. Drain decoder to Surface
                if (!decoderDone) {
                    val decStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                    if (decStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Decoder output format changed: ${decoder.outputFormat}")
                    } else if (decStatus >= 0) {
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                        // In surface-based MediaCodec decoding, valid decoded video frames have bufferInfo.size == 0.
                        // Therefore, we MUST render every valid decoded output buffer to the SurfaceTexture,
                        // and only skip rendering when the buffer is purely an EOS marker without frame data.
                        val render = !isEos || bufferInfo.size > 0
                        decoder.releaseOutputBuffer(decStatus, render)
                        if (render) {
                            decodedFrameCount++
                            if (eglHelper.awaitNewImage()) {
                                eglHelper.drawImage(
                                    isMirrored = isMirrored,
                                    rotation = rotation,
                                    colorMatrix = colorMatrix,
                                    vignette = vignette,
                                    grain = grain,
                                    softLight = softLight,
                                    videoAdjustments = videoAdjustments
                                )
                                eglHelper.setPresentationTime(bufferInfo.presentationTimeUs * 1000L)
                                eglHelper.swapBuffers()
                                renderedFrameCount++
                            } else {
                                Log.w(TAG, "awaitNewImage timed out for frame #$decodedFrameCount")
                            }
                        }
                        if (isEos) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                            Log.d(TAG, "Decoder signaled EOS: decoded=$decodedFrameCount, rendered=$renderedFrameCount")
                        }
                    }
                }

                // 3. Drain encoder to Muxer
                val encStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw IllegalStateException("Encoder format changed twice")
                    }
                    val newFormat = encoder.outputFormat
                    muxerVideoTrack = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                    Log.d(TAG, "MediaMuxer started with video track $muxerVideoTrack")
                } else if (encStatus >= 0) {
                    consecutiveWaitCount = 0
                    val encodedData = encoder.getOutputBuffer(encStatus)
                    if (encodedData != null) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
                            encodedFrameCount++
                        }
                        encoder.releaseOutputBuffer(encStatus, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                            Log.d(TAG, "Encoder reached EOS: total encoded frames = $encodedFrameCount")
                        }
                    }
                } else if (encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (decoderDone) {
                        consecutiveWaitCount++
                        if (consecutiveWaitCount > 200) {
                            Log.w(TAG, "Encoder did not signal EOS within timeout after decoder EOS; finishing drain")
                            break
                        }
                    }
                }
            }

            // Copy audio track directly
            if (audioTrackIndex >= 0 && muxerAudioTrack >= 0 && muxerStarted) {
                try {
                    extractor.unselectTrack(videoTrackIndex)
                } catch (ignored: Exception) {}
                extractor.selectTrack(audioTrackIndex)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val audioBuffer = ByteBuffer.allocateDirect(256 * 1024)
                val audioBufferInfo = MediaCodec.BufferInfo()
                var audioSampleCount = 0

                while (true) {
                    val sampleSize = extractor.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) break

                    audioBufferInfo.offset = 0
                    audioBufferInfo.size = sampleSize
                    audioBufferInfo.presentationTimeUs = extractor.sampleTime
                    audioBufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioBufferInfo)
                    audioSampleCount++
                    extractor.advance()
                }
                Log.d(TAG, "Direct audio pass-through complete: samples=$audioSampleCount")
            }

            // Codec and resource teardown before final output inspection
            try { decoder?.stop() } catch (ignored: Exception) {}
            try { decoder?.release() } catch (ignored: Exception) {}
            decoder = null

            try { encoder?.stop() } catch (ignored: Exception) {}
            try { encoder?.release() } catch (ignored: Exception) {}
            encoder = null

            try { eglHelper?.release() } catch (ignored: Exception) {}
            eglHelper = null

            try { extractor?.release() } catch (ignored: Exception) {}
            extractor = null

            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MediaMuxer", e)
            }
            try { muxer?.release() } catch (ignored: Exception) {}
            muxer = null

            Log.i(
                TAG,
                "Transcoding stats: decoded=$decodedFrameCount, rendered=$renderedFrameCount, encoded=$encodedFrameCount, outputSize=${outputFile.length()} bytes"
            )

            // Strict zero-frame check: never treat a zero-frame or near-empty export as successful
            if (decodedFrameCount == 0 || renderedFrameCount == 0 || encodedFrameCount == 0) {
                Log.e(
                    TAG,
                    "Transcode failed zero-frame check: decoded=$decodedFrameCount, rendered=$renderedFrameCount, encoded=$encodedFrameCount. Discarding output."
                )
                try { outputFile.delete() } catch (ignored: Exception) {}
                return false
            }

            // Strict file validation check
            val isValid = validateVideoFile(outputFile)
            if (!isValid) {
                Log.e(TAG, "Transcoded output file failed validation check: size=${outputFile.length()}. Discarding output.")
                try { outputFile.delete() } catch (ignored: Exception) {}
                return false
            }

            Log.i(TAG, "Video export transcoding SUCCESS: decoded=$decodedFrameCount, rendered=$renderedFrameCount, encoded=$encodedFrameCount, size=${outputFile.length()} bytes")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to transcode video, falling back to original", e)
            try { outputFile.delete() } catch (ignored: Exception) {}
            false
        } finally {
            try { decoder?.stop() } catch (ignored: Exception) {}
            try { decoder?.release() } catch (ignored: Exception) {}
            try { encoder?.stop() } catch (ignored: Exception) {}
            try { encoder?.release() } catch (ignored: Exception) {}
            try { eglHelper?.release() } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
            try { muxer?.release() } catch (ignored: Exception) {}
        }
    }

    /**
     * EGL + OpenGL ES 2.0 Surface helper to draw the decoded video texture
     * with horizontal flip and 3D LUT / ColorMatrix grade onto the encoder's input surface.
     */
    private class EglSurfaceHelper(
        private val surface: Surface,
        val width: Int,
        val height: Int,
        private val lutStripBitmap: Bitmap? = null,
        private val lutSize: Int = 33,
        private val lutIntensity: Float = 1.0f
    ) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        lateinit var surfaceTexture: SurfaceTexture
        private var handlerThread: HandlerThread? = null
        private var textureId: Int = -1
        private var lutTextureId: Int = 0
        private var program: Int = 0
        private var uMVPMatrixLoc: Int = -1
        private var uSTMatrixLoc: Int = -1
        private var sTextureLoc: Int = -1
        private var uColorMatrixLoc: Int = -1
        private var uColorOffsetLoc: Int = -1
        private var uHasColorMatrixLoc: Int = -1
        private var uLutTextureLoc: Int = -1
        private var uLutSizeLoc: Int = -1
        private var uLutIntensityLoc: Int = -1
        private var uHas3DLutLoc: Int = -1
        private var uVignetteLoc: Int = -1
        private var uGrainLoc: Int = -1
        private var uSoftLightLoc: Int = -1
        private var uHasVideoAdjustmentsLoc: Int = -1
        private var uVaExposureLoc: Int = -1
        private var uVaTonalityLoc: Int = -1
        private var uVaContrastLoc: Int = -1
        private var uVaSaturationLoc: Int = -1
        private var uVaColorVibranceLoc: Int = -1
        private var uVaHighlightsLoc: Int = -1
        private var uVaShadowsLoc: Int = -1
        private var uVaTemperatureLoc: Int = -1
        private var uVaTintLoc: Int = -1
        private var uVaCurveBlacksLoc: Int = -1
        private var uVaCurveShadowsLoc: Int = -1
        private var uVaCurveMidtonesLoc: Int = -1
        private var uVaCurveHighlightsLoc: Int = -1
        private var uVaCurveWhitesLoc: Int = -1
        private var uVaColorBalanceRLoc: Int = -1
        private var uVaColorBalanceGLoc: Int = -1
        private var uVaColorBalanceBLoc: Int = -1
        private var uVaBloomLoc: Int = -1
        private var uVaFlashLoc: Int = -1
        private var uVaHalationLoc: Int = -1
        private var aPositionLoc: Int = -1
        private var aTextureCoordLoc: Int = -1

        private val mvpMatrix = FloatArray(16)
        private val stMatrix = FloatArray(16)
        private val glColorMatrix = FloatArray(16)
        private val glColorOffset = FloatArray(4)

        @Volatile
        private var frameAvailable = false
        private val frameSyncObject = Object()

        private val vertexBuffer: FloatBuffer
        private val texCoordBuffer: FloatBuffer

        init {
            val coords = floatArrayOf(
                -1.0f, -1.0f, 0.0f,
                 1.0f, -1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f,
                 1.0f,  1.0f, 0.0f
            )
            vertexBuffer = ByteBuffer.allocateDirect(coords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(coords)
                    position(0)
                }

            val texCoords = floatArrayOf(
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
            )
            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(texCoords)
                    position(0)
                }

            initEgl()
            initGl()
        }

        private fun initEgl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw RuntimeException("eglGetDisplay failed")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize failed")
            }

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] <= 0) {
                throw RuntimeException("Unable to find matching EGLConfig")
            }
            val config = configs[0] ?: throw RuntimeException("EGLConfig is null")

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw RuntimeException("eglCreateContext failed")
            }

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw RuntimeException("eglCreateWindowSurface failed")
            }
        }

        private fun initGl() {
            makeCurrent()

            val vertexShaderCode = """
                uniform mat4 uMVPMatrix;
                uniform mat4 uSTMatrix;
                attribute vec4 aPosition;
                attribute vec4 aTextureCoord;
                varying vec2 vTextureCoord;
                void main() {
                    gl_Position = uMVPMatrix * aPosition;
                    vTextureCoord = (uSTMatrix * aTextureCoord).xy;
                }
            """.trimIndent()

            val fragmentShaderCode = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTextureCoord;
                uniform samplerExternalOES sTexture;

                uniform sampler2D uLutTexture;
                uniform float uLutSize;
                uniform float uLutIntensity;
                uniform int uHas3DLut;

                uniform mat4 uColorMatrix;
                uniform vec4 uColorOffset;
                uniform int uHasColorMatrix;

                uniform float uVignette;
                uniform float uGrain;
                uniform float uSoftLight;

                uniform int uHasVideoAdjustments;
                uniform float uVaExposure;
                uniform float uVaTonality;
                uniform float uVaContrast;
                uniform float uVaSaturation;
                uniform float uVaColorVibrance;
                uniform float uVaHighlights;
                uniform float uVaShadows;
                uniform float uVaTemperature;
                uniform float uVaTint;
                uniform float uVaCurveBlacks;
                uniform float uVaCurveShadows;
                uniform float uVaCurveMidtones;
                uniform float uVaCurveHighlights;
                uniform float uVaCurveWhites;
                uniform float uVaColorBalanceR;
                uniform float uVaColorBalanceG;
                uniform float uVaColorBalanceB;
                uniform float uVaBloom;
                uniform float uVaFlash;
                uniform float uVaHalation;

                vec3 sample3DLut(sampler2D lutTex, vec3 color, float lutSize) {
                    float maxColor = lutSize - 1.0;
                    vec3 c = clamp(color, 0.0, 1.0) * maxColor;
                    float b = c.b;
                    float b0 = floor(b);
                    float b1 = min(b0 + 1.0, maxColor);
                    float frac = b - b0;

                    float u0 = (b0 * lutSize + c.r + 0.5) / (lutSize * lutSize);
                    float v0 = (c.g + 0.5) / lutSize;

                    float u1 = (b1 * lutSize + c.r + 0.5) / (lutSize * lutSize);
                    float v1 = (c.g + 0.5) / lutSize;

                    vec3 sample0 = texture2D(lutTex, vec2(u0, v0)).rgb;
                    vec3 sample1 = texture2D(lutTex, vec2(u1, v1)).rgb;
                    return mix(sample0, sample1, frac);
                }

                void main() {
                    vec4 texColor = texture2D(sTexture, vTextureCoord);
                    vec3 curRgb = texColor.rgb;

                    if (uHasVideoAdjustments != 0) {
                        float luma = dot(curRgb, vec3(0.2126, 0.7152, 0.0722));

                        // 1. Independent Luminance-based Tonal Masks (Smoothstep parabolic curves)
                        float shadowT = 1.0 - smoothstep(0.0, 0.50, luma);
                        float shadowMask = shadowT * shadowT;
                        float shadowAdj = (uVaShadows + uVaCurveShadows) * 0.35 * shadowMask;

                        float hlT = smoothstep(0.45, 1.0, luma);
                        float hlMask = hlT * hlT;
                        float hlAdj = (uVaHighlights + uVaCurveHighlights) * 0.35 * hlMask;

                        float blackT = 1.0 - smoothstep(0.0, 0.25, luma);
                        float blackAdj = uVaCurveBlacks * 0.25 * (blackT * blackT);

                        float whiteT = smoothstep(0.75, 1.0, luma);
                        float whiteAdj = uVaCurveWhites * 0.25 * (whiteT * whiteT);

                        float midDist = abs(luma - 0.5);
                        float midMask = clamp(1.0 - 4.0 * midDist * midDist, 0.0, 1.0);
                        float midAdj = uVaCurveMidtones * 0.25 * midMask;

                        curRgb += vec3(shadowAdj + hlAdj + blackAdj + whiteAdj + midAdj);
                        curRgb += vec3(uVaTonality * 0.10);

                        // 2. Exposure & Contrast
                        if (abs(uVaExposure) > 0.001) {
                            curRgb *= pow(2.0, uVaExposure * 0.45);
                        }
                        if (abs(uVaContrast) > 0.001) {
                            float c = 1.0 + uVaContrast * 0.65;
                            curRgb = (curRgb - 0.5) * c + 0.5;
                        }

                        // 3. White Balance: Temperature & Tint
                        if (abs(uVaTemperature) > 0.001 || abs(uVaTint) > 0.001) {
                            float tFactor = uVaTemperature * 0.22;
                            float tintFactor = uVaTint * 0.18;
                            curRgb.r *= (1.0 + tFactor) * (1.0 + tintFactor * 0.5);
                            curRgb.g *= (1.0 - tintFactor);
                            curRgb.b *= (1.0 - tFactor) * (1.0 + tintFactor * 0.5);
                        }

                        // 4. Color Vibrance & Saturation
                        float totalSat = uVaSaturation + (uVaColorVibrance * 0.65);
                        if (abs(totalSat) > 0.001) {
                            float newLuma = dot(curRgb, vec3(0.2126, 0.7152, 0.0722));
                            float s = max(0.0, 1.0 + totalSat);
                            curRgb = mix(vec3(newLuma), curRgb, s);
                        }

                        // 5. RGB Balance
                        curRgb.r += uVaColorBalanceR * 0.08;
                        curRgb.g += uVaColorBalanceG * 0.08;
                        curRgb.b += uVaColorBalanceB * 0.08;

                        // 6. Spatial Effects: Bloom, Flash, Halation
                        if (uVaBloom > 0.001) {
                            float bDist = length(vTextureCoord - vec2(0.5, 0.42));
                            float bFactor = (1.0 - smoothstep(0.0, 0.65, bDist)) * uVaBloom * 0.15;
                            curRgb += vec3(1.0, 0.85, 0.3) * bFactor;
                        }
                        if (uVaHalation > 0.001) {
                            float hDist = length(vTextureCoord - 0.5);
                            float hFactor = smoothstep(0.35, 0.85, hDist) * uVaHalation * 0.15;
                            curRgb.r += hFactor;
                        }
                        if (uVaFlash > 0.001) {
                            float yDist = abs(vTextureCoord.y - 0.48);
                            float fStreak = (1.0 - smoothstep(0.0, 0.03, yDist)) * uVaFlash * 0.35;
                            curRgb += vec3(0.6, 0.8, 1.0) * fStreak;
                        }
                    } else if (uHasColorMatrix != 0) {
                        vec3 transformed = mat3(uColorMatrix) * curRgb + uColorOffset.rgb;
                        curRgb = clamp(transformed, 0.0, 1.0);
                    }

                    if (uHas3DLut != 0 && uLutIntensity > 0.001) {
                        vec3 graded = sample3DLut(uLutTexture, curRgb, uLutSize);
                        curRgb = mix(curRgb, graded, uLutIntensity);
                    }
                    if (uVignette > 0.001) {
                        vec2 uv = vTextureCoord - 0.5;
                        float d = length(uv);
                        float vFactor = 1.0 - smoothstep(0.38, 0.82, d) * uVignette;
                        curRgb *= vFactor;
                    }
                    if (uGrain > 0.001) {
                        float noise = (fract(sin(dot(vTextureCoord * 1234.5, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) * uGrain * 0.16;
                        curRgb = clamp(curRgb + noise, 0.0, 1.0);
                    }
                    if (uSoftLight > 0.001) {
                        vec3 softGlow = vec3(0.98, 0.95, 0.90) * uSoftLight * 0.10;
                        curRgb = clamp(curRgb + softGlow, 0.0, 1.0);
                    }
                    gl_FragColor = vec4(clamp(curRgb, 0.0, 1.0), texColor.a);
                }
            """.trimIndent()

            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

            val prg = GLES20.glCreateProgram()
            if (prg == 0) {
                checkGlError("glCreateProgram")
                throw RuntimeException("Failed to create GL program")
            }
            GLES20.glAttachShader(prg, vShader)
            GLES20.glAttachShader(prg, fShader)
            GLES20.glLinkProgram(prg)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prg, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                val infoLog = GLES20.glGetProgramInfoLog(prg)
                GLES20.glDeleteProgram(prg)
                throw RuntimeException("Could not link GL program: $infoLog")
            }
            program = prg

            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
            sTextureLoc = GLES20.glGetUniformLocation(program, "sTexture")
            uColorMatrixLoc = GLES20.glGetUniformLocation(program, "uColorMatrix")
            uColorOffsetLoc = GLES20.glGetUniformLocation(program, "uColorOffset")
            uHasColorMatrixLoc = GLES20.glGetUniformLocation(program, "uHasColorMatrix")
            uLutTextureLoc = GLES20.glGetUniformLocation(program, "uLutTexture")
            uLutSizeLoc = GLES20.glGetUniformLocation(program, "uLutSize")
            uLutIntensityLoc = GLES20.glGetUniformLocation(program, "uLutIntensity")
            uHas3DLutLoc = GLES20.glGetUniformLocation(program, "uHas3DLut")
            uVignetteLoc = GLES20.glGetUniformLocation(program, "uVignette")
            uGrainLoc = GLES20.glGetUniformLocation(program, "uGrain")
            uSoftLightLoc = GLES20.glGetUniformLocation(program, "uSoftLight")
            uHasVideoAdjustmentsLoc = GLES20.glGetUniformLocation(program, "uHasVideoAdjustments")
            uVaExposureLoc = GLES20.glGetUniformLocation(program, "uVaExposure")
            uVaTonalityLoc = GLES20.glGetUniformLocation(program, "uVaTonality")
            uVaContrastLoc = GLES20.glGetUniformLocation(program, "uVaContrast")
            uVaSaturationLoc = GLES20.glGetUniformLocation(program, "uVaSaturation")
            uVaColorVibranceLoc = GLES20.glGetUniformLocation(program, "uVaColorVibrance")
            uVaHighlightsLoc = GLES20.glGetUniformLocation(program, "uVaHighlights")
            uVaShadowsLoc = GLES20.glGetUniformLocation(program, "uVaShadows")
            uVaTemperatureLoc = GLES20.glGetUniformLocation(program, "uVaTemperature")
            uVaTintLoc = GLES20.glGetUniformLocation(program, "uVaTint")
            uVaCurveBlacksLoc = GLES20.glGetUniformLocation(program, "uVaCurveBlacks")
            uVaCurveShadowsLoc = GLES20.glGetUniformLocation(program, "uVaCurveShadows")
            uVaCurveMidtonesLoc = GLES20.glGetUniformLocation(program, "uVaCurveMidtones")
            uVaCurveHighlightsLoc = GLES20.glGetUniformLocation(program, "uVaCurveHighlights")
            uVaCurveWhitesLoc = GLES20.glGetUniformLocation(program, "uVaCurveWhites")
            uVaColorBalanceRLoc = GLES20.glGetUniformLocation(program, "uVaColorBalanceR")
            uVaColorBalanceGLoc = GLES20.glGetUniformLocation(program, "uVaColorBalanceG")
            uVaColorBalanceBLoc = GLES20.glGetUniformLocation(program, "uVaColorBalanceB")
            uVaBloomLoc = GLES20.glGetUniformLocation(program, "uVaBloom")
            uVaFlashLoc = GLES20.glGetUniformLocation(program, "uVaFlash")
            uVaHalationLoc = GLES20.glGetUniformLocation(program, "uVaHalation")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            checkGlError("glBindTexture external OES")

            if (lutStripBitmap != null && !lutStripBitmap.isRecycled) {
                val lutTextures = IntArray(1)
                GLES20.glGenTextures(1, lutTextures, 0)
                lutTextureId = lutTextures[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, lutStripBitmap, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                checkGlError("GLUtils.texImage2D LUT")
                Log.d(TAG, "Uploaded 3D LUT strip bitmap to OpenGL texture: id=$lutTextureId, dims=${lutStripBitmap.width}x${lutStripBitmap.height}")
            }

            // Dedicated HandlerThread for frame synchronization guarantees immediate callbacks
            // without relying on or blocking the main Android UI looper
            val thread = HandlerThread("TranscoderFrameSync").apply { start() }
            handlerThread = thread
            val syncHandler = Handler(thread.looper)

            surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener({
                    synchronized(frameSyncObject) {
                        frameAvailable = true
                        frameSyncObject.notifyAll()
                    }
                }, syncHandler)
            }

            Matrix.setIdentityM(mvpMatrix, 0)
            checkGlError("initGl complete")
        }

        fun makeCurrent() {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent failed")
            }
        }

        fun awaitNewImage(): Boolean {
            synchronized(frameSyncObject) {
                val deadline = System.currentTimeMillis() + 2500L
                while (!frameAvailable) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    try {
                        frameSyncObject.wait(remaining.coerceAtMost(250))
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                if (!frameAvailable) {
                    Log.w(TAG, "awaitNewImage: Timed out waiting for frame on SurfaceTexture")
                    return false
                }
                frameAvailable = false
            }
            return try {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(stMatrix)
                true
            } catch (e: Throwable) {
                Log.w(TAG, "awaitNewImage: surfaceTexture.updateTexImage failed", e)
                false
            }
        }

        fun drawImage(
            isMirrored: Boolean,
            rotation: Int = 0,
            colorMatrix: FloatArray? = null,
            vignette: Float = 0f,
            grain: Float = 0f,
            softLight: Float = 0f,
            videoAdjustments: VideoAdjustments? = null
        ) {
            makeCurrent()
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)

            Matrix.setIdentityM(mvpMatrix, 0)
            if (isMirrored) {
                Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
            }
            if (rotation != 0) {
                val renderAngle = (360 - rotation) % 360
                Matrix.rotateM(mvpMatrix, 0, renderAngle.toFloat(), 0f, 0f, 1f)
            }

            GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)

            val effVignette = if (videoAdjustments != null && videoAdjustments.vignette > 0f) videoAdjustments.vignette else vignette
            val effGrain = if (videoAdjustments != null && (videoAdjustments.grain + videoAdjustments.textureFilmGrain) > 0f) {
                videoAdjustments.grain + videoAdjustments.textureFilmGrain
            } else grain
            val effSoftLight = if (videoAdjustments != null && videoAdjustments.lightFxSoftLight > 0f) videoAdjustments.lightFxSoftLight else softLight

            if (uVignetteLoc != -1) GLES20.glUniform1f(uVignetteLoc, (effVignette / 100f).coerceIn(0f, 1f))
            if (uGrainLoc != -1) GLES20.glUniform1f(uGrainLoc, (effGrain / 100f).coerceIn(0f, 1f))
            if (uSoftLightLoc != -1) GLES20.glUniform1f(uSoftLightLoc, (effSoftLight / 100f).coerceIn(0f, 1f))

            if (videoAdjustments != null && !videoAdjustments.isDefault) {
                if (uHasVideoAdjustmentsLoc != -1) GLES20.glUniform1i(uHasVideoAdjustmentsLoc, 1)
                if (uVaExposureLoc != -1) GLES20.glUniform1f(uVaExposureLoc, videoAdjustments.exposure)
                if (uVaTonalityLoc != -1) GLES20.glUniform1f(uVaTonalityLoc, videoAdjustments.tonality / 100f)
                if (uVaContrastLoc != -1) GLES20.glUniform1f(uVaContrastLoc, videoAdjustments.contrast / 100f)
                if (uVaSaturationLoc != -1) GLES20.glUniform1f(uVaSaturationLoc, videoAdjustments.saturation / 100f)
                if (uVaColorVibranceLoc != -1) GLES20.glUniform1f(uVaColorVibranceLoc, videoAdjustments.colorVibrance / 100f)
                if (uVaHighlightsLoc != -1) GLES20.glUniform1f(uVaHighlightsLoc, videoAdjustments.highlights / 100f)
                if (uVaShadowsLoc != -1) GLES20.glUniform1f(uVaShadowsLoc, videoAdjustments.shadows / 100f)
                if (uVaTemperatureLoc != -1) GLES20.glUniform1f(uVaTemperatureLoc, videoAdjustments.temperature / 100f)
                if (uVaTintLoc != -1) GLES20.glUniform1f(uVaTintLoc, videoAdjustments.tint / 100f)
                if (uVaCurveBlacksLoc != -1) GLES20.glUniform1f(uVaCurveBlacksLoc, videoAdjustments.curveBlacks / 100f)
                if (uVaCurveShadowsLoc != -1) GLES20.glUniform1f(uVaCurveShadowsLoc, videoAdjustments.curveShadows / 100f)
                if (uVaCurveMidtonesLoc != -1) GLES20.glUniform1f(uVaCurveMidtonesLoc, videoAdjustments.curveMidtones / 100f)
                if (uVaCurveHighlightsLoc != -1) GLES20.glUniform1f(uVaCurveHighlightsLoc, videoAdjustments.curveHighlights / 100f)
                if (uVaCurveWhitesLoc != -1) GLES20.glUniform1f(uVaCurveWhitesLoc, videoAdjustments.curveWhites / 100f)
                if (uVaColorBalanceRLoc != -1) GLES20.glUniform1f(uVaColorBalanceRLoc, videoAdjustments.colorBalanceR / 100f)
                if (uVaColorBalanceGLoc != -1) GLES20.glUniform1f(uVaColorBalanceGLoc, videoAdjustments.colorBalanceG / 100f)
                if (uVaColorBalanceBLoc != -1) GLES20.glUniform1f(uVaColorBalanceBLoc, videoAdjustments.colorBalanceB / 100f)
                if (uVaBloomLoc != -1) GLES20.glUniform1f(uVaBloomLoc, videoAdjustments.lightFxBloom / 100f)
                if (uVaFlashLoc != -1) GLES20.glUniform1f(uVaFlashLoc, videoAdjustments.lightFxFlash / 100f)
                if (uVaHalationLoc != -1) GLES20.glUniform1f(uVaHalationLoc, videoAdjustments.textureHalation / 100f)
            } else {
                if (uHasVideoAdjustmentsLoc != -1) GLES20.glUniform1i(uHasVideoAdjustmentsLoc, 0)
            }

            if (colorMatrix != null && colorMatrix.size >= 20) {
                GLES20.glUniform1i(uHasColorMatrixLoc, 1)

                // Android ColorMatrix is 4x5 row-major:
                // [ 0:a,  1:b,  2:c,  3:d,  4:e,
                //   5:f,  6:g,  7:h,  8:i,  9:j,
                //  10:k, 11:l, 12:m, 13:n, 14:o,
                //  15:p, 16:q, 17:r, 18:s, 19:t ]
                //
                // OpenGL glUniformMatrix4fv expects column-major order:
                // col 0 (R weights): a, f, k, 0
                // col 1 (G weights): b, g, l, 0
                // col 2 (B weights): c, h, m, 0
                // col 3: 0, 0, 0, 1
                glColorMatrix[0] = colorMatrix[0];  glColorMatrix[1] = colorMatrix[5];  glColorMatrix[2] = colorMatrix[10]; glColorMatrix[3] = 0f
                glColorMatrix[4] = colorMatrix[1];  glColorMatrix[5] = colorMatrix[6];  glColorMatrix[6] = colorMatrix[11]; glColorMatrix[7] = 0f
                glColorMatrix[8] = colorMatrix[2];  glColorMatrix[9] = colorMatrix[7];  glColorMatrix[10] = colorMatrix[12]; glColorMatrix[11] = 0f
                glColorMatrix[12] = 0f;             glColorMatrix[13] = 0f;             glColorMatrix[14] = 0f;              glColorMatrix[15] = 1f

                // 5th column offsets normalized from [0..255] to [0..1]
                glColorOffset[0] = colorMatrix[4] / 255.0f
                glColorOffset[1] = colorMatrix[9] / 255.0f
                glColorOffset[2] = colorMatrix[14] / 255.0f
                glColorOffset[3] = colorMatrix[19] / 255.0f

                GLES20.glUniformMatrix4fv(uColorMatrixLoc, 1, false, glColorMatrix, 0)
                GLES20.glUniform4fv(uColorOffsetLoc, 1, glColorOffset, 0)
            } else {
                GLES20.glUniform1i(uHasColorMatrixLoc, 0)
            }

            if (lutTextureId != 0 && lutIntensity > 0.001f) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
                GLES20.glUniform1i(uLutTextureLoc, 1)
                GLES20.glUniform1f(uLutSizeLoc, lutSize.toFloat())
                GLES20.glUniform1f(uLutIntensityLoc, lutIntensity.coerceIn(0f, 1f))
                GLES20.glUniform1i(uHas3DLutLoc, 1)
            } else {
                GLES20.glUniform1i(uHas3DLutLoc, 0)
            }

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(sTextureLoc, 0)

            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
            GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            if (lutTextureId != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            }
            GLES20.glUseProgram(0)

            checkGlError("drawImage")
        }

        fun setPresentationTime(nsecs: Long) {
            android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        }

        fun swapBuffers(): Boolean {
            return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
            try {
                surfaceTexture.release()
            } catch (ignored: Throwable) {}

            try {
                handlerThread?.quitSafely()
            } catch (ignored: Throwable) {}
            handlerThread = null

            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            if (textureId != -1) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = -1
            }
            if (lutTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                lutTextureId = 0
            }

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) {
                checkGlError("glCreateShader type=$type")
                throw RuntimeException("Failed to create shader for type $type")
            }
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val infoLog = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Could not compile shader $type: $infoLog\nShader code:\n$shaderCode")
            }
            return shader
        }

        private fun checkGlError(op: String) {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                val msg = "$op: glError 0x${Integer.toHexString(error)}"
                Log.e(TAG, msg)
                throw RuntimeException(msg)
            }
        }
    }
}

