package com.example.camera.dualvideo.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.camera.dualvideo.gl.DualVideoGLCompositor
import com.example.camera.dualvideo.model.*
import com.example.camera.dualvideo.recorder.DualVideoRecorder
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class DualCameraEngine(
    private val context: Context,
    private val availableLenses: List<LensInfo>
) {
    companion object {
        private const val TAG = "DualCameraEngine"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val engineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var compositor: DualVideoGLCompositor? = null
    private var recorder: DualVideoRecorder? = null

    private var primaryCameraDevice: CameraDevice? = null
    private var primarySession: CameraCaptureSession? = null

    private var secondaryCameraDevice: CameraDevice? = null
    private var secondarySession: CameraCaptureSession? = null

    private val _uiState = MutableStateFlow(DualVideoUiState())
    val uiState: StateFlow<DualVideoUiState> = _uiState.asStateFlow()

    private var recordingTimerJob: Job? = null
    private val isSwitching = AtomicBoolean(false)

    fun initialize() {
        val thread = HandlerThread("DualCameraEngineThread").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)

        // 1. Detect Hardware Concurrent Capability
        val cap = DualCameraCapabilityDetector.detectCapability(context, availableLenses)
        val defaultPair = cap.supportedPairs.firstOrNull()
        val defaultRes = cap.supportedResolutions.firstOrNull() ?: DualVideoResolution(1080, 1920, "1080p")
        val defaultFps = if (cap.supportedFps.contains(30)) 30 else (cap.supportedFps.firstOrNull() ?: 30)

        val backLenses = availableLenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            .distinctBy { it.lensType to it.baseZoomRatio }
        val frontLenses = availableLenses.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            .distinctBy { it.cameraId }

        val primLens = defaultPair?.primaryLens ?: backLenses.firstOrNull { it.isPrimaryMain } ?: backLenses.firstOrNull()
        val secLens = defaultPair?.secondaryLens ?: frontLenses.firstOrNull() ?: backLenses.lastOrNull()

        val config = DualVideoConfig(
            layout = DualVideoLayout.PIP,
            pipPosition = PipPosition.TOP_RIGHT,
            resolution = defaultRes,
            fps = defaultFps,
            isAudioEnabled = true
        )

        _uiState.value = _uiState.value.copy(
            capability = cap,
            primaryLens = primLens,
            secondaryLens = secLens,
            availablePrimaryLenses = backLenses,
            availableSecondaryLenses = frontLenses,
            config = config
        )

        // 2. Start GL Compositor with native portrait dimensions
        val glComp = DualVideoGLCompositor(defaultRes.portraitWidth, defaultRes.portraitHeight)
        glComp.setFps(defaultFps)
        compositor = glComp
        updateCompositorCameraInfo(primLens, secLens)
        glComp.start {
            // Once GL textures and surfaces are created, open both cameras
            cameraHandler?.post {
                openBothCameras()
            }
        }
    }

    private fun updateCompositorCameraInfo(
        prim: LensInfo? = _uiState.value.primaryLens,
        sec: LensInfo? = _uiState.value.secondaryLens
    ) {
        if (prim == null || sec == null) return
        val isPrimFront = (prim.facing == CameraCharacteristics.LENS_FACING_FRONT)
        val primOrient = try {
            cameraManager.getCameraCharacteristics(prim.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isPrimFront) 270 else 90)
        } catch (e: Exception) { if (isPrimFront) 270 else 90 }

        val isSecFront = (sec.facing == CameraCharacteristics.LENS_FACING_FRONT)
        val secOrient = try {
            cameraManager.getCameraCharacteristics(sec.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isSecFront) 270 else 90)
        } catch (e: Exception) { if (isSecFront) 270 else 90 }

        compositor?.setCameraInfo(
            isPrimaryFront = isPrimFront,
            primaryOrientation = primOrient,
            isSecondaryFront = isSecFront,
            secondaryOrientation = secOrient
        )
    }

    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        compositor?.setPreviewSurface(surface, width, height)
    }

    @SuppressLint("MissingPermission")
    private fun openBothCameras() {
        val prim = _uiState.value.primaryLens ?: return
        val sec = _uiState.value.secondaryLens ?: return
        val handler = cameraHandler ?: return

        closeBothCameras()

        try {
            // Open Primary Camera
            cameraManager.openCamera(prim.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    primaryCameraDevice = camera
                    startPrimaryStream(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (primaryCameraDevice == camera) primaryCameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Primary camera error: $error")
                    camera.close()
                    if (primaryCameraDevice == camera) primaryCameraDevice = null
                    _uiState.value = _uiState.value.copy(errorMessage = "Primary camera open error ($error)")
                }
            }, handler)

            // Open Secondary Camera concurrently
            cameraManager.openCamera(sec.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    secondaryCameraDevice = camera
                    startSecondaryStream(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (secondaryCameraDevice == camera) secondaryCameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Secondary camera error: $error")
                    camera.close()
                    if (secondaryCameraDevice == camera) secondaryCameraDevice = null
                    _uiState.value = _uiState.value.copy(errorMessage = "Secondary camera open error ($error)")
                }
            }, handler)

        } catch (t: Throwable) {
            Log.e(TAG, "Failed opening concurrent cameras", t)
            _uiState.value = _uiState.value.copy(errorMessage = "Concurrent camera error: ${t.message}")
        }
    }

    private fun startPrimaryStream(camera: CameraDevice, initialZoom: Float = 1.0f) {
        val surf = compositor?.surfacePrimary ?: return
        val handler = cameraHandler ?: return

        try {
            val chars = try { cameraManager.getCameraCharacteristics(camera.id) } catch (e: Exception) { null }
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surf)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyZoomToBuilder(this, chars, initialZoom)
            }

            camera.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    primarySession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, handler)
                        checkStreamingReady()
                    } catch (e: Exception) {
                        Log.e(TAG, "Primary repeating request failed", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Primary session configure failed")
                }
            }, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed creating primary capture session", t)
        }
    }

    private fun startSecondaryStream(camera: CameraDevice) {
        val surf = compositor?.surfaceSecondary ?: return
        val handler = cameraHandler ?: return

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surf)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            camera.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    secondarySession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, handler)
                        checkStreamingReady()
                    } catch (e: Exception) {
                        Log.e(TAG, "Secondary repeating request failed", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Secondary session configure failed")
                }
            }, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed creating secondary capture session", t)
        }
    }

    private fun checkStreamingReady() {
        if (primarySession != null && secondarySession != null) {
            _uiState.value = _uiState.value.copy(
                isStreamingActive = true,
                errorMessage = null
            )
        }
    }

    fun setLayout(layout: DualVideoLayout) {
        val newConfig = _uiState.value.config.copy(layout = layout)
        _uiState.value = _uiState.value.copy(config = newConfig)
        compositor?.setLayout(layout, newConfig.pipPosition)
    }

    fun setPipPosition(pos: PipPosition) {
        val newConfig = _uiState.value.config.copy(pipPosition = pos)
        _uiState.value = _uiState.value.copy(config = newConfig)
        compositor?.setPipPosition(pos)
    }

    fun setResolution(resolution: DualVideoResolution) {
        if (_uiState.value.isRecording) return
        val newConfig = _uiState.value.config.copy(resolution = resolution)
        _uiState.value = _uiState.value.copy(config = newConfig)

        // Recreate compositor with new portrait resolution
        cameraHandler?.post {
            compositor?.release()
            val glComp = DualVideoGLCompositor(resolution.portraitWidth, resolution.portraitHeight)
            glComp.setFps(newConfig.fps)
            compositor = glComp
            updateCompositorCameraInfo()
            glComp.setLayout(newConfig.layout, newConfig.pipPosition)
            glComp.start {
                cameraHandler?.post {
                    openBothCameras()
                }
            }
        }
    }

    fun setFps(fps: Int) {
        if (_uiState.value.isRecording) return
        val newConfig = _uiState.value.config.copy(fps = fps)
        _uiState.value = _uiState.value.copy(config = newConfig)
        compositor?.setFps(fps)
    }

    fun setAudioEnabled(enabled: Boolean) {
        val newConfig = _uiState.value.config.copy(isAudioEnabled = enabled)
        _uiState.value = _uiState.value.copy(config = newConfig)
    }

    /**
     * Allows real-time lens switching on primary camera during preview AND active recording!
     */
    fun switchPrimaryLens(targetLens: LensInfo) {
        if (targetLens.id == _uiState.value.primaryLens?.id) return
        if (!isSwitching.compareAndSet(false, true)) return

        _uiState.value = _uiState.value.copy(
            primaryLens = targetLens,
            isSwitchingLens = true
        )
        updateCompositorCameraInfo(prim = targetLens)

        cameraHandler?.post {
            try {
                // If target lens is on the same open device (e.g. logical multi-camera 0.5x, 1x, 2x)
                if (primaryCameraDevice != null && primaryCameraDevice?.id == targetLens.cameraId) {
                    applyPrimaryZoom(targetLens.baseZoomRatio)
                    isSwitching.set(false)
                    _uiState.value = _uiState.value.copy(isSwitchingLens = false)
                    return@post
                }

                primarySession?.stopRepeating()
                primarySession?.close()
                primarySession = null

                primaryCameraDevice?.close()
                primaryCameraDevice = null

                cameraManager.openCamera(targetLens.cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        primaryCameraDevice = camera
                        startPrimaryStream(camera, targetLens.baseZoomRatio)
                        isSwitching.set(false)
                        _uiState.value = _uiState.value.copy(isSwitchingLens = false)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (primaryCameraDevice == camera) primaryCameraDevice = null
                        isSwitching.set(false)
                        _uiState.value = _uiState.value.copy(isSwitchingLens = false)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (primaryCameraDevice == camera) primaryCameraDevice = null
                        isSwitching.set(false)
                        _uiState.value = _uiState.value.copy(isSwitchingLens = false)
                    }
                }, cameraHandler)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed switching primary lens to ${targetLens.lensType}", t)
                isSwitching.set(false)
                _uiState.value = _uiState.value.copy(isSwitchingLens = false)
            }
        }
    }

    private fun applyPrimaryZoom(zoomRatio: Float) {
        val session = primarySession ?: return
        val camera = primaryCameraDevice ?: return
        val surf = compositor?.surfacePrimary ?: return
        val handler = cameraHandler ?: return
        try {
            val chars = try { cameraManager.getCameraCharacteristics(camera.id) } catch (e: Exception) { null }
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surf)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyZoomToBuilder(this, chars, zoomRatio)
            }
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed applying primary zoom $zoomRatio", e)
        }
    }

    private fun applyZoomToBuilder(builder: CaptureRequest.Builder, chars: CameraCharacteristics?, zoomRatio: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                val clamped = zoomRatio.coerceIn(range.lower, range.upper)
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
                return
            }
        }
        val sensorRect = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (sensorRect != null && zoomRatio > 1.0f) {
            val cropW = (sensorRect.width() / zoomRatio).toInt()
            val cropH = (sensorRect.height() / zoomRatio).toInt()
            val cropX = (sensorRect.width() - cropW) / 2
            val cropY = (sensorRect.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH))
        }
    }

    /**
     * Swaps Primary and Secondary cameras smoothly.
     */
    fun swapCameras() {
        val oldPrim = _uiState.value.primaryLens ?: return
        val oldSec = _uiState.value.secondaryLens ?: return

        _uiState.value = _uiState.value.copy(
            primaryLens = oldSec,
            secondaryLens = oldPrim
        )
        updateCompositorCameraInfo(prim = oldSec, sec = oldPrim)

        cameraHandler?.post {
            openBothCameras()
        }
    }

    fun startRecording() {
        if (_uiState.value.isRecording) return
        val config = _uiState.value.config

        try {
            val rec = DualVideoRecorder(
                context = context,
                videoWidth = config.resolution.portraitWidth,
                videoHeight = config.resolution.portraitHeight,
                frameRate = config.fps,
                isAudioEnabled = config.isAudioEnabled,
                orientationHint = 0
            )
            val encoderSurface = rec.prepare()
            compositor?.setRecordingSurface(encoderSurface)
            rec.start()
            recorder = rec

            _uiState.value = _uiState.value.copy(
                isRecording = true,
                recordingDurationSeconds = 0
            )

            // Duration timer ticker
            recordingTimerJob?.cancel()
            recordingTimerJob = engineScope.launch {
                var seconds = 0
                while (isActive && _uiState.value.isRecording) {
                    delay(1000L)
                    seconds++
                    _uiState.value = _uiState.value.copy(recordingDurationSeconds = seconds)
                }
            }

            Log.i(TAG, "Dual video recording started at ${config.resolution.width}x${config.resolution.height} @ ${config.fps}fps")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start dual video recording", t)
            _uiState.value = _uiState.value.copy(errorMessage = "Recording error: ${t.message}")
        }
    }

    fun stopRecording(onComplete: (Uri?) -> Unit = {}) {
        if (!_uiState.value.isRecording) return

        recordingTimerJob?.cancel()
        recordingTimerJob = null

        compositor?.stopRecording()
        val savedUri = recorder?.stop()
        recorder = null

        _uiState.value = _uiState.value.copy(
            isRecording = false,
            recordingDurationSeconds = 0,
            lastRecordedVideoUri = savedUri
        )

        onComplete(savedUri)
        Log.i(TAG, "Dual video recording stopped, saved to: $savedUri")
    }

    private fun getOrientationHint(): Int {
        val prim = _uiState.value.primaryLens ?: return 90
        val isFront = prim.facing == CameraCharacteristics.LENS_FACING_FRONT
        return try {
            val chars = cameraManager.getCameraCharacteristics(prim.cameraId)
            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isFront) 270 else 90)
        } catch (e: Exception) {
            if (isFront) 270 else 90
        }
    }

    private fun closeBothCameras() {
        try { primarySession?.stopRepeating() } catch (ignored: Throwable) {}
        try { primarySession?.close() } catch (ignored: Throwable) {}
        primarySession = null

        try { primaryCameraDevice?.close() } catch (ignored: Throwable) {}
        primaryCameraDevice = null

        try { secondarySession?.stopRepeating() } catch (ignored: Throwable) {}
        try { secondarySession?.close() } catch (ignored: Throwable) {}
        secondarySession = null

        try { secondaryCameraDevice?.close() } catch (ignored: Throwable) {}
        secondaryCameraDevice = null
    }

    fun release() {
        recordingTimerJob?.cancel()
        if (_uiState.value.isRecording) {
            stopRecording()
        }
        cameraHandler?.post {
            closeBothCameras()
            compositor?.release()
            compositor = null
            cameraThread?.quitSafely()
            cameraThread = null
            cameraHandler = null
        }
        engineScope.cancel()
    }
}
