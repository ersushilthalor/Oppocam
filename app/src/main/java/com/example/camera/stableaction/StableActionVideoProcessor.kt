package com.example.camera.stableaction

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
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dedicated hardware-accelerated Video Processor for Stable Action Horizontal Lock.
 *
 * Implements:
 * 1. Mathematically exact isotropic 360° counter-rotation without stretching or distortion.
 * 2. Stable Action safe crop scaling to ensure zero black borders or unwanted zoom
 *    at any rotation angle.
 * 3. Precise synchronization between sensor trajectory timestamps and video PTS.
 * 4. Lateral translation shift compensation (gimbal effect) ported from Stable Action.
 * 5. 100% Lossless audio track passthrough without re-encoding.
 */
object StableActionVideoProcessor {

    private const val TAG = "StableActionProcessor"
    private const val DRAIN_TIMEOUT_US = 10_000L
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    data class InterpolatedMotion(
        val rollRad: Float,
        val normX: Float,
        val normY: Float
    )

    fun processHorizonLockVideo(
        inputFile: File,
        outputFile: File,
        trajectory: List<StableActionHorizonEngine.TrajectoryPoint>,
        aspectRatio: Float = 16f / 9f,
        orientationDegrees: Int = -1
    ): File {
        if (!inputFile.exists() || inputFile.length() <= 0L) {
            Log.w(TAG, "Input file does not exist or is empty: ${inputFile.absolutePath}")
            return inputFile
        }

        if (trajectory.isEmpty()) {
            Log.d(TAG, "No trajectory points recorded, skipping video post-processing")
            return inputFile
        }

        Log.i(TAG, "Starting Stable Action Horizontal Lock video processing: ${trajectory.size} trajectory points")

        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }
            outputFile.createNewFile()

            val success = transcodeVideoWithHorizonLock(
                inputFile = inputFile,
                outputFile = outputFile,
                trajectory = trajectory,
                orientationDegrees = orientationDegrees
            )

            if (success && outputFile.exists() && outputFile.length() > 0L) {
                Log.i(TAG, "Horizontal Lock video processed successfully: ${outputFile.length()} bytes")
                return outputFile
            } else {
                Log.w(TAG, "Horizontal Lock video processing failed, falling back to original recording")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to process horizontal lock video, falling back to original recording", t)
        }

