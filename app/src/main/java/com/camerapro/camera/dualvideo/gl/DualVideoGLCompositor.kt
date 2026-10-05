package com.camerapro.camera.dualvideo.gl

import android.graphics.Matrix as AndroidMatrix
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.camerapro.camera.dualvideo.model.DualVideoLayout
import com.camerapro.camera.dualvideo.model.PipPosition
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * High-performance real-time OpenGL ES 2.0 dual-stream video compositor.
 *
 * Simultaneously binds:
 * - Texture 1 (Primary Camera stream)
 * - Texture 2 (Secondary Camera stream)
 *
 * Composites both live streams with zero latency onto:
 * 1. Viewfinder preview window (TextureView surface) - matches standard 9:16 Video mode
 * 2. MediaCodec recording surface (during video recording)
 *
 * Fully fixes:
 * - Upright portrait orientation for back camera (90° sensor) and front camera (270° sensor with selfie mirror)
 * - True uniform aspect-ratio center-crop scaling for all layouts (PiP, Split Top/Bottom, Side by Side)
 *   so the camera image is never stretched or squished
 * - Exact video timebase (starting at PTS 0, perfectly spaced at target FPS), eliminating the 12-hour duration bug
 */
class DualVideoGLCompositor(
    private val outputWidth: Int,
    private val outputHeight: Int
) {
    companion object {
        private const val TAG = "DualVideoCompositor"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform float uAlpha;
            void main() {
                vec4 color = texture2D(sTexture, vTextureCoord);
                gl_FragColor = vec4(color.rgb, color.a * uAlpha);
            }
        """
    }

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglConfig: EGLConfig? = null

    private var previewEglSurface: EGLSurface? = null
    private var recordEglSurface: EGLSurface? = null

    private var previewWidth = 0
    private var previewHeight = 0
    private var previewDisplayRotation = 0

    private var recordWidth = outputWidth
    private var recordHeight = outputHeight
    private var recordingRotation = 0

    private var programId = 0
    private var uMVPMatrixHandle = 0
    private var uSTMatrixHandle = 0
    private var uAlphaHandle = 0
    private var aPositionHandle = 0
    private var aTextureCoordHandle = 0

    private var textureIdPrimary = 0
    private var textureIdSecondary = 0

    var surfaceTexturePrimary: SurfaceTexture? = null
        private set
    var surfaceTextureSecondary: SurfaceTexture? = null
        private set

    var surfacePrimary: Surface? = null
        private set
    var surfaceSecondary: Surface? = null
        private set

    private val primaryTransformMatrix = FloatArray(16)
    private val secondaryTransformMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private var isPrimaryFront = false
    private var primarySensorOrientation = 90
    private var isSecondaryFront = true
    private var secondarySensorOrientation = 270

    private var currentLayout: DualVideoLayout = DualVideoLayout.PIP
    private var currentPipPosition: PipPosition = PipPosition.TOP_RIGHT

    private var targetFps = 30
    private var frameIntervalNs = 1_000_000_000L / 30

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    @Volatile
    private var isRecording = false
    private var recordingStartNs: Long = 0L
    private var lastRecordFrameTimeNs: Long = 0L
    private var recordedFrameCount: Long = 0L

    private val localTexMatrix = FloatArray(16)
    private val finalTexMatrix = FloatArray(16)
    private val matrixValues = FloatArray(9)

    private val fullQuadVertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 3 * 4).run {
        order(ByteOrder.nativeOrder())
        asFloatBuffer().apply {
            put(floatArrayOf(
                -1.0f, -1.0f, 0.0f,
                 1.0f, -1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f,
                 1.0f,  1.0f, 0.0f
            ))
            position(0)
        }
    }

    private val quadTexCoords: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4).run {
        order(ByteOrder.nativeOrder())
        asFloatBuffer().apply {
            put(floatArrayOf(
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
            ))
            position(0)
        }
    }

    fun start(onReady: () -> Unit) {
        val thread = HandlerThread("DualVideoGLThread").apply { start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        handler.post {
            initEGL()
            initGL()
            createCameraSurfaces()
            onReady()
        }
    }

    fun setFps(fps: Int) {
        glHandler?.post {
            targetFps = fps.coerceIn(15, 60)
            frameIntervalNs = 1_000_000_000L / targetFps
        }
    }

    fun setCameraInfo(
        isPrimaryFront: Boolean,
        primaryOrientation: Int,
        isSecondaryFront: Boolean,
        secondaryOrientation: Int
    ) {
        glHandler?.post {
            this.isPrimaryFront = isPrimaryFront
            this.primarySensorOrientation = primaryOrientation
            this.isSecondaryFront = isSecondaryFront
            this.secondarySensorOrientation = secondaryOrientation
            requestRender()
        }
    }

    private fun initEGL() {
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
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        // Create offscreen pbuffer surface so context can be made current immediately
        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val pbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun initGL() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        programId = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        uMVPMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        uAlphaHandle = GLES20.glGetUniformLocation(programId, "uAlpha")
        aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        textureIdPrimary = textures[0]
        textureIdSecondary = textures[1]

        for (texId in textures) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
    }

    private fun createCameraSurfaces() {
        // Camera HAL stream configuration map natively supplies landscape frame buffers (e.g. 1920x1080)
        val camBufW = maxOf(outputWidth, outputHeight)
        val camBufH = minOf(outputWidth, outputHeight)

        surfaceTexturePrimary = SurfaceTexture(textureIdPrimary).apply {
            setDefaultBufferSize(camBufW, camBufH)
            setOnFrameAvailableListener({ requestRender() }, glHandler)
        }
        surfacePrimary = Surface(surfaceTexturePrimary)

        surfaceTextureSecondary = SurfaceTexture(textureIdSecondary).apply {
            setDefaultBufferSize(camBufW, camBufH)
            setOnFrameAvailableListener({ requestRender() }, glHandler)
        }
        surfaceSecondary = Surface(surfaceTextureSecondary)
    }

    fun setPreviewSurface(surface: Surface?, width: Int, height: Int, displayRotationDegrees: Int = 0) {
        glHandler?.post {
            val disp = eglDisplay
            val prevSurf = previewEglSurface
            if (disp != null && prevSurf != null && prevSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(disp, prevSurf)
            }
            previewEglSurface = null
            previewWidth = width
            previewHeight = height
            previewDisplayRotation = ((displayRotationDegrees % 360) + 360) % 360
            if (disp != null && surface != null && surface.isValid) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                previewEglSurface = EGL14.eglCreateWindowSurface(disp, eglConfig, surface, surfaceAttribs, 0)
            }
            requestRender()
        }
    }

    fun updatePreviewSize(width: Int, height: Int, displayRotationDegrees: Int = 0) {
        glHandler?.post {
            previewWidth = width
            previewHeight = height
            previewDisplayRotation = ((displayRotationDegrees % 360) + 360) % 360
            requestRender()
        }
    }

    fun setRecordingSurface(
        surface: Surface?,
        width: Int = outputWidth,
        height: Int = outputHeight,
        rotationDegrees: Int = 0
    ) {
        glHandler?.post {
            val disp = eglDisplay
            val recSurf = recordEglSurface
            if (disp != null && recSurf != null && recSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(disp, recSurf)
            }
            recordEglSurface = null
            recordWidth = if (width > 0) width else outputWidth
            recordHeight = if (height > 0) height else outputHeight
            recordingRotation = ((rotationDegrees % 360) + 360) % 360
            if (disp != null && surface != null && surface.isValid) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                recordEglSurface = EGL14.eglCreateWindowSurface(disp, eglConfig, surface, surfaceAttribs, 0)
                isRecording = true
                recordingStartNs = 0L
                lastRecordFrameTimeNs = 0L
                recordedFrameCount = 0L
            } else {
                isRecording = false
            }
        }
    }

    fun stopRecording() {
        glHandler?.post {
            isRecording = false
            val disp = eglDisplay
            val recSurf = recordEglSurface
            if (disp != null && recSurf != null && recSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(disp, recSurf)
            }
            recordEglSurface = null
            recordingStartNs = 0L
            lastRecordFrameTimeNs = 0L
            recordedFrameCount = 0L
        }
    }

    fun setLayout(layout: DualVideoLayout, pipPos: PipPosition = currentPipPosition) {
        glHandler?.post {
            currentLayout = layout
            currentPipPosition = pipPos
            requestRender()
        }
    }

    fun setPipPosition(pipPos: PipPosition) {
        glHandler?.post {
            currentPipPosition = pipPos
            requestRender()
        }
    }

    private fun requestRender() {
        glHandler?.post {
            renderFrame()
        }
    }

    private fun renderFrame() {
        try {
            surfaceTexturePrimary?.updateTexImage()
            surfaceTexturePrimary?.getTransformMatrix(primaryTransformMatrix)
        } catch (ignored: Exception) {}

        try {
            surfaceTextureSecondary?.updateTexImage()
            surfaceTextureSecondary?.getTransformMatrix(secondaryTransformMatrix)
        } catch (ignored: Exception) {}

        // 1. Render to on-screen Preview Viewfinder (consistent with normal Video mode)
        val disp = eglDisplay
        val ctx = eglContext
        val prevSurf = previewEglSurface
        val recSurf = recordEglSurface

        if (disp != null && ctx != null && prevSurf != null && prevSurf != EGL14.EGL_NO_SURFACE && previewWidth > 0 && previewHeight > 0) {
            EGL14.eglMakeCurrent(disp, prevSurf, prevSurf, ctx)
            drawCompositeLayout(previewWidth, previewHeight, previewDisplayRotation)
            EGL14.eglSwapBuffers(disp, prevSurf)
        }

        // 2. Render to MediaCodec Recording Encoder Surface with exact paced presentation timestamps
        if (isRecording && disp != null && ctx != null && recSurf != null && recSurf != EGL14.EGL_NO_SURFACE) {
            val nowNs = System.nanoTime()
            if (recordingStartNs == 0L) {
                recordingStartNs = nowNs
                lastRecordFrameTimeNs = nowNs
                recordedFrameCount = 0L
            }

            val elapsedSinceLast = nowNs - lastRecordFrameTimeNs
            // Encode if at least 85% of target frame interval elapsed, or on the very first frame
            if (elapsedSinceLast >= (frameIntervalNs * 0.85f) || recordedFrameCount == 0L) {
                lastRecordFrameTimeNs = nowNs
                val ptsNs = recordedFrameCount * frameIntervalNs
                recordedFrameCount++

                EGL14.eglMakeCurrent(disp, recSurf, recSurf, ctx)
                drawCompositeLayout(recordWidth, recordHeight, recordingRotation)
                EGLExt.eglPresentationTimeANDROID(disp, recSurf, ptsNs)
                EGL14.eglSwapBuffers(disp, recSurf)
            }
        }
    }

    private fun drawCompositeLayout(width: Int, height: Int, rotationDegrees: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)

        when (currentLayout) {
            DualVideoLayout.PIP -> {
                // Background: Fullscreen Primary Camera
                GLES20.glViewport(0, 0, width, height)
                drawCamera(
                    textureId = textureIdPrimary,
                    stMatrix = primaryTransformMatrix,
                    isFront = isPrimaryFront,
                    sensorOrientation = primarySensorOrientation,
                    rotationDegrees = rotationDegrees,
                    viewportWidth = width,
                    viewportHeight = height
                )

                // Foreground: Floating Picture-in-Picture Secondary Camera
                val pipW = (width * 0.34f).toInt()
                val pipH = (height * 0.34f).toInt()
                val marginX = (width * 0.04f).toInt()
                val marginY = (height * 0.06f).toInt()

                val (pipX, pipY) = when (currentPipPosition) {
                    PipPosition.TOP_RIGHT -> Pair(width - pipW - marginX, height - pipH - marginY)
                    PipPosition.TOP_LEFT -> Pair(marginX, height - pipH - marginY)
                    PipPosition.BOTTOM_RIGHT -> Pair(width - pipW - marginX, marginY)
                    PipPosition.BOTTOM_LEFT -> Pair(marginX, marginY)
                }

                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(pipX, pipY, pipW, pipH)
                GLES20.glViewport(pipX, pipY, pipW, pipH)

                drawCamera(
                    textureId = textureIdSecondary,
                    stMatrix = secondaryTransformMatrix,
                    isFront = isSecondaryFront,
                    sensorOrientation = secondarySensorOrientation,
                    rotationDegrees = rotationDegrees,
                    viewportWidth = pipW,
                    viewportHeight = pipH
                )

                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }

            DualVideoLayout.SPLIT_TOP_BOTTOM -> {
                val halfH = height / 2

                // Top Viewport: Primary Camera
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(0, halfH, width, height - halfH)
                GLES20.glViewport(0, halfH, width, height - halfH)
                drawCamera(
                    textureId = textureIdPrimary,
                    stMatrix = primaryTransformMatrix,
                    isFront = isPrimaryFront,
                    sensorOrientation = primarySensorOrientation,
                    rotationDegrees = rotationDegrees,
                    viewportWidth = width,
                    viewportHeight = height - halfH
                )

                // Bottom Viewport: Secondary Camera
                GLES20.glScissor(0, 0, width, halfH)
                GLES20.glViewport(0, 0, width, halfH)
                drawCamera(
                    textureId = textureIdSecondary,
                    stMatrix = secondaryTransformMatrix,
                    isFront = isSecondaryFront,
                    sensorOrientation = secondarySensorOrientation,
                    rotationDegrees = rotationDegrees,
                    viewportWidth = width,
                    viewportHeight = halfH
                )
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }

            DualVideoLayout.SPLIT_LEFT_RIGHT -> {
                val halfW = width / 2

                // Left Viewport: Primary Camera
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(0, 0, halfW, height)
                GLES20.glViewport(0, 0, halfW, height)
                drawCamera(
                    textureId = textureIdPrimary,
                    stMatrix = primaryTransformMatrix,
                    isFront = isPrimaryFront,
                    sensorOrientation = primarySensorOrientation,
                    rotationDegrees = rotationDegrees,
                    viewportWidth = halfW,
                    viewportHeight = height
                )

                // Right Viewport: Secondary Camera
                GLES20.glScissor(halfW, 0, width - halfW, height)
                GLES20.glViewport(halfW, 0, width - halfW, height)
                drawCamera(
                    textureId = textureIdSecondary,
                    stMatrix = secondaryTransformMatrix,
                    isFront = isSecondaryFront,
                    sensorOrientation = secondarySensorOrientation,
                    rotationDegrees = rotationDegrees,
                    viewportWidth = width - halfW,
                    viewportHeight = height
                )
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }
        }

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
    }

    /**
     * Renders a camera frame upright and uniform center-cropped (no non-uniform stretching or squishing).
     *
     * Respects the SurfaceTexture transform matrix (`stMatrix`) populated by Camera2 HAL
     * (`Camera3OutputStream` / `CameraUtils::getRotationTransform`), which on Android already
     * applies `SENSOR_ORIENTATION` (90° for back camera, 270° + FLIP_H for front camera) relative
     * to natural portrait orientation (`ROTATION_0`). Avoids double-rotating by 90° while still
     * compensating for display/recording rotation (`0°`, `90°`, `180°`, `270°`) and providing
     * a fallback if `stMatrix` is unrotated.
     */
    private fun drawCamera(
        textureId: Int,
        stMatrix: FloatArray,
        isFront: Boolean,
        sensorOrientation: Int,
        rotationDegrees: Int,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        computeCameraTexMatrix(
            stMatrix = stMatrix,
            isFront = isFront,
            sensorOrientation = sensorOrientation,
            rotationDegrees = rotationDegrees,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            camBufferWidth = maxOf(outputWidth, outputHeight),
            camBufferHeight = minOf(outputWidth, outputHeight),
            outMatrix = finalTexMatrix
        )

        drawQuad(textureId, finalTexMatrix, 1.0f)
    }

    internal fun computeCameraTexMatrix(
        stMatrix: FloatArray,
        isFront: Boolean,
        sensorOrientation: Int,
        rotationDegrees: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        camBufferWidth: Int = maxOf(outputWidth, outputHeight),
        camBufferHeight: Int = minOf(outputWidth, outputHeight),
        outMatrix: FloatArray
    ) {
        val m0 = stMatrix[0]
        val m1 = stMatrix[1]
        val m4 = stMatrix[4]
        val m5 = stMatrix[5]

        val offDiag = kotlin.math.abs(m1) + kotlin.math.abs(m4)
        val diag = kotlin.math.abs(m0) + kotlin.math.abs(m5)
        // When Camera2 streams to a SurfaceTexture, Camera3OutputStream sets the ANativeWindow
        // buffer transform (ROT_90 / ROT_270), making off-diagonal terms (m1, m4) dominant.
        val isStRotated90 = offDiag > diag

        // Standard un-mirrored SurfaceTexture has mtxFlipV (1 reflection -> det < 0).
        // When Camera3OutputStream also sets NATIVE_WINDOW_TRANSFORM_FLIP_H (front camera),
        // there are 2 reflections (mtxFlipV * FLIP_H) -> det > 0.
        val isIdentitySt = kotlin.math.abs(m0 - 1f) < 1e-4f &&
            kotlin.math.abs(m5 - 1f) < 1e-4f &&
            offDiag < 1e-4f &&
            kotlin.math.abs(stMatrix[13]) < 1e-4f
        val det = m0 * m5 - m1 * m4
        val isStMirrored = !isIdentitySt && (offDiag + diag > 0.1f) && (det > 0f)

        val normRot = ((rotationDegrees % 360) + 360) % 360
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
            // Viewport is wider than upright camera frame -> fit width, crop height uniformly
            scaleX = 1.0f
            scaleY = camAspect / targetAspect
        } else {
            // Viewport is taller/narrower than upright camera frame -> fit height, crop width uniformly
            scaleX = targetAspect / camAspect
            scaleY = 1.0f
        }

        // Map from target viewport orientation (0, 90, 180, 270) into natural portrait (0)
        val rotToPortrait = when (normRot) {
            90 -> -90f
            180 -> 180f
            270 -> 90f
            else -> 0f
        }

        val matrix2d = AndroidMatrix().apply {
            postTranslate(-0.5f, -0.5f)
            // 1. Uniform center-crop scaling in the viewport's upright coordinate axes
            postScale(scaleX, scaleY)
            // 2. Rotate from display/recording orientation into natural portrait space
            if (rotToPortrait != 0f) {
                postRotate(rotToPortrait)
            }
            // 3. Apply horizontal selfie mirror only if stMatrix hasn't already applied FLIP_H
            if (isFront != isStMirrored) {
                postScale(-1.0f, 1.0f)
            }
            // 4. Apply sensor orientation rotation only if stMatrix hasn't already rotated the buffer
            if (!isStRotated90) {
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    val fallbackRot = if (isFront) {
                        if (sensorOrientation == 270) 90f else -90f
                    } else {
                        if (sensorOrientation == 90) -90f else 90f
                    }
                    postRotate(fallbackRot)
                } else if (sensorOrientation == 180) {
                    postRotate(180f)
                }
            }
            postTranslate(0.5f, 0.5f)
        }

        matrix2d.getValues(matrixValues)
        // Convert 3x3 affine matrix to 4x4 OpenGL column-major matrix
        localTexMatrix[0] = matrixValues[0]; localTexMatrix[1] = matrixValues[3]; localTexMatrix[2] = 0f; localTexMatrix[3] = 0f
        localTexMatrix[4] = matrixValues[1]; localTexMatrix[5] = matrixValues[4]; localTexMatrix[6] = 0f; localTexMatrix[7] = 0f
        localTexMatrix[8] = 0f;              localTexMatrix[9] = 0f;              localTexMatrix[10] = 1f; localTexMatrix[11] = 0f
        localTexMatrix[12] = matrixValues[2]; localTexMatrix[13] = matrixValues[5]; localTexMatrix[14] = 0f; localTexMatrix[15] = 1f

        Matrix.multiplyMM(outMatrix, 0, stMatrix, 0, localTexMatrix, 0)
    }

    private fun drawQuad(textureId: Int, stMatrix: FloatArray, alpha: Float) {
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, identityMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uAlphaHandle, alpha)

        fullQuadVertices.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 0, fullQuadVertices)

        quadTexCoords.position(0)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        glHandler?.post {
            try {
                val disp = eglDisplay
                val prevSurf = previewEglSurface
                if (disp != null && prevSurf != null && prevSurf != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(disp, prevSurf)
                }
                previewEglSurface = null

                val recSurf = recordEglSurface
                if (disp != null && recSurf != null && recSurf != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(disp, recSurf)
                }
                recordEglSurface = null

                if (programId != 0) {
                    GLES20.glDeleteProgram(programId)
                    programId = 0
                }
                if (textureIdPrimary != 0 || textureIdSecondary != 0) {
                    GLES20.glDeleteTextures(2, intArrayOf(textureIdPrimary, textureIdSecondary), 0)
                    textureIdPrimary = 0
                    textureIdSecondary = 0
                }
                surfacePrimary?.release()
                surfacePrimary = null
                surfaceTexturePrimary?.release()
                surfaceTexturePrimary = null

                surfaceSecondary?.release()
                surfaceSecondary = null
                surfaceTextureSecondary?.release()
                surfaceTextureSecondary = null

                val ctx = eglContext
                if (disp != null) {
                    EGL14.eglMakeCurrent(disp, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(disp, ctx)
                        eglContext = null
                    }
                    if (disp != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglTerminate(disp)
                        eglDisplay = null
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing GL compositor", t)
            } finally {
                glThread?.quitSafely()
                glThread = null
                glHandler = null
            }
        }
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
            throw RuntimeException("Could not compile shader $type: $log")
        }
        return shader
    }
}
