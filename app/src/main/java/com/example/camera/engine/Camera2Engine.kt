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
import com.example.camera.engine.night.*
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

class Camera2Engine(private val context: Context) {

    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    // Background threads
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

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
    val motorolaSwitchEngine by lazy { MotorolaInstantSwitchEngine(context) }
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

    // State Flows
    private val _availableLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    val availableLenses: StateFlow<List<LensInfo>> = _availableLenses.asStateFlow()

    private val _selectedLens = MutableStateFlow<LensInfo?>(null)
    val selectedLens: StateFlow<LensInfo?> = _selectedLens.asStateFlow()

    private val _capabilities = MutableStateFlow(HardwareCapabilities())
    val capabilities: StateFlow<HardwareCapabilities> = _capabilities.asStateFlow()

    private val _selectedPhotoResolution = MutableStateFlow<CameraResolution?>(null)
    val selectedPhotoResolution: StateFlow<CameraResolution?> = _selectedPhotoResolution.asStateFlow()

    private val _selectedVideoResolution = MutableStateFlow<CameraResolution?>(null)
    val selectedVideoResolution: StateFlow<CameraResolution?> = _selectedVideoResolution.asStateFlow()

    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private val _isRecordingVideo = MutableStateFlow(false)
    val isRecordingVideo: StateFlow<Boolean> = _isRecordingVideo.asStateFlow()

    private val _videoDurationSeconds = MutableStateFlow(0)
    val videoDurationSeconds: StateFlow<Int> = _videoDurationSeconds.asStateFlow()

    private val _lastCapturedMedia = MutableStateFlow<CapturedMedia?>(null)
    val lastCapturedMedia: StateFlow<CapturedMedia?> = _lastCapturedMedia.asStateFlow()

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
    var isAfLocked: Boolean = false
    var isRawCaptureEnabled: Boolean = false
    var isVideoStabilizationEnabled: Boolean = true
    var videoBitrateOption: VideoBitrateOption = VideoBitrateOption.AUTO
    var videoFps: Int = 30
    var colorProfile: ColorProfile = ColorProfile.STANDARD
    var isAudioEnabled: Boolean = true
    var currentZoom: Float = 1.0f
    private val _currentZoom = MutableStateFlow(1.0f)
    val currentZoomState: StateFlow<Float> = _currentZoom.asStateFlow()
    var saveSelfieAsPreviewed: Boolean = true
    var viewfinderResolution: ViewfinderResolution = ViewfinderResolution.NORMAL
    var selectedPhotoFilter: PhotoFilter = PhotoFilter.ORIGINAL
    private val cinemaSoftwareRecorder by lazy { CinemaSoftwareRecordingEngine(context) }
    private var isSoftwareCinemaRecording: Boolean = false

    private val _previewBufferSize = MutableStateFlow<Size?>(null)
    val previewBufferSize: StateFlow<Size?> = _previewBufferSize.asStateFlow()

    private val _sensorOrientation = MutableStateFlow(90)
    val sensorOrientation: StateFlow<Int> = _sensorOrientation.asStateFlow()

    private var cameraOpenRetryCount = 0
    private val MAX_CAMERA_OPEN_RETRIES = 3

    private val _isAeLockedFlow = MutableStateFlow(false)
    val isAeLockedFlow: StateFlow<Boolean> = _isAeLockedFlow.asStateFlow()

    private val _isAfLockedFlow = MutableStateFlow(false)
    val isAfLockedFlow: StateFlow<Boolean> = _isAfLockedFlow.asStateFlow()

    private var activeMeteringRectangle: MeteringRectangle? = null

    val dollyZoomEngine = DollyZoomEngine()
    val nightFusionProcessor = NightFusionProcessor()
    val gyroStabilizationEngine = GyroStabilizationEngine(context)
    val humanVisionEngine = com.example.camera.engine.humanvision.HumanVisionEngine(context)
    val humanVisionProgress: StateFlow<com.example.camera.engine.humanvision.HumanVisionProgress> = humanVisionEngine.progressState

    private val _hybridStabilizationConfig = MutableStateFlow(HybridStabilizationConfig())
    val hybridStabilizationConfig: StateFlow<HybridStabilizationConfig> = _hybridStabilizationConfig.asStateFlow()

    private val _nightProgress = MutableStateFlow(NightCaptureProgress())
    val nightProgress: StateFlow<NightCaptureProgress> = _nightProgress.asStateFlow()

    fun updateHybridStabilizationConfig(config: HybridStabilizationConfig) {
        _hybridStabilizationConfig.value = config
        val isVideoMode = currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA ||
                currentMode == CameraMode.DOLLY_ZOOM
        if (config.isUltraStabilizationEnabled && isVideoMode) {
            gyroStabilizationEngine.start()
        } else {
            gyroStabilizationEngine.stop()
            lastStabilizedCrop = null
        }
        updatePreviewSettings()
    }

    // Camera Session Concurrency & State Guard
    private val cameraLifecycleLock = Any()
    @Volatile
    private var isStartingCamera = false
    @Volatile
    private var isClosingCamera = false
    @Volatile
    private var isConfiguringSession = false
    @Volatile
    private var restartPending = false
    private var zoomDebounceJob: Job? = null
    private val isStartingRecording = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isStoppingRecording = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isSwitchingLens = java.util.concurrent.atomic.AtomicBoolean(false)

    val cinemaEngine = CinemaEngine(context)
    private val _cinemaConfig = MutableStateFlow(preferences.getCinemaConfig())
    val cinemaConfig: StateFlow<CinemaConfig> = _cinemaConfig.asStateFlow()
    private val _cinemaCapabilities = MutableStateFlow(cinemaEngine.capabilities)
    val cinemaCapabilities: StateFlow<CinemaHardwareCapabilities> = _cinemaCapabilities.asStateFlow()
    val rec2020AutoToneParams: StateFlow<Rec2020AutoToneParams> = cinemaEngine.rec2020AutoToneEngine.currentParams
    val nativeNaturalParams: StateFlow<NativeNaturalToneParams> = cinemaEngine.nativeNaturalEngine.currentParams

    val ultraRes50MStacker = UltraRes50MStacker(context)
    val refocusEngine = RefocusEngine(context)
    val highQualityZoomEngine = com.example.camera.zoom.HighQualityZoomEngine.getInstance(context)
    var photoMegapixelMode: PhotoMegapixelMode = PhotoMegapixelMode.M12
    var isRefocusPhotoEnabled: Boolean = false
    var refocusFrameCount: Int = 5
    var isHighQualityZoomEnabled: Boolean = true
    var zoomProcessingQuality: com.example.camera.zoom.ZoomProcessingQuality = com.example.camera.zoom.ZoomProcessingQuality.BALANCED
    private val _isZoomProcessing = MutableStateFlow(false)
    val isZoomProcessing: StateFlow<Boolean> = _isZoomProcessing.asStateFlow()
    private val _zoomProgress = MutableStateFlow(0f)
    val zoomProgress: StateFlow<Float> = _zoomProgress.asStateFlow()

    init {
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
        if (backgroundThread == null) {
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

    /**
     * Detects all real physical and logical lenses available on the device,
     * including hidden auxiliary cameras, multi-camera physical streams, and integrated ultra-wide zoom ratios.
     */
    fun detectHardwareLenses(forceDeepScan: Boolean = false): Int {
        val mgr = cameraManager ?: return 0
        try {
            val officialIds = try {
                mgr.cameraIdList.toList()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to get cameraIdList", t)
                emptyList<String>()
            }
            val candidateIds = linkedSetOf<String>()
            candidateIds.addAll(officialIds)

            // Discover vendor-hidden auxiliary camera IDs (common on Samsung, Xiaomi, OnePlus, Vivo)
            for (testId in 0..12) {
                val sId = testId.toString()
                if (!candidateIds.contains(sId)) {
                    try {
                        val chars = mgr.getCameraCharacteristics(sId)
                        if (chars != null) {
                            candidateIds.add(sId)
                        }
                    } catch (ignored: Throwable) {}
                }
            }

            val lenses = mutableListOf<LensInfo>()
            val processedPhysicalIds = mutableSetOf<String>()

            val primaryBackId = candidateIds.firstOrNull { id ->
                try {
                    getCharacteristics(id)?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } catch (t: Throwable) { false }
            } ?: candidateIds.firstOrNull() ?: "0"

            val primaryFrontId = candidateIds.firstOrNull { id ->
                try {
                    getCharacteristics(id)?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                } catch (t: Throwable) { false }
            } ?: "1"

            for (id in candidateIds) {
                try {
                    val chars = getCharacteristics(id) ?: continue
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4.0f)
                    val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)
                    val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    val maxAperture = apertures.firstOrNull() ?: 1.8f
                    val primaryFocalMm = focalLengths.firstOrNull() ?: 4.0f

                    val cropFactor = if (sensorSize != null && sensorSize.width > 0) {
                        36f / sensorSize.width
                    } else {
                        7f
                    }

                    // For each focal length supported by this camera ID
                    for (focalMm in focalLengths) {
                        val eq35mm = focalMm * cropFactor
                        val fovDegrees = if (sensorSize != null && sensorSize.width > 0 && focalMm > 0) {
                            (2.0 * kotlin.math.atan(sensorSize.width.toDouble() / (2.0 * focalMm.toDouble())) * (180.0 / Math.PI)).toFloat()
                        } else {
                            0f
                        }

                        val isBack = facing == CameraCharacteristics.LENS_FACING_BACK
                        val isUltraWide = isBack && ((eq35mm in 1.0f..23.4f) || focalMm <= 2.8f || fovDegrees >= 88.0f)
                        val isTele3x = isBack && (eq35mm >= 70f || focalMm >= 9.0f)
                        val isTele2x = isBack && !isTele3x && (eq35mm in 45f..70f || focalMm in 5.9f..9.0f)
                        val isMacro = isBack && minFocus > 10f && focalMm < 3.2f
                        val isMain = isBack && !isUltraWide && !isTele3x && !isTele2x && !isMacro

                        val lensType = when {
                            facing == CameraCharacteristics.LENS_FACING_FRONT -> LensType.FRONT
                            isUltraWide -> LensType.ULTRAWIDE
                            isTele3x -> LensType.TELEPHOTO_3X
                            isTele2x -> LensType.TELEPHOTO
                            isMacro -> LensType.MACRO
                            else -> LensType.WIDE
                        }

                        val isOfficial = officialIds.contains(id)
                        val idDesc = when {
                            !isOfficial -> "Hidden Aux ID $id"
                            else -> "Camera ID $id"
                        }

                        val displayName = when (lensType) {
                            LensType.FRONT -> "Front Selfie (f/${maxAperture})"
                            LensType.ULTRAWIDE -> "0.5x Ultra Wide (${focalMm}mm f/${maxAperture})"
                            LensType.WIDE -> "1x Main (${focalMm}mm f/${maxAperture})"
                            LensType.TELEPHOTO -> "2x Telephoto (${focalMm}mm f/${maxAperture})"
                            LensType.TELEPHOTO_3X -> "3x Telephoto (${focalMm}mm f/${maxAperture})"
                            LensType.MACRO -> "Macro (${focalMm}mm)"
                        }

                        lenses.add(
                            LensInfo(
                                cameraId = id,
                                facing = facing,
                                lensType = lensType,
                                displayName = displayName,
                                focalLengthMm = focalMm,
                                maxAperture = maxAperture,
                                isPhysical = true,
                                isHiddenAux = !isOfficial,
                                isZoomPreset = false,
                                baseZoomRatio = when (lensType) {
                                    LensType.ULTRAWIDE -> 0.5f
                                    LensType.WIDE -> 1.0f
                                    LensType.TELEPHOTO -> 2.0f
                                    LensType.TELEPHOTO_3X -> 3.0f
                                    LensType.MACRO -> 1.0f
                                    LensType.FRONT -> 1.0f
                                },
                                fovDegrees = fovDegrees,
                                equivalent35mmFocalMm = eq35mm,
                                idTypeDescription = idDesc
                            )
                        )
                    }

                    // Android 9+ Physical camera inspection inside logical multi-camera
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val physicalCameraIds = chars.physicalCameraIds
                        for (physId in physicalCameraIds) {
                            if (!processedPhysicalIds.contains(physId)) {
                                processedPhysicalIds.add(physId)
                                try {
                                    val physChars = getCharacteristics(physId) ?: continue
                                    val pFacing = physChars.get(CameraCharacteristics.LENS_FACING) ?: facing
                                    val pFocals = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4f)
                                    val pApertures = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)
                                    val pSensor = physChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                                    val pMinFocus = physChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

                                    val pFocal = pFocals.firstOrNull() ?: 4f
                                    val pAperture = pApertures.firstOrNull() ?: 1.8f
                                    val pCrop = if (pSensor != null && pSensor.width > 0) 36f / pSensor.width else 7f
                                    val pEq35 = pFocal * pCrop
                                    val pFov = if (pSensor != null && pSensor.width > 0 && pFocal > 0) {
                                        (2.0 * kotlin.math.atan(pSensor.width.toDouble() / (2.0 * pFocal.toDouble())) * (180.0 / Math.PI)).toFloat()
                                    } else 0f

                                    val isPUltraWide = (pEq35 in 1.0f..23.5f) || pFocal <= 2.6f || pFov >= 75.0f
                                    val isPTele3x = pEq35 >= 70f || pFocal >= 9.0f
                                    val isPTele2x = pEq35 in 45f..70f || pFocal in 5.8f..9.0f
                                    val isPMacro = pMinFocus > 10f && pFocal < 3.2f

                                    val pType = when {
                                        pFacing == CameraCharacteristics.LENS_FACING_FRONT -> LensType.FRONT
                                        isPUltraWide -> LensType.ULTRAWIDE
                                        isPTele3x -> LensType.TELEPHOTO_3X
                                        isPTele2x -> LensType.TELEPHOTO
                                        isPMacro -> LensType.MACRO
                                        else -> LensType.WIDE
                                    }

                                    val openableId = if (candidateIds.contains(physId)) physId else id
                                    val isHidden = !officialIds.contains(openableId)
                                    lenses.add(
                                        LensInfo(
                                            cameraId = openableId,
                                            facing = pFacing,
                                            lensType = pType,
                                            displayName = "Physical $physId (${pType.shortLabel} · ${pFocal}mm)",
                                            focalLengthMm = pFocal,
                                            maxAperture = pAperture,
                                            isPhysical = true,
                                            isHiddenAux = isHidden,
                                            isZoomPreset = false,
                                            baseZoomRatio = when (pType) {
                                                LensType.ULTRAWIDE -> 0.5f
                                                LensType.WIDE -> 1.0f
                                                LensType.TELEPHOTO -> 2.0f
                                                LensType.TELEPHOTO_3X -> 3.0f
                                                LensType.MACRO -> 1.0f
                                                LensType.FRONT -> 1.0f
                                            },
                                            physicalCameraId = physId,
                                            fovDegrees = pFov,
                                            equivalent35mmFocalMm = pEq35,
                                            idTypeDescription = "Physical Multi-Cam ID $physId"
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error inspecting physical camera $physId", e)
                                }
                            }
                        }
                    }


                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting camera $id", e)
                }
            }

            // Ensure physical back and front cameras have at least a baseline entry if hardware exists:
            val hasBack = lenses.any { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            val hasFront = lenses.any { it.facing == CameraCharacteristics.LENS_FACING_FRONT }

            // Baseline Main Wide (1x) if no back camera detected yet
            if (!hasBack) {
                lenses.add(
                    LensInfo(
                        cameraId = primaryBackId,
                        facing = CameraCharacteristics.LENS_FACING_BACK,
                        lensType = LensType.WIDE,
                        displayName = "1x Main Camera",
                        focalLengthMm = 4.2f,
                        maxAperture = 1.8f,
                        isPhysical = true,
                        isHiddenAux = false,
                        isZoomPreset = false,
                        baseZoomRatio = 1.0f,
                        fovDegrees = 78f,
                        equivalent35mmFocalMm = 24f,
                        idTypeDescription = "Main Camera (1x)"
                    )
                )
            }

            // Baseline Front Selfie Camera if no front camera detected yet
            if (!hasFront) {
                lenses.add(
                    LensInfo(
                        cameraId = primaryFrontId,
                        facing = CameraCharacteristics.LENS_FACING_FRONT,
                        lensType = LensType.FRONT,
                        displayName = "Front Selfie Camera",
                        focalLengthMm = 3.5f,
                        maxAperture = 2.0f,
                        isPhysical = true,
                        isHiddenAux = false,
                        isZoomPreset = false,
                        baseZoomRatio = 1.0f,
                        fovDegrees = 85f,
                        equivalent35mmFocalMm = 22f,
                        idTypeDescription = "Front Camera (1x)"
                    )
                )
            }

            // Clean, de-duplicate and sort lenses intuitively:
            // 1. Back Ultra-Wide (0.5x)
            // 2. Back Main Wide (1x)
            // 3. Back Telephoto (2x / 3x)
            // 4. Back Macro
            // 5. Additional Physical/Aux lenses
            // 6. Front Selfie (1x)
            val sortedLenses = lenses.distinctBy {
                "${it.cameraId}_${it.lensType.name}_${it.isZoomPreset}_${it.baseZoomRatio}_${it.isPhysical}"
            }.sortedWith(
                compareBy<LensInfo> { it.facing }
                    .thenBy {
                        when (it.lensType) {
                            LensType.ULTRAWIDE -> 0
                            LensType.WIDE -> 1
                            LensType.TELEPHOTO -> 2
                            LensType.TELEPHOTO_3X -> 3
                            LensType.MACRO -> 4
                            LensType.FRONT -> 5
                        }
                    }
                    .thenBy { it.baseZoomRatio }
                    .thenBy { if (it.isPhysical) 0 else 1 }
            )

            _availableLenses.value = sortedLenses

            // Maintain current selection or restore user's saved lens across sessions
            val currentSelected = _selectedLens.value
            val savedLens = preferences.getLastLens(sortedLenses)
            val validSelection = sortedLenses.firstOrNull { it.id == currentSelected?.id }
                ?: savedLens
                ?: sortedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.WIDE && !it.isZoomPreset }
                ?: sortedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                ?: sortedLenses.firstOrNull()

            _selectedLens.value = validSelection
            if (validSelection != null) {
                inspectCapabilities(validSelection.cameraId)
                val savedZoom = preferences.currentZoom
                if (savedZoom > 0f) {
                    currentZoom = savedZoom
                    _currentZoom.value = savedZoom
                } else {
                    currentZoom = validSelection.baseZoomRatio
                    _currentZoom.value = validSelection.baseZoomRatio
                }
                motorolaSwitchEngine.updatePrimaryLens(validSelection, sortedLenses)
            }

            Log.i(TAG, "Total discovered lenses after deep scan: ${sortedLenses.size}")
            return sortedLenses.size
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to detect hardware lenses", t)
            return 0
        }
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
            val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8f

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

