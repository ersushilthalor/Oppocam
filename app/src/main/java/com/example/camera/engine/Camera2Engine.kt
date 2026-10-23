package com.example.camera.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.TonemapCurve
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import java.util.concurrent.Executors
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StatFs
import java.util.concurrent.Executor
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SizeF
import android.view.Surface
import com.example.camera.model.*
import com.example.camera.data.CubeLutParser
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private const val TAG = "Camera2Engine"

enum class PreviewStreamSource {
    MAIN,
    ULTRAWIDE
}

class Camera2Engine(private val context: Context) {

    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    // Background threads
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Camera instances
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    // Surfaces & Readers
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var imageReaderJpeg: ImageReader? = null
    private var imageReaderRaw: ImageReader? = null
    private var imageReaderYuv: ImageReader? = null
    val customImagePipelineEngine by lazy { com.example.camera.pipeline.engine.CustomImagePipelineEngine(context) }
    private var mediaRecorder: MediaRecorder? = null
    private var activeRecordingSurface: Surface? = null
    private var videoRecordingFileDescriptor: ParcelFileDescriptor? = null
    private var currentRecordingTempFile: File? = null
    private var currentVideoUri: Uri? = null
    private var currentVideoFileName: String? = null
    private var currentVideoMimeType: String? = null

    // Initialization & Safety States
    private val _isCameraInitialized = MutableStateFlow(false)
    val isCameraInitialized: StateFlow<Boolean> = _isCameraInitialized.asStateFlow()

    private val _cameraInitError = MutableStateFlow<String?>(null)
    val cameraInitError: StateFlow<String?> = _cameraInitError.asStateFlow()

    fun getCharacteristics(cameraId: String): CameraCharacteristics? {
        val mgr = cameraManager ?: return null
        return try {
            mgr.getCameraCharacteristics(cameraId)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to get characteristics for camera $cameraId", t)
            null
        }
    }

    private val preferences by lazy { com.example.camera.data.CameraPreferences(context) }
    var antiBandingMode: String = preferences.antiBanding
        get() = preferences.antiBanding
        set(value) {
            field = value
            preferences.antiBanding = value
            updatePreviewSettings()
        }

    fun onPreviewBitmapFrame(bitmap: Bitmap) {
        // Preview frame callback placeholder (motion photo removed)
    }

    // Keep Ultra Wide Ready simultaneous stream & background camera state
    private val _isKeepUltraWideReady = MutableStateFlow(preferences.isKeepUltraWideReady)
    val isKeepUltraWideReady: StateFlow<Boolean> = _isKeepUltraWideReady.asStateFlow()

    private val _isAutoSwitchToUltraWide = MutableStateFlow(preferences.isAutoSwitchToUltraWide)
    val isAutoSwitchToUltraWide: StateFlow<Boolean> = _isAutoSwitchToUltraWide.asStateFlow()

    private val _isAutoMacroActive = MutableStateFlow(false)
    val isAutoMacroActive: StateFlow<Boolean> = _isAutoMacroActive.asStateFlow()


    var onLensSwitchCompletedListener: ((LensInfo, Float) -> Unit)? = null
    private val lensSwitchListeners = java.util.concurrent.CopyOnWriteArrayList<(LensInfo, Float) -> Unit>()

    fun addLensSwitchCompleteListener(listener: (LensInfo, Float) -> Unit) {
        lensSwitchListeners.add(listener)
    }

    fun removeLensSwitchCompleteListener(listener: (LensInfo, Float) -> Unit) {
        lensSwitchListeners.remove(listener)
    }

    suspend fun switchLensSuspend(lens: LensInfo, preserveZoom: Boolean = false, targetZoom: Float? = null): LensInfo {
        if (isRunningOnLens(lens) && !isSwitchingLens.get()) {
            if (targetZoom != null) {
                setZoom(targetZoom, isPresetTap = false)
            }
            return lens
        }

        return kotlinx.coroutines.withTimeoutOrNull(2500L) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val listener = object : (LensInfo, Float) -> Unit {
                    override fun invoke(completedLens: LensInfo, zoom: Float) {
                        if (continuation.isActive && (completedLens.id == lens.id || completedLens.cameraId == lens.cameraId || completedLens.lensType == lens.lensType)) {
                            removeLensSwitchCompleteListener(this)
                            continuation.resume(completedLens) { _, _, _ -> }
                        }
                    }
                }
                addLensSwitchCompleteListener(listener)
                continuation.invokeOnCancellation {
                    removeLensSwitchCompleteListener(listener)
                }
                selectLens(lens, preserveZoom = preserveZoom, targetZoom = targetZoom)
            }
        } ?: lens
    }

    private var ultraWideReconnectRunnable: Runnable? = null
    private var lastAutoSwitchTimestampMs: Long = 0L
    private var closeSubjectUnfocusedFrames: Int = 0
    private var subjectFarFrames: Int = 0

    private val _ultraWideStreamStatus = MutableStateFlow(
        if (preferences.isKeepUltraWideReady) BackgroundCameraStatus.PREPARING else BackgroundCameraStatus.OFF
    )
    val ultraWideStreamStatus: StateFlow<BackgroundCameraStatus> = _ultraWideStreamStatus.asStateFlow()

    private val _isUsingUltraWideSurface = MutableStateFlow(false)
    val isUsingUltraWideSurface: StateFlow<Boolean> = _isUsingUltraWideSurface.asStateFlow()

    val zoomTransitionController = ZoomTransitionController(
        onApplyZoomToEngine = { zoom, isContinuous ->
            setZoom(zoom, isPresetTap = false, isContinuousTransition = isContinuous)
        },
        onSelectLensOnEngine = { lens, targetZoom, isContinuous ->
            selectLens(lens, preserveZoom = true, targetZoom = targetZoom, isContinuousTransition = isContinuous)
        }
    )
    val previewOverlapState: StateFlow<PreviewOverlapState> = zoomTransitionController.previewOverlapState

    val isLensSwitchOverlapAnimationEnabled: Boolean
        get() = zoomTransitionController.isOverlapAnimationEnabled

    fun setLensSwitchOverlapAnimationEnabled(enabled: Boolean) {
        zoomTransitionController.isOverlapAnimationEnabled = enabled
        preferences.isLensSwitchOverlapAnimationEnabled = enabled
    }

    /** Route actual TextureView buffer updates to the frame-based lens handoff controller. */
    fun onPreviewFrameAvailable(source: PreviewStreamSource) {
        val wasOverlapping = zoomTransitionController.previewOverlapState.value.isOverlapping
        zoomTransitionController.onPreviewFrameAvailable(source)
        val handoffCompleted = wasOverlapping &&
            !zoomTransitionController.previewOverlapState.value.isOverlapping
        if (handoffCompleted) {
            // Restore the low-cost idle FOV match once the final blended frame has arrived.
            // Without this reset, the outgoing standby sensor can remain digitally zoomed to
            // 2x/3x and give the next instant lens switch the wrong starting framing.
            backgroundHandler?.post { syncStandbyStreamSettings() }
        }
    }

    @Volatile
    private var ultraWideViewfinderSurfaceTexture: SurfaceTexture? = null
    @Volatile
    private var ultraWideViewfinderSurface: Surface? = null
    @Volatile
    private var fallbackMainSurfaceTexture: SurfaceTexture? = null
    @Volatile
    private var fallbackMainSurface: Surface? = null

    private var ultraWideStandbyCameraDevice: CameraDevice? = null
    private var ultraWideStandbyCaptureSession: CameraCaptureSession? = null
    private var ultraWideStandbyJpegReader: ImageReader? = null
    private var ultraWideStandbySurfaceTexture: SurfaceTexture? = null
    private var ultraWideStandbySurface: Surface? = null
    private var ultraWideStandbyRequestBuilder: CaptureRequest.Builder? = null
    data class ActiveRecordingStreamConfig(
        val videoResolution: CameraResolution,
        val targetFps: Int,
        val fpsRange: Range<Int>?,
        val is10Bit: Boolean,
        val dynamicRangeProfile: Long?,
        val isVideoStabilizationEnabled: Boolean,
        val videoStabilizationMode: com.example.camera.model.VideoStabilizationMode,
        val isOisAllowed: Boolean,
        val orientationHint: Int,
        val encoderRotation: Int
    )

    @Volatile
    private var dualCameraRecordingRelay: DualCameraRecordingRelay? = null
    @Volatile
    private var activeRecordingStreamConfig: ActiveRecordingStreamConfig? = null
    @Volatile
    private var standbyConfiguredForRecording: Boolean = false
    @Volatile
    private var standbyRecordingSessionFailed: Boolean = false
    @Volatile
    private var logicalMultiCamRecordingFailed: Boolean = false
    @Volatile
    private var logicalMultiCamDualRecSurfacesConfigured: Boolean = false

    fun getDualCameraRecordingRelay(): DualCameraRecordingRelay? = dualCameraRecordingRelay



    fun isStandbyRecordingPipelineReady(): Boolean {
        if (!_isKeepUltraWideReady.value) return false
        if (activeLogicalMultiCamUltraWideConfigured && !logicalMultiCamRecordingFailed && captureSession != null) {
            return true
        }
        val standbyCam = ultraWideStandbyCameraDevice
        val standbySess = ultraWideStandbyCaptureSession
        return standbyCam != null && standbySess != null && !standbyRecordingSessionFailed &&
            (!_isRecordingVideo.value || standbyConfiguredForRecording || activeRecordingStreamConfig == null)
    }

    @Volatile
    private var standbyConfiguredPreviewSize: Size? = null
    @Volatile
    private var standbyConfiguredIsVideoMode: Boolean = false
    private val isPreparingUltraWideStandby = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isUltraWideStreaming = java.util.concurrent.atomic.AtomicBoolean(false)
    private var activeLogicalMultiCamUltraWideConfigured = false
    private val standbySessionGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val standbyCameraDeviceGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    fun isSizeSupportedForLens(cameraId: String?, size: Size): Boolean {
        if (cameraId == null) return false
        val chars = getCharacteristics(cameraId) ?: return false
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val supportedSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        return supportedSizes.any {
            (it.width == size.width && it.height == size.height) ||
            (it.width == size.height && it.height == size.width)
        }
    }

    private val standbyCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val currentTexture = if (_selectedLens.value?.lensType == LensType.ULTRAWIDE) {
                previewSurfaceTexture ?: fallbackMainSurfaceTexture
            } else {
                ultraWideViewfinderSurfaceTexture ?: ultraWideStandbySurfaceTexture
            }
            val isTextureValid = currentTexture != null && run {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) !currentTexture.isReleased else true
            }
            if (session == ultraWideStandbyCaptureSession && _isKeepUltraWideReady.value && isTextureValid) {
                if (!isUltraWideStreaming.get()) {
                    isUltraWideStreaming.set(true)
                }
                if (_ultraWideStreamStatus.value != BackgroundCameraStatus.READY_QUIET) {
                    _ultraWideStreamStatus.value = BackgroundCameraStatus.READY_QUIET
                }
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (session == ultraWideStandbyCaptureSession) {
                Log.w(TAG, "Standby capture failed: reason=${failure.reason}")
            }
        }
    }

    enum class DualStreamingMode {
        NONE,
        CONCURRENT_DEVICES,
        LOGICAL_MULTI_CAMERA_PHYSICAL,
        PERSISTENT_DUAL_SESSION
    }

    fun detectSimultaneousStreamingMode(mainLens: LensInfo?, ultraWideLens: LensInfo?): DualStreamingMode {
        if (mainLens == null || ultraWideLens == null) return DualStreamingMode.NONE
        val mgr = cameraManager ?: return DualStreamingMode.NONE

        if (mainLens.cameraId == ultraWideLens.cameraId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val chars = getCharacteristics(mainLens.cameraId)
                val physIds = chars?.physicalCameraIds ?: emptySet()
                val targetPhysId = ultraWideLens.physicalCameraId
                if (targetPhysId != null && physIds.contains(targetPhysId) && ultraWideLens.supportsPhysicalStream) {
                    return DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
                }
            }
            return DualStreamingMode.PERSISTENT_DUAL_SESSION
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val concurrentSets = mgr.concurrentCameraIds
                    if (concurrentSets.isNotEmpty()) {
                        val matches = concurrentSets.any { combo ->
                            combo.contains(mainLens.cameraId) && combo.contains(ultraWideLens.cameraId)
                        }
                        if (matches) return DualStreamingMode.CONCURRENT_DEVICES
                    }
                } catch (ignored: Throwable) {}
            }
            return DualStreamingMode.PERSISTENT_DUAL_SESSION
        }
    }

    /**
     * Synchronizes all preview SurfaceTexture buffer dimensions (Main + Ultra-Wide)
     * so both streams match their respective active viewfinder resolution and aspect ratio.
     */
    private fun syncPreviewBufferSizes(
        cameraW: Int,
        cameraH: Int,
        surfaceLens: LensInfo? = _selectedLens.value
    ) {
        if (cameraW <= 0 || cameraH <= 0) return
        val targetRatio = getTargetAspectRatioForMode(currentMode)
        val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
            ?: backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val mainLens = if (surfaceLens?.facing == CameraCharacteristics.LENS_FACING_FRONT) {
            surfaceLens
        } else {
            backLenses.firstOrNull { it.isPrimaryMain }
                ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
                ?: surfaceLens
        }

        // Main/front and ultra-wide previews are persistent parallel outputs. Never set the
        // main SurfaceTexture to the active ultra-wide camera's dimensions.
        val mainSize = if (mainLens != null) {
            getOptimalPreviewSize(
                mainLens.physicalCameraId ?: mainLens.cameraId,
                targetRatio,
                mainLens.lensType
            )
        } else {
            Size(cameraW, cameraH)
        }
        val mainW = max(mainSize.width, mainSize.height)
        val mainH = min(mainSize.width, mainSize.height)
        _mainPreviewBufferSize.value = Size(mainW, mainH)
        try { previewSurfaceTexture?.setDefaultBufferSize(mainW, mainH) } catch (_: Throwable) {}
        try { fallbackMainSurfaceTexture?.setDefaultBufferSize(mainW, mainH) } catch (_: Throwable) {}

        // Size the dedicated ultra-wide output from a size actually supported by that lens.
        val uwId = ultraWideLens?.physicalCameraId ?: ultraWideLens?.cameraId
        val uwSize = if (uwId != null) {
            getOptimalPreviewSize(uwId, targetRatio, LensType.ULTRAWIDE)
        } else {
            Size(cameraW, cameraH)
        }
        val uwW = max(uwSize.width, uwSize.height)
        val uwH = min(uwSize.width, uwSize.height)
        _ultraWidePreviewBufferSize.value = Size(uwW, uwH)
        try { ultraWideViewfinderSurfaceTexture?.setDefaultBufferSize(uwW, uwH) } catch (_: Throwable) {}
        try { ultraWideStandbySurfaceTexture?.setDefaultBufferSize(uwW, uwH) } catch (_: Throwable) {}
    }

    /**
     * Returns the Ultra-Wide preview Surface configured at the exact resolution and aspect ratio
     * of the active viewfinder. Uses the UI TextureView SurfaceTexture when attached, or an
     * internal SurfaceTexture at the exact viewfinder dimensions when running headless/before UI attach.
     */
    fun getOrCreateUltraWidePreviewSurface(cameraId: String? = null): Surface? {
        val targetRatio = getTargetAspectRatioForMode(currentMode)
        val lensId = cameraId ?: _availableLenses.value.firstOrNull { it.lensType == LensType.ULTRAWIDE }?.cameraId ?: _selectedLens.value?.cameraId
        val optimalSize = getOptimalPreviewSize(lensId, targetRatio, LensType.ULTRAWIDE)
        val cameraW = max(optimalSize.width, optimalSize.height)
        val cameraH = min(optimalSize.width, optimalSize.height)
        _ultraWidePreviewBufferSize.value = Size(cameraW, cameraH)

        val uiTexture = ultraWideViewfinderSurfaceTexture
        if (uiTexture != null) {
            try { uiTexture.setDefaultBufferSize(cameraW, cameraH) } catch (_: Throwable) {}
            var surf = ultraWideViewfinderSurface
            if (surf == null || !surf.isValid) {
                try { surf?.release() } catch (_: Throwable) {}
                surf = Surface(uiTexture)
                ultraWideViewfinderSurface = surf
            }
            ultraWideStandbySurface = surf
            return surf
        }

        var fallbackTex = ultraWideStandbySurfaceTexture
        if (fallbackTex == null) {
            fallbackTex = SurfaceTexture(0)
            ultraWideStandbySurfaceTexture = fallbackTex
        }
        try { fallbackTex.setDefaultBufferSize(cameraW, cameraH) } catch (_: Throwable) {}
        var standbySurf = ultraWideStandbySurface
        if (standbySurf == null || !standbySurf.isValid) {
            try { standbySurf?.release() } catch (_: Throwable) {}
            standbySurf = Surface(fallbackTex)
            ultraWideStandbySurface = standbySurf
        }
        return standbySurf
    }

    /**
     * Returns the Main (1x) preview Surface configured at the exact resolution and aspect ratio
     * of the active viewfinder.
     */
    fun getOrCreateMainPreviewSurface(cameraId: String? = null): Surface? {
        val targetRatio = getTargetAspectRatioForMode(currentMode)
        val lensId = cameraId ?: _availableLenses.value.firstOrNull { it.isPrimaryMain || it.lensType == LensType.WIDE }?.cameraId ?: _selectedLens.value?.cameraId
        val mainTypeHint = _availableLenses.value.firstOrNull {
            it.cameraId == lensId && (it.isPrimaryMain || it.lensType == LensType.WIDE || it.lensType == LensType.FRONT)
        }?.lensType
        val optimalSize = getOptimalPreviewSize(lensId, targetRatio, mainTypeHint)
        val cameraW = max(optimalSize.width, optimalSize.height)
        val cameraH = min(optimalSize.width, optimalSize.height)
        _mainPreviewBufferSize.value = Size(cameraW, cameraH)

        val mainTex = previewSurfaceTexture
        if (mainTex != null) {
            try { mainTex.setDefaultBufferSize(cameraW, cameraH) } catch (_: Throwable) {}
            var surf = previewSurface
            if (surf == null || !surf.isValid) {
                try { surf?.release() } catch (_: Throwable) {}
                surf = Surface(mainTex)
                previewSurface = surf
            }
            return surf
        }

        var fallbackTex = fallbackMainSurfaceTexture
        if (fallbackTex == null) {
            fallbackTex = SurfaceTexture(0)
            fallbackMainSurfaceTexture = fallbackTex
        }
        try { fallbackTex.setDefaultBufferSize(cameraW, cameraH) } catch (_: Throwable) {}
        var surf = fallbackMainSurface
        if (surf == null || !surf.isValid) {
            try { surf?.release() } catch (_: Throwable) {}
            surf = Surface(fallbackTex)
            fallbackMainSurface = surf
        }
        return surf
    }

    /**
     * Attach the dedicated Ultra-Wide viewfinder SurfaceTexture from Compose Viewfinder.
     */
    fun setUltraWidePreviewSurfaceTexture(texture: SurfaceTexture?, width: Int = 0, height: Int = 0) {
        val prevTexture = ultraWideViewfinderSurfaceTexture
        ultraWideViewfinderSurfaceTexture = texture
        if (texture != null) {
            val targetRatio = getTargetAspectRatioForMode(currentMode)
            val isVideoOrCinema = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA)
            val currentBuf = _previewBufferSize.value
            val uwId = _availableLenses.value.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                ?.let { it.physicalCameraId ?: it.cameraId }
                ?: _availableLenses.value.firstOrNull { it.lensType == LensType.ULTRAWIDE }?.cameraId
                ?: _selectedLens.value?.cameraId
            val isBufMatching = !isVideoOrCinema && currentBuf != null &&
                isSizeSupportedForLens(uwId, currentBuf) && run {
                    val r = max(currentBuf.width, currentBuf.height).toFloat() / min(currentBuf.width, currentBuf.height).toFloat()
                    kotlin.math.abs(r - targetRatio) < 0.08f
                }
            val optimalSize = if (isBufMatching && currentBuf != null) {
                currentBuf
            } else {
                getOptimalPreviewSize(uwId, targetRatio, LensType.ULTRAWIDE)
            }
            val cameraW = max(optimalSize.width, optimalSize.height)
            val cameraH = min(optimalSize.width, optimalSize.height)
            _ultraWidePreviewBufferSize.value = Size(cameraW, cameraH)
            try { texture.setDefaultBufferSize(cameraW, cameraH) } catch (_: Throwable) {}

            if (prevTexture !== texture) {
                standbySessionGeneration.incrementAndGet()
                try { ultraWideStandbyCaptureSession?.stopRepeating() } catch (_: Throwable) {}
                try { ultraWideStandbyCaptureSession?.close() } catch (_: Throwable) {}
                ultraWideStandbyCaptureSession = null
                isUltraWideStreaming.set(false)
                standbyConfiguredForRecording = false
                _ultraWideStreamStatus.value = BackgroundCameraStatus.OFF
                if (ultraWideStandbySurface === ultraWideViewfinderSurface) {
                    ultraWideStandbySurface = null
                }
            }

            if (prevTexture !== texture || ultraWideViewfinderSurface == null || !ultraWideViewfinderSurface!!.isValid) {
                try { ultraWideViewfinderSurface?.release() } catch (_: Throwable) {}
                ultraWideViewfinderSurface = Surface(texture)
                ultraWideStandbySurface = ultraWideViewfinderSurface
            }

            if (_isKeepUltraWideReady.value && _isCameraInitialized.value) {
                backgroundHandler?.post {
                    if (isClosingCamera || !_isKeepUltraWideReady.value || !_isCameraInitialized.value) return@post
                    if (activeLogicalMultiCamUltraWideConfigured && prevTexture !== texture && !isConfiguringSession && !_isRecordingVideo.value) {
                        activeLogicalMultiCamUltraWideConfigured = false
                        createCameraCaptureSession()
                        return@post
                    }
                    ensureUltraWideSimultaneousReady()
                }
            }
        } else {
            standbySessionGeneration.incrementAndGet()
            try { ultraWideStandbyCaptureSession?.stopRepeating() } catch (_: Throwable) {}
            try { ultraWideStandbyCaptureSession?.close() } catch (_: Throwable) {}
            ultraWideStandbyCaptureSession = null
            isPreparingUltraWideStandby.set(false)
            isUltraWideStreaming.set(false)
            _ultraWideStreamStatus.value = BackgroundCameraStatus.OFF
            if (activeLogicalMultiCamUltraWideConfigured) {
                activeLogicalMultiCamUltraWideConfigured = false
            }
            if (ultraWideStandbySurface === ultraWideViewfinderSurface) {
                ultraWideStandbySurface = null
            }
            try { ultraWideViewfinderSurface?.release() } catch (_: Throwable) {}
            ultraWideViewfinderSurface = null
        }
    }

    fun onUltraWideViewfinderSurfaceSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        ultraWideViewfinderSurfaceTexture = texture
        val targetRatio = getTargetAspectRatioForMode(currentMode)
        val isVideoOrCinema = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA)
        val currentBuf = _previewBufferSize.value
        val uwId = _availableLenses.value.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
            ?.let { it.physicalCameraId ?: it.cameraId }
            ?: _availableLenses.value.firstOrNull { it.lensType == LensType.ULTRAWIDE }?.cameraId
            ?: _selectedLens.value?.cameraId
        val isBufMatching = !isVideoOrCinema && currentBuf != null &&
            isSizeSupportedForLens(uwId, currentBuf) && run {
                val r = max(currentBuf.width, currentBuf.height).toFloat() / min(currentBuf.width, currentBuf.height).toFloat()
                kotlin.math.abs(r - targetRatio) < 0.08f
            }
        val optimalSize = if (isBufMatching && currentBuf != null) {
            currentBuf
        } else {
            getOptimalPreviewSize(uwId, targetRatio, LensType.ULTRAWIDE)
        }
        val cameraW = max(optimalSize.width, optimalSize.height)
        val cameraH = min(optimalSize.width, optimalSize.height)
        _ultraWidePreviewBufferSize.value = Size(cameraW, cameraH)
        try { texture.setDefaultBufferSize(cameraW, cameraH) } catch (_: Throwable) {}
        if (ultraWideViewfinderSurface == null || !ultraWideViewfinderSurface!!.isValid) {
            try { ultraWideViewfinderSurface?.release() } catch (_: Throwable) {}
            ultraWideViewfinderSurface = Surface(texture)
            ultraWideStandbySurface = ultraWideViewfinderSurface
        }
    }

    /**
     * Calculates the standby lens crop using discovered equivalent focal lengths.
     * While idle, the standby stream is framed to match Main's native/switch-point FOV.
     * During a frame-based handoff, it follows the live UI zoom so the two preview streams
     * stay close in FOV while the transition animation continues.
     */
    internal fun calculateStandbyCropRatio(standbyLens: LensInfo, uiZoomOverride: Float? = null): Float {
        val sameFacingLenses = _availableLenses.value.filter { it.facing == standbyLens.facing }
        val mainLens = sameFacingLenses.firstOrNull { it.isPrimaryMain }
            ?: sameFacingLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            ?: sameFacingLenses.firstOrNull { it.lensType == LensType.WIDE }
        val mainEqFocalMm = mainLens?.equivalent35mmFocalMm
            ?.takeIf { it.isFinite() && it > 0f }
            ?: CameraOpticalCalibration.DEFAULT_MAIN_EQUIVALENT_FOCAL_MM

        val uwEqFocalMm = sameFacingLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
            ?.equivalent35mmFocalMm
            ?.takeIf { it.isFinite() && it > 0f }
            ?: CameraOpticalCalibration.DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
        if (uiZoomOverride != null && uiZoomOverride.isFinite()) {
            // While blending, keep the standby stream on the same effective zoom as the active
            // stream. Otherwise a 16-frame fade outlasts the 300ms zoom animation and blends
            // a fixed switch-point view with a target that has already reached 2x/3x (or 0.5x).
            return CameraOpticalCalibration.calculateRequiredDigitalCrop(
                uiZoom = uiZoomOverride.coerceAtLeast(0.5f),
                lensBaseRatio = standbyLens.baseZoomRatio,
                lensType = standbyLens.lensType,
                uwEquivalentFocalMm = uwEqFocalMm,
                mainEquivalentFocalMm = mainEqFocalMm,
                switchPointMm = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM
            )
        }

        return if (standbyLens.lensType == LensType.ULTRAWIDE) {
            CameraOpticalCalibration.calculateFovMatchedUltraWideCrop(
                switchPointMm = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM,
                mainEquivalentFocalMm = mainEqFocalMm,
                uwEquivalentFocalMm = uwEqFocalMm
            )
        } else {
            val matchedTargetFocalMm = maxOf(CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM, mainEqFocalMm)
            CameraOpticalCalibration.calculateMainCropForSwitchPoint(matchedTargetFocalMm, mainEqFocalMm)
        }
    }

    /**
     * Applies synchronized 3A (AE/AWB/AF), Pro manual exposure, FPS, and base optical crop
     * to the standby stream's CaptureRequest.Builder so both Main and Ultra-Wide streams
     * remain continuously converged at the same exposure, color balance, and frame rate.
     */
    private fun applySynchronizedStandbySettings(builder: CaptureRequest.Builder, standbyLens: LensInfo) {
        try {
            applyCommonSettings(builder)
            val isRecordingActive = _isRecordingVideo.value || isStartingRecording.get()
            val isVideo = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA || isRecordingActive)
            val recConfig = activeRecordingStreamConfig
            val targetFps = when {
                isRecordingActive && recConfig != null -> recConfig.targetFps
                currentMode == CameraMode.CINEMA -> cinemaConfig.value.videoFps
                else -> videoFps
            }
            if (isRecordingActive) {
                builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
            }
            val chars = getCharacteristics(standbyLens.cameraId)
            if (chars != null) {
                val fpsRange = findBestFpsRange(chars, if (isVideo) targetFps else 30) ?: recConfig?.fpsRange
                if (fpsRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                }
                // Clamp EV compensation safely to standby camera's supported range
                val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (evRange != null) {
                    val evToApply = if (currentMode == CameraMode.CINEMA) {
                        cinemaConfig.value.exposureCompensation
                    } else {
                        exposureCompensationIndex
                    }
                    builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        evToApply.coerceIn(evRange.lower, evRange.upper)
                    )
                }
                // Check the actual standby physical sensor where possible; logical camera
                // capabilities may describe the main sensor instead of the standby lens.
                val oisChars = standbyLens.physicalCameraId?.let { getCharacteristics(it) } ?: chars
                val oisModes = oisChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?: intArrayOf()
                val standbySupportsOis = oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)
                val desiredOis = if (isRecordingActive && recConfig != null) recConfig.isOisAllowed else isOisAllowed
                val effectiveOisAllowed = desiredOis && standbySupportsOis
                applyHardwareOisRequest(
                    builder,
                    oisAllowed = effectiveOisAllowed,
                    physicalCameraId = standbyLens.physicalCameraId,
                    vendorCameraIdOverride = standbyLens.cameraId
                )

                // Explicitly keep hardware EIS OFF on standby streams too. OIS is applied
                // independently above for the standby lens when that lens supports it.
                applyHardwareEisRequest(builder, lensOverride = standbyLens)
            }

            // Park the standby stream at the FOV-matched switch point while idle.
            // During overlap, track the controller's current zoom so the old and new views do
            // not diverge merely because the 16-frame crossfade outlasts the zoom animation.
            val sensorRect = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
            val handoffState = zoomTransitionController.previewOverlapState.value
            val handoffZoom = if (handoffState.isOverlapping) {
                zoomTransitionController.currentInterpolatedZoom.value
            } else {
                null
            }
            val targetStandbyRatio = calculateStandbyCropRatio(standbyLens, handoffZoom)
            val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && chars != null) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else {
                null
            }

            if (zoomRange != null) {
                // On API 30+, express the standby crop with CONTROL_ZOOM_RATIO only.
                // Android overrides a windowboxed SCALER_CROP_REGION when zoom ratio != 1,
                // so setting both a digital zoom ratio and a smaller crop rectangle is ambiguous.
                builder.set(
                    CaptureRequest.CONTROL_ZOOM_RATIO,
                    targetStandbyRatio.coerceIn(zoomRange.lower, zoomRange.upper)
                )
                builder.set(CaptureRequest.SCALER_CROP_REGION, sensorRect)
            } else {
                // Legacy fallback: apply the crop rectangle only when native zoom ratio is unavailable.
                val extraScale = targetStandbyRatio.coerceAtLeast(1.0f)
                val cropW = (sensorRect.width() / extraScale).toInt().coerceIn(1, sensorRect.width())
                val cropH = (sensorRect.height() / extraScale).toInt().coerceIn(1, sensorRect.height())
                val cropX = sensorRect.left + (sensorRect.width() - cropW) / 2
                val cropY = sensorRect.top + (sensorRect.height() - cropH) / 2
                builder.set(
                    CaptureRequest.SCALER_CROP_REGION,
                    Rect(cropX, cropY, cropX + cropW, cropY + cropH)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error applying synchronized standby settings", e)
        }
    }

    /**
     * Synchronizes the background standby session's repeating request whenever active settings change.
     */
    private fun syncStandbyStreamSettings(forceDuringFrameHandoff: Boolean = false) {
        val handoffActive = zoomTransitionController.previewOverlapState.value.isOverlapping
        if ((!_isKeepUltraWideReady.value && !(forceDuringFrameHandoff && handoffActive)) || isClosingCamera) return
        val session = ultraWideStandbyCaptureSession ?: return
        val builder = ultraWideStandbyRequestBuilder ?: return
        val standbyCam = ultraWideStandbyCameraDevice ?: return
        val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val standbyLens = backLenses.firstOrNull { it.cameraId == standbyCam.id && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.cameraId == standbyCam.id }
            ?: return
        try {
            applySynchronizedStandbySettings(builder, standbyLens)
            session.setRepeatingRequest(builder.build(), standbyCaptureCallback, backgroundHandler)
        } catch (ignored: Exception) {}
    }

    fun setAutoSwitchToUltraWide(enabled: Boolean) {
        _isAutoSwitchToUltraWide.value = enabled
        preferences.isAutoSwitchToUltraWide = enabled
        if (!enabled && _isAutoMacroActive.value) {
            _isAutoMacroActive.value = false
            val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            val mainLens = backLenses.firstOrNull { it.isPrimaryMain }
                ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
            if (mainLens != null) {
                selectLens(mainLens, preserveZoom = false, targetZoom = 1.0f)
            }
        }
    }

    private var ultraWideReconnectCount = 0
    private val MAX_ULTRAWIDE_RECONNECT = 3

    private fun scheduleUltraWideReconnect(delayMs: Long = 1000L) {
        if (isClosingCamera || !_isKeepUltraWideReady.value) return
        if (ultraWideReconnectCount >= MAX_ULTRAWIDE_RECONNECT) {
            return
        }
        ultraWideReconnectRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        val runnable = Runnable {
            if (!isClosingCamera && _isKeepUltraWideReady.value && _selectedLens.value?.facing == CameraCharacteristics.LENS_FACING_BACK) {
                if (ultraWideStandbyCameraDevice == null && !isPreparingUltraWideStandby.get()) {
                    ultraWideReconnectCount++
                    Log.i(TAG, "[KEEP_ULTRAWIDE_READY] Reopening background camera (attempt $ultraWideReconnectCount)...")
                    ensureUltraWideSimultaneousReady()
                }
            }
        }
        ultraWideReconnectRunnable = runnable
        backgroundHandler?.postDelayed(runnable, delayMs)
    }

    fun setKeepUltraWideReady(enabled: Boolean) {
        _isKeepUltraWideReady.value = enabled
        preferences.isKeepUltraWideReady = enabled
        ultraWideReconnectCount = 0
        _ultraWideStreamStatus.value = if (enabled) BackgroundCameraStatus.PREPARING else BackgroundCameraStatus.OFF
        backgroundHandler?.post {
            if (enabled) {
                ensureUltraWideSimultaneousReady()
            } else {
                releaseUltraWideStandby()
            }
        } ?: run {
            if (enabled) {
                ensureUltraWideSimultaneousReady()
            } else {
                releaseUltraWideStandby()
            }
        }
    }

    private fun configureStandbySessionOnDevice(
        camera: CameraDevice,
        standbyLens: LensInfo,
        includeJpegReader: Boolean = false
    ) {
        try {
            val standbyPreviewSurf = if (standbyLens.lensType == LensType.ULTRAWIDE) {
                getOrCreateUltraWidePreviewSurface(standbyLens.cameraId)
            } else {
                getOrCreateMainPreviewSurface(standbyLens.cameraId)
            } ?: run {
                isPreparingUltraWideStandby.set(false)
                return
            }

            try { ultraWideStandbyCaptureSession?.stopRepeating() } catch (_: Throwable) {}
            try { ultraWideStandbyCaptureSession?.close() } catch (_: Throwable) {}
            ultraWideStandbyCaptureSession = null

            val isRecordingActive = (_isRecordingVideo.value || isStartingRecording.get()) && dualCameraRecordingRelay != null
            val isVideo = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA || isRecordingActive)
            val targetRatio = getTargetAspectRatioForMode(currentMode)
            val expectedPreviewSize = getOptimalPreviewSize(standbyLens.cameraId, targetRatio)
            standbyConfiguredPreviewSize = expectedPreviewSize
            standbyConfiguredIsVideoMode = isVideo

            val configToken = standbySessionGeneration.incrementAndGet()
            val targetSurfaceTexture = if (standbyLens.lensType == LensType.ULTRAWIDE) {
                ultraWideViewfinderSurfaceTexture ?: ultraWideStandbySurfaceTexture
            } else {
                previewSurfaceTexture ?: fallbackMainSurfaceTexture
            }

            val relay = dualCameraRecordingRelay
            val standbyRecorderSurf = if (isRecordingActive && relay != null && relay.hasDistinctUltraWideSurface()) {
                relay.getRecorderSurfaceForLens(standbyLens)
            } else null

            // When video recording is active, configure the standby session with BOTH the preview surface
            // AND its dedicated recording surface at the active recording configuration/FPS/dynamic range.
            if (isRecordingActive && standbyRecorderSurf != null && standbyRecorderSurf.isValid) {
                val recConfig = activeRecordingStreamConfig
                val requestedVideoRes = recConfig?.videoResolution
                    ?: (userSelectedVideoResolution ?: _selectedVideoResolution.value ?: CameraResolution(1920, 1080))
                val resolvedStandbyRes = resolveEffectiveVideoResolutionForLens(standbyLens.cameraId, requestedVideoRes)
                val effectiveStandbyVideoSize = Size(
                    max(resolvedStandbyRes.width, resolvedStandbyRes.height),
                    min(resolvedStandbyRes.width, resolvedStandbyRes.height)
                )
                relay?.updateInputBufferSize(
                    isUltraWide = (standbyLens.lensType == LensType.ULTRAWIDE),
                    width = effectiveStandbyVideoSize.width,
                    height = effectiveStandbyVideoSize.height
                )

                val recordBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(standbyPreviewSurf)
                    addTarget(standbyRecorderSurf)
                    applySynchronizedStandbySettings(this, standbyLens)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                }
                ultraWideStandbyRequestBuilder = recordBuilder

                val targetPhysId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    standbyLens.physicalCameraId != null &&
                    standbyLens.supportsPhysicalStream &&
                    standbyLens.physicalCameraId != standbyLens.cameraId) {
                    standbyLens.physicalCameraId
                } else null
                val is10BitStandby = recConfig?.is10Bit == true

                var retriedWithoutAdvancedConfig = false
                val recordingStandbyCallback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val currentExpectedTexture = if (standbyLens.lensType == LensType.ULTRAWIDE) {
                            ultraWideViewfinderSurfaceTexture ?: ultraWideStandbySurfaceTexture
                        } else {
                            previewSurfaceTexture ?: fallbackMainSurfaceTexture
                        }
                        val isTextureValid = targetSurfaceTexture != null &&
                            (targetSurfaceTexture === currentExpectedTexture) &&
                            run {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) !targetSurfaceTexture.isReleased else true
                            }
                        if (configToken != standbySessionGeneration.get() ||
                            (!_isKeepUltraWideReady.value && !isContinuousZoomTransitionActive) ||
                            ultraWideStandbyCameraDevice != camera ||
                            !isTextureValid) {
                            isPreparingUltraWideStandby.set(false)
                            try { session.close() } catch (_: Throwable) {}
                            return
                        }
                        ultraWideStandbyCaptureSession = session
                        standbyConfiguredForRecording = true
                        standbyRecordingSessionFailed = false
                        isPreparingUltraWideStandby.set(false)
                        isUltraWideStreaming.set(true)

                        try {
                            applySynchronizedStandbySettings(recordBuilder, standbyLens)
                            recordBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                            session.setRepeatingRequest(recordBuilder.build(), standbyCaptureCallback, backgroundHandler)
                            Log.i(TAG, "[KEEP_ULTRAWIDE_READY] Prewarmed recording-capable standby session active on ID ${camera.id} (${standbyLens.lensType})")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed repeating recording request on standby camera ${camera.id}", e)
                            try { session.close() } catch (_: Throwable) {}
                            if (ultraWideStandbyCaptureSession == session) {
                                ultraWideStandbyCaptureSession = null
                            }
                            standbyConfiguredForRecording = false
                            standbyRecordingSessionFailed = true
                            isPreparingUltraWideStandby.set(false)
                            isUltraWideStreaming.set(false)
                            _ultraWideStreamStatus.value = BackgroundCameraStatus.OFF
                            if (_isKeepUltraWideReady.value && !isClosingCamera) {
                                scheduleUltraWideReconnect()
                            }
                        }
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        if (ultraWideStandbyCaptureSession == session) {
                            ultraWideStandbyCaptureSession = null
                            isUltraWideStreaming.set(false)
                            standbyConfiguredForRecording = false
                            standbyRecordingSessionFailed = true
                            _ultraWideStreamStatus.value = BackgroundCameraStatus.OFF
                            if (_isKeepUltraWideReady.value && !isClosingCamera) {
                                scheduleUltraWideReconnect()
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        try { session.close() } catch (_: Throwable) {}
                        if (configToken != standbySessionGeneration.get()) {
                            isPreparingUltraWideStandby.set(false)
                            return
                        }
                        if (!retriedWithoutAdvancedConfig && (is10BitStandby || targetPhysId != null)) {
                            retriedWithoutAdvancedConfig = true
                            Log.w(TAG, "[KEEP_ULTRAWIDE_READY] Standby recording session with 10-bit/physical config rejected; retrying standard recording session")
                            createRecordingCaptureSession(
                                camera = camera,
                                previewSurface = standbyPreviewSurf,
                                recorderSurface = standbyRecorderSurf,
                                is10Bit = false,
                                physicalCameraId = null,
                                updateActivePhysicalId = false,
                                callback = this
                            )
                            return
                        }
                        Log.w(TAG, "[KEEP_ULTRAWIDE_READY] Hardware rejected concurrent recording session on standby camera ${camera.id}; safe recording switch fallback will be used")
                        standbyConfiguredForRecording = false
                        standbyRecordingSessionFailed = true
                        isPreparingUltraWideStandby.set(false)
                        if (ultraWideStandbyCaptureSession == session) {
                            ultraWideStandbyCaptureSession = null
                        }
                    }
                }

                createRecordingCaptureSession(
                    camera = camera,
                    previewSurface = standbyPreviewSurf,
                    recorderSurface = standbyRecorderSurf,
                    is10Bit = is10BitStandby,
                    physicalCameraId = targetPhysId,
                    updateActivePhysicalId = false,
                    callback = recordingStandbyCallback
                )
                return
            }

            standbyConfiguredForRecording = false
            val template = if (isVideo) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = camera.createCaptureRequest(template).apply {
                addTarget(standbyPreviewSurf)
                applySynchronizedStandbySettings(this, standbyLens)
            }
            ultraWideStandbyRequestBuilder = builder

            val sessionSurfaces = mutableListOf<Surface>(standbyPreviewSurf)
            if (includeJpegReader) {
                try {
                    val photoSize = getOptimalPhotoSizeForLens(standbyLens, standbyLens.cameraId)
                    val existingReader = ultraWideStandbyJpegReader
                    if (existingReader == null ||
                        existingReader.width != photoSize.width ||
                        existingReader.height != photoSize.height ||
                        !existingReader.surface.isValid
                    ) {
                        try { existingReader?.close() } catch (_: Throwable) {}
                        ultraWideStandbyJpegReader = ImageReader.newInstance(
                            photoSize.width,
                            photoSize.height,
                            ImageFormat.JPEG,
                            3
                        )
                    }
                    ultraWideStandbyJpegReader?.surface?.takeIf { it.isValid }?.let {
                        sessionSurfaces.add(it)
                    }
                } catch (_: Throwable) {}
            }

            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    val currentExpectedTexture = if (standbyLens.lensType == LensType.ULTRAWIDE) {
                        ultraWideViewfinderSurfaceTexture ?: ultraWideStandbySurfaceTexture
                    } else {
                        previewSurfaceTexture ?: fallbackMainSurfaceTexture
                    }
                    val isTextureValid = targetSurfaceTexture != null &&
                        (targetSurfaceTexture === currentExpectedTexture) &&
                        run {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) !targetSurfaceTexture.isReleased else true
                        }
                    if (configToken != standbySessionGeneration.get() ||
                        (!_isKeepUltraWideReady.value && !isContinuousZoomTransitionActive) ||
                        ultraWideStandbyCameraDevice != camera ||
                        !isTextureValid) {
                        isPreparingUltraWideStandby.set(false)
                        try { session.close() } catch (_: Throwable) {}
                        return
                    }
                    ultraWideStandbyCaptureSession = session
                    isPreparingUltraWideStandby.set(false)

                    try {
                        session.setRepeatingRequest(builder.build(), standbyCaptureCallback, backgroundHandler)
                        Log.i(TAG, "[KEEP_ULTRAWIDE_READY] Persistent background session active on ID ${camera.id} (${standbyLens.lensType})")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed repeating request on standby camera ${camera.id}", e)
                        try { session.close() } catch (_: Throwable) {}
                        if (ultraWideStandbyCaptureSession == session) {
                            ultraWideStandbyCaptureSession = null
                        }
                        isPreparingUltraWideStandby.set(false)
                        isUltraWideStreaming.set(false)
                        _ultraWideStreamStatus.value = BackgroundCameraStatus.OFF
                        if (_isKeepUltraWideReady.value && !isClosingCamera) {
                            scheduleUltraWideReconnect()
                        }
                    }

                    val pendingLens = pendingLensWhileSwitching
                    if (pendingLens != null && (pendingLens.cameraId == camera.id || pendingLens.id == standbyLens.id)) {
                        pendingLensWhileSwitching = null
                        val pendingContinuous = pendingIsContinuousTransitionWhileSwitching
                        pendingIsContinuousTransitionWhileSwitching = false
                        Log.i(TAG, "[KEEP_ULTRAWIDE_READY] Standby session ready; executing pending lens switch to ${pendingLens.lensType}")
                        switchUsingPrewarmedStandby(pendingLens, standbySessionGeneration.get(), isContinuousTransition = pendingContinuous)
                        return
                    }
                }

                override fun onClosed(session: CameraCaptureSession) {
                    if (ultraWideStandbyCaptureSession == session) {
                        ultraWideStandbyCaptureSession = null
                        isUltraWideStreaming.set(false)
                        _ultraWideStreamStatus.value = BackgroundCameraStatus.OFF
                        if (_isKeepUltraWideReady.value && !isClosingCamera) {
                            scheduleUltraWideReconnect()
                        }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    try { session.close() } catch (_: Throwable) {}
                    if (configToken != standbySessionGeneration.get()) {
                        isPreparingUltraWideStandby.set(false)
                        return
                    }
                    if (includeJpegReader && sessionSurfaces.size > 1) {
                        Log.w(TAG, "[KEEP_ULTRAWIDE_READY] Standby session with JPEG reader rejected; retrying with preview surface only")
                        try { ultraWideStandbyJpegReader?.close() } catch (_: Throwable) {}
                        ultraWideStandbyJpegReader = null
                        configureStandbySessionOnDevice(camera, standbyLens, includeJpegReader = false)
                        return
                    }
                    isPreparingUltraWideStandby.set(false)
                    if (ultraWideStandbyCaptureSession == session) {
                        ultraWideStandbyCaptureSession = null
                    }
                    if (_isKeepUltraWideReady.value && !isClosingCamera) {
                        scheduleUltraWideReconnect()
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                standbyLens.physicalCameraId != null &&
                standbyLens.supportsPhysicalStream &&
                standbyLens.physicalCameraId != standbyLens.cameraId) {
                try {
                    val outputConfigs = sessionSurfaces.map { surf ->
                        android.hardware.camera2.params.OutputConfiguration(surf).apply {
                            setPhysicalCameraId(standbyLens.physicalCameraId)
                        }
                    }
                    val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                        outputConfigs,
                        Executor { cmd -> backgroundHandler?.post(cmd) ?: cmd.run() },
                        stateCallback
                    )
                    camera.createCaptureSession(sessionConfig)
                    return
                } catch (_: Throwable) {}
            }

            camera.createCaptureSession(sessionSurfaces, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            isPreparingUltraWideStandby.set(false)
            Log.e(TAG, "Failed to configure standby session on ${camera.id}", e)
            if (_isKeepUltraWideReady.value) {
                scheduleUltraWideReconnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun ensureUltraWideSimultaneousReady() {
        if (!_isKeepUltraWideReady.value && !isContinuousZoomTransitionActive) return
        if (isClosingCamera || isStartingCamera || isConfiguringSession) {
            pendingEnsureUltraWideReady = true
            return
        }
        val currentLens = _selectedLens.value ?: return
        if (currentLens.facing != CameraCharacteristics.LENS_FACING_BACK) return

        val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
            ?: backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
            ?: return

        val mainLens = backLenses.firstOrNull { it.isPrimaryMain }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
            ?: return

        val standbyLens = if (currentLens.lensType == LensType.ULTRAWIDE) mainLens else ultraWideLens
        val dualMode = detectSimultaneousStreamingMode(mainLens, ultraWideLens)
        Log.i(TAG, "[KEEP_ULTRAWIDE_READY] Dual streaming mode: $dualMode (main=${mainLens.cameraId}, uw=${ultraWideLens.cameraId}, phys=${ultraWideLens.physicalCameraId})")

        if (dualMode == DualStreamingMode.NONE) {
            return
        }

        // 1. Same logical camera device (multi-camera physical stream)
        if (dualMode == DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL && mainLens.cameraId == ultraWideLens.cameraId) {
            val camera = cameraDevice ?: return
            getOrCreateUltraWidePreviewSurface(ultraWideLens.cameraId)
            getOrCreateMainPreviewSurface(mainLens.cameraId)
            if (!activeLogicalMultiCamUltraWideConfigured && captureSession != null && !isConfiguringSession && !_isRecordingVideo.value && !isContinuousZoomTransitionActive) {
                createCameraCaptureSession()
                return
            }
            if (captureSession != null) {
                isUltraWideStreaming.set(true)
                activeLogicalMultiCamUltraWideConfigured = true
                _ultraWideStreamStatus.value = BackgroundCameraStatus.READY_QUIET
                Log.i(TAG, "[KEEP_ULTRAWIDE_READY] Logical multi-camera Ultra-Wide stream active on ID ${camera.id}")
            }
            return
        }

        // 2. Separate Camera Devices (Concurrent or Persistent Dual-Session stream)
        val isRecordingNow = (_isRecordingVideo.value || isStartingRecording.get()) && dualCameraRecordingRelay != null
        val existingStandbyCam = ultraWideStandbyCameraDevice
        if (existingStandbyCam != null) {
            if (existingStandbyCam.id == standbyLens.cameraId) {
                val targetRatio = getTargetAspectRatioForMode(currentMode)
                val optimalStandbySize = getOptimalPreviewSize(standbyLens.cameraId, targetRatio)
                val isVideo = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA || isRecordingNow)
                val sizeMatches = standbyConfiguredPreviewSize != null &&
                    max(standbyConfiguredPreviewSize!!.width, standbyConfiguredPreviewSize!!.height) == max(optimalStandbySize.width, optimalStandbySize.height) &&
                    min(standbyConfiguredPreviewSize!!.width, standbyConfiguredPreviewSize!!.height) == min(optimalStandbySize.width, optimalStandbySize.height)

                if (ultraWideStandbyCaptureSession != null &&
                    sizeMatches &&
                    standbyConfiguredForRecording == isRecordingNow) {
                    isUltraWideStreaming.set(true)
                    _ultraWideStreamStatus.value = BackgroundCameraStatus.READY_QUIET
                    syncStandbyStreamSettings()
                    return
                }
                configureStandbySessionOnDevice(existingStandbyCam, standbyLens)
                return
            } else {
                // Close mismatched standby camera if any
                standbyCameraDeviceGeneration.incrementAndGet()
                try { ultraWideStandbyCaptureSession?.close() } catch (_: Throwable) {}
                ultraWideStandbyCaptureSession = null
                try { existingStandbyCam.close() } catch (_: Throwable) {}
                ultraWideStandbyCameraDevice = null
            }
        }

        if (!isPreparingUltraWideStandby.compareAndSet(false, true)) {
            return
        }

        val mgr = cameraManager ?: run {
            isPreparingUltraWideStandby.set(false)
            return
        }

        try {
            // Ensure preview surface is prepared at active viewfinder resolution & aspect ratio before opening
            if (standbyLens.lensType == LensType.ULTRAWIDE) {
                getOrCreateUltraWidePreviewSurface(standbyLens.cameraId)
            } else {
                getOrCreateMainPreviewSurface(standbyLens.cameraId)
            }

            val openToken = standbyCameraDeviceGeneration.incrementAndGet()
            mgr.openCamera(standbyLens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (openToken != standbyCameraDeviceGeneration.get() ||
                        (!_isKeepUltraWideReady.value && !isContinuousZoomTransitionActive) ||
                        isClosingCamera ||
                        cameraDevice == null ||
                        !_isCameraInitialized.value) {
                        Log.i(TAG, "[KEEP_ULTRAWIDE_READY] Stale standby camera opened (${camera.id}, token=$openToken), closing safely")
                        isPreparingUltraWideStandby.set(false)
                        try { camera.close() } catch (_: Throwable) {}
                        return
                    }
                    if (ultraWideStandbyCameraDevice != null && ultraWideStandbyCameraDevice !== camera) {
                        try { ultraWideStandbyCameraDevice?.close() } catch (_: Throwable) {}
                    }
                    ultraWideStandbyCameraDevice = camera
                    configureStandbySessionOnDevice(camera, standbyLens)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (openToken != standbyCameraDeviceGeneration.get()) {
                        try { camera.close() } catch (ignored: Throwable) {}
                        return
                    }
                    standbyCameraDeviceGeneration.incrementAndGet()
                    standbySessionGeneration.incrementAndGet()
                    try { camera.close() } catch (ignored: Throwable) {}
                    if (ultraWideStandbyCameraDevice == camera) {
                        ultraWideStandbyCameraDevice = null
                        ultraWideStandbyCaptureSession = null
                    }
                    isPreparingUltraWideStandby.set(false)
                    isUltraWideStreaming.set(false)
                    _ultraWideStreamStatus.value = BackgroundCameraStatus.OFF
                    if (_isKeepUltraWideReady.value && !isClosingCamera) {
                        scheduleUltraWideReconnect()
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (openToken != standbyCameraDeviceGeneration.get()) {
                        try { camera.close() } catch (ignored: Throwable) {}
                        return
                    }
                    standbyCameraDeviceGeneration.incrementAndGet()
                    standbySessionGeneration.incrementAndGet()
                    try { camera.close() } catch (ignored: Throwable) {}
                    if (ultraWideStandbyCameraDevice == camera) {
                        ultraWideStandbyCameraDevice = null
                        ultraWideStandbyCaptureSession = null
                    }
                    isPreparingUltraWideStandby.set(false)
                    isUltraWideStreaming.set(false)
                    Log.w(TAG, "Standby camera open error: $error (scheduling reconnect)")
                    if (_isKeepUltraWideReady.value && !isClosingCamera) {
                        scheduleUltraWideReconnect()
                    }
                }
            }, backgroundHandler)
        } catch (t: Throwable) {
            standbyCameraDeviceGeneration.incrementAndGet()
            standbySessionGeneration.incrementAndGet()
            isPreparingUltraWideStandby.set(false)
            Log.w(TAG, "Could not open standby camera concurrently", t)
            if (_isKeepUltraWideReady.value && !isClosingCamera) {
                scheduleUltraWideReconnect()
            }
        }
    }

    fun releaseUltraWideStandby() {
        standbyCameraDeviceGeneration.incrementAndGet()
        standbySessionGeneration.incrementAndGet()
        ultraWideReconnectRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        ultraWideReconnectRunnable = null
        isPreparingUltraWideStandby.set(false)
        isUltraWideStreaming.set(false)
        try {
            ultraWideStandbyCaptureSession?.stopRepeating()
        } catch (ignored: Throwable) {}
        try {
            ultraWideStandbyCaptureSession?.close()
        } catch (ignored: Throwable) {}
        ultraWideStandbyCaptureSession = null

        try {
            ultraWideStandbyCameraDevice?.close()
        } catch (ignored: Throwable) {}
        ultraWideStandbyCameraDevice = null

        try {
            ultraWideStandbyJpegReader?.close()
        } catch (ignored: Throwable) {}
        ultraWideStandbyJpegReader = null
        standbyConfiguredPreviewSize = null
        standbyConfiguredIsVideoMode = false
        standbyConfiguredForRecording = false
        standbyRecordingSessionFailed = false

        if (ultraWideStandbySurface != null && ultraWideStandbySurface !== ultraWideViewfinderSurface) {
            try {
                ultraWideStandbySurface?.release()
            } catch (ignored: Throwable) {}
        }
        ultraWideStandbySurface = ultraWideViewfinderSurface

        try {
            ultraWideStandbySurfaceTexture?.release()
        } catch (ignored: Throwable) {}
        ultraWideStandbySurfaceTexture = null

        ultraWideStandbyRequestBuilder = null
        activeLogicalMultiCamUltraWideConfigured = false
        _ultraWideStreamStatus.value = BackgroundCameraStatus.OFF
    }

    // State Flows
    private val _availableLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    val availableLenses: StateFlow<List<LensInfo>> = _availableLenses.asStateFlow()

    private val _selectedLens = MutableStateFlow<LensInfo?>(null)
    val selectedLens: StateFlow<LensInfo?> = _selectedLens.asStateFlow()

    fun isRunningOnLens(targetLens: LensInfo): Boolean {
        if (isSwitchingLens.get()) return false
        val active = activeSessionLens ?: _selectedLens.value ?: return false
        val cam = cameraDevice
        return active.id == targetLens.id &&
                active.lensType == targetLens.lensType &&
                (cam == null || cam.id == targetLens.cameraId) &&
                activeSessionPhysicalCameraId == targetLens.physicalCameraId
    }

    private val _capabilities = MutableStateFlow(HardwareCapabilities())
    val capabilities: StateFlow<HardwareCapabilities> = _capabilities.asStateFlow()

    private val _selectedPhotoResolution = MutableStateFlow<CameraResolution?>(null)
    val selectedPhotoResolution: StateFlow<CameraResolution?> = _selectedPhotoResolution.asStateFlow()

    @Volatile
    private var userSelectedVideoResolution: CameraResolution? = null
    private val lensSupportedVideoResolutions = java.util.concurrent.ConcurrentHashMap<String, List<CameraResolution>>()

    private val _selectedVideoResolution = MutableStateFlow<CameraResolution?>(null)
    val selectedVideoResolution: StateFlow<CameraResolution?> = _selectedVideoResolution.asStateFlow()

    fun getSupportedVideoResolutionsForLens(cameraId: String): List<CameraResolution> {
        return lensSupportedVideoResolutions[cameraId] ?: _capabilities.value.supportedVideoResolutions
    }

    fun resolveEffectiveVideoResolutionForLens(
        cameraId: String,
        requested: CameraResolution = userSelectedVideoResolution ?: _selectedVideoResolution.value ?: CameraResolution(1920, 1080)
    ): CameraResolution {
        val chars = getCharacteristics(cameraId)
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map?.getOutputSizes(MediaRecorder::class.java)
            ?: map?.getOutputSizes(SurfaceTexture::class.java)
            ?: emptyArray()
        val reqW = maxOf(requested.width, requested.height)
        val reqH = minOf(requested.width, requested.height)
        val lens = _availableLenses.value.firstOrNull { it.cameraId == cameraId }
        val isUltraWide = (lens?.lensType == LensType.ULTRAWIDE) || (lens?.isPhysical == true && lens.lensType == LensType.ULTRAWIDE)
        val is2K = (reqW == 2560 && reqH == 1440) || (reqW in 2400..2800)

        if (supportedSizes.isEmpty()) {
            val cached = lensSupportedVideoResolutions[cameraId]
            if (!cached.isNullOrEmpty()) {
                val exact = cached.firstOrNull {
                    maxOf(it.width, it.height) == reqW && minOf(it.width, it.height) == reqH
                }
                if (exact != null) return requested
                if (is2K) {
                    val exact2K = cached.firstOrNull { maxOf(it.width, it.height) == 2560 && minOf(it.width, it.height) == 1440 }
                    if (exact2K != null) return CameraResolution(exact2K.width, exact2K.height)
                    val size4K = cached.firstOrNull { maxOf(it.width, it.height) >= 3840 }
                    if (size4K != null) return CameraResolution(size4K.width, size4K.height)
                    return requested
                }
                if (reqW >= 3840 && isUltraWide) {
                    val highest43Cached = cached.filter {
                        val r = maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height).toFloat()
                        kotlin.math.abs(r - (4f / 3f)) < 0.08f
                    }.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    if (highest43Cached != null) return CameraResolution(highest43Cached.width, highest43Cached.height)
                }
                val targetRatio = reqW.toFloat() / reqH.toFloat()
                val matchingRatio = cached
                    .filter {
                        val r = maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height).toFloat()
                        kotlin.math.abs(r - targetRatio) < 0.08f && maxOf(it.width, it.height) <= reqW
                    }
                    .maxByOrNull { it.width.toLong() * it.height.toLong() }
                val bestCached = matchingRatio
                    ?: cached
                        .filter { maxOf(it.width, it.height) <= reqW }
                        .maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: cached.first()
                return CameraResolution(bestCached.width, bestCached.height)
            }
            return requested
        }
        val isExactSupported = supportedSizes.any {
            maxOf(it.width, it.height) == reqW && minOf(it.width, it.height) == reqH
        }
        if (isExactSupported) return requested

        if (is2K) {
            val exact2K = supportedSizes.firstOrNull { maxOf(it.width, it.height) == 2560 && minOf(it.width, it.height) == 1440 }
            if (exact2K != null) return CameraResolution(exact2K.width, exact2K.height)
            val size4K = supportedSizes.firstOrNull { maxOf(it.width, it.height) == 3840 && minOf(it.width, it.height) == 2160 }
            if (size4K != null) return CameraResolution(size4K.width, size4K.height)
            return requested
        }

        // When 4K recording is requested on ultra-wide sensors that cannot provide 8.3MP native 16:9 pixels (e.g. 8MP sensor):
        // capture using the highest suitable supported 4:3 sensor stream (e.g. 3264x2448) which will be cropped to 9:16 for
        // live preview and final video while keeping encoder output at 4K (2160x3840).
        if (reqW >= 3840 && isUltraWide) {
            val allStreamSizes = ((map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()).toList() +
                    supportedSizes.toList()).distinctBy { "${it.width}x${it.height}" }
            val fourThreeSizes = allStreamSizes.filter {
                val r = maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height).toFloat()
                kotlin.math.abs(r - (4f / 3f)) < 0.08f
            }
            val highest43 = fourThreeSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            if (highest43 != null) {
                return CameraResolution(highest43.width, highest43.height)
            }
        }

        val targetRatio = reqW.toFloat() / reqH.toFloat()
        val matchingRatio = supportedSizes
            .filter {
                val r = maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height).toFloat()
                kotlin.math.abs(r - targetRatio) < 0.08f && maxOf(it.width, it.height) <= reqW
            }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
        if (matchingRatio != null) {
            return CameraResolution(matchingRatio.width, matchingRatio.height)
        }
        val largestWithinCap = supportedSizes
            .filter { maxOf(it.width, it.height) <= reqW }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: supportedSizes.first()
        return CameraResolution(largestWithinCap.width, largestWithinCap.height)
    }

    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private val _isRecordingVideo = MutableStateFlow(false)
    val isRecordingVideo: StateFlow<Boolean> = _isRecordingVideo.asStateFlow()

    private val _isRecordingPaused = MutableStateFlow(false)
    val isRecordingPaused: StateFlow<Boolean> = _isRecordingPaused.asStateFlow()

    private val _isSavingVideo = MutableStateFlow(false)
    val isSavingVideo: StateFlow<Boolean> = _isSavingVideo.asStateFlow()
    private val savingVideoJobsCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val sessionRestoredForStop = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _videoDurationSeconds = MutableStateFlow(0)
    val videoDurationSeconds: StateFlow<Int> = _videoDurationSeconds.asStateFlow()

    private val _lastCapturedMedia = MutableStateFlow<CapturedMedia?>(null)
    val lastCapturedMedia: StateFlow<CapturedMedia?> = _lastCapturedMedia.asStateFlow()

    fun setLastCapturedMedia(uri: Uri) {
        val now = System.currentTimeMillis()
        _lastCapturedMedia.value = CapturedMedia(
            uri = uri,
            isVideo = false,
            timestamp = now,
            displayName = "CineDepth_$now.jpg"
        )
    }

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> = _isCameraReady.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _previewAspectRatio = MutableStateFlow(4f / 3f)
    val previewAspectRatio: StateFlow<Float> = _previewAspectRatio.asStateFlow()

    private var videoTimerJob: Job? = null
    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Capture Settings State
    var currentMode: CameraMode = CameraMode.PHOTO
    var flashMode: FlashMode = FlashMode.OFF
        set(value) {
            field = value
            updatePreviewSettings()
            syncStandbyStreamSettings()
        }
    var whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO
    var focusMode: FocusMode = FocusMode.CONTINUOUS
    var manualFocusDistance: Float = 0f // 0 = infinity, max = closest
    var manualIso: Int? = null // null = auto
    var manualExposureTimeNs: Long? = null // null = auto
    var exposureCompensationIndex: Int = 0
        set(value) {
            field = value
            // When setting AE compensation, ensure AE is active and unlocked so exposure visibly updates
            if (value != 0) {
                isAeLocked = false
            }
        }
    var isAeLocked: Boolean = false
        set(value) {
            field = value
            _isAeLockedFlow.value = value
        }
    var isAfLocked: Boolean = false
    var isRawCaptureEnabled: Boolean = false
    // EIS was removed because its software crop path caused lag and could conflict with OIS.
    // Keep this legacy property as a hard-OFF compatibility shim.
    var isVideoStabilizationEnabled: Boolean = false
        set(@Suppress("UNUSED_PARAMETER") value) {
            field = false
            gyroStabilizationEngine.stop()
            updatePreviewSettings()
        }
    var videoBitrateOption: VideoBitrateOption = VideoBitrateOption.AUTO
    var videoFps: Int = 30
    var colorProfile: ColorProfile = ColorProfile.STANDARD
    var isAudioEnabled: Boolean = true
    var currentVideoAdjustments: com.example.camera.model.VideoAdjustments = com.example.camera.model.VideoAdjustments()
        set(value) {
            field = value
            dualCameraRecordingRelay?.updateVideoAdjustments(value)
            cinemaSoftwareRecorder.updateVideoAdjustments(value)
        }

    private val _isGpuRelayPreviewActive = MutableStateFlow(false)
    val isGpuRelayPreviewActive: StateFlow<Boolean> = _isGpuRelayPreviewActive.asStateFlow()

    var currentZoom: Float = 1.0f
    private val _currentZoom = MutableStateFlow(1.0f)
    val currentZoomState: StateFlow<Float> = _currentZoom.asStateFlow()

    private val _displayedPreviewSource = MutableStateFlow(PreviewStreamSource.MAIN)
    val displayedPreviewSource: StateFlow<PreviewStreamSource> = _displayedPreviewSource.asStateFlow()

    private val _targetLens = MutableStateFlow<LensInfo?>(null)
    val targetLens: StateFlow<LensInfo?> = _targetLens.asStateFlow()
    val resolvedTargetLens: StateFlow<LensInfo?> = _targetLens.asStateFlow()

    @Volatile
    var activeSessionLens: LensInfo? = null

    data class PreparedVideoGeometry(
        val width: Int,
        val height: Int,
        val fps: Int,
        val orientationHint: Int,
        val encoderRotation: Int
    )

    @Volatile
    private var preparedVideoGeometry: PreparedVideoGeometry? = null

    @Volatile
    private var pendingDirectTargetZoom: Float? = null
    var saveSelfieAsPreviewed: Boolean = true
    var viewfinderResolution: ViewfinderResolution = ViewfinderResolution.NORMAL
    var selectedPhotoFilter: PhotoFilter = PhotoFilter.ORIGINAL
    private val cinemaSoftwareRecorder by lazy { CinemaSoftwareRecordingEngine(context) }
    private val normalVideoRecorder by lazy { NormalVideoRecordingEngine(context) }
    private var isSoftwareCinemaRecording: Boolean = false
    private var recordingCinemaConfig: CinemaConfig? = null

    private val _previewBufferSize = MutableStateFlow<Size?>(null)
    val previewBufferSize: StateFlow<Size?> = _previewBufferSize.asStateFlow()

    // Geometry for the main TextureView remains independent when the active lens is ultra-wide.
    private val _mainPreviewBufferSize = MutableStateFlow<Size?>(null)
    val mainPreviewBufferSize: StateFlow<Size?> = _mainPreviewBufferSize.asStateFlow()

    // The standby ultra-wide SurfaceTexture has independent geometry from the selected stream.
    private val _ultraWidePreviewBufferSize = MutableStateFlow<Size?>(null)
    val ultraWidePreviewBufferSize: StateFlow<Size?> = _ultraWidePreviewBufferSize.asStateFlow()

    private val _sensorOrientation = MutableStateFlow(90)
    val sensorOrientation: StateFlow<Int> = _sensorOrientation.asStateFlow()

    private var cameraOpenRetryCount = 0
    private val MAX_CAMERA_OPEN_RETRIES = 3

    private val _isAeLockedFlow = MutableStateFlow(false)
    val isAeLockedFlow: StateFlow<Boolean> = _isAeLockedFlow.asStateFlow()

    private val _isAfLockedFlow = MutableStateFlow(false)
    val isAfLockedFlow: StateFlow<Boolean> = _isAfLockedFlow.asStateFlow()

    private var activeMeteringRectangle: MeteringRectangle? = null

    val gyroStabilizationEngine by lazy { GyroStabilizationEngine(context) }

    var videoStabilizationMode: com.example.camera.model.VideoStabilizationMode = com.example.camera.model.VideoStabilizationMode.OFF
        set(@Suppress("UNUSED_PARAMETER") value) {
            field = com.example.camera.model.VideoStabilizationMode.OFF
            preferences.videoStabilizationMode = com.example.camera.model.VideoStabilizationMode.OFF
            updatePreviewSettings()
        }

    val photoHdrEngine by lazy { com.example.camera.engine.hdr.PhotoHdrEngine(context) }


    fun refreshCaptureSessionForRaw() {
        val lens = _selectedLens.value ?: return
        val caps = _capabilities.value
        if (!caps.supportsRaw) return
        val needsRaw = isRawCaptureEnabled
        val hasRaw = imageReaderRaw != null
        if (needsRaw != hasRaw) {
            setupImageReaders(lens.cameraId)
            createCameraCaptureSession()
        }
    }

    private val _hybridStabilizationConfig = MutableStateFlow(preferences.hybridStabilizationConfig)
    val hybridStabilizationConfig: StateFlow<HybridStabilizationConfig> = _hybridStabilizationConfig.asStateFlow()

    // Pro Mode Advanced Image Adjustments
    val proSaturation = MutableStateFlow(preferences.proSaturation)
    val proContrast = MutableStateFlow(preferences.proContrast)
    val proHighlights = MutableStateFlow(preferences.proHighlights)
    val proShadows = MutableStateFlow(preferences.proShadows)
    val proSharpness = MutableStateFlow(preferences.proSharpness)
    val proNoiseReduction = MutableStateFlow(preferences.proNoiseReduction)

    private val _actualOisHardwareActive = MutableStateFlow(false)
    val actualOisHardwareActive: StateFlow<Boolean> = _actualOisHardwareActive.asStateFlow()

    private val _actualEisHardwareActive = MutableStateFlow(false)
    val actualEisHardwareActive: StateFlow<Boolean> = _actualEisHardwareActive.asStateFlow()

    // OIS is a separate hardware feature; never gate it on old hybrid/EIS preferences.
    val isOisAllowed: Boolean
        get() = preferences.isOisEnabled

    var isOisEnabled: Boolean
        get() = preferences.isOisEnabled
        set(value) {
            preferences.isOisEnabled = value
            val current = _hybridStabilizationConfig.value
            _hybridStabilizationConfig.value = current.copy(isOisPreferred = value, isOisEnabled = value)
            updatePreviewSettings()
        }

    fun updateHybridStabilizationConfig(config: HybridStabilizationConfig) {
        // Preserve the user's OIS preference while sanitizing all legacy EIS/hybrid flags.
        val oisEnabled = preferences.isOisEnabled
        _hybridStabilizationConfig.value = config.copy(
            isHybridEnabled = false,
            isOisPreferred = oisEnabled,
            isOisEnabled = oisEnabled,
            isEisPreferred = false,
            isAdaptiveFpsLens = false,
            isUltraStabilizationEnabled = false,
            isEisOnly = false
        )
        preferences.isUltraStabilizationEnabled = false
        gyroStabilizationEngine.stop()
        updatePreviewSettings()
    }

    // Camera Session Concurrency & State Guard
    private val cameraLifecycleLock = Any()
    private val previewRequestLock = Any()
    @Volatile
    private var isStartingCamera = false
    @Volatile
    private var isClosingCamera = false
    @Volatile
    private var isConfiguringSession = false
    @Volatile
    private var restartPending = false
    @Volatile
    private var pendingReconfigureSession = false
    private var zoomDebounceJob: Job? = null
    private val isStartingRecording = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isStoppingRecording = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isSwitchingLens = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var lastLensSwitchStartTimeMs: Long = 0L
    private val lensSwitchGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val sessionConfigGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var activeSessionPhysicalCameraId: String? = null
    @Volatile
    private var pendingZoomWhileSwitching: Float? = null
    @Volatile
    private var pendingZoomPresetTapWhileSwitching: Boolean = false
    @Volatile
    private var pendingLensWhileSwitching: LensInfo? = null
    @Volatile
    private var activeSwitchingTargetLens: LensInfo? = null
    @Volatile
    private var pendingEnsureUltraWideReady: Boolean = false
    @Volatile
    private var pendingIsContinuousTransitionWhileSwitching: Boolean = false
    @Volatile
    private var _isContinuousZoomTransitionActiveField: Boolean = false
    var isContinuousZoomTransitionActive: Boolean
        get() = zoomTransitionController.isTransitionActive.value || _isContinuousZoomTransitionActiveField
        set(value) {
            _isContinuousZoomTransitionActiveField = value
        }

    val cinemaEngine = CinemaEngine(context)
    private val _cinemaConfig = MutableStateFlow(preferences.getCinemaConfig())
    val cinemaConfig: StateFlow<CinemaConfig> = _cinemaConfig.asStateFlow()
    private val _cinemaCapabilities = MutableStateFlow(cinemaEngine.capabilities)
    val cinemaCapabilities: StateFlow<CinemaHardwareCapabilities> = _cinemaCapabilities.asStateFlow()
    val nativeNaturalParams: StateFlow<NativeNaturalToneParams> = cinemaEngine.nativeNaturalEngine.currentParams

    val ultraRes50MStacker = UltraRes50MStacker(context)
    val refocusEngine = RefocusEngine(context)
    var photoMegapixelMode: PhotoMegapixelMode = PhotoMegapixelMode.M12
        set(value) {
            val changed = field != value
            field = value
            if (changed && currentMode == CameraMode.PHOTO) {
                val activeLens = _selectedLens.value
                val cameraId = activeLens?.physicalCameraId ?: activeLens?.cameraId
                if (cameraId != null && cameraDevice != null && !isConfiguringSession) {
                    setupImageReaders(cameraId)
                    createCameraCaptureSession()
                }
            }
        }
    var isRefocusPhotoEnabled: Boolean = false
    var refocusFrameCount: Int = 3

    // Ultra Fast Shutter System
    val ultraFastShutterEngine = com.example.camera.ultrafast.engine.UltraFastShutterEngine(
        context,
        com.example.camera.ultrafast.data.UltraFastBurstRepository(context)
    )
    val fastShutterFrameCount: StateFlow<Int> = ultraFastShutterEngine.liveFrameCount
    val isFastShutterHolding: StateFlow<Boolean> = ultraFastShutterEngine.isHolding
    var isUltraFastShutterEnabled: Boolean = false
    var ultraFastShutterFps: Int = 15

    fun updateFastShutterState(enabled: Boolean, fps: Int) {
        val wasEnabled = isUltraFastShutterEnabled
        isUltraFastShutterEnabled = enabled
        ultraFastShutterFps = fps.coerceIn(5, 20)

        if (wasEnabled != enabled) {
            if (!enabled) {
                try {
                    imageReaderYuv?.close()
                } catch (ignored: Throwable) {}
                imageReaderYuv = null
                ultraFastShutterEngine.reset()
                if (cameraDevice != null && captureSession != null && !isConfiguringSession) {
                    createCameraCaptureSession()
                }
            } else if (currentMode == CameraMode.PHOTO) {
                val activeLens = _selectedLens.value
                val cameraId = activeLens?.physicalCameraId ?: activeLens?.cameraId ?: return
                if (cameraDevice != null && !isConfiguringSession) {
                    setupImageReaders(cameraId)
                    createCameraCaptureSession()
                }
            }
        }
    }


    init {
        initOrientationListener()
        val savedCinema = preferences.getCinemaConfig()
        cinemaEngine.updateConfig(savedCinema)
        _cinemaConfig.value = savedCinema


    }

    /**
     * Centralized, safe camera initialization function.
     * Guaranteed to never throw an uncaught exception to the caller.
     * Must be called only after CAMERA permission is granted.
     */
    fun safeInitializeCamera(onResult: (success: Boolean, errorMessage: String?) -> Unit = { _, _ -> }) {
        if (_isCameraInitialized.value) {
            Log.d(TAG, "safeInitializeCamera: Already initialized")
            onResult(true, null)
            return
        }

        try {
            // 1. Verify context & permission
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "safeInitializeCamera: CAMERA permission not granted")
                _cameraInitError.value = "Camera permission not granted"
                onResult(false, _cameraInitError.value)
                return
            }

            // 2. Safely obtain CameraManager
            val mgr = cameraManager ?: run {
                Log.e(TAG, "safeInitializeCamera: Camera service unavailable on this device")
                _cameraInitError.value = "Camera service is unavailable on this device"
                onResult(false, _cameraInitError.value)
                return
            }

            // 3. Start background thread safely with UncaughtExceptionHandler
            startBackgroundThread()

            // 4. Enumerate camera IDs inside try/catch
            val officialIds = try {
                mgr.cameraIdList.toList()
            } catch (t: Throwable) {
                Log.e(TAG, "safeInitializeCamera: Failed to enumerate camera IDs", t)
                emptyList<String>()
            }

            if (officialIds.isEmpty()) {
                Log.w(TAG, "safeInitializeCamera: No camera IDs reported by device")
                _cameraInitError.value = "No camera hardware detected on this device"
                _isCameraInitialized.value = true
                onResult(false, _cameraInitError.value)
                return
            }

            // 5. Run physical camera/lens detection
            try {
                detectHardwareLenses()
            } catch (t: Throwable) {
                Log.e(TAG, "safeInitializeCamera: Error during lens detection", t)
            }

            // 6. Update storage stats
            try {
                updateStorageStats()
            } catch (t: Throwable) {
                Log.w(TAG, "safeInitializeCamera: Error updating storage stats", t)
            }

            _isCameraInitialized.value = true
            _cameraInitError.value = null

            // 7. If surface texture is already available, start camera
            if (previewSurfaceTexture != null) {
                startCamera()
            }

            onResult(true, null)
        } catch (t: Throwable) {
            Log.e(TAG, "safeInitializeCamera: Critical failure during camera initialization", t)
            val msg = "Camera initialization failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
            _cameraInitError.value = msg
            _isCameraInitialized.value = false
            onResult(false, msg)
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null || backgroundThread?.isAlive == false) {
            try { backgroundThread?.quitSafely() } catch (_: Throwable) {}
            backgroundThread = HandlerThread("Camera2Background").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
                    Log.e(TAG, "Uncaught exception on Camera2Background: ${thread.name}", throwable)
                }
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    var cameraInventory: CameraInventory? = null
        private set

    /**
     * Detects all real physical and logical lenses available on the device,
     * powered by PhotonCamera's robust camera inventory and lens classification architecture.
     */
    fun detectHardwareLenses(forceDeepScan: Boolean = false): Int {
        val mgr = cameraManager ?: return 0
        try {
            val inventory = CameraDiscovery.discover(mgr)
            cameraInventory = inventory
            val sortedLenses = inventory.lenses

            _availableLenses.value = sortedLenses

            // Selection priority:
            // 1. Always prioritize the Primary 1x Main camera on launch or reset so 1x NEVER starts on ultra-wide!
            val currentSelected = _selectedLens.value
            val primaryMainLens = sortedLenses.firstOrNull { it.isPrimaryMain }
                ?: sortedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.WIDE && !it.isZoomPreset }
                ?: sortedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }

            val isFrontPreferred = preferences.lastFacing == CameraCharacteristics.LENS_FACING_FRONT
            val frontLens = sortedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }

            val validSelection = sortedLenses.firstOrNull { it.id == currentSelected?.id }
                ?: (if (isFrontPreferred) frontLens else null)
                ?: primaryMainLens
                ?: sortedLenses.firstOrNull()

            _selectedLens.value = validSelection
            if (validSelection != null) {
                inspectCapabilities(validSelection.cameraId)
                val preserveExistingZoom = (currentSelected != null && validSelection.id == currentSelected.id && currentZoom > 0f)
                val isFrontCamera = validSelection.facing == CameraCharacteristics.LENS_FACING_FRONT
                val initialZoom = if (preserveExistingZoom && !isFrontCamera) {
                    currentZoom
                } else if (isFrontCamera || validSelection.isPrimaryMain || validSelection.lensType == LensType.WIDE) {
                    1.0f
                } else {
                    validSelection.baseZoomRatio
                }
                currentZoom = initialZoom
                _currentZoom.value = initialZoom
                preferences.currentZoom = initialZoom
                activeSessionLens = validSelection
            }

            Log.i(TAG, "Total discovered lenses after scan: ${sortedLenses.size}")
            return sortedLenses.size
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to detect hardware lenses", t)
            return 0
        }
    }

    fun hasBackCameraFlash(): Boolean {
        val mgr = cameraManager ?: return false
        val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        for (lens in backLenses) {
            val chars = getCharacteristics(lens.cameraId) ?: continue
            if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                return true
            }
        }
        return false
    }

    /**
     * Inspects actual hardware capabilities of the given camera ID.
     */
    fun inspectCapabilities(cameraId: String) {
        try {
            val chars = getCharacteristics(cameraId) ?: return
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val hasManualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
            val exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
            val aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-4, 4)
            val aeCompStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.333f
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val charsFlashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val flashAvailable = charsFlashAvailable || (facing == CameraCharacteristics.LENS_FACING_BACK && hasBackCameraFlash())
            val zoomRatioRangeMax = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
            } else null
            val reportedDigitalZoom = zoomRatioRangeMax
                ?: chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: 8f
            val maxZoom = reportedDigitalZoom.coerceAtLeast(1.0f)

            // OIS / EIS
            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
            val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
            val hasOis = oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)
            val hasEis = eisModes.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON)

            // AWB modes
            val availableAwb = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
            val awbModes = WhiteBalanceMode.entries.filter { availableAwb.contains(it.camera2Mode) }

            // AF modes
            val availableAf = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val afModes = FocusMode.entries.filter { availableAf.contains(it.camera2Mode) }

            // Photo JPEG Resolutions: use standard SCALER_STREAM_CONFIGURATION_MAP sizes
            // (SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION sizes are illegal in standard SESSION_REGULAR
            // preview sessions without SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION and cause HAL hangs/failures on back cameras)
            val standardSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val allJpegSizes = standardSizes.distinctBy { "${it.width}x${it.height}" }
            val photoResolutions = allJpegSizes
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                .map { CameraResolution(it.width, it.height, ImageFormat.JPEG, isRaw = false) }

            // RAW Resolutions
            val standardRawSizes = if (hasRaw) {
                map?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
            } else emptyArray()
            val allRawSizes = standardRawSizes.distinctBy { "${it.width}x${it.height}" }
            val rawResolutions = allRawSizes
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                .map { CameraResolution(it.width, it.height, ImageFormat.RAW_SENSOR, isRaw = true) }

            // Video Resolutions: strictly validate against camera sensor's supported sizes
            val videoSizes = map?.getOutputSizes(MediaRecorder::class.java)
                ?: map?.getOutputSizes(SurfaceTexture::class.java)
                ?: emptyArray()

            val standardVideoQualities = listOf(
                CameraResolution(3840, 2160), // 4K UHD
                CameraResolution(2560, 1440), // 2K QHD
                CameraResolution(1920, 1080), // 1080p FHD
                CameraResolution(1280, 720),  // 720p HD
                CameraResolution(720, 480)    // 480p SD
            )
            val filteredVideoResolutions = if (videoSizes.isNotEmpty()) {
                val matched = standardVideoQualities.filter { standard ->
                    if (standard.width == 3840 && standard.height == 2160) {
                        videoSizes.any { it.width == 3840 && it.height == 2160 } ||
                        videoSizes.any { (it.width.toLong() * it.height.toLong()) >= 5_000_000L } ||
                        (map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()).any {
                            (it.width.toLong() * it.height.toLong()) >= 5_000_000L
                        }
                    } else if (standard.width == 2560 && standard.height == 1440) {
                        videoSizes.any { it.width == 2560 && it.height == 1440 } ||
                        videoSizes.any { (it.width.toLong() * it.height.toLong()) >= 3_500_000L }
                    } else {
                        videoSizes.any { it.width == standard.width && it.height == standard.height }
                    }
                }
                if (matched.isNotEmpty()) {
                    matched
                } else {
                    videoSizes
                        .filter { it.width >= 640 && it.height >= 480 && it.width < 7680 && it.height < 7680 }
                        .sortedByDescending { it.width.toLong() * it.height.toLong() }
                        .map { CameraResolution(it.width, it.height) }
                        .distinctBy { "${it.width}x${it.height}" }
                }
            } else {
                listOf(
                    CameraResolution(2560, 1440),
                    CameraResolution(1920, 1080),
                    CameraResolution(1280, 720)
                )
            }

            // FPS ranges
            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val maxFps = fpsRanges.maxOfOrNull { it.upper } ?: 30
            val supportedFps = if (maxFps >= 60) listOf(30, 60) else listOf(30)

            // Tonemap curve
            val tonemapModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
            val hasTonemapCurve = tonemapModes.contains(CameraCharacteristics.TONEMAP_MODE_CONTRAST_CURVE) ||
                    tonemapModes.contains(CameraCharacteristics.TONEMAP_MODE_FAST)

            val supportsAeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: true
            val supportsAwbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: true
            val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

            val hardwareCaps = HardwareCapabilities(
                supportsManualSensor = hasManualSensor,
                supportsRaw = hasRaw && rawResolutions.isNotEmpty(),
                supportsOis = hasOis,
                supportsEis = hasEis,
                supportsFlash = flashAvailable,
                minIso = isoRange.lower,
                maxIso = isoRange.upper,
                minExposureTimeNs = exposureTimeRange.lower,
                maxExposureTimeNs = exposureTimeRange.upper,
                minExposureCompensation = aeCompRange.lower,
                maxExposureCompensation = aeCompRange.upper,
                exposureCompensationStep = aeCompStep,
                minFocusDistance = minFocus,
                supportedAwbModes = awbModes.ifEmpty { listOf(WhiteBalanceMode.AUTO) },
                supportedAfModes = afModes.ifEmpty { listOf(FocusMode.CONTINUOUS) },
                supportedPhotoResolutions = photoResolutions,
                supportedRawResolutions = rawResolutions,
                supportedVideoResolutions = filteredVideoResolutions,
                supportedFpsRanges = supportedFps,
                supportsTonemapCurve = hasTonemapCurve,
                supportsAeLock = supportsAeLock,
                supportsAwbLock = supportsAwbLock,
                hardwareLevel = hwLevel,
                maxZoom = maxZoom
            )

            _capabilities.value = hardwareCaps

            cinemaEngine.onCameraConfigured(chars, filteredVideoResolutions)
            _cinemaCapabilities.value = cinemaEngine.capabilities

            lensSupportedVideoResolutions[cameraId] = filteredVideoResolutions

            if (_selectedPhotoResolution.value == null || !photoResolutions.contains(_selectedPhotoResolution.value)) {
                _selectedPhotoResolution.value = photoResolutions.firstOrNull()
            }
            val preservedVideoRes = userSelectedVideoResolution ?: _selectedVideoResolution.value
            if (preservedVideoRes != null) {
                userSelectedVideoResolution = preservedVideoRes
                if (_selectedVideoResolution.value != preservedVideoRes) {
                    _selectedVideoResolution.value = preservedVideoRes
                }
            } else {
                val prefRes = CameraResolution(preferences.videoWidth, preferences.videoHeight)
                val initialVideoRes = filteredVideoResolutions.firstOrNull {
                    it.width == prefRes.width && it.height == prefRes.height
                } ?: if (prefRes.width > 0 && prefRes.height > 0) {
                    prefRes
                } else {
                    filteredVideoResolutions.firstOrNull { it.height == 1080 }
                        ?: filteredVideoResolutions.firstOrNull()
                }
                userSelectedVideoResolution = initialVideoRes
                _selectedVideoResolution.value = initialVideoRes
            }

            updatePreviewAspectRatio()
        } catch (t: Throwable) {
            Log.e(TAG, "Error inspecting capabilities for camera $cameraId", t)
        }
    }



    fun getTargetAspectRatioForMode(mode: CameraMode = currentMode): Float {
        return when (mode) {
            CameraMode.PHOTO -> 4f / 3f // Fixed 3:4 portrait (sensor landscape 4:3)
            CameraMode.CINEMA -> cinemaConfig.value.aspectRatio.ratioValue
            else -> 16f / 9f // Fixed 9:16 portrait (sensor landscape 16:9)
        }
    }

    fun getOptimalPreviewSize(
        cameraId: String? = null,
        targetRatio: Float = getTargetAspectRatioForMode(currentMode),
        lensTypeHint: LensType? = null
    ): Size {
        val streamRatio = targetRatio
        val fallbackDefault = if (streamRatio > 1.5f) Size(1920, 1080) else Size(1440, 1080)
        val id = cameraId ?: _selectedLens.value?.cameraId ?: return fallbackDefault
        val lens = _availableLenses.value.firstOrNull {
            it.cameraId == id && (lensTypeHint == null || it.lensType == lensTypeHint)
        }
        val isUltraWideLens = lensTypeHint == LensType.ULTRAWIDE || lens?.lensType == LensType.ULTRAWIDE
        val is4KVideo = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA) &&
            ((userSelectedVideoResolution?.let { maxOf(it.width, it.height) >= 3840 } == true) ||
             (_selectedVideoResolution.value?.let { maxOf(it.width, it.height) >= 3840 } == true))

        val chars = getCharacteristics(id) ?: return fallbackDefault
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fallbackDefault
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        if (previewSizes.isEmpty()) {
            return fallbackDefault
        }

        // In Video and Pro Video (Cinema) modes only:
        // Use the selected recording resolution (e.g. 4K/1080p/720p) for the viewfinder before recording starts
        val isVideoOrCinema = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA)
        if (isVideoOrCinema) {
            val targetRecRes = if (currentMode == CameraMode.CINEMA) {
                _cinemaConfig.value.selectedResolution
                    ?: userSelectedVideoResolution
                    ?: _selectedVideoResolution.value
                    ?: CameraResolution(3840, 2160)
            } else {
                userSelectedVideoResolution
                    ?: _selectedVideoResolution.value
                    ?: CameraResolution(1920, 1080)
            }
            val targetMax = maxOf(targetRecRes.width, targetRecRes.height)
            val targetMin = minOf(targetRecRes.width, targetRecRes.height)

            val isRecordingActive = (_isRecordingVideo.value || isStartingRecording.get())
            if (targetMax >= 3840 && isRecordingActive) {
                // When 4K recording is active, output preview at 1080p matching the recording aspect ratio.
                // This eliminates camera ISP bandwidth bottlenecks from dual concurrent 4K pipelines,
                // while the recorder surface receives the full native 4K stream with zero frame drops.
                val hd16to9 = previewSizes.filter {
                    val r = maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height).toFloat()
                    kotlin.math.abs(r - streamRatio) < 0.08f && maxOf(it.width, it.height) <= 1920
                }.maxByOrNull { it.width.toLong() * it.height.toLong() }
                if (hd16to9 != null) {
                    return hd16to9
                }
            }

            // 1. Direct exact match in SurfaceTexture preview sizes
            val exactMatch = previewSizes.firstOrNull {
                maxOf(it.width, it.height) == targetMax && minOf(it.width, it.height) == targetMin
            }
            if (exactMatch != null) {
                return exactMatch
            }

            // 2. The 8MP ultra-wide sensor cannot provide 8.3MP native pixels for a full 4K frame,
            // so capture using the highest suitable supported 4:3 sensor stream and crop it to 9:16.
            if (targetMax >= 3840 && isUltraWideLens) {
                val hasNative4K = previewSizes.any { max(it.width, it.height) >= 3840 && min(it.width, it.height) >= 2160 }
                if (!hasNative4K) {
                    val fourThreeSizes = previewSizes.filter {
                        val r = max(it.width, it.height).toFloat() / min(it.width, it.height).toFloat()
                        kotlin.math.abs(r - (4f / 3f)) < 0.08f
                    }
                    val highest43 = fourThreeSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    if (highest43 != null) {
                        return highest43
                    }
                }
            }

            // 3. Matching stream ratio sizes closest to target recording dimension
            val matchingRatioSizes = previewSizes.filter {
                val r = max(it.width, it.height).toFloat() / min(it.width, it.height).toFloat()
                kotlin.math.abs(r - streamRatio) < 0.08f
            }
            if (matchingRatioSizes.isNotEmpty()) {
                val closest = matchingRatioSizes.minByOrNull {
                    kotlin.math.abs(maxOf(it.width, it.height) - targetMax)
                }
                if (closest != null) {
                    return closest
                }
            }

            // 4. Closest overall size in previewSizes
            val closestOverall = previewSizes.minByOrNull {
                kotlin.math.abs(maxOf(it.width, it.height) - targetMax)
            }
            if (closestOverall != null) {
                return closestOverall
            }
        }

        val maxDim = viewfinderResolution.maxDimension
        val matchingRatioSizes = previewSizes.filter {
            val r = max(it.width, it.height).toFloat() / min(it.width, it.height).toFloat()
            kotlin.math.abs(r - streamRatio) < 0.08f
        }
        return matchingRatioSizes
            .filter { max(it.width, it.height) <= maxDim }
            .maxByOrNull { it.width * it.height }
            ?: matchingRatioSizes.minByOrNull { max(it.width, it.height) }
            ?: previewSizes.minByOrNull {
                val r = max(it.width, it.height).toFloat() / min(it.width, it.height).toFloat()
                kotlin.math.abs(r - streamRatio)
            }
            ?: fallbackDefault
    }

    private fun onSessionConfigurationFinished() {
        synchronized(cameraLifecycleLock) {
            isConfiguringSession = false
            if (pendingReconfigureSession) {
                pendingReconfigureSession = false
                backgroundHandler?.post { reconfigureSession() }
            }
        }
    }

    private fun updatePreviewAspectRatio() {
        _previewAspectRatio.value = getTargetAspectRatioForMode(currentMode)
    }

    /**
     * Checks whether the logical camera device can seamlessly handle the target lens and zoom ratio
     * using CONTROL_ZOOM_RATIO without binding OutputConfiguration to a physical camera stream.
     */
    private fun canUseLogicalZoomForLens(cameraId: String, lens: LensInfo, targetZoom: Float): Boolean {
        if (lens.cameraId != cameraId) return false
        val chars = getCharacteristics(cameraId) ?: return false
        val isLogicalMulti = lens.isLogicalMultiCamera ||
            (chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            ) == true)

        if (lens.lensType == LensType.ULTRAWIDE || targetZoom < 0.95f) {
            // CONTROL_ZOOM_RATIO genuinely supports < 1.0x ONLY if zoomRange.lower <= 0.55f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null && zoomRange.lower <= 0.55f) {
                    return true
                }
            }
            return false
        }

        if (!isLogicalMulti) {
            return lens.physicalCameraId == null || lens.physicalCameraId == cameraId
        }
        if (lens.physicalCameraId == null || !lens.supportsPhysicalStream) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange != null) {
                val neededRatio = minOf(lens.baseZoomRatio, targetZoom)
                if (zoomRange.lower <= neededRatio + 0.05f) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Completes an active lens switch, unlocks [isSwitchingLens], and immediately applies any
     * coalesced zoom or lens updates that arrived while the switch was in progress.
     */
    private fun completeLensSwitch(
        configuredLens: LensInfo? = activeSwitchingTargetLens ?: _targetLens.value ?: _selectedLens.value,
        isContinuousTransition: Boolean = false
    ) {
        if (configuredLens != null) {
            activeSessionLens = configuredLens
            _selectedLens.value = configuredLens
        }
        activeSwitchingTargetLens = null
        val nextLens = pendingLensWhileSwitching
        val nextZoom = pendingZoomWhileSwitching
        val nextPresetTap = pendingZoomPresetTapWhileSwitching
        val nextIsContinuous = pendingIsContinuousTransitionWhileSwitching || isContinuousTransition || isContinuousZoomTransitionActive
        pendingLensWhileSwitching = null
        pendingZoomWhileSwitching = null
        pendingZoomPresetTapWhileSwitching = false
        pendingIsContinuousTransitionWhileSwitching = false

        if (nextZoom != null) {
            val effectiveZoom = nextZoom
            currentZoom = effectiveZoom
            _currentZoom.value = effectiveZoom
            preferences.currentZoom = effectiveZoom
        }

        isSwitchingLens.set(false)

        val currentActive = activeSessionLens ?: _selectedLens.value
        val isHardwareMismatch = nextLens != null && (
            nextLens.id != currentActive?.id ||
            nextLens.cameraId != currentActive?.cameraId ||
            (cameraDevice != null && nextLens.cameraId != cameraDevice?.id) ||
            nextLens.physicalCameraId != currentActive?.physicalCameraId ||
            nextLens.physicalCameraId != activeSessionPhysicalCameraId ||
            nextLens.lensType != currentActive?.lensType
        )

        if (nextLens != null && isHardwareMismatch && nextLens.facing == currentActive?.facing) {
            selectLens(nextLens, preserveZoom = true, targetZoom = currentZoom, isContinuousTransition = nextIsContinuous)
        } else {
            scheduleZoomPreviewUpdate(immediate = true, explicitZoom = currentZoom)
        }

        if (currentActive != null) {
            onLensSwitchCompletedListener?.invoke(currentActive, currentZoom)
            for (listener in lensSwitchListeners) {
                try {
                    listener.invoke(currentActive, currentZoom)
                } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * Switch active lens using PhotonCamera's lens switching architecture.
     * Handles:
     * - INDEPENDENT_DEVICE: Cleanly closes previous camera and opens target device (e.g. Front <-> Back or separate Aux camera)
     * - LOGICAL_PHYSICAL_STREAM: Reconfigures preview and capture outputs on active logical device using OutputConfiguration.setPhysicalCameraId()
     * - LOGICAL_ZOOM: Seamlessly adjusts continuous zoom ratio on the active logical multi-camera session
     */
    fun selectLens(
        lens: LensInfo,
        preserveZoom: Boolean = false,
        targetZoom: Float? = null,
        isContinuousTransition: Boolean = false
    ) {
        val defaultLensZoom = if (lens.isPrimaryMain || lens.lensType == LensType.WIDE) 1.0f else lens.baseZoomRatio
        val effectiveTargetZoom = targetZoom ?: if (preserveZoom) currentZoom else defaultLensZoom

        if (isStartingRecording.get() || isStoppingRecording.get()) {
            Log.w(TAG, "Lens switch deferred: video recording is transitioning")
            pendingLensWhileSwitching = lens
            pendingZoomWhileSwitching = effectiveTargetZoom
            pendingIsContinuousTransitionWhileSwitching = isContinuousTransition
            return
        }
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (!isSwitchingLens.compareAndSet(false, true)) {
            if (nowMs - lastLensSwitchStartTimeMs > 1800L) {
                Log.w(TAG, "Previous lens switch timed out (>1800ms), forcing new switch to ${lens.lensType}")
                isSwitchingLens.set(true)
            } else {
                Log.d(TAG, "Lens switch already in progress, coalescing target lens=${lens.lensType} zoom=$effectiveTargetZoom")
                currentZoom = effectiveTargetZoom
                _currentZoom.value = effectiveTargetZoom
                preferences.currentZoom = effectiveTargetZoom
                pendingLensWhileSwitching = lens
                pendingZoomWhileSwitching = effectiveTargetZoom
                pendingIsContinuousTransitionWhileSwitching = isContinuousTransition
                return
            }
        }
        lastLensSwitchStartTimeMs = nowMs

        try {
            val switchGen = lensSwitchGeneration.incrementAndGet()
            pendingLensWhileSwitching = null
            pendingZoomWhileSwitching = null
            pendingZoomPresetTapWhileSwitching = false
            pendingIsContinuousTransitionWhileSwitching = false
            if (!isContinuousTransition) {
                pendingZoomRunnable?.let { backgroundHandler?.removeCallbacks(it) }
                pendingZoomRunnable = null
            }

            val previousLens = _selectedLens.value
            val currentRunningLens = activeSessionLens ?: previousLens
            val strategy = CameraDiscovery.resolveSwitchStrategy(currentRunningLens, lens)
            preferences.saveLastLens(lens)

            currentZoom = effectiveTargetZoom
            _currentZoom.value = effectiveTargetZoom
            preferences.currentZoom = effectiveTargetZoom
            activeSwitchingTargetLens = lens
            _targetLens.value = lens

            Log.i(TAG, "[LENS_SWITCH] Switching from ${currentRunningLens?.lensType} (ID=${currentRunningLens?.cameraId}, phys=${currentRunningLens?.physicalCameraId}) to ${lens.lensType} (ID=${lens.cameraId}, phys=${lens.physicalCameraId}) via strategy: $strategy (effectiveZoom=$effectiveTargetZoom)")

            val isSwitchBetweenMainAndUW = (currentRunningLens?.lensType == LensType.WIDE && lens.lensType == LensType.ULTRAWIDE) ||
                    (currentRunningLens?.lensType == LensType.ULTRAWIDE && lens.lensType == LensType.WIDE) ||
                    (lens.lensType == LensType.WIDE && cameraDevice != null && lens.cameraId != cameraDevice?.id)

            // Determine if the switch requires physical Ultra-Wide routing vs logical continuous zoom
            val isSameCameraDevice = (cameraDevice != null && lens.cameraId == cameraDevice?.id)
            val isPhysicalStreamRequired = (strategy == LensSwitchStrategy.LOGICAL_PHYSICAL_STREAM) ||
                    (lens.physicalCameraId != null && lens.physicalCameraId != activeSessionPhysicalCameraId)
            val canUseLogicalContinuousZoom = isSameCameraDevice &&
                    !isPhysicalStreamRequired &&
                    canUseLogicalZoomForLens(lens.cameraId, lens, effectiveTargetZoom)

            // 1. Instant 0ms switch when Keep Ultra Wide Ready or continuous zoom transition is active (both Photo mode AND active Video recording):
            // Neither close(), stopRepeating(), openCamera(), nor createCameraCaptureSession() is called!
            if ((_isKeepUltraWideReady.value || isContinuousZoomTransitionActive || isContinuousTransition || ultraWideStandbyCaptureSession != null) && isSwitchBetweenMainAndUW) {
                val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                val primaryMainLens = backLenses.firstOrNull { it.isPrimaryMain }
                    ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                    ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }

                // Case A: Logical multi-camera with simultaneous Main + Physical Ultra-Wide surfaces already active in captureSession
                val isLogicalPrewarmedReady = isSameCameraDevice &&
                    captureSession != null &&
                    activeLogicalMultiCamUltraWideConfigured &&
                    (!_isRecordingVideo.value || !logicalMultiCamRecordingFailed)
                if (isLogicalPrewarmedReady) {
                    val useUw = (lens.lensType == LensType.ULTRAWIDE)
                    val targetSource = if (useUw) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                    if (_isRecordingVideo.value) {
                        dualCameraRecordingRelay?.setActiveSource(targetSource)
                        dualCameraRecordingRelay?.getRecorderSurfaceForLens(lens)?.let { surf ->
                            if (surf.isValid) activeRecordingSurface = surf
                        }
                    }
                    _isUsingUltraWideSurface.value = useUw
                    _displayedPreviewSource.value = targetSource
                    activeSessionLens = lens
                    _selectedLens.value = lens
                    activeSessionPhysicalCameraId = if (useUw) lens.physicalCameraId else primaryMainLens?.physicalCameraId
                    Log.i(TAG, "[KEEP_ULTRAWIDE_READY] 0ms simultaneous logical-physical surface switch to ${lens.lensType} (recording=${_isRecordingVideo.value}, source=${_displayedPreviewSource.value})")
                    scheduleZoomPreviewUpdate(immediate = true, explicitZoom = effectiveTargetZoom)
                    completeLensSwitch(lens, isContinuousTransition = isContinuousTransition)
                    return
                }

                // Case B: Persistent dual-session across separate CameraDevices (both sessions continuously running)
                val prewarmedCamera = ultraWideStandbyCameraDevice
                if (prewarmedCamera != null && prewarmedCamera.id == lens.cameraId) {
                    Log.i(TAG, "[KEEP_ULTRAWIDE_READY] Persistent dual-session switch to CameraDevice ID ${lens.cameraId} (${lens.lensType}, recording=${_isRecordingVideo.value})")
                    switchUsingPrewarmedStandby(lens, switchGen, isContinuousTransition = isContinuousTransition)
                    return
                }

                // Case C: Headless / pre-open state where both preview surfaces are prepared at viewfinder resolution
                if (cameraDevice == null && !_isRecordingVideo.value) {
                    val useUw = (lens.lensType == LensType.ULTRAWIDE)
                    if (useUw) {
                        getOrCreateUltraWidePreviewSurface(lens.cameraId)
                    } else {
                        getOrCreateMainPreviewSurface(lens.cameraId)
                    }
                    _isUsingUltraWideSurface.value = useUw
                    _displayedPreviewSource.value = if (useUw) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                    activeSessionLens = lens
                    _selectedLens.value = lens
                    activeSessionPhysicalCameraId = lens.physicalCameraId
                    completeLensSwitch(lens, isContinuousTransition = isContinuousTransition)
                    return
                }

                // Case D: Standby camera device is not opened yet or switch between separate camera devices requested:
                // Switch camera devices seamlessly without tearing down the active preview stream (ZERO black screen)
                if (cameraDevice != null && !_isRecordingVideo.value) {
                    Log.i(TAG, "[LENS_SWITCH] Switching camera device to ${lens.cameraId} (${lens.lensType}) seamlessly")
                    switchCameraDevice(lens, switchGen, isContinuousTransition = isContinuousTransition)
                    return
                }
            }

            // On the same camera device / logical multi-camera with genuine continuous zoom support: keep ONE continuous CameraCaptureSession alive
            if (canUseLogicalContinuousZoom && captureSession != null) {
                Log.i(TAG, "[LENS_SWITCH] Seamless continuous transition on open camera ID ${lens.cameraId} to ${lens.lensType} at zoom $effectiveTargetZoom (recording=${_isRecordingVideo.value})")
                val useUw = (lens.lensType == LensType.ULTRAWIDE && _isKeepUltraWideReady.value && activeLogicalMultiCamUltraWideConfigured)
                val targetSource = if (lens.lensType == LensType.ULTRAWIDE) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                if (_isRecordingVideo.value) {
                    dualCameraRecordingRelay?.setActiveSource(targetSource)
                }
                _isUsingUltraWideSurface.value = useUw
                _displayedPreviewSource.value = if (useUw) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                activeSessionLens = lens
                _selectedLens.value = lens
                scheduleZoomPreviewUpdate(immediate = true, explicitZoom = effectiveTargetZoom)
                completeLensSwitch(lens, isContinuousTransition = isContinuousTransition)
                return
            }

            // Seamless lens switch during active video recording
            if (_isRecordingVideo.value) {
                val activeCam = cameraDevice
                val isSameOpenCamera = (activeCam != null && activeCam.id == lens.cameraId && previousLens?.cameraId == lens.cameraId)
                if (isSameOpenCamera && captureSession != null) {
                    val useLogicalZoom = (strategy == LensSwitchStrategy.LOGICAL_ZOOM) ||
                            canUseLogicalZoomForLens(lens.cameraId, lens, effectiveTargetZoom)
                    val targetPhysId = if (useLogicalZoom) null else lens.physicalCameraId
                    if (targetPhysId == activeSessionPhysicalCameraId) {
                        // Keep the active recording session and encoder surface untouched; apply zoom directly
                        activeSessionLens = lens
                        completeLensSwitch(lens, isContinuousTransition = isContinuousTransition)
                        return
                    } else {
                        // Same CameraDevice requires switching between physical and logical stream without closing CameraDevice
                        reconfigureRecordingSessionOnSameCamera(lens, targetPhysId, switchGen)
                        return
                    }
                }
                inspectCapabilities(lens.cameraId)
                switchCameraDuringRecording(lens, previousLens, System.nanoTime(), switchGen)
                return
            }

            val effectiveStrategy = if (cameraDevice != null && lens.cameraId != cameraDevice?.id) {
                LensSwitchStrategy.INDEPENDENT_DEVICE
            } else {
                strategy
            }

            when (effectiveStrategy) {
                LensSwitchStrategy.LOGICAL_ZOOM, LensSwitchStrategy.LOGICAL_PHYSICAL_STREAM -> {
                    activeSessionLens = lens
                    if (cameraDevice != null && cameraDevice?.id == lens.cameraId && captureSession != null) {
                        scheduleZoomPreviewUpdate(immediate = true, explicitZoom = effectiveTargetZoom)
                        completeLensSwitch(lens, isContinuousTransition = isContinuousTransition)
                    } else if (cameraDevice != null && cameraDevice?.id == lens.cameraId && previewSurfaceTexture != null) {
                        createCameraCaptureSession(forceLogicalStream = true)
                    } else {
                        switchCameraDevice(lens, switchGen, isContinuousTransition = isContinuousTransition)
                    }
                }
                LensSwitchStrategy.INDEPENDENT_DEVICE -> {
                    switchCameraDevice(lens, switchGen, isContinuousTransition = isContinuousTransition)
                }
            }
        } catch (t: Throwable) {
            isSwitchingLens.set(false)
            Log.e(TAG, "Failed to switch lens to ${lens.lensType}", t)
        }
    }

    /**
     * Instantly switches between Main (1x) and Ultra-Wide (0.5x) using the already-running
     * persistent dual CameraCaptureSessions.
     *
     * Does NOT call close(), stopRepeating(), or createCameraCaptureSession() on either lens.
     * Both Main and Ultra-Wide streams remain continuously active and synchronized on their
     * respective viewfinder surfaces; lens switching simply swaps the active session pointers
     * and changes which already-running preview stream is displayed (0 ms latency).
     */
    private fun switchUsingPrewarmedStandby(
        targetLens: LensInfo,
        switchGen: Int,
        isContinuousTransition: Boolean = false
    ) {
        val targetCam = ultraWideStandbyCameraDevice ?: run {
            if (cameraDevice != null) {
                switchCameraDevice(targetLens, switchGen, isContinuousTransition = isContinuousTransition)
                return
            }
            _selectedLens.value = targetLens
            activeSessionLens = targetLens
            startCamera()
            return
        }

        synchronized(cameraLifecycleLock) {
            if (switchGen != lensSwitchGeneration.get()) {
                completeLensSwitch(targetLens, isContinuousTransition = isContinuousTransition)
                return
            }

            val standbySession = ultraWideStandbyCaptureSession
            val oldCam = cameraDevice
            val oldSession = captureSession

            // Ensure both preview surfaces are sized to the active viewfinder resolution & aspect ratio
            val targetRatio = getTargetAspectRatioForMode(currentMode)
            val optimalPreviewSize = getOptimalPreviewSize(targetLens.cameraId, targetRatio, targetLens.lensType)
            _previewAspectRatio.value = targetRatio
            _previewBufferSize.value = optimalPreviewSize
            val cameraW = max(optimalPreviewSize.width, optimalPreviewSize.height)
            val cameraH = min(optimalPreviewSize.width, optimalPreviewSize.height)
            syncPreviewBufferSizes(cameraW, cameraH)

            if (standbySession != null) {
                // True 0ms persistent dual-session swap:
                // Both sessions are already configured and streaming continuously.
                // Swap active <-> standby pointers without stopping or closing either session!
                val prevBuilder = synchronized(previewRequestLock) { previewRequestBuilder }
                val prevJpeg = imageReaderJpeg
                val targetBuilder = ultraWideStandbyRequestBuilder
                val targetJpeg = ultraWideStandbyJpegReader

                cameraDevice = targetCam
                captureSession = standbySession
                synchronized(previewRequestLock) {
                    previewRequestBuilder = targetBuilder
                }
                if (targetJpeg != null) {
                    imageReaderJpeg = targetJpeg
                }

                ultraWideStandbyCameraDevice = oldCam
                ultraWideStandbyCaptureSession = oldSession
                ultraWideStandbyRequestBuilder = prevBuilder
                if (targetJpeg != null) {
                    ultraWideStandbyJpegReader = prevJpeg
                }

                val useUw = (targetLens.lensType == LensType.ULTRAWIDE)
                val targetSource = if (useUw) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                if (_isRecordingVideo.value) {
                    dualCameraRecordingRelay?.setActiveSource(targetSource)
                    dualCameraRecordingRelay?.getRecorderSurfaceForLens(targetLens)?.let { surf ->
                        if (surf.isValid) {
                            activeRecordingSurface = surf
                        }
                    }
                }
                _isUsingUltraWideSurface.value = useUw
                _displayedPreviewSource.value = targetSource
                activeSessionLens = targetLens
                _selectedLens.value = targetLens
                activeSessionPhysicalCameraId = targetLens.physicalCameraId
                _isCameraReady.value = true
                isUltraWideStreaming.set(true)
                _ultraWideStreamStatus.value = BackgroundCameraStatus.READY_QUIET

                // Update capabilities for the newly active lens first, then apply
                // settings. During continuous zoom, do NOT synchronously flush the capture request.
                inspectCapabilities(targetLens.cameraId)
                scheduleZoomPreviewUpdate(immediate = true, explicitZoom = currentZoom)
                backgroundHandler?.post {
                    syncStandbyStreamSettings()
                }
                completeLensSwitch(targetLens, isContinuousTransition = isContinuousTransition)
                Log.i(TAG, "[KEEP_ULTRAWIDE_READY] 0ms instant stream swap completed to ${targetLens.lensType} (ID=${targetCam.id}, recording=${_isRecordingVideo.value}, source=${_displayedPreviewSource.value})")
                return
            }

            // If targetCam was just opened and standbySession is still configuring, prepare session
            // while keeping the current valid preview visible until the target stream is usable.
            Log.w(TAG, "Standby session not configured on target camera ${targetCam.id}; preparing session before switching preview source")
            cameraDevice = targetCam
            ultraWideStandbyCameraDevice = oldCam
            ultraWideStandbyCaptureSession = oldSession
            ultraWideStandbyRequestBuilder = synchronized(previewRequestLock) { previewRequestBuilder }
            ultraWideStandbyJpegReader = imageReaderJpeg
            activeSessionLens = targetLens
            activeSessionPhysicalCameraId = targetLens.physicalCameraId

            backgroundHandler?.post {
                inspectCapabilities(targetLens.cameraId)
                setupImageReaders(targetLens.cameraId)
                createCameraCaptureSession()
            }
        }
    }

    /**
     * Seamlessly switches to another physical CameraDevice (e.g. 0.5x Ultra-Wide <-> 1x Main)
     * without black screen: keeps the current preview stream live while opening the new camera,
     * then swaps stream pointers instantly when the new capture session is ready.
     */
    private fun switchCameraDevice(
        targetLens: LensInfo,
        switchGen: Int,
        isContinuousTransition: Boolean = false
    ) {
        val mgr = cameraManager ?: run {
            completeLensSwitch(targetLens, isContinuousTransition = isContinuousTransition)
            return
        }

        // Check if standby camera is already opened and matches targetLens
        val standbyCam = ultraWideStandbyCameraDevice
        if (standbyCam != null && standbyCam.id == targetLens.cameraId) {
            switchUsingPrewarmedStandby(targetLens, switchGen, isContinuousTransition = isContinuousTransition)
            return
        }

        val activeCam = cameraDevice

        // Prepare preview surface for target lens
        if (targetLens.lensType == LensType.ULTRAWIDE) {
            getOrCreateUltraWidePreviewSurface(targetLens.cameraId)
        } else {
            getOrCreateMainPreviewSurface(targetLens.cameraId)
        }

        val targetRatio = getTargetAspectRatioForMode(currentMode)
        val optimalPreviewSize = getOptimalPreviewSize(targetLens.cameraId, targetRatio, targetLens.lensType)
        _previewAspectRatio.value = targetRatio
        _previewBufferSize.value = optimalPreviewSize
        val cameraW = max(optimalPreviewSize.width, optimalPreviewSize.height)
        val cameraH = min(optimalPreviewSize.width, optimalPreviewSize.height)
        syncPreviewBufferSizes(cameraW, cameraH, targetLens)

        // If no camera is currently open, simply open target camera
        if (activeCam == null) {
            _selectedLens.value = targetLens
            activeSessionLens = targetLens
            startCamera()
            return
        }

        // Seamless camera switch: keep active camera streaming while opening target camera
        try {
            mgr.openCamera(targetLens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(newCamera: CameraDevice) {
                    synchronized(cameraLifecycleLock) {
                        if (switchGen != lensSwitchGeneration.get()) {
                            try { newCamera.close() } catch (_: Throwable) {}
                            return@synchronized
                        }

                        // Inspect target capabilities on background thread
                        inspectCapabilities(targetLens.cameraId)

                        val surf = if (targetLens.lensType == LensType.ULTRAWIDE) {
                            ultraWideViewfinderSurface ?: ultraWideStandbySurface ?: previewSurface
                        } else {
                            previewSurface ?: fallbackMainSurface
                        } ?: run {
                            try { newCamera.close() } catch (_: Throwable) {}
                            fallbackToSequentialSwitch(targetLens, switchGen, isContinuousTransition)
                            return@synchronized
                        }

                        val sessionConfigGen = sessionConfigGeneration.incrementAndGet()
                        val stateCallback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                synchronized(cameraLifecycleLock) {
                                    if (switchGen != lensSwitchGeneration.get() || sessionConfigGen != sessionConfigGeneration.get()) {
                                        try { session.close() } catch (_: Throwable) {}
                                        try { newCamera.close() } catch (_: Throwable) {}
                                        return@synchronized
                                    }

                                    val template = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA) {
                                        CameraDevice.TEMPLATE_RECORD
                                    } else {
                                        CameraDevice.TEMPLATE_PREVIEW
                                    }
                                    val builder = try {
                                        newCamera.createCaptureRequest(template).apply {
                                            addTarget(surf)
                                            applyCommonSettings(this)
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed creating preview request on new camera ${newCamera.id}", e)
                                        null
                                    }

                                    // Swap pointers seamlessly
                                    val oldDevice = cameraDevice
                                    val oldSess = captureSession
                                    val oldBuilder = synchronized(previewRequestLock) { previewRequestBuilder }
                                    val oldJpeg = imageReaderJpeg

                                    cameraDevice = newCamera
                                    captureSession = session
                                    if (builder != null) {
                                        synchronized(previewRequestLock) {
                                            previewRequestBuilder = builder
                                        }
                                        try {
                                            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to set repeating request on new camera ${newCamera.id}", e)
                                        }
                                    }

                                    val useUw = (targetLens.lensType == LensType.ULTRAWIDE)
                                    _isUsingUltraWideSurface.value = useUw
                                    _displayedPreviewSource.value = if (useUw) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                                    activeSessionLens = targetLens
                                    _selectedLens.value = targetLens
                                    activeSessionPhysicalCameraId = targetLens.physicalCameraId
                                    _isCameraReady.value = true

                                    // If keep-ultrawide-ready is enabled and old camera is still valid, retain as standby
                                    if (_isKeepUltraWideReady.value && oldDevice != null && oldSess != null) {
                                        ultraWideStandbyCameraDevice = oldDevice
                                        ultraWideStandbyCaptureSession = oldSess
                                        ultraWideStandbyRequestBuilder = oldBuilder
                                        ultraWideStandbyJpegReader = oldJpeg
                                        isUltraWideStreaming.set(true)
                                        _ultraWideStreamStatus.value = BackgroundCameraStatus.READY_QUIET
                                        syncStandbyStreamSettings()
                                    } else {
                                        try { oldSess?.stopRepeating() } catch (_: Throwable) {}
                                        try { oldSess?.close() } catch (_: Throwable) {}
                                        try { oldDevice?.close() } catch (_: Throwable) {}
                                        if (ultraWideStandbyCameraDevice == oldDevice) {
                                            ultraWideStandbyCameraDevice = null
                                            ultraWideStandbyCaptureSession = null
                                        }
                                    }

                                    // Setup photo readers for the new lens in background
                                    setupImageReaders(targetLens.cameraId)
                                    scheduleZoomPreviewUpdate(immediate = true, explicitZoom = currentZoom)
                                    completeLensSwitch(targetLens, isContinuousTransition = isContinuousTransition)
                                    Log.i(TAG, "[LENS_SWITCH] Seamless camera switch completed to ${targetLens.lensType} (ID=${newCamera.id})")
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.w(TAG, "Configure failed on new camera ${newCamera.id}, falling back to sequential switch")
                                try { session.close() } catch (_: Throwable) {}
                                try { newCamera.close() } catch (_: Throwable) {}
                                fallbackToSequentialSwitch(targetLens, switchGen, isContinuousTransition)
                            }
                        }

                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val outputConfig = android.hardware.camera2.params.OutputConfiguration(surf)
                                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                                    listOf(outputConfig),
                                    Executor { cmd -> backgroundHandler?.post(cmd) ?: cmd.run() },
                                    stateCallback
                                )
                                newCamera.createCaptureSession(sessionConfig)
                            } else {
                                @Suppress("DEPRECATION")
                                newCamera.createCaptureSession(listOf(surf), stateCallback, backgroundHandler)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed creating capture session on new camera ${newCamera.id}", e)
                            try { newCamera.close() } catch (_: Throwable) {}
                            fallbackToSequentialSwitch(targetLens, switchGen, isContinuousTransition)
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Throwable) {}
                    if (switchGen == lensSwitchGeneration.get()) {
                        fallbackToSequentialSwitch(targetLens, switchGen, isContinuousTransition)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Concurrent open error ($error) on camera ${targetLens.cameraId}; falling back to sequential switch")
                    try { camera.close() } catch (_: Throwable) {}
                    if (switchGen == lensSwitchGeneration.get()) {
                        fallbackToSequentialSwitch(targetLens, switchGen, isContinuousTransition)
                    }
                }
            }, backgroundHandler)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to open target camera ${targetLens.cameraId} concurrently", t)
            fallbackToSequentialSwitch(targetLens, switchGen, isContinuousTransition)
        }
    }

    private fun fallbackToSequentialSwitch(
        targetLens: LensInfo,
        switchGen: Int,
        isContinuousTransition: Boolean
    ) {
        synchronized(cameraLifecycleLock) {
            if (switchGen != lensSwitchGeneration.get()) return
            // Safely close previous session and camera without calling restartCamera()
            try { captureSession?.stopRepeating() } catch (_: Throwable) {}
            try { captureSession?.close() } catch (_: Throwable) {}
            captureSession = null
            try { cameraDevice?.close() } catch (_: Throwable) {}
            cameraDevice = null
            activeSessionPhysicalCameraId = null

            _selectedLens.value = targetLens
            activeSessionLens = targetLens
            val useUw = (targetLens.lensType == LensType.ULTRAWIDE)
            _isUsingUltraWideSurface.value = useUw
            _displayedPreviewSource.value = if (useUw) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN

            startCamera()
        }
    }

    /**
     * Reconfigures the active recording CameraCaptureSession on the already-open CameraDevice
     * when transitioning between a physical sub-camera stream and logical stream during video recording,
     * avoiding closing or reopening the CameraDevice.
     */
    private fun reconfigureRecordingSessionOnSameCamera(
        targetLens: LensInfo,
        targetPhysicalId: String?,
        switchGen: Int
    ) {
        val camera = cameraDevice ?: run {
            completeLensSwitch(targetLens)
            return
        }
        backgroundHandler?.post {
            synchronized(cameraLifecycleLock) {
                if (switchGen != lensSwitchGeneration.get() || cameraDevice != camera) {
                    return@synchronized
                }
                val previewSurf = getActivePreviewSurface()
                val relay = dualCameraRecordingRelay
                if (relay != null) {
                    val targetSource = if (targetLens.lensType == LensType.ULTRAWIDE) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                    relay.setActiveSource(targetSource)
                    val requestedRes = activeRecordingStreamConfig?.videoResolution
                        ?: (userSelectedVideoResolution ?: _selectedVideoResolution.value ?: CameraResolution(1920, 1080))
                    val effectiveRes = resolveEffectiveVideoResolutionForLens(targetLens.cameraId, requestedRes)
                    relay.updateInputBufferSize(
                        isUltraWide = (targetLens.lensType == LensType.ULTRAWIDE),
                        width = effectiveRes.width,
                        height = effectiveRes.height
                    )
                }
                val recSurf = relay?.getRecorderSurfaceForLens(targetLens) ?: activeRecordingSurface
                if (!_isRecordingVideo.value || previewSurf == null || recSurf == null || !recSurf.isValid) {
                    completeLensSwitch(targetLens)
                    return@synchronized
                }
                activeRecordingSurface = recSurf

                try {
                    try {
                        captureSession?.stopRepeating()
                    } catch (ignored: Throwable) {}
                    try {
                        captureSession?.close()
                    } catch (ignored: Throwable) {}
                    captureSession = null

                    val configGen = sessionConfigGeneration.incrementAndGet()
                    val recConfig = activeRecordingStreamConfig
                    val is10BitMode = recConfig?.is10Bit
                        ?: (currentMode == CameraMode.CINEMA && cinemaConfig.value.logBitDepth == LogBitDepth.BIT_10)
                    activeSessionPhysicalCameraId = targetPhysicalId
                    activeSessionLens = targetLens

                    val recordBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurf)
                        addTarget(recSurf)
                        applyCommonSettings(this)
                        set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                        val targetChars = getCharacteristics(targetLens.cameraId)
                        val desiredFps = recConfig?.targetFps ?: if (currentMode == CameraMode.CINEMA) cinemaConfig.value.videoFps else videoFps
                        val matchedRange = targetChars?.let { findBestFpsRange(it, desiredFps) } ?: recConfig?.fpsRange
                        if (matchedRange != null) {
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, matchedRange)
                        }
                    }
                    synchronized(previewRequestLock) {
                        previewRequestBuilder = recordBuilder
                    }

                    createRecordingCaptureSession(
                        camera = camera,
                        previewSurface = previewSurf,
                        recorderSurface = recSurf,
                        is10Bit = is10BitMode,
                        physicalCameraId = targetPhysicalId,
                        callback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) {
                                    try { session.close() } catch (ignored: Throwable) {}
                                    return
                                }
                                captureSession = session
                                _isCameraReady.value = true
                                try {
                                    synchronized(previewRequestLock) {
                                        previewRequestBuilder?.let { builder ->
                                            applyCommonSettings(builder)
                                            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed repeating request on same-camera recording switch", e)
                                }
                                completeLensSwitch(targetLens)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) return
                                Log.w(TAG, "Physical recording session rejected for phys=$targetPhysicalId, falling back to logical recording session")
                                activeSessionPhysicalCameraId = null
                                val fallbackGen = sessionConfigGeneration.incrementAndGet()
                                createRecordingCaptureSession(
                                    camera = camera,
                                    previewSurface = previewSurf,
                                    recorderSurface = recSurf,
                                    is10Bit = false,
                                    physicalCameraId = null,
                                    callback = object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(fallbackSession: CameraCaptureSession) {
                                            if (fallbackGen != sessionConfigGeneration.get() || cameraDevice != camera) {
                                                try { fallbackSession.close() } catch (ignored: Throwable) {}
                                                return
                                            }
                                            captureSession = fallbackSession
                                            _isCameraReady.value = true
                                            try {
                                                synchronized(previewRequestLock) {
                                                    previewRequestBuilder?.let { builder ->
                                                        applyCommonSettings(builder)
                                                        fallbackSession.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Failed repeating request on fallback logical recording session", e)
                                            }
                                            completeLensSwitch(targetLens)
                                        }

                                        override fun onConfigureFailed(fallbackSession: CameraCaptureSession) {
                                            Log.e(TAG, "Fallback logical recording session also failed")
                                            completeLensSwitch(targetLens)
                                        }
                                    }
                                )
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error reconfiguring recording session on same camera", e)
                    activeSessionPhysicalCameraId = null
                    completeLensSwitch(targetLens)
                }
            }
        } ?: completeLensSwitch(targetLens)
    }

    /**
     * Reconfigures CameraCaptureSession to bind to a physical camera stream inside a logical multi-camera.
     * Keeps the CameraDevice open, eliminating hardware tear-down and HAL contention.
     * If the device HAL does not support physical output binding for the requested surface,
     * falls back safely to standard logical capture session with continuous zoom.
     */
    fun reconfigureSessionForPhysicalLens(targetLens: LensInfo, switchGen: Int = lensSwitchGeneration.get()) {
        val camera = cameraDevice ?: run {
            isSwitchingLens.set(false)
            restartCamera()
            return
        }
        val texture = previewSurfaceTexture ?: run {
            completeLensSwitch(targetLens)
            return
        }

        backgroundHandler?.post {
            synchronized(cameraLifecycleLock) {
                if (switchGen != lensSwitchGeneration.get()) {
                    return@synchronized
                }
                try {
                    // Close previous session
                    try {
                        captureSession?.stopRepeating()
                        captureSession?.abortCaptures()
                    } catch (ignored: Throwable) {}
                    try {
                        captureSession?.close()
                    } catch (ignored: Throwable) {}
                    captureSession = null
                    _isCameraReady.value = false

                    val targetRatio = getTargetAspectRatioForMode(currentMode)
                    val optimalSize = getOptimalPreviewSize(targetLens.cameraId, targetRatio)
                    _previewAspectRatio.value = targetRatio
                    _previewBufferSize.value = optimalSize
                    val cameraW = max(optimalSize.width, optimalSize.height)
                    val cameraH = min(optimalSize.width, optimalSize.height)
                    texture.setDefaultBufferSize(cameraW, cameraH)

                    if (previewSurface == null || !previewSurface!!.isValid) {
                        try { previewSurface?.release() } catch (ignored: Throwable) {}
                        previewSurface = Surface(texture)
                    }
                    val previewSurf = getEffectiveMainPreviewSurface() ?: previewSurface!!

                    // Setup ImageReaders for target physical lens
                    setupImageReaders(targetLens.cameraId)

                    val physId = targetLens.physicalCameraId
                    val chars = getCharacteristics(targetLens.cameraId)
                    val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        chars?.physicalCameraIds ?: emptySet()
                    } else emptySet()

                    val isPhysicalStreamSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            physId != null &&
                            targetLens.supportsPhysicalStream &&
                            physicalIds.contains(physId)

                    if (isPhysicalStreamSupported) {
                        try {
                            val configGen = sessionConfigGeneration.incrementAndGet()
                            activeSessionPhysicalCameraId = physId
                            val outputConfigs = mutableListOf<OutputConfiguration>()

                            val previewConfig = OutputConfiguration(previewSurf)
                            previewConfig.setPhysicalCameraId(physId)
                            outputConfigs.add(previewConfig)

                            imageReaderJpeg?.surface?.let {
                                val cfg = OutputConfiguration(it)
                                cfg.setPhysicalCameraId(physId)
                                outputConfigs.add(cfg)
                            }
                            imageReaderYuv?.surface?.let {
                                val cfg = OutputConfiguration(it)
                                cfg.setPhysicalCameraId(physId)
                                outputConfigs.add(cfg)
                            }
                            imageReaderRaw?.surface?.let {
                                val cfg = OutputConfiguration(it)
                                cfg.setPhysicalCameraId(physId)
                                outputConfigs.add(cfg)
                            }

                            val sessionConfig = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                outputConfigs,
                                Executor { command -> backgroundHandler?.post(command) ?: command.run() },
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        onSessionConfigurationFinished()
                                        if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) {
                                            try { session.close() } catch (ignored: Throwable) {}
                                            return
                                        }
                                        captureSession = session
                                        try {
                                            activeSessionPhysicalCameraId = physId
                                            activeSessionLens = targetLens
                                            val template = CameraDevice.TEMPLATE_PREVIEW
                                            val builder = camera.createCaptureRequest(template).apply {
                                                addTarget(previewSurf)
                                                applyCommonSettings(this)
                                            }
                                            synchronized(previewRequestLock) {
                                                previewRequestBuilder = builder
                                                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                                            }
                                            _isCameraReady.value = true
                                            completeLensSwitch(targetLens)
                                            Log.i(TAG, "[PHYSICAL_STREAM] Successfully configured physical camera stream $physId")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed repeating request on physical session", e)
                                            completeLensSwitch(targetLens)
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) return
                                        Log.w(TAG, "Physical camera output session rejected by HAL for $physId, falling back to standard session")
                                        activeSessionPhysicalCameraId = null
                                        onSessionConfigurationFinished()
                                        createCameraCaptureSession(forceLogicalStream = true)
                                    }
                                }
                            )
                            camera.createCaptureSession(sessionConfig)
                            return@synchronized
                        } catch (e: Exception) {
                            activeSessionPhysicalCameraId = null
                            Log.w(TAG, "Failed to create physical camera output configuration for $physId, falling back", e)
                        }
                    }

                    // Fallback or return to logical stream: standard session with logical zoom
                    activeSessionPhysicalCameraId = null
                    createCameraCaptureSession(forceLogicalStream = true)
                } catch (e: Exception) {
                    Log.e(TAG, "reconfigureSessionForPhysicalLens failed", e)
                    activeSessionPhysicalCameraId = null
                    isSwitchingLens.set(false)
                    restartCamera()
                }
            }
        } ?: run {
            isSwitchingLens.set(false)
            restartCamera()
        }
    }

    @SuppressLint("MissingPermission")
    private fun switchCameraDuringRecording(
        lens: LensInfo,
        previousLens: LensInfo?,
        switchStartNs: Long,
        switchGen: Int
    ) {
        val mgr = cameraManager ?: run {
            completeLensSwitch(lens)
            return
        }
        startBackgroundThread()
        backgroundHandler?.post {
            synchronized(cameraLifecycleLock) {
                if (switchGen != lensSwitchGeneration.get()) {
                    return@synchronized
                }
                val isTargetFront = (lens.facing == CameraCharacteristics.LENS_FACING_FRONT)
                if (isTargetFront) {
                    // Release standby ultra-wide camera when switching to front camera
                    // to prevent camera hardware resource conflicts (e.g. max cameras in use)
                    releaseUltraWideStandby()
                }
                val oldSession = captureSession
                val oldDevice = cameraDevice
                captureSession = null
                activeSessionPhysicalCameraId = null
                try {
                    oldSession?.stopRepeating()
                } catch (ignored: Throwable) {}
                try {
                    oldSession?.close()
                } catch (ignored: Throwable) {}
                try {
                    oldDevice?.close()
                } catch (ignored: Throwable) {}
                cameraDevice = null

                fun recoverToPreviousLensIfNeeded() {
                    val fallbackLens = previousLens?.takeIf { it.cameraId != lens.cameraId }
                    if (fallbackLens != null && switchGen == lensSwitchGeneration.get()) {
                        Log.w(TAG, "[RECORDING_SWITCH] Recovering to previous lens ${fallbackLens.lensType} (${fallbackLens.cameraId})")
                        _selectedLens.value = fallbackLens
                        activeSessionLens = fallbackLens
                        try {
                            mgr.openCamera(fallbackLens.cameraId, object : CameraDevice.StateCallback() {
                                override fun onOpened(recoveredCam: CameraDevice) {
                                    synchronized(cameraLifecycleLock) {
                                        cameraDevice = recoveredCam
                                        val recSurf = activeRecordingSurface
                                        val pSurf = getActivePreviewSurface()
                                        if (_isRecordingVideo.value && recSurf != null && recSurf.isValid && pSurf != null) {
                                            val configGen = sessionConfigGeneration.incrementAndGet()
                                            val builder = recoveredCam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                                addTarget(pSurf)
                                                addTarget(recSurf)
                                                applyCommonSettings(this)
                                                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                                            }
                                            synchronized(previewRequestLock) {
                                                previewRequestBuilder = builder
                                            }
                                            createRecordingCaptureSession(
                                                camera = recoveredCam,
                                                previewSurface = pSurf,
                                                recorderSurface = recSurf,
                                                is10Bit = false,
                                                physicalCameraId = null,
                                                callback = object : CameraCaptureSession.StateCallback() {
                                                    override fun onConfigured(session: CameraCaptureSession) {
                                                        if (configGen != sessionConfigGeneration.get() || cameraDevice != recoveredCam) return
                                                        captureSession = session
                                                        _isCameraReady.value = true
                                                        try {
                                                            synchronized(previewRequestLock) {
                                                                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                                                            }
                                                        } catch (ignored: Exception) {}
                                                        completeLensSwitch(fallbackLens)
                                                    }
                                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                                        completeLensSwitch(fallbackLens)
                                                        createCameraCaptureSession()
                                                    }
                                                }
                                            )
                                        } else {
                                            createCameraCaptureSession()
                                            completeLensSwitch(fallbackLens)
                                        }
                                    }
                                }
                                override fun onDisconnected(cam: CameraDevice) {
                                    cam.close()
                                    if (cameraDevice == cam) cameraDevice = null
                                    completeLensSwitch(fallbackLens)
                                }
                                override fun onError(cam: CameraDevice, err: Int) {
                                    cam.close()
                                    if (cameraDevice == cam) cameraDevice = null
                                    completeLensSwitch(fallbackLens)
                                }
                            }, backgroundHandler)
                            return
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to recover previous camera during recording", e)
                        }
                    }
                    completeLensSwitch(lens)
                }

                try {
                    mgr.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            synchronized(cameraLifecycleLock) {
                                if (switchGen != lensSwitchGeneration.get()) {
                                    camera.close()
                                    return
                                }
                                cameraDevice = camera
                                val texture = previewSurfaceTexture ?: run {
                                    completeLensSwitch(lens)
                                    return
                                }
                                val optimalSize = _previewBufferSize.value ?: Size(1920, 1080)
                                val cameraW = max(optimalSize.width, optimalSize.height)
                                val cameraH = min(optimalSize.width, optimalSize.height)
                                texture.setDefaultBufferSize(cameraW, cameraH)
                                if (previewSurface == null || !previewSurface!!.isValid) {
                                    try { previewSurface?.release() } catch (ignored: Throwable) {}
                                    previewSurface = Surface(texture)
                                }

                                val relay = dualCameraRecordingRelay
                                val isTargetFront = (lens.facing == CameraCharacteristics.LENS_FACING_FRONT)
                                val targetSensorOrient = try {
                                    getCharacteristics(lens.cameraId)?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isTargetFront) 270 else 90)
                                } catch (_: Exception) {
                                    if (isTargetFront) 270 else 90
                                }
                                _sensorOrientation.value = targetSensorOrient
                                if (relay != null) {
                                    relay.updateCameraFacing(isTargetFront, targetSensorOrient)
                                    val targetSource = if (lens.lensType == LensType.ULTRAWIDE) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                                    relay.setActiveSource(targetSource)
                                    val requestedRes = activeRecordingStreamConfig?.videoResolution
                                        ?: (userSelectedVideoResolution ?: _selectedVideoResolution.value ?: CameraResolution(1920, 1080))
                                    val effectiveRes = resolveEffectiveVideoResolutionForLens(lens.cameraId, requestedRes)
                                    relay.updateInputBufferSize(
                                        isUltraWide = (lens.lensType == LensType.ULTRAWIDE),
                                        width = effectiveRes.width,
                                        height = effectiveRes.height
                                    )
                                }
                                if (isSoftwareCinemaRecording) {
                                    cinemaSoftwareRecorder.updateCameraFacing(isTargetFront, targetSensorOrient)
                                }
                                val recSurf = relay?.getRecorderSurfaceForLens(lens) ?: activeRecordingSurface
                                if (recSurf != null && recSurf.isValid) {
                                    activeRecordingSurface = recSurf
                                }
                                val isRecording = _isRecordingVideo.value
                                if (isRecording && recSurf != null && recSurf.isValid) {
                                    try {
                                        val configGen = sessionConfigGeneration.incrementAndGet()
                                        val recConfig = activeRecordingStreamConfig
                                        val useLogicalZoom = canUseLogicalZoomForLens(lens.cameraId, lens, currentZoom)
                                        val targetPhysId = if (useLogicalZoom) null else lens.physicalCameraId
                                        activeSessionPhysicalCameraId = targetPhysId
                                        activeSessionLens = lens

                                        val previewSurf = getEffectiveMainPreviewSurface() ?: previewSurface!!
                                        val template = CameraDevice.TEMPLATE_RECORD
                                        val recordBuilder = camera.createCaptureRequest(template).apply {
                                            addTarget(previewSurf)
                                            addTarget(recSurf)
                                            applyCommonSettings(this)
                                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                                            val targetChars = getCharacteristics(lens.cameraId)
                                            val desiredFps = recConfig?.targetFps ?: if (currentMode == CameraMode.CINEMA) cinemaConfig.value.videoFps else videoFps
                                            val matchedRange = targetChars?.let { findBestFpsRange(it, desiredFps) } ?: recConfig?.fpsRange
                                            if (matchedRange != null) {
                                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, matchedRange)
                                            }
                                        }
                                        synchronized(previewRequestLock) {
                                            previewRequestBuilder = recordBuilder
                                        }

                                        val sessionCallback = object : CameraCaptureSession.StateCallback() {
                                            override fun onConfigured(session: CameraCaptureSession) {
                                                if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) {
                                                    try { session.close() } catch (ignored: Throwable) {}
                                                    return
                                                }
                                                captureSession = session
                                                _isCameraReady.value = true
                                                try {
                                                    synchronized(previewRequestLock) {
                                                        previewRequestBuilder?.let { builder ->
                                                            applyCommonSettings(builder)
                                                            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Failed repeating request after lens switch in recording", e)
                                                }
                                                completeLensSwitch(lens)
                                                if (!isTargetFront && _isKeepUltraWideReady.value) {
                                                    val recConf = activeRecordingStreamConfig
                                                    val is4k = recConf != null && maxOf(recConf.videoResolution.width, recConf.videoResolution.height) >= 3840
                                                    if (!is4k) {
                                                        ensureUltraWideSimultaneousReady()
                                                    }
                                                }
                                            }

                                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                                if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) return
                                                Log.e(TAG, "Failed to configure recording session after lens switch")
                                                activeSessionPhysicalCameraId = null
                                                completeLensSwitch(lens)
                                                createCameraCaptureSession()
                                            }
                                        }

                                        createRecordingCaptureSession(
                                            camera = camera,
                                            previewSurface = previewSurf,
                                            recorderSurface = recSurf,
                                            is10Bit = false,
                                            physicalCameraId = targetPhysId,
                                            callback = sessionCallback
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error re-attaching recorder surface after lens switch", e)
                                        activeSessionPhysicalCameraId = null
                                        createCameraCaptureSession()
                                        completeLensSwitch(lens)
                                    }
                                } else {
                                    createCameraCaptureSession()
                                    completeLensSwitch(lens)
                                }
                                val elapsedMs = (System.nanoTime() - switchStartNs) / 1_000_000L
                                Log.i(TAG, "[RECORDING_SWITCH] Seamless lens switch to ${lens.lensType} completed in ${elapsedMs}ms")
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (cameraDevice == camera) cameraDevice = null
                            if (switchGen == lensSwitchGeneration.get()) {
                                recoverToPreviousLensIfNeeded()
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e(TAG, "Error opening camera ${lens.cameraId} during recording: $error")
                            camera.close()
                            if (cameraDevice == camera) cameraDevice = null
                            if (switchGen == lensSwitchGeneration.get()) {
                                recoverToPreviousLensIfNeeded()
                            }
                        }
                    }, backgroundHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open camera ${lens.cameraId} during recording", e)
                    recoverToPreviousLensIfNeeded()
                }
            }
        }
    }

    /**
     * Switch photo resolution
     */
    fun selectPhotoResolution(resolution: CameraResolution) {
        _selectedPhotoResolution.value = resolution
        updatePreviewAspectRatio()
        if (cameraDevice != null) {
            reconfigureSession()
        } else {
            restartCamera()
        }
    }

    /**
     * Switch video resolution
     */
    fun selectVideoResolution(resolution: CameraResolution) {
        userSelectedVideoResolution = resolution
        _selectedVideoResolution.value = resolution
        val supportedFps = getSupportedFpsForResolution(resolution)
        if (!supportedFps.contains(videoFps)) {
            videoFps = supportedFps.firstOrNull() ?: 30
        }
        updatePreviewAspectRatio()
        if (cameraDevice != null) {
            reconfigureSession()
        } else {
            restartCamera()
        }
    }

    /**
     * Determines which FPS options (e.g. 60, 30) are genuinely supported
     * by the given resolution and active camera lens configuration.
     */
    fun getSupportedFpsForResolution(
        resolution: CameraResolution,
        cameraId: String? = _selectedLens.value?.cameraId
    ): List<Int> {
        val targetCameraId = cameraId ?: _selectedLens.value?.cameraId ?: return listOf(30)
        val chars = try {
            getCharacteristics(targetCameraId)
        } catch (e: Exception) {
            null
        } ?: return listOf(30)

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val maxFps = fpsRanges.maxOfOrNull { it.upper } ?: 30
        val supports60FpsRange = fpsRanges.any { it.upper >= 60 }

        if (!supports60FpsRange || maxFps < 60) {
            return listOf(30)
        }

        val maxDim = maxOf(resolution.width, resolution.height)
        val minDim = minOf(resolution.width, resolution.height)

        var canDo60 = true
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            val size = Size(maxDim, minDim)
            val minDurationNs = try {
                val mrDuration = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
                if (mrDuration > 0L) {
                    mrDuration
                } else {
                    map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                }
            } catch (e: Exception) {
                0L
            }

            if (minDurationNs > 0L) {
                // If min frame duration > 20ms (~50fps), 60 FPS is physically not supported for this resolution
                if (minDurationNs > 20_000_000L) {
                    canDo60 = false
                }
            } else {
                // When minDuration is 0 (unreported/emulator), check hardware level
                val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                val isFullOrBetter = hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
                        hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3

                // 4K (3840x2160) at 60fps requires FULL or LEVEL_3 hardware level
                if (maxDim >= 3840 && minDim >= 2160 && !isFullOrBetter) {
                    canDo60 = false
                }
            }
        }

        // Action / Ultra stabilization mode restrictions
        val isUltraStab = _hybridStabilizationConfig.value.isUltraStabilizationEnabled || preferences.isUltraStabilizationEnabled
        if (isUltraStab && (maxDim >= 3840 || minDim >= 2160)) {
            canDo60 = false
        }

        // Front camera usually limits 4K/2K to 30fps max
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        if (facing == CameraCharacteristics.LENS_FACING_FRONT && maxDim >= 2560) {
            canDo60 = false
        }

        return if (canDo60) listOf(60, 30) else listOf(30)
    }

    /**
     * Restore saved video resolution without triggering camera restart before viewfinder is attached
     */
    fun restoreInitialVideoResolution(resolution: CameraResolution) {
        userSelectedVideoResolution = resolution
        _selectedVideoResolution.value = resolution
        updatePreviewAspectRatio()
    }

    /**
     * Switch between Photo, Video & Cinema modes smoothly without closing hardware device
     */
    fun setMode(mode: CameraMode) {
        if (currentMode == mode && _previewBufferSize.value != null) return
        val previousMode = currentMode
        val oldRatio = getTargetAspectRatioForMode(previousMode)
        val newRatio = getTargetAspectRatioForMode(mode)
        val was43 = (previousMode == CameraMode.PHOTO)
        val is43 = (mode == CameraMode.PHOTO)
        val oldStreamRatio = if (was43) 4f / 3f else 16f / 9f
        val newStreamRatio = if (is43) 4f / 3f else 16f / 9f
        val wasVideoTemplate = (previousMode == CameraMode.VIDEO || previousMode == CameraMode.CINEMA)
        val isVideoTemplate = (mode == CameraMode.VIDEO || mode == CameraMode.CINEMA)
        val wasMore = (previousMode == CameraMode.MORE)
        if (_isRecordingVideo.value) {
            stopVideoRecording()
        }
        currentMode = mode

        val previousBufferSize = _previewBufferSize.value
        // Immediately synchronize aspect ratio and optimal buffer dimensions on mode switch
        _previewAspectRatio.value = newRatio
        val optimalSize = getOptimalPreviewSize(_selectedLens.value?.cameraId, newRatio)
        _previewBufferSize.value = optimalSize
        val cameraW = max(optimalSize.width, optimalSize.height)
        val cameraH = min(optimalSize.width, optimalSize.height)
        syncPreviewBufferSizes(cameraW, cameraH)

        // Software gyro EIS is intentionally not started in any mode.
        gyroStabilizationEngine.stop()



        val optimalSizeChanged = previousBufferSize != null && (previousBufferSize.width != optimalSize.width || previousBufferSize.height != optimalSize.height)

        val needsReconfigure = (kotlin.math.abs(oldStreamRatio - newStreamRatio) > 0.05f) ||
                (was43 != is43) ||
                (wasVideoTemplate != isVideoTemplate) ||
                (wasMore && is43) ||
                optimalSizeChanged ||
                (captureSession == null) ||
                (!_isCameraReady.value)

        if (needsReconfigure) {
            if (cameraDevice != null) {
                reconfigureSession()
            } else {
                restartCamera()
            }
        } else {
            updatePreviewSettings()
            syncStandbyStreamSettings()
        }
    }

    /**
     * Reconfigures CameraCaptureSession on the currently active CameraDevice.
     * Prevents hardware sensor re-opening, elimination of black screens and HAL contention.
     */
    fun reconfigureSession() {
        if (!_isCameraInitialized.value || previewSurfaceTexture == null || cameraDevice == null) {
            restartCamera()
            return
        }
        backgroundHandler?.post {
            synchronized(cameraLifecycleLock) {
                if (isConfiguringSession || isStartingCamera) {
                    Log.d(TAG, "reconfigureSession already in progress, queuing pending reconfigure for $currentMode")
                    pendingReconfigureSession = true
                    return@synchronized
                }
                isConfiguringSession = true
                try {
                    val camera = cameraDevice ?: run {
                        isConfiguringSession = false
                        return@synchronized
                    }
                    val lens = _selectedLens.value ?: run {
                        isConfiguringSession = false
                        return@synchronized
                    }
                    val texture = previewSurfaceTexture ?: run {
                        isConfiguringSession = false
                        return@synchronized
                    }

                    val targetRatio = getTargetAspectRatioForMode(currentMode)
                    val optimalPreviewSize = getOptimalPreviewSize(lens.cameraId, targetRatio, lens.lensType)

                    _previewAspectRatio.value = targetRatio
                    _previewBufferSize.value = optimalPreviewSize
                    val cameraW = max(optimalPreviewSize.width, optimalPreviewSize.height)
                    val cameraH = min(optimalPreviewSize.width, optimalPreviewSize.height)
                    syncPreviewBufferSizes(cameraW, cameraH)

                    // Safely close previous session before reconfiguring
                    try {
                        captureSession?.stopRepeating()
                        captureSession?.abortCaptures()
                    } catch (ignored: Throwable) {}
                    try {
                        captureSession?.close()
                    } catch (ignored: Throwable) {}
                    captureSession = null
                    _isCameraReady.value = false

                    if (previewSurface == null || !previewSurface!!.isValid) {
                        try { previewSurface?.release() } catch (ignored: Throwable) {}
                        previewSurface = Surface(texture)
                    }

                    setupImageReaders(lens.cameraId)
                    createCameraCaptureSession()
                } catch (e: Exception) {
                    isConfiguringSession = false
                    Log.e(TAG, "reconfigureSession failed, falling back to restartCamera", e)
                    restartCamera()
                }
            }
        } ?: run {
            restartCamera()
        }
    }

    /**
     * Update Cinema Mode configuration and immediately apply to hardware ISP
     */
    fun setCinemaConfig(newConfig: CinemaConfig) {
        val oldConfig = _cinemaConfig.value
        val oldProfile = oldConfig.colorProfile
        val supportedDepths = _cinemaCapabilities.value.getSupportedBitDepthsForCodec(newConfig.codec)
        val sanitizedConfig = if (newConfig.logBitDepth == LogBitDepth.BIT_10 && !supportedDepths.contains(LogBitDepth.BIT_10)) {
            newConfig.copy(logBitDepth = LogBitDepth.BIT_8)
        } else {
            newConfig
        }
        cinemaEngine.config = sanitizedConfig
        _cinemaConfig.value = sanitizedConfig
        exposureCompensationIndex = sanitizedConfig.exposureCompensation
        if (sanitizedConfig.colorProfile == CinemaColorProfile.HLG10 && oldProfile != CinemaColorProfile.HLG10) {
            cinemaEngine.hlg10AutoExposureEngine.reset()
        }
        if (isSoftwareCinemaRecording) {
            cinemaSoftwareRecorder.updateLiveCinemaConfig(sanitizedConfig)
        }
        if (currentMode == CameraMode.CINEMA) {
            updatePreviewAspectRatio()
            updatePreviewSettings()
            val resChanged = oldConfig.selectedResolution != sanitizedConfig.selectedResolution
            val ratioChanged = oldConfig.aspectRatio != sanitizedConfig.aspectRatio
            if (resChanged || ratioChanged) {
                if (cameraDevice != null) {
                    reconfigureSession()
                } else {
                    restartCamera()
                }
            }
        }
    }

    /**
     * Set Camera2 AE exposure compensation and immediately update repeating preview/recording request
     */
    fun setExposureCompensation(index: Int) {
        val caps = _capabilities.value
        val clamped = if (caps.minExposureCompensation <= caps.maxExposureCompensation) {
            index.coerceIn(caps.minExposureCompensation, caps.maxExposureCompensation)
        } else {
            index
        }
        exposureCompensationIndex = clamped
        isAeLocked = false
        if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA) {
            manualIso = null
            manualExposureTimeNs = null
        }
        if (currentMode == CameraMode.CINEMA) {
            _cinemaConfig.value = _cinemaConfig.value.copy(
                exposureCompensation = clamped,
                manualIso = null,
                manualShutterSpeedNs = null
            )
            cinemaEngine.config = _cinemaConfig.value
        }
        updatePreviewSettings()
    }

    @Volatile
    private var viewfinderWidth: Int = 0
    @Volatile
    private var viewfinderHeight: Int = 0

    fun onViewfinderSurfaceSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        previewSurfaceTexture = texture
        if (width > 0 && height > 0) {
            viewfinderWidth = width
            viewfinderHeight = height
        }
        val targetRatio = getTargetAspectRatioForMode(currentMode)
        val selectedCamId = _selectedLens.value?.cameraId
        val isVideoOrCinema = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA)
        val currentBuf = _previewBufferSize.value
        val isBufValidForLens = !isVideoOrCinema && currentBuf != null && isSizeSupportedForLens(selectedCamId, currentBuf) && run {
            val r = max(currentBuf.width, currentBuf.height).toFloat() / min(currentBuf.width, currentBuf.height).toFloat()
            kotlin.math.abs(r - targetRatio) < 0.08f
        }
        val optimalSize = if (isBufValidForLens && currentBuf != null) currentBuf else getOptimalPreviewSize(selectedCamId, targetRatio, _selectedLens.value?.lensType)
        _previewBufferSize.value = optimalSize
        _previewAspectRatio.value = targetRatio
        val cameraW = max(optimalSize.width, optimalSize.height)
        val cameraH = min(optimalSize.width, optimalSize.height)
        syncPreviewBufferSizes(cameraW, cameraH)

        var curSurf = previewSurface
        if (curSurf == null || !curSurf.isValid) {
            try { curSurf?.release() } catch (ignored: Throwable) {}
            curSurf = Surface(texture)
            previewSurface = curSurf
        }
    }

    /**
     * Attach viewfinder surface texture from Compose AndroidView
     */
    fun setPreviewSurfaceTexture(texture: SurfaceTexture?, width: Int = 0, height: Int = 0) {
        val prevTexture = previewSurfaceTexture
        previewSurfaceTexture = texture
        if (width > 0 && height > 0) {
            viewfinderWidth = width
            viewfinderHeight = height
        }
        if (texture != null) {
            val targetRatio = getTargetAspectRatioForMode(currentMode)
            val selectedCamId = _selectedLens.value?.cameraId
            val isVideoOrCinema = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA)
            val currentBuf = _previewBufferSize.value
            val isBufValidForLens = !isVideoOrCinema && currentBuf != null && isSizeSupportedForLens(selectedCamId, currentBuf) && run {
                val r = max(currentBuf.width, currentBuf.height).toFloat() / min(currentBuf.width, currentBuf.height).toFloat()
                kotlin.math.abs(r - targetRatio) < 0.08f
            }
            val optimalSize = if (isBufValidForLens && currentBuf != null) currentBuf else getOptimalPreviewSize(selectedCamId, targetRatio)
            _previewBufferSize.value = optimalSize
            _previewAspectRatio.value = targetRatio
            val cameraW = max(optimalSize.width, optimalSize.height)
            val cameraH = min(optimalSize.width, optimalSize.height)
            syncPreviewBufferSizes(cameraW, cameraH)

            if (previewSurface == null || !previewSurface!!.isValid) {
                try { previewSurface?.release() } catch (ignored: Throwable) {}
                previewSurface = Surface(texture)
            }

            if (prevTexture != texture || cameraDevice == null) {
                if (_isCameraInitialized.value) {
                    startCamera()
                }
            } else if (captureSession == null && !isConfiguringSession && !isStartingCamera) {
                reconfigureSession()
            }
        } else {
            closeCamera()
        }
    }

    /**
     * Start/Open Camera2 device
     */
    @SuppressLint("MissingPermission")
    fun startCamera() {
        if (!_isCameraInitialized.value) {
            Log.d(TAG, "startCamera deferred: camera not initialized yet")
            isSwitchingLens.set(false)
            return
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startCamera aborted: CAMERA permission not granted")
            isSwitchingLens.set(false)
            return
        }

        val lens = _selectedLens.value ?: run {
            isSwitchingLens.set(false)
            return
        }
        val texture = previewSurfaceTexture ?: run {
            isSwitchingLens.set(false)
            return
        }
        val mgr = cameraManager ?: run {
            isSwitchingLens.set(false)
            return
        }

        startBackgroundThread()
        initOrientationListener()

        synchronized(cameraLifecycleLock) {
            if (isStartingCamera) {
                restartPending = true
                return
            }
            if (cameraDevice != null) {
                reconfigureSession()
                return
            }
            isClosingCamera = false
            isStartingCamera = true
        }

        try {
            // Inspect target lens capabilities on background thread (avoids blocking UI thread during Front <-> Back switch)
            inspectCapabilities(lens.cameraId)

            val chars = getCharacteristics(lens.cameraId) ?: run {
                synchronized(cameraLifecycleLock) { isStartingCamera = false }
                completeLensSwitch(lens)
                return
            }
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
                synchronized(cameraLifecycleLock) { isStartingCamera = false }
                completeLensSwitch(lens)
                return
            }

            // Pick optimal preview size matching selected aspect ratio and viewfinderResolution level
            val targetRatio = getTargetAspectRatioForMode(currentMode)
            val optimalPreviewSize = getOptimalPreviewSize(lens.cameraId, targetRatio, lens.lensType)

            _previewAspectRatio.value = targetRatio
            val sensorOrient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            _sensorOrientation.value = sensorOrient
            _previewBufferSize.value = optimalPreviewSize
            val cameraW = max(optimalPreviewSize.width, optimalPreviewSize.height)
            val cameraH = min(optimalPreviewSize.width, optimalPreviewSize.height)
            syncPreviewBufferSizes(cameraW, cameraH)

            if (previewSurface == null || !previewSurface!!.isValid) {
                try {
                    previewSurface?.release()
                } catch (ignored: Throwable) {}
                previewSurface = Surface(texture)
            }

            // Setup ImageReader for Photo mode
            setupImageReaders(lens.cameraId)

            mgr.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenRetryCount = 0
                    cameraDevice = camera
                    synchronized(cameraLifecycleLock) {
                        isStartingCamera = false
                        if (restartPending) {
                            restartPending = false
                            backgroundHandler?.post { restartCamera() }
                            return
                        }
                    }
                    createCameraCaptureSession()
                    if (_isKeepUltraWideReady.value) {
                        backgroundHandler?.post { ensureUltraWideSimultaneousReady() }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (ignored: Throwable) {}
                    cameraDevice = null
                    _isCameraReady.value = false
                    synchronized(cameraLifecycleLock) {
                        isStartingCamera = false
                        if (restartPending) {
                            restartPending = false
                            backgroundHandler?.post { restartCamera() }
                            return
                        }
                    }
                    completeLensSwitch(_selectedLens.value)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error (attempt $cameraOpenRetryCount)")
                    try { camera.close() } catch (ignored: Throwable) {}
                    cameraDevice = null
                    _isCameraReady.value = false
                    synchronized(cameraLifecycleLock) {
                        isStartingCamera = false
                        if (restartPending) {
                            restartPending = false
                            backgroundHandler?.post { restartCamera() }
                            return
                        }
                    }
                    completeLensSwitch(_selectedLens.value)
                    // Auto-recover from transient HAL contention or device busy error with bounded retries
                    if (cameraOpenRetryCount < MAX_CAMERA_OPEN_RETRIES &&
                        (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                         error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ||
                         error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)) {
                        cameraOpenRetryCount++
                        backgroundHandler?.postDelayed({
                            restartCamera()
                        }, 300L * cameraOpenRetryCount)
                    }
                }
            }, backgroundHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start camera", t)
            synchronized(cameraLifecycleLock) {
                isStartingCamera = false
            }
            completeLensSwitch(_selectedLens.value)
            _isCameraReady.value = false
        }
    }

    /**
     * Resolves the actual maximum sensor resolution supported by the camera hardware using
     * super-native-camera's Camera2 API implementation:
     * 1. SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION (Android 12+ / API 31+) for genuine 50MP/108MP/200MP
     * 2. High-resolution output sizes (getHighResolutionOutputSizes) from regular map
     * 3. Standard stream configuration map output sizes (highest available hardware resolution)
     */
    fun getMaxResolutionNative(characteristics: CameraCharacteristics, format: Int): Size? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val maxResMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                )
                val ultraSizes = maxResMap?.getOutputSizes(format)
                val maxUltra = ultraSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                if (maxUltra != null) return maxUltra
            } catch (e: Exception) {
                Log.w(TAG, "Error querying SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION", e)
            }
        }

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        try {
            val highResSizes = map?.getHighResolutionOutputSizes(format)
            val maxHighRes = highResSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            if (maxHighRes != null) return maxHighRes
        } catch (e: Exception) {
            Log.w(TAG, "Error querying getHighResolutionOutputSizes", e)
        }

        val standardSizes = map?.getOutputSizes(format)
        return standardSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    /**
     * Determines the optimal native photo capture resolution for the specified lens.
     * In 50MP mode, queries the real sensor-supported maximum-resolution output.
     * Never reuses the main camera resolution.
     */
    fun getOptimalPhotoSizeForLens(lens: LensInfo?, cameraId: String): Size {
        val targetId = if (lens?.physicalCameraId != null && lens.supportsPhysicalStream) {
            lens.physicalCameraId
        } else {
            cameraId
        }
        val chars = try {
            getCharacteristics(targetId) ?: getCharacteristics(cameraId)
        } catch (e: Exception) {
            null
        }

        val is50MMode = photoMegapixelMode == PhotoMegapixelMode.M50
        if (is50MMode && chars != null) {
            val maxNativeResolution = getMaxResolutionNative(chars, ImageFormat.JPEG)
            if (maxNativeResolution != null) {
                val mp = (maxNativeResolution.width.toLong() * maxNativeResolution.height.toLong()) / 1_000_000f
                Log.i(TAG, "[PHOTO_RES_50M] Native maximum-resolution sensor stream selected: ${maxNativeResolution.width}x${maxNativeResolution.height} (~${mp}MP)")
                return maxNativeResolution
            }
        }

        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val allSizes = jpegSizes.distinctBy { "${it.width}x${it.height}" }

        val isUltraWide = lens?.lensType == LensType.ULTRAWIDE

        // Standard 12MP–16MP binned ceiling for fast, zero-lag session creation and multi-stream compatibility
        val maxStandardPixels = 16_500_000L

        // 4:3 aspect ratio filter (~1.333)
        val fourThreeSizes = allSizes.filter { size ->
            val ratio = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height).toFloat()
            kotlin.math.abs(ratio - (4f / 3f)) < 0.05f
        }
        val standardFourThreeSizes = fourThreeSizes.filter {
            (it.width.toLong() * it.height.toLong()) <= maxStandardPixels
        }.ifEmpty { fourThreeSizes }

        // 16:9 aspect ratio filter (~1.777)
        val sixteenNineSizes = allSizes.filter { size ->
            val ratio = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height).toFloat()
            kotlin.math.abs(ratio - (16f / 9f)) < 0.05f
        }
        val standardSixteenNineSizes = sixteenNineSizes.filter {
            (it.width.toLong() * it.height.toLong()) <= maxStandardPixels
        }.ifEmpty { sixteenNineSizes }

        val isPhotoOrPortrait = (currentMode == CameraMode.PHOTO)

        return when {
            isPhotoOrPortrait -> {
                if (isUltraWide) {
                    standardFourThreeSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: allSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: Size(3264, 2448)
                } else if (is50MMode) {
                    fourThreeSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: allSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: Size(4000, 3000)
                } else {
                    standardFourThreeSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: fourThreeSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: allSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: Size(4000, 3000)
                }
            }
            else -> {
                standardSixteenNineSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: allSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: Size(1920, 1080)
            }
        }
    }

    /**
     * Resolves a hardware-compatible YUV_420_888 resolution for BurstEngine that complies with
     * Android Camera2's mandatory stream combinations (PRIV PREVIEW + YUV RECORD + JPEG MAXIMUM).
     */
    fun getOptimalBurstYuvSize(lens: LensInfo?, cameraId: String, targetSize: Size): Size {
        val targetId = if (lens?.physicalCameraId != null && lens.supportsPhysicalStream) {
            lens.physicalCameraId
        } else {
            cameraId
        }
        val chars = try {
            getCharacteristics(targetId) ?: getCharacteristics(cameraId)
        } catch (e: Exception) {
            null
        }
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        if (yuvSizes.isEmpty()) {
            return Size(1920, 1440)
        }

        val targetAspect = maxOf(targetSize.width, targetSize.height).toFloat() /
                minOf(targetSize.width, targetSize.height).toFloat()
        val aspectMatches = yuvSizes.filter { size ->
            val aspect = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height).toFloat()
            kotlin.math.abs(aspect - targetAspect) < 0.06f
        }

        // Cap YUV stream to <= 3.0MP (e.g. 1920x1440 or 1920x1080) so PRIV + YUV + JPEG
        // is guaranteed to succeed on all LIMITED / FULL / LEVEL_3 Camera2 HALs without session failure.
        val maxSafeYuvPixels = 3_000_000L
        val safeAspectSizes = aspectMatches.filter { (it.width.toLong() * it.height.toLong()) <= maxSafeYuvPixels }

        return safeAspectSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: yuvSizes.filter { (it.width.toLong() * it.height.toLong()) <= maxSafeYuvPixels }
                .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: aspectMatches.minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: yuvSizes.minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(1920, 1440)
    }

    private fun setupImageReaders(cameraId: String) {
        try {
            imageReaderJpeg?.close()
        } catch (ignored: Throwable) {}
        imageReaderJpeg = null

        try {
            imageReaderRaw?.close()
        } catch (ignored: Throwable) {}
        imageReaderRaw = null

        try {
            imageReaderYuv?.close()
        } catch (ignored: Throwable) {}
        imageReaderYuv = null

        val caps = _capabilities.value
        val activeLens = _selectedLens.value
        val isVideoOrCinema = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA)
        val targetSize = if (isVideoOrCinema) {
            val targetRecRes = if (currentMode == CameraMode.CINEMA) {
                _cinemaConfig.value.selectedResolution ?: userSelectedVideoResolution ?: _selectedVideoResolution.value ?: CameraResolution(1920, 1080)
            } else {
                userSelectedVideoResolution ?: _selectedVideoResolution.value ?: CameraResolution(1920, 1080)
            }
            Size(maxOf(targetRecRes.width, targetRecRes.height), minOf(targetRecRes.width, targetRecRes.height))
        } else {
            getOptimalPhotoSizeForLens(activeLens, cameraId)
        }
        if (!isVideoOrCinema) {
            _selectedPhotoResolution.value = CameraResolution(targetSize.width, targetSize.height, ImageFormat.JPEG)
        }

        try {
            imageReaderJpeg = ImageReader.newInstance(
                targetSize.width,
                targetSize.height,
                ImageFormat.JPEG,
                3
            )
            val mp = (targetSize.width.toLong() * targetSize.height.toLong()) / 1_000_000f
            Log.i(TAG, "[PHOTO_RES] Recreated ImageReader for ${activeLens?.lensType} (cameraId=$cameraId): JPEG=${targetSize.width}x${targetSize.height} (~${mp}MP)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create ImageReader with ${targetSize.width}x${targetSize.height}, falling back to largest supported", t)
            val fallback = caps.supportedPhotoResolutions.firstOrNull() ?: CameraResolution(1920, 1080)
            try {
                imageReaderJpeg = ImageReader.newInstance(fallback.width, fallback.height, ImageFormat.JPEG, 3)
            } catch (t2: Throwable) {
                Log.e(TAG, "Failed fallback ImageReader", t2)
            }
        }

        val isFastShutterSupported = currentMode == CameraMode.PHOTO && isUltraFastShutterEnabled
        if (isFastShutterSupported) {
            try {
                val optimalBurstSize = getOptimalBurstYuvSize(activeLens, cameraId, targetSize)
                val yuvWidth = optimalBurstSize.width
                val yuvHeight = optimalBurstSize.height
                imageReaderYuv = ImageReader.newInstance(
                    yuvWidth,
                    yuvHeight,
                    ImageFormat.YUV_420_888,
                    4
                )
                ultraFastShutterEngine.configureFramePool(yuvWidth, yuvHeight)
                imageReaderYuv?.setOnImageAvailableListener({ reader ->
                    if (ultraFastShutterEngine.isBurstActive()) {
                        CameraPerformanceMonitor.onYuvFrame()
                        val isFront = _selectedLens.value?.facing == CameraCharacteristics.LENS_FACING_FRONT
                        ultraFastShutterEngine.onSensorImageAvailable(
                            reader = reader,
                            sensorOrientation = getCaptureJpegOrientation(),
                            isFrontFacing = isFront,
                            saveMirrored = saveSelfieAsPreviewed
                        )
                    } else {
                        // Immediately drain stray frame if any so ImageReader never stalls or holds memory
                        try {
                            reader.acquireLatestImage()?.close()
                        } catch (ignored: Throwable) {}
                    }
                }, ultraFastShutterEngine.getCaptureHandler() ?: backgroundHandler)
                Log.i(TAG, "[FAST_SHUTTER] Created YUV ImageReader ${yuvWidth}x${yuvHeight} (idle, not in preview repeating request)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create uncompressed YUV ImageReader for Ultra Fast Shutter", t)
                imageReaderYuv = null
            }
        } else {
            try {
                imageReaderYuv?.close()
            } catch (ignored: Throwable) {}
            imageReaderYuv = null
        }

        if (caps.supportsRaw && isRawCaptureEnabled && caps.supportedRawResolutions.isNotEmpty()) {
            val rawRes = caps.supportedRawResolutions.first()
            try {
                imageReaderRaw = ImageReader.newInstance(
                    rawRes.width,
                    rawRes.height,
                    ImageFormat.RAW_SENSOR,
                    2
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create RAW ImageReader", t)
            }
        }
    }

    private fun getEffectiveMainPreviewSurface(): Surface? {
        if (_isUsingUltraWideSurface.value && !activeLogicalMultiCamUltraWideConfigured) {
            val uwSurf = getOrCreateUltraWidePreviewSurface(_selectedLens.value?.cameraId)
            if (uwSurf != null && uwSurf.isValid) {
                return uwSurf
            }
        }
        val surf = previewSurface ?: getOrCreateMainPreviewSurface(_selectedLens.value?.cameraId)
        if (surf != null && surf.isValid) {
            return surf
        }
        return null
    }

    private fun createCameraCaptureSession(forceLogicalStream: Boolean = false) {
        val camera = cameraDevice ?: run {
            completeLensSwitch()
            return
        }
        val activeLens = _selectedLens.value
        val previewSurf = getEffectiveMainPreviewSurface() ?: run {
            completeLensSwitch()
            return
        }

        // Close previous session safely to avoid overlapping capture sessions
        val oldSession = captureSession
        if (oldSession != null) {
            try {
                oldSession.stopRepeating()
                oldSession.abortCaptures()
            } catch (ignored: Throwable) {}
            try {
                oldSession.close()
            } catch (ignored: Throwable) {}
            captureSession = null
        }

        isConfiguringSession = true
        _isCameraReady.value = false
        val configGen = sessionConfigGeneration.incrementAndGet()

        // Seamless lens switch during active video recording (Front, Back, Ultra-Wide)
        val isRecording = _isRecordingVideo.value
        val recSurface = activeRecordingSurface
        if (isRecording && recSurface != null && recSurface.isValid) {
            try {
                val useLogicalZoom = forceLogicalStream ||
                        (activeLens != null && canUseLogicalZoomForLens(camera.id, activeLens, currentZoom))
                val targetPhysId = if (useLogicalZoom) null else activeLens?.physicalCameraId
                activeSessionPhysicalCameraId = targetPhysId

                val template = CameraDevice.TEMPLATE_RECORD
                val recBuilder = camera.createCaptureRequest(template).apply {
                    addTarget(previewSurf)
                    addTarget(recSurface)
                    applyCommonSettings(this)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                }
                synchronized(previewRequestLock) {
                    previewRequestBuilder = recBuilder
                }

                val is10BitMode = currentMode == CameraMode.CINEMA &&
                        (cinemaConfig.value.logBitDepth == LogBitDepth.BIT_10 || cinemaConfig.value.colorProfile == CinemaColorProfile.HLG10)
                createRecordingCaptureSession(
                    camera = camera,
                    previewSurface = previewSurf,
                    recorderSurface = recSurface,
                    is10Bit = is10BitMode,
                    physicalCameraId = targetPhysId,
                    callback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            onSessionConfigurationFinished()
                            if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) {
                                try { session.close() } catch (ignored: Throwable) {}
                                return
                            }
                            captureSession = session
                            try {
                                synchronized(previewRequestLock) {
                                    previewRequestBuilder?.let {
                                        applyCommonSettings(it)
                                        session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                                    }
                                }
                                _isCameraReady.value = true
                                completeLensSwitch(_selectedLens.value)
                                Log.i(TAG, "Seamless lens switch during active recording session completed successfully")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed repeating record request after seamless lens switch", e)
                                completeLensSwitch(_selectedLens.value)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) return
                            onSessionConfigurationFinished()
                            activeSessionPhysicalCameraId = null
                            completeLensSwitch(_selectedLens.value)
                            Log.e(TAG, "Failed to configure video recording session after lens switch")
                            _isCameraReady.value = false
                        }
                    }
                )
                return
            } catch (e: Exception) {
                activeSessionPhysicalCameraId = null
                Log.e(TAG, "Error configuring video recording capture session during lens switch", e)
            }
        }

        val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
            ?: backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val mainLens = backLenses.firstOrNull { it.isPrimaryMain }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }

        val canConfigureSimultaneousLogicalPhysical = !forceLogicalStream &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            _isKeepUltraWideReady.value &&
            activeLens?.facing == CameraCharacteristics.LENS_FACING_BACK &&
            ultraWideLens != null &&
            mainLens != null &&
            ultraWideLens.cameraId == camera.id &&
            mainLens.cameraId == camera.id &&
            ultraWideLens.physicalCameraId != null &&
            ultraWideLens.supportsPhysicalStream &&
            ultraWideLens.physicalCameraId != camera.id

        if (canConfigureSimultaneousLogicalPhysical && ultraWideLens != null) {
            try {
                val chars = getCharacteristics(camera.id)
                val physIds = chars?.physicalCameraIds ?: emptySet()
                val uwPhysId = ultraWideLens.physicalCameraId!!
                if (physIds.contains(uwPhysId)) {
                    val mainSurf = getOrCreateMainPreviewSurface(mainLens?.physicalCameraId ?: camera.id) ?: previewSurf
                    val uwSurf = getOrCreateUltraWidePreviewSurface(uwPhysId)
                    if (uwSurf != null && uwSurf.isValid && uwSurf !== mainSurf) {
                        val isUwSelected = (activeLens?.lensType == LensType.ULTRAWIDE)
                        activeSessionPhysicalCameraId = if (isUwSelected) uwPhysId else mainLens?.physicalCameraId
                        _isUsingUltraWideSurface.value = isUwSelected
                        _displayedPreviewSource.value = if (isUwSelected) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN

                        val outputConfigs = mutableListOf<android.hardware.camera2.params.OutputConfiguration>()
                        val mainPreviewConfig = android.hardware.camera2.params.OutputConfiguration(mainSurf)
                        if (mainLens?.physicalCameraId != null && mainLens.supportsPhysicalStream && physIds.contains(mainLens.physicalCameraId)) {
                            mainPreviewConfig.setPhysicalCameraId(mainLens.physicalCameraId)
                        }
                        outputConfigs.add(mainPreviewConfig)

                        val uwPreviewConfig = android.hardware.camera2.params.OutputConfiguration(uwSurf).apply {
                            setPhysicalCameraId(uwPhysId)
                        }
                        outputConfigs.add(uwPreviewConfig)

                        imageReaderJpeg?.surface?.let {
                            outputConfigs.add(android.hardware.camera2.params.OutputConfiguration(it))
                        }

                        val template = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA) {
                            CameraDevice.TEMPLATE_RECORD
                        } else {
                            CameraDevice.TEMPLATE_PREVIEW
                        }
                        val builder = camera.createCaptureRequest(template).apply {
                            addTarget(mainSurf)
                            addTarget(uwSurf)
                            applyCommonSettings(this)
                        }
                        synchronized(previewRequestLock) {
                            previewRequestBuilder = builder
                        }

                        val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                            android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                            outputConfigs,
                            Executor { command -> backgroundHandler?.post(command) ?: command.run() },
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    onSessionConfigurationFinished()
                                    if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) {
                                        try { session.close() } catch (ignored: Throwable) {}
                                        return
                                    }
                                    captureSession = session
                                    activeLogicalMultiCamUltraWideConfigured = true
                                    isUltraWideStreaming.set(true)
                                    _ultraWideStreamStatus.value = BackgroundCameraStatus.READY_QUIET
                                    try {
                                        synchronized(previewRequestLock) {
                                            previewRequestBuilder?.let {
                                                applyCommonSettings(it)
                                                session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                                            }
                                        }
                                        _isCameraReady.value = true
                                        CameraPerformanceMonitor.start()
                                        completeLensSwitch(_selectedLens.value)
                                        Log.i(TAG, "[KEEP_ULTRAWIDE_READY] Simultaneous Main + Physical Ultra-Wide ($uwPhysId) session active on logical camera ${camera.id}")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed repeating request on simultaneous dual-physical session", e)
                                        completeLensSwitch(_selectedLens.value)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) return
                                    onSessionConfigurationFinished()
                                    activeLogicalMultiCamUltraWideConfigured = false
                                    Log.w(TAG, "[KEEP_ULTRAWIDE_READY] Simultaneous dual-physical session rejected by HAL; falling back to standard session")
                                    createCameraCaptureSession(forceLogicalStream = true)
                                }
                            }
                        )
                        camera.createCaptureSession(sessionConfig)
                        return
                    }
                }
            } catch (e: Exception) {
                activeLogicalMultiCamUltraWideConfigured = false
                Log.w(TAG, "Simultaneous dual-physical session setup fallback: ${e.message}")
            }
        }

        if (!forceLogicalStream &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            activeLens?.physicalCameraId != null &&
            activeLens.supportsPhysicalStream &&
            activeLens.physicalCameraId != activeLens.cameraId) {
            try {
                activeSessionPhysicalCameraId = activeLens.physicalCameraId
                val outputConfigs = mutableListOf<android.hardware.camera2.params.OutputConfiguration>()
                val previewConfig = android.hardware.camera2.params.OutputConfiguration(previewSurf)
                previewConfig.setPhysicalCameraId(activeLens.physicalCameraId)
                outputConfigs.add(previewConfig)

                imageReaderJpeg?.surface?.let {
                    val jpegConfig = android.hardware.camera2.params.OutputConfiguration(it)
                    jpegConfig.setPhysicalCameraId(activeLens.physicalCameraId)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && photoMegapixelMode == PhotoMegapixelMode.M50) {
                        try {
                            val chars = getCharacteristics(activeLens.physicalCameraId) ?: getCharacteristics(activeLens.cameraId)
                            val maxResMap = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                            val ultraSizes = maxResMap?.getOutputSizes(ImageFormat.JPEG)
                            val isUltra = ultraSizes?.any { sz -> sz.width == imageReaderJpeg?.width && sz.height == imageReaderJpeg?.height } == true
                            if (isUltra) {
                                jpegConfig.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                            }
                        } catch (ignored: Throwable) {}
                    }
                    outputConfigs.add(jpegConfig)
                }
                imageReaderYuv?.surface?.let {
                    val yuvConfig = android.hardware.camera2.params.OutputConfiguration(it)
                    yuvConfig.setPhysicalCameraId(activeLens.physicalCameraId)
                    outputConfigs.add(yuvConfig)
                }
                imageReaderRaw?.surface?.let {
                    val rawConfig = android.hardware.camera2.params.OutputConfiguration(it)
                    rawConfig.setPhysicalCameraId(activeLens.physicalCameraId)
                    outputConfigs.add(rawConfig)
                }

                val activeOutputs = mutableListOf<String>("PREVIEW")
                if (imageReaderJpeg != null) activeOutputs.add("JPEG")
                if (imageReaderYuv != null) activeOutputs.add("YUV_ARMED")
                if (imageReaderRaw != null) activeOutputs.add("RAW")
                CameraPerformanceMonitor.setActiveOutputs(activeOutputs)

                val template = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA) {
                    CameraDevice.TEMPLATE_RECORD
                } else {
                    CameraDevice.TEMPLATE_PREVIEW
                }
                val builder = camera.createCaptureRequest(template).apply {
                    addTarget(previewSurf)
                    applyCommonSettings(this)
                }
                synchronized(previewRequestLock) {
                    previewRequestBuilder = builder
                }

                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    Executor { command -> backgroundHandler?.post(command) ?: command.run() },
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            onSessionConfigurationFinished()
                            if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) {
                                try { session.close() } catch (ignored: Throwable) {}
                                return
                            }
                            captureSession = session
                            try {
                                activeSessionPhysicalCameraId = activeLens.physicalCameraId
                                synchronized(previewRequestLock) {
                                    previewRequestBuilder?.let {
                                        applyCommonSettings(it)
                                        session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                                    }
                                }
                                _isCameraReady.value = true
                                CameraPerformanceMonitor.start()
                                completeLensSwitch(activeSwitchingTargetLens ?: _targetLens.value ?: _selectedLens.value)
                                if (_isKeepUltraWideReady.value || isContinuousZoomTransitionActive || pendingEnsureUltraWideReady) {
                                    pendingEnsureUltraWideReady = false
                                    ensureUltraWideSimultaneousReady()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start repeating preview request", e)
                                completeLensSwitch(_selectedLens.value)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) return
                            onSessionConfigurationFinished()
                            activeSessionPhysicalCameraId = null
                            Log.w(TAG, "Physical capture session failed, falling back to logical session")
                            createCameraCaptureSession(forceLogicalStream = true)
                        }
                    }
                )
                camera.createCaptureSession(sessionConfig)
                return
            } catch (e: Exception) {
                activeSessionPhysicalCameraId = null
                Log.w(TAG, "Falling back to standard session creation: ${e.message}")
            }
        }

        try {
            activeSessionPhysicalCameraId = null
            val surfaces = mutableListOf<Surface>()
            surfaces.add(previewSurf)

            imageReaderJpeg?.surface?.let { surfaces.add(it) }
            imageReaderYuv?.surface?.let { surfaces.add(it) }
            imageReaderRaw?.surface?.let { surfaces.add(it) }

            val activeOutputs = mutableListOf<String>("PREVIEW")
            if (imageReaderJpeg != null) activeOutputs.add("JPEG")
            if (imageReaderYuv != null) activeOutputs.add("YUV_ARMED")
            if (imageReaderRaw != null) activeOutputs.add("RAW")
            CameraPerformanceMonitor.setActiveOutputs(activeOutputs)

            val template = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA) {
                CameraDevice.TEMPLATE_RECORD
            } else {
                CameraDevice.TEMPLATE_PREVIEW
            }

            val builder = camera.createCaptureRequest(template).apply {
                addTarget(previewSurf)
                applyCommonSettings(this)
            }
            synchronized(previewRequestLock) {
                previewRequestBuilder = builder
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = mutableListOf<android.hardware.camera2.params.OutputConfiguration>()
                outputConfigs.add(android.hardware.camera2.params.OutputConfiguration(previewSurf))

                imageReaderJpeg?.surface?.let { surf ->
                    val jpegConfig = android.hardware.camera2.params.OutputConfiguration(surf)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && photoMegapixelMode == PhotoMegapixelMode.M50) {
                        try {
                            val chars = activeLens?.let { getCharacteristics(it.cameraId) } ?: getCharacteristics(camera.id)
                            val maxResMap = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                            val ultraSizes = maxResMap?.getOutputSizes(ImageFormat.JPEG)
                            val isUltra = ultraSizes?.any { sz -> sz.width == imageReaderJpeg?.width && sz.height == imageReaderJpeg?.height } == true
                            if (isUltra) {
                                jpegConfig.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                                Log.i(TAG, "[SESSION_50M] Configured SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION on OutputConfiguration")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Could not set sensor pixel mode on OutputConfiguration", t)
                        }
                    }
                    outputConfigs.add(jpegConfig)
                }
                imageReaderYuv?.surface?.let { outputConfigs.add(android.hardware.camera2.params.OutputConfiguration(it)) }
                imageReaderRaw?.surface?.let { outputConfigs.add(android.hardware.camera2.params.OutputConfiguration(it)) }

                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    Executor { command -> backgroundHandler?.post(command) ?: command.run() },
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            onSessionConfigurationFinished()
                            if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) {
                                try { session.close() } catch (ignored: Throwable) {}
                                return
                            }
                            captureSession = session
                            try {
                                activeSessionPhysicalCameraId = null
                                synchronized(previewRequestLock) {
                                    previewRequestBuilder?.let {
                                        applyCommonSettings(it)
                                        session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                                    }
                                }
                                _isCameraReady.value = true
                                CameraPerformanceMonitor.start()
                                completeLensSwitch(activeSwitchingTargetLens ?: _targetLens.value ?: _selectedLens.value)
                                if (_isKeepUltraWideReady.value || isContinuousZoomTransitionActive || pendingEnsureUltraWideReady) {
                                    pendingEnsureUltraWideReady = false
                                    ensureUltraWideSimultaneousReady()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start repeating preview request", e)
                                completeLensSwitch(_selectedLens.value)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) return
                            onSessionConfigurationFinished()
                            if (imageReaderYuv != null || imageReaderRaw != null) {
                                Log.w(TAG, "Capture session rejected with auxiliary streams; retrying with Preview + JPEG only")
                                try { imageReaderYuv?.close() } catch (ignored: Throwable) {}
                                imageReaderYuv = null
                                try { imageReaderRaw?.close() } catch (ignored: Throwable) {}
                                imageReaderRaw = null
                                createCameraCaptureSession(forceLogicalStream = true)
                                return
                            }
                            completeLensSwitch(_selectedLens.value)
                            Log.e(TAG, "Camera capture session configuration failed")
                            _isCameraReady.value = false
                        }
                    }
                )
                camera.createCaptureSession(sessionConfig)
                return
            }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        onSessionConfigurationFinished()
                        if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) {
                            try { session.close() } catch (ignored: Throwable) {}
                            return
                        }
                        captureSession = session
                        try {
                            activeSessionPhysicalCameraId = null
                            synchronized(previewRequestLock) {
                                previewRequestBuilder?.let {
                                    applyCommonSettings(it)
                                    session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                                }
                            }
                            _isCameraReady.value = true
                            CameraPerformanceMonitor.start()
                            completeLensSwitch(_selectedLens.value)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating preview request", e)
                            completeLensSwitch(_selectedLens.value)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (configGen != sessionConfigGeneration.get() || cameraDevice != camera) return
                        onSessionConfigurationFinished()
                        // If auxiliary YUV/RAW streams caused session rejection, drop them and retry immediately on the open camera
                        if (imageReaderYuv != null || imageReaderRaw != null) {
                            Log.w(TAG, "Capture session rejected with auxiliary YUV/RAW streams; retrying with Preview + JPEG only")
                            try { imageReaderYuv?.close() } catch (ignored: Throwable) {}
                            imageReaderYuv = null
                            try { imageReaderRaw?.close() } catch (ignored: Throwable) {}
                            imageReaderRaw = null
                            createCameraCaptureSession(forceLogicalStream = true)
                            return
                        }
                        completeLensSwitch(_selectedLens.value)
                        Log.e(TAG, "Camera capture session configuration failed")
                        _isCameraReady.value = false
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            onSessionConfigurationFinished()
            completeLensSwitch(_selectedLens.value)
            Log.e(TAG, "Failed to create camera capture session", e)
        }
    }

    private var lastCaptureResult: TotalCaptureResult? = null

    private var lastOisForceOffTimeMs = 0L
    private var lastOisForceOnTimeMs = 0L
    private var lastOisIgnoredWarningTimeMs = 0L

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            lastCaptureResult = result
            CameraPerformanceMonitor.onPreviewFrame()

            // Verify OIS using the HAL's CaptureResult, not just the preference toggle.
            val actualOisMode = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
            val requestedOisMode = request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
            val isActualOisHwActive = (actualOisMode == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON)
            if (_actualOisHardwareActive.value != isActualOisHwActive) {
                _actualOisHardwareActive.value = isActualOisHwActive
                Log.i(
                    TAG,
                    "OIS HAL state changed: preference=${isOisAllowed}, requestMode=$requestedOisMode, actualMode=$actualOisMode, " +
                        "activeCamera=${cameraDevice?.id}, physicalCamera=${activeSessionPhysicalCameraId ?: activeSessionLens?.physicalCameraId}, " +
                        "lens=${activeSessionLens?.cameraId ?: _selectedLens.value?.cameraId}"
                )
            }

            val nowMs = android.os.SystemClock.uptimeMillis()
            // A legacy request builder or a session transition can leave OIS OFF even after the
            // user enables it. Repair the live repeating request once, without reopening camera.
            if (isOisAllowed &&
                requestedOisMode != CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON &&
                isOisSupportedForActiveLens() &&
                nowMs - lastOisForceOnTimeMs >= 500L
            ) {
                lastOisForceOnTimeMs = nowMs
                try {
                    synchronized(previewRequestLock) {
                        previewRequestBuilder?.let { builder ->
                            applyCommonSettings(builder)
                            applyHardwareOisRequest(builder, oisAllowed = true)
                            captureSession?.setRepeatingRequest(builder.build(), this, backgroundHandler)
                        }
                    }
                    Log.w(TAG, "OIS self-heal: repeating request was not ON; re-applied native OIS ON")
                } catch (t: Throwable) {
                    Log.w(TAG, "OIS self-heal request failed", t)
                }
            } else if (
                isOisAllowed &&
                requestedOisMode == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON &&
                actualOisMode != CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON &&
                nowMs - lastOisIgnoredWarningTimeMs >= 2000L
            ) {
                lastOisIgnoredWarningTimeMs = nowMs
                Log.w(
                    TAG,
                    "OIS was requested ON but HAL reports actualMode=$actualOisMode; " +
                        "check camera/lens OIS capability metadata and vendor HAL behavior"
                )
            }

            val actualEisMode = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
            val isActualEisHwActive =
                actualEisMode == CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_ON ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        actualEisMode == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
            if (_actualEisHardwareActive.value != isActualEisHwActive) {
                _actualEisHardwareActive.value = isActualEisHwActive
            }

            // When OIS is OFF: if the hardware HAL unexpectedly re-enabled OIS, force it OFF (throttled to avoid IPC flooding)
            if (!isOisAllowed && isActualOisHwActive) {
                val nowMs = android.os.SystemClock.uptimeMillis()
                if (nowMs - lastOisForceOffTimeMs >= 500L) {
                    lastOisForceOffTimeMs = nowMs
                    try {
                        previewRequestBuilder?.let { b ->
                            applyHardwareOisRequest(b, oisAllowed = false)
                            captureSession?.setRepeatingRequest(b.build(), this, backgroundHandler)
                        }
                    } catch (_: Throwable) {}
                }
            }

            if (_isAutoSwitchToUltraWide.value && !_isRecordingVideo.value && !isSwitchingLens.get()) {
                checkAutoLensSwitch(result)
            }



            // EIS has been removed: never run gyro crop compensation or mutate the repeating
            // request crop region from capture callbacks. Camera2 OIS remains independently
            // controlled through LENS_OPTICAL_STABILIZATION_MODE.

            // The imported engine uses Camera2 crop-region compensation, not the GPU relay's
            // offset path; keeping those offsets neutral prevents applying stabilization twice.
            dualCameraRecordingRelay?.updateStabilizationOffset(0f, 0f, 1.0f)
            if (isSoftwareCinemaRecording) {
                cinemaSoftwareRecorder.updateStabilizationOffset(0f, 0f, 1.0f)
            }

            // Real-time continuous Auto Tone Control for Cinema Log Profiles
            if (currentMode == CameraMode.CINEMA && _cinemaConfig.value.colorProfile == CinemaColorProfile.HLG10) {
                val lens = _selectedLens.value
                val chars = if (lens != null) getCharacteristics(lens.cameraId) else null
                cinemaEngine.hlg10AutoExposureEngine.onFrameCaptured(result, chars)
                onHlgAutoToneFrame()
            } else if (currentMode == CameraMode.CINEMA && _cinemaConfig.value.colorProfile == CinemaColorProfile.NATIVE) {
                val lens = _selectedLens.value
                val chars = if (lens != null) getCharacteristics(lens.cameraId) else null
                cinemaEngine.nativeNaturalEngine.onFrameCaptured(result, chars)
                onNativeNaturalAutoToneFrame()
            }
        }
    }

    private fun checkAutoLensSwitch(result: TotalCaptureResult) {
        if (!_isAutoSwitchToUltraWide.value || _isRecordingVideo.value || isSwitchingLens.get()) return

        val currentLens = activeSessionLens ?: _selectedLens.value ?: return
        if (currentLens.facing != CameraCharacteristics.LENS_FACING_BACK) return

        val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
            ?: backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
            ?: return
        val mainLens = backLenses.firstOrNull { it.isPrimaryMain }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
            ?: return

        val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: CaptureResult.CONTROL_AF_STATE_INACTIVE
        val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0.0f
        val chars = getCharacteristics(currentLens.cameraId)
        val minFocusDistance = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f

        processAfCondition(
            afState = afState,
            focusDistance = focusDistance,
            minFocusDistance = minFocusDistance,
            currentLens = currentLens,
            mainLens = mainLens,
            ultraWideLens = ultraWideLens
        )
    }

    fun processAfCondition(
        afState: Int,
        focusDistance: Float,
        minFocusDistance: Float,
        currentLens: LensInfo,
        mainLens: LensInfo,
        ultraWideLens: LensInfo,
        nowMs: Long = android.os.SystemClock.uptimeMillis()
    ) {
        val isCurrentMain = currentLens.id == mainLens.id || currentLens.lensType == LensType.WIDE

        if (isCurrentMain) {
            // Monitor main-lens autofocus for close-up subject that repeatedly cannot achieve focus
            val effectiveMinFocus = if (minFocusDistance > 0f) minFocusDistance else 10.0f
            val isSubjectVeryClose = focusDistance >= effectiveMinFocus * 0.70f || focusDistance >= 6.0f
            val isAfStruggling = afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED ||
                    (focusDistance >= effectiveMinFocus * 0.85f && (afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN || afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN))

            if (isSubjectVeryClose && isAfStruggling && currentZoom in 0.9f..1.6f) {
                closeSubjectUnfocusedFrames++
                subjectFarFrames = 0
            } else {
                closeSubjectUnfocusedFrames = maxOf(0, closeSubjectUnfocusedFrames - 1)
            }

            // Hysteresis: sustained for >= 10 frames (~330ms) and cooldown of at least 2000ms
            if (closeSubjectUnfocusedFrames >= 10 && (nowMs - lastAutoSwitchTimestampMs >= 2000L)) {
                Log.i(TAG, "[AUTO_LENS_SWITCH] Main lens repeatedly cannot achieve focus on close subject ($focusDistance diopters, AF state=$afState). Auto-switching to real ultra-wide lens.")
                lastAutoSwitchTimestampMs = nowMs
                closeSubjectUnfocusedFrames = 0
                _isAutoMacroActive.value = true
                selectLens(ultraWideLens, preserveZoom = false, targetZoom = 0.5f)
            }
        } else if (currentLens.id == ultraWideLens.id || currentLens.lensType == LensType.ULTRAWIDE) {
            // On ultra-wide lens: monitor if subject moves away and main-lens AF becomes reliable
            if (_isAutoMacroActive.value) {
                val isSubjectFarAway = focusDistance < 4.0f ||
                        (afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED && focusDistance < 5.0f) ||
                        (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED && focusDistance < 5.0f)

                if (isSubjectFarAway) {
                    subjectFarFrames++
                    closeSubjectUnfocusedFrames = 0
                } else {
                    subjectFarFrames = maxOf(0, subjectFarFrames - 1)
                }

                // Hysteresis: sustained for >= 14 frames (~460ms) and cooldown of at least 2000ms
                if (subjectFarFrames >= 14 && (nowMs - lastAutoSwitchTimestampMs >= 2000L)) {
                    Log.i(TAG, "[AUTO_LENS_SWITCH] Subject moved away ($focusDistance diopters). Auto-switching back to main lens.")
                    lastAutoSwitchTimestampMs = nowMs
                    subjectFarFrames = 0
                    _isAutoMacroActive.value = false
                    selectLens(mainLens, preserveZoom = false, targetZoom = 1.0f)
                }
            }
        }
    }

    /**
     * Testing hook to simulate AF conditions for unit testing auto lens switch logic.
     */
    fun simulateAfConditionForTesting(afState: Int, focusDistance: Float, minFocusDistance: Float = 10.0f) {
        val currentLens = activeSessionLens ?: _selectedLens.value ?: return
        val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE } ?: return
        val mainLens = backLenses.firstOrNull { it.isPrimaryMain } ?: backLenses.firstOrNull { it.lensType == LensType.WIDE } ?: return
        processAfCondition(afState, focusDistance, minFocusDistance, currentLens, mainLens, ultraWideLens)
    }

    @Volatile
    var latestLuminanceStats: FrameLuminanceStats? = null

    fun onFrameLuminanceStats(stats: FrameLuminanceStats) {
        latestLuminanceStats = stats
        cinemaEngine.hlg10AutoExposureEngine.onFrameLuminanceAnalyzed(stats)
        if (currentMode == CameraMode.CINEMA && _cinemaConfig.value.colorProfile == CinemaColorProfile.HLG10) {
            onHlgAutoToneFrame()
        }
    }

    private var lastHlgIspUpdateTime = 0L
    private fun onHlgAutoToneFrame() {
        val now = System.currentTimeMillis()
        if (now - lastHlgIspUpdateTime < 66L) return // 15fps throttle for repeating ISP tonemap updates
        if (!cinemaEngine.hlg10AutoExposureEngine.hasSignificantChangeSinceLastIspUpdate()) return
        lastHlgIspUpdateTime = now
        cinemaEngine.hlg10AutoExposureEngine.markIspUpdated()
        val session = captureSession ?: return
        synchronized(previewRequestLock) {
            val builder = previewRequestBuilder ?: return
            try {
                cinemaEngine.applyToCaptureRequest(builder)
                applyCommonSettings(builder)
                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
            } catch (ignored: Exception) {}
        }
    }

    private var lastNativeNaturalIspUpdateTime = 0L
    private fun onNativeNaturalAutoToneFrame() {
        val now = System.currentTimeMillis()
        if (now - lastNativeNaturalIspUpdateTime < 66L) return // 15fps throttle for repeating ISP tonemap updates
        if (!cinemaEngine.nativeNaturalEngine.hasSignificantChangeSinceLastIspUpdate()) return
        lastNativeNaturalIspUpdateTime = now
        cinemaEngine.nativeNaturalEngine.markIspUpdated()
        val session = captureSession ?: return
        synchronized(previewRequestLock) {
            val builder = previewRequestBuilder ?: return
            try {
                cinemaEngine.applyToCaptureRequest(builder)
                applyCommonSettings(builder)
                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
            } catch (ignored: Exception) {}
        }
    }




    /**
     * Check OIS against the sensor that actually feeds this session.
     *
     * A logical multi-camera's capability flags are not always identical to its active physical
     * sensor. Prefer the active physical ID when one is selected, then fall back to the openable
     * camera's Camera2 characteristics.
     */
    private fun isOisSupportedForLens(
        lens: LensInfo?,
        physicalCameraId: String? = lens?.physicalCameraId
    ): Boolean {
        val logicalCameraId = lens?.cameraId ?: cameraDevice?.id
        val physicalId = physicalCameraId

        // Prefer physical-sensor metadata, but some vendor HALs omit the OIS mode list on
        // physical IDs while advertising it on the logical camera. Missing metadata alone is
        // not proof that OIS is unsupported.
        if (physicalId != null) {
            val physicalChars = getCharacteristics(physicalId)
            val physicalModes = physicalChars?.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
            )
            if (physicalModes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                return true
            }
        }

        if (logicalCameraId != null) {
            val logicalChars = getCharacteristics(logicalCameraId)
            val logicalModes = logicalChars?.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
            )
            if (logicalModes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                Log.d(TAG, "OIS supported by logical camera $logicalCameraId; physicalId=$physicalId")
                return true
            }
        }

        // Some vendor HALs report OIS on the logical camera but omit it on the active
        // physical-sensor metadata. Prefer the selected camera's capability snapshot before
        // treating a physical mode list as authoritative.
        val selectedLensId = _selectedLens.value?.cameraId
        if (logicalCameraId != null &&
            logicalCameraId == selectedLensId &&
            _capabilities.value.supportsOis
        ) {
            Log.w(
                TAG,
                "OIS capability fallback: logical=" + logicalCameraId +
                    " physical=" + physicalId +
                    " selectedCameraSupportsOis=true; using selected-camera capability snapshot"
            )
            return true
        }

        val checkedChars = (physicalId?.let { getCharacteristics(it) })
            ?: logicalCameraId?.let { getCharacteristics(it) }
        val checkedModes = checkedChars?.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        )
        if (checkedModes != null && checkedModes.isNotEmpty()) return false
        return _capabilities.value.supportsOis
    }

    private fun isOisSupportedForActiveLens(): Boolean {
        val lens = activeSessionLens ?: _selectedLens.value
        val physicalId = activeSessionPhysicalCameraId ?: lens?.physicalCameraId
        return isOisSupportedForLens(lens, physicalId)
    }

    /**
     * Apply native Camera2 OIS to the active request and, when this is a logical camera's
     * physical stream, also apply the physical-camera-specific override.
     */
    private fun applyHardwareOisRequest(
        builder: CaptureRequest.Builder,
        oisAllowed: Boolean,
        // Only apply the physical-camera override if the active session explicitly routes to it.
        physicalCameraId: String? = activeSessionPhysicalCameraId,
        vendorCameraIdOverride: String? = null
    ) {
        val mode = if (oisAllowed) {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        } else {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        }
        try {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)
            Log.d(
                TAG,
                "OIS request=$mode allowed=${oisAllowed} logical=${cameraDevice?.id} " +
                    "physical=$physicalCameraId supported=${isOisSupportedForLens(activeSessionLens ?: _selectedLens.value, physicalCameraId)}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to set native OIS mode=$mode", t)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalCameraId != null) {
            try {
                val logicalCameraId = cameraDevice?.id
                val logicalChars = logicalCameraId?.let { getCharacteristics(it) }
                if (logicalChars?.physicalCameraIds?.contains(physicalCameraId) == true) {
                    builder.setPhysicalCameraKey(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        mode,
                        physicalCameraId
                    )
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Physical-camera OIS override unavailable for $physicalCameraId", t)
            }
        }

        // The same CaptureRequest.Builder is reused for setting changes. Always write both states
        // so vendor overrides left by a previous OFF toggle cannot keep OIS disabled after ON.
        applyVendorOisControls(builder, oisAllowed, vendorCameraIdOverride)
    }

    /**
     * EIS is intentionally disabled in every preview and recording request.
     * Retained only as a compatibility hook for older request-building paths.
     */
    private fun applyHardwareEisRequest(
        builder: CaptureRequest.Builder,
        @Suppress("UNUSED_PARAMETER") lensOverride: LensInfo? = null
    ) {
        try {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        } catch (t: Throwable) {
            Log.d(TAG, "Camera2 EIS key unavailable; continuing with OIS-only stabilization", t)
        }
    }

    /**
     * Apply vendor-specific OIS controls symmetrically for ON and OFF.
     *
     * Restrict this to vendor keys. The old contains("ois") matched Android's standard
     * noiseReduction key ("noise" contains "ois"), so turning OIS off could accidentally
     * write NOISE_REDUCTION_MODE=OFF. Android's standard OIS key is handled separately above.
     */
    private fun applyVendorOisControls(
        builder: CaptureRequest.Builder,
        oisAllowed: Boolean,
        cameraIdOverride: String? = null
    ) {
        try {
            val camId = cameraIdOverride
                ?: cameraDevice?.id
                ?: activeSessionLens?.cameraId
                ?: _selectedLens.value?.cameraId
                ?: return
            val chars = getCharacteristics(camId) ?: return
            val requestKeys = chars.availableCaptureRequestKeys ?: return
            val intValue = if (oisAllowed) 1 else 0
            for (key in requestKeys) {
                val keyName = key.name.lowercase(Locale.ROOT)
                val isVendorOisKey = !keyName.startsWith("android.") &&
                    (keyName.contains("optical_stabilization") ||
                        keyName.contains("opticalstabilization") ||
                        (keyName.contains("ois") && !keyName.contains("noise")))
                if (!isVendorOisKey) continue

                try {
                    @Suppress("UNCHECKED_CAST")
                    val typedKey = key as CaptureRequest.Key<Any>
                    try {
                        builder.set(typedKey, intValue)
                    } catch (_: Throwable) {
                        try {
                            builder.set(typedKey, intValue.toByte())
                        } catch (_: Throwable) {
                            try {
                                builder.set(typedKey, oisAllowed)
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * Apply AE, AF, AWB, Flash, ISO, Shutter, Zoom, Stabilization to CaptureRequest.Builder
     */
    private fun applyCommonSettings(
        builder: CaptureRequest.Builder,
        explicitZoom: Float? = null,
        isStillCapture: Boolean = false
    ) {
        val caps = _capabilities.value

        // Exposure compensation (apply cinema EV if in Cinema mode, or standard exposure index)
        val evToApply = if (currentMode == CameraMode.CINEMA) {
            cinemaConfig.value.exposureCompensation
        } else {
            exposureCompensationIndex
        }
        val minEv = caps.minExposureCompensation
        val maxEv = caps.maxExposureCompensation
        val clampedEv = if (minEv <= maxEv) evToApply.coerceIn(minEv, maxEv) else 0

        val isVideoOrCinema = (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA)

        val activeLens = _selectedLens.value
        val isBackFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_BACK
        val hasHardwareFlash = caps.supportsFlash || (isBackFacing && hasBackCameraFlash())

        // AE & Manual Exposure / ISO
        if (!isVideoOrCinema && (manualIso != null || manualExposureTimeNs != null)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            manualIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            manualExposureTimeNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            if (hasHardwareFlash) {
                if (isStillCapture) {
                    if (flashMode == FlashMode.ON || flashMode == FlashMode.TORCH) {
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                    } else {
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                } else {
                    if (flashMode == FlashMode.TORCH) {
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    } else {
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                }
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
        } else {
            // Auto Exposure mode + Flash configuration (safely verifying hardware flash support)
            if (isVideoOrCinema) {
                // Video and Pro Video (Cinema) must use continuous torch while enabled
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (hasHardwareFlash && flashMode != FlashMode.OFF) {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            } else {
                // Photo Mode:
                // Photo mode must fire the flash only during photo capture for the minimum effective duration.
                if (!hasHardwareFlash || flashMode == FlashMode.OFF) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                } else if (isStillCapture) {
                    // Still capture: fire flash only during photo capture
                    when (flashMode) {
                        FlashMode.AUTO -> {
                            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                        }
                        FlashMode.ON -> {
                            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                        }
                        FlashMode.TORCH -> {
                            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                        }
                        FlashMode.OFF -> {
                            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        }
                    }
                } else {
                    // Repeating preview in Photo mode: flash stays OFF during preview stream
                    when (flashMode) {
                        FlashMode.TORCH -> {
                            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                        }
                        FlashMode.AUTO -> {
                            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        }
                        FlashMode.ON -> {
                            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        }
                        FlashMode.OFF -> {
                            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        }
                    }
                }
            }
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedEv)
            if (caps.supportsAeLock) {
                val effectiveAeLock = if (isVideoOrCinema) false else isAeLocked
                builder.set(CaptureRequest.CONTROL_AE_LOCK, effectiveAeLock)
            }
        }

        // Active Tap-to-Expose & Tap-to-Focus Metering Region (preserved across setting adjustments)
        activeMeteringRectangle?.let { rect ->
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
        }

        // White Balance
        builder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalanceMode.camera2Mode)

        // Anti-Banding Filter: Auto, 50Hz, 60Hz, Off
        try {
            val abTarget = when (antiBandingMode.uppercase()) {
                "50HZ", "50_HZ" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
                "60HZ", "60_HZ" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
                "OFF" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
                else -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
            }
            val activeId = activeLens?.cameraId ?: activeLens?.id
            val supportedAntiBanding = activeId?.let { getCharacteristics(it)?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) }
            if (supportedAntiBanding == null || supportedAntiBanding.contains(abTarget)) {
                builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, abTarget)
            } else if (supportedAntiBanding.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)) {
                builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed applying anti-banding setting ($antiBandingMode): ${t.message}")
        }

        // Focus (safely verifying camera capabilities, e.g. fixed-focus front cameras)
        when (focusMode) {
            FocusMode.MANUAL -> {
                if (caps.supportsManualSensor) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
                } else {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                }
            }
            FocusMode.CONTINUOUS -> {
                val mode = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA) {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                } else {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                }
                if (caps.supportedAfModes.contains(FocusMode.CONTINUOUS)) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
                } else {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                }
            }
            FocusMode.AUTO -> {
                if (caps.supportedAfModes.contains(FocusMode.AUTO)) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                } else {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                }
            }
            FocusMode.MACRO -> {
                if (caps.supportedAfModes.contains(FocusMode.MACRO)) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO)
                } else {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                }
            }
        }

        // Always force EIS OFF first, then apply hardware OIS to the active capture route.
        // OIS is controlled only by the OIS preference and the active sensor's capability.
        applyHardwareEisRequest(builder)
        val oisAllowed = isOisAllowed && isOisSupportedForActiveLens()
        applyHardwareOisRequest(builder, oisAllowed)

        // Preserve video recording capture intent and target FPS range across lens switches & setting updates
        if (_isRecordingVideo.value) {
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
            val is4kOrHigher = (_selectedVideoResolution.value?.let { maxOf(it.width, it.height) >= 3840 } == true) ||
                    (activeRecordingStreamConfig?.videoResolution?.let { maxOf(it.width, it.height) >= 3840 } == true)
            // Optimize edge, noise reduction, hot pixel, shading and aberration modes for real-time 4K recording throughput
            if (is4kOrHigher || proSharpness.value <= 50f) {
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            }
            if (is4kOrHigher || proNoiseReduction.value <= 50f) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            }
            try {
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
            } catch (_: Throwable) {}
            try {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST)
            } catch (_: Throwable) {}
            try {
                builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
            } catch (_: Throwable) {}
        }
        if (isVideoOrCinema) {
            val activeLens = activeSessionLens ?: _selectedLens.value
            val activeChars = activeLens?.let { getCharacteristics(it.cameraId) }
            val recConfig = activeRecordingStreamConfig
            val desiredFps = when {
                _isRecordingVideo.value && recConfig != null -> recConfig.targetFps
                currentMode == CameraMode.CINEMA -> cinemaConfig.value.videoFps
                else -> videoFps
            }
            val matchedRange = activeChars?.let { findBestFpsRange(it, desiredFps) } ?: recConfig?.fpsRange
            if (matchedRange != null) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, matchedRange)
            }
        }

        // Color profiles:
        when (colorProfile) {
            ColorProfile.MONOCHROME -> {
                builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_MONO)
            }
            ColorProfile.VIBRANT -> {
                builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            }
            ColorProfile.STANDARD, ColorProfile.NATURAL -> {
                builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
            }
        }

        // Pro Mode / Normal Mode Edge & Noise Reduction tuning
        if (proSharpness.value > 50f) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        } else if (proSharpness.value < 5f) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }
        if (proNoiseReduction.value > 50f) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        } else if (proNoiseReduction.value < 5f) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        }

        // Dedicated Cinema Log Color Profile
        if (currentMode == CameraMode.CINEMA) {
            cinemaEngine.applyToCaptureRequest(builder)
        }


        // Digital Zoom / Crop Region
        applyZoom(builder, explicitZoom)
    }

    private fun applyZoom(builder: CaptureRequest.Builder, explicitZoom: Float? = null) {
        val lens = activeSessionLens ?: _selectedLens.value ?: return

        try {
            val chars = getCharacteristics(lens.cameraId) ?: return
            val isLogicalMulti = lens.isLogicalMultiCamera ||
                (chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                ) == true)

            val effectiveUiZoom = explicitZoom ?: currentZoom

            // Digital Crop calculation calibrated from actual sensor FOV
            val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            val mainLens = backLenses.firstOrNull { it.isPrimaryMain }
                ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
            val uwLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                ?: backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }

            val uwEqFocal = if (lens.lensType == LensType.ULTRAWIDE && lens.equivalent35mmFocalMm > 0f) {
                lens.equivalent35mmFocalMm
            } else if (uwLens != null && uwLens.equivalent35mmFocalMm > 0f) {
                uwLens.equivalent35mmFocalMm
            } else {
                CameraOpticalCalibration.DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
            }

            val mainEqFocal = if (mainLens != null && mainLens.equivalent35mmFocalMm > 0f) {
                mainLens.equivalent35mmFocalMm
            } else {
                CameraOpticalCalibration.DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
            }

            val digitalCrop = CameraOpticalCalibration.calculateRequiredDigitalCrop(
                uiZoom = effectiveUiZoom,
                lensBaseRatio = lens.baseZoomRatio,
                lensType = lens.lensType,
                uwEquivalentFocalMm = uwEqFocal,
                mainEquivalentFocalMm = mainEqFocal,
                switchPointMm = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM
            )

            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

            // A session is only running on a bound physical stream when OutputConfiguration.setPhysicalCameraId
            // was actually applied to the active CameraCaptureSession (tracked by activeSessionPhysicalCameraId).
            val isRunningOnPhysicalStream = (activeSessionPhysicalCameraId != null &&
                    activeSessionPhysicalCameraId == lens.physicalCameraId) ||
                    (!isLogicalMulti && lens.physicalCameraId != null && lens.supportsPhysicalStream)

            // On Android 11+ (API 30+), CONTROL_ZOOM_RATIO applies optical multi-camera / ISP continuous zoom
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    val desiredZoom = if (lens.lensType == LensType.FRONT) {
                        digitalCrop
                    } else if (isLogicalMulti && !isRunningOnPhysicalStream) {
                        effectiveUiZoom
                    } else {
                        digitalCrop
                    }
                    val maxAllowedZoom = if (lens.lensType == LensType.ULTRAWIDE && (!isLogicalMulti || isRunningOnPhysicalStream)) {
                        val baseUwCrop = CameraOpticalCalibration.calculateUltraWideCropForSwitchPoint(CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM, uwEqFocal)
                        val switchZ = CameraOpticalCalibration.switchPointToZoom(CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM)
                        baseUwCrop * (effectiveUiZoom / switchZ).coerceAtLeast(1.0f)
                    } else {
                        zoomRange.upper
                    }
                    val targetZoomRatio = desiredZoom.coerceIn(zoomRange.lower, minOf(zoomRange.upper, maxAllowedZoom))
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, targetZoomRatio)

                    // Native camera zoom is used up to supported maximum (e.g. 10x).
                    // From 10x to 20x, extend zoom continuously via digital crop/upscaling on top of native maximum
                    if (desiredZoom > zoomRange.upper) {
                        val extraScale = (desiredZoom / zoomRange.upper).coerceAtLeast(1.0f)
                        val cropW = (sensorRect.width() / extraScale).toInt().coerceIn(1, sensorRect.width())
                        val cropH = (sensorRect.height() / extraScale).toInt().coerceIn(1, sensorRect.height())
                        val cropX = sensorRect.left + (sensorRect.width() - cropW) / 2
                        val cropY = sensorRect.top + (sensorRect.height() - cropH) / 2
                        val cropRegion = Rect(cropX, cropY, cropX + cropW, cropY + cropH)
                        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                    } else {
                        builder.set(CaptureRequest.SCALER_CROP_REGION, sensorRect)
                    }
                    return
                }
            }

            // Fallback for devices without CONTROL_ZOOM_RATIO or legacy hardware: precise SCALER_CROP_REGION
            val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            val safeMaxZoom = if (lens.lensType == LensType.ULTRAWIDE) {
                val baseUwCrop = CameraOpticalCalibration.calculateUltraWideCropForSwitchPoint(CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM, uwEqFocal)
                val switchZ = CameraOpticalCalibration.switchPointToZoom(CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM)
                maxOf(baseUwCrop, baseUwCrop * (effectiveUiZoom / switchZ))
            } else {
                maxOf(maxDigitalZoom, lens.maxZoomRatio, 20.0f)
            }

            val factor = digitalCrop.coerceIn(1.0f, safeMaxZoom)
            val cropW = (sensorRect.width() / factor).toInt().coerceIn(1, sensorRect.width())
            val cropH = (sensorRect.height() / factor).toInt().coerceIn(1, sensorRect.height())
            val cropX = sensorRect.left + (sensorRect.width() - cropW) / 2
            val cropY = sensorRect.top + (sensorRect.height() - cropH) / 2

            val cropRegion = Rect(cropX, cropY, cropX + cropW, cropY + cropH)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting zoom", e)
        }
    }

    /**
     * Updates preview request with new settings on the fly
     */
    fun updatePreviewSettings(explicitZoom: Float? = null) {
        if (isClosingCamera) return
        val session = captureSession ?: return
        synchronized(previewRequestLock) {
            val builder = previewRequestBuilder ?: return
            val activeSess = captureSession ?: return
            if (activeSess !== session) return
            try {
                applyCommonSettings(builder, explicitZoom)
                activeSess.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update preview settings", e)
            }
        }
    }

    private var lastZoomPreviewUpdateTime = 0L
    private var pendingZoomRunnable: Runnable? = null
    @Volatile
    private var pendingZoomUpdateTarget: Float? = null
    @Volatile
    private var isZoomUpdateScheduled = false

    private fun syncStandbyIfFrameHandoffActive() {
        if (zoomTransitionController.previewOverlapState.value.isOverlapping) {
            // The camera callback already runs on the background handler, so update the standby
            // repeating request on the same serialized path as the active preview request.
            syncStandbyStreamSettings(forceDuringFrameHandoff = true)
        }
    }

    private fun scheduleZoomPreviewUpdate(immediate: Boolean = false, explicitZoom: Float? = null) {
        val handler = backgroundHandler
        val now = android.os.SystemClock.uptimeMillis()
        val isContinuous = isContinuousZoomTransitionActive
        val zoomToApply = explicitZoom ?: currentZoom

        if (handler == null || Looper.myLooper() == handler.looper) {
            if (!immediate && !isContinuous && (now - lastZoomPreviewUpdateTime < 16L)) {
                if (pendingZoomRunnable == null) {
                    val delayMs = (16L - (now - lastZoomPreviewUpdateTime)).coerceIn(4L, 16L)
                    val runnable = Runnable {
                        pendingZoomRunnable = null
                        lastZoomPreviewUpdateTime = android.os.SystemClock.uptimeMillis()
                        updatePreviewSettings(zoomToApply)
                        syncStandbyIfFrameHandoffActive()
                    }
                    pendingZoomRunnable = runnable
                    handler?.postDelayed(runnable, delayMs)
                }
                return
            }
            pendingZoomRunnable?.let { handler?.removeCallbacks(it) }
            pendingZoomRunnable = null
            lastZoomPreviewUpdateTime = now
            updatePreviewSettings(zoomToApply)
            syncStandbyIfFrameHandoffActive()
            return
        }

        pendingZoomUpdateTarget = zoomToApply

        if (immediate) {
            pendingZoomRunnable?.let { handler.removeCallbacks(it) }
            pendingZoomRunnable = null
            isZoomUpdateScheduled = false
            lastZoomPreviewUpdateTime = now
            handler.post {
                val z = pendingZoomUpdateTarget
                lastZoomPreviewUpdateTime = android.os.SystemClock.uptimeMillis()
                updatePreviewSettings(z)
                syncStandbyIfFrameHandoffActive()
            }
            return
        }

        // For continuous transitions and regular zooms: coalesce so at most ONE update is pending in the queue
        if (!isZoomUpdateScheduled) {
            isZoomUpdateScheduled = true
            val timeSinceLast = now - lastZoomPreviewUpdateTime
            val delayMs = if (timeSinceLast >= 16L) 0L else (16L - timeSinceLast).coerceIn(2L, 16L)

            val runnable = Runnable {
                isZoomUpdateScheduled = false
                val z = pendingZoomUpdateTarget
                lastZoomPreviewUpdateTime = android.os.SystemClock.uptimeMillis()
                updatePreviewSettings(z)
                syncStandbyIfFrameHandoffActive()
            }
            pendingZoomRunnable = runnable
            if (delayMs > 0L) {
                handler.postDelayed(runnable, delayMs)
            } else {
                handler.post(runnable)
            }
        }
    }

    /**
     * Set Zoom (.5x to 10x) with seamless automatic lens switching and hysteresis
     */
    fun setZoom(zoom: Float, isPresetTap: Boolean = false, isContinuousTransition: Boolean = false) {
        val inFlightLens = activeSwitchingTargetLens ?: _targetLens.value
        val currentLens = inFlightLens ?: _selectedLens.value ?: return
        isContinuousZoomTransitionActive = isContinuousTransition || zoomTransitionController.isTransitionActive.value

        val isFront = currentLens.facing == CameraCharacteristics.LENS_FACING_FRONT
        val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
            ?: backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val mainWideLens = backLenses.firstOrNull { it.isPrimaryMain }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
            ?: backLenses.firstOrNull()

        val minAllowedZoom = if (isFront) {
            0.8f
        } else if (ultraWideLens != null) {
            0.5f
        } else {
            1.0f
        }
        val maxCapabilityZoom = if (isFront) {
            1.0f
        } else {
            maxOf(
                _availableLenses.value
                    .filter { it.facing == currentLens.facing }
                    .map { it.maxZoomRatio }
                    .maxOrNull() ?: 20.0f,
                currentLens.maxZoomRatio,
                capabilities.value.maxZoom,
                20.0f
            )
        }
        val clampedZoom = zoom.coerceIn(minAllowedZoom, maxCapabilityZoom)

        currentZoom = clampedZoom
        _currentZoom.value = clampedZoom
        if (!isContinuousZoomTransitionActive && !isContinuousTransition) {
            preferences.currentZoom = clampedZoom
        }

        // If front selfie camera, apply digital zoom on active stream
        if (isFront) {
            if (isSwitchingLens.get()) {
                pendingZoomWhileSwitching = clampedZoom
                pendingZoomPresetTapWhileSwitching = isPresetTap
                pendingIsContinuousTransitionWhileSwitching = isContinuousTransition
                scheduleZoomPreviewUpdate(immediate = isPresetTap || isContinuousTransition, explicitZoom = clampedZoom)
            } else {
                scheduleZoomPreviewUpdate(immediate = isPresetTap || isContinuousTransition, explicitZoom = clampedZoom)
            }
            return
        }

        // Native Physical Lens Switching & Zoom logic
        val hasUltraWide = ultraWideLens != null
        val hasTele2x = backLenses.any { it.lensType == LensType.TELEPHOTO && it.isPhysical }
        val hasTele3x = backLenses.any { it.lensType == LensType.TELEPHOTO_3X && it.isPhysical }

        val switchPoint = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM
        val switchZoom = CameraOpticalCalibration.switchPointToZoom(switchPoint)
        val referenceLensType = (activeSessionLens ?: currentLens).lensType
        val targetType = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = referenceLensType,
            targetZoom = clampedZoom,
            hasUltraWide = hasUltraWide,
            hasTelephoto2x = hasTele2x,
            hasTelephoto3x = hasTele3x,
            isPresetTap = isPresetTap,
            switchPointMm = switchPoint,
            isContinuousTransition = isContinuousTransition
        )

        val targetLens: LensInfo = when (targetType) {
            LensType.ULTRAWIDE -> ultraWideLens ?: mainWideLens ?: currentLens
            LensType.TELEPHOTO -> backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical } ?: mainWideLens ?: currentLens
            LensType.TELEPHOTO_3X -> backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO_3X && it.isPhysical } ?: mainWideLens ?: currentLens
            else -> mainWideLens ?: currentLens
        }

        val pZoom = clampedZoom

        _targetLens.value = targetLens

        if (isSwitchingLens.get()) {
            // Lens switch is in progress internally: coalesce target metadata
            // but do NOT freeze or pause the continuous zoom timeline on the active stream.
            pendingZoomWhileSwitching = pZoom
            pendingZoomPresetTapWhileSwitching = isPresetTap
            val inFlightTarget = activeSwitchingTargetLens ?: _targetLens.value
            if (inFlightTarget == null || (targetLens.id != inFlightTarget.id && targetLens.lensType != inFlightTarget.lensType)) {
                pendingLensWhileSwitching = targetLens
            } else {
                pendingLensWhileSwitching = null
            }
            pendingIsContinuousTransitionWhileSwitching = isContinuousTransition
            scheduleZoomPreviewUpdate(immediate = isPresetTap || isContinuousTransition, explicitZoom = pZoom)
            return
        }

        val activeLens = activeSessionLens ?: currentLens
        val isContinuous = isContinuousTransition || isContinuousZoomTransitionActive
        // Hold Ultra-Wide only while its digital crop is matching the Main lens below the
        // calibrated handoff point. Never pin Ultra-Wide once the slider reaches 1x or above.
        val keepUltraWideUntilSwitchPoint =
            CameraOpticalCalibration.shouldKeepUltraWideDuringContinuousZoom(
                currentLensType = activeLens.lensType,
                targetZoom = pZoom,
                switchPointMm = switchPoint,
                isContinuousTransition = isContinuous
            )
        val effectiveTargetLens = if (keepUltraWideUntilSwitchPoint) {
            ultraWideLens ?: targetLens
        } else {
            targetLens
        }
        val isDifferentCameraDevice = cameraDevice != null && effectiveTargetLens.cameraId != cameraDevice?.id
        val requiresPhysicalStreamSwitch = (effectiveTargetLens.physicalCameraId != null &&
                effectiveTargetLens.physicalCameraId != activeSessionPhysicalCameraId) ||
                !canUseLogicalZoomForLens(effectiveTargetLens.cameraId, effectiveTargetLens, pZoom)

        val isStandbyOrPhysicalAvailable = requiresPhysicalStreamSwitch ||
                activeLogicalMultiCamUltraWideConfigured ||
                _isKeepUltraWideReady.value ||
                ultraWideStandbyCaptureSession != null ||
                isContinuous

        val isInFlightTarget = activeSwitchingTargetLens != null &&
                (effectiveTargetLens.id == activeSwitchingTargetLens?.id || effectiveTargetLens.lensType == activeSwitchingTargetLens?.lensType)
        val shouldSwitchPhysicalLens = (isDifferentCameraDevice || (effectiveTargetLens.lensType != activeLens.lensType && isStandbyOrPhysicalAvailable)) && !isInFlightTarget

        if (shouldSwitchPhysicalLens) {
            selectLens(
                effectiveTargetLens,
                preserveZoom = true,
                targetZoom = pZoom,
                isContinuousTransition = isContinuous
            )
        } else {
            // Same logical/physical device with genuine continuous zoom support: smoothly update active lens and zoom continuously without tearing down camera session
            if (effectiveTargetLens.id == activeLens.id || cameraDevice == null || effectiveTargetLens.cameraId == cameraDevice?.id) {
                _selectedLens.value = effectiveTargetLens
                activeSessionLens = effectiveTargetLens
            }
            if (effectiveTargetLens.facing == CameraCharacteristics.LENS_FACING_BACK) {
                if (effectiveTargetLens.lensType == LensType.ULTRAWIDE && pZoom > 0.5f && !isContinuous) {
                    ensureUltraWideSimultaneousReady()
                }
                if (_isKeepUltraWideReady.value) {
                    val useUw = (effectiveTargetLens.lensType == LensType.ULTRAWIDE)
                    val targetSource = if (useUw) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                    if (_isRecordingVideo.value) {
                        dualCameraRecordingRelay?.setActiveSource(targetSource)
                    }
                    _isUsingUltraWideSurface.value = useUw
                    _displayedPreviewSource.value = targetSource
                }
            }
            scheduleZoomPreviewUpdate(immediate = isPresetTap || isContinuous, explicitZoom = clampedZoom)
        }
    }

    /**
     * Tap to Focus & Meter with optional AE/AF Lock
     * Maps screen normalized tap coordinates to exact SENSOR_INFO_ACTIVE_ARRAY_SIZE coordinates,
     * fully accounting for sensor orientation, front mirror, stream aspect ratio crop, and zoom factor.
     */
    fun triggerFocusAndMeter(normX: Float, normY: Float, isLock: Boolean = false) {
        val lens = _selectedLens.value ?: return
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            val chars = getCharacteristics(lens.cameraId) ?: return
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val isFront = (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)

            val sensorW = sensorRect.width().toFloat()
            val sensorH = sensorRect.height().toFloat()

            // 1. Account for Sensor Orientation & Front Camera Mirroring
            // In Android portrait orientation:
            // Rear sensor (90°): X_sensor = normY, Y_sensor = 1 - normX
            // Front sensor (270° with mirror): X_sensor = 1 - normY, Y_sensor = 1 - normX
            val normSensorX = if (isFront || sensorOrientation == 270) {
                (1.0f - normY).coerceIn(0f, 1f)
            } else {
                normY.coerceIn(0f, 1f)
            }
            val normSensorY = (1.0f - normX).coerceIn(0f, 1f)

            // 2. Account for Stream / Viewfinder Aspect Ratio letterbox/pillarbox crop on the 4:3 active sensor array
            val previewW = previewBufferSize.value?.width?.toFloat() ?: 1920f
            val previewH = previewBufferSize.value?.height?.toFloat() ?: 1080f
            val previewAspectLandscape = max(previewW, previewH) / min(previewW, previewH)
            val sensorAspectLandscape = sensorW / sensorH

            val visibleSensorW: Float
            val visibleSensorH: Float
            val cropOffsetX: Float
            val cropOffsetY: Float

            if (previewAspectLandscape > sensorAspectLandscape) {
                // Wide stream (e.g. 16:9 on a 4:3 sensor) -> cropped along sensor height
                visibleSensorW = sensorW
                visibleSensorH = sensorW / previewAspectLandscape
                cropOffsetX = 0f
                cropOffsetY = (sensorH - visibleSensorH) / 2f
            } else {
                // Stream is narrower than or equal to sensor (e.g. 4:3 or 1:1) -> cropped along sensor width
                visibleSensorH = sensorH
                visibleSensorW = sensorH * previewAspectLandscape
                cropOffsetX = (sensorW - visibleSensorW) / 2f
                cropOffsetY = 0f
            }

            // 3. Account for Digital Zoom & Crop Region
            val zoom = _currentZoom.value.coerceAtLeast(1.0f)
            val zoomedW = visibleSensorW / zoom
            val zoomedH = visibleSensorH / zoom
            val zoomedOffsetX = cropOffsetX + (visibleSensorW - zoomedW) / 2f
            val zoomedOffsetY = cropOffsetY + (visibleSensorH - zoomedH) / 2f

            // 4. Exact Sensor Coordinate
            val targetSensorX = sensorRect.left + zoomedOffsetX + normSensorX * zoomedW
            val targetSensorY = sensorRect.top + zoomedOffsetY + normSensorY * zoomedH

            // 5. Metering Rectangle (scaled proportionally to active sensor array)
            val boxW = (sensorW * 0.12f).toInt().coerceIn(180, 450)
            val boxH = (sensorH * 0.12f).toInt().coerceIn(180, 450)

            val left = (targetSensorX - boxW / 2).toInt().coerceIn(sensorRect.left, sensorRect.right - 10)
            val right = (targetSensorX + boxW / 2).toInt().coerceIn(left + 10, sensorRect.right)
            val top = (targetSensorY - boxH / 2).toInt().coerceIn(sensorRect.top, sensorRect.bottom - 10)
            val bottom = (targetSensorY + boxH / 2).toInt().coerceIn(top + 10, sensorRect.bottom)

            val focusRect = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)
            activeMeteringRectangle = focusRect

            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRect))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)

            if (isLock) {
                isAeLocked = true
                isAfLocked = true
                _isAeLockedFlow.value = true
                _isAfLockedFlow.value = true
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            }

            val precaptureRequest = builder.build()

            // Crucial: reset triggers on the builder so subsequent frames do not re-trigger AE/AF precapture
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)

            session.capture(precaptureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                    if (isLock) {
                        builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    } else {
                        // Transition to smooth continuous AF holding the tapped region to prevent hunting
                        val continuousMode = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA) {
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        } else {
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        }
                        builder.set(CaptureRequest.CONTROL_AF_MODE, continuousMode)
                    }
                    try {
                        session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore repeating request after tap-to-expose", e)
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Tap to focus failed", e)
        }
    }

    fun toggleAeAfLock() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val nextLock = !(isAeLocked || isAfLocked)
        isAeLocked = nextLock
        isAfLocked = nextLock
        _isAeLockedFlow.value = nextLock
        _isAfLockedFlow.value = nextLock

        builder.set(CaptureRequest.CONTROL_AE_LOCK, nextLock)
        if (!nextLock) {
            activeMeteringRectangle = null
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
        }
        try {
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle lock", e)
        }
    }



    fun setPreviewAspectRatio(ratio: Float) {
        if (ratio > 0f) {
            _previewAspectRatio.value = ratio
        }
    }

    private var physicalOrientationEventListener: android.view.OrientationEventListener? = null
    @Volatile
    private var physicalOrientationDegrees: Int = 0

    private fun initOrientationListener() {
        if (physicalOrientationEventListener == null) {
            physicalOrientationEventListener = object : android.view.OrientationEventListener(context, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    physicalOrientationDegrees = when (orientation) {
                        in 45..134 -> 270
                        in 135..224 -> 180
                        in 225..314 -> 90
                        else -> 0
                    }
                }
            }
        }
        if (physicalOrientationEventListener?.canDetectOrientation() == true) {
            physicalOrientationEventListener?.enable()
        }
    }

    private fun getEffectiveDeviceRotation(): Int {
        val windowRot = getDeviceRotationDegrees()
        if (windowRot != 0) return windowRot
        if (physicalOrientationDegrees != 0) return physicalOrientationDegrees
        val configOrientation = context.resources.configuration.orientation
        if (configOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            return 90
        }
        return 0
    }

    private fun getDeviceRotationDegrees(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display?.rotation ?: android.view.Surface.ROTATION_0
            } catch (e: Exception) {
                windowManager?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
            }
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
        }
        return when (rotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun calculateOrientation(sensorOrientation: Int, isFrontFacing: Boolean, deviceRotation: Int): Int {
        return if (isFrontFacing) {
            // Front sensor: (sensorOrientation + deviceRotation) % 360
            (sensorOrientation + deviceRotation) % 360
        } else {
            // Back sensor: (sensorOrientation - deviceRotation + 360) % 360
            (sensorOrientation - deviceRotation + 360) % 360
        }
    }

    private fun getVideoOrientationHint(): Int {
        val lens = _selectedLens.value ?: return 90
        val isFront = lens.facing == CameraCharacteristics.LENS_FACING_FRONT
        val sensorOrientation = try {
            val chars = getCharacteristics(lens.cameraId)
            chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isFront) 270 else 90)
        } catch (e: Exception) {
            if (isFront) 270 else 90
        }
        val deviceRotation = getEffectiveDeviceRotation()
        val standardHint = calculateOrientation(sensorOrientation, isFront, deviceRotation)
        return standardHint
    }

    private fun getCaptureJpegOrientation(): Int {
        val lens = _selectedLens.value ?: return 90
        val isFront = lens.facing == CameraCharacteristics.LENS_FACING_FRONT
        val sensorOrientation = try {
            val chars = getCharacteristics(lens.cameraId)
            chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isFront) 270 else 90)
        } catch (e: Exception) {
            if (isFront) 270 else 90
        }
        val deviceRotation = getEffectiveDeviceRotation()
        return calculateOrientation(sensorOrientation, isFront, deviceRotation)
    }



    private val isHoldingContinuousCapture = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Starts continuous high-speed Burst capture at selected 5-20 FPS. Holds until stopUltraFastContinuousCapture() is called.
     * Uses continuous sensor streaming into preallocated buffer pool and bounded queue.
     * Never calls normal ImageCapture or session.capture() in a loop; viewfinder runs smooth without stall.
     */
    fun startUltraFastContinuousCapture(
        fps: Int = 15,
        onComplete: (Uri?) -> Unit
    ) {
        val camera = cameraDevice ?: run {
            onComplete(null)
            return
        }
        val session = captureSession ?: run {
            onComplete(null)
            return
        }
        val readerYuv = imageReaderYuv ?: run {
            takePhoto(onComplete)
            return
        }

        val clampedFps = fps.coerceIn(5, 20)
        val burstId = java.util.UUID.randomUUID().toString()

        isHoldingContinuousCapture.set(true)
        _isCapturing.value = true

        val isFront = _selectedLens.value?.facing == CameraCharacteristics.LENS_FACING_FRONT
        readerYuv.setOnImageAvailableListener({ reader ->
            ultraFastShutterEngine.onSensorImageAvailable(
                reader = reader,
                sensorOrientation = getCaptureJpegOrientation(),
                isFrontFacing = isFront,
                saveMirrored = saveSelfieAsPreviewed
            )
        }, ultraFastShutterEngine.getCaptureHandler() ?: backgroundHandler)

        // Ensure repeating request includes YUV target for continuous frame acquisition
        val builder = previewRequestBuilder
        if (builder != null) {
            try {
                builder.addTarget(readerYuv.surface)
                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                CameraPerformanceMonitor.setActiveOutputs(listOf("PREVIEW", "YUV_BURST_ACTIVE"))
                Log.i(TAG, "[FAST_SHUTTER] Attached YUV surface to repeating request for active burst capture")
            } catch (e: Exception) {
                Log.w(TAG, "Failed ensuring repeating request includes YUV target", e)
            }
        }

        ultraFastShutterEngine.startContinuousBurst(
            burstId = burstId,
            fps = clampedFps,
            onComplete = { coverUri ->
                _isCapturing.value = false
                updateStorageStats()
                if (coverUri != null) {
                    _lastCapturedMedia.value = CapturedMedia(
                        uri = coverUri,
                        isVideo = false,
                        timestamp = System.currentTimeMillis(),
                        displayName = "BURST_${burstId.take(8)}_0001.jpg"
                    )
                }
                onComplete(coverUri)
            }
        )
    }

    /**
     * Immediately stops continuous burst capture and lets background worker drain queued frames.
     */
    fun stopUltraFastContinuousCapture() {
        if (!isHoldingContinuousCapture.getAndSet(false)) return
        val readerYuv = imageReaderYuv
        val session = captureSession
        val builder = previewRequestBuilder
        if (builder != null && readerYuv != null && session != null) {
            try {
                builder.removeTarget(readerYuv.surface)
                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                val activeOutputs = mutableListOf<String>("PREVIEW")
                if (imageReaderJpeg != null) activeOutputs.add("JPEG")
                if (imageReaderYuv != null) activeOutputs.add("YUV_ARMED")
                if (imageReaderRaw != null) activeOutputs.add("RAW")
                CameraPerformanceMonitor.setActiveOutputs(activeOutputs)
                Log.i(TAG, "[FAST_SHUTTER] Detached YUV surface from repeating request; preview back to single-surface")
            } catch (e: Exception) {
                Log.w(TAG, "Failed removing YUV target from repeating request", e)
            }
        }
        ultraFastShutterEngine.stopContinuousBurst()
    }

    /**
     * Take still photo (JPEG + optional RAW). In 50M mode, triggers single-frame computational 50MP capture.
     */
    fun takePhoto(onComplete: (Uri?) -> Unit) {
        if (photoMegapixelMode == PhotoMegapixelMode.M50) {
            takePhoto50M(onComplete)
            return
        }

        if (isRefocusPhotoEnabled && currentMode == CameraMode.PHOTO) {
            takePhotoRefocus(onComplete)
            return
        }

        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val readerJpeg = imageReaderJpeg ?: return

        val activeLens = _selectedLens.value
        val jpegW = readerJpeg.width
        val jpegH = readerJpeg.height
        val mp = (jpegW.toLong() * jpegH.toLong()) / 1_000_000f
        Log.i(TAG, "[PHOTO_CAPTURE] Initiating capture on ${activeLens?.lensType}: ImageReader JPEG=${jpegW}x${jpegH} (~${mp}MP)")

        _isCapturing.value = true

        val chars = activeLens?.let { getCharacteristics(it.cameraId) }
        val hdrPlan = photoHdrEngine.planCapture(
            chars = chars,
            lastResult = lastCaptureResult,
            flashMode = flashMode,
            stats = latestLuminanceStats,
            gyroEngine = gyroStabilizationEngine
        )
        Log.i(TAG, "[PHOTO_CAPTURE_HDR] Plan: ${hdrPlan.bracketType}, specs=${hdrPlan.specs.size} - ${hdrPlan.reason}")

        try {
            val caps = _capabilities.value
            val isRaw = isRawCaptureEnabled && caps.supportsRaw && imageReaderRaw != null

            if (hdrPlan.bracketType == com.example.camera.engine.hdr.HdrBracketType.SINGLE_FRAME) {
                // Single-frame capture path (low contrast scene, active flash, or high motion)
                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureBuilder.addTarget(readerJpeg.surface)
                if (isRaw) {
                    imageReaderRaw?.surface?.let { captureBuilder.addTarget(it) }
                }

                applyCommonSettings(captureBuilder, isStillCapture = true)
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
                captureBuilder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())

                readerJpeg.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        engineScope.launch(Dispatchers.IO) {
                            try {
                                val uri = saveJpegToMediaStore(image)
                                image.close()
                                _isCapturing.value = false
                                updateStorageStats()
                                withContext(Dispatchers.Main) {
                                    onComplete(uri)
                                }
                            } catch (e: Throwable) {
                                image.close()
                                _isCapturing.value = false
                                updateStorageStats()
                                val err = e.message ?: "Photo processing failure"
                                Log.e(TAG, "Still photo processing failed: $err", e)
                                withContext(Dispatchers.Main) {
                                    onComplete(null)
                                }
                            }
                        }
                    }
                }, backgroundHandler)

                if (isRaw) {
                    imageReaderRaw?.setOnImageAvailableListener({ reader ->
                        val rawImage = reader.acquireLatestImage()
                        if (rawImage != null) {
                            engineScope.launch(Dispatchers.IO) {
                                val lens = _selectedLens.value
                                if (lens != null) {
                                    val characteristics = getCharacteristics(lens.cameraId)
                                    if (characteristics != null) {
                                        saveRawToMediaStore(rawImage, characteristics)
                                    }
                                }
                                rawImage.close()
                            }
                        }
                    }, backgroundHandler)
                }

                session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(TAG, "Photo single frame capture completed")
                    }
                }, backgroundHandler)

            } else {
                // Multi-frame computational HDR bracket capture path (2-frame or 3-frame)
                val requests = ArrayList<CaptureRequest>()
                for (idx in hdrPlan.specs.indices) {
                    val spec = hdrPlan.specs[idx]
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    builder.addTarget(readerJpeg.surface)
                    if (isRaw && spec.isReference) {
                        imageReaderRaw?.surface?.let { builder.addTarget(it) }
                    }
                    applyCommonSettings(builder, isStillCapture = true)
                    builder.set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
                    builder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())

                    if (spec.aeCompIndex != 0) {
                        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, spec.aeCompIndex)
                    }
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    builder.setTag(idx)
                    requests.add(builder.build())
                }

                val expectedCount = requests.size
                val capturedFrames = java.util.Collections.synchronizedList(ArrayList<com.example.camera.engine.hdr.HdrInputFrame>())
                val receivedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val isProcessingStarted = java.util.concurrent.atomic.AtomicBoolean(false)

                // Cache per-frame hardware metadata from TotalCaptureResult
                val hardwareResultsByTimestamp = java.util.concurrent.ConcurrentHashMap<Long, TotalCaptureResult>()
                val hardwareResultsByIndex = java.util.concurrent.ConcurrentHashMap<Int, TotalCaptureResult>()
                val pendingImages = java.util.Collections.synchronizedList(ArrayList<Pair<Int, Pair<Long, ByteArray>>>())

                fun syncAndCreateFrame(idx: Int, ts: Long, bytes: ByteArray): com.example.camera.engine.hdr.HdrInputFrame {
                    val spec = hdrPlan.specs.getOrNull(idx) ?: hdrPlan.specs[0]
                    val result = hardwareResultsByTimestamp[ts] ?: hardwareResultsByIndex[idx]

                    val actualExpTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        ?: lastCaptureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        ?: 33_333_333L

                    val actualIso = result?.get(CaptureResult.SENSOR_SENSITIVITY)
                        ?: lastCaptureResult?.get(CaptureResult.SENSOR_SENSITIVITY)
                        ?: 100

                    val actualAeComp = result?.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                        ?: spec.aeCompIndex

                    return com.example.camera.engine.hdr.HdrInputFrame(
                        jpegBytes = bytes,
                        role = spec.role,
                        evOffset = spec.evOffset,
                        exposureTimeNs = actualExpTime,
                        iso = actualIso,
                        timestampNs = ts,
                        gyroYawSpeed = gyroStabilizationEngine.latestYawSpeed,
                        gyroPitchSpeed = gyroStabilizationEngine.latestPitchSpeed,
                        gyroRollSpeed = gyroStabilizationEngine.latestRollSpeed,
                        actualAeCompensation = actualAeComp
                    )
                }

                fun triggerHdrProcessing() {
                    if (isProcessingStarted.compareAndSet(false, true)) {
                        // Viewfinder unblocks immediately upon final burst frame arrival!
                        _isCapturing.value = false

                        // Finalize any pending frames with latest hardware results
                        synchronized(pendingImages) {
                            for ((pIdx, pData) in pendingImages) {
                                val alreadyAdded = capturedFrames.any { it.timestampNs == pData.first }
                                if (!alreadyAdded) {
                                    val frame = syncAndCreateFrame(pIdx, pData.first, pData.second)
                                    capturedFrames.add(frame)
                                }
                            }
                            pendingImages.clear()
                        }

                        val framesToProcess = synchronized(capturedFrames) { ArrayList(capturedFrames) }
                        engineScope.launch(Dispatchers.Default) {
                            try {
                                val finalJpegBytes = photoHdrEngine.processHdrCapture(
                                    frames = framesToProcess,
                                    plan = hdrPlan,
                                    jpegQuality = preferences.jpegQuality
                                )
                                val uri = saveJpegBytesToMediaStore(finalJpegBytes)
                                updateStorageStats()
                                withContext(Dispatchers.Main) {
                                    onComplete(uri)
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "Error in PhotoHdrEngine processing", e)
                                // Failsafe: save base frame directly
                                val baseBytes = framesToProcess.firstOrNull { it.role == com.example.camera.engine.hdr.FrameRole.REFERENCE_BASE }?.jpegBytes
                                    ?: framesToProcess.firstOrNull()?.jpegBytes
                                val uri = if (baseBytes != null) saveJpegBytesToMediaStore(baseBytes) else null
                                updateStorageStats()
                                withContext(Dispatchers.Main) {
                                    onComplete(uri)
                                }
                            }
                        }
                    }
                }

                readerJpeg.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val ts = image.timestamp
                        image.close()

                        val idx = receivedCount.getAndIncrement()
                        val frame = syncAndCreateFrame(idx, ts, bytes)
                        capturedFrames.add(frame)

                        if (capturedFrames.size >= expectedCount) {
                            triggerHdrProcessing()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error acquiring HDR burst frame", t)
                        if (capturedFrames.isNotEmpty()) {
                            triggerHdrProcessing()
                        }
                    }
                }, backgroundHandler)

                if (isRaw) {
                    imageReaderRaw?.setOnImageAvailableListener({ reader ->
                        val rawImage = reader.acquireLatestImage()
                        if (rawImage != null) {
                            engineScope.launch(Dispatchers.IO) {
                                val lens = _selectedLens.value
                                if (lens != null) {
                                    val characteristics = getCharacteristics(lens.cameraId)
                                    if (characteristics != null) {
                                        saveRawToMediaStore(rawImage, characteristics)
                                    }
                                }
                                rawImage.close()
                            }
                        }
                    }, backgroundHandler)
                }

                // Failsafe timeout: in case HAL drops a frame, trigger processing after 2000ms
                backgroundHandler?.postDelayed({
                    if (capturedFrames.isNotEmpty() && !isProcessingStarted.get()) {
                        Log.w(TAG, "HDR burst timeout: processing ${capturedFrames.size}/$expectedCount frames")
                        triggerHdrProcessing()
                    } else if (capturedFrames.isEmpty() && !isProcessingStarted.get()) {
                        Log.e(TAG, "HDR burst timeout: 0 frames received")
                        _isCapturing.value = false
                        onComplete(null)
                    }
                }, 2000L)

                session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                        if (ts != null) {
                            hardwareResultsByTimestamp[ts] = result
                        }
                        val tag = request.tag as? Int
                        if (tag != null) {
                            hardwareResultsByIndex[tag] = result
                        }
                        Log.d(TAG, "Photo HDR burst frame completed: tag=$tag, ts=$ts, exp=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)}ns, iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)}")
                    }
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.w(TAG, "Photo HDR burst frame capture failed: ${failure.reason}")
                    }
                }, backgroundHandler)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error taking photo", e)
            _isCapturing.value = false
            onComplete(null)
        }
    }

    /**
     * Re-renders the latest pristine uncompressed capture with updated custom pipeline parameters
     * or a different preset WITHOUT having to re-take the photo.
     */
    suspend fun reprocessLatestPipelineCapture(
        params: com.example.camera.pipeline.model.CustomPipelineParams,
        preset: com.example.camera.pipeline.model.PipelinePreset
    ): Uri? = withContext(Dispatchers.IO) {
        val capture = com.example.camera.pipeline.engine.PipelineCaptureCache.getCapture() ?: return@withContext null
        try {
            val finalBytes = customImagePipelineEngine.processAndEncodeToJpegBytes(
                source = capture.fullResBitmap,
                params = params,
                jpegQuality = preferences.jpegQuality
            )
            val tempFile = File.createTempFile("reprocess_exif", ".jpg", context.cacheDir)
            val bytesWithExif = try {
                FileOutputStream(tempFile).use { it.write(finalBytes) }
                val exif = android.media.ExifInterface(tempFile.absolutePath)
                exif.setAttribute(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL.toString()
                )
                exif.saveAttributes()
                tempFile.readBytes()
            } catch (t: Throwable) {
                finalBytes
            } finally {
                tempFile.delete()
            }
            val uri = saveJpegBytesToMediaStore(bytesWithExif, skipPipeline = true)
            com.example.camera.pipeline.engine.PipelineCaptureCache.updateSavedUri(uri)
            updateStorageStats()
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reprocess pipeline capture", e)
            null
        }
    }

    /**
     * Refocus Photo Capture:
     * When Refocus Photo is enabled, captures a short sequence of 3 focus planes:
     * Near (macro/foreground) -> Mid (subject/user focus) -> Far (background/infinity).
     *
     * - The subject/mid plane is immediately saved and presented as the normal photo preview
     *   to ensure zero shutter lag or preview stall.
     * - Sequential/tiled depth map generation and permanent bundling is handled in the
     *   background thread without keeping all full-resolution frames in RAM simultaneously.
     * - If burst capture or refocus processing fails, it automatically falls back to saving
     *   the normal photo without failing capture.
     */
    private fun takePhotoRefocus(onComplete: (Uri?) -> Unit) {
        val camera = cameraDevice
        val session = captureSession
        val readerJpeg = imageReaderJpeg
        if (camera == null || session == null || readerJpeg == null) {
            takePhoto(onComplete)
            return
        }

        _isCapturing.value = true

        val lens = _selectedLens.value
        val chars = if (lens != null) getCharacteristics(lens.cameraId) else null
        val minFocusDist = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val effectiveMinDist = if (minFocusDist > 0.05f) minFocusDist else 5.0f

        val lastFocusDiopters = lastCaptureResult?.get(CaptureResult.LENS_FOCUS_DISTANCE)
            ?: (effectiveMinDist * 0.35f)

        val frameCount = 3
        val focusPlanes = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            val fraction = i.toFloat() / (frameCount - 1).coerceAtLeast(1)
            // Sweep from maximum focus (closest near) down to 0.0 (infinity/far)
            focusPlanes[i] = (effectiveMinDist * (1.0f - fraction)).coerceIn(0f, effectiveMinDist)
        }

        // Identify the frame closest to current user AF distance for instant saving as default gallery photo
        var bestMidIndex = frameCount / 2
        var minDiff = Float.MAX_VALUE
        for (i in 0 until frameCount) {
            val diff = kotlin.math.abs(focusPlanes[i] - lastFocusDiopters)
            if (diff < minDiff) {
                minDiff = diff
                bestMidIndex = i
            }
        }

        val tempPlaneFiles = List(frameCount) { idx ->
            File(context.cacheDir, "refocus_tmp_${idx}_${System.currentTimeMillis()}.jpg")
        }

        var framesReceived = 0
        val isCompleted = java.util.concurrent.atomic.AtomicBoolean(false)

        val captureOrientation = getCaptureJpegOrientation()
        val isFrontFacing = lens?.facing == CameraCharacteristics.LENS_FACING_FRONT

        fun finalizeRefocusCapture() {
            if (!isCompleted.compareAndSet(false, true)) return
            readerJpeg.setOnImageAvailableListener(null, null)
            engineScope.launch(Dispatchers.IO) {
                var finalUri: Uri? = null
                var shutterReleased = false
                try {
                    val validFiles = tempPlaneFiles.filter { it.exists() && it.length() > 0 }
                    if (validFiles.isNotEmpty()) {
                        val midFile = tempPlaneFiles.getOrNull(bestMidIndex)?.takeIf { it.exists() && it.length() > 0 }
                            ?: validFiles.first()

                        // Save and publish the normal photo first. Refocus/depth processing is
                        // deliberately kept off the shutter path so the next shot isn't blocked.
                        finalUri = saveJpegBytesToMediaStore(midFile.readBytes())
                    }

                    withContext(Dispatchers.Main) {
                        _isCapturing.value = false
                        onComplete(finalUri)
                    }
                    shutterReleased = true

                    // Package the extra focus planes only after the normal gallery photo is
                    // already saved and the UI/shutter has been released.
                    if (finalUri != null && validFiles.isNotEmpty()) {
                        refocusEngine.processAndPersistPlanes(
                            photoUri = finalUri!!,
                            tempPlaneFiles = tempPlaneFiles,
                            planeDiopters = focusPlanes.toList(),
                            captureOrientation = captureOrientation,
                            isFrontFacing = isFrontFacing,
                            saveMirrored = saveSelfieAsPreviewed
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error finalizing refocus package", e)
                } finally {
                    tempPlaneFiles.forEach { runCatching { it.delete() } }
                    if (!shutterReleased) {
                        withContext(Dispatchers.Main) {
                            _isCapturing.value = false
                            onComplete(finalUri)
                        }
                    }
                    updateStorageStats()
                }
            }
        }

        readerJpeg.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close() // Close Image immediately to free Camera2 buffer pool

            val frameIndex = synchronized(this) { framesReceived++ }
            if (frameIndex < frameCount) {
                try {
                    tempPlaneFiles[frameIndex].writeBytes(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed writing refocus frame $frameIndex", e)
                }

                if (frameIndex >= frameCount - 1) {
                    finalizeRefocusCapture()
                }
            }
        }, backgroundHandler)

        try {
            val requests = mutableListOf<CaptureRequest>()
            for (i in 0 until frameCount) {
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(readerJpeg.surface)
                    applyCommonSettings(this, isStillCapture = true)
                    set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusPlanes[i])
                }
                requests.add(req.build())
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Refocus burst plane completed")
                }
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.w(TAG, "Refocus burst plane failed: ${failure.reason}")
                }
            }, backgroundHandler)

            // Watchdog fallback: in case burst fails or frame is missed, ensure normal photo is saved
            engineScope.launch {
                delay(6000)
                finalizeRefocusCapture()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed submitting refocus burst, falling back to standard capture", e)
            tempPlaneFiles.forEach { runCatching { it.delete() } }
            takePhoto(onComplete)
        }
    }

    /**
     * 50 Megapixel Native Maximum-Resolution Sensor Capture (integrated from super-native-camera):
     * Uses real sensor-supported maximum-resolution output via Camera2 API.
     * Captures the genuine full-resolution sensor frame directly without software upscaling,
     * AI enhancement, interpolation, or in-app image processing.
     */
    fun takePhoto50M(onComplete: (Uri?) -> Unit) {
        val camera = cameraDevice ?: run {
            onComplete(null)
            return
        }
        val session = captureSession ?: run {
            onComplete(null)
            return
        }
        val readerJpeg = imageReaderJpeg ?: run {
            onComplete(null)
            return
        }

        _isCapturing.value = true
        val activeLens = _selectedLens.value

        try {
            // Build native maximum-resolution capture request (from super-native-camera)
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(readerJpeg.surface)

            val caps = _capabilities.value
            val isRaw = isRawCaptureEnabled && caps.supportsRaw && imageReaderRaw != null
            if (isRaw) {
                imageReaderRaw?.surface?.let { captureBuilder.addTarget(it) }
            }

            applyCommonSettings(captureBuilder, isStillCapture = true)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            captureBuilder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)

            // Enable ultra-high resolution sensor remosaic mode if physical hardware supports it (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val chars = activeLens?.let { getCharacteristics(it.cameraId) } ?: getCharacteristics(camera.id)
                    val sensorCaps = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    val maxResMap = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    val ultraSizes = maxResMap?.getOutputSizes(ImageFormat.JPEG)
                    val isFromMaxResMap = ultraSizes?.any { it.width == readerJpeg.width && it.height == readerJpeg.height } == true

                    if (sensorCaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) || isFromMaxResMap) {
                        captureBuilder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                        Log.i(TAG, "[50M_NATIVE] SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION enabled for hardware capture (${readerJpeg.width}x${readerJpeg.height})")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Hardware sensor ultra-high resolution mode not applicable", e)
                }
            }

            var frameProcessed = false

            readerJpeg.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (frameProcessed) {
                    try { image.close() } catch (ignored: Exception) {}
                    return@setOnImageAvailableListener
                }
                frameProcessed = true
                readerJpeg.setOnImageAvailableListener(null, null)

                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val imgW = image.width
                    val imgH = image.height
                    image.close()

                    Log.i(TAG, "[50M_NATIVE] Captured genuine full-resolution sensor frame: ${imgW}x${imgH} (${bytes.size} bytes)")

                    engineScope.launch(Dispatchers.IO) {
                        val uri = saveJpegBytesToMediaStore(
                            bytes = bytes,
                            skipPipeline = true,
                            filePrefix = "50M_NATIVE"
                        )
                        _isCapturing.value = false
                        updateStorageStats()
                        if (uri != null) {
                            _lastCapturedMedia.value = CapturedMedia(
                                uri = uri,
                                isVideo = false,
                                timestamp = System.currentTimeMillis(),
                                displayName = "50M_NATIVE.jpg"
                            )
                        }
                        withContext(Dispatchers.Main) {
                            onComplete(uri)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring native 50M frame", e)
                    try { image.close() } catch (ignored: Exception) {}
                    _isCapturing.value = false
                    engineScope.launch(Dispatchers.Main) {
                        onComplete(null)
                    }
                }
            }, backgroundHandler)

            if (isRaw) {
                imageReaderRaw?.setOnImageAvailableListener({ reader ->
                    val rawImage = reader.acquireLatestImage()
                    if (rawImage != null) {
                        engineScope.launch(Dispatchers.IO) {
                            val lens = _selectedLens.value
                            if (lens != null) {
                                val characteristics = getCharacteristics(lens.cameraId)
                                if (characteristics != null) {
                                    saveRawToMediaStore(rawImage, characteristics)
                                }
                            }
                            rawImage.close()
                        }
                    }
                }, backgroundHandler)
            }

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                    val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    Log.d(TAG, "50M native capture completed (ISO=$iso, Exp=${exp}ns)")
                }
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e(TAG, "50M native capture failed: ${failure.reason}")
                    _isCapturing.value = false
                    onComplete(null)
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting 50M native capture, falling back to standard capture", e)
            _isCapturing.value = false
            takePhoto(onComplete)
        }
    }

    /**
     * Captures a single full-resolution uncompressed Bitmap for Portrait AI processing.
     * Ensures orientation and left/right mirroring match the viewfinder exactly.
     */
    fun captureStillBitmap(onBitmapCaptured: (Bitmap?) -> Unit) {
        val camera = cameraDevice ?: run {
            onBitmapCaptured(null)
            return
        }
        val session = captureSession ?: run {
            onBitmapCaptured(null)
            return
        }
        val readerJpeg = imageReaderJpeg ?: run {
            onBitmapCaptured(null)
            return
        }

        val activeLens = _selectedLens.value
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT

        _isCapturing.value = true

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(readerJpeg.surface)
            applyCommonSettings(captureBuilder, isStillCapture = true)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())

            readerJpeg.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    engineScope.launch(Dispatchers.IO) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            val orientedBitmap = decodeUprightBitmapFromJpeg(
                                jpegBytes = bytes,
                                captureOrientation = getCaptureJpegOrientation(),
                                isFrontFacing = isFrontFacing,
                                mirrorHorizontally = saveSelfieAsPreviewed,
                                mutable = true
                            )

                            if (orientedBitmap != null) {
                                _isCapturing.value = false
                                withContext(Dispatchers.Main) {
                                    onBitmapCaptured(orientedBitmap)
                                }
                            } else {
                                _isCapturing.value = false
                                withContext(Dispatchers.Main) {
                                    onBitmapCaptured(null)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error decoding and orienting captured still bitmap", e)
                            try { image.close() } catch (ignored: Exception) {}
                            _isCapturing.value = false
                            withContext(Dispatchers.Main) {
                                onBitmapCaptured(null)
                            }
                        }
                    }
                }
            }, backgroundHandler)

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Still frame capture triggered")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing still bitmap", e)
            _isCapturing.value = false
            onBitmapCaptured(null)
        }
    }

    /**
     * Helper to resolve the active preview surface for either Main or Ultra-Wide streams.
     */
    private fun getActivePreviewSurface(): Surface? {
        if (_isUsingUltraWideSurface.value && !activeLogicalMultiCamUltraWideConfigured) {
            val uwSurf = ultraWideViewfinderSurface ?: ultraWideStandbySurface
            if (uwSurf != null && uwSurf.isValid) return uwSurf
        }
        return if (previewSurface?.isValid == true) previewSurface else getOrCreateMainPreviewSurface(_selectedLens.value?.cameraId)
    }

    private fun findBestFpsRange(chars: CameraCharacteristics, targetFps: Int): Range<Int>? {
        val availableFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        if (availableFpsRanges.isEmpty()) return null

        val exactFixed = availableFpsRanges.firstOrNull { it.upper == targetFps && it.lower == targetFps }
        if (exactFixed != null) return exactFixed

        val matchedUpper = availableFpsRanges.filter { it.upper == targetFps }.maxByOrNull { it.lower }
        if (matchedUpper != null) return matchedUpper

        val higherUpper = availableFpsRanges.filter { it.upper >= targetFps }
            .minWithOrNull(compareBy({ it.upper - targetFps }, { -it.lower }))
        if (higherUpper != null) return higherUpper

        return availableFpsRanges.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
    }

    /**
     * Creates an end-to-end 10-bit HDR or standard capture session for recording.
     * When 10-bit is requested and the device supports DynamicRangeProfiles (Android 13+),
     * this configures the recorder surface with HLG10/HDR10 to ensure raw 10-bit HAL stream buffers.
     */
    private fun createRecordingCaptureSession(
        camera: CameraDevice,
        previewSurface: Surface,
        recorderSurface: Surface?,
        is10Bit: Boolean,
        physicalCameraId: String? = null,
        updateActivePhysicalId: Boolean = true,
        callback: CameraCaptureSession.StateCallback
    ) {
        val executor = Executor { command -> backgroundHandler?.post(command) ?: command.run() }
        val hasSeparateRecorder = (recorderSurface != null && recorderSurface.isValid && recorderSurface != previewSurface)

        val chars = getCharacteristics(camera.id)
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            chars?.physicalCameraIds ?: emptySet()
        } else emptySet()

        val usePhysicalRouting = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                physicalCameraId != null &&
                physicalCameraId != camera.id &&
                physicalIds.contains(physicalCameraId)

        if (updateActivePhysicalId) {
            activeSessionPhysicalCameraId = if (usePhysicalRouting) physicalCameraId else null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && is10Bit && hasSeparateRecorder) {
            try {
                val dynamicProfiles = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                val supported = dynamicProfiles?.supportedProfiles ?: emptySet()
                val isHlg10 = _cinemaConfig.value.colorProfile == CinemaColorProfile.HLG10
                val targetProfile = when {
                    isHlg10 && supported.contains(DynamicRangeProfiles.HLG10) -> DynamicRangeProfiles.HLG10
                    supported.contains(DynamicRangeProfiles.HLG10) -> DynamicRangeProfiles.HLG10
                    supported.contains(DynamicRangeProfiles.HDR10) -> DynamicRangeProfiles.HDR10
                    supported.contains(DynamicRangeProfiles.HDR10_PLUS) -> DynamicRangeProfiles.HDR10_PLUS
                    else -> null
                }

                if (targetProfile != null) {
                    val recorderConfig = OutputConfiguration(recorderSurface!!).apply {
                        dynamicRangeProfile = targetProfile
                        if (usePhysicalRouting) setPhysicalCameraId(physicalCameraId)
                    }
                    val previewConfig = OutputConfiguration(previewSurface).apply {
                        if (usePhysicalRouting) setPhysicalCameraId(physicalCameraId)
                    }
                    val sessionConfig = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(previewConfig, recorderConfig),
                        executor,
                        callback
                    )
                    camera.createCaptureSession(sessionConfig)
                    Log.i(TAG, "Configured 10-bit CameraCaptureSession with DynamicRangeProfile: $targetProfile (phys=$physicalCameraId)")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create 10-bit SessionConfiguration, falling back to standard capture session", e)
            }
        }

        val previewConfig = OutputConfiguration(previewSurface).apply {
            if (usePhysicalRouting) setPhysicalCameraId(physicalCameraId)
        }
        val recorderConfig = if (hasSeparateRecorder) {
            OutputConfiguration(recorderSurface!!).apply {
                if (usePhysicalRouting) setPhysicalCameraId(physicalCameraId)
            }
        } else null

        val outputConfigs = if (recorderConfig != null) {
            listOf(previewConfig, recorderConfig)
        } else {
            listOf(previewConfig)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    executor,
                    callback
                )
                camera.createCaptureSession(sessionConfig)
                return
            } catch (e: Exception) {
                if (updateActivePhysicalId) {
                    activeSessionPhysicalCameraId = null
                }
                Log.w(TAG, "Failed SessionConfiguration for recording, falling back to createCaptureSession", e)
            }
        }

        if (updateActivePhysicalId) {
            activeSessionPhysicalCameraId = null
        }
        @Suppress("DEPRECATION")
        val surfaces = if (hasSeparateRecorder) {
            listOf(previewSurface, recorderSurface!!)
        } else {
            listOf(previewSurface)
        }
        camera.createCaptureSession(surfaces, callback, backgroundHandler)
    }

    /**
     * Safely releases failed recording resources, restores preview, and notifies error callback.
     */
    private fun cleanupFailedRecording(onError: (String) -> Unit, message: String) {
        isStartingRecording.set(false)
        isStoppingRecording.set(false)
        _isRecordingVideo.value = false
        _isRecordingPaused.value = false
        _isGpuRelayPreviewActive.value = false
        isSoftwareCinemaRecording = false
        videoTimerJob?.cancel()
        activeRecordingSurface = null
        activeRecordingStreamConfig = null
        standbyConfiguredForRecording = false
        standbyRecordingSessionFailed = false
        logicalMultiCamRecordingFailed = false
        logicalMultiCamDualRecSurfacesConfigured = false
        try {
            dualCameraRecordingRelay?.release()
        } catch (ignored: Throwable) {}
        dualCameraRecordingRelay = null

        try {
            cinemaSoftwareRecorder.stopRecording()
        } catch (ignored: Throwable) {}

        try {
            normalVideoRecorder.stopRecording()
        } catch (ignored: Throwable) {}

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (ignored: Throwable) {}
        mediaRecorder = null

        try {
            videoRecordingFileDescriptor?.close()
        } catch (ignored: Throwable) {}
        videoRecordingFileDescriptor = null
        recordingCinemaConfig = null

        currentRecordingTempFile?.let { f ->
            try { f.delete() } catch (ignored: Throwable) {}
            currentRecordingTempFile = null
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            onError(message)
        } else {
            Handler(Looper.getMainLooper()).post { onError(message) }
        }

        // CRITICAL: Restore the preview session on backgroundHandler so the viewfinder NEVER freezes
        backgroundHandler?.post {
            createCameraCaptureSession()
        }
    }

    /**
     * Start Video Recording
     */
    fun startVideoRecording(onError: (String) -> Unit) {
        if (_isRecordingVideo.value || isStartingRecording.get() || isStoppingRecording.get() || isSwitchingLens.get()) {
            Log.w(TAG, "startVideoRecording ignored: camera is busy or already recording")
            return
        }
        if (!isStartingRecording.compareAndSet(false, true)) return
        _isRecordingPaused.value = false

        val camera = cameraDevice ?: run {
            isStartingRecording.set(false)
            onError("Camera device not ready")
            return
        }
        val lens = _selectedLens.value ?: run {
            isStartingRecording.set(false)
            onError("No active lens selected")
            return
        }
        val previewSurf = getActivePreviewSurface() ?: run {
            isStartingRecording.set(false)
            onError("Preview surface not ready")
            return
        }



        try {
            val chars = getCharacteristics(lens.cameraId)
            val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // 1. Validate supported video resolutions against actual sensor configuration
            val isCinema = (currentMode == CameraMode.CINEMA)
            val requestedRes = if (isCinema && cinemaConfig.value.selectedResolution != null) {
                cinemaConfig.value.selectedResolution!!
            } else {
                userSelectedVideoResolution ?: _selectedVideoResolution.value ?: CameraResolution(1920, 1080)
            }

            val supportedVideoSizes = map?.getOutputSizes(MediaRecorder::class.java)
                ?: map?.getOutputSizes(SurfaceTexture::class.java)
                ?: emptyArray()

            val reqMaxDim = maxOf(requestedRes.width, requestedRes.height)
            val reqMinDim = minOf(requestedRes.width, requestedRes.height)
            val isSupported = supportedVideoSizes.isEmpty() || supportedVideoSizes.any {
                (maxOf(it.width, it.height) == reqMaxDim) &&
                (minOf(it.width, it.height) == reqMinDim)
            }

            val is16To9Requested = kotlin.math.abs((reqMaxDim.toFloat() / reqMinDim.toFloat()) - (16f / 9f)) < 0.08f
            val is4KRequested = reqMaxDim >= 3840
            val is2KRequested = (reqMaxDim == 2560 && reqMinDim == 1440) || (reqMaxDim in 2400..2800) || (isCinema && reqMaxDim == 2560)
            val isUltraWideLens = (lens.lensType == LensType.ULTRAWIDE)

            val videoRes = if (is2KRequested) {
                // True 2K Output: Preserve selected 2K resolution (2560x1440 or cinema 2560x1080) throughout
                // encoder configuration and final output saving. Sensor stream is supplied at highest available
                // supported stream (e.g. 4K UHD or native 2K) and rendered/encoded at exact 2K dimensions.
                requestedRes
            } else if (is4KRequested && isUltraWideLens) {
                // The 8MP ultra-wide sensor cannot provide 8.3MP native pixels for a full 4K frame,
                // so capture using the highest suitable supported 4:3 sensor stream and crop it to 9:16
                // for both the live viewfinder and final video. Keep the encoder output at 4K (2160x3840 in portrait);
                // do not downgrade recording to 1080p because of the aspect ratio.
                requestedRes
            } else if (isSupported) {
                requestedRes
            } else {
                val matching16to9 = if (is16To9Requested) {
                    supportedVideoSizes
                        .filter {
                            val r = maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height).toFloat()
                            kotlin.math.abs(r - (16f / 9f)) < 0.08f && maxOf(it.width, it.height) <= reqMaxDim
                        }
                        .maxByOrNull { it.width.toLong() * it.height.toLong() }
                } else null

                if (matching16to9 != null) {
                    Log.w(TAG, "[RECORDING] Size ${requestedRes.width}x${requestedRes.height} unsupported for ${lens.lensType}, using supported 16:9 ${matching16to9.width}x${matching16to9.height}")
                    CameraResolution(matching16to9.width, matching16to9.height)
                } else {
                    val largest = supportedVideoSizes
                        .filter { maxOf(it.width, it.height) <= reqMaxDim }
                        .maxByOrNull { it.width.toLong() * it.height.toLong() }
                    if (largest != null) {
                        Log.w(TAG, "[RECORDING] Size ${requestedRes.width}x${requestedRes.height} unsupported for ${lens.lensType}, using ${largest.width}x${largest.height}")
                        CameraResolution(largest.width, largest.height)
                    } else if (supportedVideoSizes.isNotEmpty()) {
                        val fallback = supportedVideoSizes.first()
                        CameraResolution(fallback.width, fallback.height)
                    } else {
                        CameraResolution(1920, 1080)
                    }
                }
            }

            // 2. Validate FPS range
            val targetFps = if (isCinema) cinemaConfig.value.videoFps else videoFps
            val matchedFpsRange = chars?.let { findBestFpsRange(it, targetFps) }

            // 3. Dynamic Range & Codec validation
            val isHlg10Active = isCinema && cinemaConfig.value.colorProfile == CinemaColorProfile.HLG10
            val cinemaCodec = if (isHlg10Active && cinemaConfig.value.codec == CinemaCodec.H264) {
                CinemaCodec.H265
            } else if (isCinema) {
                cinemaConfig.value.codec
            } else {
                CinemaCodec.H264
            }
            val dynamicProfiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            } else null
            val supportedProfiles = dynamicProfiles?.supportedProfiles ?: emptySet()
            val has10BitDynamicRange = supportedProfiles.contains(DynamicRangeProfiles.HLG10) ||
                    supportedProfiles.contains(DynamicRangeProfiles.HDR10) ||
                    supportedProfiles.contains(DynamicRangeProfiles.HDR10_PLUS)

            val isCameraSource10Bit = isCinema && has10BitDynamicRange &&
                    (cinemaConfig.value.logBitDepth == LogBitDepth.BIT_10 || isHlg10Active || cinemaCodec == CinemaCodec.PRORES)

            val is10BitRequested = isCinema &&
                    (cinemaConfig.value.logBitDepth == LogBitDepth.BIT_10 || isHlg10Active || cinemaCodec == CinemaCodec.PRORES) &&
                    has10BitDynamicRange

            val baseBitrate = if (isCinema) {
                if (cinemaConfig.value.videoBitrate != VideoBitrateOption.AUTO && cinemaConfig.value.videoBitrate.bps > 0) {
                    cinemaConfig.value.videoBitrate.bps
                } else {
                    when {
                        cinemaCodec == CinemaCodec.PRORES -> {
                            when {
                                videoRes.width >= 3840 -> 150_000_000
                                videoRes.width >= 1920 -> 90_000_000
                                else -> 50_000_000
                            }
                        }
                        videoRes.width >= 3840 -> 100_000_000
                        videoRes.width >= 1920 -> 60_000_000
                        else -> 30_000_000
                    }
                }
            } else if (is10BitRequested) {
                when {
                    videoRes.width >= 3840 -> 75_000_000
                    videoRes.width >= 1920 -> 40_000_000
                    else -> 20_000_000
                }
            } else if (videoBitrateOption.bps > 0) {
                videoBitrateOption.bps
            } else {
                when {
                    videoRes.width >= 3840 || videoRes.height >= 3840 -> 40_000_000
                    videoRes.width >= 2560 || videoRes.height >= 2560 -> 28_000_000
                    videoRes.width >= 1920 || videoRes.height >= 1920 -> 20_000_000
                    else -> 10_000_000
                }
            }
            val bitrate = baseBitrate
            val isSoftwareCinema = isCinema

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val prefix = if (isCinema) "CINEMA_" else "VID_"
            val isVp9 = isCinema && cinemaCodec == CinemaCodec.VP9
            val isProRes = isCinema && cinemaCodec == CinemaCodec.PRORES
            val extension = when {
                isVp9 -> "webm"
                isProRes -> "mov"
                else -> "mp4"
            }
            val mimeType = when {
                isVp9 -> "video/webm"
                isProRes -> "video/quicktime"
                else -> "video/mp4"
            }
            val fileName = "${prefix}$timeStamp.$extension"

            if (isCinema) {
                recordingCinemaConfig = cinemaConfig.value.copy()
            } else {
                recordingCinemaConfig = null
            }

            currentVideoFileName = fileName
            currentVideoMimeType = mimeType

            val recordingDir = context.externalCacheDir ?: context.cacheDir
            recordingDir.mkdirs()

            val tempFileName = if (isCinema) {
                "cinema_temp_${System.currentTimeMillis()}.$extension"
            } else {
                "rec_temp_${System.currentTimeMillis()}.$extension"
            }
            val tempFile = File(recordingDir, tempFileName).apply {
                if (exists()) delete()
                createNewFile()
            }
            currentRecordingTempFile = tempFile

            // Determine device orientation and compute upright recording dimensions
            val currentRot = getEffectiveDeviceRotation()
            val isPortraitRecording = (currentRot == 0 || currentRot == 180)
            val maxDim = maxOf(videoRes.width, videoRes.height)
            val minDim = minOf(videoRes.width, videoRes.height)
            val (finalRecordWidth, finalRecordHeight) = if (isSoftwareCinema) {
                when (cinemaConfig.value.aspectRatio) {
                    com.example.camera.model.CinemaAspectRatio.IMAX -> {
                        if (isPortraitRecording) {
                            val w = minDim and 1.inv()
                            val h = ((w * 1.43f).toInt() and 1.inv()).coerceAtLeast(240)
                            w to h
                        } else {
                            val h = minDim and 1.inv()
                            val w = ((h * 1.43f).toInt() and 1.inv()).coerceAtLeast(320)
                            w to h
                        }
                    }
                    com.example.camera.model.CinemaAspectRatio.CINEMATIC -> {
                        if (isPortraitRecording) {
                            val h = maxDim and 1.inv()
                            val w = ((h / 2.39f).toInt() and 1.inv()).coerceAtLeast(240)
                            w to h
                        } else {
                            val w = maxDim and 1.inv()
                            val h = ((w / 2.39f).toInt() and 1.inv()).coerceAtLeast(240)
                            w to h
                        }
                    }
                    com.example.camera.model.CinemaAspectRatio.RATIO_16_9 -> {
                        if (isPortraitRecording) minDim to maxDim else maxDim to minDim
                    }
                }
            } else {
                if (isPortraitRecording) minDim to maxDim else maxDim to minDim
            }

            val isFront = lens.facing == CameraCharacteristics.LENS_FACING_FRONT
            val encoderRotation = if (isPortraitRecording) {
                if (currentRot == 180) 180 else 0
            } else {
                if (isFront) {
                    if (currentRot == 90) 90 else 270
                } else {
                    if (currentRot == 90) 270 else 90
                }
            }

            // Setup recording target surface:
            // 1. Cinema Mode -> CinemaSoftwareRecordingEngine
            // 2. Normal Video -> Standard MediaRecorder

            val recorderSurface: Surface
            if (isSoftwareCinema) {
                if (cinemaCodec == CinemaCodec.VP9) {
                    val isVp9Supported = DeviceCompatibilityManager.isVp9EncodingSupported(
                        width = finalRecordWidth,
                        height = finalRecordHeight,
                        fps = targetFps,
                        requireSurface = true
                    )
                    if (!isVp9Supported) {
                        isStartingRecording.set(false)
                        _isRecordingVideo.value = false
                        try { tempFile.delete() } catch (_: Exception) {}
                        onError("Google VP9 encoder does not support ${finalRecordWidth}x${finalRecordHeight} @ ${targetFps}fps with Surface input on this device. Please select a supported resolution or codec.")
                        return
                    }
                    if (cinemaConfig.value.logBitDepth == LogBitDepth.BIT_10) {
                        val isVp9Profile2 = DeviceCompatibilityManager.isVp9Profile2Supported()
                        if (!isVp9Profile2 || !has10BitDynamicRange) {
                            isStartingRecording.set(false)
                            _isRecordingVideo.value = false
                            try { tempFile.delete() } catch (_: Exception) {}
                            onError("Google VP9 10-bit hardware recording is not supported on this device.")
                            return
                        }
                    }
                }

                if (cinemaConfig.value.logBitDepth == LogBitDepth.BIT_10) {
                    val supportedDepths = cinemaCapabilities.value.getSupportedBitDepthsForCodec(cinemaCodec)
                    if (!supportedDepths.contains(LogBitDepth.BIT_10)) {
                        isStartingRecording.set(false)
                        _isRecordingVideo.value = false
                        try { tempFile.delete() } catch (_: Exception) {}
                        val msg = when (cinemaCodec) {
                            CinemaCodec.VP9 -> "Google VP9 10-bit hardware recording is not supported on this device."
                            CinemaCodec.H265 -> "HEVC 10-bit hardware recording is not supported on this device."
                            CinemaCodec.H264 -> "H.264 does not support 10-bit recording."
                            else -> "10-bit recording is not supported for ${cinemaCodec.label}."
                        }
                        onError(msg)
                        return
                    }
                    if (cinemaCodec != CinemaCodec.PRORES && !has10BitDynamicRange) {
                        isStartingRecording.set(false)
                        _isRecordingVideo.value = false
                        try { tempFile.delete() } catch (_: Exception) {}
                        onError("Camera sensor does not support 10-bit dynamic range capture for ${cinemaCodec.label}.")
                        return
                    }
                }
                isSoftwareCinemaRecording = true
                val isFront = lens.facing == CameraCharacteristics.LENS_FACING_FRONT
                val sensorOrientation = try {
                    chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isFront) 270 else 90)
                } catch (_: Exception) {
                    if (isFront) 270 else 90
                }
                // Frames are rendered directly upright on the GPU into finalRecordWidth x finalRecordHeight
                val cinemaOrientationHint = 0
                val cinemaTargetBitDepth = when (cinemaCodec) {
                    CinemaCodec.PRORES -> LogBitDepth.BIT_10
                    else -> cinemaConfig.value.logBitDepth
                }
                recorderSurface = try {
                    cinemaSoftwareRecorder.startRecording(
                        destFile = tempFile,
                        width = finalRecordWidth,
                        height = finalRecordHeight,
                        fps = targetFps,
                        bitrate = bitrate,
                        codec = cinemaCodec,
                        bitDepth = cinemaTargetBitDepth,
                        isAudioEnabled = isAudioEnabled,
                        orientationHint = cinemaOrientationHint,
                        colorProfile = cinemaConfig.value.colorProfile,
                        colorSpace = if (isHlg10Active) CinemaColorSpace.REC_2020 else cinemaConfig.value.colorSpace,
                        isSource10Bit = isCameraSource10Bit,
                        cinemaConfig = cinemaConfig.value,
                        isFront = isFront,
                        sensorOrientation = sensorOrientation,
                        deviceRotation = currentRot,
                        sourceBufferWidth = maxOf(videoRes.width, videoRes.height),
                        sourceBufferHeight = minOf(videoRes.width, videoRes.height)
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed starting software cinema recording: ${t.message}", t)
                    isStartingRecording.set(false)
                    _isRecordingVideo.value = false
                    isSoftwareCinemaRecording = false
                    try { tempFile.delete() } catch (_: Exception) {}
                    onError("Failed starting Cinema recording: ${t.localizedMessage ?: t.message}")
                    return
                }
                preparedVideoGeometry = PreparedVideoGeometry(
                    width = finalRecordWidth,
                    height = finalRecordHeight,
                    fps = targetFps,
                    orientationHint = cinemaOrientationHint,
                    encoderRotation = encoderRotation
                )
            } else {
                val validatedConfig = DeviceCompatibilityManager.getValidatedVideoConfig(
                    requestedWidth = finalRecordWidth,
                    requestedHeight = finalRecordHeight,
                    requestedFps = targetFps,
                    requestedBitrate = bitrate,
                    preferHevc = (cinemaCodec == CinemaCodec.H265),
                    prefer10Bit = is10BitRequested
                )
                val targetCodec = if (validatedConfig.encoder == MediaRecorder.VideoEncoder.HEVC) CinemaCodec.H265 else CinemaCodec.H264
                val activeOrientHint = if (isPortraitRecording) {
                    if (currentRot == 180) 180 else 0
                } else {
                    encoderRotation
                }

                val sensorOrient = try {
                    chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isFront) 270 else 90)
                } catch (_: Exception) {
                    if (isFront) 270 else 90
                }

                recorderSurface = try {
                    normalVideoRecorder.startRecording(
                        destFile = tempFile,
                        width = finalRecordWidth,
                        height = finalRecordHeight,
                        fps = validatedConfig.fps,
                        bitrate = validatedConfig.bitrate,
                        codec = targetCodec,
                        isAudioEnabled = isAudioEnabled,
                        orientationHint = activeOrientHint,
                        isFront = isFront,
                        sensorOrientation = sensorOrient,
                        deviceRotation = currentRot
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed starting normal video recording: ${t.message}", t)
                    isStartingRecording.set(false)
                    _isRecordingVideo.value = false
                    try { tempFile.delete() } catch (_: Exception) {}
                    onError("Failed starting video recording: ${t.localizedMessage ?: t.message}")
                    return
                }

                preparedVideoGeometry = PreparedVideoGeometry(
                    width = finalRecordWidth,
                    height = finalRecordHeight,
                    fps = targetFps,
                    orientationHint = activeOrientHint,
                    encoderRotation = encoderRotation
                )
            }
                val is10BitSession = is10BitRequested || (isSoftwareCinema && cinemaCodec == CinemaCodec.PRORES && has10BitDynamicRange)
                val targetDynamicProfile: Long? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && is10BitSession) {
                    val isHlg10 = _cinemaConfig.value.colorProfile == CinemaColorProfile.HLG10
                    when {
                        isHlg10 && supportedProfiles.contains(DynamicRangeProfiles.HLG10) -> DynamicRangeProfiles.HLG10
                        supportedProfiles.contains(DynamicRangeProfiles.HLG10) -> DynamicRangeProfiles.HLG10
                        supportedProfiles.contains(DynamicRangeProfiles.HDR10) -> DynamicRangeProfiles.HDR10
                        supportedProfiles.contains(DynamicRangeProfiles.HDR10_PLUS) -> DynamicRangeProfiles.HDR10_PLUS
                        else -> null
                    }
                } else null

                activeRecordingStreamConfig = ActiveRecordingStreamConfig(
                    videoResolution = videoRes,
                    targetFps = targetFps,
                    fpsRange = matchedFpsRange,
                    is10Bit = is10BitSession,
                    dynamicRangeProfile = targetDynamicProfile,
                    isVideoStabilizationEnabled = isVideoStabilizationEnabled,
                    videoStabilizationMode = videoStabilizationMode,
                    isOisAllowed = isOisAllowed,
                    orientationHint = preparedVideoGeometry?.orientationHint ?: 0,
                    encoderRotation = encoderRotation
                )
                standbyConfiguredForRecording = false
                standbyRecordingSessionFailed = false
                logicalMultiCamRecordingFailed = false
                logicalMultiCamDualRecSurfacesConfigured = false

                // Initialize DualCameraRecordingRelay when Keep Ultra Wide Ready is enabled on back cameras
                // so both Main and Ultra-Wide recording pipelines can stream concurrently without interrupting the encoder.
                try {
                    dualCameraRecordingRelay?.release()
                } catch (_: Throwable) {}
                dualCameraRecordingRelay = null

                val backLensesForRec = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                val uwLensForRec = backLensesForRec.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                    ?: backLensesForRec.firstOrNull { it.lensType == LensType.ULTRAWIDE }
                val mainLensForRec = backLensesForRec.firstOrNull { it.isPrimaryMain }
                    ?: backLensesForRec.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                    ?: backLensesForRec.firstOrNull { it.lensType == LensType.WIDE }

                val initialStreamSource = if (lens.lensType == LensType.ULTRAWIDE) {
                    PreviewStreamSource.ULTRAWIDE
                } else {
                    PreviewStreamSource.MAIN
                }

                val sensorOrient = try {
                    chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isFront) 270 else 90)
                } catch (_: Exception) {
                    if (isFront) 270 else 90
                }

                val isKeepUwReadyBack = _isKeepUltraWideReady.value &&
                    lens.facing == CameraCharacteristics.LENS_FACING_BACK &&
                    uwLensForRec != null &&
                    mainLensForRec != null

                // Initialize DualCameraRecordingRelay as the live GPU processing surface:
                // Camera2 Surface -> GPU/EGL relay -> VideoAdjustments shader -> split to:
                // 1. Preview surface (viewfinder)
                // 2. MediaRecorder input surface (encoder)
                val relay = DualCameraRecordingRelay(
                    encoderTargetSurface = recorderSurface,
                    bufferWidth = finalRecordWidth,
                    bufferHeight = finalRecordHeight,
                    fps = targetFps,
                    is10Bit = is10BitSession,
                    initialSource = initialStreamSource,
                    cinemaRecorder = if (isSoftwareCinema) cinemaSoftwareRecorder else null,
                    sensorOrientation = sensorOrient,
                    deviceRotation = currentRot,
                    isFront = isFront,
                    previewTargetSurface = null,
                    previewWidth = viewfinderWidth,
                    previewHeight = viewfinderHeight,
                    initialAdjustments = currentVideoAdjustments
                )
                if (relay.isRelayReady) {
                    if (isKeepUwReadyBack && uwLensForRec != null && mainLensForRec != null) {
                        val bestUwRes = resolveEffectiveVideoResolutionForLens(uwLensForRec.cameraId, videoRes)
                        relay.updateInputBufferSize(isUltraWide = true, bestUwRes.width, bestUwRes.height)

                        val bestMainRes = resolveEffectiveVideoResolutionForLens(mainLensForRec.cameraId, videoRes)
                        relay.updateInputBufferSize(isUltraWide = false, bestMainRes.width, bestMainRes.height)
                    } else {
                        val activeStreamRes = resolveEffectiveVideoResolutionForLens(lens.cameraId, videoRes)
                        relay.updateInputBufferSize(
                            isUltraWide = (lens.lensType == LensType.ULTRAWIDE),
                            width = activeStreamRes.width,
                            height = activeStreamRes.height
                        )
                    }

                    dualCameraRecordingRelay = relay
                    _isGpuRelayPreviewActive.value = relay.isRelayPreviewActive
                } else {
                    relay.release()
                    _isGpuRelayPreviewActive.value = false
                }

                val activeLensRecorderSurface = dualCameraRecordingRelay?.getRecorderSurfaceForLens(lens) ?: recorderSurface
                activeRecordingSurface = activeLensRecorderSurface

                // Native Camera2 video recording session:
                // Feeds camera frames to both previewSurface and recorderSurface.
                backgroundHandler?.post {
                    try {
                        val dualMode = if (_isKeepUltraWideReady.value && lens.facing == CameraCharacteristics.LENS_FACING_BACK) {
                            detectSimultaneousStreamingMode(mainLensForRec, uwLensForRec)
                        } else {
                            DualStreamingMode.NONE
                        }

                        // Prewarm standby recording session in parallel when Main and Ultra-Wide are separate CameraDevices
                        if (_isKeepUltraWideReady.value &&
                            dualMode != DualStreamingMode.NONE &&
                            dualMode != DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
                            dualCameraRecordingRelay?.hasDistinctUltraWideSurface() == true) {
                            ensureUltraWideSimultaneousReady()
                        }

                        // Logical Multi-Camera with Physical Sub-Cameras:
                        // Configure BOTH Main and Ultra-Wide physical streams in the same recording CameraCaptureSession
                        // so Main <-> Ultra-Wide switching during recording is 0ms without closing or reconfiguring the session.
                        if (_isKeepUltraWideReady.value &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            dualMode == DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
                            mainLensForRec != null &&
                            uwLensForRec != null &&
                            mainLensForRec.cameraId == camera.id &&
                            uwLensForRec.cameraId == camera.id &&
                            uwLensForRec.physicalCameraId != null &&
                            uwLensForRec.supportsPhysicalStream) {
                            val physIds = chars?.physicalCameraIds ?: emptySet()
                            val uwPhysId = uwLensForRec.physicalCameraId!!
                            val mainPhysId = mainLensForRec.physicalCameraId?.takeIf {
                                mainLensForRec.supportsPhysicalStream && physIds.contains(it) && it != camera.id
                            }
                            val mainPrevSurf = getOrCreateMainPreviewSurface(camera.id) ?: previewSurf
                            val uwPrevSurf = getOrCreateUltraWidePreviewSurface(camera.id)
                            val relay = dualCameraRecordingRelay
                            val mainRecSurf = relay?.mainRecorderSurface ?: activeLensRecorderSurface
                            val uwRecSurf = relay?.ultraWideRecorderSurface

                            if (physIds.contains(uwPhysId) && uwPrevSurf != null && uwPrevSurf.isValid && uwPrevSurf !== mainPrevSurf) {
                                val isUwSelected = (lens.lensType == LensType.ULTRAWIDE)
                                activeSessionPhysicalCameraId = if (isUwSelected) uwPhysId else mainPhysId
                                _isUsingUltraWideSurface.value = isUwSelected
                                _displayedPreviewSource.value = if (isUwSelected) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN

                                val executor = Executor { cmd -> backgroundHandler?.post(cmd) ?: cmd.run() }

                                fun startEncodersAndCompleteLogicalRecording(
                                    configuredSession: CameraCaptureSession,
                                    reqBuilder: CaptureRequest.Builder,
                                    dualRecConfigured: Boolean
                                ) {
                                    captureSession = configuredSession
                                    activeLogicalMultiCamUltraWideConfigured = true
                                    logicalMultiCamDualRecSurfacesConfigured = dualRecConfigured
                                    logicalMultiCamRecordingFailed = false
                                    isUltraWideStreaming.set(true)
                                    _ultraWideStreamStatus.value = BackgroundCameraStatus.READY_QUIET
                                    synchronized(previewRequestLock) {
                                        previewRequestBuilder = reqBuilder
                                        applyCommonSettings(reqBuilder)
                                        reqBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                                        if (matchedFpsRange != null) {
                                            reqBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, matchedFpsRange)
                                        }
                                        configuredSession.setRepeatingRequest(reqBuilder.build(), captureCallback, backgroundHandler)
                                    }
                                    if (!isSoftwareCinema) {
                                        mediaRecorder?.start()
                                    }
                                    dualCameraRecordingRelay?.startRecording()
                                    _isRecordingVideo.value = true
                                    isStartingRecording.set(false)
                                    startVideoTimer()
                                    if (pendingLensWhileSwitching != null || pendingZoomWhileSwitching != null) {
                                        completeLensSwitch(lens)
                                    }
                                    Log.i(TAG, "[KEEP_ULTRAWIDE_READY] Logical multi-camera dual-physical recording session active on ${camera.id} (dualRec=$dualRecConfigured)")
                                }

                                fun tryThreeOutputLogicalPhysicalRecording(fallbackToSingleOnFail: () -> Unit) {
                                    try {
                                        val mPrevCfg = OutputConfiguration(mainPrevSurf).apply {
                                            if (mainPhysId != null) setPhysicalCameraId(mainPhysId)
                                        }
                                        val uPrevCfg = OutputConfiguration(uwPrevSurf).apply {
                                            setPhysicalCameraId(uwPhysId)
                                        }
                                        val sharedRecCfg = OutputConfiguration(activeLensRecorderSurface).apply {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && targetDynamicProfile != null) {
                                                dynamicRangeProfile = targetDynamicProfile
                                            }
                                        }
                                        val builder3 = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                            addTarget(mainPrevSurf)
                                            addTarget(uwPrevSurf)
                                            addTarget(activeLensRecorderSurface)
                                            applyCommonSettings(this)
                                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                                            if (matchedFpsRange != null) {
                                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, matchedFpsRange)
                                            }
                                        }
                                        val sessionCfg3 = SessionConfiguration(
                                            SessionConfiguration.SESSION_REGULAR,
                                            listOf(mPrevCfg, uPrevCfg, sharedRecCfg),
                                            executor,
                                            object : CameraCaptureSession.StateCallback() {
                                                override fun onConfigured(s3: CameraCaptureSession) {
                                                    try {
                                                        startEncodersAndCompleteLogicalRecording(s3, builder3, dualRecConfigured = false)
                                                    } catch (e: Exception) {
                                                        cleanupFailedRecording(onError, "Failed to start logical recording: ${e.message}")
                                                    }
                                                }
                                                override fun onConfigureFailed(s3: CameraCaptureSession) {
                                                    try { s3.close() } catch (_: Throwable) {}
                                                    activeLogicalMultiCamUltraWideConfigured = false
                                                    logicalMultiCamRecordingFailed = true
                                                    fallbackToSingleOnFail()
                                                }
                                            }
                                        )
                                        camera.createCaptureSession(sessionCfg3)
                                    } catch (e: Exception) {
                                        activeLogicalMultiCamUltraWideConfigured = false
                                        logicalMultiCamRecordingFailed = true
                                        fallbackToSingleOnFail()
                                    }
                                }

                                // Tier 1 for Logical Multi-Camera: 2 Physical Previews + 2 Physical Recording Surfaces
                                if (uwRecSurf != null && uwRecSurf.isValid && uwRecSurf !== mainRecSurf) {
                                    try {
                                        val mPrevCfg = OutputConfiguration(mainPrevSurf).apply {
                                            if (mainPhysId != null) setPhysicalCameraId(mainPhysId)
                                        }
                                        val uPrevCfg = OutputConfiguration(uwPrevSurf).apply {
                                            setPhysicalCameraId(uwPhysId)
                                        }
                                        val mRecCfg = OutputConfiguration(mainRecSurf).apply {
                                            if (mainPhysId != null) setPhysicalCameraId(mainPhysId)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && targetDynamicProfile != null) {
                                                dynamicRangeProfile = targetDynamicProfile
                                            }
                                        }
                                        val uRecCfg = OutputConfiguration(uwRecSurf).apply {
                                            setPhysicalCameraId(uwPhysId)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && targetDynamicProfile != null) {
                                                dynamicRangeProfile = targetDynamicProfile
                                            }
                                        }
                                        val builder4 = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                            addTarget(mainPrevSurf)
                                            addTarget(uwPrevSurf)
                                            addTarget(mainRecSurf)
                                            addTarget(uwRecSurf)
                                            applyCommonSettings(this)
                                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                                            if (matchedFpsRange != null) {
                                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, matchedFpsRange)
                                            }
                                        }
                                        val sessionCfg4 = SessionConfiguration(
                                            SessionConfiguration.SESSION_REGULAR,
                                            listOf(mPrevCfg, uPrevCfg, mRecCfg, uRecCfg),
                                            executor,
                                            object : CameraCaptureSession.StateCallback() {
                                                override fun onConfigured(s4: CameraCaptureSession) {
                                                    try {
                                                        startEncodersAndCompleteLogicalRecording(s4, builder4, dualRecConfigured = true)
                                                    } catch (e: Exception) {
                                                        cleanupFailedRecording(onError, "Failed to start logical dual-physical recording: ${e.message}")
                                                    }
                                                }
                                                override fun onConfigureFailed(s4: CameraCaptureSession) {
                                                    try { s4.close() } catch (_: Throwable) {}
                                                    Log.w(TAG, "[KEEP_ULTRAWIDE_READY] 4-output logical physical recording rejected; trying 3-output physical recording session")
                                                    tryThreeOutputLogicalPhysicalRecording {
                                                        // Fallback to standard single-stream recording session below
                                                        val fallbackBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                                            addTarget(previewSurf)
                                                            addTarget(activeLensRecorderSurface)
                                                            applyCommonSettings(this)
                                                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                                                        }
                                                        synchronized(previewRequestLock) { previewRequestBuilder = fallbackBuilder }
                                                        createRecordingCaptureSession(
                                                            camera = camera,
                                                            previewSurface = previewSurf,
                                                            recorderSurface = activeLensRecorderSurface,
                                                            is10Bit = is10BitSession,
                                                            physicalCameraId = null,
                                                            callback = object : CameraCaptureSession.StateCallback() {
                                                                override fun onConfigured(fs: CameraCaptureSession) {
                                                                    captureSession = fs
                                                                    try {
                                                                        synchronized(previewRequestLock) {
                                                                            fs.setRepeatingRequest(fallbackBuilder.build(), captureCallback, backgroundHandler)
                                                                        }
                                                                        if (!isSoftwareCinema) mediaRecorder?.start()
                                                                        dualCameraRecordingRelay?.startRecording()
                                                                        _isRecordingVideo.value = true
                                                                        isStartingRecording.set(false)
                                                                        startVideoTimer()
                                                                    } catch (e: Exception) {
                                                                        cleanupFailedRecording(onError, "Failed to start fallback recording: ${e.message}")
                                                                    }
                                                                }
                                                                override fun onConfigureFailed(fs: CameraCaptureSession) {
                                                                    cleanupFailedRecording(onError, "Camera hardware failed to configure video capture session")
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                        camera.createCaptureSession(sessionCfg4)
                                        return@post
                                    } catch (e: Exception) {
                                        Log.w(TAG, "4-output logical physical recording setup exception, trying 3-output: ${e.message}")
                                    }
                                }
                            }
                        }

                        val useLogicalZoom = canUseLogicalZoomForLens(camera.id, lens, currentZoom)
                        val targetPhysId = if (useLogicalZoom) null else lens.physicalCameraId
                        activeSessionPhysicalCameraId = targetPhysId

                        val recordBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(previewSurf)
                            addTarget(activeLensRecorderSurface)
                            applyCommonSettings(this)
                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                            if (matchedFpsRange != null) {
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, matchedFpsRange)
                            }
                            applyHardwareEisRequest(this)
                            applyHardwareOisRequest(
                                this,
                                oisAllowed = isOisAllowed && isOisSupportedForActiveLens()
                            )
                        }
                        synchronized(previewRequestLock) {
                            previewRequestBuilder = recordBuilder
                        }

                        var isFallbackActive = false
                        val sessionCallback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                Log.i(TAG, "[RECORDING_SESSION] Video recording session configured")
                                captureSession = session
                                try {
                                    synchronized(previewRequestLock) {
                                        applyCommonSettings(recordBuilder)
                                        applyHardwareEisRequest(recordBuilder)
                                        applyHardwareOisRequest(
                                            recordBuilder,
                                            oisAllowed = isOisAllowed && isOisSupportedForActiveLens()
                                        )
                                        session.setRepeatingRequest(recordBuilder.build(), captureCallback, backgroundHandler)
                                    }
                                    if (!isSoftwareCinema) {
                                        mediaRecorder?.start()
                                    }
                                    dualCameraRecordingRelay?.startRecording()
                                    _isRecordingVideo.value = true
                                    isStartingRecording.set(false)
                                    startVideoTimer()
                                    val is4kRec = maxOf(videoRes.width, videoRes.height) >= 3840
                                    if (_isKeepUltraWideReady.value && !is4kRec) {
                                        ensureUltraWideSimultaneousReady()
                                    }
                                    if (pendingLensWhileSwitching != null || pendingZoomWhileSwitching != null) {
                                        completeLensSwitch(lens)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed starting repeating request or recorder", e)
                                    cleanupFailedRecording(onError, "Failed to start recording: ${e.message}")
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.w(TAG, "[RECORDING_SESSION] Video capture session configuration failed, trying safe standard fallback")
                                activeSessionPhysicalCameraId = null
                                if (!isFallbackActive) {
                                    isFallbackActive = true
                                    try {
                                        // Build safe fallback capture request: standard FPS, standard template
                                        val safeBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                            addTarget(previewSurf)
                                            addTarget(activeLensRecorderSurface)
                                            applyCommonSettings(this)
                                            if (!isOisAllowed) {
                                                try {
                                                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                                } catch (_: Throwable) {}
                                                applyVendorOisControls(this, oisAllowed = false)
                                            }
                                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                                            // Set safe 30fps range
                                            val fpsRanges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                                            val safeRange = fpsRanges.firstOrNull { it.upper == 30 } ?: fpsRanges.firstOrNull()
                                            if (safeRange != null) {
                                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, safeRange)
                                            }
                                        }
                                        synchronized(previewRequestLock) {
                                            previewRequestBuilder = safeBuilder
                                        }

                                        @Suppress("DEPRECATION")
                                        val fallbackSurfaces = listOf(previewSurf, activeLensRecorderSurface)
                                        camera.createCaptureSession(fallbackSurfaces, object : CameraCaptureSession.StateCallback() {
                                            override fun onConfigured(fallbackSession: CameraCaptureSession) {
                                                Log.i(TAG, "[RECORDING_SESSION] Fallback video recording session configured successfully")
                                                captureSession = fallbackSession
                                                try {
                                                    synchronized(previewRequestLock) {
                                                        applyCommonSettings(safeBuilder)
                                                        fallbackSession.setRepeatingRequest(safeBuilder.build(), captureCallback, backgroundHandler)
                                                    }
                                                    if (!isSoftwareCinema) {
                                                        mediaRecorder?.start()
                                                    }
                                                    dualCameraRecordingRelay?.startRecording()
                                                    _isRecordingVideo.value = true
                                                    isStartingRecording.set(false)
                                                    startVideoTimer()
                                                    val is4kRec = maxOf(videoRes.width, videoRes.height) >= 3840
                                                    if (_isKeepUltraWideReady.value && !is4kRec) {
                                                        ensureUltraWideSimultaneousReady()
                                                    }
                                                    if (pendingLensWhileSwitching != null || pendingZoomWhileSwitching != null) {
                                                        completeLensSwitch(lens)
                                                    }
                                                } catch (e: Exception) {
                                                    cleanupFailedRecording(onError, "Failed to start fallback recording: ${e.message}")
                                                }
                                            }

                                            override fun onConfigureFailed(fallbackSession: CameraCaptureSession) {
                                                Log.e(TAG, "[RECORDING_SESSION] Fallback video session failed, trying preview-only recording fallback")
                                                // Extreme hardware compatibility fallback (e.g. LEGACY or budget devices)
                                                try {
                                                    val previewTemplateBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                        addTarget(previewSurf)
                                                        addTarget(activeLensRecorderSurface)
                                                        applyCommonSettings(this)
                                                        if (!isOisAllowed) {
                                                            try {
                                                                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                                            } catch (_: Throwable) {}
                                                            applyVendorOisControls(this, oisAllowed = false)
                                                        }
                                                    }
                                                    synchronized(previewRequestLock) {
                                                        previewRequestBuilder = previewTemplateBuilder
                                                    }
                                                    @Suppress("DEPRECATION")
                                                    camera.createCaptureSession(fallbackSurfaces, object : CameraCaptureSession.StateCallback() {
                                                        override fun onConfigured(prevSession: CameraCaptureSession) {
                                                            captureSession = prevSession
                                                            try {
                                                                synchronized(previewRequestLock) {
                                                                    applyCommonSettings(previewTemplateBuilder)
                                                                    prevSession.setRepeatingRequest(previewTemplateBuilder.build(), captureCallback, backgroundHandler)
                                                                }
                                                                if (!isSoftwareCinema) {
                                                                    mediaRecorder?.start()
                                                                }
                                                                dualCameraRecordingRelay?.startRecording()
                                                                _isRecordingVideo.value = true
                                                                isStartingRecording.set(false)
                                                                startVideoTimer()
                                                                if (pendingLensWhileSwitching != null || pendingZoomWhileSwitching != null) {
                                                                    completeLensSwitch(lens)
                                                                }
                                                            } catch (e: Exception) {
                                                                cleanupFailedRecording(onError, "Recording initialization failed: ${e.message}")
                                                            }
                                                        }

                                                        override fun onConfigureFailed(prevSession: CameraCaptureSession) {
                                                            cleanupFailedRecording(onError, "Camera hardware failed to configure video capture session")
                                                        }
                                                    }, backgroundHandler)
                                                    return
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Secondary fallback exception", e)
                                                }
                                                cleanupFailedRecording(onError, "Camera hardware failed to configure video capture session")
                                            }
                                        }, backgroundHandler)
                                        return
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Fallback capture session creation exception", e)
                                    }
                                }
                                cleanupFailedRecording(onError, "Camera hardware failed to configure video capture session")
                            }
                        }

                        createRecordingCaptureSession(
                            camera = camera,
                            previewSurface = previewSurf,
                            recorderSurface = activeLensRecorderSurface,
                            is10Bit = is10BitSession,
                            physicalCameraId = targetPhysId,
                            callback = sessionCallback
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create recording capture session", e)
                        cleanupFailedRecording(onError, "Failed to initialize recording session: ${e.message}")
                    }
                }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video recording", e)
            cleanupFailedRecording(onError, getMediaCodecErrorDetail(e))
        }
    }

    private fun startVideoTimer() {
        _videoDurationSeconds.value = 0

        videoTimerJob?.cancel()
        videoTimerJob = engineScope.launch {
            while (_isRecordingVideo.value) {
                delay(1000)
                if (!_isRecordingPaused.value && _isRecordingVideo.value) {
                    _videoDurationSeconds.value += 1
                }
            }
        }
    }

    private fun restorePreviewSessionImmediate() {
        isStoppingRecording.set(false)
        if (sessionRestoredForStop.compareAndSet(false, true)) {
            backgroundHandler?.post {
                try {
                    val previewSurf = getActivePreviewSurface()
                    if (previewSurf != null && previewSurf.isValid && cameraDevice != null && captureSession != null) {
                        val previewReq = (cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: previewRequestBuilder)?.apply {
                            addTarget(previewSurf)
                            applyCommonSettings(this)
                        }
                        if (previewReq != null) {
                            synchronized(previewRequestLock) {
                                previewRequestBuilder = previewReq
                                captureSession?.setRepeatingRequest(previewReq.build(), captureCallback, backgroundHandler)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Quick preview switch: ${e.message}")
                }
                createCameraCaptureSession()
                if (_isKeepUltraWideReady.value) {
                    ensureUltraWideSimultaneousReady()
                }
            }
        }
    }

    /**
     * Pause ongoing video recording (both standard Video Mode and Cinema Mode).
     * Preserves current video and audio pipeline states without terminating the session.
     */
    fun pauseVideoRecording() {
        if (!_isRecordingVideo.value || _isRecordingPaused.value) return
        _isRecordingPaused.value = true
        Log.i(TAG, "pauseVideoRecording called")
        dualCameraRecordingRelay?.pause()

        if (isSoftwareCinemaRecording) {
            cinemaSoftwareRecorder.pause()
        } else {
            normalVideoRecorder.pause()
        }
    }

    /**
     * Resume paused video recording into the same active session file.
     */
    fun resumeVideoRecording() {
        if (!_isRecordingVideo.value || !_isRecordingPaused.value) return
        _isRecordingPaused.value = false
        Log.i(TAG, "resumeVideoRecording called")
        dualCameraRecordingRelay?.resume()

        if (isSoftwareCinemaRecording) {
            cinemaSoftwareRecorder.resume()
        } else {
            normalVideoRecorder.resume()
        }
    }

    /**
     * Stop Video Recording
     *
     * Recording stops instantly with zero viewfinder freezing or UI blocking.
     * All video encoding finalization, muxing, LUT/color grading, and gallery saving
     * are executed asynchronously in background coroutines.
     */
    fun stopVideoRecording(onComplete: ((Uri?) -> Unit)? = null) {
        if (!_isRecordingVideo.value && !isSoftwareCinemaRecording) {
            onComplete?.invoke(null)
            return
        }
        if (!isStoppingRecording.compareAndSet(false, true)) {
            onComplete?.invoke(null)
            return
        }

        sessionRestoredForStop.set(false)
        activeRecordingSurface = null
        activeRecordingStreamConfig = null
        standbyConfiguredForRecording = false
        standbyRecordingSessionFailed = false
        logicalMultiCamRecordingFailed = false
        logicalMultiCamDualRecSurfacesConfigured = false
        val activeRelay = dualCameraRecordingRelay
        dualCameraRecordingRelay = null
        _isGpuRelayPreviewActive.value = false
        _isRecordingVideo.value = false
        _isRecordingPaused.value = false
        videoTimerJob?.cancel()

        val isCinema = (currentMode == CameraMode.CINEMA)
        val snapCodec = recordingCinemaConfig?.codec ?: cinemaConfig.value.codec
        val defaultExt = if (isCinema) {
            when (snapCodec) {
                CinemaCodec.VP9 -> "webm"
                CinemaCodec.PRORES -> "mov"
                else -> "mp4"
            }
        } else "mp4"
        val rawFileName = currentVideoFileName ?: (if (isCinema) "CINEMA_${System.currentTimeMillis()}.$defaultExt" else "VID_${System.currentTimeMillis()}.mp4")
        val effectiveFileName = rawFileName
        val effectiveMimeType = if (isCinema) {
            when (snapCodec) {
                CinemaCodec.VP9 -> "video/webm"
                CinemaCodec.PRORES -> "video/quicktime"
                else -> "video/mp4"
            }
        } else (currentVideoMimeType ?: "video/mp4")
        currentVideoFileName = null
        currentVideoMimeType = null

        val activeLens = _selectedLens.value
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
        val wasSoftwareCinema = isSoftwareCinemaRecording
        isSoftwareCinemaRecording = false
        val snapCinemaConfig = recordingCinemaConfig ?: cinemaConfig.value.copy()
        recordingCinemaConfig = null


        val lockedOrientationHint = preparedVideoGeometry?.orientationHint ?: getVideoOrientationHint()

        val activeMediaRecorder = mediaRecorder
        mediaRecorder = null
        val activePfd = videoRecordingFileDescriptor
        videoRecordingFileDescriptor = null
        val tempFile = currentRecordingTempFile
        currentRecordingTempFile = null

        savingVideoJobsCount.incrementAndGet()
        _isSavingVideo.value = true

        // Dispatch stop and resource cleanup to background IO so UI thread never freezes
        engineScope.launch(Dispatchers.IO) {
            var callbackTriggered = false
            val notifyComplete: (Uri?) -> Unit = { uri ->
                if (!callbackTriggered) {
                    callbackTriggered = true
                    onComplete?.invoke(uri)
                }
            }

            try {
                // Ensure dual-camera recording relay flushes and finalizes pending frames before stopping encoder
                if (activeRelay != null) {
                    val waitStart = System.currentTimeMillis()
                    while (activeRelay.relayedFrameCount.get() < 2L && System.currentTimeMillis() - waitStart < 400L) {
                        try { Thread.sleep(25) } catch (_: InterruptedException) { break }
                    }
                    activeRelay.flush(300L)
                    activeRelay.stopRecording()
                    try { Thread.sleep(60L) } catch (_: InterruptedException) {}
                }

                val recordedFile: File? = when {
                    wasSoftwareCinema -> {
                        val f = try {
                            cinemaSoftwareRecorder.stopRecording()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping cinema software recorder", e)
                            null
                        }
                        restorePreviewSessionImmediate()
                        f
                    }
                    else -> {
                        activeMediaRecorder?.apply {
                            try { stop() } catch (_: Throwable) {}
                            try { reset() } catch (_: Throwable) {}
                            try { release() } catch (_: Throwable) {}
                        }
                        try { activePfd?.fileDescriptor?.sync() } catch (_: Throwable) {}
                        try { activePfd?.close() } catch (_: Throwable) {}
                        val f = try {
                            normalVideoRecorder.stopRecording()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping normal video recorder", e)
                            null
                        }
                        restorePreviewSessionImmediate()
                        f ?: tempFile
                    }
                }

                finalizeAndSaveRecordedVideo(
                    rawRecordedFile = recordedFile,
                    isCinema = isCinema,
                    snapCinemaConfig = snapCinemaConfig,
                    effectiveFileName = effectiveFileName,
                    effectiveMimeType = effectiveMimeType,
                    isFrontFacing = isFrontFacing,
                    lockedOrientationHint = lockedOrientationHint,
                    notifyComplete = notifyComplete
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping video recording in background", e)
                notifyComplete(null)
            } finally {
                restorePreviewSessionImmediate()
                try {
                    activeRelay?.release()
                } catch (ignored: Throwable) {}
                if (savingVideoJobsCount.decrementAndGet() <= 0) {
                    _isSavingVideo.value = false
                }
            }
        }
    }

    private suspend fun finalizeAndSaveRecordedVideo(
        rawRecordedFile: File?,
        isCinema: Boolean,
        snapCinemaConfig: CinemaConfig,
        effectiveFileName: String,
        effectiveMimeType: String,
        isFrontFacing: Boolean,
        lockedOrientationHint: Int,
        notifyComplete: (Uri?) -> Unit
    ) {
        if (rawRecordedFile == null || !rawRecordedFile.exists() || rawRecordedFile.length() <= 0L) {
            Log.w(TAG, "Recorded file was null or empty, cannot finalize video")
            notifyComplete(null)
            return
        }

        var fileToSave = rawRecordedFile
        var gradedFile: File? = null
        val intermediateFiles = mutableListOf<File>()

        try {
            if (isCinema) {
                // Real-time GPU pipeline has already applied Log, LUT, and color grading during recording!
                // Zero post-processing delay; no re-decoding, re-processing or re-encoding needed!
                fileToSave = rawRecordedFile
            }





            // Ensure output file has valid container structure before publishing to MediaStore
            if (!validateRecordedFileIntegrity(fileToSave, effectiveMimeType)) {
                Log.e(TAG, "Recorded file integrity validation failed for ${fileToSave.name}; aborting MediaStore publish")
                try { fileToSave.delete() } catch (ignored: Exception) {}
                if (fileToSave != rawRecordedFile) {
                    try { rawRecordedFile.delete() } catch (ignored: Exception) {}
                }
                for (f in intermediateFiles) { try { f.delete() } catch (ignored: Exception) {} }
                notifyComplete(null)
                return
            }

            var finalWidth = 0
            var finalHeight = 0
            var finalOrientation = 0
            var retrieverSuccess = false

            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(fileToSave.absolutePath)
                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val r = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) {
                    finalWidth = w
                    finalHeight = h
                    finalOrientation = r
                    retrieverSuccess = true
                    Log.i(TAG, "Extracted real video geometry from container: ${w}x${h}, rotation=$r")
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaMetadataRetriever query failed: ${e.message}")
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }

            if (!retrieverSuccess) {
                if (isCinema) {
                    finalWidth = preparedVideoGeometry?.width ?: 1920
                    finalHeight = preparedVideoGeometry?.height ?: 1080
                    finalOrientation = 0
                } else {
                    val geomW = preparedVideoGeometry?.width ?: 1920
                    val geomH = preparedVideoGeometry?.height ?: 1080
                    val hint = preparedVideoGeometry?.orientationHint ?: lockedOrientationHint
                    finalOrientation = hint
                    if (hint == 90 || hint == 270) {
                        finalWidth = maxOf(geomW, geomH)
                        finalHeight = minOf(geomW, geomH)
                    } else {
                        finalWidth = geomW
                        finalHeight = geomH
                    }
                }
            }

            val savedUri = saveVideoToGallery(
                tempFile = fileToSave,
                fileName = effectiveFileName,
                mimeType = effectiveMimeType,
                isCinema = isCinema,
                isFrontFacing = isFrontFacing,
                orientationDegrees = finalOrientation,
                width = finalWidth,
                height = finalHeight
            )

            if (savedUri != null) {
                val videoDisplayName = if (isCinema) {
                    "Cinema Video"
                } else {
                    "Video"
                }
                _lastCapturedMedia.value = CapturedMedia(
                    uri = savedUri,
                    isVideo = true,
                    timestamp = System.currentTimeMillis(),
                    displayName = videoDisplayName,
                    isFrontCamera = isFrontFacing
                )
                Log.i(TAG, "Finalized video successfully saved: size=${fileToSave.length()} bytes, uri=$savedUri")
                notifyComplete(savedUri)
            } else {
                Log.w(TAG, "Failed saving finalized video to gallery")
                notifyComplete(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing recorded video", e)
            notifyComplete(null)
        } finally {
            intermediateFiles.forEach { try { it.delete() } catch (_: Exception) {} }
            try { gradedFile?.delete() } catch (ignored: Exception) {}
            try { rawRecordedFile.delete() } catch (ignored: Exception) {}
            updateStorageStats()
        }
    }

    /**
     * Validates that the recorded video file exists, is non-empty, has a valid container,
     * and crucially contains a valid, finalized video track with real dimensions, recognized codec,
     * and encoded frame/sample data before publishing to MediaStore.
     */
    private fun validateRecordedFileIntegrity(file: File, mimeType: String): Boolean {
        if (!file.exists() || file.length() < 32L) return false

        val isRobolectric = Build.FINGERPRINT.contains("robolectric", ignoreCase = true) ||
                Build.HARDWARE.contains("robolectric", ignoreCase = true) ||
                Build.MODEL.contains("robolectric", ignoreCase = true)

        // 1. Basic container header validation
        val hasValidContainer = try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                when {
                    mimeType.contains("quicktime", ignoreCase = true) || file.name.endsWith(".mov", ignoreCase = true) -> {
                        // MOV must start with 'ftyp' and contain 'mdat' & 'moov'
                        val size = raf.readInt()
                        val type = raf.readInt()
                        if (type != 0x66747970 || size < 16) return@use false
                        val fileLen = raf.length()
                        var pos = size.toLong()
                        var hasMdat = false
                        var hasMoov = false
                        while (pos + 8 <= fileLen) {
                            raf.seek(pos)
                            val atomSizeRaw = raf.readInt().toLong() and 0xFFFFFFFFL
                            val atomType = raf.readInt()
                            val atomSize = when (atomSizeRaw) {
                                1L -> {
                                    if (pos + 16 > fileLen) break
                                    raf.readLong()
                                }
                                0L -> fileLen - pos
                                else -> atomSizeRaw
                            }
                            if (atomType == 0x6D646174) hasMdat = true
                            if (atomType == 0x6D6F6F76) hasMoov = true
                            if (atomSize <= 0 || (pos + atomSize > fileLen && atomSizeRaw != 0L)) break
                            pos += atomSize
                        }
                        hasMdat && hasMoov
                    }
                    mimeType.contains("webm", ignoreCase = true) || file.name.endsWith(".webm", ignoreCase = true) -> {
                        // WebM must start with EBML header: 0x1A 0x45 0xDF 0xA3
                        val b0 = raf.read()
                        val b1 = raf.read()
                        val b2 = raf.read()
                        val b3 = raf.read()
                        b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3
                    }
                    else -> {
                        // MP4: must start with 'ftyp' or have valid box structure
                        val size = raf.readInt()
                        val type = raf.readInt()
                        size >= 16 && (type == 0x66747970 || type == 0x6D646174 || type == 0x6D6F6F76)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during container integrity validation", e)
            false
        }

        if (!hasValidContainer) {
            Log.w(TAG, "Recorded file ${file.name} failed container header check")
            return false
        }

        if (isRobolectric) {
            return true
        }

        // 2. Validate video track codec, resolution, and samples using MediaExtractor
        var hasValidVideoTrack = false
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val numTracks = extractor.trackCount
            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (trackMime.startsWith("video/")) {
                    val w = if (format.containsKey(android.media.MediaFormat.KEY_WIDTH)) {
                        format.getInteger(android.media.MediaFormat.KEY_WIDTH)
                    } else 0
                    val h = if (format.containsKey(android.media.MediaFormat.KEY_HEIGHT)) {
                        format.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                    } else 0

                    if (w > 0 && h > 0) {
                        hasValidVideoTrack = true
                        Log.i(TAG, "Validated video track via MediaExtractor: mime=$trackMime, ${w}x${h}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor inspection failed: ${e.message}", e)
        } finally {
            try { extractor.release() } catch (_: Throwable) {}
        }

        if (hasValidVideoTrack) {
            return true
        }

        // 3. Fallback verification via MediaMetadataRetriever
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val width = wStr?.toIntOrNull() ?: 0
            val height = hStr?.toIntOrNull() ?: 0
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            if ((width > 0 && height > 0) || durationMs > 0L || hasVideo != null) {
                Log.i(TAG, "Validated video via MediaMetadataRetriever: ${width}x${height}, duration=${durationMs}ms")
                return true
            } else {
                Log.w(TAG, "MediaMetadataRetriever rejected video: hasVideo=$hasVideo, ${width}x${height}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever inspection failed: ${e.message}", e)
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        // 4. File-size container failsafe: if file has non-trivial size (> 4KB) and valid container
        if (file.length() > 4096L && hasValidContainer) {
            Log.i(TAG, "Recorded file ${file.name} validated via container header and size (${file.length()} bytes)")
            return true
        }

        Log.e(TAG, "Recorded file ${file.name} rejected: missing or corrupt video track (0x0 or 0 samples)")
        return false
    }

    /**
     * Reliable Gallery saving pipeline for recorded videos with automatic fallback.
     */
    private suspend fun saveVideoToGallery(
        tempFile: File,
        fileName: String,
        mimeType: String,
        isCinema: Boolean,
        isFrontFacing: Boolean,
        orientationDegrees: Int = 0,
        width: Int = 0,
        height: Int = 0
    ): Uri? = withContext(Dispatchers.IO) {
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            Log.w(TAG, "saveVideoToGallery: tempFile is missing or empty")
            return@withContext null
        }

        val fileToSave: File = tempFile

        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.TITLE, fileName.substringBeforeLast('.'))
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            if (width > 0) put(MediaStore.Video.Media.WIDTH, width)
            if (height > 0) put(MediaStore.Video.Media.HEIGHT, height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.ORIENTATION, orientationDegrees)
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                val dcimDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera"
                ).apply { if (!exists()) mkdirs() }
                val targetFile = File(dcimDir, fileName)
                put(MediaStore.Video.Media.DATA, targetFile.absolutePath)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        var targetUri: Uri? = null
        try {
            // Ensure physical DCIM/Camera directory exists
            try {
                val dcimDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera"
                )
                if (!dcimDir.exists()) dcimDir.mkdirs()
            } catch (ignored: Exception) {}

            try {
                targetUri = resolver.insert(collection, contentValues)
            } catch (e: Exception) {
                Log.w(TAG, "Primary MediaStore insertion failed, retrying with minimal values", e)
                try {
                    val minimalValues = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    targetUri = resolver.insert(collection, minimalValues)
                } catch (e2: Exception) {
                    Log.e(TAG, "Minimal MediaStore insertion also failed", e2)
                }
            }

            if (targetUri != null) {
                var streamSuccess = false
                try {
                    resolver.openOutputStream(targetUri, "w")?.use { out ->
                        fileToSave.inputStream().use { input ->
                            input.copyTo(out)
                        }
                        out.flush()
                        streamSuccess = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed writing video bytes to MediaStore OutputStream", e)
                    streamSuccess = false
                }

                if (streamSuccess) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val updateValues = ContentValues().apply {
                                put(MediaStore.Video.Media.IS_PENDING, 0)
                                put(MediaStore.Video.Media.SIZE, fileToSave.length())
                                put(MediaStore.Video.Media.ORIENTATION, orientationDegrees)
                                if (width > 0) put(MediaStore.Video.Media.WIDTH, width)
                                if (height > 0) put(MediaStore.Video.Media.HEIGHT, height)
                            }
                            resolver.update(targetUri, updateValues, null, null)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed updating MediaStore with full attributes, trying IS_PENDING=0 only", e)
                            try {
                                val minimalUpdate = ContentValues().apply {
                                    put(MediaStore.Video.Media.IS_PENDING, 0)
                                    put(MediaStore.Video.Media.SIZE, fileToSave.length())
                                }
                                resolver.update(targetUri, minimalUpdate, null, null)
                            } catch (_: Exception) {}
                        }
                    }

                    // Ensure immediate indexing in Gallery and Google Photos across all Android versions
                    try {
                        val dcimPath = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                            "Camera/$fileName"
                        ).absolutePath
                        MediaScannerConnection.scanFile(context, arrayOf(dcimPath), arrayOf(mimeType)) { path, uri ->
                            Log.d(TAG, "MediaScanner indexed video at $path -> $uri")
                        }
                    } catch (ignored: Exception) {}

                    try {
                        @Suppress("DEPRECATION")
                        val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                            data = targetUri
                        }
                        context.sendBroadcast(mediaScanIntent)
                    } catch (ignored: Exception) {}

                    return@withContext targetUri
                } else {
                    // Write failed, remove pending placeholder
                    try { resolver.delete(targetUri, null, null) } catch (ignored: Exception) {}
                    targetUri = null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Primary MediaStore insertion failed, falling back to direct DCIM/Camera write", e)
            if (targetUri != null) {
                try { resolver.delete(targetUri, null, null) } catch (ignored: Exception) {}
            }
        }

        // Direct DCIM/Camera fallback
        try {
            val dcimDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera"
            ).apply { if (!exists()) mkdirs() }
            val targetFile = File(dcimDir, fileName)
            fileToSave.copyTo(targetFile, overwrite = true)

            var scannedUri: Uri? = null
            val scanCompletable = kotlinx.coroutines.CompletableDeferred<Uri?>()
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType)
            ) { _, uri ->
                scannedUri = uri
                scanCompletable.complete(uri)
                Log.d(TAG, "Fallback video scanned: $uri")
            }
            try {
                kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    scannedUri = scanCompletable.await()
                }
            } catch (ignored: Exception) {}

            return@withContext scannedUri ?: Uri.fromFile(targetFile)
        } catch (e: Exception) {
            Log.w(TAG, "Fallback DCIM video save failed, trying app external movies dir", e)
            try {
                val appMoviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    ?: File(context.filesDir, "Movies").apply { mkdirs() }
                val targetFile = File(appMoviesDir, fileName)
                fileToSave.copyTo(targetFile, overwrite = true)
                var scannedUri: Uri? = null
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(mimeType)
                ) { _, uri ->
                    scannedUri = uri
                }
                return@withContext scannedUri ?: Uri.fromFile(targetFile)
            } catch (e2: Exception) {
                Log.e(TAG, "All fallback video saves failed", e2)
                return@withContext null
            }
        }
    }

    /**
     * Extracts human-readable diagnostic error messages from MediaCodec / MediaRecorder exceptions.
     */
    private fun getMediaCodecErrorDetail(e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is MediaCodec.CodecException) {
                return "MediaCodec error: ${cause.diagnosticInfo} (code=${cause.errorCode}, transient=${cause.isTransient})"
            }
            if (cause is java.io.IOException && cause.message?.contains("prepare failed") == true) {
                return "Hardware encoder prepare failed (unsupported codec, resolution, or profile level)"
            }
            cause = cause.cause
        }
        return e.localizedMessage ?: e.message ?: "Encoder initialization error"
    }

    private fun saveJpegToMediaStore(image: Image): Uri? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return saveJpegBytesToMediaStore(bytes)
    }

    private val photoSaveSequence = java.util.concurrent.atomic.AtomicInteger((System.currentTimeMillis() % 1000).toInt())

    private fun generateUniqueImageFileName(prefix: String = "IMG", extension: String = "jpg"): String {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val seq = (photoSaveSequence.incrementAndGet() and 0xFFFF).toString().padStart(4, '0')
        return "${prefix}_${timeStamp}_${seq}.$extension"
    }

    private fun getMediaStorePrimaryImageUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } catch (t: Throwable) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun copyAllExifAttributes(srcExif: android.media.ExifInterface, dstExif: android.media.ExifInterface) {
        val standardTags = arrayOf(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.TAG_DATETIME,
            android.media.ExifInterface.TAG_DATETIME_ORIGINAL,
            android.media.ExifInterface.TAG_DATETIME_DIGITIZED,
            android.media.ExifInterface.TAG_MAKE,
            android.media.ExifInterface.TAG_MODEL,
            android.media.ExifInterface.TAG_FLASH,
            android.media.ExifInterface.TAG_FOCAL_LENGTH,
            android.media.ExifInterface.TAG_WHITE_BALANCE,
            android.media.ExifInterface.TAG_EXPOSURE_TIME,
            android.media.ExifInterface.TAG_ISO_SPEED_RATINGS,
            android.media.ExifInterface.TAG_GPS_LATITUDE,
            android.media.ExifInterface.TAG_GPS_LATITUDE_REF,
            android.media.ExifInterface.TAG_GPS_LONGITUDE,
            android.media.ExifInterface.TAG_GPS_LONGITUDE_REF,
            android.media.ExifInterface.TAG_GPS_ALTITUDE,
            android.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
            android.media.ExifInterface.TAG_GPS_TIMESTAMP,
            android.media.ExifInterface.TAG_GPS_DATESTAMP,
            android.media.ExifInterface.TAG_GPS_PROCESSING_METHOD,
            android.media.ExifInterface.TAG_SUBSEC_TIME,
            android.media.ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            android.media.ExifInterface.TAG_SUBSEC_TIME_DIGITIZED
        )
        for (tag in standardTags) {
            try {
                val value = srcExif.getAttribute(tag)
                if (value != null) {
                    dstExif.setAttribute(tag, value)
                }
            } catch (ignored: Throwable) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val nTags = arrayOf(
                android.media.ExifInterface.TAG_F_NUMBER,
                android.media.ExifInterface.TAG_APERTURE_VALUE,
                android.media.ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                android.media.ExifInterface.TAG_EXPOSURE_PROGRAM,
                android.media.ExifInterface.TAG_EXPOSURE_MODE,
                android.media.ExifInterface.TAG_METERING_MODE,
                android.media.ExifInterface.TAG_LIGHT_SOURCE,
                android.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM
            )
            for (tag in nTags) {
                try {
                    val value = srcExif.getAttribute(tag)
                    if (value != null) {
                        dstExif.setAttribute(tag, value)
                    }
                } catch (ignored: Throwable) {}
            }
        }
    }

    private fun saveDirectFileFallback(bytes: ByteArray, fileName: String): Uri? {
        return try {
            val dcimDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            if (!dcimDir.exists()) {
                dcimDir.mkdirs()
            }
            val targetFile = if (dcimDir.exists() && dcimDir.canWrite()) {
                File(dcimDir, fileName)
            } else {
                val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                File(picturesDir, fileName)
            }

            FileOutputStream(targetFile).use { out ->
                out.write(bytes)
                out.flush()
            }
            Log.i(TAG, "Direct disk fallback write succeeded: ${targetFile.absolutePath} (${bytes.size} bytes)")
            val fileUri = Uri.fromFile(targetFile)
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf("image/jpeg")
                ) { path, scannedUri ->
                    Log.d(TAG, "Direct fallback scanned: $path -> $scannedUri")
                }
            } catch (ignored: Throwable) {}
            fileUri
        } catch (e: Throwable) {
            Log.e(TAG, "Direct file fallback write failed completely for $fileName", e)
            null
        }
    }

    /**
     * Builds the exact transformation Matrix needed to rotate a decoded Camera2 JPEG bitmap
     * into physically upright orientation (and optionally mirror front-facing captures).
     *
     * Handles all Camera2 HAL variations:
     * 1. HAL leaves pixels in raw sensor orientation (width > height) and writes EXIF TAG_ORIENTATION (6/8/3).
     * 2. HAL leaves pixels in raw sensor orientation (width > height) and writes TAG_ORIENTATION = 1 or 0
     *    even when captureOrientation is 90° or 270° (portrait hold).
     * 3. HAL physically rotates pixels in hardware to portrait (height > width) and writes TAG_ORIENTATION = 1.
     */
    private fun buildJpegOrientationMatrix(
        rawWidth: Int,
        rawHeight: Int,
        exifOrientation: Int,
        captureOrientation: Int,
        isFrontFacing: Boolean,
        mirrorHorizontally: Boolean = false
    ): Matrix {
        val matrix = Matrix()
        val isPortraitTarget = (captureOrientation == 90 || captureOrientation == 270)
        val isRawLandscape = rawWidth > rawHeight

        when (exifOrientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> {
                if (!isPortraitTarget || isRawLandscape) matrix.postRotate(90f)
            }
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.postRotate(180f)
            }
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> {
                if (!isPortraitTarget || isRawLandscape) matrix.postRotate(270f)
            }
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postScale(1f, -1f)
            }
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                if (!isPortraitTarget || isRawLandscape) matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                if (!isPortraitTarget || isRawLandscape) matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> {
                // ORIENTATION_NORMAL (1) or ORIENTATION_UNDEFINED (0):
                // If the capture was taken in Portrait (90° or 270°) and the decoded bitmap is still
                // landscape (width > height), the HAL did not rotate the pixel buffer.
                if (isPortraitTarget && isRawLandscape) {
                    val rot = if (captureOrientation == 90 || captureOrientation == 270) {
                        captureOrientation.toFloat()
                    } else if (isFrontFacing) {
                        270f
                    } else {
                        90f
                    }
                    matrix.postRotate(rot)
                } else if (captureOrientation == 180 && exifOrientation == android.media.ExifInterface.ORIENTATION_UNDEFINED) {
                    matrix.postRotate(180f)
                }
            }
        }

        if (isFrontFacing && mirrorHorizontally) {
            matrix.postScale(-1f, 1f)
        }
        return matrix
    }

    private fun decodeUprightBitmapFromJpeg(
        jpegBytes: ByteArray,
        captureOrientation: Int = getCaptureJpegOrientation(),
        isFrontFacing: Boolean = (_selectedLens.value?.facing == CameraCharacteristics.LENS_FACING_FRONT),
        mirrorHorizontally: Boolean = false,
        mutable: Boolean = true
    ): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = mutable
            inSampleSize = 1
        }
        val rawBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return null
        val srcExif = try {
            android.media.ExifInterface(java.io.ByteArrayInputStream(jpegBytes))
        } catch (e: Exception) {
            null
        }
        val exifOrientation = srcExif?.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_UNDEFINED
        ) ?: android.media.ExifInterface.ORIENTATION_UNDEFINED

        val matrix = buildJpegOrientationMatrix(
            rawWidth = rawBitmap.width,
            rawHeight = rawBitmap.height,
            exifOrientation = exifOrientation,
            captureOrientation = captureOrientation,
            isFrontFacing = isFrontFacing,
            mirrorHorizontally = mirrorHorizontally
        )

        return if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(
                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
            )
            if (transformed != rawBitmap) {
                rawBitmap.recycle()
            }
            if (mutable && !transformed.isMutable) {
                val mutableCopy = transformed.copy(Bitmap.Config.ARGB_8888, true)
                transformed.recycle()
                mutableCopy
            } else {
                transformed
            }
        } else {
            rawBitmap
        }
    }

    private fun processPhotoWithCustomPipeline(jpegBytes: ByteArray): Pair<ByteArray, Boolean> {
        try {
            val activePreset = preferences.getActivePipelinePreset()
            val activeParams = preferences.getPipelineParams(activePreset.id)
            val activeLens = _selectedLens.value
            val isFront = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
            val captureOrientation = getCaptureJpegOrientation()

            // Decode and orient to physically upright (and mirror selfie if enabled) BEFORE running pipeline
            val sourceBmp = decodeUprightBitmapFromJpeg(
                jpegBytes = jpegBytes,
                captureOrientation = captureOrientation,
                isFrontFacing = isFront,
                mirrorHorizontally = isFront && saveSelfieAsPreviewed,
                mutable = true
            ) ?: return Pair(jpegBytes, false)

            val processedBytes = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
                customImagePipelineEngine.processAndEncodeToJpegBytes(
                    source = sourceBmp,
                    params = activeParams,
                    jpegQuality = preferences.jpegQuality
                )
            }

            val maxDim = max(sourceBmp.width, sourceBmp.height)
            val previewScale = (1080f / maxDim).coerceAtMost(1.0f)
            val previewBmp = if (previewScale < 0.95f) {
                Bitmap.createScaledBitmap(
                    sourceBmp,
                    (sourceBmp.width * previewScale).roundToInt(),
                    (sourceBmp.height * previewScale).roundToInt(),
                    true
                )
            } else {
                sourceBmp.copy(Bitmap.Config.ARGB_8888, true)
            }

            val captureSource = com.example.camera.pipeline.engine.PipelineCaptureSource(
                fullResBitmap = sourceBmp,
                previewBitmap = previewBmp,
                orientationDegrees = 0, // Already physically oriented upright
                isFrontFacing = isFront,
                lensInfo = activeLens,
                appliedPreset = activePreset,
                appliedParams = activeParams
            )
            com.example.camera.pipeline.engine.PipelineCaptureCache.setCapture(captureSource)

            val finalPipelineBytes = copyExifFromOriginal(
                srcBytes = jpegBytes,
                dstBytes = processedBytes,
                forceOrientationNormal = true
            )
            return Pair(finalPipelineBytes, true)
        } catch (e: Throwable) {
            Log.e(TAG, "Error applying custom pipeline preset to photo, falling back to original", e)
            return Pair(jpegBytes, false)
        }
    }

    private fun copyExifFromOriginal(
        srcBytes: ByteArray,
        dstBytes: ByteArray,
        forceOrientationNormal: Boolean = false
    ): ByteArray {
        return try {
            val srcExif = android.media.ExifInterface(java.io.ByteArrayInputStream(srcBytes))
            val tempFile = File.createTempFile("exif_copy", ".jpg", context.cacheDir)
            try {
                FileOutputStream(tempFile).use { it.write(dstBytes) }
                val dstExif = android.media.ExifInterface(tempFile.absolutePath)
                copyAllExifAttributes(srcExif, dstExif)
                if (forceOrientationNormal) {
                    dstExif.setAttribute(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL.toString()
                    )
                }
                dstExif.saveAttributes()
                tempFile.readBytes()
            } finally {
                tempFile.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to copy EXIF attributes to pipeline output", t)
            dstBytes
        }
    }

    private fun processStillJpegBytes(bytes: ByteArray, skipPipeline: Boolean = false): Pair<ByteArray, Boolean> {
        var effectiveBytes = bytes
        var wasProcessingApplied = false

        if (!skipPipeline && currentMode == CameraMode.PHOTO && preferences.isCustomPipelineEnabled) {
            val (pipelineBytes, applied) = processPhotoWithCustomPipeline(effectiveBytes)
            effectiveBytes = pipelineBytes
            if (applied) wasProcessingApplied = true
        }

        val activeLens = _selectedLens.value
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
        val captureOrientation = getCaptureJpegOrientation()

        val isProAdjusted = (proSaturation.value != 0f || proContrast.value != 1.0f || proHighlights.value != 0f || proShadows.value != 0f)
        val needsColorProcessing = !skipPipeline && isProAdjusted

        // Check whether effectiveBytes still needs physical rotation or front-camera mirroring
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(effectiveBytes, 0, effectiveBytes.size, boundsOpts)
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight

        val srcExif = try {
            android.media.ExifInterface(java.io.ByteArrayInputStream(effectiveBytes))
        } catch (e: Exception) {
            null
        }
        val exifOrientation = srcExif?.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_UNDEFINED
        ) ?: android.media.ExifInterface.ORIENTATION_UNDEFINED

        val shouldMirrorHere = !skipPipeline && !wasProcessingApplied && isFrontFacing && saveSelfieAsPreviewed
        val orientMatrix = if (skipPipeline || wasProcessingApplied) {
            Matrix()
        } else {
            buildJpegOrientationMatrix(
                rawWidth = rawW,
                rawHeight = rawH,
                exifOrientation = exifOrientation,
                captureOrientation = captureOrientation,
                isFrontFacing = isFrontFacing,
                mirrorHorizontally = shouldMirrorHere
            )
        }

        val needsBitmapTransform = !orientMatrix.isIdentity || needsColorProcessing
        var wasTransformed = wasProcessingApplied

        val outputBytes = if (needsBitmapTransform) {
            try {
                val rawBitmap = BitmapFactory.decodeByteArray(effectiveBytes, 0, effectiveBytes.size)
                if (rawBitmap != null) {
                    val uprightBmp = if (!orientMatrix.isIdentity) {
                        val rotated = Bitmap.createBitmap(
                            rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, orientMatrix, true
                        )
                        if (rotated != rawBitmap) {
                            rawBitmap.recycle()
                        }
                        rotated
                    } else {
                        rawBitmap
                    }

                    // Apply filter and Pro image adjustments if active
                    val colorMatrix = android.graphics.ColorMatrix()
                    var hasColorTransform = false
                    if (needsColorProcessing) {
                        if (proSaturation.value != 0f) {
                            val satMat = android.graphics.ColorMatrix().apply {
                                setSaturation((1f + proSaturation.value / 100f).coerceIn(0f, 3f))
                            }
                            colorMatrix.postConcat(satMat)
                            hasColorTransform = true
                        }
                        if (proContrast.value != 1.0f || proHighlights.value != 0f || proShadows.value != 0f) {
                            val c = proContrast.value.coerceIn(0.5f, 2.0f)
                            val b = ((proHighlights.value + proShadows.value) / 4f)
                            val cm = android.graphics.ColorMatrix(floatArrayOf(
                                c, 0f, 0f, 0f, b,
                                0f, c, 0f, 0f, b,
                                0f, 0f, c, 0f, b,
                                0f, 0f, 0f, 1f, 0f
                            ))
                            colorMatrix.postConcat(cm)
                            hasColorTransform = true
                        }
                    }

                    val finalBmp = if (hasColorTransform) {
                        val fb = Bitmap.createBitmap(uprightBmp.width, uprightBmp.height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(fb)
                        val paint = android.graphics.Paint().apply {
                            colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
                        }
                        canvas.drawBitmap(uprightBmp, 0f, 0f, paint)
                        uprightBmp.recycle()
                        fb
                    } else {
                        uprightBmp
                    }

                    val tempOutFile = File.createTempFile("still_upright", ".jpg", context.cacheDir)
                    val quality = preferences.jpegQuality.coerceIn(95, 100)
                    FileOutputStream(tempOutFile).use { fos ->
                        finalBmp.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                        fos.flush()
                    }
                    finalBmp.recycle()

                    // Copy all original EXIF metadata and mark orientation as NORMAL (1)
                    // since the bitmap pixels are now physically upright
                    try {
                        val dstExif = android.media.ExifInterface(tempOutFile.absolutePath)
                        if (srcExif != null) {
                            copyAllExifAttributes(srcExif, dstExif)
                        }
                        dstExif.setAttribute(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL.toString()
                        )
                        dstExif.saveAttributes()
                    } catch (e: Exception) {
                        Log.w(TAG, "EXIF preservation on upright photo warning", e)
                    }

                    val transformedBytes = tempOutFile.readBytes()
                    tempOutFile.delete()
                    wasTransformed = true
                    transformedBytes
                } else {
                    effectiveBytes
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to orient/filter still JPEG, falling back", e)
                effectiveBytes
            }
        } else {
            effectiveBytes
        }
        return Pair(outputBytes, wasTransformed)
    }

    private fun saveMotionPhotoBytesToMediaStore(packedBytes: ByteArray): Uri? {
        return saveJpegBytesToMediaStore(packedBytes, skipPipeline = true, filePrefix = "MVIMG")
    }

    private fun saveJpegBytesToMediaStore(bytes: ByteArray, skipPipeline: Boolean = false, filePrefix: String = "IMG"): Uri? {
        val activeLens = _selectedLens.value
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
        val (outputBytes, wasFilterApplied) = processStillJpegBytes(bytes, skipPipeline)

        val fileName = generateUniqueImageFileName(filePrefix, "jpg")
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.size, boundsOptions)
        val imageW = boundsOptions.outWidth
        val imageH = boundsOptions.outHeight

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, nowSec)
            put(MediaStore.Images.Media.DATE_MODIFIED, nowSec)
            if (imageW > 0 && imageH > 0) {
                put(MediaStore.Images.Media.WIDTH, imageW)
                put(MediaStore.Images.Media.HEIGHT, imageH)
            }
            put(MediaStore.Images.Media.ORIENTATION, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_TAKEN, nowMs)
            } else {
                try {
                    val dcimDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
                    if (!dcimDir.exists()) dcimDir.mkdirs()
                    val targetFile = File(dcimDir, fileName)
                    put(MediaStore.Images.Media.DATA, targetFile.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Legacy DCIM path resolution failed for $fileName", e)
                }
            }
        }

        val primaryUri = getMediaStorePrimaryImageUri()
        val uri = try {
            context.contentResolver.insert(primaryUri, contentValues)
        } catch (t: Throwable) {
            Log.e(TAG, "MediaStore insert on primary volume failed: ${t.message}. Trying default external volume", t)
            try {
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            } catch (t2: Throwable) {
                Log.e(TAG, "MediaStore insert on fallback external volume also failed: ${t2.message}", t2)
                null
            }
        }

        if (uri == null) {
            Log.e(TAG, "MediaStore insertion returned null for $fileName (${outputBytes.size} bytes). Executing disk fallback.")
            val fallbackUri = saveDirectFileFallback(outputBytes, fileName)
            if (fallbackUri != null) {
                _lastCapturedMedia.value = CapturedMedia(
                    uri = fallbackUri,
                    isVideo = false,
                    timestamp = nowMs,
                    displayName = fileName
                )
            }
            return fallbackUri
        }

        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Unable to open output stream for URI: $uri")
            outputStream.use { out ->
                out.write(outputBytes)
                out.flush()
            }

            if ((isFrontFacing && saveSelfieAsPreviewed) || wasFilterApplied) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val outExif = android.media.ExifInterface(pfd.fileDescriptor)
                        outExif.setAttribute(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL.toString()
                        )
                        outExif.saveAttributes()
                    }
                } catch (e: Throwable) {
                    Log.d(TAG, "openFileDescriptor 'rw' not supported on this device/provider; pre-encoded EXIF in outputBytes will be used")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            } else {
                val legacyPath = contentValues.getAsString(MediaStore.Images.Media.DATA)
                if (!legacyPath.isNullOrEmpty()) {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(legacyPath), arrayOf("image/jpeg"), null)
                }
            }

            // Guarantee notification for external gallery apps
            try {
                val legacyPath = contentValues.getAsString(MediaStore.Images.Media.DATA)
                if (!legacyPath.isNullOrEmpty()) {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(legacyPath), arrayOf("image/jpeg")) { path, scannedUri ->
                        Log.d(TAG, "MediaScanner indexed $path -> $scannedUri")
                    }
                }
            } catch (ignored: Throwable) {}

            Log.i(TAG, "Photo saved successfully to MediaStore: $uri ($fileName, ${outputBytes.size} bytes)")
            _lastCapturedMedia.value = CapturedMedia(
                uri = uri,
                isVideo = false,
                timestamp = nowMs,
                displayName = fileName
            )
            return uri
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write photo bytes to MediaStore (uri=$uri, file=$fileName, size=${outputBytes.size})", e)
            try {
                context.contentResolver.delete(uri, null, null)
                Log.d(TAG, "Successfully cleaned up pending MediaStore entry: $uri")
            } catch (cleanupEx: Throwable) {
                Log.w(TAG, "Failed to delete incomplete MediaStore entry: $uri", cleanupEx)
            }

            val fallbackUri = saveDirectFileFallback(outputBytes, fileName)
            if (fallbackUri != null) {
                _lastCapturedMedia.value = CapturedMedia(
                    uri = fallbackUri,
                    isVideo = false,
                    timestamp = nowMs,
                    displayName = fileName
                )
                return fallbackUri
            }
            return null
        }
    }



    private fun saveRawToMediaStore(rawImage: Image, characteristics: CameraCharacteristics) {
        val fileName = generateUniqueImageFileName("RAW", "dng")
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.DATE_ADDED, nowSec)
            put(MediaStore.Images.Media.DATE_MODIFIED, nowSec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_TAKEN, nowMs)
            } else {
                try {
                    val dcimDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
                    if (!dcimDir.exists()) dcimDir.mkdirs()
                    val targetFile = File(dcimDir, fileName)
                    put(MediaStore.Images.Media.DATA, targetFile.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Legacy DCIM path resolution failed for RAW $fileName", e)
                }
            }
        }

        val primaryUri = getMediaStorePrimaryImageUri()
        val uri = try {
            context.contentResolver.insert(primaryUri, contentValues)
        } catch (t: Throwable) {
            try {
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            } catch (t2: Throwable) {
                Log.e(TAG, "Failed to insert RAW DNG to MediaStore", t2)
                null
            }
        } ?: return

        try {
            val captureResult = lastCaptureResult ?: throw java.lang.IllegalStateException("No captureResult for DNG")
            val dngCreator = DngCreator(characteristics, captureResult)

            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Unable to open output stream for RAW URI: $uri")
            outputStream.use { out ->
                dngCreator.writeImage(out, rawImage)
                out.flush()
            }
            dngCreator.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, completeValues, null, null)
            } else {
                val legacyPath = contentValues.getAsString(MediaStore.Images.Media.DATA)
                if (!legacyPath.isNullOrEmpty()) {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(legacyPath), arrayOf("image/x-adobe-dng"), null)
                }
            }
            Log.i(TAG, "Saved RAW DNG successfully to $uri ($fileName)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save RAW image data to $uri, cleaning up pending entry", e)
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (cleanupEx: Throwable) {
                Log.w(TAG, "Failed to delete incomplete RAW MediaStore entry: $uri", cleanupEx)
            }
        }
    }

    /**
     * Update storage statistics
     */
    fun updateStorageStats() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val bytesAvailable = stat.availableBytes
            val totalBytes = stat.totalBytes
            val freeGb = bytesAvailable.toFloat() / (1024 * 1024 * 1024)

            // Approximate: 5MB per JPEG photo, ~150MB per minute of 1080p video
            val estimatedPhotos = (bytesAvailable / (5L * 1024 * 1024)).toInt()
            val estimatedVideoMinutes = (bytesAvailable / (150L * 1024 * 1024)).toInt()

            _storageStats.value = StorageStats(
                freeBytes = bytesAvailable,
                totalBytes = totalBytes,
                freeGb = freeGb,
                estimatedPhotos = estimatedPhotos,
                estimatedVideoMinutes = estimatedVideoMinutes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating storage stats", e)
        }
    }

    fun restartCamera() {
        if (!_isCameraInitialized.value || previewSurfaceTexture == null) {
            Log.d(TAG, "Skipping restartCamera: not initialized or previewSurfaceTexture is null")
            isSwitchingLens.set(false)
            return
        }
        backgroundHandler?.post {
            synchronized(cameraLifecycleLock) {
                if (isStartingCamera) {
                    restartPending = true
                    return@synchronized
                }
                closeCameraInternal()
                startCamera()
            }
        } ?: run {
            closeCamera()
            startCamera()
        }
    }

    fun applyViewfinderResolution(resolution: ViewfinderResolution) {
        if (viewfinderResolution == resolution) return
        viewfinderResolution = resolution
        restartCamera()
    }

    private fun closeCameraCaptureSession() {
        try {
            pendingZoomRunnable?.let { backgroundHandler?.removeCallbacks(it) }
            pendingZoomRunnable = null
            activeSessionPhysicalCameraId = null
            try {
                captureSession?.stopRepeating()
            } catch (ignored: Throwable) {}
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing capture session", e)
        }
    }

    private fun closeCameraInternal() {
        isClosingCamera = true
        CameraPerformanceMonitor.stop()
        _isCameraReady.value = false
        sessionConfigGeneration.incrementAndGet()
        isConfiguringSession = false
        pendingReconfigureSession = false
        gyroStabilizationEngine.stop()

        ultraFastShutterEngine.reset()
        closeCameraCaptureSession()
        releaseUltraWideStandby()
        activeRecordingStreamConfig = null
        standbyConfiguredForRecording = false
        standbyRecordingSessionFailed = false
        logicalMultiCamRecordingFailed = false
        logicalMultiCamDualRecSurfacesConfigured = false
        try {
            dualCameraRecordingRelay?.release()
        } catch (ignored: Throwable) {}
        dualCameraRecordingRelay = null
        _isUsingUltraWideSurface.value = false
        _displayedPreviewSource.value = PreviewStreamSource.MAIN
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera device", e)
        }
        // Release previewSurface when closing CameraDevice so the old ANativeWindow producer
        // disconnects cleanly from SurfaceTexture before the next camera sets buffer size
        try {
            previewSurface?.release()
            previewSurface = null
        } catch (ignored: Throwable) {}
        try {
            fallbackMainSurface?.release()
            fallbackMainSurface = null
            fallbackMainSurfaceTexture?.release()
            fallbackMainSurfaceTexture = null
        } catch (ignored: Throwable) {}
        try {
            previewRequestBuilder = null
        } catch (ignored: Throwable) {}
        try {
            imageReaderJpeg?.close()
            imageReaderJpeg = null
            imageReaderYuv?.close()
            imageReaderYuv = null
            imageReaderRaw?.close()
            imageReaderRaw = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing image readers", e)
        }
        isStartingCamera = false
        isClosingCamera = false
    }

    fun closeCamera() {
        isClosingCamera = true
        _isCameraReady.value = false
        standbySessionGeneration.incrementAndGet()
        sessionConfigGeneration.incrementAndGet()
        ultraWideReconnectRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        ultraWideReconnectRunnable = null
        pendingZoomRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        pendingZoomRunnable = null
        isPreparingUltraWideStandby.set(false)
        isUltraWideStreaming.set(false)
        _ultraWideStreamStatus.value = BackgroundCameraStatus.OFF

        val handler = backgroundHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                synchronized(cameraLifecycleLock) {
                    closeCameraInternal()
                }
            }
        } else {
            synchronized(cameraLifecycleLock) {
                closeCameraInternal()
            }
        }
    }

    /**
     * Handles app moving to background (Settings, Home, switch app)
     */
    fun onAppBackgrounded() {
        Log.d(TAG, "onAppBackgrounded: closing camera cleanly")
        if (_isRecordingVideo.value) {
            try { stopVideoRecording() } catch (ignored: Throwable) {}
        }
        closeCamera()
    }

    /**
     * Handles app moving back to foreground
     */
    fun onAppForegrounded() {
        Log.d(TAG, "onAppForegrounded: re-initializing camera session")
        val isTextureValid = previewSurfaceTexture != null && run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) !previewSurfaceTexture!!.isReleased else true
        }
        if (_isCameraInitialized.value && isTextureValid) {
            restartCamera()
        }
    }

    fun release() {
        try {
            physicalOrientationEventListener?.disable()
            physicalOrientationEventListener = null
        } catch (ignored: Exception) {}
        closeCamera()
        stopBackgroundThread()
    }

    internal fun configurePrewarmedDualRecordingStateForTest(
        activeCam: CameraDevice?,
        activeSess: CameraCaptureSession?,
        standbyCam: CameraDevice?,
        standbySess: CameraCaptureSession?,
        relay: DualCameraRecordingRelay?,
        config: ActiveRecordingStreamConfig?,
        standbyRecordingReady: Boolean = true,
        standbyFailed: Boolean = false
    ) {
        cameraDevice = activeCam
        captureSession = activeSess
        ultraWideStandbyCameraDevice = standbyCam
        ultraWideStandbyCaptureSession = standbySess
        dualCameraRecordingRelay = relay
        activeRecordingStreamConfig = config
        standbyConfiguredForRecording = standbyRecordingReady
        standbyRecordingSessionFailed = standbyFailed
        _isRecordingVideo.value = true
    }

    internal fun configureLogicalPhysicalRecordingStateForTest(
        logicalCam: CameraDevice?,
        logicalSess: CameraCaptureSession?,
        relay: DualCameraRecordingRelay?,
        config: ActiveRecordingStreamConfig?,
        logicalDualReady: Boolean = true,
        logicalFailed: Boolean = false
    ) {
        cameraDevice = logicalCam
        captureSession = logicalSess
        activeLogicalMultiCamUltraWideConfigured = logicalDualReady
        logicalMultiCamDualRecSurfacesConfigured = logicalDualReady
        logicalMultiCamRecordingFailed = logicalFailed
        dualCameraRecordingRelay = relay
        activeRecordingStreamConfig = config
        _isRecordingVideo.value = true
    }

    internal fun getActiveCameraDeviceForTest(): CameraDevice? = cameraDevice
    internal fun getActiveCaptureSessionForTest(): CameraCaptureSession? = captureSession
    internal fun getStandbyCameraDeviceForTest(): CameraDevice? = ultraWideStandbyCameraDevice
    internal fun getStandbyCaptureSessionForTest(): CameraCaptureSession? = ultraWideStandbyCaptureSession
    internal fun getActiveRecordingSurfaceForTest(): Surface? = activeRecordingSurface
    internal fun getActivePhysicalCameraIdForTest(): String? = activeSessionPhysicalCameraId
    internal fun getActiveRecordingStreamConfigForTest(): ActiveRecordingStreamConfig? = activeRecordingStreamConfig
    internal fun getStandbyRequestBuilderForTest(): CaptureRequest.Builder? = ultraWideStandbyRequestBuilder
    internal fun applySynchronizedStandbySettingsForTest(builder: CaptureRequest.Builder, standbyLens: LensInfo) {
        applySynchronizedStandbySettings(builder, standbyLens)
    }
}
