package com.example.camera.engine.eisplus

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

/**
 * Dedicated hardware-accelerated Video Processor for EIS+ (Ultra Advanced Stabilization).
 *
 * Implements:
 * 1. Synchronously maps recorded EIS+ fused trajectory onto every video frame via GPU OpenGL ES 2.0.
 * 2. Applies rolling shutter shear/slant cancellation per-frame in vertex shader.
 * 3. Exact PTS timestamp interpolation to match live preview stabilization identically.
 * 4. Hardware MediaCodec video decode/encode pipeline with zero CPU rasterization.
 * 5. 100% Lossless audio track passthrough without re-encoding.
 * 6. Fails safely and gracefully falls back to the original recording if any issue occurs.
 */
object EisPlusVideoProcessor {

    private const val TAG = "EisPlusVideoProcessor"
    private const val DRAIN_TIMEOUT_US = 10_000L
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    fun processEisPlusVideo(
        inputFile: File,
        outputFile: File,
        trajectory: List<EisPlusTrajectoryPoint>,
        aspectRatio: Float = 16f / 9f,
        orientationDegrees: Int = -1
    ): File {
        if (!inputFile.exists() || inputFile.length() <= 0L) {
            Log.w(TAG, "Input file does not exist or is empty: ${inputFile.absolutePath}")
            return inputFile
        }

        if (trajectory.isEmpty()) {
            Log.d(TAG, "No EIS+ trajectory recorded, skipping post-processing")
            return inputFile
        }

        Log.i(TAG, "Starting EIS+ video post-processing: ${trajectory.size} trajectory points")

        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }
            outputFile.createNewFile()

            val success = transcodeVideoWithEisPlus(
                inputFile = inputFile,
                outputFile = outputFile,
                trajectory = trajectory,
                orientationDegrees = orientationDegrees
            )

            if (success && outputFile.exists() && outputFile.length() > 0L) {
                Log.i(TAG, "EIS+ video processed successfully: ${outputFile.length()} bytes")
                return outputFile
            } else {
                Log.w(TAG, "EIS+ video processing returned false, falling back to original recording")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to process EIS+ video, falling back to original recording", t)
        }

