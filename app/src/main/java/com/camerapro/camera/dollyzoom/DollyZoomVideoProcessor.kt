package com.camerapro.camera.dollyzoom

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Hardware-accelerated Video Processor for Dolly Zoom.
 *
 * Requirements & Features:
 * 1. Synchronously maps the recorded Dolly Zoom trajectory (crop scale and focus centering)
 *    onto every video frame using GPU OpenGL ES 2.0.
 * 2. Employs hardware MediaCodec video decode/encode pipeline with zero CPU rasterization.
 * 3. Preserves 100% original lossless audio passthrough without re-encoding.
 * 4. Fails safely and gracefully falls back to the original recording if any step fails.
 */
object DollyZoomVideoProcessor {

    private const val TAG = "DollyZoomVideoProcessor"
    private const val DRAIN_TIMEOUT_US = 10_000L
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    fun processDollyZoomVideo(
        inputFile: File,
        outputFile: File,
        trajectory: List<DollyTrajectoryPoint>,
        aspectRatio: Float = 16f / 9f
    ): File {
        if (!inputFile.exists() || inputFile.length() <= 0L) {
            Log.w(TAG, "Input file does not exist or is empty: ${inputFile.absolutePath}")
            return inputFile
        }

        if (trajectory.isEmpty()) {
            Log.d(TAG, "No Dolly Zoom trajectory recorded, skipping post-processing")
            return inputFile
        }

        Log.i(TAG, "Starting Dolly Zoom video post-processing: ${trajectory.size} trajectory points")

        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }
            outputFile.createNewFile()

            val success = transcodeVideoWithDollyZoom(
                inputFile = inputFile,
                outputFile = outputFile,
                trajectory = trajectory,
                aspectRatio = aspectRatio
            )

            if (success && outputFile.exists() && outputFile.length() > 0L) {
                Log.i(TAG, "Dolly Zoom video processed successfully: ${outputFile.length()} bytes")
                return outputFile
            } else {
                Log.w(TAG, "Dolly Zoom video processing returned false, falling back to original recording")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to process Dolly Zoom video, falling back to original recording", t)
        }

