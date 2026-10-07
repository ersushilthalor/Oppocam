package com.example.camera.engine

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class PreviewStreamSource {
    MAIN,
    ULTRAWIDE
}

/**
 * High-performance OpenGL ES 2.0 persistent preview compositor.
 *
 * Keeps both Main and Ultra-Wide streams continuously producing frames on dedicated
 * persistent SurfaceTextures. Viewfinder display source switching is instantaneous (0 ms)
 * without tearing down CameraCaptureSession, closing CameraDevice, or calling openCamera().
 */
class CameraPreviewCompositor(
    private val bufferWidth: Int = 1920,
    private val bufferHeight: Int = 1080
) {
    companion object {
        private const val TAG = "PreviewCompositor"
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
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglConfig: EGLConfig? = null
    private var previewEglSurface: EGLSurface? = null
    private var pbufferSurface: EGLSurface? = null

    private var previewWidth = 0
    private var previewHeight = 0
    private var previewDisplayRotation = 0

    private var programId = 0
    private var uMVPMatrixHandle = 0
    private var uSTMatrixHandle = 0
    private var aPositionHandle = 0
    private var aTextureCoordHandle = 0

    private var textureIdMain = 0
    private var textureIdUltraWide = 0

    var mainSurfaceTexture: SurfaceTexture? = null
        private set
    var mainSurface: Surface? = null
        private set

    var ultraWideSurfaceTexture: SurfaceTexture? = null
        private set
    var ultraWideSurface: Surface? = null
        private set

    @Volatile
    var activeSource: PreviewStreamSource = PreviewStreamSource.MAIN
        private set

    @Volatile
    var activeCropZoom: Float = 1.0f
        private set

    @Volatile
    var firstUltraWideFrameTimestamp: Long = 0L
        private set

    @Volatile
    var firstMainFrameTimestamp: Long = 0L
        private set

    @Volatile
    var lastHandoffTimestamp: Long = 0L
        private set

    var onFirstUltraWideFrameCallback: ((Long) -> Unit)? = null
    var onFirstMainFrameCallback: ((Long) -> Unit)? = null

    private val isStarted = AtomicBoolean(false)
    val isEglInitialized = AtomicBoolean(false)

    private val mainTransformMatrix = FloatArray(16)
    private val ultraWideTransformMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

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

    fun start(onReady: (() -> Unit)? = null) {
        if (isStarted.getAndSet(true)) {
            onReady?.invoke()
            return
        }

        val thread = HandlerThread("CameraCompositorGL").apply { start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        val latch = CountDownLatch(1)
        handler.post {
            try {
                initEGL()
                initGL()
                createPersistentSurfaces()
                isEglInitialized.set(true)
                Log.i(TAG, "CameraPreviewCompositor initialized with dedicated Main and Ultra-Wide textures")
            } catch (t: Throwable) {
                Log.w(TAG, "EGL initialization failed; initializing fallback persistent textures", t)
                createFallbackSurfaces()
            } finally {
                latch.countDown()
                onReady?.invoke()
            }
        }
        try { latch.await(1000, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    private fun initEGL() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed")
        }
        eglDisplay = display

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(display, attribList, 0, configs, 0, configs.size, numConfigs, 0)
        val cfg = configs[0] ?: throw RuntimeException("eglChooseConfig returned no config")
        eglConfig = cfg

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        val ctx = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }
        eglContext = ctx

        // Offscreen 1x1 pbuffer surface so EGL context can be current immediately
        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val pbuffer = EGL14.eglCreatePbufferSurface(display, cfg, pbufferAttribs, 0)
        pbufferSurface = pbuffer
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, ctx)
    }

    private fun initGL() {
        Matrix.setIdentityM(mainTransformMatrix, 0)
        Matrix.setIdentityM(ultraWideTransformMatrix, 0)
        Matrix.setIdentityM(mvpMatrix, 0)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        programId = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        uMVPMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        textureIdMain = textures[0]
        textureIdUltraWide = textures[1]

        for (texId in textures) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
    }

    private fun createPersistentSurfaces() {
        val camW = maxOf(bufferWidth, bufferHeight)
        val camH = minOf(bufferWidth, bufferHeight)

        val stMain = SurfaceTexture(textureIdMain).apply {
            setDefaultBufferSize(camW, camH)
            setOnFrameAvailableListener({
                if (firstMainFrameTimestamp == 0L) {
                    val ts = SystemClock.uptimeMillis()
                    firstMainFrameTimestamp = ts
                    onFirstMainFrameCallback?.invoke(ts)
                }
                if (activeSource == PreviewStreamSource.MAIN) {
                    renderFrame()
                }
            }, glHandler)
        }
        mainSurfaceTexture = stMain
        mainSurface = Surface(stMain)

        val stUw = SurfaceTexture(textureIdUltraWide).apply {
            setDefaultBufferSize(camW, camH)
            setOnFrameAvailableListener({
                if (firstUltraWideFrameTimestamp == 0L) {
                    val ts = SystemClock.uptimeMillis()
                    firstUltraWideFrameTimestamp = ts
                    Log.i(TAG, "[KEEP_ULTRAWIDE_READY] First Ultra-Wide frame arrived on persistent SurfaceTexture at $ts ms")
                    onFirstUltraWideFrameCallback?.invoke(ts)
                }
                if (activeSource == PreviewStreamSource.ULTRAWIDE) {
                    renderFrame()
                }
            }, glHandler)
        }
        ultraWideSurfaceTexture = stUw
        ultraWideSurface = Surface(stUw)
    }

    private fun createFallbackSurfaces() {
        val camW = maxOf(bufferWidth, bufferHeight)
        val camH = minOf(bufferWidth, bufferHeight)

        try {
            val stMain = SurfaceTexture(1).apply {
                setDefaultBufferSize(camW, camH)
                setOnFrameAvailableListener({
                    if (firstMainFrameTimestamp == 0L) {
                        firstMainFrameTimestamp = SystemClock.uptimeMillis()
                        onFirstMainFrameCallback?.invoke(firstMainFrameTimestamp)
                    }
                })
            }
            mainSurfaceTexture = stMain
            mainSurface = Surface(stMain)
        } catch (_: Throwable) {}

        try {
            val stUw = SurfaceTexture(2).apply {
                setDefaultBufferSize(camW, camH)
                setOnFrameAvailableListener({
                    if (firstUltraWideFrameTimestamp == 0L) {
                        firstUltraWideFrameTimestamp = SystemClock.uptimeMillis()
                        onFirstUltraWideFrameCallback?.invoke(firstUltraWideFrameTimestamp)
                    }
                })
            }
            ultraWideSurfaceTexture = stUw
            ultraWideSurface = Surface(stUw)
        } catch (_: Throwable) {}
    }

    /**
     * Binds or detaches the viewfinder target window surface (from TextureView).
     */
    fun setOutputSurface(surface: Surface?, width: Int, height: Int, displayRotationDegrees: Int = 0) {
        val latch = CountDownLatch(1)
        glHandler?.post {
            try {
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
                    val newSurf = EGL14.eglCreateWindowSurface(disp, eglConfig, surface, surfaceAttribs, 0)
                    if (newSurf != null && newSurf != EGL14.EGL_NO_SURFACE) {
                        previewEglSurface = newSurf
                        Log.i(TAG, "previewEglSurface created successfully ($width x $height)")
                    } else {
                        val err = EGL14.eglGetError()
                        Log.e(TAG, "eglCreateWindowSurface failed with error 0x${Integer.toHexString(err)}")
                    }
                    renderFrame()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error setting output surface on compositor", t)
            } finally {
                latch.countDown()
            }
        } ?: latch.countDown()
        try { latch.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    /**
     * Instantly switches the displayed preview stream source without any session destruction.
     * Returns the exact handoff timestamp in milliseconds.
     */
    fun setActiveSource(source: PreviewStreamSource, cropZoom: Float = 1.0f): Long {
        val nowMs = SystemClock.uptimeMillis()
        lastHandoffTimestamp = nowMs
        activeSource = source
        activeCropZoom = cropZoom

        glHandler?.post {
            renderFrame()
        }
        return nowMs
    }

    /**
     * Updates crop zoom factor for continuous zoom interpolation across 0.5x .. 1x.
     */
    fun setCropZoom(zoom: Float) {
        activeCropZoom = zoom
        glHandler?.post {
            renderFrame()
        }
    }

    private fun renderFrame() {
        val disp = eglDisplay ?: return
        val pbuffer = pbufferSurface
        val targetContextSurf = previewEglSurface.takeIf { it != null && it != EGL14.EGL_NO_SURFACE } ?: pbuffer
        if (targetContextSurf == null || targetContextSurf == EGL14.EGL_NO_SURFACE) return

        EGL14.eglMakeCurrent(disp, targetContextSurf, targetContextSurf, eglContext)

        val isUw = activeSource == PreviewStreamSource.ULTRAWIDE
        val texId = if (isUw) textureIdUltraWide else textureIdMain
        val st = if (isUw) ultraWideSurfaceTexture else mainSurfaceTexture
        val stMatrix = if (isUw) ultraWideTransformMatrix else mainTransformMatrix

        if (st != null) {
            try {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
            } catch (ignored: Throwable) {}
        }

        val eglSurf = previewEglSurface ?: return
        if (eglSurf == EGL14.EGL_NO_SURFACE) return

        EGL14.eglMakeCurrent(disp, eglSurf, eglSurf, eglContext)
        GLES20.glViewport(0, 0, previewWidth, previewHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)

        // Calculate zoom crop transformation on MVP matrix
        Matrix.setIdentityM(mvpMatrix, 0)
        val zoom = activeCropZoom
        val cropScale = if (isUw) {
            // Ultra-Wide base is 0.5x. Matching 1x Main FOV corresponds to 0.999 / 0.5 = 1.998x magnification.
            // As zoom transitions down to 0.5x, cropScale smoothly drops to 1.0x (full uncropped Ultra-Wide sensor).
            (zoom / 0.5f).coerceIn(1.0f, 2.05f)
        } else {
            // Main wide base is 1.0x.
            (zoom / 1.0f).coerceIn(1.0f, 20.0f)
        }

        if (cropScale > 1.001f) {
            Matrix.scaleM(mvpMatrix, 0, cropScale, cropScale, 1.0f)
        }

        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

        fullQuadVertices.position(0)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 0, fullQuadVertices)

        quadTexCoords.position(0)
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)

        EGL14.eglSwapBuffers(disp, eglSurf)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Could not compile shader $type: $error")
            }
        }
    }

    fun release() {
        isStarted.set(false)
        val latch = CountDownLatch(1)
        glHandler?.post {
            try {
                val disp = eglDisplay
                if (disp != null) {
                    previewEglSurface?.let { EGL14.eglDestroySurface(disp, it) }
                    previewEglSurface = null
                    pbufferSurface?.let { EGL14.eglDestroySurface(disp, it) }
                    pbufferSurface = null
                    eglContext?.let { EGL14.eglDestroyContext(disp, it) }
                    eglContext = null
                    EGL14.eglTerminate(disp)
                    eglDisplay = null
                }

                mainSurface?.release()
                mainSurface = null
                mainSurfaceTexture?.release()
                mainSurfaceTexture = null

                ultraWideSurface?.release()
                ultraWideSurface = null
                ultraWideSurfaceTexture?.release()
                ultraWideSurfaceTexture = null
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing compositor", t)
            } finally {
                latch.countDown()
            }
        } ?: latch.countDown()

        try { latch.await(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        glThread?.quitSafely()
        glThread = null
        glHandler = null
    }
}