        return inputFile
    }

    private data class InterpolatedEisPoint(
        val dxNorm: Float,
        val dyNorm: Float,
        val rotationDeg: Float,
        val scaleFactor: Float,
        val shearX: Float,
        val shearY: Float
    )

    private fun interpolateTrajectory(
        trajectory: List<EisPlusTrajectoryPoint>,
        timeUs: Long
    ): InterpolatedEisPoint {
        if (trajectory.isEmpty()) return InterpolatedEisPoint(0f, 0f, 0f, 1.08f, 0f, 0f)
        if (trajectory.size == 1 || timeUs <= trajectory.first().ptsUs) {
            val f = trajectory.first()
            return InterpolatedEisPoint(f.dxNorm, f.dyNorm, f.rotationDeg, f.scaleFactor, f.shearX, f.shearY)
        }
        if (timeUs >= trajectory.last().ptsUs) {
            val l = trajectory.last()
            return InterpolatedEisPoint(l.dxNorm, l.dyNorm, l.rotationDeg, l.scaleFactor, l.shearX, l.shearY)
        }

        var low = 0
        var high = trajectory.size - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val t = trajectory[mid].ptsUs
            if (t < timeUs) {
                low = mid + 1
            } else if (t > timeUs) {
                high = mid - 1
            } else {
                val p = trajectory[mid]
                return InterpolatedEisPoint(p.dxNorm, p.dyNorm, p.rotationDeg, p.scaleFactor, p.shearX, p.shearY)
            }
        }

        val i1 = (low - 1).coerceIn(0, trajectory.size - 1)
        val i2 = low.coerceIn(0, trajectory.size - 1)
        val p1 = trajectory[i1]
        val p2 = trajectory[i2]
        val dt = (p2.ptsUs - p1.ptsUs).toFloat()
        if (dt <= 0f) return InterpolatedEisPoint(p1.dxNorm, p1.dyNorm, p1.rotationDeg, p1.scaleFactor, p1.shearX, p1.shearY)

        val frac = ((timeUs - p1.ptsUs) / dt).coerceIn(0f, 1f)
        val dx = p1.dxNorm + frac * (p2.dxNorm - p1.dxNorm)
        val dy = p1.dyNorm + frac * (p2.dyNorm - p1.dyNorm)
        val rot = p1.rotationDeg + frac * (p2.rotationDeg - p1.rotationDeg)
        val s = p1.scaleFactor + frac * (p2.scaleFactor - p1.scaleFactor)
        val sx = p1.shearX + frac * (p2.shearX - p1.shearX)
        val sy = p1.shearY + frac * (p2.shearY - p1.shearY)

        return InterpolatedEisPoint(dx, dy, rot, s, sx, sy)
    }

    private fun transcodeVideoWithEisPlus(
        inputFile: File,
        outputFile: File,
        trajectory: List<EisPlusTrajectoryPoint>,
        orientationDegrees: Int
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var glManager: EisPlusGlManager? = null

        try {
            extractor.setDataSource(inputFile.absolutePath)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var inputVideoFormat: MediaFormat? = null
            var inputAudioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex < 0) {
                    videoTrackIndex = i
                    inputVideoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = i
                    inputAudioFormat = format
                }
            }

            if (videoTrackIndex < 0 || inputVideoFormat == null) {
                Log.e(TAG, "No video track found in ${inputFile.name}")
                return false
            }

            val width = inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val inputMime = inputVideoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val bitrate = try {
                inputVideoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
            } catch (e: Exception) {
                25_000_000
            }
            val frameRate = try {
                inputVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } catch (e: Exception) {
                30
            }

            val outputMime = if (inputMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }

            val outputFormat = MediaFormat.createVideoFormat(outputMime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(outputMime)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            glManager = EisPlusGlManager(encoderInputSurface, width, height)
            val decoderOutputSurface = glManager.createDecoderSurface()

            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputVideoFormat, decoderOutputSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (orientationDegrees >= 0) {
                muxer.setOrientationHint(orientationDegrees)
            } else if (inputVideoFormat.containsKey("rotation-degrees")) {
                try {
                    muxer.setOrientationHint(inputVideoFormat.getInteger("rotation-degrees"))
                } catch (ignored: Exception) {}
            }

            extractor.selectTrack(videoTrackIndex)

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var isMuxerStarted = false

            if (audioTrackIndex >= 0 && inputAudioFormat != null) {
                muxerAudioTrack = muxer.addTrack(inputAudioFormat)
            }

            val decoderBufferInfo = MediaCodec.BufferInfo()
            val encoderBufferInfo = MediaCodec.BufferInfo()
            var isExtractorEOS = false
            var isDecoderEOS = false
            var isEncoderEOS = false

            val inputBuffer = ByteBuffer.allocate(1024 * 1024)

            while (!isEncoderEOS) {
                // Feed decoder
                if (!isExtractorEOS) {
                    val inIdx = decoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)
                        if (buf != null) {
                            val sampleSize = extractor.readSampleData(buf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorEOS = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain decoder -> GPU render -> encoder
                if (!isDecoderEOS) {
                    val outIdx = decoder.dequeueOutputBuffer(decoderBufferInfo, DRAIN_TIMEOUT_US)
                    if (outIdx >= 0) {
                        val isEOS = (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val ptsUs = decoderBufferInfo.presentationTimeUs

                        decoder.releaseOutputBuffer(outIdx, true)

                        if (!isEOS) {
                            glManager.awaitNewImage()
                            val motion = interpolateTrajectory(trajectory, ptsUs)
                            glManager.drawFrame(motion)
                            glManager.setPresentationTime(ptsUs * 1000L)
                            glManager.swapBuffers()
                        } else {
                            isDecoderEOS = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // Drain encoder -> MediaMuxer
                val encIdx = encoder.dequeueOutputBuffer(encoderBufferInfo, DRAIN_TIMEOUT_US)
                if (encIdx >= 0) {
                    if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoder.releaseOutputBuffer(encIdx, false)
                        continue
                    }

                    if (encoderBufferInfo.size > 0) {
                        if (!isMuxerStarted) {
                            muxer.start()
                            isMuxerStarted = true
                        }
                        val outBuf = encoder.getOutputBuffer(encIdx)
                        if (outBuf != null && muxerVideoTrack >= 0) {
                            outBuf.position(encoderBufferInfo.offset)
                            outBuf.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, outBuf, encoderBufferInfo)
                        }
                    }

                    val isEOS = (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(encIdx, false)
                    if (isEOS) {
                        isEncoderEOS = true
                    }
                } else if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    muxerVideoTrack = muxer.addTrack(newFormat)
                    if (!isMuxerStarted) {
                        muxer.start()
                        isMuxerStarted = true
                    }
                }
            }

            // Audio track passthrough
            if (audioTrackIndex >= 0 && isMuxerStarted && muxerAudioTrack >= 0) {
                extractor.unselectTrack(videoTrackIndex)
                extractor.selectTrack(audioTrackIndex)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val audioBufferInfo = MediaCodec.BufferInfo()
                val audioBuf = ByteBuffer.allocate(512 * 1024)

                while (true) {
                    val sampleSize = extractor.readSampleData(audioBuf, 0)
                    if (sampleSize < 0) break
                    audioBufferInfo.offset = 0
                    audioBufferInfo.size = sampleSize
                    audioBufferInfo.presentationTimeUs = extractor.sampleTime
                    audioBufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerAudioTrack, audioBuf, audioBufferInfo)
                    extractor.advance()
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in transcodeVideoWithEisPlus", e)
            return false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { glManager?.release() } catch (_: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private class EisPlusGlManager(
        private val encoderSurface: Surface,
        private val width: Int,
        private val height: Int
    ) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var oesTextureId: Int = 0
        private var surfaceTexture: android.graphics.SurfaceTexture? = null
        private var decoderSurface: Surface? = null
        private var frameAvailable = false
        private val frameSyncObject = Object()

        private var programId: Int = 0
        private var aPositionHandle: Int = -1
        private var aTextureCoordHandle: Int = -1
        private var uMVPMatrixHandle: Int = -1
        private var uSTMatrixHandle: Int = -1
        private var uShearXHandle: Int = -1
        private var uShearYHandle: Int = -1

        private val mvpMatrix = FloatArray(16)
        private val stMatrix = FloatArray(16)
        private val vertexBuffer: FloatBuffer
        private val texCoordBuffer: FloatBuffer

        init {
            val vCoords = floatArrayOf(
                -1.0f, -1.0f, 0.0f,
                 1.0f, -1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f,
                 1.0f,  1.0f, 0.0f
            )
            val tCoords = floatArrayOf(
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
            )
            vertexBuffer = ByteBuffer.allocateDirect(vCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(vCoords); position(0)
            }
            texCoordBuffer = ByteBuffer.allocateDirect(tCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(tCoords); position(0)
            }

            initEgl()
            initGl()
        }

        private fun initEgl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

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
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
            val chosenConfig = configs[0] ?: throw RuntimeException("No EGL config found")

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, chosenConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, chosenConfig, encoderSurface, surfaceAttribs, 0)

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun initGl() {
            val vShaderCode = """
                uniform mat4 uMVPMatrix;
                uniform mat4 uSTMatrix;
                uniform float uShearX;
                uniform float uShearY;
                attribute vec4 aPosition;
                attribute vec4 aTextureCoord;
                varying vec2 vTextureCoord;
                void main() {
                    vec4 pos = aPosition;
                    pos.x += uShearX * pos.y;
                    pos.y += uShearY * pos.x;
                    gl_Position = uMVPMatrix * pos;
                    vTextureCoord = (uSTMatrix * aTextureCoord).xy;
                }
            """.trimIndent()

            val fShaderCode = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTextureCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTextureCoord);
                }
            """.trimIndent()

            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vShaderCode)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fShaderCode)
            programId = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vs)
                GLES20.glAttachShader(it, fs)
                GLES20.glLinkProgram(it)
            }

            aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            aTextureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
            uMVPMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
            uSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
            uShearXHandle = GLES20.glGetUniformLocation(programId, "uShearX")
            uShearYHandle = GLES20.glGetUniformLocation(programId, "uShearY")

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            oesTextureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun compileShader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            return shader
        }

        fun createDecoderSurface(): Surface {
            surfaceTexture = android.graphics.SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener {
                    synchronized(frameSyncObject) {
                        frameAvailable = true
                        frameSyncObject.notifyAll()
                    }
                }
            }
            decoderSurface = Surface(surfaceTexture)
            return decoderSurface!!
        }

        fun awaitNewImage() {
            synchronized(frameSyncObject) {
                while (!frameAvailable) {
                    try {
                        frameSyncObject.wait(500)
                        if (!frameAvailable) break
                    } catch (ignored: InterruptedException) {}
                }
                frameAvailable = false
            }
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(stMatrix)
        }

        fun drawFrame(motion: InterpolatedEisPoint) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(programId)

            // Setup MVP matrix with translation, counter-rotation, and dynamic crop scaling
            Matrix.setIdentityM(mvpMatrix, 0)

            // 1. Counter-rotation (roll)
            if (kotlin.math.abs(motion.rotationDeg) > 0.01f) {
                Matrix.rotateM(mvpMatrix, 0, motion.rotationDeg, 0f, 0f, 1f)
            }

            // 2. Scale (dynamic crop margin to prevent black borders)
            val s = motion.scaleFactor.coerceIn(1.0f, 1.35f)
            Matrix.scaleM(mvpMatrix, 0, s, s, 1f)

            // 3. Normalized Translation shift (pitch and yaw counter-motion)
            val tx = motion.dxNorm * (1f - 1f / s) * 0.90f
            val ty = motion.dyNorm * (1f - 1f / s) * 0.90f
            Matrix.translateM(mvpMatrix, 0, tx, ty, 0f)

            GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)

            // 4. Rolling shutter shear compensation
            GLES20.glUniform1f(uShearXHandle, -motion.shearX * 0.5f)
            GLES20.glUniform1f(uShearYHandle, -motion.shearY * 0.5f)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
            GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionHandle)
            GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
        }

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        }

        fun swapBuffers() {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
            try { decoderSurface?.release() } catch (_: Exception) {}
            try { surfaceTexture?.release() } catch (_: Exception) {}
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        }
    }
}
