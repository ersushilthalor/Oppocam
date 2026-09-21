package com.example.camera.tracking.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.camera.data.CameraPreferences
import com.example.camera.tracking.camera.CameraXManager
import com.example.camera.tracking.engine.AspectRatioCropEngine
import com.example.camera.tracking.engine.CropController
import com.example.camera.tracking.engine.DigitalGimbalEngine
import com.example.camera.tracking.engine.TrackedVideoRecorder
import com.example.camera.tracking.ml.AdaptiveTrackingLearner
import com.example.camera.tracking.ml.SubjectTracker
import com.example.camera.tracking.model.*
import com.example.camera.tracking.util.MediaStorageHelper
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

typealias CameraTrackingUiState = com.example.camera.tracking.model.CameraTrackingUiState

/**
 * Central State & Pipeline Orchestrator for AI Subject Tracking.
 * Synchronizes:
 * 1. CameraX High-rate sensor frames
 * 2. ML Kit Vision Detection & 2nd-Order Kinematic Predictor
 * 3. 3x Dynamic Crop Window with Aspect-Ratio Safety
 * 4. Hardware Gyro Digital Gimbal (EIS)
 * 5. MediaStore Auto-Save & Hardware Video Encoding
 */
class CameraTrackingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraTrackingVM"
    }

    private val preferences = CameraPreferences(application.applicationContext)
    private val initialLens = preferences.trackingLens

    private val _uiState = MutableStateFlow(
        CameraTrackingUiState(
            selectedLens = initialLens,
            isFrontCamera = initialLens.isFront
        )
    )
    val uiState: StateFlow<CameraTrackingUiState> = _uiState.asStateFlow()

    // Engines
    val cropController = CropController(targetZoom = 1.0f)
    val digitalGimbalEngine = DigitalGimbalEngine(application)
    private val videoRecorder = TrackedVideoRecorder()

    private var cameraXManager: CameraXManager? = null

    val subjectTracker = SubjectTracker { status, activeSub, candidates ->
        _uiState.update { current ->
            current.copy(
                trackingStatus = status,
                activeSubject = activeSub,
                allDetections = candidates
            )
        }
    }

    // Frame references
    @Volatile
    private var latestSourceBitmap: Bitmap? = null
    private val isProcessingMlFrame = AtomicBoolean(false)

    // Timing & FPS metrics
    private var lastFrameTimeNs = System.nanoTime()
    private var frameCount = 0
    private var lastFpsCalcTimeMs = SystemClock.uptimeMillis()
    private var lastFocusUpdateTime = 0L

    // Tracking input resolution performance monitoring & auto-fallback
    private var lastManualResolutionChangeTimeMs = SystemClock.uptimeMillis()
    private var lowFpsWindowCount = 0
    private var resolutionNoticeJob: Job? = null

    // Recording duration timer
    private var recordingTimerJob: Job? = null

    // Continuous 60fps smoothing loop
    private var smoothingLoopJob: Job? = null

    // Adaptive tracking learner instance
    private var adaptiveLearner: AdaptiveTrackingLearner? = null

    init {
        // Start continuous crop smoothing and kinematic prediction loop
        startSmoothingLoop()
    }

    /**
     * Initializes CameraX with the host LifecycleOwner.
     */
    fun initCamera(lifecycleOwner: LifecycleOwner, context: Context) {
        if (cameraXManager != null) return

        if (adaptiveLearner == null) {
            val learner = AdaptiveTrackingLearner(context.applicationContext)
            adaptiveLearner = learner
            subjectTracker.adaptiveLearner = learner
            _uiState.update { it.copy(learnedSubjectsCount = learner.getLearnedProfilesCount()) }
        }

        digitalGimbalEngine.isEnabled = _uiState.value.isGimbalEnabled
        digitalGimbalEngine.start()

        val manager = CameraXManager(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onFrameAvailable = { bitmap, inputImage, mlW, mlH ->
                onNewCameraFrame(bitmap, inputImage, mlW, mlH)
            }
        )
        cameraXManager = manager
        manager.setFps(_uiState.value.selectedFpsOption)
        manager.setTrackingResolution(_uiState.value.trackingResolution)
        manager.setViewfinderResolution(_uiState.value.viewfinderResolution.width, _uiState.value.viewfinderResolution.height)
        val available = manager.getAvailableLenses()
        val preferredLens = preferences.trackingLens
        val targetLens = if (available.contains(preferredLens)) preferredLens else TrackingCameraLens.WIDE
        _uiState.update {
            it.copy(
                availableLenses = available,
                selectedLens = targetLens,
                isFrontCamera = targetLens.isFront
            )
        }
        manager.startCamera(lens = targetLens)
    }

    private fun startSmoothingLoop() {
        smoothingLoopJob?.cancel()
        smoothingLoopJob = viewModelScope.launch(Dispatchers.Default) {
            var lastLoopTime = System.nanoTime()
            while (isActive) {
                val now = System.nanoTime()
                val dt = ((now - lastLoopTime) / 1_000_000_000f).coerceIn(0.005f, 0.05f)
                lastLoopTime = now

                // Update continuous kinematics for active tracking
                subjectTracker.updateContinuousKinematics(dt)

                // Update Digital Gimbal shake compensation
                val gimbalState = digitalGimbalEngine.updateFrame(dt)
                cropController.gimbalOffsetX = gimbalState.offsetX
                cropController.gimbalOffsetY = gimbalState.offsetY

                // Step crop controller motion smoothing
                val activeSub = subjectTracker.getActiveSubject()
                val isTracking = subjectTracker.getTrackingStatus() == TrackingStatus.TRACKING_LOCKED ||
                        subjectTracker.getTrackingStatus() == TrackingStatus.OCCLUDED_PREDICTING

                if (isTracking && activeSub != null) {
                    cropController.setTarget(
                        PointF(activeSub.bounds.centerX, activeSub.bounds.centerY),
                        activeTracking = true,
                        desiredZoom = 3.0f
                    )
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFocusUpdateTime > 450) {
                        lastFocusUpdateTime = now
                        cameraXManager?.focusOnRegion(activeSub.bounds.centerX, activeSub.bounds.centerY)
                    }
                } else if (!cropController.isCinematicPanActive) {
                    // Manual tracking only: When no subject is locked, maintain wide view (1.0x).
                    // Tracking must start only when the user explicitly taps a subject.
                    cropController.setTarget(null, activeTracking = false, desiredZoom = 1.0f)
                }

                val newCrop = cropController.update(dt)

                // Record frame if recording is active
                latestSourceBitmap?.let { bmp ->
                    if (videoRecorder.isRecordingActive() && !bmp.isRecycled) {
                        videoRecorder.recordFrame(bmp, newCrop)
                    }
                }

                val learnedCount = adaptiveLearner?.getLearnedProfilesCount() ?: 0

                _uiState.update {
                    it.copy(
                        cropWindow = newCrop,
                        currentZoom = newCrop.zoomFactor,
                        gimbalState = gimbalState,
                        isCinematicPanActive = cropController.isCinematicPanActive,
                        cinematicPanProgress = cropController.cinematicPanProgress,
                        learnedSubjectsCount = learnedCount
                    )
                }

                val delayMs = _uiState.value.selectedFpsOption.loopDelayMs
                delay(delayMs)
            }
        }
    }

    private fun onNewCameraFrame(bitmap: Bitmap, inputImage: InputImage, mlW: Int, mlH: Int) {
        latestSourceBitmap = bitmap

        // Measure real FPS accurately using elapsed monotonic clock window
        frameCount++
        val nowMs = SystemClock.uptimeMillis()
        val elapsed = nowMs - lastFpsCalcTimeMs
        if (elapsed >= 1000L) {
            val measuredFps = ((frameCount * 1000L) / elapsed).toInt()
            frameCount = 0
            lastFpsCalcTimeMs = nowMs
            _uiState.update { it.copy(fps = measuredFps) }

            // Automatic fallback: If 1080p AI tracking cannot maintain 30 FPS,
            // automatically switch to 720p to preserve rock-solid 30 FPS tracking.
            if (_uiState.value.trackingResolution == TrackingResolution.FHD_1080P) {
                if (nowMs - lastManualResolutionChangeTimeMs > 3500L) {
                    if (measuredFps < 26) {
                        lowFpsWindowCount++
                        if (lowFpsWindowCount >= 2) {
                            triggerAutoSwitchTo720p()
                        }
                    } else {
                        lowFpsWindowCount = 0
                    }
                }
            }
        }

        // Process frame with decoupled tracking pipeline:
        // Periodic heavy AI detection + high-speed continuous lightweight tracking on every frame
        subjectTracker.processFrame(
            image = inputImage,
            imageWidth = mlW,
            imageHeight = mlH,
            sourceBitmap = bitmap
        )

        _uiState.update { it.copy(currentFrame = bitmap) }
    }

    /**
     * User taps the viewfinder to track an object or person.
     * [normX, normY] are in normalized source frame coordinates [0..1].
     */
    fun onTapToTrack(normX: Float, normY: Float) {
        cameraXManager?.focusOnRegion(normX, normY)
        subjectTracker.selectSubjectAt(
            srcX = normX,
            srcY = normY,
            allCurrentDetections = _uiState.value.allDetections,
            sourceBitmap = latestSourceBitmap
        )
    }

    /**
     * Unlocks subject tracking and zooms back out to 1.0x wide.
     */
    fun unlockTracking() {
        subjectTracker.unlock()
        cropController.setTarget(null, activeTracking = false, desiredZoom = 1.0f)
    }

    /**
     * Handles shutter click: captures photo or starts/stops video recording.
     */
    fun onShutterClicked(context: Context) {
        if (_uiState.value.captureMode == CameraCaptureMode.PHOTO) {
            captureTrackedPhoto(context)
        } else {
            toggleVideoRecording(context)
        }
    }

    private fun captureTrackedPhoto(context: Context) {
        val srcBitmap = latestSourceBitmap ?: return
        if (srcBitmap.isRecycled) return

        com.example.camera.sound.CameraSoundManager.playShutter()

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(flashFeedback = true) }

            val crop = _uiState.value.cropWindow
            val targetAspect = _uiState.value.aspectRatio.ratioValue

            // Uniformly crop to exact 3x tracking region with zero distortion
            val croppedBitmap = AspectRatioCropEngine.cropBitmap(srcBitmap, crop, targetAspect)

            // Automatically save to Gallery (DCIM/Camera3X)
            val (savedUri, filePath) = MediaStorageHelper.savePhotoToGallery(
                context = context,
                bitmap = croppedBitmap,
                aspectRatioLabel = _uiState.value.aspectRatio.label
            )

            val mediaItem = CapturedMediaItem(
                uri = savedUri ?: Uri.EMPTY,
                galleryUri = savedUri,
                filePath = filePath,
                isVideo = false,
                thumbnailBitmap = croppedBitmap,
                aspectRatioLabel = _uiState.value.aspectRatio.label,
                resolutionLabel = "${croppedBitmap.width}x${croppedBitmap.height}",
                subjectLabel = _uiState.value.activeSubject?.label
            )

            _uiState.update {
                it.copy(
                    lastCapturedMedia = mediaItem,
                    reviewingMediaItem = mediaItem
                )
            }

            delay(120)
            _uiState.update { it.copy(flashFeedback = false) }

            // Tracking stops only when the final output frame is completed/saved
            unlockTracking()
        }
    }

    private fun toggleVideoRecording(context: Context) {
        if (_uiState.value.isRecording) {
            stopVideoRecording(context)
        } else {
            startVideoRecording(context)
        }
    }

    private fun startVideoRecording(context: Context) {
        val recordingDir = context.externalCacheDir ?: context.cacheDir
        recordingDir.mkdirs()
        val tempVideoFile = File(recordingDir, "temp_rec_${System.currentTimeMillis()}.mp4")
        val success = videoRecorder.start(
            destinationFile = tempVideoFile,
            resolution = _uiState.value.videoResolution,
            targetAspect = _uiState.value.aspectRatio.ratioValue
        )

        if (success) {
            com.example.camera.sound.CameraSoundManager.playStartVideo()
            _uiState.update { it.copy(isRecording = true, recordingDurationSec = 0) }
            recordingTimerJob = viewModelScope.launch {
                var sec = 0
                while (isActive && _uiState.value.isRecording) {
                    delay(1000)
                    sec++
                    _uiState.update { it.copy(recordingDurationSec = sec) }
                }
            }
        }
    }

    private fun stopVideoRecording(context: Context) {
        com.example.camera.sound.CameraSoundManager.playStopVideo()
        recordingTimerJob?.cancel()
        val recordedFile = videoRecorder.stop()
        _uiState.update { it.copy(isRecording = false, recordingDurationSec = 0) }

        if (recordedFile != null && recordedFile.exists()) {
            viewModelScope.launch(Dispatchers.IO) {
                // Automatically save to Gallery (DCIM/Camera3X)
                val (savedUri, thumbnail, filePath) = MediaStorageHelper.saveVideoToGallery(
                    context = context,
                    sourceVideoFile = recordedFile,
                    resolutionLabel = _uiState.value.videoResolution.label
                )

                val mediaItem = CapturedMediaItem(
                    uri = savedUri ?: Uri.fromFile(recordedFile),
                    galleryUri = savedUri,
                    filePath = filePath,
                    isVideo = true,
                    thumbnailBitmap = thumbnail,
                    aspectRatioLabel = _uiState.value.aspectRatio.label,
                    resolutionLabel = _uiState.value.videoResolution.label,
                    subjectLabel = _uiState.value.activeSubject?.label
                )

                _uiState.update {
                    it.copy(
                        lastCapturedMedia = mediaItem,
                        reviewingMediaItem = mediaItem
                    )
                }

                // Tracking stops only when the final output frame is completed/saved
                unlockTracking()
            }
        }
    }

    fun setCaptureMode(mode: CameraCaptureMode) {
        _uiState.update { it.copy(captureMode = mode) }
    }

    fun setAspectRatio(ratio: TrackingAspectRatio) {
        _uiState.update { it.copy(aspectRatio = ratio) }
    }

    fun cycleAspectRatio() {
        val values = TrackingAspectRatio.values()
        val nextIdx = (values.indexOf(_uiState.value.aspectRatio) + 1) % values.size
        setAspectRatio(values[nextIdx])
    }

    fun setTrackingIntensity(intensity: Float) {
        cropController.trackingIntensity = intensity
        subjectTracker.trackingSpeedIntensity = intensity
        _uiState.update { it.copy(trackingIntensity = intensity) }
    }

    fun setVideoResolution(res: VideoResolution) {
        _uiState.update { it.copy(videoResolution = res) }
    }

    fun setViewfinderResolution(res: ViewfinderResolution) {
        _uiState.update { it.copy(viewfinderResolution = res) }
        cameraXManager?.setViewfinderResolution(res.width, res.height)
    }

    /**
     * Sets AI tracking input resolution: 720p (Max 30 FPS Performance) vs 1080p (High Detail Tracking).
     * Camera preview resolution remains completely unchanged.
     */
    fun setTrackingResolution(res: TrackingResolution) {
        resolutionNoticeJob?.cancel()
        _uiState.update { it.copy(trackingResolution = res, trackingResolutionNotice = null) }
        cameraXManager?.setTrackingResolution(res)
        lastManualResolutionChangeTimeMs = SystemClock.uptimeMillis()
        lowFpsWindowCount = 0
        Log.d(TAG, "Tracking input resolution selected: ${res.label}")
    }

    fun toggleTrackingResolution() {
        val current = _uiState.value.trackingResolution
        val next = if (current == TrackingResolution.HD_720P) TrackingResolution.FHD_1080P else TrackingResolution.HD_720P
        setTrackingResolution(next)
    }

    fun dismissTrackingResolutionNotice() {
        resolutionNoticeJob?.cancel()
        _uiState.update { it.copy(trackingResolutionNotice = null) }
    }

    private fun triggerAutoSwitchTo720p() {
        Log.w(TAG, "Performance notice: 1080p AI tracking dropped below 26 FPS. Auto-switching to 720p to maintain smooth 30 FPS.")
        lowFpsWindowCount = 0
        _uiState.update {
            it.copy(
                trackingResolution = TrackingResolution.HD_720P,
                trackingResolutionNotice = "⚡ Auto-switched AI tracking to 720p to maintain 30 FPS"
            )
        }
        cameraXManager?.setTrackingResolution(TrackingResolution.HD_720P)

        resolutionNoticeJob?.cancel()
        resolutionNoticeJob = viewModelScope.launch {
            delay(3500)
            _uiState.update { it.copy(trackingResolutionNotice = null) }
        }
    }

    fun toggleGimbal(enabled: Boolean) {
        digitalGimbalEngine.isEnabled = enabled
        _uiState.update { it.copy(isGimbalEnabled = enabled) }
    }

    fun setGimbalSensitivity(sens: Float) {
        digitalGimbalEngine.sensitivity = sens
        _uiState.update { it.copy(gimbalSensitivity = sens) }
    }

    fun startCinematicPan(durationSec: Float) {
        cropController.startCinematicPan(durationSec = durationSec, leftToRight = true) {
            _uiState.update { it.copy(isCinematicPanActive = false, cinematicPanProgress = 0f) }
        }
        _uiState.update {
            it.copy(
                isCinematicPanActive = true,
                cinematicPanDurationSec = durationSec,
                cinematicPanProgress = 0f
            )
        }
    }

    fun stopCinematicPan() {
        cropController.stopCinematicPan()
        _uiState.update { it.copy(isCinematicPanActive = false, cinematicPanProgress = 0f) }
    }

    fun flipCamera() {
        val nextLens = if (_uiState.value.selectedLens.isFront) {
            val saved = preferences.trackingLens
            if (saved == TrackingCameraLens.ULTRAWIDE && _uiState.value.availableLenses.contains(TrackingCameraLens.ULTRAWIDE)) {
                TrackingCameraLens.ULTRAWIDE
            } else {
                TrackingCameraLens.WIDE
            }
        } else {
            TrackingCameraLens.FRONT
        }
        setCameraLens(nextLens)
    }

    fun setCameraLens(lens: TrackingCameraLens) {
        if (!_uiState.value.availableLenses.contains(lens)) {
            android.util.Log.w("CameraTrackingViewModel", "Requested lens $lens is not available on this device")
            return
        }
        preferences.trackingLens = lens
        // Smoothly unlock tracking to cleanly reorient tracking and crop bounds to new physical sensor
        unlockTracking()
        _uiState.update { it.copy(selectedLens = lens, isFrontCamera = lens.isFront) }
        cameraXManager?.setLens(lens)
    }

    fun setTrackingFps(fpsOption: TrackingFpsOption) {
        _uiState.update { it.copy(selectedFpsOption = fpsOption) }
        cameraXManager?.setFps(fpsOption)
        startSmoothingLoop()
    }

    fun clearLearnedSubjects() {
        adaptiveLearner?.clearLearnedProfiles()
        _uiState.update { it.copy(learnedSubjectsCount = 0) }
    }

    fun openMediaReview(item: CapturedMediaItem) {
        _uiState.update { it.copy(reviewingMediaItem = item) }
    }

    fun closeMediaReview() {
        _uiState.update { it.copy(reviewingMediaItem = null) }
    }

    fun setSettingsOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isSettingsOpen = isOpen) }
    }

    override fun onCleared() {
        super.onCleared()
        smoothingLoopJob?.cancel()
        recordingTimerJob?.cancel()
        subjectTracker.close()
        digitalGimbalEngine.stop()
        cameraXManager?.release()
    }
}