        return inputFile
    }

    private fun interpolateMotion(
        trajectory: List<StableActionHorizonEngine.TrajectoryPoint>,
        timeUs: Long
    ): InterpolatedMotion {
        if (trajectory.isEmpty()) return InterpolatedMotion(0f, 0f, 0f)
        if (trajectory.size == 1 || timeUs <= trajectory.first().timestampUs) {
            val f = trajectory.first()
            return InterpolatedMotion(f.smoothedRollRad, f.normX, f.normY)
        }
        if (timeUs >= trajectory.last().timestampUs) {
            val l = trajectory.last()
            return InterpolatedMotion(l.smoothedRollRad, l.normX, l.normY)
        }

        // Binary search for nearest points
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
                    return InterpolatedMotion(p.smoothedRollRad, p.normX, p.normY)
                }
            }
        }

        val idx0 = (low - 1).coerceIn(0, trajectory.size - 1)
        val idx1 = low.coerceIn(0, trajectory.size - 1)
        val p0 = trajectory[idx0]
        val p1 = trajectory[idx1]

        val dt = (p1.timestampUs - p0.timestampUs).toFloat()
        if (dt <= 0f) return InterpolatedMotion(p0.smoothedRollRad, p0.normX, p0.normY)

        val fraction = ((timeUs - p0.timestampUs).toFloat() / dt).coerceIn(0f, 1f)
        val roll = p0.smoothedRollRad + fraction * (p1.smoothedRollRad - p0.smoothedRollRad)
        val x = p0.normX + fraction * (p1.normX - p0.normX)
        val y = p0.normY + fraction * (p1.normY - p0.normY)
        return InterpolatedMotion(roll, x, y)
    }

    private fun transcodeVideoWithHorizonLock(
        inputFile: File,
        outputFile: File,
        trajectory: List<StableActionHorizonEngine.TrajectoryPoint>,
        orientationDegrees: Int = -1
    ): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surfaceTexture: android.graphics.SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var encoderSurface: Surface? = null
        var isMuxerStarted = false

        var eglDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext = EGL14.EGL_NO_CONTEXT
        var eglSurface = EGL14.EGL_NO_SURFACE
        var programId = 0
        var textureId = 0

        try {
            // Extract accurate video rotation metadata
            val retriever = android.media.MediaMetadataRetriever()
            var inputRotation = 0
            try {
                retriever.setDataSource(inputFile.absolutePath)
                val rot = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                inputRotation = rot?.toIntOrNull() ?: 0
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract rotation via retriever", e)
            } finally {
                try { retriever.release() } catch (ignored: Exception) {}
            }

            val finalRotation = if (inputRotation != 0) inputRotation else if (orientationDegrees >= 0) orientationDegrees else 0

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

            // Setup MediaCodec Video Encoder with robust fallback
            val isHevc = inMime == MediaFormat.MIMETYPE_VIDEO_HEVC
            var encoderMime = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val outFormat = MediaFormat.createVideoFormat(encoderMime, outWidth, outHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, maxOf(inBitrate, 20_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, maxOf(inFps, 24))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = try {
                MediaCodec.createEncoderByType(encoderMime).apply {
                    configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Encoder $encoderMime failed to configure, falling back to AVC", e)
                encoderMime = MediaFormat.MIMETYPE_VIDEO_AVC
                val fallbackFormat = MediaFormat.createVideoFormat(encoderMime, outWidth, outHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, maxOf(inBitrate, 15_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, maxOf(inFps, 24))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                MediaCodec.createEncoderByType(encoderMime).apply {
                    configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
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

            val frameSyncObject = Object()
            var isFrameAvailable = false

            surfaceTexture = android.graphics.SurfaceTexture(textureId).apply {
                setDefaultBufferSize(inWidth, inHeight)
                setOnFrameAvailableListener {
                    synchronized(frameSyncObject) {
                        isFrameAvailable = true
                        frameSyncObject.notifyAll()
                    }
                }
            }
            decoderSurface = Surface(surfaceTexture)

            videoFormat.setInteger(MediaFormat.KEY_ROTATION, 0)

            decoder = MediaCodec.createDecoderByType(inMime).apply {
                configure(videoFormat, decoderSurface, null, 0)
                start()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                if (finalRotation != 0) {
                    setOrientationHint(finalRotation)
                }
            }

            val vertexBuffer = createFloatBuffer(floatArrayOf(
                -1.0f, -1.0f, 0.0f,
                 1.0f, -1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f,
                 1.0f,  1.0f, 0.0f
            ))
            val texCoordBuffer = createFloatBuffer(floatArrayOf(
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
            ))

            val mvpMatrix = FloatArray(16)
            val stMatrix = FloatArray(16)

            // Safe crop scale factor so NO black borders appear at any rotation angle (0° to 360°)
            val maxD = max(outWidth, outHeight).toFloat()
            val minD = min(outWidth, outHeight).toFloat()
            val asp = maxD / minD
            val safeCropScale = max(sqrt(1f + asp * asp) / 0.90f, 1.8518f)
            val marginNorm = (safeCropScale - 1f) * 0.5f
            val bufferAspect = outWidth.toFloat() / outHeight.toFloat()

            GLES20.glViewport(0, 0, outWidth, outHeight)

            val bufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            var isExtractorEos = false
            var isDecoderEos = false
            var isEncoderEos = false
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            isMuxerStarted = false

            var firstPtsUs: Long? = null

            while (!isEncoderEos) {
                // Feed decoder
                if (!isExtractorEos) {
                    val inIdx = decoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIdx)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorEos = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Dequeue from decoder and render to OpenGL
                if (!isDecoderEos) {
                    val decIdx = decoder.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
                    if (decIdx >= 0) {
                        val doRender = (bufferInfo.size != 0)
                        if (doRender) {
                            synchronized(frameSyncObject) {
                                isFrameAvailable = false
                            }
                        }
                        decoder.releaseOutputBuffer(decIdx, doRender)

                        if (doRender) {
                            synchronized(frameSyncObject) {
                                val deadlineMs = System.currentTimeMillis() + 200L
                                while (!isFrameAvailable && System.currentTimeMillis() < deadlineMs) {
                                    try {
                                        frameSyncObject.wait(40L)
                                    } catch (ignored: InterruptedException) {
                                        break
                                    }
                                }
                                isFrameAvailable = false
                            }
                            try {
                                surfaceTexture.updateTexImage()
                                surfaceTexture.getTransformMatrix(stMatrix)

                                if (firstPtsUs == null) {
                                    firstPtsUs = bufferInfo.presentationTimeUs
                                }
                                val relPtsUs = (bufferInfo.presentationTimeUs - (firstPtsUs ?: 0L)).coerceAtLeast(0L)

                                // Interpolate exact roll angle and lateral shift from trajectory
                                val motion = interpolateMotion(trajectory, relPtsUs)
                                val rollDeg = Math.toDegrees(motion.rollRad.toDouble()).toFloat()

                                // In Viewfinder.kt, angleDeg = -horizonRollDegrees is applied to android.graphics.Matrix.postRotate.
                                // In android.graphics.Matrix (where +Y is down), a negative angle rotates counter-clockwise.
                                // In OpenGL NDC (where +Y is up), counter-clockwise rotation around +Z is POSITIVE.
                                // Hence, rotating by +rollDeg in OpenGL counter-rotates the horizon,
                                // exactly matching the counter-rotation in Viewfinder.kt.
                                val counterRotationDegrees = rollDeg

                                // Apply isotropic counter-rotation & safe crop strictly matching Viewfinder.kt
                                Matrix.setIdentityM(mvpMatrix, 0)

                                // Lateral translation shift (gimbal effect) rotated by roll angle matching Viewfinder.kt
                                val rad = Math.toRadians(rollDeg.toDouble())
                                val cosA = kotlin.math.cos(rad).toFloat()
                                val sinA = kotlin.math.sin(rad).toFloat()
                                val rotNormX = motion.normX * cosA - motion.normY * sinA
                                val rotNormY = motion.normX * sinA + motion.normY * cosA

                                // Map screen-space shift into buffer coordinates according to finalRotation
                                val normShiftX: Float
                                val normShiftY: Float
                                when (finalRotation) {
                                    90 -> {
                                        normShiftX = -rotNormY
                                        normShiftY = rotNormX
                                    }
                                    180 -> {
                                        normShiftX = -rotNormX
                                        normShiftY = -rotNormY
                                    }
                                    270 -> {
                                        normShiftX = rotNormY
                                        normShiftY = -rotNormX
                                    }
                                    else -> {
                                        normShiftX = rotNormX
                                        normShiftY = rotNormY
                                    }
                                }
                                val transX = (normShiftX * marginNorm * 0.9f).coerceIn(-marginNorm, marginNorm)
                                val transY = (normShiftY * marginNorm * 0.9f).coerceIn(-marginNorm, marginNorm)

                                // 1. Post-stabilization translation
                                Matrix.translateM(mvpMatrix, 0, transX, transY, 0f)

                                // 2. Safe crop scale
                                Matrix.scaleM(mvpMatrix, 0, safeCropScale, safeCropScale, 1f)

                                // 3. Compensate for aspect ratio so rotation is isotropic in pixel space
                                Matrix.scaleM(mvpMatrix, 0, 1f, bufferAspect, 1f)

                                // 4. Counter-rotate to level the horizon matching Viewfinder
                                Matrix.rotateM(mvpMatrix, 0, counterRotationDegrees, 0f, 0f, 1f)

                                // 5. Invert aspect ratio compensation
                                Matrix.scaleM(mvpMatrix, 0, 1f, 1f / bufferAspect, 1f)

                                GLES20.glClearColor(0f, 0f, 0f, 1f)
                                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                                GLES20.glUseProgram(programId)

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

                // Drain encoder
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
                        }
                        encoder.releaseOutputBuffer(encIdx, false)
                    }
                    if (isEncoderEos) break
                    encIdx = encoder.dequeueOutputBuffer(encBufferInfo, 0)
                }
            }

            // Copy audio track losslessly using a dedicated extractor to prevent track seeking interference
            if (audioTrackIndex >= 0 && isMuxerStarted && muxerAudioTrack >= 0) {
                var audioExtractor: MediaExtractor? = null
                try {
                    audioExtractor = MediaExtractor().apply {
                        setDataSource(inputFile.absolutePath)
                        selectTrack(audioTrackIndex)
                    }
                    val audioBuf = ByteBuffer.allocateDirect(1024 * 256)
                    val aInfo = MediaCodec.BufferInfo()
                    while (true) {
                        val sampleSize = audioExtractor.readSampleData(audioBuf, 0)
                        if (sampleSize < 0) break
                        aInfo.offset = 0
                        aInfo.size = sampleSize
                        aInfo.presentationTimeUs = audioExtractor.sampleTime
                        aInfo.flags = audioExtractor.sampleFlags
                        try {
                            muxer.writeSampleData(muxerAudioTrack, audioBuf, aInfo)
                        } catch (e: Exception) {
                            Log.w(TAG, "Audio writeSampleData warning: ${e.message}")
                            break
                        }
                        audioExtractor.advance()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio copy exception: ${e.message}")
                } finally {
                    try { audioExtractor?.release() } catch (ignored: Exception) {}
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in Horizontal Lock transcode", e)
            return false
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (ignored: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (ignored: Exception) {}
            try {
                if (muxer != null && isMuxerStarted) {
                    try { muxer.stop() } catch (ignored: Exception) {}
                    try { muxer.release() } catch (ignored: Exception) {}
                }
            } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
            try { surfaceTexture?.release() } catch (ignored: Exception) {}
            try { decoderSurface?.release() } catch (ignored: Exception) {}
            try { encoderSurface?.release() } catch (ignored: Exception) {}

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            if (programId != 0) GLES20.glDeleteProgram(programId)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    private fun createGlProgram(): Int {
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
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """.trimIndent()

        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(coords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(coords)
        fb.position(0)
        return fb
    }
}
