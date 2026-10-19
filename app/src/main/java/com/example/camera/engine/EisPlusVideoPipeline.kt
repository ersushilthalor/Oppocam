package com.example.camera.engine

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.hinnka.mycamera.lut.MgcEisYuvRenderer
import com.hinnka.mycamera.stabilization.RealtimeStabilizationCoordinator
import com.hinnka.mycamera.stabilization.StabilizationImageReader
import com.hinnka.mycamera.stabilization.StabilizationUseCase
import com.hinnka.mycamera.utils.PLog
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 YUV -> MGC EIS+ mesh -> encoder surface.
 *
 * The camera output surface returned to Camera2 is the timestamped YUV ImageReader surface, not
 * the encoder surface. The encoder receives only frames whose exact sensor timestamps have a
 * reconstructed MGC transform. This prevents the direct, unwarped Camera2 stream from bypassing
 * software stabilization.
 */
internal class EisPlusVideoPipeline(
    context: Context,
    private val coordinator: RealtimeStabilizationCoordinator,
    private val cameraId: String,
    characteristics: CameraCharacteristics,
    private val cameraInputSize: Size,
    private val outputWidth: Int,
    private val outputHeight: Int,
    strength: Float,
    lookaheadFrames: Int,
    private val outputRotationDegrees: Int = 0,
    private val encoderSurface: Surface,
) : AutoCloseable {
    companion object {
        private const val TAG = "EisPlusVideoPipeline"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        private const val EGL_CONTEXT_MAJOR_VERSION_KHR = 0x3098
        private const val EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB
        private const val MAX_READER_IMAGES = 12
        private const val VERTEX_SHADER = """
            #version 310 es
            precision highp float;
            out vec2 vTexCoord;
            void main() {
                vec2 p[4] = vec2[4](
                    vec2(-1.0, -1.0), vec2(1.0, -1.0),
                    vec2(-1.0,  1.0), vec2(1.0,  1.0)
                );
                vec2 uv[4] = vec2[4](
                    vec2(0.0, 0.0), vec2(1.0, 0.0),
                    vec2(0.0, 1.0), vec2(1.0, 1.0)
                );
                gl_Position = vec4(p[gl_VertexID], 0.0, 1.0);
                vTexCoord = uv[gl_VertexID];
            }
        """
        private const val FRAGMENT_SHADER = """
            #version 310 es
            precision highp float;
            in vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform int uRotationDegrees;
            out vec4 outColor;
            void main() {
                vec2 uv = vTexCoord;
                if (uRotationDegrees == 90) {
                    uv = vec2(vTexCoord.y, 1.0 - vTexCoord.x);
                } else if (uRotationDegrees == 180) {
                    uv = vec2(1.0 - vTexCoord.x, 1.0 - vTexCoord.y);
                } else if (uRotationDegrees == 270) {
                    uv = vec2(1.0 - vTexCoord.y, vTexCoord.x);
                }
                outColor = texture(uTexture, uv);
            }
        """
    }

    private val appContext = context.applicationContext
    private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EisPlusRender").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val inputThread = HandlerThread("EisPlusYuvInput")
    private val renderQueued = AtomicBoolean(false)
    private val renderRequested = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val reader = StabilizationImageReader(
        cameraInputSize.width,
        cameraInputSize.height,
        MAX_READER_IMAGES,
    )

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var contextEgl: EGLContext = EGL14.EGL_NO_CONTEXT
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputProgram = 0
    private var vertexArray = 0
    private var textureUniform = -1
    private var rotationUniform = -1
    private var mgcRenderer: MgcEisYuvRenderer? = null
    private var firstFrameTimestampNs = Long.MIN_VALUE
    private var lastPresentationTimestampNs = -1L

    private val session = coordinator.createSession(
        useCase = StabilizationUseCase.VIDEO,
        strength = strength,
        frameWidth = cameraInputSize.width,
        frameHeight = cameraInputSize.height,
        lookaheadFrames = lookaheadFrames,
        onFrameAvailable = ::scheduleRender,
    )

    val inputSurface: Surface
        get() = reader.surface

    init {
        require(outputWidth > 0 && outputHeight > 0) { "Invalid EIS+ encoder size" }
        require(cameraInputSize.width > 0 && cameraInputSize.height > 0) { "Invalid EIS+ input size" }
        coordinator.configureCamera(cameraId, characteristics)
        require(coordinator.isCameraSupported(characteristics)) {
            "This camera does not meet Photon EIS+ requirements (back camera, realtime sensor timestamps and gyro)"
        }
        inputThread.start()
        try {
            runRenderBlocking {
                initializeEgl()
                mgcRenderer = MgcEisYuvRenderer(TAG)
                check(session.start()) { "MGC EIS+ pose source could not be started" }
            }
            reader.setOnImageAvailableListener(Handler(inputThread.looper)) { source ->
                if (closed.get()) return@setOnImageAvailableListener
                while (!closed.get()) {
                    val image = try {
                        source.acquireNextImage()
                    } catch (error: RuntimeException) {
                        PLog.w(TAG, "EIS+ YUV acquire failed: ${error.message}")
                        null
                    } ?: break
                    coordinator.submitStabilizationImage(image)
                }
            }
            PLog.i(
                TAG,
                "EIS+ active: camera=$cameraId input=${cameraInputSize.width}x${cameraInputSize.height} " +
                    "output=${outputWidth}x${outputHeight}",
            )
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun submitCaptureResult(
        result: TotalCaptureResult,
        request: CaptureRequest,
        outputPhysicalCameraId: String? = null,
    ) {
        if (closed.get()) return
        coordinator.submitCaptureResult(
            result = result,
            request = request,
            outputPhysicalCameraId = outputPhysicalCameraId ?: cameraId,
        )
    }

    fun setPaused(value: Boolean) {
        paused.set(value)
        scheduleRender()
    }

    private fun scheduleRender() {
        if (closed.get()) return
        renderRequested.set(true)
        if (!renderQueued.compareAndSet(false, true)) return
        try {
            renderExecutor.execute {
                try {
                    if (!makeCurrent()) return@execute
                    do {
                        renderRequested.set(false)
                        while (!closed.get()) {
                            val frame = session.dequeueFrame() ?: break
                            if (paused.get()) {
                                frame.image.close()
                                continue
                            }
                            val corrected = try {
                                mgcRenderer?.render(frame)
                            } catch (error: Throwable) {
                                PLog.w(TAG, "EIS+ mesh render failed: ${error.message}")
                                null
                            } ?: continue
                            drawCorrectedFrame(corrected.textureId, corrected.timestampNs)
                        }
                    } while (!closed.get() && renderRequested.get())
                } finally {
                    renderQueued.set(false)
                    // Preserve callbacks that arrived while the GL queue was draining.
                    if (!closed.get() && renderRequested.get()) scheduleRender()
                }
            }
        } catch (error: RuntimeException) {
            renderQueued.set(false)
            if (!closed.get()) PLog.w(TAG, "EIS+ render scheduling failed: ${error.message}")
        }
    }

    private fun drawCorrectedFrame(textureId: Int, sensorTimestampNs: Long) {
        if (!makeCurrent() || outputProgram == 0 || vertexArray == 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, outputWidth, outputHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(outputProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(textureUniform, 0)
        GLES30.glUniform1i(rotationUniform, normalizeRotation(outputRotationDegrees))
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)

        if (firstFrameTimestampNs == Long.MIN_VALUE) firstFrameTimestampNs = sensorTimestampNs
        val relativeNs = (sensorTimestampNs - firstFrameTimestampNs).coerceAtLeast(0L)
        val presentationNs = maxOf(relativeNs, lastPresentationTimestampNs + 1L)
        if (!EGLExt.eglPresentationTimeANDROID(display, windowSurface, presentationNs)) {
            PLog.w(TAG, "EIS+ could not set encoder presentation timestamp")
        }
        if (!EGL14.eglSwapBuffers(display, windowSurface)) {
            PLog.w(TAG, "EIS+ encoder surface swap failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        } else {
            lastPresentationTimestampNs = presentationNs
        }
    }

    private fun initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "EGL initialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, configs.size, count, 0) && count[0] > 0) {
            "No recordable OpenGL ES 3 config"
        }
        val config = requireNotNull(configs[0])
        val contextAttrs = intArrayOf(
            EGL_CONTEXT_MAJOR_VERSION_KHR, 3,
            EGL_CONTEXT_MINOR_VERSION_KHR, 1,
            EGL14.EGL_NONE,
        )
        contextEgl = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
        check(contextEgl != EGL14.EGL_NO_CONTEXT) { "OpenGL ES 3.1 context unavailable" }
        windowSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(windowSurface != EGL14.EGL_NO_SURFACE) { "Cannot create EGL encoder surface" }
        check(makeCurrent()) { "Cannot make EGL encoder surface current" }
        outputProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        check(outputProgram != 0) { "Cannot create EIS+ presentation program" }
        textureUniform = GLES30.glGetUniformLocation(outputProgram, "uTexture")
        rotationUniform = GLES30.glGetUniformLocation(outputProgram, "uRotationDegrees")
        vertexArray = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        check(vertexArray != 0 && textureUniform >= 0 && rotationUniform >= 0) {
            "Cannot initialize EIS+ presentation geometry"
        }
    }

    private fun normalizeRotation(degrees: Int): Int =
        ((degrees % 360 + 360) % 360).let { if (it in setOf(0, 90, 180, 270)) it else 0 }

    private fun makeCurrent(): Boolean =
        display != EGL14.EGL_NO_DISPLAY &&
            windowSurface != EGL14.EGL_NO_SURFACE &&
            contextEgl != EGL14.EGL_NO_CONTEXT &&
            EGL14.eglMakeCurrent(display, windowSurface, windowSurface, contextEgl)

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("EIS+ shader compile failed: $log")
            }
            return shader
        }
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("EIS+ program link failed: $log")
        }
        return program
    }

    private fun runRenderBlocking(block: () -> Unit) {
        val future: Future<*> = renderExecutor.submit(block)
        try {
            future.get()
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            reader.close()
            coordinator.discardStabilizationImages(reader)
        } catch (_: Throwable) {}
        try {
            runRenderBlocking {
                while (true) {
                    val frame = session.dequeueFrame() ?: break
                    frame.image.close()
                }
                session.stop()
                try { mgcRenderer?.release() } catch (_: Throwable) {}
                mgcRenderer = null
                if (vertexArray != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
                vertexArray = 0
                if (outputProgram != 0) GLES30.glDeleteProgram(outputProgram)
                outputProgram = 0
                if (display != EGL14.EGL_NO_DISPLAY) {
                    if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, windowSurface)
                    if (contextEgl != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, contextEgl)
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglTerminate(display)
                }
                display = EGL14.EGL_NO_DISPLAY
                windowSurface = EGL14.EGL_NO_SURFACE
                contextEgl = EGL14.EGL_NO_CONTEXT
            }
        } catch (error: Throwable) {
            PLog.w(TAG, "EIS+ cleanup failed: ${error.message}")
        }
        try { inputThread.quitSafely() } catch (_: Throwable) {}
        try { inputThread.join(500) } catch (_: Throwable) {}
        renderExecutor.shutdown()
    }
}