            // Photo JPEG Resolutions (incorporates native high-resolution & sensor remosaic modes)
            val standardSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val highResSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    map?.getHighResolutionOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                } catch (e: Exception) {
                    emptyArray()
                }
            } else emptyArray()

            val maxResSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    val m1 = maxResMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                    val m2 = maxResMap?.getHighResolutionOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                    m1 + m2
                } catch (e: Exception) {
                    emptyArray()
                }
            } else emptyArray()

            val allJpegSizes = (standardSizes + highResSizes + maxResSizes).distinctBy { "${it.width}x${it.height}" }
            val photoResolutions = allJpegSizes
                .sortedByDescending { it.width * it.height }
                .map { CameraResolution(it.width, it.height, ImageFormat.JPEG, isRaw = false) }

            // RAW Resolutions
            val standardRawSizes = if (hasRaw) {
                map?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
            } else emptyArray()
            val highResRawSizes = if (hasRaw && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    map?.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
                } catch (e: Exception) {
                    emptyArray()
                }
            } else emptyArray()
            val maxResRawSizes = if (hasRaw && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    val m1 = maxResMap?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
                    val m2 = maxResMap?.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
                    m1 + m2
                } catch (e: Exception) {
                    emptyArray()
                }
            } else emptyArray()

            val allRawSizes = (standardRawSizes + highResRawSizes + maxResRawSizes).distinctBy { "${it.width}x${it.height}" }
            val rawResolutions = allRawSizes
                .sortedByDescending { it.width * it.height }
                .map { CameraResolution(it.width, it.height, ImageFormat.RAW_SENSOR, isRaw = true) }

            // Video Resolutions: strictly validate against camera sensor's supported sizes
            val videoSizes = map?.getOutputSizes(MediaRecorder::class.java)
                ?: map?.getOutputSizes(SurfaceTexture::class.java)
                ?: emptyArray()

            val standardVideoQualities = listOf(
                CameraResolution(3840, 2160), // 4K UHD
                CameraResolution(1920, 1080), // 1080p FHD
                CameraResolution(1280, 720),  // 720p HD
                CameraResolution(720, 480)    // 480p SD
            )
            val filteredVideoResolutions = if (videoSizes.isNotEmpty()) {
                val matched = standardVideoQualities.filter { standard ->
                    videoSizes.any { it.width == standard.width && it.height == standard.height }
                }
                if (matched.isNotEmpty()) {
                    matched
                } else {
                    videoSizes
                        .filter { it.width >= 640 && it.height >= 480 }
                        .sortedByDescending { it.width.toLong() * it.height.toLong() }
                        .map { CameraResolution(it.width, it.height) }
                        .distinctBy { "${it.width}x${it.height}" }
                }
            } else {
                listOf(
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
                maxZoom = maxZoom
            )

            _capabilities.value = hardwareCaps

            cinemaEngine.onCameraConfigured(chars, filteredVideoResolutions)
            _cinemaCapabilities.value = cinemaEngine.capabilities

            // Default resolutions
            if (_selectedPhotoResolution.value == null || !photoResolutions.contains(_selectedPhotoResolution.value)) {
                _selectedPhotoResolution.value = photoResolutions.firstOrNull()
            }
            if (_selectedVideoResolution.value == null || !filteredVideoResolutions.contains(_selectedVideoResolution.value)) {
                _selectedVideoResolution.value = filteredVideoResolutions.firstOrNull { it.height == 1080 }
                    ?: filteredVideoResolutions.firstOrNull()
            }

            updatePreviewAspectRatio()
        } catch (t: Throwable) {
            Log.e(TAG, "Error inspecting capabilities for camera $cameraId", t)
        }
    }

    fun getTargetAspectRatioForMode(mode: CameraMode = currentMode): Float {
        return when (mode) {
            CameraMode.PHOTO, CameraMode.PORTRAIT -> 4f / 3f // Fixed 3:4 portrait (sensor landscape 4:3)
            else -> 16f / 9f // Fixed 9:16 portrait (sensor landscape 16:9)
        }
    }

    private fun updatePreviewAspectRatio() {
        _previewAspectRatio.value = getTargetAspectRatioForMode(currentMode)
    }

    /**
     * Switch active lens
     */
    fun selectLens(lens: LensInfo, preserveZoom: Boolean = false, targetZoom: Float? = null) {
        if (isStartingRecording.get() || isStoppingRecording.get()) {
            Log.w(TAG, "Lens switch ignored: video recording is transitioning")
            return
        }
        if (!isSwitchingLens.compareAndSet(false, true)) {
            Log.d(TAG, "Lens switch already in progress, ignoring duplicate call")
            return
        }

        try {
            val previousLens = _selectedLens.value
            _selectedLens.value = lens

            if (preserveZoom) {
                val z = targetZoom ?: currentZoom
                currentZoom = z
                _currentZoom.value = z
                preferences.saveLastLens(lens)
                preferences.currentZoom = z
            } else {
                currentZoom = lens.baseZoomRatio
                _currentZoom.value = lens.baseZoomRatio
                preferences.saveLastLens(lens)
                preferences.currentZoom = lens.baseZoomRatio
            }

            // If same camera ID and same facing, update optical zoom/crop dynamically without restarting hardware
            if (previousLens?.cameraId == lens.cameraId &&
                previousLens?.facing == lens.facing &&
                cameraDevice != null) {
                updatePreviewSettings()
                motorolaSwitchEngine.updatePrimaryLens(lens, _availableLenses.value)
                motorolaSwitchEngine.compositor.switchActiveStream(lens.lensType)
                return
            }

            val switchStartNs = System.nanoTime()

            // Seamless lens switch during active video recording
            if (_isRecordingVideo.value) {
                Log.i(TAG, "[RECORDING_SWITCH] Switching active lens during video recording to ${lens.lensType} (Camera ID: ${lens.cameraId})")
                val warmDevice = motorolaSwitchEngine.handoffBackgroundCamera(lens)
                    ?: motorolaSwitchEngine.getStandbyCameraDevice(lens)
                if (warmDevice != null) {
                    inspectCapabilities(lens.cameraId)
                    switchWithWarmCamera(warmDevice, lens, switchStartNs)
                    motorolaSwitchEngine.compositor.switchActiveStream(lens.lensType, switchStartNs)
                    return
                }
                // Target camera device not pre-warmed: seamlessly switch recording pipeline without stopping MediaRecorder
                inspectCapabilities(lens.cameraId)
                switchCameraDuringRecording(lens, switchStartNs)
                return
            }

            // 1. Instant Concurrent Switch if target camera session is already streaming in standby
            if (motorolaSwitchEngine.isConcurrentSessionReady(lens)) {
                val bundle = motorolaSwitchEngine.switchConcurrentLens(
                    targetLens = lens,
                    currentDevice = cameraDevice,
                    currentSession = captureSession,
                    currentJpegReader = imageReaderJpeg,
                    currentYuvReader = imageReaderYuv,
                    currentLens = previousLens,
                    switchStartNs = switchStartNs
                )
                if (bundle != null) {
                    cameraDevice = bundle.cameraDevice
                    captureSession = bundle.captureSession
                    imageReaderJpeg = bundle.imageReaderJpeg
                    imageReaderYuv = bundle.imageReaderYuv
                    _isCameraReady.value = true
                    inspectCapabilities(lens.cameraId)

                    val activeJpegW = bundle.imageReaderJpeg?.width ?: 0
                    val activeJpegH = bundle.imageReaderJpeg?.height ?: 0
                    val activeYuvW = bundle.imageReaderYuv?.width ?: 0
                    val activeYuvH = bundle.imageReaderYuv?.height ?: 0
                    val activeMp = (activeJpegW.toLong() * activeJpegH.toLong()) / 1_000_000f
                    Log.i(TAG, "[PHOTO_RES] Switched active lens to ${lens.lensType}: ImageReader JPEG=${activeJpegW}x${activeJpegH} (~${activeMp}MP), YUV=${activeYuvW}x${activeYuvH}")

                    val optimalPhotoSize = getOptimalPhotoSizeForLens(lens, lens.cameraId)
                    if (bundle.imageReaderJpeg == null || (lens.lensType == LensType.ULTRAWIDE && activeJpegW < 2500)) {
                        Log.i(TAG, "[PHOTO_RES] Updating Ultra-Wide ImageReader to native resolution: ${optimalPhotoSize.width}x${optimalPhotoSize.height}")
                        setupImageReaders(lens.cameraId)
                        createCameraCaptureSession()
                    } else if (activeJpegW > 0 && activeJpegH > 0) {
                        _selectedPhotoResolution.value = CameraResolution(activeJpegW, activeJpegH, ImageFormat.JPEG)
                        updatePreviewAspectRatio()
                    }

                    // Update previewRequestBuilder targeting the compositor surface for the newly active lens
                    val targetSurf = if (lens.lensType == LensType.ULTRAWIDE) {
                        motorolaSwitchEngine.compositor.ultraWideCameraSurface
                    } else {
                        motorolaSwitchEngine.compositor.mainCameraSurface
                    } ?: previewSurface

                    if (targetSurf != null && targetSurf.isValid) {
                        try {
                            val newBuilder = bundle.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(targetSurf)
                                applyCommonSettings(this)
                            }
                            previewRequestBuilder = newBuilder
                            bundle.captureSession.setRepeatingRequest(newBuilder.build(), captureCallback, backgroundHandler)
                            if (lens.lensType == LensType.ULTRAWIDE) {
                                Log.i(TAG, "[UW_SWITCH] target session active")
                            } else {
                                Log.i(TAG, "[SWITCH] target session active for ${lens.lensType}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to submit repeating preview request on target session for ${lens.lensType}", e)
                        }
                    }

                    val elapsedMs = (System.nanoTime() - switchStartNs) / 1_000_000L
                    Log.i(TAG, "[INSTANT CONCURRENT SWITCH] Switched to ${lens.lensType} in ${elapsedMs}ms (0 sessions recreated)")
                    return
                }
            }

            // 2. Fast Handover if target camera was warm in background (non-concurrent HAL):
            val warmDevice = motorolaSwitchEngine.handoffBackgroundCamera(lens)
            if (warmDevice != null) {
                inspectCapabilities(lens.cameraId)
                switchWithWarmCamera(warmDevice, lens, switchStartNs)
                return
            }

            inspectCapabilities(lens.cameraId)
            restartCamera()
        } finally {
            isSwitchingLens.set(false)
        }
    }

    private fun switchCameraDuringRecording(lens: LensInfo, switchStartNs: Long) {
        val mgr = cameraManager ?: return
        startBackgroundThread()
        backgroundHandler?.post {
            synchronized(cameraLifecycleLock) {
                val oldSession = captureSession
                val oldDevice = cameraDevice
                captureSession = null
                try {
                    oldSession?.close()
                } catch (ignored: Throwable) {}
                try {
                    oldDevice?.close()
                } catch (ignored: Throwable) {}
                cameraDevice = null

                try {
                    mgr.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            synchronized(cameraLifecycleLock) {
                                cameraDevice = camera
                                val texture = previewSurfaceTexture ?: return
                                val optimalSize = _previewBufferSize.value ?: Size(1920, 1080)
                                val isPhotoOrPortrait = (currentMode == CameraMode.PHOTO || currentMode == CameraMode.PORTRAIT)
                                val targetW = if (viewfinderWidth > 0) viewfinderWidth else 1080
                                val targetH = if (viewfinderHeight > 0) viewfinderHeight else if (isPhotoOrPortrait) 1440 else 1920
                                val cameraW = max(optimalSize.width, optimalSize.height)
                                val cameraH = min(optimalSize.width, optimalSize.height)
                                if (previewSurface == null || !previewSurface!!.isValid) {
                                    try { previewSurface?.release() } catch (ignored: Throwable) {}
                                    previewSurface = Surface(texture)
                                }
                                motorolaSwitchEngine.compositor.setDefaultBufferSize(cameraW, cameraH)
                                motorolaSwitchEngine.compositor.setMainViewfinderSurface(previewSurface, targetW, targetH)
                                motorolaSwitchEngine.compositor.switchActiveStream(lens.lensType, switchStartNs)
                                createCameraCaptureSession()
                                motorolaSwitchEngine.updatePrimaryLens(lens, _availableLenses.value)
                                val elapsedMs = (System.nanoTime() - switchStartNs) / 1_000_000L
                                Log.i(TAG, "[RECORDING_SWITCH] Seamless lens switch to ${lens.lensType} completed in ${elapsedMs}ms")
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (cameraDevice == camera) cameraDevice = null
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e(TAG, "Error opening camera ${lens.cameraId} during recording: $error")
                            camera.close()
                            if (cameraDevice == camera) cameraDevice = null
                        }
                    }, backgroundHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open camera ${lens.cameraId} during recording", e)
                }
            }
        }
    }

    private fun switchWithWarmCamera(warmDevice: CameraDevice, lens: LensInfo, switchStartNs: Long = System.nanoTime()) {
        startBackgroundThread()
        backgroundHandler?.post {
            synchronized(cameraLifecycleLock) {
                val oldDevice = cameraDevice
                closeCameraCaptureSession()
                try {
                    oldDevice?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing previous camera during warm handover", e)
                }
                cameraDevice = warmDevice
                val texture = previewSurfaceTexture ?: return@synchronized
                val optimalSize = _previewBufferSize.value ?: Size(1920, 1080)
                val isPhotoOrPortrait = (currentMode == CameraMode.PHOTO || currentMode == CameraMode.PORTRAIT)
                val targetW = if (viewfinderWidth > 0) viewfinderWidth else 1080
                val targetH = if (viewfinderHeight > 0) viewfinderHeight else if (isPhotoOrPortrait) 1440 else 1920
                val cameraW = max(optimalSize.width, optimalSize.height)
                val cameraH = min(optimalSize.width, optimalSize.height)

                val curSurf = previewSurface
                if (curSurf == null || !curSurf.isValid) {
                    try { curSurf?.release() } catch (ignored: Throwable) {}
                    previewSurface = Surface(texture)
                }

                motorolaSwitchEngine.compositor.setDefaultBufferSize(cameraW, cameraH)
                motorolaSwitchEngine.compositor.setMainViewfinderSurface(previewSurface, targetW, targetH)
                motorolaSwitchEngine.compositor.switchActiveStream(lens.lensType, switchStartNs)

                setupImageReaders(lens.cameraId)
                createCameraCaptureSession()
                motorolaSwitchEngine.updatePrimaryLens(lens, _availableLenses.value)
                val elapsedMs = (System.nanoTime() - switchStartNs) / 1_000_000L
                Log.i(TAG, "[LATENCY] Fast handover to ${lens.lensType} completed in ${elapsedMs}ms")
            }
        } ?: run {
            closeCamera()
            startCamera()
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
        _selectedVideoResolution.value = resolution
        updatePreviewAspectRatio()
        if (cameraDevice != null) {
            reconfigureSession()
        } else {
            restartCamera()
        }
    }

    /**
     * Restore saved video resolution without triggering camera restart before viewfinder is attached
     */
    fun restoreInitialVideoResolution(resolution: CameraResolution) {
        _selectedVideoResolution.value = resolution
        updatePreviewAspectRatio()
    }

    /**
     * Switch between Photo, Portrait, Video & Cinema modes smoothly without closing hardware device
     */
    fun setMode(mode: CameraMode) {
        if (currentMode == mode) return
        val wasPhotoOrPortrait = (currentMode == CameraMode.PHOTO || currentMode == CameraMode.PORTRAIT)
        val isPhotoOrPortrait = (mode == CameraMode.PHOTO || mode == CameraMode.PORTRAIT)
        val wasMore = (currentMode == CameraMode.MORE)
        if (_isRecordingVideo.value) {
            stopVideoRecording()
        }
        currentMode = mode
        updatePreviewAspectRatio()

        val isVideoMode = (mode == CameraMode.VIDEO || mode == CameraMode.CINEMA ||
                mode == CameraMode.DOLLY_ZOOM)
        if (!isVideoMode || !_hybridStabilizationConfig.value.isUltraStabilizationEnabled) {
            gyroStabilizationEngine.stop()
            lastStabilizedCrop = null
        } else if (isVideoMode && _hybridStabilizationConfig.value.isUltraStabilizationEnabled) {
            gyroStabilizationEngine.start()
        }

        val needsReconfigure = (wasPhotoOrPortrait != isPhotoOrPortrait) ||
                (wasMore && isPhotoOrPortrait) ||
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
                    Log.d(TAG, "reconfigureSession already in progress, skipping")
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
                    val chars = getCharacteristics(lens.cameraId) ?: run {
                        isConfiguringSession = false
                        return@synchronized
                    }
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
                        isConfiguringSession = false
                        return@synchronized
                    }
                    val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()

                    val targetRatio = getTargetAspectRatioForMode(currentMode)
                    val maxDim = viewfinderResolution.maxDimension
                    val matchingRatioSizes = previewSizes.filter {
                        val r = max(it.width, it.height).toFloat() / min(it.width, it.height).toFloat()
                        kotlin.math.abs(r - targetRatio) < 0.08f
                    }

                    val optimalPreviewSize = matchingRatioSizes
                        .filter { max(it.width, it.height) <= maxDim }
                        .maxByOrNull { it.width * it.height }
                        ?: matchingRatioSizes.minByOrNull { max(it.width, it.height) }
                        ?: previewSizes.firstOrNull { it.width <= 1920 && it.height <= 1080 }
                        ?: previewSizes.firstOrNull()
                        ?: Size(1920, 1080)

                    _previewAspectRatio.value = targetRatio
                    _previewBufferSize.value = optimalPreviewSize
                    val isPhotoOrPortrait = (currentMode == CameraMode.PHOTO || currentMode == CameraMode.PORTRAIT)
                    val targetW = if (viewfinderWidth > 0) viewfinderWidth else 1080
                    val targetH = if (viewfinderHeight > 0) viewfinderHeight else if (isPhotoOrPortrait) 1440 else 1920
                    val cameraW = max(optimalPreviewSize.width, optimalPreviewSize.height)
                    val cameraH = min(optimalPreviewSize.width, optimalPreviewSize.height)

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

                    // Direct native Surface connection to TextureView
                    var curSurf = previewSurface
                    if (curSurf == null || !curSurf.isValid) {
                        try {
                            curSurf?.release()
                        } catch (ignored: Exception) {}
                        curSurf = Surface(texture)
                        previewSurface = curSurf
                    }

                    motorolaSwitchEngine.compositor.setDefaultBufferSize(cameraW, cameraH)
                    motorolaSwitchEngine.compositor.setMainViewfinderSurface(curSurf, targetW, targetH)
                    motorolaSwitchEngine.compositor.switchActiveStream(lens.lensType)

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
        cinemaEngine.config = newConfig
        _cinemaConfig.value = newConfig
        if (currentMode == CameraMode.CINEMA) {
            updatePreviewAspectRatio()
            updatePreviewSettings()
        }
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
        var curSurf = previewSurface
        if (curSurf == null || !curSurf.isValid) {
            try { curSurf?.release() } catch (ignored: Throwable) {}
            curSurf = Surface(texture)
            previewSurface = curSurf
        }
        val isPhotoOrPortrait = (currentMode == CameraMode.PHOTO || currentMode == CameraMode.PORTRAIT)
        val targetW = if (viewfinderWidth > 0) viewfinderWidth else 1080
        val targetH = if (viewfinderHeight > 0) viewfinderHeight else if (isPhotoOrPortrait) 1440 else 1920
        motorolaSwitchEngine.compositor.setMainViewfinderSurface(curSurf, targetW, targetH)
        motorolaSwitchEngine.compositor.triggerRender()
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
            val isPhotoOrPortrait = (currentMode == CameraMode.PHOTO || currentMode == CameraMode.PORTRAIT)
            val optimalSize = _previewBufferSize.value ?: Size(1920, 1080)
            val cameraW = max(optimalSize.width, optimalSize.height)
            val cameraH = min(optimalSize.width, optimalSize.height)

            if (previewSurface == null || !previewSurface!!.isValid) {
                try { previewSurface?.release() } catch (ignored: Throwable) {}
                previewSurface = Surface(texture)
            }
            motorolaSwitchEngine.compositor.setDefaultBufferSize(cameraW, cameraH)
            val targetW = if (viewfinderWidth > 0) viewfinderWidth else 1080
            val targetH = if (viewfinderHeight > 0) viewfinderHeight else if (isPhotoOrPortrait) 1440 else 1920
            motorolaSwitchEngine.compositor.setMainViewfinderSurface(previewSurface, targetW, targetH)
            motorolaSwitchEngine.compositor.switchActiveStream(_selectedLens.value?.lensType ?: LensType.WIDE)

            if (prevTexture != texture || cameraDevice == null) {
                if (_isCameraInitialized.value) {
                    startCamera()
                }
            } else if (captureSession == null && !isConfiguringSession && !isStartingCamera) {
                reconfigureSession()
            }
        } else {
            motorolaSwitchEngine.compositor.setMainViewfinderSurface(null, 0, 0)
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
            return
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startCamera aborted: CAMERA permission not granted")
            return
        }

        val lens = _selectedLens.value ?: return
        val texture = previewSurfaceTexture ?: return
        val mgr = cameraManager ?: return

        startBackgroundThread()

        synchronized(cameraLifecycleLock) {
            if (isStartingCamera) {
                restartPending = true
                return
            }
            if (cameraDevice != null) {
                reconfigureSession()
                return
            }
            isStartingCamera = true
        }

        try {
            val chars = getCharacteristics(lens.cameraId) ?: return
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

            // Pick optimal preview size matching selected aspect ratio and viewfinderResolution level
            val targetRatio = getTargetAspectRatioForMode(currentMode)
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()

            val maxDim = viewfinderResolution.maxDimension
            val matchingRatioSizes = previewSizes.filter {
                val r = max(it.width, it.height).toFloat() / min(it.width, it.height).toFloat()
                kotlin.math.abs(r - targetRatio) < 0.08f
            }

            val optimalPreviewSize = matchingRatioSizes
                .filter { max(it.width, it.height) <= maxDim }
                .maxByOrNull { it.width * it.height }
                ?: matchingRatioSizes.minByOrNull { max(it.width, it.height) }
                ?: previewSizes.firstOrNull { it.width <= 1920 && it.height <= 1080 }
                ?: previewSizes.firstOrNull()
                ?: Size(1920, 1080)

            _previewAspectRatio.value = targetRatio
            val sensorOrient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            _sensorOrientation.value = sensorOrient
            _previewBufferSize.value = optimalPreviewSize
            val isPhotoOrPortrait = (currentMode == CameraMode.PHOTO || currentMode == CameraMode.PORTRAIT)
            val targetW = if (viewfinderWidth > 0) viewfinderWidth else 1080
            val targetH = if (viewfinderHeight > 0) viewfinderHeight else if (isPhotoOrPortrait) 1440 else 1920
            val cameraW = max(optimalPreviewSize.width, optimalPreviewSize.height)
            val cameraH = min(optimalPreviewSize.width, optimalPreviewSize.height)

            if (previewSurface == null || !previewSurface!!.isValid) {
                try {
                    previewSurface?.release()
                } catch (ignored: Throwable) {}
                previewSurface = Surface(texture)
            }

            motorolaSwitchEngine.compositor.setDefaultBufferSize(cameraW, cameraH)
            motorolaSwitchEngine.compositor.setMainViewfinderSurface(previewSurface, targetW, targetH)
            motorolaSwitchEngine.compositor.switchActiveStream(lens.lensType)
            motorolaSwitchEngine.compositor.awaitInitialized(200)

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
                    motorolaSwitchEngine.updatePrimaryLens(_selectedLens.value, _availableLenses.value)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _isCameraReady.value = false
                    synchronized(cameraLifecycleLock) {
                        isStartingCamera = false
                        if (restartPending) {
                            restartPending = false
                            backgroundHandler?.post { restartCamera() }
                        }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error (attempt $cameraOpenRetryCount)")
                    camera.close()
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
            _isCameraReady.value = false
        }
    }

    /**
     * Determines the optimal native photo capture resolution for the specified lens.
     * For 0.5x Ultra-Wide, strictly selects the highest native 4:3 resolution (around 8MP, e.g. 3264x2448).
     * Never reuses the main camera resolution.
     */
    fun getOptimalPhotoSizeForLens(lens: LensInfo?, cameraId: String): Size {
        val targetId = lens?.physicalCameraId ?: cameraId
        val chars = try {
            getCharacteristics(targetId) ?: getCharacteristics(cameraId)
        } catch (e: Exception) {
            null
        }
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val highResSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        } else emptyList()
        val maxResSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val maxResMap = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                val m1 = maxResMap?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
                val m2 = maxResMap?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
                m1 + m2
            } catch (e: Exception) { emptyList() }
        } else emptyList()

        val allSizes = (jpegSizes + highResSizes + maxResSizes).distinctBy { "${it.width}x${it.height}" }

        val isUltraWide = lens?.lensType == LensType.ULTRAWIDE
        val is50MMode = photoMegapixelMode == PhotoMegapixelMode.M50

        // 4:3 aspect ratio filter (~1.333)
        val fourThreeSizes = allSizes.filter { size ->
            val ratio = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height).toFloat()
            kotlin.math.abs(ratio - (4f / 3f)) < 0.05f
        }
        // 16:9 aspect ratio filter (~1.777)
        val sixteenNineSizes = allSizes.filter { size ->
            val ratio = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height).toFloat()
            kotlin.math.abs(ratio - (16f / 9f)) < 0.05f
        }

        val isPhotoOrPortrait = (currentMode == CameraMode.PHOTO || currentMode == CameraMode.PORTRAIT)

        return when {
            isPhotoOrPortrait -> {
                if (isUltraWide) {
                    // For 0.5x Ultra-Wide, select highest native 4:3 resolution supported (around 8MP, e.g. 3264x2448)
                    fourThreeSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: allSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: Size(3264, 2448)
                } else if (is50MMode) {
                    allSizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(4000, 3000)
                } else {
                    fourThreeSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: allSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: Size(4000, 3000)
                }
            }
            else -> {
                sixteenNineSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: allSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: Size(1920, 1080)
            }
        }
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
        val targetSize = getOptimalPhotoSizeForLens(activeLens, cameraId)
        _selectedPhotoResolution.value = CameraResolution(targetSize.width, targetSize.height, ImageFormat.JPEG)

        try {
            imageReaderJpeg = ImageReader.newInstance(
                targetSize.width,
                targetSize.height,
                ImageFormat.JPEG,
                4
            )
            val mp = (targetSize.width.toLong() * targetSize.height.toLong()) / 1_000_000f
            Log.i(TAG, "[PHOTO_RES] Recreated ImageReader for ${activeLens?.lensType} (cameraId=$cameraId): JPEG=${targetSize.width}x${targetSize.height} (~${mp}MP)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create ImageReader with ${targetSize.width}x${targetSize.height}, falling back to largest supported", t)
            val fallback = caps.supportedPhotoResolutions.firstOrNull() ?: CameraResolution(1920, 1080)
            try {
                imageReaderJpeg = ImageReader.newInstance(fallback.width, fallback.height, ImageFormat.JPEG, 4)
            } catch (t2: Throwable) {
                Log.e(TAG, "Failed fallback ImageReader", t2)
            }
        }

        try {
            val yuvWidth = imageReaderJpeg?.width ?: targetSize.width
            val yuvHeight = imageReaderJpeg?.height ?: targetSize.height
            imageReaderYuv = ImageReader.newInstance(
                yuvWidth,
                yuvHeight,
                ImageFormat.YUV_420_888,
                3
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create uncompressed YUV ImageReader for Custom Pipeline", t)
        }

        if (caps.supportsRaw && isRawCaptureEnabled && caps.supportedRawResolutions.isNotEmpty()) {
            val rawRes = caps.supportedRawResolutions.first()
            try {
                imageReaderRaw = ImageReader.newInstance(
                    rawRes.width,
                    rawRes.height,
                    ImageFormat.RAW_SENSOR,
                    6
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create RAW ImageReader", t)
            }
        }
    }

    private fun createCameraCaptureSession() {
        val camera = cameraDevice ?: return
        val activeLens = _selectedLens.value
        val isUltraWide = activeLens?.lensType == LensType.ULTRAWIDE
        val compositorSurf = if (isUltraWide) {
            motorolaSwitchEngine.compositor.ultraWideCameraSurface
        } else {
            motorolaSwitchEngine.compositor.mainCameraSurface
        }
        val previewSurf = if (compositorSurf != null && compositorSurf.isValid) {
            compositorSurf
        } else {
            previewSurface ?: return
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

        // Seamless lens switch during active video recording (Front, Back, Ultra-Wide)
        val isRecording = _isRecordingVideo.value
        val recSurface = activeRecordingSurface
        if (isRecording && recSurface != null && recSurface.isValid) {
            try {
                val template = CameraDevice.TEMPLATE_RECORD
                previewRequestBuilder = camera.createCaptureRequest(template).apply {
                    addTarget(previewSurf)
                    addTarget(recSurface)
                    applyCommonSettings(this)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                }

                val is10BitMode = currentMode == CameraMode.CINEMA && cinemaConfig.value.logBitDepth == LogBitDepth.BIT_10
                createRecordingCaptureSession(
                    camera = camera,
                    previewSurface = previewSurf,
                    recorderSurface = recSurface,
                    is10Bit = is10BitMode,
                    callback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            isConfiguringSession = false
                            if (cameraDevice == null) return
                            captureSession = session
                            try {
                                previewRequestBuilder?.let {
                                    session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                                }
                                _isCameraReady.value = true
                                Log.i(TAG, "Seamless lens switch during active recording session completed successfully")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed repeating record request after seamless lens switch", e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            isConfiguringSession = false
                            Log.e(TAG, "Failed to configure video recording session after lens switch")
                            _isCameraReady.value = false
                        }
                    }
                )
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring video recording capture session during lens switch", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            activeLens?.physicalCameraId != null &&
            activeLens.physicalCameraId != activeLens.cameraId) {
            try {
                val outputConfigs = mutableListOf<android.hardware.camera2.params.OutputConfiguration>()
                val previewConfig = android.hardware.camera2.params.OutputConfiguration(previewSurf)
                previewConfig.setPhysicalCameraId(activeLens.physicalCameraId)
                outputConfigs.add(previewConfig)

                imageReaderJpeg?.surface?.let {
                    val jpegConfig = android.hardware.camera2.params.OutputConfiguration(it)
                    jpegConfig.setPhysicalCameraId(activeLens.physicalCameraId)
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

                val template = CameraDevice.TEMPLATE_PREVIEW
                previewRequestBuilder = camera.createCaptureRequest(template).apply {
                    addTarget(previewSurf)
                    applyCommonSettings(this)
                }

                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    java.util.concurrent.Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            isConfiguringSession = false
                            if (cameraDevice == null) return
                            captureSession = session
                            try {
                                previewRequestBuilder?.let {
                                    session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                                }
                                _isCameraReady.value = true
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start repeating preview request", e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            isConfiguringSession = false
                            Log.e(TAG, "Camera capture session configuration failed, scheduling recovery")
                            _isCameraReady.value = false
                            backgroundHandler?.postDelayed({
                                restartCamera()
                            }, 250)
                        }
                    }
                )
                camera.createCaptureSession(sessionConfig)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Falling back to standard session creation: ${e.message}")
            }
        }

        try {
            val surfaces = mutableListOf<Surface>()
            surfaces.add(previewSurf)

            imageReaderJpeg?.surface?.let { surfaces.add(it) }
            imageReaderYuv?.surface?.let { surfaces.add(it) }
            imageReaderRaw?.surface?.let { surfaces.add(it) }

            val template = CameraDevice.TEMPLATE_PREVIEW

            previewRequestBuilder = camera.createCaptureRequest(template).apply {
                addTarget(previewSurf)
                applyCommonSettings(this)
            }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        isConfiguringSession = false
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            previewRequestBuilder?.let {
                                session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                            }
                            _isCameraReady.value = true
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating preview request", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        isConfiguringSession = false
                        Log.e(TAG, "Camera capture session configuration failed, scheduling recovery")
                        _isCameraReady.value = false
                        backgroundHandler?.postDelayed({
                            restartCamera()
                        }, 250)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            isConfiguringSession = false
            Log.e(TAG, "Failed to create camera capture session", e)
        }
    }

    private var lastCaptureResult: TotalCaptureResult? = null
    private var lastHdrUpdateRequestTime = 0L
    private var lastDollyApplyTime = 0L
    private var lastAppliedDollyZoom = 1.0f

    private var lastStabilizedCropTime = 0L
    private var lastStabilizedCrop: Rect? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            lastCaptureResult = result

            if (currentMode == CameraMode.DOLLY_ZOOM) {
                val lens = _selectedLens.value
                if (lens != null) {
                    try {
                        val chars = getCharacteristics(lens.cameraId) ?: return
                        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        } else null
                        val minZoom = zoomRange?.lower ?: 1.0f
                        val maxZoom = zoomRange?.upper ?: (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8f)
                        val newZoom = dollyZoomEngine.processFrame(
                            result = result,
                            sensorRect = sensorRect,
                            minAvailableZoom = minZoom,
                            maxAvailableZoom = maxZoom
                        )
                        if (newZoom != null) {
                            applyContinuousDollyZoom(newZoom)
                        }
                    } catch (ignored: Exception) {}
                }
            } else if (_hybridStabilizationConfig.value.isUltraStabilizationEnabled &&
                (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA)) {
                // When hardware EIS is supported, the camera HAL's internal DSP/ISP handles gyro EIS.
                // Interfering with SCALER_CROP_REGION on every 30fps repeating request disrupts the HAL's internal EIS.
                // We only use custom gyro crop shifting if the hardware sensor lacks native EIS!
                val caps = capabilities.value
                if (!caps.supportsEis) {
                    val lens = _selectedLens.value
                    if (lens != null) {
                        try {
                            val chars = getCharacteristics(lens.cameraId)
                            val activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                            val focalLengths = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            val focalLength = focalLengths?.firstOrNull() ?: 4.38f
                            val sensorSize = chars?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(6.4f, 4.8f)

                            if (activeArray != null) {
                                val crop = gyroStabilizationEngine.computeStabilizedCrop(
                                    result = result,
                                    activeArray = activeArray,
                                    baseZoom = currentZoom,
                                    focalLengthMm = focalLength,
                                    sensorPhysicalSizeMm = sensorSize
                                )
                                if (crop != null) {
                                    applyStabilizedCrop(crop)
                                }
                            }
                        } catch (ignored: Exception) {}
                    }
                }
            }

            // Real-time continuous Auto Tone Control for Cinema Log Profiles
            if (currentMode == CameraMode.CINEMA && _cinemaConfig.value.colorProfile == CinemaColorProfile.REC_2020) {
                val lens = _selectedLens.value
                val chars = if (lens != null) getCharacteristics(lens.cameraId) else null
                cinemaEngine.rec2020AutoToneEngine.onFrameCaptured(result, chars)
                onRec2020AutoToneFrame()
            } else if (currentMode == CameraMode.CINEMA && _cinemaConfig.value.colorProfile == CinemaColorProfile.FLAT_LOG) {
                val lens = _selectedLens.value
                val chars = if (lens != null) getCharacteristics(lens.cameraId) else null
                cinemaEngine.naturalLogEngine.onFrameCaptured(result, chars)
                onNaturalLogAutoToneFrame()
            } else if (currentMode == CameraMode.CINEMA && _cinemaConfig.value.colorProfile == CinemaColorProfile.NATIVE) {
                val lens = _selectedLens.value
                val chars = if (lens != null) getCharacteristics(lens.cameraId) else null
                cinemaEngine.nativeNaturalEngine.onFrameCaptured(result, chars)
                onNativeNaturalAutoToneFrame()
            }
        }
    }

    fun onFrameLuminanceStats(stats: FrameLuminanceStats) {
        cinemaEngine.naturalLogEngine.onFrameLuminanceAnalyzed(stats)
        if (currentMode == CameraMode.CINEMA && _cinemaConfig.value.colorProfile == CinemaColorProfile.FLAT_LOG) {
            onNaturalLogAutoToneFrame()
        }
    }

    private var lastNaturalLogIspUpdateTime = 0L
    private fun onNaturalLogAutoToneFrame() {
        val now = System.currentTimeMillis()
        if (now - lastNaturalLogIspUpdateTime < 66L) return // 15fps throttle for repeating ISP tonemap updates
        if (!cinemaEngine.naturalLogEngine.hasSignificantChangeSinceLastIspUpdate()) return
        lastNaturalLogIspUpdateTime = now
        cinemaEngine.naturalLogEngine.markIspUpdated()
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            cinemaEngine.applyToCaptureRequest(builder)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (ignored: Exception) {}
    }

    private var lastRec2020IspUpdateTime = 0L
    private fun onRec2020AutoToneFrame() {
        val now = System.currentTimeMillis()
        if (now - lastRec2020IspUpdateTime < 66L) return // 15fps throttle for repeating ISP tonemap updates
        if (!cinemaEngine.rec2020AutoToneEngine.hasSignificantChangeSinceLastIspUpdate()) return
        lastRec2020IspUpdateTime = now
        cinemaEngine.rec2020AutoToneEngine.markIspUpdated()
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            cinemaEngine.applyToCaptureRequest(builder)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (ignored: Exception) {}
    }

    private var lastNativeNaturalIspUpdateTime = 0L
    private fun onNativeNaturalAutoToneFrame() {
        val now = System.currentTimeMillis()
        if (now - lastNativeNaturalIspUpdateTime < 66L) return // 15fps throttle for repeating ISP tonemap updates
        if (!cinemaEngine.nativeNaturalEngine.hasSignificantChangeSinceLastIspUpdate()) return
        lastNativeNaturalIspUpdateTime = now
        cinemaEngine.nativeNaturalEngine.markIspUpdated()
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            cinemaEngine.applyToCaptureRequest(builder)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (ignored: Exception) {}
    }

    private fun applyStabilizedCrop(crop: Rect) {
        val now = System.currentTimeMillis()
        if (now - lastStabilizedCropTime < 33) return // 30fps throttle
        val last = lastStabilizedCrop
        if (last != null &&
            kotlin.math.abs(crop.left - last.left) < 2 &&
            kotlin.math.abs(crop.top - last.top) < 2
        ) return

        lastStabilizedCropTime = now
        lastStabilizedCrop = crop

        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Error applying stabilized crop", e)
        }
    }

    private fun applyContinuousDollyZoom(zoom: Float) {
        val now = System.currentTimeMillis()
        if (now - lastDollyApplyTime < 16) return // 60fps responsive tracking
        if (kotlin.math.abs(zoom - lastAppliedDollyZoom) < 0.002f) return
        lastDollyApplyTime = now
        lastAppliedDollyZoom = zoom

        currentZoom = zoom
        _currentZoom.value = zoom

        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            } else {
                val chars = getCharacteristics(_selectedLens.value?.cameraId ?: "0")
                val activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeArray != null) {
                    val cropW = (activeArray.width() / zoom).toInt()
                    val cropH = (activeArray.height() / zoom).toInt()
                    val cropX = (activeArray.width() - cropW) / 2
                    val cropY = (activeArray.height() - cropH) / 2
                    builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                }
            }
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Error applying continuous dolly zoom", e)
        }
    }

    /**
     * Apply AE, AF, AWB, Flash, ISO, Shutter, Zoom, Stabilization to CaptureRequest.Builder
     */
    private fun applyCommonSettings(builder: CaptureRequest.Builder) {
        val caps = _capabilities.value

        // AE & Manual Exposure / ISO
        if (manualIso != null || manualExposureTimeNs != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            manualIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            manualExposureTimeNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else {
            // Auto Exposure mode + Flash configuration (safely verifying hardware flash support)
            if (!caps.supportsFlash || flashMode == FlashMode.OFF) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            } else {
                when (flashMode) {
                    FlashMode.AUTO -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
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
            }
            // Exposure compensation (apply cinema EV if in Cinema mode, or standard exposure index)
            val evToApply = if (currentMode == CameraMode.CINEMA) {
                cinemaConfig.value.exposureCompensation
            } else {
                exposureCompensationIndex
            }
            val minEv = caps.minExposureCompensation
            val maxEv = caps.maxExposureCompensation
            val clampedEv = if (minEv < maxEv) evToApply.coerceIn(minEv, maxEv) else evToApply
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedEv)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, isAeLocked)
        }

        // Active Tap-to-Expose & Tap-to-Focus Metering Region (preserved across setting adjustments)
        activeMeteringRectangle?.let { rect ->
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
        }

        // White Balance
        builder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalanceMode.camera2Mode)

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
                val mode = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA ||
                    currentMode == CameraMode.DOLLY_ZOOM) {
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

        // Coordinated Hybrid OIS + EIS Stabilization
        val isVideoMode = currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA ||
                currentMode == CameraMode.DOLLY_ZOOM || _isRecordingVideo.value

        val hybridConfig = _hybridStabilizationConfig.value
        val isEisOnly = hybridConfig.isEisOnly || (!hybridConfig.isOisPreferred && hybridConfig.isEisPreferred)
        val isOisOnly = hybridConfig.isOisPreferred && !hybridConfig.isEisPreferred && !hybridConfig.isHybridEnabled

        if (isVideoMode) {
            val isUltra = hybridConfig.isUltraStabilizationEnabled
            val isStabActive = isVideoStabilizationEnabled || isUltra || hybridConfig.isHybridEnabled || isEisOnly || isOisOnly

            if (isStabActive) {
                // Optical Image Stabilization (Physical voice-coil motor hardware)
                // When "EIS Only" is selected, OIS is strictly OFF.
                if (isEisOnly) {
                    if (caps.supportsOis) {
                        builder.set(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                        )
                    }
                } else if (caps.supportsOis && hybridConfig.isOisPreferred) {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                } else if (caps.supportsOis) {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    )
                }

                // Electronic Image Stabilization (Digital frame margin compensation)
                // When "OIS Only" is selected, EIS is strictly OFF.
                if (isOisOnly) {
                    builder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                } else {
                    val isHighFps4k = (_selectedVideoResolution.value?.width ?: 0) >= 3840 && videoFps >= 60
                    val allowEis = caps.supportsEis && (isUltra || isEisOnly || (hybridConfig.isEisPreferred && (!hybridConfig.isAdaptiveFpsLens || !isHighFps4k)))

                    if (isUltra || allowEis) {
                        builder.set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        )
                    } else {
                        builder.set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                        )
                    }
                }
            } else {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                if (caps.supportsOis) {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    )
                }
            }
        } else {
            // In Still Photo / Night / Portrait: respect OIS toggle state
            if (caps.supportsOis) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    if (hybridConfig.isOisPreferred && !isEisOnly) {
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    } else {
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    }
                )
            }
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }

        // Color profiles & Tonemap
        when (colorProfile) {
            ColorProfile.FLAT_LOG -> {
                if (caps.supportsTonemapCurve) {
                    val flatCurve = TonemapCurve(
                        floatArrayOf(0f, 0.16f, 0.25f, 0.35f, 0.5f, 0.54f, 0.75f, 0.72f, 1f, 0.86f),
                        floatArrayOf(0f, 0.16f, 0.25f, 0.35f, 0.5f, 0.54f, 0.75f, 0.72f, 1f, 0.86f),
                        floatArrayOf(0f, 0.16f, 0.25f, 0.35f, 0.5f, 0.54f, 0.75f, 0.72f, 1f, 0.86f)
                    )
                    builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                    builder.set(CaptureRequest.TONEMAP_CURVE, flatCurve)
                }
                builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
            }
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

        // Dedicated Cinema Log Color Profile
        if (currentMode == CameraMode.CINEMA) {
            cinemaEngine.applyToCaptureRequest(builder)
        }

        // Digital Zoom / Crop Region
        applyZoom(builder)
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val lens = _selectedLens.value ?: return

        val hybridConfig = _hybridStabilizationConfig.value
        val isVideoMode = currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA
        val caps = capabilities.value
        if (!caps.supportsEis && hybridConfig.isUltraStabilizationEnabled && isVideoMode && lastStabilizedCrop != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, lastStabilizedCrop)
            return
        }

        try {
            val chars = getCharacteristics(lens.cameraId) ?: return
            val isUltraWide = lens.lensType == LensType.ULTRAWIDE || lens.baseZoomRatio < 0.9f

            // Formula: ultraWideDigitalZoom = requestedZoom / 0.5f
            // 0.5x = full ultra-wide sensor (1.0x digital zoom)
            // 0.6x = ultra-wide + 1.2x digital crop
            // 0.7x = ultra-wide + 1.4x digital crop
            // 0.8x = ultra-wide + 1.6x digital crop
            // 0.9x = ultra-wide + 1.8x digital crop
            // 1.0x = ultra-wide + 2.0x digital crop
            val targetDigitalZoom = if (isUltraWide) {
                val base = if (lens.baseZoomRatio > 0.1f) lens.baseZoomRatio else 0.5f
                (currentZoom / base).coerceAtLeast(1.0f)
            } else {
                val baseRatio = if (lens.baseZoomRatio > 0f) lens.baseZoomRatio else 1.0f
                if (lens.isPhysical && baseRatio > 1.2f) {
                    (currentZoom / baseRatio).coerceAtLeast(1.0f)
                } else {
                    currentZoom.coerceAtLeast(1.0f)
                }
            }

            // On Android 11+ (API 30+), CONTROL_ZOOM_RATIO applies ISP digital zoom
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    val clamped = targetDigitalZoom.coerceIn(zoomRange.lower, zoomRange.upper)
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
                    return
                }
            }

            // Fallback for legacy devices or SCALER_CROP_REGION
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            val effectiveZoom = targetDigitalZoom.coerceIn(1.0f, maxZoom)

            val cropW = (sensorRect.width() / effectiveZoom).toInt()
            val cropH = (sensorRect.height() / effectiveZoom).toInt()
            val cropX = (sensorRect.width() - cropW) / 2
            val cropY = (sensorRect.height() - cropH) / 2

            val cropRegion = Rect(cropX, cropY, cropX + cropW, cropY + cropH)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting zoom", e)
        }
    }

    /**
     * Updates preview request with new settings on the fly
     */
    fun updatePreviewSettings() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            applyCommonSettings(builder)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preview settings", e)
        }
    }

    /**
     * Set Zoom (.5x to 10x) with seamless automatic lens switching
     */
    fun setZoom(zoom: Float, isPresetTap: Boolean = false) {
        val clampedZoom = zoom.coerceIn(0.5f, 10.0f)
        currentZoom = clampedZoom
        _currentZoom.value = clampedZoom
        preferences.currentZoom = clampedZoom

        val currentLens = _selectedLens.value ?: return

        // If front selfie camera, apply digital zoom on active stream,
        // or switch to rear lens if user explicitly tapped a rear zoom preset (.5x or 1x)
        if (currentLens.facing == CameraCharacteristics.LENS_FACING_FRONT) {
            if (isPresetTap && (clampedZoom < 0.95f || clampedZoom in 0.95f..1.1f)) {
                val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                val backTarget = if (clampedZoom < 0.95f) {
                    backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                } else {
                    backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                        ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
                }
                if (backTarget != null) {
                    selectLens(backTarget)
                    return
                }
            }
            updatePreviewSettings()
            return
        }

        // Keep standby camera repeating request synchronized with target zoom
        motorolaSwitchEngine.updateStandbyZoom(clampedZoom)

        val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val ultraWideLens = backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
            ?: backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val mainWideLens = backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
        val isCurrentlyUltraWide = currentLens.lensType == LensType.ULTRAWIDE

        // Hysteresis & Continuous Zoom logic:
        // When using 0.5x Ultra-Wide, use UW for the entire 0.5x -> 1.0x range (0.6x = ~1.2x crop, 0.7x = ~1.4x crop, etc.).
        // Only switch to Main lens when zoom reaches 1.00x.
        // When zooming out from Main lens, switch back to Ultra-Wide below 0.97x.
        // If user explicitly taps a preset (.5x), switch to Ultra-Wide immediately.
        val targetLens: LensInfo? = if (isPresetTap) {
            when {
                clampedZoom < 0.95f -> ultraWideLens ?: mainWideLens
                clampedZoom in 0.95f..<2.0f -> mainWideLens
                clampedZoom >= 3.0f -> {
                    backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO_3X && it.isPhysical }
                        ?: backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical }
                        ?: mainWideLens
                }
                clampedZoom >= 2.0f -> {
                    backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical }
                        ?: mainWideLens
                }
                else -> mainWideLens
            }
        } else {
            when {
                isCurrentlyUltraWide -> {
                    if (clampedZoom >= 1.00f) {
                        mainWideLens
                    } else {
                        ultraWideLens ?: mainWideLens
                    }
                }
                else -> {
                    if (clampedZoom < 0.97f && ultraWideLens != null) {
                        ultraWideLens
                    } else if (clampedZoom >= 3.0f) {
                        backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO_3X && it.isPhysical }
                            ?: backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical }
                            ?: mainWideLens
                    } else if (clampedZoom >= 2.0f) {
                        backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical }
                            ?: mainWideLens
                    } else {
                        mainWideLens
                    }
                }
            }
        }

        if (targetLens != null && targetLens != currentLens) {
            zoomDebounceJob?.cancel()
            zoomDebounceJob = null

            // Instant seamless lens switch using warm concurrent stream or handover
            if (isPresetTap) {
                selectLens(targetLens, preserveZoom = false)
            } else {
                selectLens(targetLens, preserveZoom = true, targetZoom = clampedZoom)
            }
        } else {
            // Same lens: apply smooth digital crop / optical zoom immediately on active preview
            updatePreviewSettings()
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
                        val continuousMode = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA || currentMode == CameraMode.DOLLY_ZOOM) {
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

    fun lockDollySubjectAt(normX: Float, normY: Float) {
        val lens = _selectedLens.value ?: return
        val chars = getCharacteristics(lens.cameraId) ?: return
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8f
        val lastResult = lastCaptureResult
        val faces = lastResult?.get(CaptureResult.STATISTICS_FACES)
        val diopters = lastResult?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
        dollyZoomEngine.lockSubject(
            normX = normX,
            normY = normY,
            currentZoom = currentZoom,
            faces = faces,
            lensFocusDiopters = diopters,
            sensorRect = sensorRect,
            minZoom = 1.0f,
            maxZoom = maxZoom
        )
    }

    fun calibrateDollyZoom() {
        lockDollySubjectAt(0.5f, 0.5f)
    }

    fun resetDollyZoom() {
        dollyZoomEngine.reset()
    }

    fun setPreviewAspectRatio(ratio: Float) {
        if (ratio > 0f) {
            _previewAspectRatio.value = ratio
        }
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
        val deviceRotation = getDeviceRotationDegrees()
        val standardHint = calculateOrientation(sensorOrientation, isFront, deviceRotation)

        // When "Save selfie as previewed without flipping" is enabled:
        // In the viewfinder preview, front camera is mirrored horizontally (scale -1, 1).
        // Standard video container players play according to orientation hint.
        // For front camera in portrait (deviceRotation = 0, sensor = 270):
        // Standard hint is 270°. Some players or sensors inverted this to 90° (upside-down by 180°).
        // Returning standardHint properly prevents upside down playback.
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
        val deviceRotation = getDeviceRotationDegrees()
        return calculateOrientation(sensorOrientation, isFront, deviceRotation)
    }

    /**
     * Upgraded Computational Night Mode Multi-Frame Capture & Fusion.
     * Captures multiple aligned burst frames across 1-5 seconds, reduces hand-shake ghosting,
     * boosts signal-to-noise ratio by temporal averaging, and applies adaptive tone mapping.
     */
    fun takeNightPhoto(
        config: NightConfig = NightConfig(),
        onProgress: (NightCaptureProgress) -> Unit = {},
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
        val readerJpeg = imageReaderJpeg ?: run {
            onComplete(null)
            return
        }

        _isCapturing.value = true
        gyroStabilizationEngine.start()

        val activeLens = _selectedLens.value
        val chars = activeLens?.let { getCharacteristics(it.cameraId) }
        val previewResult = lastCaptureResult

        // 1. Analyze hardware sensor limits, scene illuminance & gyro stability
        val analyzer = NightSceneAnalyzer()
        val plan = analyzer.createPlan(chars, previewResult, gyroStabilizationEngine, config)
        val bracketFrames = plan.bracketFrames
        val targetFrameCount = bracketFrames.size

        val collectedFrames = java.util.Collections.synchronizedList(mutableListOf<CapturedNightFrame>())
        val isCompleted = java.util.concurrent.atomic.AtomicBoolean(false)

        val initialProgress = NightCaptureProgress(
            isCapturing = true,
            remainingSeconds = (plan.totalEstimatedDurationMs / 1000f),
            progress = 0.05f,
            statusText = "Hold device steady... [${plan.sceneLevel.label}]",
            detectedScene = plan.sceneLevel.label,
            activeFrameCount = 0,
            targetFrameCount = targetFrameCount,
            isTripodDetected = plan.isTripod,
            exposureTimeMs = (bracketFrames.firstOrNull()?.exposureTimeNs ?: 33_333_333L) / 1_000_000f,
            iso = bracketFrames.firstOrNull()?.iso ?: 400
        )
        _nightProgress.value = initialProgress
        onProgress(initialProgress)

        // Real-time countdown timer job
        val totalMs = plan.totalEstimatedDurationMs
        val countdownJob = engineScope.launch {
            val stepMs = 100L
            var elapsedMs = 0L
            while (elapsedMs < totalMs && !isCompleted.get()) {
                delay(stepMs)
                elapsedMs += stepMs
                val remSec = max(0f, (totalMs - elapsedMs) / 1000f)
                val prog = (elapsedMs.toFloat() / totalMs * 0.45f).coerceIn(0.05f, 0.45f)
                val currentFrameIdx = collectedFrames.size.coerceIn(0, targetFrameCount - 1)
                val activeBracket = bracketFrames[currentFrameIdx]
                val current = NightCaptureProgress(
                    isCapturing = true,
                    remainingSeconds = remSec,
                    progress = prog,
                    statusText = "Hold steady (${collectedFrames.size}/$targetFrameCount frames)",
                    detectedScene = plan.sceneLevel.label,
                    activeFrameCount = collectedFrames.size,
                    targetFrameCount = targetFrameCount,
                    isTripodDetected = plan.isTripod,
                    exposureTimeMs = activeBracket.exposureTimeNs / 1_000_000f,
                    iso = activeBracket.iso
                )
                _nightProgress.value = current
                withContext(Dispatchers.Main) { onProgress(current) }
            }
        }

        val orientation = getCaptureJpegOrientation()

        readerJpeg.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val frameIdx = collectedFrames.size
                    val bracket = bracketFrames.getOrElse(frameIdx) { bracketFrames.last() }

                    val gyroPitch = gyroStabilizationEngine.latestPitchSpeed
                    val gyroYaw = gyroStabilizationEngine.latestYawSpeed

                    val capturedFrame = CapturedNightFrame(
                        index = frameIdx,
                        bitmap = bmp,
                        exposureTimeNs = bracket.exposureTimeNs,
                        iso = bracket.iso,
                        timestampNanos = System.nanoTime(),
                        type = bracket.type,
                        isReference = bracket.isAnchorFrame,
                        gyroPitchVelocity = gyroPitch,
                        gyroYawVelocity = gyroYaw
                    )
                    collectedFrames.add(capturedFrame)

                    val prog = 0.05f + (collectedFrames.size.toFloat() / targetFrameCount * 0.40f)
                    val status = "Acquired frame ${collectedFrames.size}/$targetFrameCount (${bracket.type.name})"
                    val progressUpdate = NightCaptureProgress(
                        isCapturing = true,
                        remainingSeconds = max(0f, (totalMs - (frameIdx * 250L)) / 1000f),
                        progress = prog,
                        statusText = status,
                        detectedScene = plan.sceneLevel.label,
                        activeFrameCount = collectedFrames.size,
                        targetFrameCount = targetFrameCount,
                        isTripodDetected = plan.isTripod,
                        exposureTimeMs = bracket.exposureTimeNs / 1_000_000f,
                        iso = bracket.iso
                    )
                    _nightProgress.value = progressUpdate
                    engineScope.launch(Dispatchers.Main) { onProgress(progressUpdate) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error acquiring night burst frame", e)
            } finally {
                image.close()
            }

            if (collectedFrames.size >= targetFrameCount && isCompleted.compareAndSet(false, true)) {
                countdownJob.cancel()
                finalizeUltraNightCapture(
                    frames = collectedFrames,
                    plan = plan,
                    config = config,
                    onProgress = onProgress,
                    onComplete = onComplete
                )
            }
        }, backgroundHandler)

        try {
            val requests = mutableListOf<CaptureRequest>()
            val availableCaps = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val hasManualSensor = availableCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

            for (i in 0 until targetFrameCount) {
                val bracket = bracketFrames[i]
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(readerJpeg.surface)
                applyCommonSettings(builder)

                if (hasManualSensor) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, bracket.exposureTimeNs)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, bracket.iso)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    val aeStep = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: android.util.Rational(1, 3)
                    val stepFloat = aeStep.numerator.toFloat() / aeStep.denominator.toFloat()
                    val compIndex = (bracket.evOffset / stepFloat).toInt()
                    val compRange = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-6, 6)
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compIndex.coerceIn(compRange.lower, compRange.upper))
                }

                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
                builder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                requests.add(builder.build())
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val expAccepted = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    val isoAccepted = result.get(CaptureResult.SENSOR_SENSITIVITY)
                    Log.d(TAG, "[NIGHT_FRAME_ACCEPTED] Accepted Exposure: ${expAccepted}ns, ISO: $isoAccepted")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit night burst requests", e)
            isCompleted.set(true)
            countdownJob.cancel()
            _isCapturing.value = false
            _nightProgress.value = NightCaptureProgress()
            onComplete(null)
        }
    }

    // Overload for backward compatibility
    fun takeNightPhoto(
        durationSeconds: Int,
        isAntiGhosting: Boolean = true,
        noiseSuppression: Float = 0.85f,
        shadowLift: Float = 1.35f,
        onProgress: (NightCaptureProgress) -> Unit = {},
        onComplete: (Uri?) -> Unit
    ) {
        takeNightPhoto(
            config = NightConfig(
                durationSeconds = durationSeconds,
                antiGhostingEnabled = isAntiGhosting,
                noiseSuppression = noiseSuppression,
                shadowLift = shadowLift
            ),
            onProgress = onProgress,
            onComplete = onComplete
        )
    }

    private fun finalizeUltraNightCapture(
        frames: List<CapturedNightFrame>,
        plan: NightBracketPlan,
        config: NightConfig,
        onProgress: (NightCaptureProgress) -> Unit,
        onComplete: (Uri?) -> Unit
    ) {
        engineScope.launch(Dispatchers.Default) {
            val updateProgressText: (Float, String) -> Unit = { p, status ->
                val state = NightCaptureProgress(
                    isCapturing = true,
                    remainingSeconds = 0f,
                    progress = p,
                    statusText = status,
                    detectedScene = plan.sceneLevel.label,
                    activeFrameCount = frames.size,
                    targetFrameCount = plan.bracketFrames.size,
                    isTripodDetected = plan.isTripod,
                    exposureTimeMs = (plan.bracketFrames.firstOrNull()?.exposureTimeNs ?: 33_333_333L) / 1_000_000f,
                    iso = plan.bracketFrames.firstOrNull()?.iso ?: 400
                )
                _nightProgress.value = state
                engineScope.launch(Dispatchers.Main) { onProgress(state) }
            }

            updateProgressText(0.48f, "Selecting optical anchor frame...")

            val alignmentEngine = NightAlignmentEngine()
            val ultraEngine = UltraNightFusionEngine()

            val refIdx = alignmentEngine.selectOptimalReferenceFrame(frames)

            updateProgressText(0.55f, "Gyro-assisted alignment & optical flow...")
            val alignedData = alignmentEngine.alignFrames(frames, refIdx) { alignProg ->
                updateProgressText(0.55f + alignProg * 0.18f, "Sub-pixel alignment & anti-ghosting...")
            }

            updateProgressText(0.75f, "HDR radiance fusion & temporal denoising...")
            val fusedBitmap = try {
                ultraEngine.processUltraNightFrames(
                    frames = frames,
                    alignedData = alignedData,
                    config = config,
                    onProgress = { fusionProg ->
                        val subText = when {
                            fusionProg < 0.40f -> "Linear HDR Radiance Accumulation..."
                            fusionProg < 0.70f -> "Bilateral Local Tone Mapping..."
                            fusionProg < 0.90f -> "Shadow Recovery & Chromatic Adaptation..."
                            else -> "Edge Sharpening & Noise Filtering..."
                        }
                        updateProgressText(0.75f + fusionProg * 0.22f, subText)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Flagship night fusion failed, falling back to reference frame", e)
                frames.getOrNull(refIdx)?.bitmap ?: frames.first().bitmap
            }

            updateProgressText(0.98f, "Saving ultra-bright night photo...")
            val uri = saveBitmapToMediaStore(fusedBitmap, 0)

            frames.forEach { frame ->
                if (!frame.bitmap.isRecycled && frame.bitmap != fusedBitmap) {
                    frame.bitmap.recycle()
                }
            }
            if (!fusedBitmap.isRecycled) fusedBitmap.recycle()

            _isCapturing.value = false
            val finalProgress = NightCaptureProgress(
                isCapturing = false,
                remainingSeconds = 0f,
                progress = 1.0f,
                statusText = "Completed",
                detectedScene = plan.sceneLevel.label,
                activeFrameCount = frames.size,
                targetFrameCount = plan.bracketFrames.size,
                isTripodDetected = plan.isTripod
            )
            _nightProgress.value = finalProgress
            updateStorageStats()
            withContext(Dispatchers.Main) {
                onProgress(finalProgress)
                onComplete(uri)
            }
        }
    }

    /**
     * Human Vision / Natural Perspective Computational Capture:
     * - Captures 0.5x Ultra-Wide master composition frame (or full sensor reference).
     * - Captures 1x burst (2-3 frames with locked AE/AWB) for HDR and temporal denoising.
     * - Captures 3x overlapping tiles covering distant horizon / center regions.
     * - Passes to HumanVisionEngine for depth-aware fusion & natural perspective correction.
     */
    fun takeHumanVisionPhoto(
        config: com.example.camera.engine.humanvision.HumanVisionConfig = com.example.camera.engine.humanvision.HumanVisionConfig(),
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
        val readerJpeg = imageReaderJpeg ?: run {
            onComplete(null)
            return
        }

        _isCapturing.value = true
        val isCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
        val collectedRawBitmaps = java.util.Collections.synchronizedList(mutableListOf<Bitmap>())

        val activeLens = _selectedLens.value
        val chars = activeLens?.let { getCharacteristics(it.id) }
        val sensorRect = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 4000, 3000)

        val rotationDeg = getCaptureJpegOrientation()
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT

        // Calculate 3x overlapping tile crop regions on the sensor
        val sensorW = sensorRect.width().coerceAtLeast(1)
        val sensorH = sensorRect.height().coerceAtLeast(1)
        val tileW = (sensorW / 3).coerceAtLeast(1)
        val tileH = (sensorH / 3).coerceAtLeast(1)
        val distantCenterY = (sensorH * 0.40f).toInt()
        val tileTop = (distantCenterY - tileH / 2).coerceIn(0, sensorH - tileH)

        val centerLeft = (sensorW - tileW) / 2
        val stepX = (tileW * (1.0f - config.tileOverlapRatio)).toInt()
        val leftTileLeft = (centerLeft - stepX).coerceIn(0, sensorW - tileW)
        val rightTileLeft = (centerLeft + stepX).coerceIn(0, sensorW - tileW)

        val centerTileRect = Rect(centerLeft, tileTop, centerLeft + tileW, tileTop + tileH)
        val leftTileRect = Rect(leftTileLeft, tileTop, leftTileLeft + tileW, tileTop + tileH)
        val rightTileRect = Rect(rightTileLeft, tileTop, rightTileLeft + tileW, tileTop + tileH)

        val totalRequests = 6

        readerJpeg.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rawBitmap != null) {
                    val matrix = Matrix()
                    if (rawBitmap.width > rawBitmap.height && rotationDeg != 0) {
                        matrix.postRotate(rotationDeg.toFloat())
                    }
                    if (isFrontFacing && saveSelfieAsPreviewed) {
                        matrix.postScale(-1f, 1f)
                    }

                    val orientedBitmap = if (!matrix.isIdentity) {
                        val transformed = Bitmap.createBitmap(
                            rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                        )
                        if (transformed != rawBitmap) rawBitmap.recycle()
                        transformed
                    } else rawBitmap

                    synchronized(collectedRawBitmaps) {
                        collectedRawBitmaps.add(orientedBitmap)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error acquiring Human Vision capture frame", e)
            }

            val currentCount = synchronized(collectedRawBitmaps) { collectedRawBitmaps.size }
            if (currentCount >= totalRequests) {
                if (isCompleted.compareAndSet(false, true)) {
                    readerJpeg.setOnImageAvailableListener(null, null)
                    dispatchHumanVisionProcessing(
                        bitmaps = synchronized(collectedRawBitmaps) { ArrayList(collectedRawBitmaps) },
                        sensorW = sensorW,
                        sensorH = sensorH,
                        centerRect = centerTileRect,
                        leftRect = leftTileRect,
                        rightRect = rightTileRect,
                        config = config,
                        onComplete = onComplete
                    )
                }
            }
        }, backgroundHandler)

        try {
            val requests = mutableListOf<CaptureRequest>()

            // 0. Reference frame (full composition / foreground)
            val refBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            applyCommonSettings(refBuilder)
            refBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            refBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            refBuilder.set(CaptureRequest.JPEG_ORIENTATION, rotationDeg)
            refBuilder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
            refBuilder.set(CaptureRequest.SCALER_CROP_REGION, sensorRect)
            refBuilder.addTarget(readerJpeg.surface)
            requests.add(refBuilder.build())

            // 1..2. 1x Detail burst with locked AE/AWB
            for (i in 0 until 2) {
                val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                applyCommonSettings(b)
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                b.set(CaptureRequest.JPEG_ORIENTATION, rotationDeg)
                b.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                b.set(CaptureRequest.SCALER_CROP_REGION, sensorRect)
                b.addTarget(readerJpeg.surface)
                requests.add(b.build())
            }

            // 3..5. 3x Distant tiles (Center, Left, Right)
            val tileCrops = listOf(centerTileRect, leftTileRect, rightTileRect)
            for (tileRect in tileCrops) {
                val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                applyCommonSettings(b)
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                b.set(CaptureRequest.JPEG_ORIENTATION, rotationDeg)
                b.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                b.set(CaptureRequest.SCALER_CROP_REGION, tileRect)
                b.addTarget(readerJpeg.surface)
                requests.add(b.build())
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Human Vision burst frame completed")
                }
            }, backgroundHandler)

            // Watchdog fallback in case any frame is dropped by Camera2 HAL
            engineScope.launch {
                delay(4500)
                if (isCompleted.compareAndSet(false, true)) {
                    Log.w(TAG, "Human Vision capture watchdog triggered")
                    readerJpeg.setOnImageAvailableListener(null, null)
                    val frames = synchronized(collectedRawBitmaps) { ArrayList(collectedRawBitmaps) }
                    dispatchHumanVisionProcessing(
                        bitmaps = frames,
                        sensorW = sensorW,
                        sensorH = sensorH,
                        centerRect = centerTileRect,
                        leftRect = leftTileRect,
                        rightRect = rightTileRect,
                        config = config,
                        onComplete = onComplete
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed initiating Human Vision capture burst", e)
            _isCapturing.value = false
            takePhoto(onComplete)
        }
    }

    private fun dispatchHumanVisionProcessing(
        bitmaps: List<Bitmap>,
        sensorW: Int,
        sensorH: Int,
        centerRect: Rect,
        leftRect: Rect,
        rightRect: Rect,
        config: com.example.camera.engine.humanvision.HumanVisionConfig,
        onComplete: (Uri?) -> Unit
    ) {
        if (bitmaps.isEmpty()) {
            _isCapturing.value = false
            takePhoto(onComplete)
            return
        }

        engineScope.launch(Dispatchers.Default) {
            try {
                val refBitmap = bitmaps.firstOrNull()
                val mainFrames = if (bitmaps.size > 2) {
                    bitmaps.subList(1, min(3, bitmaps.size))
                } else {
                    listOfNotNull(refBitmap)
                }

                val tileRects = listOf(centerRect, leftRect, rightRect)
                val zoomBitmaps = if (bitmaps.size > 3) bitmaps.subList(3, bitmaps.size) else emptyList()
                val zoomTiles = mutableListOf<com.example.camera.engine.humanvision.TileRegion>()

                for ((idx, bmp) in zoomBitmaps.withIndex()) {
                    val r = tileRects.getOrElse(idx) { centerRect }
                    val normRect = android.graphics.RectF(
                        r.left.toFloat() / sensorW,
                        r.top.toFloat() / sensorH,
                        r.right.toFloat() / sensorW,
                        r.bottom.toFloat() / sensorH
                    )
                    zoomTiles.add(
                        com.example.camera.engine.humanvision.TileRegion(
                            index = idx,
                            rectNorm = normRect,
                            bitmap = bmp
                        )
                    )
                }

                val frameSet = com.example.camera.engine.humanvision.CapturedFrameSet(
                    ultraWideReference = refBitmap,
                    mainFrames = mainFrames,
                    zoomTiles = zoomTiles
                )

                humanVisionEngine.processHumanVisionCapture(
                    frameSet = frameSet,
                    config = config
                ) { finalUri ->
                    _isCapturing.value = false
                    updateStorageStats()
                    if (finalUri != null) {
                        _lastCapturedMedia.value = CapturedMedia(
                            uri = finalUri,
                            isVideo = false,
                            timestamp = System.currentTimeMillis(),
                            displayName = "HUMAN_VISION_${System.currentTimeMillis()}.jpg"
                        )
                    }
                    onComplete(finalUri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in dispatchHumanVisionProcessing", e)
                _isCapturing.value = false
                withContext(Dispatchers.Main) { onComplete(null) }
            }
        }
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

        if (isHighQualityZoomEnabled && currentZoom > 1.2f && currentMode == CameraMode.PHOTO) {
            takePhotoHighQualityZoom(onComplete)
            return
        }

        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val readerJpeg = imageReaderJpeg ?: return
        val readerYuv = imageReaderYuv
        val isPipelineEnabled = preferences.isCustomPipelineEnabled && readerYuv != null

        val activeLens = _selectedLens.value
        val jpegW = readerJpeg.width
        val jpegH = readerJpeg.height
        val mp = (jpegW.toLong() * jpegH.toLong()) / 1_000_000f
        Log.i(TAG, "[PHOTO_CAPTURE] Initiating capture on ${activeLens?.lensType}: ImageReader JPEG=${jpegW}x${jpegH} (~${mp}MP), YUV=${readerYuv?.width}x${readerYuv?.height}")

        _isCapturing.value = true

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            if (isPipelineEnabled) {
                // SENSOR -> UNPROCESSED YUV/RAW -> CUSTOM PIPELINE -> FINAL JPEG
                captureBuilder.addTarget(readerYuv.surface)
            } else {
                captureBuilder.addTarget(readerJpeg.surface)
            }

            val caps = _capabilities.value
            val isRaw = isRawCaptureEnabled && caps.supportsRaw && imageReaderRaw != null
            if (isRaw) {
                imageReaderRaw?.surface?.let { captureBuilder.addTarget(it) }
            }

            applyCommonSettings(captureBuilder)
            if (!isPipelineEnabled) {
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
                captureBuilder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
            }

            if (isPipelineEnabled) {
                readerYuv.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        engineScope.launch(Dispatchers.IO) {
                            try {
                                val rotation = getCaptureJpegOrientation()
                                val activeLens = _selectedLens.value
                                val isFront = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
                                val saveMirrored = saveSelfieAsPreviewed

                                // Convert directly from uncompressed YUV sensor planes without intermediate JPEG
                                val uncompressedBmp = customImagePipelineEngine.convertCameraImageToUncompressedBitmap(
                                    image = image,
                                    rotationDegrees = rotation,
                                    isFrontFacing = isFront,
                                    saveMirrored = saveMirrored
                                )
                                image.close()

                                // Build downscaled fast preview for 60fps real-time before/after comparison
                                val maxDim = max(uncompressedBmp.width, uncompressedBmp.height)
                                val previewScale = (1080f / maxDim).coerceAtMost(1.0f)
                                val previewBmp = if (previewScale < 0.95f) {
                                    Bitmap.createScaledBitmap(
                                        uncompressedBmp,
                                        (uncompressedBmp.width * previewScale).roundToInt(),
                                        (uncompressedBmp.height * previewScale).roundToInt(),
                                        true
                                    )
                                } else {
                                    uncompressedBmp.copy(Bitmap.Config.ARGB_8888, true)
                                }

                                val activePreset = preferences.getActivePipelinePreset()
                                val activeParams = preferences.getPipelineParams(activePreset.id)

                                val captureSource = com.example.camera.pipeline.engine.PipelineCaptureSource(
                                    fullResBitmap = uncompressedBmp,
                                    previewBitmap = previewBmp,
                                    orientationDegrees = rotation,
                                    isFrontFacing = isFront,
                                    lensInfo = activeLens,
                                    appliedPreset = activePreset,
                                    appliedParams = activeParams
                                )
                                com.example.camera.pipeline.engine.PipelineCaptureCache.setCapture(captureSource)

                                // Process uncompressed data through custom pipeline and encode final JPEG directly
                                val finalJpegBytes = customImagePipelineEngine.processAndEncodeToJpegBytes(
                                    source = uncompressedBmp,
                                    params = activeParams,
                                    jpegQuality = preferences.jpegQuality
                                )

                                val uri = saveJpegBytesToMediaStore(finalJpegBytes)
                                com.example.camera.pipeline.engine.PipelineCaptureCache.updateSavedUri(uri)
                                _isCapturing.value = false
                                updateStorageStats()
                                withContext(Dispatchers.Main) {
                                    onComplete(uri)
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "Error in Custom Pipeline Capture", e)
                                _isCapturing.value = false
                                withContext(Dispatchers.Main) {
                                    onComplete(null)
                                }
                            }
                        }
                    }
                }, backgroundHandler)
            } else {
                readerJpeg.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        engineScope.launch(Dispatchers.IO) {
                            val uri = saveJpegToMediaStore(image)
                            image.close()
                            _isCapturing.value = false
                            updateStorageStats()
                            withContext(Dispatchers.Main) {
                                onComplete(uri)
                            }
                        }
                    }
                }, backgroundHandler)
            }

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
                    Log.d(TAG, "Photo capture completed")
                }
            }, backgroundHandler)

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
            val uri = saveJpegBytesToMediaStore(finalBytes)
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

        val frameCount = refocusFrameCount.coerceIn(5, 20)
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

        fun finalizeRefocusCapture() {
            if (!isCompleted.compareAndSet(false, true)) return
            readerJpeg.setOnImageAvailableListener(null, null)
            engineScope.launch(Dispatchers.IO) {
                var finalUri: Uri? = null
                try {
                    val validFiles = tempPlaneFiles.filter { it.exists() && it.length() > 0 }
                    if (validFiles.isNotEmpty()) {
                        val midFile = tempPlaneFiles.getOrNull(bestMidIndex)?.takeIf { it.exists() && it.length() > 0 }
                            ?: validFiles.first()
                        val midBytes = midFile.readBytes()
                        finalUri = saveJpegBytesToMediaStore(midBytes)
                        if (finalUri != null) {
                            refocusEngine.processAndPersistPlanes(
                                photoUri = finalUri,
                                tempPlaneFiles = tempPlaneFiles,
                                planeDiopters = focusPlanes.toList()
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error finalizing refocus package", e)
                } finally {
                    tempPlaneFiles.forEach { runCatching { it.delete() } }
                    _isCapturing.value = false
                    updateStorageStats()
                    withContext(Dispatchers.Main) {
                        onComplete(finalUri)
                    }
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
                    applyCommonSettings(this)
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
     * High-Quality Traditional Zoom Photo Capture:
     * Captures a rapid burst of frames at the active zoom ratio with fixed AE/AWB locks,
     * aligns the frames with sub-pixel precision to boost SNR and cancel noise,
     * and performs edge-preserving Lanczos-3 reconstruction, deblur, and halo-clamped detail recovery.
     * The final output is ONLY the processed, high-clarity zoomed photo (no intermediate frames).
     */
    fun takePhotoHighQualityZoom(onComplete: (Uri?) -> Unit) {
        val camera = cameraDevice
        val session = captureSession
        val readerJpeg = imageReaderJpeg
        if (camera == null || session == null || readerJpeg == null) {
            takePhoto(onComplete)
            return
        }

        _isCapturing.value = true
        _isZoomProcessing.value = true
        _zoomProgress.value = 0.05f

        val quality = zoomProcessingQuality
        val burstCount = quality.burstCount
        val zoomRatio = currentZoom.coerceAtLeast(1.0f)
        val rotationDeg = getCaptureJpegOrientation()

        val capturedBitmaps = mutableListOf<Bitmap>()
        val isCompleted = java.util.concurrent.atomic.AtomicBoolean(false)

        readerJpeg.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            engineScope.launch(Dispatchers.IO) {
                try {
                    val options = BitmapFactory.Options().apply {
                        inMutable = true
                        inSampleSize = 1
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (rawBitmap != null) {
                        val activeLens = _selectedLens.value
                        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT

                        val exif = try {
                            android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                        } catch (e: Exception) {
                            null
                        }
                        val exifOrientation = exif?.getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_UNDEFINED
                        ) ?: android.media.ExifInterface.ORIENTATION_UNDEFINED

                        val matrix = Matrix()
                        when (exifOrientation) {
                            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            android.media.ExifInterface.ORIENTATION_NORMAL -> {
                                // Already physically oriented upright by the camera HAL / JPEG encoder. Do not rotate again.
                            }
                            else -> {
                                // Only when EXIF orientation tag is not present or undefined:
                                if (rawBitmap.width > rawBitmap.height && rotationDeg != 0) {
                                    matrix.postRotate(rotationDeg.toFloat())
                                } else if (rawBitmap.width > rawBitmap.height) {
                                    val rot = if (isFrontFacing) 270f else 90f
                                    matrix.postRotate(rot)
                                }
                            }
                        }

                        if (isFrontFacing && saveSelfieAsPreviewed) {
                            matrix.postScale(-1f, 1f)
                        }

                        val orientedBitmap = if (!matrix.isIdentity) {
                            val transformed = Bitmap.createBitmap(
                                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                            )
                            if (transformed != rawBitmap) {
                                rawBitmap.recycle()
                            }
                            transformed
                        } else {
                            rawBitmap
                        }

                        synchronized(capturedBitmaps) {
                            capturedBitmaps.add(orientedBitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed decoding zoom burst frame", e)
                }

                val currentCount = synchronized(capturedBitmaps) { capturedBitmaps.size }
                if (currentCount >= burstCount) {
                    if (isCompleted.compareAndSet(false, true)) {
                        readerJpeg.setOnImageAvailableListener(null, null)
                        val frames = synchronized(capturedBitmaps) { ArrayList(capturedBitmaps) }
                        executeZoomProcessing(frames, zoomRatio, quality, onComplete)
                    }
                }
            }
        }, backgroundHandler)

        try {
            val requests = mutableListOf<CaptureRequest>()
            for (i in 0 until burstCount) {
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                applyCommonSettings(builder)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                builder.set(CaptureRequest.JPEG_ORIENTATION, rotationDeg)
                builder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                builder.addTarget(readerJpeg.surface)
                requests.add(builder.build())
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "High-Quality Zoom burst frame completed")
                }
            }, backgroundHandler)

            // Watchdog fallback in case frames are dropped by Camera2
            engineScope.launch {
                kotlinx.coroutines.delay(4500)
                if (isCompleted.compareAndSet(false, true)) {
                    Log.w(TAG, "Zoom capture watchdog triggered")
                    readerJpeg.setOnImageAvailableListener(null, null)
                    val frames = synchronized(capturedBitmaps) { ArrayList(capturedBitmaps) }
                    if (frames.isNotEmpty()) {
                        executeZoomProcessing(frames, zoomRatio, quality, onComplete)
                    } else {
                        _isCapturing.value = false
                        _isZoomProcessing.value = false
                        takePhoto(onComplete)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error executing High-Quality Zoom capture burst", e)
            _isCapturing.value = false
            _isZoomProcessing.value = false
            takePhoto(onComplete)
        }
    }

    private fun executeZoomProcessing(
        frames: List<Bitmap>,
        zoomRatio: Float,
        quality: com.example.camera.zoom.ZoomProcessingQuality,
        onComplete: (Uri?) -> Unit
    ) {
        engineScope.launch(Dispatchers.Default) {
            try {
                _zoomProgress.value = 0.15f
                val enhancedBitmap = highQualityZoomEngine.processZoomedBurst(
                    burstBitmaps = frames,
                    zoomRatio = zoomRatio,
                    quality = quality,
                    onProgress = { p -> _zoomProgress.value = p }
                )

                val finalUri = highQualityZoomEngine.saveZoomImageToMediaStore(enhancedBitmap)
                if (enhancedBitmap !in frames) {
                    enhancedBitmap.recycle()
                }
                frames.forEach { it.recycle() }

                updateStorageStats()
                _isCapturing.value = false
                _isZoomProcessing.value = false
                _zoomProgress.value = 1.0f

                if (finalUri != null) {
                    _lastCapturedMedia.value = CapturedMedia(
                        uri = finalUri,
                        isVideo = false,
                        timestamp = System.currentTimeMillis(),
                        displayName = "ZOOM_${System.currentTimeMillis()}.jpg"
                    )
                }

                withContext(Dispatchers.Main) {
                    onComplete(finalUri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "High-Quality Zoom processing failed", e)
                _isCapturing.value = false
                _isZoomProcessing.value = false
                frames.forEach { it.recycle() }
                withContext(Dispatchers.Main) {
                    onComplete(null)
                }
            }
        }
    }

    /**
     * 50 Megapixel Computational Ultra-Resolution Capture:
     * Captures ONLY ONE native-resolution frame from the physical camera with OIS/EIS
     * stabilization, completely eliminating ghosting, motion blur, and double edges.
     * The single frame is then processed through an edge-aware, detail-preserving
     * computational upscaling and adaptive denoising pipeline.
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
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT

        try {
            // Build exactly ONE native-resolution capture request
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(readerJpeg.surface)
            applyCommonSettings(captureBuilder)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)

            // Enable ultra-high resolution sensor remosaic mode if physical hardware supports it (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val chars = getCharacteristics(camera.id)
                    val sensorCaps = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    if (sensorCaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)) {
                        captureBuilder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Hardware sensor ultra-high resolution mode not applicable", e)
                }
            }

            var frameProcessed = false
            var capturedIso: Int = manualIso ?: 100
            var capturedExposureNs: Long = manualExposureTimeNs ?: 20_000_000L

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
                    image.close()

                    // Ensure maximum photographic quality with zero downsampling
                    val options = BitmapFactory.Options().apply {
                        inMutable = true
                        inSampleSize = 1
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                    if (rawBitmap != null) {
                        val exif = try {
                            android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                        } catch (e: Exception) {
                            null
                        }
                        val exifOrientation = exif?.getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_UNDEFINED
                        ) ?: android.media.ExifInterface.ORIENTATION_UNDEFINED

                        val matrix = Matrix()
                        when (exifOrientation) {
                            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            else -> {
                                if (rawBitmap.width > rawBitmap.height) {
                                    val rot = if (isFrontFacing) 270f else 90f
                                    matrix.postRotate(rot)
                                }
                            }
                        }

                        // Front camera viewfinder WYSIWYG mirroring preservation on upright frame
                        if (isFrontFacing && saveSelfieAsPreviewed) {
                            matrix.postScale(-1f, 1f)
                        }

                        val uprightBitmap = if (!matrix.isIdentity) {
                            val rotated = Bitmap.createBitmap(
                                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                            )
                            if (rotated != rawBitmap) {
                                rawBitmap.recycle()
                            }
                            rotated
                        } else {
                            rawBitmap
                        }

                        engineScope.launch(Dispatchers.Default) {
                            val uri = ultraRes50MStacker.processAndSaveSingleFrame50M(
                                source = uprightBitmap,
                                iso = capturedIso,
                                exposureTimeNs = capturedExposureNs,
                                isFrontFacing = false, // already transformed & mirrored upright
                                saveMirrored = false
                            )
                            if (!uprightBitmap.isRecycled) {
                                uprightBitmap.recycle()
                            }
                            _isCapturing.value = false
                            updateStorageStats()
                            if (uri != null) {
                                _lastCapturedMedia.value = CapturedMedia(
                                    uri = uri,
                                    isVideo = false,
                                    timestamp = System.currentTimeMillis(),
                                    displayName = "50M_COMPUTATIONAL.jpg"
                                )
                            }
                            withContext(Dispatchers.Main) {
                                onComplete(uri)
                            }
                        }
                    } else {
                        _isCapturing.value = false
                        engineScope.launch(Dispatchers.Main) {
                            onComplete(null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring single 50M frame", e)
                    try { image.close() } catch (ignored: Exception) {}
                    _isCapturing.value = false
                    engineScope.launch(Dispatchers.Main) {
                        onComplete(null)
                    }
                }
            }, backgroundHandler)

            // Capture exactly one frame
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    capturedIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: manualIso ?: 100
                    capturedExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: manualExposureTimeNs ?: 20_000_000L
                    Log.d(TAG, "50M single frame capture completed (ISO=$capturedIso, Exp=${capturedExposureNs}ns)")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting 50M single-frame capture", e)
            _isCapturing.value = false
            onComplete(null)
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
            applyCommonSettings(captureBuilder)
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

                            val options = BitmapFactory.Options().apply {
                                inMutable = true
                            }
                            val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                            if (rawBitmap != null) {
                                val exif = try {
                                    android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                                } catch (e: Exception) {
                                    null
                                }
                                val exifOrientation = exif?.getAttributeInt(
                                    android.media.ExifInterface.TAG_ORIENTATION,
                                    android.media.ExifInterface.ORIENTATION_UNDEFINED
                                ) ?: android.media.ExifInterface.ORIENTATION_UNDEFINED

                                val matrix = Matrix()
                                when (exifOrientation) {
                                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                                    else -> {
                                        if (rawBitmap.width > rawBitmap.height) {
                                            val rot = if (isFrontFacing) 270f else 90f
                                            matrix.postRotate(rot)
                                        }
                                    }
                                }

                                // Front camera viewfinder WYSIWYG mirroring preservation
                                if (isFrontFacing && saveSelfieAsPreviewed) {
                                    matrix.postScale(-1f, 1f)
                                }

                                val orientedBitmap = if (!matrix.isIdentity) {
                                    val transformed = Bitmap.createBitmap(
                                        rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                                    )
                                    if (transformed != rawBitmap) {
                                        rawBitmap.recycle()
                                    }
                                    transformed
                                } else {
                                    rawBitmap
                                }

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
                    Log.d(TAG, "Portrait still frame capture triggered")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing still bitmap for portrait", e)
            _isCapturing.value = false
            onBitmapCaptured(null)
        }
    }

    /**
     * Helper to resolve the active preview surface for either Main or Ultra-Wide streams.
     */
    private fun getActivePreviewSurface(): Surface? {
        val activeLens = _selectedLens.value
        val isUltraWide = activeLens?.lensType == LensType.ULTRAWIDE
        val compositorSurf = if (isUltraWide) {
            motorolaSwitchEngine.compositor.ultraWideCameraSurface
        } else {
            motorolaSwitchEngine.compositor.mainCameraSurface
        }
        return if (compositorSurf != null && compositorSurf.isValid) {
            compositorSurf
        } else if (previewSurface?.isValid == true) {
            previewSurface
        } else {
            null
        }
    }

    private fun findBestFpsRange(chars: CameraCharacteristics, targetFps: Int): Range<Int>? {
        val availableFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return availableFpsRanges.firstOrNull { it.upper == targetFps && it.lower == targetFps }
            ?: availableFpsRanges.firstOrNull { it.upper == targetFps }
            ?: availableFpsRanges.firstOrNull { it.upper >= targetFps && it.lower <= targetFps }
    }

    /**
     * Creates an end-to-end 10-bit HDR or standard capture session for recording.
     * When 10-bit is requested and the device supports DynamicRangeProfiles (Android 13+),
     * this configures the recorder surface with HLG10/HDR10 to ensure raw 10-bit HAL stream buffers.
     */
    private fun createRecordingCaptureSession(
        camera: CameraDevice,
        previewSurface: Surface,
        recorderSurface: Surface,
        is10Bit: Boolean,
        callback: CameraCaptureSession.StateCallback
    ) {
        val executor = Executor { command -> backgroundHandler?.post(command) ?: command.run() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && is10Bit) {
            try {
                val chars = getCharacteristics(camera.id)
                val dynamicProfiles = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                val supported = dynamicProfiles?.supportedProfiles ?: emptySet()
                val targetProfile = when {
                    supported.contains(DynamicRangeProfiles.HLG10) -> DynamicRangeProfiles.HLG10
                    supported.contains(DynamicRangeProfiles.HDR10) -> DynamicRangeProfiles.HDR10
                    supported.contains(DynamicRangeProfiles.HDR10_PLUS) -> DynamicRangeProfiles.HDR10_PLUS
                    else -> null
                }

                if (targetProfile != null) {
                    val recorderConfig = OutputConfiguration(recorderSurface).apply {
                        dynamicRangeProfile = targetProfile
                    }
                    val previewConfig = OutputConfiguration(previewSurface)
                    val sessionConfig = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(previewConfig, recorderConfig),
                        executor,
                        callback
                    )
                    camera.createCaptureSession(sessionConfig)
                    Log.i(TAG, "Configured 10-bit CameraCaptureSession with DynamicRangeProfile: $targetProfile")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create 10-bit SessionConfiguration, falling back to standard capture session", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(previewSurface), OutputConfiguration(recorderSurface)),
                    executor,
                    callback
                )
                camera.createCaptureSession(sessionConfig)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed SessionConfiguration for standard recording, falling back to createCaptureSession", e)
            }
        }

        @Suppress("DEPRECATION")
        camera.createCaptureSession(listOf(previewSurface, recorderSurface), callback, backgroundHandler)
    }

    /**
     * Safely releases failed recording resources, restores preview, and notifies error callback.
     */
    private fun cleanupFailedRecording(onError: (String) -> Unit, message: String) {
        isStartingRecording.set(false)
        isStoppingRecording.set(false)
        _isRecordingVideo.value = false
        isSoftwareCinemaRecording = false
        videoTimerJob?.cancel()
        activeRecordingSurface = null

        try {
            cinemaSoftwareRecorder.stopRecording()
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
                _selectedVideoResolution.value ?: CameraResolution(1920, 1080)
            }

            val supportedVideoSizes = map?.getOutputSizes(MediaRecorder::class.java)
                ?: map?.getOutputSizes(SurfaceTexture::class.java)
                ?: emptyArray()

            val isSupported = supportedVideoSizes.any { it.width == requestedRes.width && it.height == requestedRes.height }
            val videoRes = if (isSupported) {
                requestedRes
            } else {
                val largest = supportedVideoSizes
                    .filter { maxOf(it.width, it.height) <= 3840 }
                    .maxByOrNull { it.width * it.height }
                if (largest != null) {
                    Log.w(TAG, "[RECORDING] Size ${requestedRes.width}x${requestedRes.height} unsupported for ${lens.lensType}, using ${largest.width}x${largest.height}")
                    CameraResolution(largest.width, largest.height)
                } else {
                    CameraResolution(1920, 1080)
                }
            }

            // 2. Validate FPS range
            val targetFps = if (isCinema) cinemaConfig.value.videoFps else videoFps
            val matchedFpsRange = chars?.let { findBestFpsRange(it, targetFps) }

            // 3. Dynamic Range & Codec validation
            val cinemaCodec = if (isCinema) cinemaConfig.value.codec else CinemaCodec.H264
            val dynamicProfiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            } else null
            val supportedProfiles = dynamicProfiles?.supportedProfiles ?: emptySet()
            val has10BitDynamicRange = supportedProfiles.contains(DynamicRangeProfiles.HLG10) ||
                    supportedProfiles.contains(DynamicRangeProfiles.HDR10) ||
                    supportedProfiles.contains(DynamicRangeProfiles.HDR10_PLUS)

            val is10BitRequested = isCinema &&
                    (cinemaConfig.value.logBitDepth == LogBitDepth.BIT_10 || cinemaCodec == CinemaCodec.PRORES) &&
                    has10BitDynamicRange

            val baseBitrate = if (isCinema) {
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
                    videoRes.width >= 3840 -> 40_000_000
                    videoRes.width >= 1920 -> 20_000_000
                    else -> 10_000_000
                }
            }
            val bitrate = baseBitrate
            val isSoftwareCinema = isCinema && (cinemaCodec == CinemaCodec.PRORES || cinemaCodec == CinemaCodec.VP9)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val prefix = if (isCinema) "CINEMA_" else "VID_"
            val extension = if (isSoftwareCinema && cinemaCodec == CinemaCodec.VP9) "webm" else "mp4"
            val mimeType = if (extension == "webm") "video/webm" else "video/mp4"
            val fileName = "${prefix}$timeStamp.$extension"

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

            // Setup recording target surface
            val recorderSurface: Surface
            if (isSoftwareCinema) {
                isSoftwareCinemaRecording = true
                recorderSurface = cinemaSoftwareRecorder.startRecording(
                    destFile = tempFile,
                    width = videoRes.width,
                    height = videoRes.height,
                    fps = targetFps,
                    bitrate = bitrate,
                    codec = cinemaCodec,
                    bitDepth = if (is10BitRequested || cinemaCodec == CinemaCodec.PRORES) LogBitDepth.BIT_10 else LogBitDepth.BIT_8,
                    isAudioEnabled = isAudioEnabled,
                    orientationHint = getVideoOrientationHint()
                )
            } else {
                @Suppress("DEPRECATION")
                val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    MediaRecorder()
                }
                mediaRecorder = mr

                mr.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder runtime error: what=$what extra=$extra")
                    onError("Hardware recording error (code=$what, extra=$extra)")
                }

                if (isAudioEnabled) {
                    try {
                        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioSource.MIC not available, continuing without audio", e)
                    }
                }

                mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE)
                videoRecordingFileDescriptor = pfd
                mr.setOutputFile(pfd.fileDescriptor)

                mr.setVideoEncodingBitRate(bitrate)
                mr.setVideoFrameRate(targetFps)
                mr.setVideoSize(videoRes.width, videoRes.height)

                val useHevc = (cinemaCodec == CinemaCodec.H265) || is10BitRequested
                if (useHevc) {
                    mr.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                    if (is10BitRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            mr.setVideoEncodingProfileLevel(
                                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
                            )
                        } catch (e: Exception) {
                            try {
                                mr.setVideoEncodingProfileLevel(
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41
                                )
                            } catch (ignored: Exception) {}
                        }
                    }
                } else {
                    mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                }

                if (isAudioEnabled) {
                    try {
                        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        mr.setAudioSamplingRate(48000)
                        mr.setAudioEncodingBitRate(192000)
                    } catch (ignored: Exception) {}
                }

                val orientationHint = getVideoOrientationHint()
                mr.setOrientationHint(orientationHint)

                try {
                    mr.prepare()
                } catch (e: Exception) {
                    Log.w(TAG, "MediaRecorder prepare failed with primary settings, trying fallback", e)
                    if (useHevc) {
                        mr.reset()
                        if (isAudioEnabled) {
                            try { mr.setAudioSource(MediaRecorder.AudioSource.MIC) } catch (ignored: Exception) {}
                        }
                        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        mr.setOutputFile(tempFile.absolutePath)
                        mr.setVideoEncodingBitRate(minOf(bitrate, 40_000_000))
                        mr.setVideoFrameRate(targetFps)
                        mr.setVideoSize(videoRes.width, videoRes.height)
                        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                        if (isAudioEnabled) {
                            try {
                                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                mr.setAudioSamplingRate(48000)
                                mr.setAudioEncodingBitRate(192000)
                            } catch (ignored: Exception) {}
                        }
                        mr.setOrientationHint(orientationHint)
                        mr.prepare()
                    } else {
                        throw e
                    }
                }

                recorderSurface = mr.surface
            }

            activeRecordingSurface = recorderSurface

            // Seamless Camera2 session transition on camera backgroundHandler:
            // Do NOT close or abort currentSession beforehand; CameraDevice.createCaptureSession
            // automatically transitions sessions while keeping preview buffers alive.
            backgroundHandler?.post {
                try {
                    val recordBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurf)
                        addTarget(recorderSurface)
                        applyCommonSettings(this)
                        set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                        if (matchedFpsRange != null) {
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, matchedFpsRange)
                        }
                        if (isVideoStabilizationEnabled) {
                            val eisModes = chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
                            if (eisModes.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                            }
                        }
                    }
                    previewRequestBuilder = recordBuilder

                    val sessionCallback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.i(TAG, "[RECORDING_SESSION] Video recording session configured")
                            captureSession = session
                            try {
                                session.setRepeatingRequest(recordBuilder.build(), captureCallback, backgroundHandler)
                                if (!isSoftwareCinema) {
                                    mediaRecorder?.start()
                                }
                                _isRecordingVideo.value = true
                                isStartingRecording.set(false)
                                startVideoTimer()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed starting repeating request or recorder", e)
                                cleanupFailedRecording(onError, "Failed to start recording: ${e.message}")
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "[RECORDING_SESSION] Video capture session configuration failed")
                            cleanupFailedRecording(onError, "Camera hardware failed to configure video capture session")
                        }
                    }

                    createRecordingCaptureSession(
                        camera = camera,
                        previewSurface = previewSurf,
                        recorderSurface = recorderSurface,
                        is10Bit = is10BitRequested || (isSoftwareCinema && cinemaCodec == CinemaCodec.PRORES),
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
                _videoDurationSeconds.value += 1
            }
        }
    }

    /**
     * Stop Video Recording
     */
    fun stopVideoRecording() {
        if (!_isRecordingVideo.value && !isSoftwareCinemaRecording) return
        if (!isStoppingRecording.compareAndSet(false, true)) return

        activeRecordingSurface = null
        _isRecordingVideo.value = false
        videoTimerJob?.cancel()

        val isCinema = (currentMode == CameraMode.CINEMA)
        val fileName = currentVideoFileName ?: "VID_${System.currentTimeMillis()}.mp4"
        val mimeType = currentVideoMimeType ?: "video/mp4"
        currentVideoFileName = null
        currentVideoMimeType = null

        val activeLens = _selectedLens.value
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
        val wasSoftwareCinema = isSoftwareCinemaRecording
        isSoftwareCinemaRecording = false

        // Dispatch stop and resource cleanup to background IO so UI thread never freezes
        engineScope.launch(Dispatchers.IO) {
            try {
                if (wasSoftwareCinema) {
                    val recordedFile = try {
                        cinemaSoftwareRecorder.stopRecording()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error stopping cinema software recorder", e)
                        null
                    }
                    currentRecordingTempFile = null

                    if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0) {
                        try {
                            val savedUri = saveVideoToGallery(
                                tempFile = recordedFile,
                                fileName = fileName,
                                mimeType = mimeType,
                                isCinema = isCinema,
                                isFrontFacing = isFrontFacing
                            )
                            if (savedUri != null) {
                                _lastCapturedMedia.value = CapturedMedia(
                                    uri = savedUri,
                                    isVideo = true,
                                    timestamp = System.currentTimeMillis(),
                                    displayName = if (isCinema) "Cinema Video" else "Video",
                                    isFrontCamera = isFrontFacing
                                )
                                Log.i(TAG, "Cinema software video successfully saved: size=${recordedFile.length()} bytes, uri=$savedUri")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed saving cinema recording", e)
                        } finally {
                            try { recordedFile.delete() } catch (ignored: Exception) {}
                            updateStorageStats()
                        }
                    }
                } else {
                    val mr = mediaRecorder
                    mediaRecorder = null
                    mr?.apply {
                        try {
                            stop()
                        } catch (e: Exception) {
                            Log.w(TAG, "MediaRecorder stop failed", e)
                        }
                        try { reset() } catch (ignored: Throwable) {}
                        try { release() } catch (ignored: Throwable) {}
                    }

                    try { videoRecordingFileDescriptor?.close() } catch (ignored: Throwable) {}
                    videoRecordingFileDescriptor = null

                    val tempFile = currentRecordingTempFile
                    currentRecordingTempFile = null

                    if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
                        try {
                            val savedUri = saveVideoToGallery(
                                tempFile = tempFile,
                                fileName = fileName,
                                mimeType = mimeType,
                                isCinema = isCinema,
                                isFrontFacing = isFrontFacing
                            )
                            if (savedUri != null) {
                                _lastCapturedMedia.value = CapturedMedia(
                                    uri = savedUri,
                                    isVideo = true,
                                    timestamp = System.currentTimeMillis(),
                                    displayName = if (isCinema) "Cinema Video" else "Video",
                                    isFrontCamera = isFrontFacing
                                )
                                Log.i(TAG, "Hardware recorded video successfully saved: size=${tempFile.length()} bytes, uri=$savedUri")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error finalizing recorded video", e)
                        } finally {
                            try { tempFile.delete() } catch (ignored: Throwable) {}
                            updateStorageStats()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping video recording in background", e)
            } finally {
                isStoppingRecording.set(false)
                // Restore standard preview session smoothly on backgroundHandler
                backgroundHandler?.post {
                    createCameraCaptureSession()
                }
            }
        }
    }

    /**
     * Reliable Gallery saving pipeline for recorded videos with automatic fallback.
     */
    private suspend fun saveVideoToGallery(
        tempFile: File,
        fileName: String,
        mimeType: String,
        isCinema: Boolean,
        isFrontFacing: Boolean
    ): Uri? = withContext(Dispatchers.IO) {
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            Log.w(TAG, "saveVideoToGallery: tempFile is missing or empty")
            return@withContext null
        }

        // Color Processing & Export Pipeline:
        // Apply Cinema 3D LUT / Color Profile transform and front camera mirroring to final recorded video
        val cfg = if (isCinema) cinemaEngine.config else null
        val isBakeLut = cfg?.isBakeLutToOutput ?: true
        val selectedLut = cfg?.selectedLut ?: CinematicLut.NONE
        val customPath = cfg?.customLutPath
        val lutIntensity = cfg?.lutIntensity ?: 1.0f

        val cinemaLutStripBitmap: Bitmap? = if (isCinema && selectedLut != CinematicLut.NONE && isBakeLut) {
            if (selectedLut == CinematicLut.CUSTOM && customPath != null) {
                CubeLutParser.getOrLoad(customPath)?.to2DStripBitmap()
            } else {
                // Presets are processed directly through CinemaColorPipeline's 5-stage matrix pipeline
                // to maintain 100% exact mathematical visual parity with the live viewfinder
                null
            }
        } else null

        val cinemaColorMatrix = if (isCinema) {
            CinemaColorPipeline.computeCinemaColorMatrix(
                config = cinemaEngine.config,
                rec2020Params = rec2020AutoToneParams.value,
                includeCreativeLut = (cinemaLutStripBitmap == null && isBakeLut && selectedLut != CinematicLut.NONE)
            )
        } else null

        val lutSize = if (selectedLut == CinematicLut.CUSTOM && customPath != null) {
            CubeLutParser.getOrLoad(customPath)?.size ?: 33
        } else 33

        val hasColorTransform = (cinemaColorMatrix != null || cinemaLutStripBitmap != null)
        val needsColorGrade = if (isCinema) (hasColorTransform && isBakeLut) else hasColorTransform
        val needsMirror = isFrontFacing
        val needsExportPipeline = needsColorGrade || needsMirror

        var exportedFile: File? = null
        val fileToSave: File = if (needsExportPipeline) {
            val targetExport = File(context.cacheDir, "EXPORT_${System.currentTimeMillis()}_${tempFile.name}")
            val success = try {
                VideoMirrorTranscoder.transcodeVideo(
                    inputFile = tempFile,
                    outputFile = targetExport,
                    isMirrored = needsMirror,
                    colorMatrix = cinemaColorMatrix?.array,
                    lutStripBitmap = cinemaLutStripBitmap,
                    lutSize = lutSize,
                    lutIntensity = lutIntensity,
                    orientationHint = getVideoOrientationHint()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Video export transcoding failed", e)
                false
            } finally {
                try {
                    cinemaLutStripBitmap?.recycle()
                } catch (ignored: Exception) {}
            }

            val isValid = targetExport.exists() &&
                    targetExport.length() > 50_000L &&
                    VideoMirrorTranscoder.validateVideoFile(targetExport)

            if (success && isValid) {
                Log.i(TAG, "Successfully processed video export (LUT/Grade/Mirror): size=${targetExport.length()} bytes")
                exportedFile = targetExport
                targetExport
            } else {
                Log.w(TAG, "Video export pipeline failed validation; falling back to original recorded file")
                try { targetExport.delete() } catch (ignored: Exception) {}
                tempFile
            }
        } else {
            tempFile
        }

        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

            targetUri = resolver.insert(collection, contentValues)
            if (targetUri != null) {
                resolver.openOutputStream(targetUri, "w")?.use { out ->
                    fileToSave.inputStream().use { input ->
                        input.copyTo(out)
                    }
                    out.flush()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val updateValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        put(MediaStore.Video.Media.SIZE, fileToSave.length())
                    }
                    resolver.update(targetUri, updateValues, null, null)
                }

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(fileToSave.absolutePath),
                    arrayOf(mimeType)
                ) { _, scannedUri ->
                    Log.d(TAG, "Video scanned into MediaStore: $scannedUri")
                }
                return@withContext targetUri
            }
        } catch (e: Exception) {
            Log.w(TAG, "Primary MediaStore insertion failed, falling back to direct DCIM/Camera write", e)
            if (targetUri != null) {
                try { resolver.delete(targetUri, null, null) } catch (ignored: Exception) {}
            }
        } finally {
            if (exportedFile != null) {
                try { exportedFile.delete() } catch (ignored: Exception) {}
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
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType)
            ) { _, uri ->
                scannedUri = uri
                Log.d(TAG, "Fallback video scanned: $uri")
            }
            return@withContext scannedUri ?: Uri.fromFile(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback video save failed", e)
            return@withContext null
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

    private fun saveJpegBytesToMediaStore(bytes: ByteArray): Uri? {
        val activeLens = _selectedLens.value
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT

        val finalBytes = if (isFrontFacing && saveSelfieAsPreviewed) {
            try {
                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rawBitmap != null) {
                    val exif = try {
                        android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                    } catch (e: Exception) { null }
                    val exifOrientation = exif?.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_UNDEFINED
                    ) ?: android.media.ExifInterface.ORIENTATION_UNDEFINED

                    val matrix = Matrix()
                    when (exifOrientation) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        else -> {
                            if (rawBitmap.width > rawBitmap.height) {
                                matrix.postRotate(270f)
                            }
                        }
                    }
                    // Mirror horizontally to save selfie exactly as previewed in viewfinder
                    matrix.postScale(-1f, 1f)

                    val mirroredBitmap = Bitmap.createBitmap(
                        rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                    )
                    if (mirroredBitmap != rawBitmap) {
                        rawBitmap.recycle()
                    }
                    val stream = java.io.ByteArrayOutputStream()
                    mirroredBitmap.compress(Bitmap.CompressFormat.JPEG, 98, stream)
                    mirroredBitmap.recycle()
                    stream.toByteArray()
                } else {
                    bytes
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to mirror selfie JPEG, falling back to original", e)
                bytes
            }
        } else {
            bytes
        }

        val photoFilter = selectedPhotoFilter
        val outputBytes = if (photoFilter != PhotoFilter.ORIGINAL && currentMode == CameraMode.PHOTO) {
            try {
                val matrix = photoFilter.toAndroidColorMatrix()
                val srcBmp = BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size)
                if (srcBmp != null && matrix != null) {
                    val filteredBmp = Bitmap.createBitmap(srcBmp.width, srcBmp.height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(filteredBmp)
                    val paint = android.graphics.Paint().apply {
                        colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                    }
                    canvas.drawBitmap(srcBmp, 0f, 0f, paint)
                    srcBmp.recycle()
                    val stream = java.io.ByteArrayOutputStream()
                    filteredBmp.compress(Bitmap.CompressFormat.JPEG, 98, stream)
                    filteredBmp.recycle()
                    stream.toByteArray()
                } else {
                    srcBmp?.recycle()
                    finalBytes
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to apply photo filter to saved JPEG", e)
                finalBytes
            }
        } else {
            finalBytes
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return null

        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(outputBytes)
            }

            if (isFrontFacing && saveSelfieAsPreviewed) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val outExif = android.media.ExifInterface(pfd.fileDescriptor)
                        outExif.setAttribute(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL.toString()
                        )
                        outExif.saveAttributes()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write EXIF orientation on mirrored selfie", e)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }

            _lastCapturedMedia.value = CapturedMedia(
                uri = uri,
                isVideo = false,
                timestamp = System.currentTimeMillis(),
                displayName = fileName
            )
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save JPEG to media store", e)
            return null
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, orientationDegrees: Int): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_NIGHT_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return null

        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            _lastCapturedMedia.value = CapturedMedia(
                uri = uri,
                isVideo = false,
                timestamp = System.currentTimeMillis(),
                displayName = fileName
            )
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save night bitmap", e)
            return null
        }
    }

    private fun saveRawToMediaStore(rawImage: Image, characteristics: CameraCharacteristics) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "RAW_$timeStamp.dng"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return

            val captureResult = lastCaptureResult ?: return
            val dngCreator = DngCreator(characteristics, captureResult)

            context.contentResolver.openOutputStream(uri)?.use { out ->
                dngCreator.writeImage(out, rawImage)
            }
            dngCreator.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, completeValues, null, null)
            }
            Log.d(TAG, "Saved RAW DNG successfully to $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save RAW image", e)
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
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing capture session", e)
        }
    }

    private fun closeCameraInternal() {
        _isCameraReady.value = false
        gyroStabilizationEngine.stop()
        lastStabilizedCrop = null
        motorolaSwitchEngine.closeBackgroundCamera()
        closeCameraCaptureSession()
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera device", e)
        }
        // Only release previewSurface if texture was destroyed or surface is invalid
        if (previewSurfaceTexture == null || previewSurface?.isValid != true) {
            try {
                previewSurface?.release()
                previewSurface = null
            } catch (ignored: Throwable) {}
        }
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
    }

    fun closeCamera() {
        synchronized(cameraLifecycleLock) {
            closeCameraInternal()
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
        if (_isCameraInitialized.value && previewSurfaceTexture != null) {
            restartCamera()
        }
    }

    fun release() {
        motorolaSwitchEngine.release()
        closeCamera()
        stopBackgroundThread()
    }
}
