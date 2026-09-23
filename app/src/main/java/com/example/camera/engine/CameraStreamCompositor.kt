package com.example.camera.engine

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.camera.data.CubeLutParser
import com.example.camera.model.CinemaConfig
import com.example.camera.model.CinematicLut
import com.example.camera.model.LensType
import com.example.camera.computational.video.ComputationalVideoPipeline
import com.example.camera.computational.video.ComputationalVideoProfile
import com.example.camera.computational.video.ComputationalVideoShader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

private const val TAG = "StreamCompositor"

/**
 * Lightweight GPU / OpenGL ES 2.0 Viewfinder Compositor.
 *
 * Provides persistent Surface/SurfaceTexture pairs for both Main (1×) and Ultra-Wide (0.5×)
 * cameras, allowing concurrent streaming without session recreation.
 *
 * During a lens switch (1× ↔ 0.5×), the compositor switches which camera texture is
 * displayed in the main viewfinder in 1 render frame (< 16ms), eliminating:
 * - Camera device re-opening (openCamera)
 * - Capture session recreation (createCaptureSession)
 * - Preview Surface re-allocation
 * - 3A re-convergence delays
 *
 * Also routes the standby stream to the Little Preview overlay without requiring a secondary
 * camera session.
 */
class CameraStreamCompositor {

    // EGL objects
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglConfig: EGLConfig? = null
    private var dummyPbuffer: EGLSurface? = null

    // Output EGL Surfaces
    private var mainEglSurface: EGLSurface? = null
    private var littleEglSurface: EGLSurface? = null

    private var mainTargetSurface: Surface? = null
    private var mainWidth: Int = 1080
    private var mainHeight: Int = 1920

    private var littleTargetSurface: Surface? = null
    private var littleWidth: Int = 240
    private var littleHeight: Int = 320

    // Native Camera Frame Buffer Dimensions (input from Camera2 HAL, always landscape)
    @Volatile
    private var cameraBufferWidth: Int = 1920
    @Volatile
    private var cameraBufferHeight: Int = 1080

    // Persistent Camera Streams
    var mainCameraSurfaceTexture: SurfaceTexture? = null
        private set
    var mainCameraSurface: Surface? = null
        private set

    var ultraWideCameraSurfaceTexture: SurfaceTexture? = null
        private set
    var ultraWideCameraSurface: Surface? = null
        private set

    // Texture IDs
    private var mainTexId: Int = 0
    private var ultraWideTexId: Int = 0

    // Transform matrices (populated by SurfaceTexture.getTransformMatrix)
    private val mainTexMatrix = FloatArray(16)
    private val ultraWideTexMatrix = FloatArray(16)

    // Active and Pending Displayed Lens
    @Volatile
    var activeLensType: LensType = LensType.WIDE
        private set

    @Volatile
    private var pendingActiveLens: LensType? = null

    // Standby Little Preview Visibility
    @Volatile
    var isLittlePreviewEnabled: Boolean = false

    // Frame availability flags and independent frame sequence counters (per-lens atomic state)
    val mainFrameAvailable = AtomicBoolean(false)
    val ultraWideFrameAvailable = AtomicBoolean(false)
    val mainFrameSequence = AtomicLong(0L)
    val ultraWideFrameSequence = AtomicLong(0L)
    private val lastMainTimestampNs = AtomicLong(0L)
    private val lastUltraWideTimestampNs = AtomicLong(0L)

    // Valid texture flags (strictly updated on GL thread)
    private var hasValidMainTexture: Boolean = false
    private var hasValidUltraWideTexture: Boolean = false

    // Switch Verification
    @Volatile
    private var targetSwitchBaselineSequence: Long = 0L
    @Volatile
    private var forceMainRender: Boolean = false

    // Latency Measurement
    @Volatile
    private var switchStartNs: Long = 0L
    var onFirstFrameRendered: ((targetLens: LensType, latencyMs: Long) -> Unit)? = null

    // Render scheduling throttle to avoid message queue explosion
    private val isRenderPending = AtomicBoolean(false)

    // Background GL Thread
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private val initLatch = java.util.concurrent.CountDownLatch(1)

    // Shader Program & Locations
    private var programId: Int = 0
    private var aPositionLoc: Int = 0
    private var aTexCoordLoc: Int = 0
    private var uTexMatrixLoc: Int = 0
    private var uSamplerLoc: Int = 0

    // Cinema 3D LUT & Color Matrix Uniforms
    private var uLutTextureLoc: Int = 0
    private var uLutSizeLoc: Int = 0
    private var uLutIntensityLoc: Int = 0
    private var uHas3DLutLoc: Int = 0
    private var uColorMatrixLoc: Int = 0
    private var uColorOffsetLoc: Int = 0
    private var uHasColorMatrixLoc: Int = 0

    // Cinema GPU Pipeline State
    private var lutTextureId: Int = 0
    private var lutSize: Int = 33
    private var lutIntensity: Float = 0f
    private var has3DLut: Boolean = false
    private val glColorMatrix = FloatArray(16)
    private val glColorOffset = FloatArray(4)
    private var hasColorMatrix: Boolean = false

    // MediaCodec Encoder Output Surface for real-time 3D LUT recording
    private var encoderTargetSurface: Surface? = null
    private var encoderEglSurface: EGLSurface? = null
    private var encoderWidth: Int = 1920
    private var encoderHeight: Int = 1080

    // Monotonic Recording Presentation Timestamp (PTS) Timeline State:
    // Tracks SurfaceTexture hardware camera timestamps, normalizes the recording timeline
    // to 0 on the first accepted frame, guarantees strict monotonicity, and bridges Main <-> UltraWide
    // lens switches seamlessly without PTS discontinuities.
    @Volatile private var recordingFps: Int = 30
    private val isRecordingToEncoder = AtomicBoolean(false)
    private var lastEncodedPtsNs: Long = -1L
    private var baseTimelinePtsNs: Long = 0L
    private var lastLensCameraTimestampNs: Long = 0L
    private var lastActiveCameraTimestampNs: Long = 0L
    private var lastEncodedLens: LensType? = null

    // Computational Video Pipeline State
    @Volatile var activeComputationalPipeline: ComputationalVideoPipeline = ComputationalVideoPipeline.DEFAULT
        private set
    @Volatile var activePerformanceTier: Int = 0
        private set
    private var activeComputationalProfile: ComputationalVideoProfile =
        ComputationalVideoProfile.forPipeline(ComputationalVideoPipeline.DEFAULT)

    // Computational Video Shader Program & Uniform Locations
    private var compProgramId: Int = 0
    private var compPositionLoc: Int = 0
    private var compTexCoordLoc: Int = 0
    private var compTexMatrixLoc: Int = 0
    private var compSamplerLoc: Int = 0
    private var compPrevSamplerLoc: Int = 0
    private var compHasPrevFrameLoc: Int = 0
    private var compPipelineModeLoc: Int = 0
    private var compTemporalDenoiseLoc: Int = 0
    private var compMotionThresholdLoc: Int = 0
    private var compTemporalFlickerDampingLoc: Int = 0
    private var compHdrToneMapLoc: Int = 0
    private var compHighlightRecoveryLoc: Int = 0
    private var compShadowRecoveryLoc: Int = 0
    private var compLocalContrastLoc: Int = 0
    private var compEdgeSharpeningLoc: Int = 0
    private var compFineDetailLoc: Int = 0
    private var compChromaDenoiseLoc: Int = 0
    private var compSaturationLoc: Int = 0
    private var compVibranceLoc: Int = 0
    private var compWarmthLoc: Int = 0
    private var compSkinToneProtectionLoc: Int = 0
    private var compColorMatrixLoc: Int = 0
    private var compTexelSizeLoc: Int = 0
    private var compPerformanceTierLoc: Int = 0