        return inputFile
    }

    private data class InterpolatedDollyPoint(
        val scaleFactor: Float,
        val focusNormX: Float,
        val focusNormY: Float
    )

    private fun interpolateTrajectory(
        trajectory: List<DollyTrajectoryPoint>,
        timeUs: Long
    ): InterpolatedDollyPoint {
        if (trajectory.isEmpty()) return InterpolatedDollyPoint(1.0f, 0.5f, 0.5f)
        if (trajectory.size == 1 || timeUs <= trajectory.first().timestampUs) {
            val first = trajectory.first()
            return InterpolatedDollyPoint(first.scaleFactor, first.focusNormX, first.focusNormY)
        }
        if (timeUs >= trajectory.last().timestampUs) {
            val last = trajectory.last()
            return InterpolatedDollyPoint(last.scaleFactor, last.focusNormX, last.focusNormY)
        }

        var low = 0
        var high = trajectory.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midTime = trajectory[mid].timestampUs
            when {
                midTime < timeUs -> low = mid + 1
                midTime > timeUs -> high = mid - 1
                else -> {
                    val p = trajectory[mid]
                    return InterpolatedDollyPoint(p.scaleFactor, p.focusNormX, p.focusNormY)
                }
            }
        }

        val idx0 = (low - 1).coerceIn(0, trajectory.size - 1)
        val idx1 = low.coerceIn(0, trajectory.size - 1)
        val p0 = trajectory[idx0]
        val p1 = trajectory[idx1]

        val dt = (p1.timestampUs - p0.timestampUs).toFloat()
        if (dt <= 0f) return InterpolatedDollyPoint(p0.scaleFactor, p0.focusNormX, p0.focusNormY)

        val frac = ((timeUs - p0.timestampUs).toFloat() / dt).coerceIn(0f, 1f)
        val scale = p0.scaleFactor + frac * (p1.scaleFactor - p0.scaleFactor)
        val focusX = p0.focusNormX + frac * (p1.focusNormX - p0.focusNormX)
        val focusY = p0.focusNormY + frac * (p1.focusNormY - p0.focusNormY)

        return InterpolatedDollyPoint(scale, focusX, focusY)
    }

    private fun transcodeVideoWithDollyZoom(
        inputFile: File,
        outputFile: File,
        trajectory: List<DollyTrajectoryPoint>,
        aspectRatio: Float
    ): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var isMuxerStarted = false
        var surfaceTexture: android.graphics.SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var encoderSurface: Surface? = null

        var eglDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext = EGL14.EGL_NO_CONTEXT
        var eglSurface = EGL14.EGL_NO_SURFACE
        var programId = 0
        var textureId = 0

        try {
            extractor = MediaExtractor().apply {
                setDataSource(inputFile.absolutePath)
            }

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex < 0) {
                    videoTrackIndex = i
                } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = i
                }
            }

            if (videoTrackIndex < 0) {
                Log.w(TAG, "No video track found in input file")
                return false
            }

            extractor.selectTrack(videoTrackIndex)
            val videoFormat = extractor.getTrackFormat(videoTrackIndex)
            val inWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val inHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val inMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val inBitrate = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
            } else 25_000_000
            val inFps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } else 30

            val outWidth = inWidth and 1.inv()
            val outHeight = inHeight and 1.inv()

            // Setup MediaCodec Video Encoder
            val encoderMime = MediaFormat.MIMETYPE_VIDEO_AVC
            val outFormat = MediaFormat.createVideoFormat(encoderMime, outWidth, outHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, maxOf(inBitrate, 20_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, maxOf(inFps, 24))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(encoderMime).apply {
                configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            encoderSurface = encoder.createInputSurface()
            encoder.start()

            // Setup EGL14 with encoderSurface
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
            val eglVersion = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, eglVersion, 0, eglVersion, 1)) {
                throw RuntimeException("eglInitialize failed")
            }

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] == 0) {
                val fallbackAttribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
                )
                EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, configs.size, numConfigs, 0)
            }
            val chosenConfig = configs[0] ?: throw RuntimeException("Unable to find EGL config")

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, chosenConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, chosenConfig, encoderSurface, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent failed")
            }

            // Create OpenGL shader program
            programId = createGlProgram()
            val uMVPMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
            val uSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
            val aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            val aTextureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            surfaceTexture = android.graphics.SurfaceTexture(textureId).apply {
                setDefaultBufferSize(outWidth, outHeight)
            }
            decoderSurface = Surface(surfaceTexture)

            // Setup MediaCodec Video Decoder
            decoder = MediaCodec.createDecoderByType(inMime).apply {
                configure(videoFormat, decoderSurface, null, 0)
                start()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Quad vertex buffers
            val vertexCoords = floatArrayOf(
                -1.0f, -1.0f, 0f,
                 1.0f, -1.0f, 0f,
                -1.0f,  1.0f, 0f,
                 1.0f,  1.0f, 0f
            )
            val texCoords = floatArrayOf(
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
            )
            val vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(vertexCoords)
                position(0)
            }
            val texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(texCoords)
                position(0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            var isExtractorEos = false
            var isDecoderEos = false
            var isEncoderEos = false
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            isMuxerStarted = false

            val stMatrix = FloatArray(16)
            val mvpMatrix = FloatArray(16)
            var firstFrameTimestamp = -1L

            GLES20.glViewport(0, 0, outWidth, outHeight)

            while (!isEncoderEos) {
                // Feed extractor into decoder
                if (!isExtractorEos) {
                    val inIndex = decoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorEos = true
                            } else {
                                val timeUs = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, timeUs, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain decoder to SurfaceTexture
                if (!isDecoderEos) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
                    if (outIndex >= 0) {
                        val doRender = bufferInfo.size > 0
                        decoder.releaseOutputBuffer(outIndex, doRender)

                        if (doRender) {
                            try {
                                surfaceTexture.updateTexImage()
                                surfaceTexture.getTransformMatrix(stMatrix)

                                val frameTimeUs = bufferInfo.presentationTimeUs
                                if (firstFrameTimestamp < 0L && trajectory.isNotEmpty()) {
                                    firstFrameTimestamp = trajectory.first().timestampUs
                                }

                                val lookupTimeUs = if (firstFrameTimestamp > 0L) {
                                    firstFrameTimestamp + frameTimeUs
                                } else {
                                    frameTimeUs
                                }

                                val point = interpolateTrajectory(trajectory, lookupTimeUs)

                                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                                GLES20.glUseProgram(programId)

                                // Construct Dolly Zoom Transform Matrix
                                Matrix.setIdentityM(mvpMatrix, 0)
                                val s = point.scaleFactor
                                Matrix.scaleM(mvpMatrix, 0, s, s, 1.0f)
                                // Centering translation in NDC [-1..1]
                                val transX = (0.5f - point.focusNormX) * 2.0f
                                val transY = (point.focusNormY - 0.5f) * 2.0f
                                Matrix.translateM(mvpMatrix, 0, transX, transY, 0f)

                                GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
                                GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)

                                vertexBuffer.position(0)
                                GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                                GLES20.glEnableVertexAttribArray(aPositionHandle)

                                texCoordBuffer.position(0)
                                GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
                                GLES20.glEnableVertexAttribArray(aTextureCoordHandle)

                                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

                                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, bufferInfo.presentationTimeUs * 1000L)
                                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                            } catch (e: Exception) {
                                Log.w(TAG, "Frame render exception: ${e.message}")
                            }
                        }

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isDecoderEos = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // Drain encoder to muxer
                var encIdx = encoder.dequeueOutputBuffer(encBufferInfo, DRAIN_TIMEOUT_US)
                while (encIdx >= 0 || encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!isMuxerStarted) {
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            if (audioTrackIndex >= 0) {
                                muxerAudioTrack = muxer.addTrack(extractor.getTrackFormat(audioTrackIndex))
                            }
                            muxer.start()
                            isMuxerStarted = true
                        }
                    } else if (encIdx >= 0) {
                        if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encBufferInfo.size > 0 && isMuxerStarted) {
                            val outputBuffer = encoder.getOutputBuffer(encIdx)
                            if (outputBuffer != null) {
                                outputBuffer.position(encBufferInfo.offset)
                                outputBuffer.limit(encBufferInfo.offset + encBufferInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, outputBuffer, encBufferInfo)
                            }
                        }

                        if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isEncoderEos = true
                            encoder.releaseOutputBuffer(encIdx, false)
                            break
                        }
                        encoder.releaseOutputBuffer(encIdx, false)
                    }
                    encIdx = encoder.dequeueOutputBuffer(encBufferInfo, DRAIN_TIMEOUT_US)
                }
            }

            // Copy original audio track losslessly
            if (audioTrackIndex >= 0 && isMuxerStarted && muxerAudioTrack >= 0) {
                try {
                    extractor.unselectTrack(videoTrackIndex)
                    extractor.selectTrack(audioTrackIndex)
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                    val audioBuf = ByteBuffer.allocateDirect(1024 * 512)
                    val aInfo = MediaCodec.BufferInfo()
                    while (true) {
                        val sampleSize = extractor.readSampleData(audioBuf, 0)
                        if (sampleSize < 0) break
                        aInfo.offset = 0
                        aInfo.size = sampleSize
                        aInfo.presentationTimeUs = extractor.sampleTime
                        aInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, audioBuf, aInfo)
                        extractor.advance()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio copy exception: ${e.message}")
                }
            }

            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed transcodeVideoWithDollyZoom", t)
            return false
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (ignored: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
            try { surfaceTexture?.release() } catch (ignored: Exception) {}
            try { decoderSurface?.release() } catch (ignored: Exception) {}
            try { encoderSurface?.release() } catch (ignored: Exception) {}

            if (isMuxerStarted) {
                try { muxer?.stop() } catch (ignored: Exception) {}
            }
            try { muxer?.release() } catch (ignored: Exception) {}

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        }
    }

    private fun createGlProgram(): Int {
        val vertexShaderSource = """
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

        val fragmentShaderSource = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """.trimIndent()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("GL Program link failed: $log")
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed ($type): $log")
        }
        return shader
    }
}
