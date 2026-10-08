package com.example.camera.videopipeline

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated Real-Time Hardware-Accelerated Custom Video Pipeline Recorder.
 *
 * Completely bypasses the default Normal Video `MediaRecorder` and post-processing pipeline!
 * When a custom pipeline (iPhone, Samsung, Vivo) is selected:
 * 1. Camera2 streams frames directly into this recorder's OES [cameraInputSurface].
 * 2. Every frame is processed in real-time on the GPU using the selected [IVideoPipeline]'s
 *    6-stage GLSL fragment shader (Spatial Detail & Micro-Contrast Convolution -> Chromatic
 *    Adaptation & Gamut Matrix -> HDR Radiance & Highlight Knee -> Subject/Skin Tone Preservation
 *    -> Filmic S-Curve & Display Encoding).
 * 3. Processed frames are rendered directly into the hardware [MediaCodec] encoder input surface
 *    and muxed with live AAC audio into the final MP4 file.
 *
 * Because the viewfinder uses the exact same 6-stage mathematical pipeline via AGSL `RuntimeShader`,
 * the live viewfinder preview and the recorded video output are 100% consistent in real-time.
 */
class CustomVideoPipelineRecorder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val orientationHint: Int = 0,
    private val pipeline: IVideoPipeline,
    private val isFront: Boolean = false,
    private val sensorOrientation: Int = 90,
    private val deviceRotation: Int = 0,
    private val sourceBufferWidth: Int = 0,
    private val sourceBufferHeight: Int = 0
) {
    companion object {
        private const val TAG = "CustomPipelineRecorder"
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_BITRATE = 192_000
        private const val AUDIO_CHANNEL_COUNT = 2
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTextureId: Int = 0
    private var ultraWideOesTextureId: Int = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    var cameraInputSurface: Surface? = null
        private set
    private var ultraWideSurfaceTexture: SurfaceTexture? = null
    private var ultraWideInputSurface: Surface? = null

    @Volatile
    private var activeStreamSource: com.example.camera.engine.PreviewStreamSource = com.example.camera.engine.PreviewStreamSource.MAIN
    @Volatile
    private var streamPtsOffsetNs: Long = 0L
    @Volatile
    private var pendingSourceSwitchClockSync: Boolean = false
    private var lastRenderedPtsNs: Long = -1L

    fun setActiveStreamSource(source: com.example.camera.engine.PreviewStreamSource) {
        if (activeStreamSource != source) {
            activeStreamSource = source
            pendingSourceSwitchClockSync = true
        }
    }

    fun getMainInputSurface(): Surface? = cameraInputSurface

    fun getUltraWideInputSurface(): Surface? = ultraWideInputSurface

    fun updateUltraWideBufferSize(width: Int, height: Int) {
        val w = maxOf(width, height).coerceAtLeast(320)
        val h = minOf(width, height).coerceAtLeast(240)
        try { ultraWideSurfaceTexture?.setDefaultBufferSize(w, h) } catch (_: Throwable) {}
    }

    private var videoEncoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null

    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var audioThread: Thread? = null

    private var mediaMuxer: MediaMuxer? = null
    private val muxerLock = Object()
    @Volatile private var isMuxerStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var hasAudioTrack = false

    private data class QueuedSample(
        val buffer: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )
    private val pendingVideoSamples = mutableListOf<QueuedSample>()
    private val pendingAudioSamples = mutableListOf<QueuedSample>()
    private val videoFramesEncodedCount = AtomicInteger(0)
    private var videoFormatStartTime: Long = 0L
    private var baseVideoPtsUs: Long = -1L

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    @Volatile private var pauseStartNs: Long = 0L
    @Volatile private var totalPausedDurationNs: Long = 0L
    private var firstFramePtsNs: Long = -1L
    private var lastVideoPtsUs: Long = 0L
    private var lastAudioPtsUs: Long = 0L

    private val renderedFrameCount = AtomicInteger(0)

    private var programId = 0
    private var uMVPMatrixHandle = -1
    private var uSTMatrixHandle = -1
    private var uTexelSizeHandle = -1
    private var aPositionHandle = -1
    private var aTextureCoordHandle = -1

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private val localTexMatrix = FloatArray(16)
    private val matrixValues = FloatArray(9)
    private val finalTexMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1.0f, -1.0f, 0.0f,
                     1.0f, -1.0f, 0.0f,
                    -1.0f,  1.0f, 0.0f,
                     1.0f,  1.0f, 0.0f
                )
            )
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    0.0f, 1.0f,
                    1.0f, 1.0f
                )
            )
            position(0)
        }

    /**
     * Number of video frames processed and encoded by this custom pipeline recorder.
     */
    val framesProcessedCount: Int
        get() = renderedFrameCount.get()

    /**
     * Initializes the hardware Video/Audio encoders, EGL context, OES SurfaceTexture,
     * and compiles the custom pipeline's 6-stage GLSL fragment shader.
     */
    fun prepare(): Surface {
        val thread = HandlerThread("CustomPipelineGLThread").apply { start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        val latch = CountDownLatch(1)
        var initError: Throwable? = null

        handler.post {
            try {
                setupEncodersAndMuxer()
                setupEglAndGlPipeline()
            } catch (t: Throwable) {
                initError = t
                Log.e(TAG, "Failed to initialize CustomVideoPipelineRecorder for ${pipeline.displayName}", t)
            } finally {
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        initError?.let {
            releaseInternal()
            throw RuntimeException("CustomVideoPipelineRecorder init failed", it)
        }

        return cameraInputSurface ?: throw IllegalStateException("Camera input surface was not created")
    }

    private fun setupEncodersAndMuxer() {
        val safeW = (width and 1.inv()).coerceAtLeast(320)
        val safeH = (height and 1.inv()).coerceAtLeast(240)
        val safeFps = fps.coerceIn(24, 120)
        val safeBitrate = bitrate.coerceAtLeast(10_000_000)

        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, safeW, safeH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val vEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        vEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderInputSurface = vEncoder.createInputSurface()
        vEncoder.start()
        videoEncoder = vEncoder

        setupAudioPipeline()

        outputFile.parentFile?.mkdirs()
        mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            val normalizedRot = ((orientationHint % 360) + 360) % 360
            setOrientationHint(normalizedRot)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioPipeline() {
        try {
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val minBufSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufSize <= 0) {
                hasAudioTrack = false
                return
            }

            val record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                AUDIO_SAMPLE_RATE,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize * 4
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                hasAudioTrack = false
                return
            }
            audioRecord = record

            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val aEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aEncoder.start()
            audioEncoder = aEncoder
            hasAudioTrack = true
        } catch (t: Throwable) {
            Log.w(TAG, "Audio pipeline unavailable in CustomVideoPipelineRecorder, recording video-only: ${t.message}")
            hasAudioTrack = false
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
            try { audioEncoder?.release() } catch (_: Exception) {}
            audioEncoder = null
        }
    }

    private fun setupEglAndGlPipeline() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

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
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed")
        }
        val config = configs[0] ?: throw RuntimeException("EGLConfig is null")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, encoderInputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }

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

        val fragmentShaderCode = pipeline.getGlFragmentShaderCode().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Custom pipeline ${pipeline.displayName} must provide a dedicated GLSL shader")

        programId = createGlProgram(vertexShaderCode, fragmentShaderCode)
        uMVPMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        uTexelSizeHandle = GLES20.glGetUniformLocation(programId, "uTexelSize")
        aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        oesTextureId = textures[0]
        ultraWideOesTextureId = textures[1]
        for (texId in textures) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        val safeW = (width and 1.inv()).coerceAtLeast(320)
        val safeH = (height and 1.inv()).coerceAtLeast(240)

        val camBufW = if (sourceBufferWidth > 0 && sourceBufferHeight > 0) {
            maxOf(sourceBufferWidth, sourceBufferHeight)
        } else {
            maxOf(safeW, safeH)
        }
        val camBufH = if (sourceBufferWidth > 0 && sourceBufferHeight > 0) {
            minOf(sourceBufferWidth, sourceBufferHeight)
        } else {
            minOf(safeW, safeH)
        }

        val st = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(camBufW, camBufH)
            setOnFrameAvailableListener({
                onCameraFrameAvailable(isUltraWide = false)
            }, glHandler)
        }
        cameraSurfaceTexture = st
        cameraInputSurface = Surface(st)

        val uwSt = SurfaceTexture(ultraWideOesTextureId).apply {
            setDefaultBufferSize(camBufW, camBufH)
            setOnFrameAvailableListener({
                onCameraFrameAvailable(isUltraWide = true)
            }, glHandler)
        }
        ultraWideSurfaceTexture = uwSt
        ultraWideInputSurface = Surface(uwSt)

        Matrix.setIdentityM(mvpMatrix, 0)
        GLES20.glViewport(0, 0, safeW, safeH)
    }

    private fun computeCameraTexMatrix(
        stMatrix: FloatArray,
        isFront: Boolean,
        sensorOrientation: Int,
        deviceRotation: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        camBufferWidth: Int,
        camBufferHeight: Int,
        outMatrix: FloatArray
    ) {
        val m0 = stMatrix[0]
        val m1 = stMatrix[1]
        val m4 = stMatrix[4]
        val m5 = stMatrix[5]

        val offDiag = kotlin.math.abs(m1) + kotlin.math.abs(m4)
        val diag = kotlin.math.abs(m0) + kotlin.math.abs(m5)
        val isStRotated90 = offDiag > diag

        val det = m0 * m5 - m1 * m4
        val isIdentitySt = kotlin.math.abs(m0 - 1f) < 1e-4f &&
            kotlin.math.abs(m5 - 1f) < 1e-4f &&
            offDiag < 1e-4f &&
            kotlin.math.abs(stMatrix[13]) < 1e-4f
        val isStMirrored = !isIdentitySt && (offDiag + diag > 0.1f) && (det > 0f)

        val normRot = ((deviceRotation % 360) + 360) % 360
        val isLandscapeTarget = (normRot == 90 || normRot == 270)
        val isSensorSwappedInPortrait = (sensorOrientation == 90 || sensorOrientation == 270)
        val isSwapped = isSensorSwappedInPortrait xor isLandscapeTarget

        val camLong = maxOf(camBufferWidth, camBufferHeight).toFloat().coerceAtLeast(1f)
        val camShort = minOf(camBufferWidth, camBufferHeight).toFloat().coerceAtLeast(1f)
        val uprightCamW = if (isSwapped) camShort else camLong
        val uprightCamH = if (isSwapped) camLong else camShort
        val camAspect = uprightCamW / uprightCamH

        val targetAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (targetAspect > camAspect) {
            scaleX = 1.0f
            scaleY = camAspect / targetAspect
        } else {
            scaleX = targetAspect / camAspect
            scaleY = 1.0f
        }

        // Determine coordinate scaling and rotation from target viewport space to sensor buffer space
        val (effScaleY, netRotationDeg) = if (!isStRotated90) {
            if (!isFront) {
                when (normRot) {
                    0 -> (-scaleY) to 90f
                    180 -> (-scaleY) to -90f
                    else -> scaleY to 0f // Landscape 90 and 270
                }
            } else {
                when (normRot) {
                    0 -> (-scaleY) to -90f
                    180 -> (-scaleY) to 90f
                    else -> scaleY to 0f // Landscape 90 and 270
                }
            }
        } else {
            when (normRot) {
                90 -> scaleY to -90f
                270 -> scaleY to 90f
                180 -> scaleY to 180f
                else -> scaleY to 0f
            }
        }

        val matrix2d = android.graphics.Matrix().apply {
            postTranslate(-0.5f, -0.5f)
            // 1. Uniform center-crop scaling in the viewport's upright coordinate axes
            postScale(scaleX, effScaleY)
            // 2. Rotate to align with sensor buffer coordinates
            if (netRotationDeg != 0f) {
                postRotate(netRotationDeg)
            }
            // 3. Apply horizontal selfie mirror only if stMatrix hasn't already applied FLIP_H
            if (isFront != isStMirrored) {
                postScale(-1.0f, 1.0f)
            }
            postTranslate(0.5f, 0.5f)
        }

        matrix2d.getValues(matrixValues)
        localTexMatrix[0] = matrixValues[0]; localTexMatrix[1] = matrixValues[3]; localTexMatrix[2] = 0f; localTexMatrix[3] = 0f
        localTexMatrix[4] = matrixValues[1]; localTexMatrix[5] = matrixValues[4]; localTexMatrix[6] = 0f; localTexMatrix[7] = 0f
        localTexMatrix[8] = 0f;              localTexMatrix[9] = 0f;              localTexMatrix[10] = 1f; localTexMatrix[11] = 0f
        localTexMatrix[12] = matrixValues[2]; localTexMatrix[13] = matrixValues[5]; localTexMatrix[14] = 0f; localTexMatrix[15] = 1f

        Matrix.multiplyMM(outMatrix, 0, stMatrix, 0, localTexMatrix, 0)
    }

    /**
     * Starts real-time custom pipeline frame rendering and audio/video encoding.
     */
    fun start() {
        firstFramePtsNs = -1L
        lastRenderedPtsNs = -1L
        streamPtsOffsetNs = 0L
        pendingSourceSwitchClockSync = false
        totalPausedDurationNs = 0L
        pauseStartNs = 0L
        isPaused.set(false)
        isRecording.set(true)

        if (hasAudioTrack && audioRecord != null && audioEncoder != null) {
            try {
                audioRecord?.startRecording()
                audioThread = Thread({ runAudioLoop() }, "CustomPipelineAudioThread").apply { start() }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start audio thread: ${t.message}")
                hasAudioTrack = false
            }
        }
        Log.i(TAG, "CustomVideoPipelineRecorder started for pipeline=${pipeline.displayName} (${width}x${height} @ ${fps}fps)")
    }

    fun pause() {
        if (isRecording.get() && isPaused.compareAndSet(false, true)) {
            pauseStartNs = System.nanoTime()
            Log.d(TAG, "CustomVideoPipelineRecorder paused")
        }
    }

    fun resume() {
        if (isRecording.get() && isPaused.compareAndSet(true, false)) {
            val pausedDelta = System.nanoTime() - pauseStartNs
            if (pausedDelta > 0L) {
                totalPausedDurationNs += pausedDelta
            }
            Log.d(TAG, "CustomVideoPipelineRecorder resumed")
        }
    }

    private fun onCameraFrameAvailable(isUltraWide: Boolean = false) {
        val st = (if (isUltraWide) ultraWideSurfaceTexture else cameraSurfaceTexture) ?: return
        try {
            st.updateTexImage()
        } catch (t: Throwable) {
            return
        }

        if (!isRecording.get() || isPaused.get()) {
            return
        }
        val expectedSource = if (isUltraWide) com.example.camera.engine.PreviewStreamSource.ULTRAWIDE else com.example.camera.engine.PreviewStreamSource.MAIN
        if (activeStreamSource != expectedSource) {
            return
        }

        try {
            st.getTransformMatrix(stMatrix)
            val rawTimestampNs = st.timestamp.takeIf { it > 0L } ?: System.nanoTime()
            if (firstFramePtsNs < 0L) {
                firstFramePtsNs = rawTimestampNs
            }
            val frameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
            var adjustedPtsNs = rawTimestampNs - firstFramePtsNs - totalPausedDurationNs + streamPtsOffsetNs
            if (pendingSourceSwitchClockSync) {
                pendingSourceSwitchClockSync = false
                if (lastRenderedPtsNs >= 0L) {
                    val expectedNextPtsNs = lastRenderedPtsNs + frameIntervalNs
                    if (kotlin.math.abs(adjustedPtsNs - expectedNextPtsNs) > 200_000_000L) {
                        streamPtsOffsetNs += (expectedNextPtsNs - adjustedPtsNs)
                        adjustedPtsNs = expectedNextPtsNs
                    }
                }
            }
            adjustedPtsNs = adjustedPtsNs.coerceAtLeast(0L)
            if (lastRenderedPtsNs >= 0L && adjustedPtsNs <= lastRenderedPtsNs) {
                adjustedPtsNs = lastRenderedPtsNs + 1_000_000L
            }
            lastRenderedPtsNs = adjustedPtsNs

            val safeW = (width and 1.inv()).coerceAtLeast(320)
            val safeH = (height and 1.inv()).coerceAtLeast(240)

            GLES20.glViewport(0, 0, safeW, safeH)
            GLES20.glUseProgram(programId)

            computeCameraTexMatrix(
                stMatrix = stMatrix,
                isFront = isFront,
                sensorOrientation = sensorOrientation,
                deviceRotation = deviceRotation,
                viewportWidth = safeW,
                viewportHeight = safeH,
                camBufferWidth = maxOf(safeW, safeH),
                camBufferHeight = minOf(safeW, safeH),
                outMatrix = finalTexMatrix
            )

            GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, finalTexMatrix, 0)
            if (uTexelSizeHandle >= 0) {
                GLES20.glUniform2f(
                    uTexelSizeHandle,
                    1.0f / safeW.toFloat(),
                    1.0f / safeH.toFloat()
                )
            }

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, if (isUltraWide) ultraWideOesTextureId else oesTextureId)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aPositionHandle)

            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(aTextureCoordHandle)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionHandle)
            GLES20.glDisableVertexAttribArray(aTextureCoordHandle)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, adjustedPtsNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            renderedFrameCount.incrementAndGet()
            drainVideoEncoder(endOfStream = false)
        } catch (t: Throwable) {
            Log.e(TAG, "Error rendering frame in CustomVideoPipelineRecorder", t)
        }
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val encoder = videoEncoder ?: return
        if (endOfStream) {
            try {
                encoder.signalEndOfInputStream()
            } catch (t: Throwable) {
                Log.w(TAG, "signalEndOfInputStream warning: ${t.message}")
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var eosRetries = 0
        while (true) {
            val outIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) DRAIN_TIMEOUT_US else 0L)
            } catch (t: Throwable) {
                break
            }

            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++eosRetries > 25) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxerLock) {
                    if (!isMuxerStarted) {
                        videoTrackIndex = mediaMuxer?.addTrack(encoder.outputFormat) ?: -1
                        videoFormatStartTime = System.currentTimeMillis()
                        checkStartMuxerLocked()
                    }
                }
            } else if (outIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outIndex)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0 && encodedData != null) {
                    synchronized(muxerLock) {
                        if (baseVideoPtsUs < 0) {
                            baseVideoPtsUs = bufferInfo.presentationTimeUs
                        }
                        var ptsUs = bufferInfo.presentationTimeUs - baseVideoPtsUs
                        if (ptsUs < 0) ptsUs = 0
                        if (lastVideoPtsUs >= 0L && ptsUs <= lastVideoPtsUs) {
                            ptsUs = lastVideoPtsUs + 1000L
                        }
                        bufferInfo.presentationTimeUs = ptsUs
                        lastVideoPtsUs = ptsUs

                        if (isMuxerStarted && videoTrackIndex >= 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            try {
                                mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                                videoFramesEncodedCount.incrementAndGet()
                            } catch (t: Throwable) {
                                Log.w(TAG, "writeSampleData video warning: ${t.message}")
                            }
                        } else {
                            // Queue sample until MediaMuxer starts
                            try {
                                val dup = ByteBuffer.allocateDirect(bufferInfo.size)
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                dup.put(encodedData)
                                dup.flip()
                                val copyInfo = MediaCodec.BufferInfo().apply {
                                    set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                }
                                if (pendingVideoSamples.size < 90) {
                                    pendingVideoSamples.add(QueuedSample(dup, copyInfo))
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                try {
                    encoder.releaseOutputBuffer(outIndex, false)
                } catch (_: Throwable) {}

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            } else {
                break
            }
        }
    }

    private fun runAudioLoop() {
        val record = audioRecord ?: return
        val encoder = audioEncoder ?: return
        val pcmBuffer = ByteBuffer.allocateDirect(4096)
        val bufferInfo = MediaCodec.BufferInfo()
        var audioStartNs = -1L

        while (isRecording.get()) {
            if (isPaused.get()) {
                try {
                    record.read(pcmBuffer, pcmBuffer.capacity())
                    Thread.sleep(15)
                } catch (_: InterruptedException) { break } catch (_: Throwable) {}
                continue
            }

            val bytesRead = try {
                record.read(pcmBuffer, pcmBuffer.capacity())
            } catch (_: Throwable) {
                -1
            }

            if (bytesRead > 0) {
                val nowNs = System.nanoTime()
                if (audioStartNs < 0L) audioStartNs = nowNs
                val ptsUs = ((nowNs - audioStartNs - totalPausedDurationNs).coerceAtLeast(0L)) / 1000L

                val inIndex = try {
                    encoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                } catch (_: Throwable) {
                    -1
                }
                if (inIndex >= 0) {
                    val inputBuf = encoder.getInputBuffer(inIndex)
                    if (inputBuf != null) {
                        inputBuf.clear()
                        pcmBuffer.position(0)
                        pcmBuffer.limit(bytesRead.coerceAtMost(inputBuf.capacity()))
                        inputBuf.put(pcmBuffer)
                        try {
                            encoder.queueInputBuffer(inIndex, 0, pcmBuffer.limit(), ptsUs, 0)
                        } catch (_: Throwable) {}
                    }
                }
            }

            drainAudioEncoder(encoder, bufferInfo, endOfStream = false)
        }

        // Send Audio EOS
        try {
            val inIndex = encoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
            if (inIndex >= 0) {
                encoder.queueInputBuffer(inIndex, 0, 0, lastAudioPtsUs + 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainAudioEncoder(encoder, bufferInfo, endOfStream = true)
        } catch (_: Throwable) {}
    }

    private fun drainAudioEncoder(encoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        var retries = 0
        while (true) {
            val outIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) DRAIN_TIMEOUT_US else 0L)
            } catch (_: Throwable) {
                break
            }

            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++retries > 15) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxerLock) {
                    if (!isMuxerStarted) {
                        audioTrackIndex = mediaMuxer?.addTrack(encoder.outputFormat) ?: -1
                        checkStartMuxerLocked()
                    }
                }
            } else if (outIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outIndex)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0 && encodedData != null) {
                    synchronized(muxerLock) {
                        if (isMuxerStarted && audioTrackIndex >= 0) {
                            if (bufferInfo.presentationTimeUs <= lastAudioPtsUs) {
                                bufferInfo.presentationTimeUs = lastAudioPtsUs + 500L
                            }
                            lastAudioPtsUs = bufferInfo.presentationTimeUs
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            try {
                                mediaMuxer?.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                            } catch (_: Throwable) {}
                        } else {
                            try {
                                val dup = ByteBuffer.allocateDirect(bufferInfo.size)
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                dup.put(encodedData)
                                dup.flip()
                                val copyInfo = MediaCodec.BufferInfo().apply {
                                    set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                }
                                if (pendingAudioSamples.size < 90) {
                                    pendingAudioSamples.add(QueuedSample(dup, copyInfo))
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                try {
                    encoder.releaseOutputBuffer(outIndex, false)
                } catch (_: Throwable) {}

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            } else {
                break
            }
        }
    }

    private fun checkStartMuxerLocked() {
        if (isMuxerStarted) return
        val muxer = mediaMuxer ?: return
        val videoReady = videoTrackIndex >= 0
        val isAudioPending = hasAudioTrack && audioEncoder != null && audioTrackIndex < 0
        val now = System.currentTimeMillis()
        if (videoFormatStartTime == 0L && videoReady) {
            videoFormatStartTime = now
        }
        val audioTimedOut = videoFormatStartTime > 0L && (now - videoFormatStartTime > 600L)
        val canStart = videoReady && (!isAudioPending || audioTimedOut)

        if (canStart) {
            try {
                muxer.start()
                isMuxerStarted = true
                Log.d(TAG, "MediaMuxer started successfully (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex, audioTimedOut=$audioTimedOut)")

                // Flush pending queued video samples
                for (s in pendingVideoSamples) {
                    try {
                        muxer.writeSampleData(videoTrackIndex, s.buffer, s.info)
                        videoFramesEncodedCount.incrementAndGet()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error writing queued video sample", t)
                    }
                }
                pendingVideoSamples.clear()

                if (audioTrackIndex >= 0) {
                    for (s in pendingAudioSamples) {
                        try {
                            muxer.writeSampleData(audioTrackIndex, s.buffer, s.info)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Error writing queued audio sample", t)
                        }
                    }
                    pendingAudioSamples.clear()
                }
                muxerLock.notifyAll()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start MediaMuxer", t)
            }
        }
    }

    /**
     * Stops recording, drains all pending GPU & MediaCodec buffers, finalizes the MP4 container,
     * and releases all OpenGL / Codec / Audio resources.
     */
    fun stopAndRelease(): File? {
        if (!isRecording.getAndSet(false)) {
            releaseInternal()
            return outputFile.takeIf { it.exists() && it.length() > 0L }
        }

        try {
            audioThread?.join(1500)
        } catch (_: InterruptedException) {}
        audioThread = null

        val latch = CountDownLatch(1)
        val handler = glHandler
        if (handler != null) {
            handler.post {
                try {
                    drainVideoEncoder(endOfStream = true)
                } catch (t: Throwable) {
                    Log.w(TAG, "Error draining video encoder on stop: ${t.message}")
                } finally {
                    releaseInternal()
                    latch.countDown()
                }
            }
            latch.await(4, TimeUnit.SECONDS)
        } else {
            releaseInternal()
        }

        try {
            glThread?.quitSafely()
        } catch (_: Throwable) {}
        glThread = null
        glHandler = null

        if (videoFramesEncodedCount.get() == 0) {
            Log.e(TAG, "CustomVideoPipelineRecorder produced 0 video frames; rejecting output file")
            try { outputFile.delete() } catch (_: Throwable) {}
            return null
        }

        return outputFile.takeIf { it.exists() && it.length() > 0L }
    }

    private fun releaseInternal() {
        try { cameraSurfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
        try { cameraInputSurface?.release() } catch (_: Throwable) {}
        cameraInputSurface = null
        try { cameraSurfaceTexture?.release() } catch (_: Throwable) {}
        cameraSurfaceTexture = null

        try { ultraWideSurfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
        try { ultraWideInputSurface?.release() } catch (_: Throwable) {}
        ultraWideInputSurface = null
        try { ultraWideSurfaceTexture?.release() } catch (_: Throwable) {}
        ultraWideSurfaceTexture = null

        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null

        try { audioEncoder?.stop() } catch (_: Throwable) {}
        try { audioEncoder?.release() } catch (_: Throwable) {}
        audioEncoder = null

        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.release() } catch (_: Throwable) {}
        videoEncoder = null

        try { encoderInputSurface?.release() } catch (_: Throwable) {}
        encoderInputSurface = null

        synchronized(muxerLock) {
            if (isMuxerStarted) {
                try { mediaMuxer?.stop() } catch (t: Throwable) {
                    Log.w(TAG, "MediaMuxer stop warning: ${t.message}")
                }
            }
            try { mediaMuxer?.release() } catch (_: Throwable) {}
            mediaMuxer = null
            isMuxerStarted = false
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                if (programId != 0) {
                    GLES20.glDeleteProgram(programId)
                    programId = 0
                }
                if (oesTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                    oesTextureId = 0
                }
                if (ultraWideOesTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(ultraWideOesTextureId), 0)
                    ultraWideOesTextureId = 0
                }
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
            } catch (_: Throwable) {}
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun createGlProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link GL program: $info")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $info")
        }
        return shader
    }
}