    // Passthrough Blit Program & Uniform Locations
    private var blitProgramId: Int = 0
    private var blitPositionLoc: Int = 0
    private var blitTexCoordLoc: Int = 0
    private var blitTexMatrixLoc: Int = 0
    private var blitSamplerLoc: Int = 0

    // Ping-Pong FBOs & Textures for Temporal Multi-Frame Computational Video
    private val historyFboIds = IntArray(2)
    private val historyTextureIds = IntArray(2)
    private var historyFboWidth = 0
    private var historyFboHeight = 0
    private var historyPingPongIndex = 0
    private var hasValidHistoryFrame = false
    private val identityMatrix = FloatArray(16).apply {
        android.opengl.Matrix.setIdentityM(this, 0)
    }

    // Quad Buffers
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    init {
        // Standard fullscreen quad coordinates
        val quadVertices = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        val quadTexCoords = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )

        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadVertices)
                position(0)
            }

        texCoordBuffer = ByteBuffer.allocateDirect(quadTexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadTexCoords)
                position(0)
            }

        startGlThread()
    }

    private fun startGlThread() {
        val thread = HandlerThread("CompositorGLThread").apply { start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        handler.post {
            try {
                initEGL()
                initGL()
                initCameraSurfaces()
            } catch (t: Throwable) {
                Log.w(TAG, "GL/EGL init skipped or unsupported in this environment: ${t.message}")
            } finally {
                initLatch.countDown()
            }
        }
    }

    private fun initEGL() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == null || eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.w(TAG, "eglGetDisplay failed or unsupported in this environment")
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "EGL not available in this environment: ${t.message}")
            return
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed")
            return
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        // Create 1x1 pbuffer dummy surface so context can be made current immediately
        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        dummyPbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, dummyPbuffer, dummyPbuffer, eglContext)
    }

    private fun initGL() {
        val vertexShaderSource = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            uniform mat4 uTexMatrix;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """.trimIndent()

        val fragmentShaderSource = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;

            // 3D LUT
            uniform sampler2D uLutTexture;
            uniform float uLutSize;
            uniform float uLutIntensity;
            uniform int uHas3DLut;

            // Color matrix (CST + primary grade)
            uniform mat4 uColorMatrix;
            uniform vec4 uColorOffset;
            uniform int uHasColorMatrix;

            vec3 sample3DLut(sampler2D lutTex, vec3 color, float lutSize) {
                float maxColor = lutSize - 1.0;
                vec3 c = clamp(color, 0.0, 1.0) * maxColor;
                float b = c.b;
                float b0 = floor(b);
                float b1 = min(b0 + 1.0, maxColor);
                float frac = b - b0;

                float totalWidth = lutSize * lutSize;
                float u0 = (b0 * lutSize + c.r + 0.5) / totalWidth;
                float v0 = (c.g + 0.5) / lutSize;

                float u1 = (b1 * lutSize + c.r + 0.5) / totalWidth;
                float v1 = (c.g + 0.5) / lutSize;

                vec3 sample0 = texture2D(lutTex, vec2(u0, v0)).rgb;
                vec3 sample1 = texture2D(lutTex, vec2(u1, v1)).rgb;
                return mix(sample0, sample1, frac);
            }

            void main() {
                vec4 texColor = texture2D(sTexture, vTextureCoord);
                vec3 curRgb = texColor.rgb;

                if (uHasColorMatrix != 0) {
                    vec3 transformed = mat3(uColorMatrix) * curRgb + uColorOffset.rgb;
                    curRgb = clamp(transformed, 0.0, 1.0);
                }

                if (uHas3DLut != 0 && uLutIntensity > 0.001) {
                    vec3 graded = sample3DLut(uLutTexture, curRgb, uLutSize);
                    curRgb = mix(curRgb, graded, uLutIntensity);
                }

                gl_FragColor = vec4(clamp(curRgb, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)

        programId = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vShader)
            GLES20.glAttachShader(prog, fShader)
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(prog))
            }
        }

        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        uSamplerLoc = GLES20.glGetUniformLocation(programId, "sTexture")

        uLutTextureLoc = GLES20.glGetUniformLocation(programId, "uLutTexture")
        uLutSizeLoc = GLES20.glGetUniformLocation(programId, "uLutSize")
        uLutIntensityLoc = GLES20.glGetUniformLocation(programId, "uLutIntensity")
        uHas3DLutLoc = GLES20.glGetUniformLocation(programId, "uHas3DLut")

        uColorMatrixLoc = GLES20.glGetUniformLocation(programId, "uColorMatrix")
        uColorOffsetLoc = GLES20.glGetUniformLocation(programId, "uColorOffset")
        uHasColorMatrixLoc = GLES20.glGetUniformLocation(programId, "uHasColorMatrix")

        // Bind default sampler texture units so external OES and 2D samplers never collide on unit 0
        GLES20.glUseProgram(programId)
        GLES20.glUniform1i(uSamplerLoc, 0)
        GLES20.glUniform1i(uLutTextureLoc, 1)
        GLES20.glUniform1i(uHas3DLutLoc, 0)
        GLES20.glUniform1i(uHasColorMatrixLoc, 0)

        // Create OES external textures
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        mainTexId = textures[0]
        ultraWideTexId = textures[1]

        setupOesTexture(mainTexId)
        setupOesTexture(ultraWideTexId)

        // Compile and link Computational Video Pipeline program
        try {
            val compVShader = compileShader(GLES20.GL_VERTEX_SHADER, ComputationalVideoShader.VERTEX_SHADER)
            val compFShader = compileShader(GLES20.GL_FRAGMENT_SHADER, ComputationalVideoShader.FRAGMENT_SHADER)
            compProgramId = GLES20.glCreateProgram().also { prog ->
                GLES20.glAttachShader(prog, compVShader)
                GLES20.glAttachShader(prog, compFShader)
                GLES20.glLinkProgram(prog)
                val status = IntArray(1)
                GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    Log.e(TAG, "Comp program link failed: " + GLES20.glGetProgramInfoLog(prog))
                }
            }
            compPositionLoc = GLES20.glGetAttribLocation(compProgramId, "aPosition")
            compTexCoordLoc = GLES20.glGetAttribLocation(compProgramId, "aTextureCoord")
            compTexMatrixLoc = GLES20.glGetUniformLocation(compProgramId, "uTexMatrix")
            compSamplerLoc = GLES20.glGetUniformLocation(compProgramId, "sTexture")
            compPrevSamplerLoc = GLES20.glGetUniformLocation(compProgramId, "sPrevTexture")
            compHasPrevFrameLoc = GLES20.glGetUniformLocation(compProgramId, "uHasPrevFrame")
            compPipelineModeLoc = GLES20.glGetUniformLocation(compProgramId, "uPipelineMode")
            compTemporalDenoiseLoc = GLES20.glGetUniformLocation(compProgramId, "uTemporalDenoise")
            compMotionThresholdLoc = GLES20.glGetUniformLocation(compProgramId, "uMotionThreshold")
            compTemporalFlickerDampingLoc = GLES20.glGetUniformLocation(compProgramId, "uTemporalFlickerDamping")
            compHdrToneMapLoc = GLES20.glGetUniformLocation(compProgramId, "uHdrToneMap")
            compHighlightRecoveryLoc = GLES20.glGetUniformLocation(compProgramId, "uHighlightRecovery")
            compShadowRecoveryLoc = GLES20.glGetUniformLocation(compProgramId, "uShadowRecovery")
            compLocalContrastLoc = GLES20.glGetUniformLocation(compProgramId, "uLocalContrast")
            compEdgeSharpeningLoc = GLES20.glGetUniformLocation(compProgramId, "uEdgeSharpening")
            compFineDetailLoc = GLES20.glGetUniformLocation(compProgramId, "uFineDetail")
            compChromaDenoiseLoc = GLES20.glGetUniformLocation(compProgramId, "uChromaDenoise")
            compSaturationLoc = GLES20.glGetUniformLocation(compProgramId, "uSaturation")
            compVibranceLoc = GLES20.glGetUniformLocation(compProgramId, "uVibrance")
            compWarmthLoc = GLES20.glGetUniformLocation(compProgramId, "uWarmth")
            compSkinToneProtectionLoc = GLES20.glGetUniformLocation(compProgramId, "uSkinToneProtection")
            compColorMatrixLoc = GLES20.glGetUniformLocation(compProgramId, "uColorMatrix")
            compTexelSizeLoc = GLES20.glGetUniformLocation(compProgramId, "uTexelSize")
            compPerformanceTierLoc = GLES20.glGetUniformLocation(compProgramId, "uPerformanceTier")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile computational video shader", e)
        }

        // Compile and link Passthrough Blit program
        try {
            val blitVShader = compileShader(GLES20.GL_VERTEX_SHADER, ComputationalVideoShader.BLIT_VERTEX_SHADER)
            val blitFShader = compileShader(GLES20.GL_FRAGMENT_SHADER, ComputationalVideoShader.BLIT_FRAGMENT_SHADER)
            blitProgramId = GLES20.glCreateProgram().also { prog ->
                GLES20.glAttachShader(prog, blitVShader)
                GLES20.glAttachShader(prog, blitFShader)
                GLES20.glLinkProgram(prog)
            }
            blitPositionLoc = GLES20.glGetAttribLocation(blitProgramId, "aPosition")
            blitTexCoordLoc = GLES20.glGetAttribLocation(blitProgramId, "aTextureCoord")
            blitTexMatrixLoc = GLES20.glGetUniformLocation(blitProgramId, "uTexMatrix")
            blitSamplerLoc = GLES20.glGetUniformLocation(blitProgramId, "sTexture")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile blit shader", e)
        }
    }

    private fun setupOesTexture(id: Int) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed ($type): " + GLES20.glGetShaderInfoLog(shader))
        }
        return shader
    }

    private fun initCameraSurfaces() {
        val handler = glHandler ?: return

        // Main Camera persistent SurfaceTexture & Surface
        val mainSt = SurfaceTexture(mainTexId).apply {
            setDefaultBufferSize(1920, 1080)
            setOnFrameAvailableListener({
                mainFrameSequence.incrementAndGet()
                mainFrameAvailable.set(true)
                triggerRender()
            }, handler)
        }
        mainCameraSurfaceTexture = mainSt
        mainCameraSurface = Surface(mainSt)

        // Ultra-Wide persistent SurfaceTexture & Surface
        val uwSt = SurfaceTexture(ultraWideTexId).apply {
            setDefaultBufferSize(1920, 1080)
            setOnFrameAvailableListener({
                val seq = ultraWideFrameSequence.incrementAndGet()
                ultraWideFrameAvailable.set(true)
                Log.d(TAG, "[UW_FRAME] frameSequence=$seq")
                triggerRender()
            }, handler)
        }
        ultraWideCameraSurfaceTexture = uwSt
        ultraWideCameraSurface = Surface(uwSt)

        Log.d(TAG, "Compositor persistent camera input surfaces created.")
        initLatch.countDown()
    }

    fun awaitInitialized(timeoutMs: Long = 500): Boolean {
        return try {
            initLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        }
    }

    fun setDefaultBufferSize(width: Int, height: Int) {
        val camW = if (width > 0 && height > 0) max(width, height) else 1920
        val camH = if (width > 0 && height > 0) min(width, height) else 1080
        cameraBufferWidth = camW
        cameraBufferHeight = camH
        glHandler?.post {
            mainCameraSurfaceTexture?.setDefaultBufferSize(camW, camH)
            ultraWideCameraSurfaceTexture?.setDefaultBufferSize(camW, camH)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Target Surface Management (Viewfinder & Little Preview)
    // -----------------------------------------------------------------------------------------

    fun setMainViewfinderSurface(surface: Surface?, width: Int, height: Int) {
        glHandler?.post {
            val display = eglDisplay
            val ctx = eglContext
            val pbuf = dummyPbuffer
            val oldMain = mainEglSurface

            if (oldMain != null && display != null && ctx != null && pbuf != null) {
                try {
                    EGL14.eglMakeCurrent(display, pbuf, pbuf, ctx)
                    EGL14.eglDestroySurface(display, oldMain)
                } catch (ignored: Exception) {}
                mainEglSurface = null
            }

            mainTargetSurface = surface
            // The viewfinder preview on a portrait device requires portrait orientation dimensions:
            val pWidth = if (width > 0 && height > 0) min(width, height) else if (width > 0) width else 1080
            val pHeight = if (width > 0 && height > 0) max(width, height) else if (height > 0) height else 1920
            mainWidth = pWidth
            mainHeight = pHeight

            if (surface != null && surface.isValid && display != null && eglConfig != null) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                try {
                    mainEglSurface = EGL14.eglCreateWindowSurface(display, eglConfig, surface, surfaceAttribs, 0)
                    Log.d(TAG, "Main Viewfinder EGL Surface attached ($mainWidth x $mainHeight)")
                    triggerRender()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create main EGL window surface", e)
                }
            }
        }
    }

    fun setLittlePreviewSurface(surface: Surface?, width: Int, height: Int) {
        glHandler?.post {
            val display = eglDisplay
            val ctx = eglContext
            val pbuf = dummyPbuffer
            val oldLittle = littleEglSurface

            if (oldLittle != null && display != null && ctx != null && pbuf != null) {
                try {
                    EGL14.eglMakeCurrent(display, pbuf, pbuf, ctx)
                    EGL14.eglDestroySurface(display, oldLittle)
                } catch (ignored: Exception) {}
                littleEglSurface = null
            }

            littleTargetSurface = surface
            littleWidth = if (width > 0) width else 240
            littleHeight = if (height > 0) height else 320

            if (surface != null && surface.isValid && display != null && eglConfig != null) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                try {
                    littleEglSurface = EGL14.eglCreateWindowSurface(display, eglConfig, surface, surfaceAttribs, 0)
                    Log.d(TAG, "Little Preview EGL Surface attached ($littleWidth x $littleHeight)")
                    triggerRender()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create little preview EGL window surface", e)
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Lens Stream Switching (Zero Session Recreation)
    // -----------------------------------------------------------------------------------------

    /**
     * Switch which camera stream is presented to the main viewfinder.
     * Keeps rendering current valid frame until first fresh target frame arrives.
     *
     * @param targetLens LensType to display (WIDE or ULTRAWIDE)
     * @param startTimestampNs nanoTime recorded when the user pressed the switch button
     */
    fun switchActiveStream(targetLens: LensType, startTimestampNs: Long = System.nanoTime()) {
        if (targetLens == LensType.ULTRAWIDE) {
            Log.i(TAG, "[UW_SWITCH] requested")
        }
        glHandler?.post {
            switchStartNs = startTimestampNs
            if (activeLensType == targetLens && pendingActiveLens == null) {
                Log.d(TAG, "Already active lens: $targetLens")
                return@post
            }
            pendingActiveLens = targetLens
            targetSwitchBaselineSequence = if (targetLens == LensType.ULTRAWIDE) {
                ultraWideFrameSequence.get()
            } else {
                mainFrameSequence.get()
            }
            Log.i(TAG, "Compositor switching active stream request: $targetLens (baselineSequence=$targetSwitchBaselineSequence, currentActive=$activeLensType)")
            renderFrame()
        }
    }

    fun getMainFrameCount(): Long = mainFrameSequence.get()
    fun getUltraWideFrameCount(): Long = ultraWideFrameSequence.get()
    fun getMainTimestamp(): Long = lastMainTimestampNs.get()
    fun getUltraWideTimestamp(): Long = lastUltraWideTimestampNs.get()
    fun getFrameCount(lens: LensType): Long = if (lens == LensType.ULTRAWIDE) ultraWideFrameSequence.get() else mainFrameSequence.get()
    fun getTimestamp(lens: LensType): Long = if (lens == LensType.ULTRAWIDE) lastUltraWideTimestampNs.get() else lastMainTimestampNs.get()

    fun triggerRender() {
        if (isRenderPending.compareAndSet(false, true)) {
            glHandler?.post {
                isRenderPending.set(false)
                renderFrame()
            }
        }
    }

    private fun renderFrame() {
        val display = eglDisplay ?: return
        val ctx = eglContext ?: return

        var newMainFrame = false
        var newUltraWideFrame = false

        // 1. Consume available frames to keep both hardware pipelines flowing & 3A converged
        if (mainFrameAvailable.compareAndSet(true, false)) {
            try {
                mainCameraSurfaceTexture?.updateTexImage()
                mainCameraSurfaceTexture?.getTransformMatrix(mainTexMatrix)
                val ts = mainCameraSurfaceTexture?.timestamp ?: 0L
                lastMainTimestampNs.set(ts)
                hasValidMainTexture = true
                newMainFrame = true
            } catch (e: Exception) {
                Log.w(TAG, "Error updating main texture image", e)
            }
        }

        if (ultraWideFrameAvailable.compareAndSet(true, false)) {
            try {
                ultraWideCameraSurfaceTexture?.updateTexImage()
                ultraWideCameraSurfaceTexture?.getTransformMatrix(ultraWideTexMatrix)
                val ts = ultraWideCameraSurfaceTexture?.timestamp ?: 0L
                lastUltraWideTimestampNs.set(ts)
                hasValidUltraWideTexture = true
                newUltraWideFrame = true
                val seq = ultraWideFrameSequence.get()
                Log.d(TAG, "[UW_RENDER] frameSequence=$seq")
                Log.d(TAG, "[UW_RENDER] texture updated")
            } catch (e: Exception) {
                Log.w(TAG, "Error updating ultrawide texture image", e)
            }
        }

        // 2. Check pending stream switch (atomic handoff on first fresh target frame)
        val pending = pendingActiveLens
        if (pending != null) {
            val targetSequence = if (pending == LensType.ULTRAWIDE) {
                ultraWideFrameSequence.get()
            } else {
                mainFrameSequence.get()
            }
            val targetHasValidTexture = if (pending == LensType.ULTRAWIDE) {
                hasValidUltraWideTexture
            } else {
                hasValidMainTexture
            }

            // Fresh frame arrived on target lens (sequence > baseline)
            if (targetHasValidTexture && targetSequence > targetSwitchBaselineSequence) {
                activeLensType = pending
                pendingActiveLens = null
                if (pending == LensType.ULTRAWIDE) {
                    Log.i(TAG, "[UW_SWITCH] first fresh frame received")
                }
                val startNs = switchStartNs
                if (startNs > 0) {
                    switchStartNs = 0L
                    val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
                    Log.i(TAG, "[LATENCY] Instant switch to $pending verified and displayed in ${latencyMs}ms (frame #$targetSequence > baseline $targetSwitchBaselineSequence)")
                    onFirstFrameRendered?.invoke(pending, latencyMs)
                }
            } else {
                // Target lens has not produced fresh frame yet:
                // Check on next render tick (~16ms) without blocking the GL thread
                glHandler?.postDelayed({
                    if (pendingActiveLens != null) {
                        renderFrame()
                    }
                }, 16)
            }
        }

        // 3. Render active stream to Main Viewfinder EGL Surface
        val mainSurf = mainEglSurface
        val currentActive = activeLensType

        val targetTexId: Int
        val targetTexMatrix: FloatArray

        if (currentActive == LensType.ULTRAWIDE) {
            if (hasValidUltraWideTexture) {
                targetTexId = ultraWideTexId
                targetTexMatrix = ultraWideTexMatrix
            } else if (hasValidMainTexture) {
                // Fallback while waiting for first fresh Ultra-Wide frame
                targetTexId = mainTexId
                targetTexMatrix = mainTexMatrix
            } else {
                targetTexId = 0
                targetTexMatrix = mainTexMatrix
            }
        } else {
            if (hasValidMainTexture) {
                targetTexId = mainTexId
                targetTexMatrix = mainTexMatrix
            } else if (hasValidUltraWideTexture) {
                // Fallback while waiting for first fresh Main frame
                targetTexId = ultraWideTexId
                targetTexMatrix = ultraWideTexMatrix
            } else {
                targetTexId = 0
                targetTexMatrix = mainTexMatrix
            }
        }

        val activeCameraTimestamp = if (currentActive == LensType.ULTRAWIDE) {
            lastUltraWideTimestampNs.get()
        } else {
            lastMainTimestampNs.get()
        }

        val timestampChanged = (activeCameraTimestamp != lastActiveCameraTimestampNs && activeCameraTimestamp > 0L)
        val isFreshActiveFrame = when (currentActive) {
            LensType.ULTRAWIDE -> (newUltraWideFrame || timestampChanged || (lastEncodedLens != LensType.ULTRAWIDE && hasValidUltraWideTexture))
            else -> (newMainFrame || timestampChanged || (lastEncodedLens != LensType.WIDE && hasValidMainTexture))
        }

        val isComputationalActive = (activeComputationalPipeline != ComputationalVideoPipeline.DEFAULT)

        if (isComputationalActive && targetTexId != 0) {
            // 1. First pass: render computational video pipeline to offscreen history FBO
            ensureComputationalFbos(cameraBufferWidth, cameraBufferHeight)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, historyFboIds[historyPingPongIndex])
            GLES20.glViewport(0, 0, historyFboWidth, historyFboHeight)

            drawComputationalQuad(targetTexId, targetTexMatrix)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            hasValidHistoryFrame = true
            val computedTexId = historyTextureIds[historyPingPongIndex]

            // 2. Second pass: blit to Main Viewfinder EGL Surface
            if (mainSurf != null) {
                EGL14.eglMakeCurrent(display, mainSurf, mainSurf, ctx)
                val surfWidthArr = IntArray(1)
                val surfHeightArr = IntArray(1)
                EGL14.eglQuerySurface(display, mainSurf, EGL14.EGL_WIDTH, surfWidthArr, 0)
                EGL14.eglQuerySurface(display, mainSurf, EGL14.EGL_HEIGHT, surfHeightArr, 0)
                val dstW = if (surfWidthArr[0] > 0) surfWidthArr[0] else mainWidth
                val dstH = if (surfHeightArr[0] > 0) surfHeightArr[0] else mainHeight
                GLES20.glViewport(0, 0, dstW, dstH)

                val camLong = max(cameraBufferWidth, cameraBufferHeight).toFloat()
                val camShort = min(cameraBufferWidth, cameraBufferHeight).toFloat()
                val srcAspect = if (camShort > 0f) camLong / camShort else (16f / 9f)

                val dstLong = max(dstW, dstH).toFloat()
                val dstShort = min(dstW, dstH).toFloat()
                val dstAspect = if (dstShort > 0f) dstLong / dstShort else (16f / 9f)

                var scaleX = 1.0f
                var scaleY = 1.0f
                if (kotlin.math.abs(dstAspect - srcAspect) >= 0.01f) {
                    if (dstAspect > srcAspect) {
                        scaleX = srcAspect / dstAspect
                        scaleY = 1.0f
                    } else {
                        scaleX = 1.0f
                        scaleY = dstAspect / srcAspect
                    }
                }

                val finalBlitMatrix = FloatArray(16)
                if (scaleX == 1.0f && scaleY == 1.0f) {
                    android.opengl.Matrix.setIdentityM(finalBlitMatrix, 0)
                } else {
                    android.opengl.Matrix.setIdentityM(finalBlitMatrix, 0)
                    android.opengl.Matrix.translateM(finalBlitMatrix, 0, 0.5f, 0.5f, 0.0f)
                    android.opengl.Matrix.scaleM(finalBlitMatrix, 0, scaleX, scaleY, 1.0f)
                    android.opengl.Matrix.translateM(finalBlitMatrix, 0, -0.5f, -0.5f, 0.0f)
                }

                drawBlitQuad(computedTexId, finalBlitMatrix)
                EGL14.eglSwapBuffers(display, mainSurf)
            }

            // 3. Third pass: blit to MediaCodec Encoder EGL Surface (if recording and fresh active frame)
            val encSurf = encoderEglSurface
            if (encSurf != null && isRecordingToEncoder.get() && isFreshActiveFrame) {
                EGL14.eglMakeCurrent(display, encSurf, encSurf, ctx)
                GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
                drawBlitQuad(computedTexId, identityMatrix)

                val ptsNs = computeNextEncoderPtsNs(currentActive, activeCameraTimestamp)
                EGLExt.eglPresentationTimeANDROID(display, encSurf, ptsNs)
                EGL14.eglSwapBuffers(display, encSurf)
            }

            // Advance ping-pong index
            historyPingPongIndex = 1 - historyPingPongIndex
        } else if (targetTexId != 0) {
            if (mainSurf != null) {
                EGL14.eglMakeCurrent(display, mainSurf, mainSurf, ctx)
                val surfWidthArr = IntArray(1)
                val surfHeightArr = IntArray(1)
                EGL14.eglQuerySurface(display, mainSurf, EGL14.EGL_WIDTH, surfWidthArr, 0)
                EGL14.eglQuerySurface(display, mainSurf, EGL14.EGL_HEIGHT, surfHeightArr, 0)
                val dstW = if (surfWidthArr[0] > 0) surfWidthArr[0] else mainWidth
                val dstH = if (surfHeightArr[0] > 0) surfHeightArr[0] else mainHeight
                GLES20.glViewport(0, 0, dstW, dstH)

                // Source camera buffer aspect ratio in portrait orientation:
                // Camera sensors are natively landscape (e.g. 1920x1080 for 16:9, or 1440x1080 for 4:3).
                // In a portrait viewfinder, sensor width maps to height and sensor height maps to width.
                val camLong = max(cameraBufferWidth, cameraBufferHeight).toFloat()
                val camShort = min(cameraBufferWidth, cameraBufferHeight).toFloat()
                val srcAspect = if (camShort > 0f) camLong / camShort else (16f / 9f)

                // Destination viewfinder aspect ratio in portrait orientation:
                val dstLong = max(dstW, dstH).toFloat()
                val dstShort = min(dstW, dstH).toFloat()
                val dstAspect = if (dstShort > 0f) dstLong / dstShort else (16f / 9f)

                var scaleX = 1.0f
                var scaleY = 1.0f
                if (kotlin.math.abs(dstAspect - srcAspect) >= 0.01f) {
                    if (dstAspect > srcAspect) {
                        // Destination is taller/narrower than source (e.g. 9:16 dest vs 3:4 source).
                        // Preserve true height and center-crop width without stretching.
                        scaleX = srcAspect / dstAspect
                        scaleY = 1.0f
                    } else {
                        // Destination is wider/shorter than source (e.g. 3:4 dest vs 16:9 source).
                        // Preserve true width and center-crop height without stretching.
                        scaleX = 1.0f
                        scaleY = dstAspect / srcAspect
                    }
                }

                val finalTexMatrix = FloatArray(16)
                if (scaleX == 1.0f && scaleY == 1.0f) {
                    System.arraycopy(targetTexMatrix, 0, finalTexMatrix, 0, 16)
                } else {
                    val cropMatrix = FloatArray(16)
                    android.opengl.Matrix.setIdentityM(cropMatrix, 0)
                    android.opengl.Matrix.translateM(cropMatrix, 0, 0.5f, 0.5f, 0.0f)
                    android.opengl.Matrix.scaleM(cropMatrix, 0, scaleX, scaleY, 1.0f)
                    android.opengl.Matrix.translateM(cropMatrix, 0, -0.5f, -0.5f, 0.0f)
                    android.opengl.Matrix.multiplyMM(finalTexMatrix, 0, targetTexMatrix, 0, cropMatrix, 0)
                }

                drawQuad(targetTexId, finalTexMatrix)
                EGL14.eglSwapBuffers(display, mainSurf)
                if (targetTexId == ultraWideTexId) {
                    val seq = ultraWideFrameSequence.get()
                    Log.d(TAG, "[UW_RENDER] frameSequence=$seq")
                }
            }

            // 3b. Render active stream with EXACT SAME 3D LUT and color grading to MediaCodec Encoder EGL Surface (if recording and fresh active frame)
            val encSurf = encoderEglSurface
            if (encSurf != null && isRecordingToEncoder.get() && isFreshActiveFrame) {
                EGL14.eglMakeCurrent(display, encSurf, encSurf, ctx)
                GLES20.glViewport(0, 0, encoderWidth, encoderHeight)

                // Render camera stream without preview center crop directly onto the encoder surface
                drawQuad(targetTexId, targetTexMatrix)

                val ptsNs = computeNextEncoderPtsNs(currentActive, activeCameraTimestamp)
                EGLExt.eglPresentationTimeANDROID(display, encSurf, ptsNs)
                EGL14.eglSwapBuffers(display, encSurf)
            }
        }

        // 4. Render standby stream to Little Preview EGL Surface (if visible)
        val littleSurf = littleEglSurface
        if (isLittlePreviewEnabled && littleSurf != null) {
            val standbyTexId: Int
            val standbyTexMatrix: FloatArray

            if (currentActive == LensType.ULTRAWIDE) {
                if (hasValidMainTexture) {
                    standbyTexId = mainTexId
                    standbyTexMatrix = mainTexMatrix
                } else {
                    standbyTexId = 0
                    standbyTexMatrix = mainTexMatrix
                }
            } else {
                if (hasValidUltraWideTexture) {
                    standbyTexId = ultraWideTexId
                    standbyTexMatrix = ultraWideTexMatrix
                } else {
                    standbyTexId = 0
                    standbyTexMatrix = ultraWideTexMatrix
                }
            }

            if (standbyTexId != 0) {
                EGL14.eglMakeCurrent(display, littleSurf, littleSurf, ctx)
                GLES20.glViewport(0, 0, littleWidth, littleHeight)
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawQuad(standbyTexId, standbyTexMatrix)
                EGL14.eglSwapBuffers(display, littleSurf)
            }
        }
    }

    private fun drawQuad(textureId: Int, texMatrix: FloatArray) {
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uSamplerLoc, 0)
        GLES20.glUniform1i(uLutTextureLoc, 1)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        if (hasColorMatrix) {
            GLES20.glUniform1i(uHasColorMatrixLoc, 1)
            GLES20.glUniformMatrix4fv(uColorMatrixLoc, 1, false, glColorMatrix, 0)
            GLES20.glUniform4fv(uColorOffsetLoc, 1, glColorOffset, 0)
        } else {
            GLES20.glUniform1i(uHasColorMatrixLoc, 0)
        }

        if (has3DLut && lutTextureId != 0 && lutIntensity > 0.001f) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            GLES20.glUniform1i(uLutTextureLoc, 1)
            GLES20.glUniform1f(uLutSizeLoc, lutSize.toFloat())
            GLES20.glUniform1f(uLutIntensityLoc, lutIntensity)
            GLES20.glUniform1i(uHas3DLutLoc, 1)
        } else {
            GLES20.glUniform1i(uHas3DLutLoc, 0)
        }

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        if (has3DLut && lutTextureId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * Updates Cinema 3D LUT and ColorMatrix parameters on the GL rendering thread.
     * Both preview and recorded video will receive this exact GPU processing in real time.
     */
    fun setCinemaConfig(config: CinemaConfig?, rec2020Params: Rec2020AutoToneParams?) {
        glHandler?.post {
            if (config != null && config.selectedLut != CinematicLut.NONE) {
                val (stripBmp, size) = if (config.selectedLut == CinematicLut.CUSTOM && !config.customLutPath.isNullOrBlank()) {
                    val parsed = CubeLutParser.getOrLoad(config.customLutPath)
                    Pair(parsed?.to2DStripBitmap(), parsed?.size ?: 33)
                } else {
                    Pair(CubeLutParser.generate3DStripBitmapForPreset(config.selectedLut, 33), 33)
                }

                if (stripBmp != null && !stripBmp.isRecycled) {
                    if (lutTextureId == 0) {
                        val textures = IntArray(1)
                        GLES20.glGenTextures(1, textures, 0)
                        lutTextureId = textures[0]
                    }
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, stripBmp, 0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

                    lutSize = size
                    lutIntensity = config.lutIntensity.coerceIn(0f, 1f)
                    has3DLut = true
                } else {
                    has3DLut = false
                }
            } else {
                has3DLut = false
            }

            if (config != null) {
                val colorMatrix = CinemaColorPipeline.computeCinemaColorMatrix(
                    config = config,
                    rec2020Params = rec2020Params,
                    includeCreativeLut = false
                )
                if (colorMatrix != null) {
                    val arr = colorMatrix.array
                    // Row-major 4x5 to column-major 4x4
                    glColorMatrix[0] = arr[0];  glColorMatrix[1] = arr[5];  glColorMatrix[2] = arr[10]; glColorMatrix[3] = 0f
                    glColorMatrix[4] = arr[1];  glColorMatrix[5] = arr[6];  glColorMatrix[6] = arr[11]; glColorMatrix[7] = 0f
                    glColorMatrix[8] = arr[2];  glColorMatrix[9] = arr[7];  glColorMatrix[10] = arr[12]; glColorMatrix[11] = 0f
                    glColorMatrix[12] = 0f;     glColorMatrix[13] = 0f;     glColorMatrix[14] = 0f;      glColorMatrix[15] = 1f

                    glColorOffset[0] = arr[4] / 255.0f
                    glColorOffset[1] = arr[9] / 255.0f
                    glColorOffset[2] = arr[14] / 255.0f
                    glColorOffset[3] = arr[19] / 255.0f
                    hasColorMatrix = true
                } else {
                    hasColorMatrix = false
                }
            } else {
                hasColorMatrix = false
            }

            triggerRender()
        }
    }

    /**
     * Resets recording timestamp state cleanly for a new recording timeline.
     */
    fun resetRecordingTimestamps(fps: Int = 30) {
        recordingFps = if (fps in 15..120) fps else 30
        lastEncodedPtsNs = -1L
        baseTimelinePtsNs = 0L
        lastLensCameraTimestampNs = 0L
        lastActiveCameraTimestampNs = 0L
        lastEncodedLens = null
        isRecordingToEncoder.set(false)
    }

    /**
     * Computes the next strictly monotonic encoder PTS on the continuous recording timeline:
     * Derives timing directly from SurfaceTexture camera frame timestamps,
     * normalizes the first frame to 0, paces frames to avoid jitter,
     * and seamlessly bridges Main <-> UltraWide lens switches without timeline discontinuities.
     */
    private fun computeNextEncoderPtsNs(currentActive: LensType, activeCamTimestamp: Long): Long {
        val currentFrameTs = if (activeCamTimestamp > 0L) activeCamTimestamp else System.nanoTime()
        val expectedFrameIntervalNs = 1_000_000_000L / recordingFps.toLong()
        val minPacingStepNs = maxOf(1_000_000L, expectedFrameIntervalNs / 10L)

        val ptsNs: Long
        if (lastEncodedPtsNs < 0L) {
            // First accepted frame of recording: normalize timeline to 0
            ptsNs = 0L
            baseTimelinePtsNs = 0L
            lastLensCameraTimestampNs = currentFrameTs
            lastEncodedLens = currentActive
        } else if (currentActive != lastEncodedLens) {
            // Lens switch (Main <-> UltraWide):
            // Seamlessly bridge timeline without discontinuities or clock jumps
            val bridgeStep = expectedFrameIntervalNs
            ptsNs = lastEncodedPtsNs + bridgeStep
            baseTimelinePtsNs = ptsNs
            lastLensCameraTimestampNs = currentFrameTs
            lastEncodedLens = currentActive
            Log.i(TAG, "[PTS_LENS_SWITCH] Switched to $currentActive, bridged PTS at ${ptsNs}ns (+${bridgeStep}ns)")
        } else if (lastLensCameraTimestampNs <= 0L) {
            // Resumed after pause: bridge timeline smoothly
            ptsNs = lastEncodedPtsNs + expectedFrameIntervalNs
            lastLensCameraTimestampNs = currentFrameTs
        } else {
            // Normal frame on current lens:
            val deltaNs = currentFrameTs - lastLensCameraTimestampNs
            val stepNs = if (deltaNs <= 0L) {
                // Duplicate timestamp or clock skew: enforce strict monotonicity
                minPacingStepNs
            } else if (deltaNs > 500_000_000L) {
                // Large gap / dropped frames / hitch: clamp to prevent sudden multi-second leap
                expectedFrameIntervalNs * 2L
            } else {
                deltaNs
            }
            ptsNs = lastEncodedPtsNs + stepNs
            lastLensCameraTimestampNs = currentFrameTs
        }

        lastEncodedPtsNs = ptsNs
        lastActiveCameraTimestampNs = currentFrameTs
        return ptsNs
    }

    /**
     * Starts submitting frames to the encoder surface.
     * Must be called ONLY after MediaRecorder.start() has successfully executed,
     * ensuring MediaRecorder never receives frames before its encoder pipeline is ready.
     */
    fun startEncoding() {
        glHandler?.post {
            isRecordingToEncoder.set(true)
            Log.i(TAG, "Compositor encoder output activated (recordingFps=$recordingFps)")
        }
    }

    /**
     * Stops submitting frames to the encoder surface immediately.
     * Guarantees MediaRecorder stop/reset never encounters active frame submissions.
     */
    fun stopEncoding() {
        isRecordingToEncoder.set(false)
        glHandler?.post {
            isRecordingToEncoder.set(false)
            lastEncodedPtsNs = -1L
            Log.i(TAG, "Compositor encoder output stopped")
        }
    }

    fun pauseEncoding() {
        isRecordingToEncoder.set(false)
    }

    fun resumeEncoding() {
        glHandler?.post {
            lastLensCameraTimestampNs = 0L
            isRecordingToEncoder.set(true)
        }
    }

    /**
     * Attaches or detaches a MediaCodec encoder Surface for real-time GPU recording.
     * When attached, frames rendered will be drawn to the encoder Surface after startEncoding() is called.
     */
    fun setEncoderSurface(surface: Surface?, width: Int, height: Int, fps: Int = 30) {
        glHandler?.post {
            setEncoderSurfaceInternal(surface, width, height, fps)
        }
    }

    /**
     * Attaches MediaCodec encoder Surface synchronously with verification.
     * Returns true if the EGL window surface was successfully created, false otherwise.
     * Enables automatic fail-safe fallback to standard direct recording if GPU encoder surface creation fails.
     */
    fun attachEncoderSurface(
        surface: Surface?,
        width: Int,
        height: Int,
        timeoutMs: Long = 400L,
        fps: Int = 30
    ): Boolean {
        if (surface == null || !surface.isValid) {
            setEncoderSurface(null, 0, 0, fps)
            return false
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        val handler = glHandler
        if (handler == null) {
            Log.e(TAG, "Cannot attach encoder surface: GL handler is null")
            return false
        }
        handler.post {
            try {
                setEncoderSurfaceInternal(surface, width, height, fps)
                success = (encoderEglSurface != null && encoderEglSurface != EGL14.EGL_NO_SURFACE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed in attachEncoderSurface", e)
                success = false
            } finally {
                latch.countDown()
            }
        }
        try {
            val completed = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!completed) {
                Log.w(TAG, "attachEncoderSurface timed out after ${timeoutMs}ms")
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for attachEncoderSurface", e)
        }
        return success
    }

    private fun setEncoderSurfaceInternal(surface: Surface?, width: Int, height: Int, fps: Int = 30) {
        val display = eglDisplay
        val ctx = eglContext
        val pbuf = dummyPbuffer
        val oldEnc = encoderEglSurface

        if (oldEnc != null && display != null && ctx != null && pbuf != null) {
            try {
                EGL14.eglMakeCurrent(display, pbuf, pbuf, ctx)
                EGL14.eglDestroySurface(display, oldEnc)
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying old encoder EGL surface", e)
            }
            encoderEglSurface = null
        }

        // Cleanly reset recording timestamp state on every new recording or when detaching
        resetRecordingTimestamps(fps)

        encoderTargetSurface = surface
        encoderWidth = if (width > 0) width else 1920
        encoderHeight = if (height > 0) height else 1080

        if (surface != null && surface.isValid && display != null && eglConfig != null) {
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            try {
                val created = EGL14.eglCreateWindowSurface(display, eglConfig, surface, surfaceAttribs, 0)
                if (created != null && created != EGL14.EGL_NO_SURFACE) {
                    encoderEglSurface = created
                    // Leaves isRecordingToEncoder as false until startEncoding() is explicitly called
                    Log.i(TAG, "Encoder EGL Surface attached ($encoderWidth x $encoderHeight @ ${recordingFps}fps)")
                } else {
                    Log.e(TAG, "eglCreateWindowSurface returned EGL_NO_SURFACE for encoder")
                    encoderEglSurface = null
                    isRecordingToEncoder.set(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create encoder EGL window surface", e)
                encoderEglSurface = null
                isRecordingToEncoder.set(false)
            }
        } else {
            isRecordingToEncoder.set(false)
        }
    }

    /**
     * Updates the computational video pipeline profile in real time on the GL rendering thread.
     * Takes effect immediately on the next frame (< 16ms) without restarting camera session or dropping frames.
     */
    fun setComputationalVideoPipeline(pipeline: ComputationalVideoPipeline, tier: Int = 0) {
        glHandler?.post {
            val changed = (activeComputationalPipeline != pipeline || activePerformanceTier != tier)
            activeComputationalPipeline = pipeline
            activePerformanceTier = tier
            activeComputationalProfile = ComputationalVideoProfile.forPipeline(pipeline, tier)

            // Reset temporal history across pipeline changes
            hasValidHistoryFrame = false

            if (pipeline == ComputationalVideoPipeline.DEFAULT) {
                // When switching to STD/Default, properly detach encoder surface and release computational FBOs
                setEncoderSurfaceInternal(null, 0, 0)
                releaseComputationalFbos()
            }

            if (changed) {
                triggerRender()
            }
        }
    }

    private fun ensureComputationalFbos(width: Int, height: Int) {
        val fboW = if (width > 0) width else 1920
        val fboH = if (height > 0) height else 1080
        if (historyFboWidth == fboW && historyFboHeight == fboH && historyFboIds[0] != 0) {
            return
        }
        releaseComputationalFbos()

        GLES20.glGenFramebuffers(2, historyFboIds, 0)
        GLES20.glGenTextures(2, historyTextureIds, 0)

        for (i in 0 until 2) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTextureIds[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                fboW, fboH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, historyFboIds[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, historyTextureIds[i], 0
            )
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        historyFboWidth = fboW
        historyFboHeight = fboH
        historyPingPongIndex = 0
        hasValidHistoryFrame = false
    }

    private fun releaseComputationalFbos() {
        if (historyFboIds[0] != 0) {
            GLES20.glDeleteFramebuffers(2, historyFboIds, 0)
            historyFboIds[0] = 0
            historyFboIds[1] = 0
        }
        if (historyTextureIds[0] != 0) {
            GLES20.glDeleteTextures(2, historyTextureIds, 0)
            historyTextureIds[0] = 0
            historyTextureIds[1] = 0
        }
        historyFboWidth = 0
        historyFboHeight = 0
        hasValidHistoryFrame = false
    }

    private fun drawComputationalQuad(textureId: Int, texMatrix: FloatArray) {
        if (compProgramId == 0) return
        GLES20.glUseProgram(compProgramId)

        // Bind current camera OES stream
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(compSamplerLoc, 0)
        GLES20.glUniformMatrix4fv(compTexMatrixLoc, 1, false, texMatrix, 0)

        // Bind previous frame for temporal multi-frame processing
        if (hasValidHistoryFrame) {
            val prevTexId = historyTextureIds[1 - historyPingPongIndex]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTexId)
            GLES20.glUniform1i(compPrevSamplerLoc, 1)
            GLES20.glUniform1i(compHasPrevFrameLoc, 1)
        } else {
            GLES20.glUniform1i(compHasPrevFrameLoc, 0)
        }

        // Set pipeline mode & parameters
        val profile = activeComputationalProfile
        val modeInt = when (profile.pipeline) {
            ComputationalVideoPipeline.DEFAULT -> 0
            ComputationalVideoPipeline.PIXEL -> 1
            ComputationalVideoPipeline.SAMSUNG -> 2
            ComputationalVideoPipeline.IPHONE -> 3
            ComputationalVideoPipeline.VIVO -> 4
        }
        GLES20.glUniform1i(compPipelineModeLoc, modeInt)

        // Temporal & Motion
        GLES20.glUniform1f(compTemporalDenoiseLoc, profile.temporalDenoise)
        GLES20.glUniform1f(compMotionThresholdLoc, profile.motionThreshold)
        GLES20.glUniform1f(compTemporalFlickerDampingLoc, profile.temporalFlickerDamping)

        // Dynamic Range & Tone
        GLES20.glUniform1f(compHdrToneMapLoc, profile.hdrToneMap)
        GLES20.glUniform1f(compHighlightRecoveryLoc, profile.highlightRecovery)
        GLES20.glUniform1f(compShadowRecoveryLoc, profile.shadowRecovery)
        GLES20.glUniform1f(compLocalContrastLoc, profile.localContrast)

        // Detail & Noise
        GLES20.glUniform1f(compEdgeSharpeningLoc, profile.edgeSharpening)
        GLES20.glUniform1f(compFineDetailLoc, profile.fineDetail)
        GLES20.glUniform1f(compChromaDenoiseLoc, profile.chromaDenoise)

        // Color & Skin
        GLES20.glUniform1f(compSaturationLoc, profile.saturation)
        GLES20.glUniform1f(compVibranceLoc, profile.vibrance)
        GLES20.glUniform1f(compWarmthLoc, profile.warmth)
        GLES20.glUniform1f(compSkinToneProtectionLoc, profile.skinToneProtection)
        GLES20.glUniformMatrix3fv(compColorMatrixLoc, 1, false, profile.colorMatrix, 0)

        // Texel size for spatial convolution
        val tx = 1.0f / max(historyFboWidth.toFloat(), 1.0f)
        val ty = 1.0f / max(historyFboHeight.toFloat(), 1.0f)
        GLES20.glUniform2f(compTexelSizeLoc, tx, ty)
        GLES20.glUniform1i(compPerformanceTierLoc, profile.performanceTier)

        // Draw quad
        GLES20.glEnableVertexAttribArray(compPositionLoc)
        GLES20.glVertexAttribPointer(compPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(compTexCoordLoc)
        GLES20.glVertexAttribPointer(compTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(compPositionLoc)
        GLES20.glDisableVertexAttribArray(compTexCoordLoc)

        if (hasValidHistoryFrame) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun drawBlitQuad(textureId: Int, texMatrix: FloatArray) {
        if (blitProgramId == 0) return
        GLES20.glUseProgram(blitProgramId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(blitSamplerLoc, 0)
        GLES20.glUniformMatrix4fv(blitTexMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glEnableVertexAttribArray(blitPositionLoc)
        GLES20.glVertexAttribPointer(blitPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(blitTexCoordLoc)
        GLES20.glVertexAttribPointer(blitTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(blitPositionLoc)
        GLES20.glDisableVertexAttribArray(blitTexCoordLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() {
        glHandler?.post {
            try {
                releaseComputationalFbos()
                if (compProgramId != 0) {
                    GLES20.glDeleteProgram(compProgramId)
                    compProgramId = 0
                }
                if (blitProgramId != 0) {
                    GLES20.glDeleteProgram(blitProgramId)
                    blitProgramId = 0
                }
                val encSurf = encoderEglSurface
                val display = eglDisplay
                if (encSurf != null && display != null) {
                    EGL14.eglDestroySurface(display, encSurf)
                    encoderEglSurface = null
                }
                resetRecordingTimestamps()
                if (lutTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                    lutTextureId = 0
                }

                mainCameraSurface?.release()
                mainCameraSurface = null
                mainCameraSurfaceTexture?.release()
                mainCameraSurfaceTexture = null

                ultraWideCameraSurface?.release()
                ultraWideCameraSurface = null
                ultraWideCameraSurfaceTexture?.release()
                ultraWideCameraSurfaceTexture = null

                val ctx = eglContext
                val mainSurf = mainEglSurface
                val littleSurf = littleEglSurface
                val pbuf = dummyPbuffer

                if (mainSurf != null && display != null) {
                    EGL14.eglDestroySurface(display, mainSurf)
                    mainEglSurface = null
                }
                if (littleSurf != null && display != null) {
                    EGL14.eglDestroySurface(display, littleSurf)
                    littleEglSurface = null
                }
                if (pbuf != null && display != null) {
                    EGL14.eglDestroySurface(display, pbuf)
                    dummyPbuffer = null
                }
                if (ctx != null && display != null) {
                    EGL14.eglDestroyContext(display, ctx)
                    eglContext = null
                }
                if (display != null) {
                    EGL14.eglTerminate(display)
                    eglDisplay = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing compositor GL resources", e)
            }
        }

        glThread?.quitSafely()
        try {
            glThread?.join(500)
            glThread = null
            glHandler = null
        } catch (ignored: Exception) {}
    }
}
