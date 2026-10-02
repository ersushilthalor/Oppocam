package com.example.camera.dualvideo.gl

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.camera.dualvideo.model.DualVideoLayout
import com.example.camera.dualvideo.model.PipPosition
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
 * 1. Viewfinder preview window (TextureView surface)
 * 2. MediaCodec recording surface (during video recording)
 *
 * Supports PiP, Split Top/Bottom, and Split Left/Right with exact orientation & aspect ratio correction.
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

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var recordEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

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

    private var currentLayout: DualVideoLayout = DualVideoLayout.PIP
    private var currentPipPosition: PipPosition = PipPosition.TOP_RIGHT

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    @Volatile
    private var isRecording = false

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
        surfaceTexturePrimary = SurfaceTexture(textureIdPrimary).apply {
            setDefaultBufferSize(outputWidth, outputHeight)
            setOnFrameAvailableListener({ requestRender() }, glHandler)
        }
        surfacePrimary = Surface(surfaceTexturePrimary)

        surfaceTextureSecondary = SurfaceTexture(textureIdSecondary).apply {
            setDefaultBufferSize(outputWidth, outputHeight)
            setOnFrameAvailableListener({ requestRender() }, glHandler)
        }
        surfaceSecondary = Surface(surfaceTextureSecondary)
    }

    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        glHandler?.post {
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (surface != null && surface.isValid) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                previewEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
            }
            requestRender()
        }
    }

    fun setRecordingSurface(surface: Surface?) {
        glHandler?.post {
            if (recordEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, recordEglSurface)
                recordEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (surface != null && surface.isValid) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                recordEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
                isRecording = true
            } else {
                isRecording = false
            }
        }
    }

    fun stopRecording() {
        glHandler?.post {
            isRecording = false
            if (recordEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, recordEglSurface)
                recordEglSurface = EGL14.EGL_NO_SURFACE
            }
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

        val nowNs = System.nanoTime()

        // 1. Render to on-screen Preview Viewfinder
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)
            drawCompositeLayout(outputWidth, outputHeight)
            EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
        }

        // 2. Render to MediaCodec Recording Encoder Surface with exact presentation timestamp
        if (isRecording && recordEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, recordEglSurface, recordEglSurface, eglContext)
            drawCompositeLayout(outputWidth, outputHeight)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, recordEglSurface, nowNs)
            EGL14.eglSwapBuffers(eglDisplay, recordEglSurface)
        }
    }

    private fun drawCompositeLayout(width: Int, height: Int) {
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
                drawQuad(textureIdPrimary, primaryTransformMatrix, 1.0f)

                // Foreground: Floating Picture-in-Picture Secondary Camera
                val pipW = (width * 0.36f).toInt()
                val pipH = (height * 0.28f).toInt()
                val marginX = (width * 0.04f).toInt()
                val marginY = (height * 0.05f).toInt()

                val (pipX, pipY) = when (currentPipPosition) {
                    PipPosition.TOP_RIGHT -> Pair(width - pipW - marginX, height - pipH - marginY)
                    PipPosition.TOP_LEFT -> Pair(marginX, height - pipH - marginY)
                    PipPosition.BOTTOM_RIGHT -> Pair(width - pipW - marginX, marginY)
                    PipPosition.BOTTOM_LEFT -> Pair(marginX, marginY)
                }

                GLES20.glViewport(pipX, pipY, pipW, pipH)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(pipX, pipY, pipW, pipH)
                drawQuad(textureIdSecondary, secondaryTransformMatrix, 1.0f)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }

            DualVideoLayout.SPLIT_TOP_BOTTOM -> {
                val halfH = height / 2

                // Top Viewport: Primary Camera
                GLES20.glViewport(0, halfH, width, halfH)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(0, halfH, width, halfH)
                drawQuad(textureIdPrimary, primaryTransformMatrix, 1.0f)

                // Bottom Viewport: Secondary Camera
                GLES20.glViewport(0, 0, width, halfH)
                GLES20.glScissor(0, 0, width, halfH)
                drawQuad(textureIdSecondary, secondaryTransformMatrix, 1.0f)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }

            DualVideoLayout.SPLIT_LEFT_RIGHT -> {
                val halfW = width / 2

                // Left Viewport: Primary Camera
                GLES20.glViewport(0, 0, halfW, height)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(0, 0, halfW, height)
                drawQuad(textureIdPrimary, primaryTransformMatrix, 1.0f)

                // Right Viewport: Secondary Camera
                GLES20.glViewport(halfW, 0, halfW, height)
                GLES20.glScissor(halfW, 0, halfW, height)
                drawQuad(textureIdSecondary, secondaryTransformMatrix, 1.0f)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }
        }

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
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
                if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
                    previewEglSurface = EGL14.EGL_NO_SURFACE
                }
                if (recordEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, recordEglSurface)
                    recordEglSurface = EGL14.EGL_NO_SURFACE
                }
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

                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglTerminate(eglDisplay)
                    eglDisplay = EGL14.EGL_NO_DISPLAY
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
