package com.example.camera.engine

import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Log
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import com.example.camera.ui.components.normalizedToZoomLog
import com.example.camera.ui.components.zoomToNormalizedLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Preview layer state preserved for compatibility without overlap crossfade animation.
 */
data class PreviewOverlapState(
    val isOverlapping: Boolean = false,
    val mainAlpha: Float = 1.0f,
    val ultraWideAlpha: Float = 0.0f,
    val activeSource: PreviewStreamSource = PreviewStreamSource.MAIN,
    val overlapProgress: Float = 0.0f,
    val topSource: PreviewStreamSource = PreviewStreamSource.MAIN
)

/**
 * State machine events for the smooth zoom transition and lens handoff.
 */
enum class ZoomTransitionState {
    IDLE,
    ZOOMING_UW_TO_SWITCH_POINT, // Ultra-wide digitally cropping smoothly up to 1.0x (switch point)
    HOLDING_AT_SWITCH_POINT,    // Holding at exact 1.0x switch point FOV if configured
    SWITCHED_TO_MAIN,           // Instantly switched to Main lens after zoom transition completion
    ZOOMING_MAIN_TO_TARGET,     // Main lens smoothly continuing zoom to target (if > 1.0x) or descending to 1.0x
    SWITCHED_TO_UW_CROPPED,     // Instantly switched to Ultra-Wide at 1.0x-equivalent crop
    HOLDING_AT_CROPPED_UW,      // Holding at 1.0x-equivalent crop on Ultra-Wide if configured
    ZOOMING_UW_OUT_TO_TARGET    // Ultra-wide smoothly transitioning digital zoom down to 0.5x
}

/**
 * Unified Background Zoom Slider Controller.
 *
 * Replaces the custom zoom transition and separate animation systems with the actual
 * zoom slider logic running programmatically in the background:
 * 1. Fixed duration of EXACTLY 300ms (0.30 seconds) for all continuous background slider movements.
 * 2. Uses the actual zoom slider logarithmic mapping (from HorizontalRulerZoomSlider) to smoothly
 *    interpolate from startZoom to targetZoom.
 * 3. Reuses the existing camera slider functionality (via onApplyZoomToEngine) which directly applies
 *    calibrated zoom and switches physical lenses cleanly without tearing down the camera session.
 * 4. Fully supports Ultra-Wide -> 1x and 1x -> Ultra-Wide without requiring user interaction or visibly
 *    moving the UI slider.
 * 5. Instant lens switching, standby camera readiness, and stream stability are strictly preserved.
 */
class ZoomTransitionController(
    private val onApplyZoomToEngine: (zoom: Float, isContinuous: Boolean) -> Unit = { _, _ -> },
    private val onSelectLensOnEngine: (lens: LensInfo, targetZoom: Float, isContinuous: Boolean) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val TAG = "ZoomTransitionCtrl"
        const val TOTAL_TRANSITION_DURATION_MS = 300L // Fixed duration of exactly 300ms (0.30s), never less
        const val DEFAULT_HOLD_AT_ONE_X_DURATION_MS = 100L // 0.10 second default hold at exact 1x FOV
        const val HOLD_AT_ONE_X_DURATION_MS = 100L // Preserved for backwards compatibility
        const val DEFAULT_OVERLAP_DURATION_MS = 300L
        const val PREVIEW_OVERLAP_DURATION_MS = 100L
        const val MIN_OVERLAP_DURATION_MS = 100L
        const val MAX_OVERLAP_DURATION_MS = 1000L
    }

    var overlapDurationMs: Long = DEFAULT_OVERLAP_DURATION_MS
    var holdAtOneXDurationMs: Long = DEFAULT_HOLD_AT_ONE_X_DURATION_MS
    var isOverlapAnimationEnabled: Boolean = false

    var onAwaitPreviewFrame: (suspend (targetZoom: Float) -> Unit)? = null
    var onAwaitLensSwitch: (suspend (targetLens: LensInfo, targetZoom: Float) -> Unit)? = null
    var isStandbyStreamReady: (() -> Boolean)? = null
    var getActiveCameraId: (() -> String?)? = null
    var getSessionStateDescription: (() -> String)? = null

    private fun logDiag(event: String, details: String) {
        val now = SystemClock.uptimeMillis()
        val cam = getActiveCameraId?.invoke() ?: "unknown"
        val sess = getSessionStateDescription?.invoke() ?: ""
        Log.i(TAG, "[ZOOM_DIAG] [$now] [$event] cam=$cam details={$details} sessionState=[$sess]")
    }

    private val _isTransitionActive = MutableStateFlow(false)
    val isTransitionActive: StateFlow<Boolean> = _isTransitionActive.asStateFlow()

    private val _transitionState = MutableStateFlow(ZoomTransitionState.IDLE)
    val transitionState: StateFlow<ZoomTransitionState> = _transitionState.asStateFlow()

    var onTransitionStateChanged: ((ZoomTransitionState) -> Unit)? = null

    private fun updateState(newState: ZoomTransitionState) {
        _transitionState.value = newState
        logDiag("STATE_CHANGED", "newState=$newState")
        onTransitionStateChanged?.invoke(newState)
    }

    private val _currentInterpolatedZoom = MutableStateFlow(1.0f)
    val currentInterpolatedZoom: StateFlow<Float> = _currentInterpolatedZoom.asStateFlow()

    private val _previewOverlapState = MutableStateFlow(PreviewOverlapState())
    val previewOverlapState: StateFlow<PreviewOverlapState> = _previewOverlapState.asStateFlow()

    private var activeTransitionJob: Job? = null
    private val transitionGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Slider normalization helper methods reusing the actual HorizontalRulerZoomSlider mapping.
     */
    fun zoomToNormalizedSliderProgress(zoom: Float, minZoom: Float = 0.5f, maxZoom: Float = 20.0f): Float =
        zoomToNormalizedLog(zoom, minZoom, maxZoom)

    fun normalizedSliderProgressToZoom(progress: Float, minZoom: Float = 0.5f, maxZoom: Float = 20.0f): Float =
        normalizedToZoomLog(progress, minZoom, maxZoom)

    /**
     * Generates an ordered list of all consecutive 0.01x zoom steps from [startZoom] to [endZoom].
     * Preserved as a utility for discrete step analysis.
     */
    fun generateContinuousZoomSteps(startZoom: Float, endZoom: Float): List<Float> {
        val startZ = ((startZoom * 100f).roundToInt() / 100f)
        val endZ = ((endZoom * 100f).roundToInt() / 100f)
        val stepCount = max(1, (abs(endZ - startZ) * 100f).roundToInt())
        val steps = ArrayList<Float>(stepCount + 1)
        for (stepIndex in 0..stepCount) {
            val t = stepIndex.toFloat() / stepCount.toFloat()
            val interpolated = startZ + (endZ - startZ) * t
            val rounded = (interpolated * 100f).roundToInt() / 100f
            steps.add(rounded)
        }
        return steps
    }

    /**
     * Calculates sinusoidal elapsed timestamps over [durationMs].
     */
    fun calculateSinusoidalElapsedTimestamps(
        stepCount: Int,
        durationMs: Long = TOTAL_TRANSITION_DURATION_MS
    ): LongArray {
        val targetElapsedMs = LongArray(stepCount + 1)
        for (stepIndex in 0..stepCount) {
            val progress = (stepIndex.toDouble() / stepCount.toDouble()).coerceIn(0.0, 1.0)
            val cosVal = (1.0 - 2.0 * progress).coerceIn(-1.0, 1.0)
            val elapsed = (durationMs.toDouble() / PI) * acos(cosVal)
            targetElapsedMs[stepIndex] = (elapsed + 0.5).toLong()
        }
        return targetElapsedMs
    }

    fun setSteadyStatePreview(isUltraWide: Boolean) {
        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = false,
            mainAlpha = if (isUltraWide) 0.0f else 1.0f,
            ultraWideAlpha = if (isUltraWide) 1.0f else 0.0f,
            activeSource = if (isUltraWide) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN,
            overlapProgress = 0.0f,
            topSource = if (isUltraWide) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
        )
    }

    fun startPreviewOverlap(
        fromLensType: LensType,
        toLensType: LensType,
        scope: CoroutineScope,
        durationMs: Long = overlapDurationMs,
        onComplete: (() -> Unit)? = null
    ): Job {
        setSteadyStatePreview(toLensType == LensType.ULTRAWIDE)
        onComplete?.invoke()
        return scope.launch(Dispatchers.Main.immediate) {}
    }

    /**
     * Executes the zoom transition by running the actual zoom slider logic in the background:
     * - Runs over exactly 0.3 seconds (300ms), never less.
     * - Uses the zoom slider's normalized logarithmic scale from [HorizontalRulerZoomSlider].
     * - Reuses the existing camera slider functionality without requiring user interaction or visibly
     *   moving the UI slider.
     * - Applies actual zoom values through the camera controller, seamlessly performing physical lens
     *   switching (both Ultra-Wide -> 1x and 1x -> Ultra-Wide) via the camera engine.
     */
    fun startTransition(
        fromZoom: Float,
        targetZoom: Float,
        targetLens: LensInfo?,
        availableLenses: List<LensInfo>,
        currentLens: LensInfo?,
        switchPointMm: Float,
        scope: CoroutineScope,
        durationMs: Long = TOTAL_TRANSITION_DURATION_MS,
        onZoomUpdate: (Float) -> Unit,
        onComplete: (Float, LensInfo?) -> Unit
    ): Job {
        cancelTransition()
        val currentGen = transitionGeneration.incrementAndGet()

        // Use specified duration if positive, defaulting to TOTAL_TRANSITION_DURATION_MS (300ms)
        val transitionDurationMs = if (durationMs > 0L) durationMs else TOTAL_TRANSITION_DURATION_MS
        logDiag("TRANSITION_START", "fromZoom=$fromZoom targetZoom=$targetZoom targetLens=${targetLens?.lensType} currentLens=${currentLens?.lensType} durationMs=$transitionDurationMs")

        val job = scope.launch(Dispatchers.Main.immediate) {
            if (currentGen != transitionGeneration.get() || !isActive) return@launch
            val currentFacing = currentLens?.facing
            val lensesForFacing = availableLenses.filter { currentFacing == null || it.facing == currentFacing }
            val ultraWideLens = lensesForFacing.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                ?: lensesForFacing.firstOrNull { it.lensType == LensType.ULTRAWIDE }
            val mainWideLens = lensesForFacing.firstOrNull { it.isPrimaryMain }
                ?: lensesForFacing.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                ?: lensesForFacing.firstOrNull { it.lensType == LensType.WIDE }
                ?: lensesForFacing.firstOrNull()

            val isFrontCamera = currentFacing == CameraCharacteristics.LENS_FACING_FRONT
            val minZoom = if (isFrontCamera) 0.8f else if (ultraWideLens != null) 0.5f else 1.0f
            val maxLensZoom = lensesForFacing.maxOfOrNull { it.maxZoomRatio } ?: 20.0f
            val maxZoom = maxOf(maxLensZoom, 20.0f)

            val startZ = ((fromZoom * 100f).roundToInt() / 100f).coerceIn(minZoom, maxZoom)
            val endZ = ((targetZoom * 100f).roundToInt() / 100f).coerceIn(minZoom, maxZoom)

            // If start and target are practically identical, finish immediately without delay
            if (abs(endZ - startZ) < 0.005f) {
                _currentInterpolatedZoom.value = endZ
                onZoomUpdate(endZ)
                onApplyZoomToEngine(endZ, false)
                val switchZoom = CameraOpticalCalibration.switchPointToZoom(switchPointMm)
                setSteadyStatePreview(!isFrontCamera && endZ < switchZoom)
                _isTransitionActive.value = false
                logDiag("TRANSITION_COMPLETE", "identical zoom=$endZ")
                onComplete(endZ, targetLens)
                return@launch
            }

            _isTransitionActive.value = true

            val switchZoom = CameraOpticalCalibration.switchPointToZoom(switchPointMm)

            // Determine if this transition is switching from Ultra-Wide to Main lens across the switch point or to 1x
            val isStartingOnUltraWide = (currentLens?.lensType == LensType.ULTRAWIDE || startZ < switchZoom)
            val isTappingOneX = abs(endZ - 1.0f) < 0.05f
            val isCrossingOrAtSwitchPoint = (endZ >= switchZoom - 0.05f) || isTappingOneX
            val effectiveSwitchZoom = if (isTappingOneX && switchZoom > 1.0f) 1.0f else switchZoom
            val isUltraWideToMainSwitch = !isFrontCamera && isStartingOnUltraWide &&
                isCrossingOrAtSwitchPoint &&
                ultraWideLens != null && mainWideLens != null &&
                (targetLens == null || targetLens.isPrimaryMain || targetLens.lensType == LensType.WIDE || targetLens.lensType == LensType.TELEPHOTO || targetLens.lensType == LensType.TELEPHOTO_3X)

            // Determine if this transition is switching from Main lens down to Ultra-Wide (e.g. 1x -> 0.5x)
            val isStartingOnMain = !isStartingOnUltraWide
            val isCrossingDownToUltraWide = (endZ < switchZoom) || (abs(endZ - 0.5f) < 0.05f) || (targetLens?.lensType == LensType.ULTRAWIDE)
            val isMainToUltraWideSwitch = !isFrontCamera && isStartingOnMain &&
                isCrossingDownToUltraWide &&
                ultraWideLens != null && mainWideLens != null &&
                (targetLens == null || targetLens.lensType == LensType.ULTRAWIDE)

            if (isUltraWideToMainSwitch) {
                // 1. 0.5x -> 1x workflow:
                // - Start with the ultra-wide lens active.
                // - Smoothly apply digital cropping from 0.5x up to 1x using the ultra-wide camera feed.
                // - Apply final transition zoom, wait for preview frame confirmation.
                // - Start configured hold timer only after that frame is available.
                // - Trigger physical lens switching to Main lens only after entire hold duration has elapsed.
                // - Coordinate completion with lens-switch callback, avoiding switching races.
                logDiag("FLOW_05_TO_1", "startZ=$startZ endZ=$endZ effectiveSwitchZoom=$effectiveSwitchZoom")
                updateState(ZoomTransitionState.ZOOMING_UW_TO_SWITCH_POINT)

                setSteadyStatePreview(true)
                if (currentLens?.id != ultraWideLens.id) {
                    onSelectLensOnEngine(ultraWideLens, startZ, true)
                    onAwaitLensSwitch?.invoke(ultraWideLens, startZ)
                }

                val startNorm = zoomToNormalizedLog(startZ, minZoom, maxZoom)
                val switchNorm = zoomToNormalizedLog(effectiveSwitchZoom, minZoom, maxZoom)
                val endNorm = zoomToNormalizedLog(endZ, minZoom, maxZoom)

                val isTargetNearSwitch = abs(endZ - effectiveSwitchZoom) <= 0.08f || endZ <= effectiveSwitchZoom + 0.05f

                // Allocate duration proportionally if transitioning further beyond switch point
                val uwPhaseDurationMs = if (isTargetNearSwitch) {
                    transitionDurationMs
                } else {
                    val totalSpan = abs(endNorm - startNorm).coerceAtLeast(0.001f)
                    val uwSpan = abs(switchNorm - startNorm)
                    ((uwSpan / totalSpan) * transitionDurationMs.toFloat()).toLong().coerceIn(60L, transitionDurationMs)
                }

                val startTime = SystemClock.uptimeMillis()
                var lastDispatchedZoom = startZ
                var lastDispatchedTime = 0L

                // Smoothly apply digital cropping on Ultra-Wide lens up to 1x (effectiveSwitchZoom) with sinusoidal ease
                while (isActive) {
                    val now = SystemClock.uptimeMillis()
                    val elapsed = now - startTime
                    if (elapsed >= uwPhaseDurationMs) break

                    val linearProgress = (elapsed.toDouble() / uwPhaseDurationMs.toDouble()).coerceIn(0.0, 1.0)
                    val smoothProgress = (0.5 * (1.0 - cos(PI * linearProgress))).coerceIn(0.0, 1.0)
                    val currentNorm = (startNorm + (switchNorm - startNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)

                    val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                    val currentZ = ((rawZoom * 100f).roundToInt() / 100f).coerceIn(minZoom, effectiveSwitchZoom)

                    if (currentZ < effectiveSwitchZoom && (abs(currentZ - lastDispatchedZoom) >= 0.005f || (now - lastDispatchedTime) >= 16L)) {
                        lastDispatchedZoom = currentZ
                        lastDispatchedTime = now
                        _currentInterpolatedZoom.value = currentZ
                        onZoomUpdate(currentZ)
                        onApplyZoomToEngine(currentZ, true)
                    }

                    val nextTick = minOf(startTime + uwPhaseDurationMs, now + 16L)
                    val waitMs = nextTick - SystemClock.uptimeMillis()
                    if (waitMs > 0) {
                        delay(waitMs)
                    }
                }

                // Ensure Ultra-Wide reaches the exact 1x field of view
                _currentInterpolatedZoom.value = effectiveSwitchZoom
                onZoomUpdate(effectiveSwitchZoom)
                onApplyZoomToEngine(effectiveSwitchZoom, true)

                // Wait for confirmation that the preview frame has actually reached the active preview pipeline
                logDiag("AWAIT_FRAME", "zoom=$effectiveSwitchZoom on Ultra-Wide before hold")
                onAwaitPreviewFrame?.invoke(effectiveSwitchZoom)
                logDiag("FRAME_CONFIRMED", "zoom=$effectiveSwitchZoom reached active preview pipeline")

                // Start the configured hold timer ONLY AFTER that frame is available
                val holdDuration = maxOf(0L, holdAtOneXDurationMs)
                if (holdDuration > 0L) {
                    updateState(ZoomTransitionState.HOLDING_AT_SWITCH_POINT)
                    logDiag("HOLD_START", "durationMs=$holdDuration at switch point")
                    val holdStartTime = SystemClock.uptimeMillis()
                    while (isActive && currentGen == transitionGeneration.get()) {
                        val now = SystemClock.uptimeMillis()
                        val elapsedHold = now - holdStartTime
                        if (elapsedHold >= holdDuration) break

                        val remaining = holdStartTime + holdDuration - now
                        if (remaining <= 0) break
                        delay(minOf(remaining, 16L))
                    }
                    logDiag("HOLD_END", "hold duration elapsed")
                }
                if (currentGen != transitionGeneration.get() || !isActive) return@launch

                // Trigger physical lens switching ONLY AFTER the entire hold duration has elapsed
                updateState(ZoomTransitionState.SWITCHED_TO_MAIN)
                logDiag("LENS_SWITCH_START", "switching to ${mainWideLens.lensType} at $effectiveSwitchZoom")
                onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                setSteadyStatePreview(false)
                _currentInterpolatedZoom.value = effectiveSwitchZoom
                onZoomUpdate(effectiveSwitchZoom)

                // Coordinate completion with lens-switch callback and frame confirmation
                onAwaitLensSwitch?.invoke(mainWideLens, effectiveSwitchZoom)
                onAwaitPreviewFrame?.invoke(effectiveSwitchZoom)
                logDiag("LENS_SWITCH_CONFIRMED", "activeLens=${mainWideLens.lensType}")

                if (isTargetNearSwitch) {
                    _currentInterpolatedZoom.value = endZ
                    onZoomUpdate(endZ)
                    onApplyZoomToEngine(endZ, false)
                    onAwaitPreviewFrame?.invoke(endZ)

                    updateState(ZoomTransitionState.IDLE)
                    _isTransitionActive.value = false
                    activeTransitionJob = null

                    logDiag("TRANSITION_COMPLETE", "finalZoom=$endZ lens=${mainWideLens.lensType}")
                    onComplete(endZ, mainWideLens)
                    return@launch
                } else {
                    // Target is beyond switch point: smoothly continue on Main lens from effectiveSwitchZoom to endZ
                    updateState(ZoomTransitionState.ZOOMING_MAIN_TO_TARGET)
                    logDiag("ZOOM_MAIN_TO_TARGET_START", "from=$effectiveSwitchZoom to=$endZ")
                    val mainPhaseDurationMs = (transitionDurationMs - uwPhaseDurationMs).coerceAtLeast(60L)
                    val mainStartTime = SystemClock.uptimeMillis()
                    lastDispatchedZoom = effectiveSwitchZoom
                    lastDispatchedTime = 0L

                    while (isActive) {
                        val now = SystemClock.uptimeMillis()
                        val elapsed = now - mainStartTime
                        if (elapsed >= mainPhaseDurationMs) break

                        val linearProgress = (elapsed.toDouble() / mainPhaseDurationMs.toDouble()).coerceIn(0.0, 1.0)
                        val smoothProgress = (0.5 * (1.0 - cos(PI * linearProgress))).coerceIn(0.0, 1.0)
                        val currentNorm = (switchNorm + (endNorm - switchNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)

                        val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                        val currentZ = ((rawZoom * 100f).roundToInt() / 100f).coerceIn(effectiveSwitchZoom, maxZoom)

                        if (abs(currentZ - lastDispatchedZoom) >= 0.005f || (now - lastDispatchedTime) >= 16L) {
                            lastDispatchedZoom = currentZ
                            lastDispatchedTime = now
                            _currentInterpolatedZoom.value = currentZ
                            onZoomUpdate(currentZ)
                            onApplyZoomToEngine(currentZ, true)
                        }

                        val nextTick = minOf(mainStartTime + mainPhaseDurationMs, now + 16L)
                        val waitMs = nextTick - SystemClock.uptimeMillis()
                        if (waitMs > 0) {
                            delay(waitMs)
                        }
                    }

                    _currentInterpolatedZoom.value = endZ
                    onZoomUpdate(endZ)
                    onApplyZoomToEngine(endZ, false)
                    onAwaitPreviewFrame?.invoke(endZ)

                    updateState(ZoomTransitionState.IDLE)
                    _isTransitionActive.value = false
                    activeTransitionJob = null

                    logDiag("TRANSITION_COMPLETE", "finalZoom=$endZ lens=${targetLens?.lensType ?: mainWideLens.lensType}")
                    onComplete(endZ, targetLens ?: mainWideLens)
                    return@launch
                }
            }

            if (isMainToUltraWideSwitch) {
                // 2. 1x -> 0.5x workflow:
                // - Do NOT assume standalone main camera can digitally zoom below native 1x FOV.
                // - Use prewarmed ultra-wide stream pre-cropped to 1.0x switch point FOV.
                // - Wait for frame confirmation on the ultra-wide stream.
                // - Honour full hold duration at cropped UW.
                // - Smoothly zoom out ultra-wide from 1.0x down to 0.5x.
                // - Safe fallback if prewarmed stream is unsupported: direct switch without fake digital zoom below 1x.
                val canUsePrewarmed = isStandbyStreamReady?.invoke() ?: true
                logDiag("FLOW_1_TO_05", "canUsePrewarmed=$canUsePrewarmed startZ=$startZ endZ=$endZ effectiveSwitchZoom=$effectiveSwitchZoom")

                if (canUsePrewarmed) {
                    // Switch to prewarmed Ultra-Wide pre-cropped at 1.0x switch point FOV
                    updateState(ZoomTransitionState.SWITCHED_TO_UW_CROPPED)
                    logDiag("LENS_SWITCH_START", "switching to Ultra-Wide at crop $effectiveSwitchZoom")
                    onSelectLensOnEngine(ultraWideLens, effectiveSwitchZoom, true)
                    setSteadyStatePreview(true)
                    _currentInterpolatedZoom.value = effectiveSwitchZoom
                    onZoomUpdate(effectiveSwitchZoom)

                    onAwaitLensSwitch?.invoke(ultraWideLens, effectiveSwitchZoom)
                    onAwaitPreviewFrame?.invoke(effectiveSwitchZoom)
                    logDiag("FRAME_CONFIRMED", "zoom=$effectiveSwitchZoom on Ultra-Wide cropped")

                    // Hold at 1.0x-equivalent crop on Ultra-Wide
                    val holdDuration = maxOf(0L, holdAtOneXDurationMs)
                    if (holdDuration > 0L) {
                        updateState(ZoomTransitionState.HOLDING_AT_CROPPED_UW)
                        logDiag("HOLD_START", "holding at cropped UW for $holdDuration ms")
                        val holdStartTime = SystemClock.uptimeMillis()
                        while (isActive && currentGen == transitionGeneration.get()) {
                            val now = SystemClock.uptimeMillis()
                            val elapsedHold = now - holdStartTime
                            if (elapsedHold >= holdDuration) break

                            val remaining = holdStartTime + holdDuration - now
                            if (remaining <= 0) break
                            delay(minOf(remaining, 16L))
                        }
                        logDiag("HOLD_END", "hold complete at cropped UW")
                    }
                    if (currentGen != transitionGeneration.get() || !isActive) return@launch

                    // Smoothly transition digital zoom on Ultra-Wide from 1.0x down to 0.5x
                    updateState(ZoomTransitionState.ZOOMING_UW_OUT_TO_TARGET)
                    logDiag("ZOOM_UW_OUT_START", "zooming UW down from $effectiveSwitchZoom to $endZ durationMs=$transitionDurationMs")

                    val switchNorm = zoomToNormalizedLog(effectiveSwitchZoom, minZoom, maxZoom)
                    val endNorm = zoomToNormalizedLog(endZ, minZoom, maxZoom)

                    val startTime = SystemClock.uptimeMillis()
                    var lastDispatchedZoom = effectiveSwitchZoom
                    var lastDispatchedTime = 0L

                    while (isActive) {
                        val now = SystemClock.uptimeMillis()
                        val elapsed = now - startTime
                        if (elapsed >= transitionDurationMs) break

                        val linearProgress = (elapsed.toDouble() / transitionDurationMs.toDouble()).coerceIn(0.0, 1.0)
                        val smoothProgress = (0.5 * (1.0 - cos(PI * linearProgress))).coerceIn(0.0, 1.0)
                        val currentNorm = (switchNorm + (endNorm - switchNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)

                        val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                        val currentZ = ((rawZoom * 100f).roundToInt() / 100f).coerceIn(endZ, effectiveSwitchZoom)

                        if (abs(currentZ - lastDispatchedZoom) >= 0.005f || (now - lastDispatchedTime) >= 16L) {
                            lastDispatchedZoom = currentZ
                            lastDispatchedTime = now
                            _currentInterpolatedZoom.value = currentZ
                            onZoomUpdate(currentZ)
                            onApplyZoomToEngine(currentZ, true)
                        }

                        val nextTick = minOf(startTime + transitionDurationMs, now + 16L)
                        val waitMs = nextTick - SystemClock.uptimeMillis()
                        if (waitMs > 0) {
                            delay(waitMs)
                        }
                    }

                    // Apply final zoom on Ultra-Wide
                    _currentInterpolatedZoom.value = endZ
                    onZoomUpdate(endZ)
                    onApplyZoomToEngine(endZ, false)
                    onAwaitPreviewFrame?.invoke(endZ)

                    updateState(ZoomTransitionState.IDLE)
                    _isTransitionActive.value = false
                    activeTransitionJob = null

                    logDiag("TRANSITION_COMPLETE", "finalZoom=$endZ lens=${ultraWideLens.lensType}")
                    onComplete(endZ, ultraWideLens)
                    return@launch
                } else {
                    // Safe fallback: direct switch to Ultra-Wide without falsely reporting digital zoom on Main below 1x
                    logDiag("SAFE_FALLBACK_1_TO_05", "switching directly to Ultra-Wide")
                    updateState(ZoomTransitionState.SWITCHED_TO_UW_CROPPED)
                    onSelectLensOnEngine(ultraWideLens, endZ, false)
                    setSteadyStatePreview(true)
                    _currentInterpolatedZoom.value = endZ
                    onZoomUpdate(endZ)

                    onAwaitLensSwitch?.invoke(ultraWideLens, endZ)
                    onApplyZoomToEngine(endZ, false)
                    onAwaitPreviewFrame?.invoke(endZ)

                    updateState(ZoomTransitionState.IDLE)
                    _isTransitionActive.value = false
                    activeTransitionJob = null

                    logDiag("TRANSITION_COMPLETE", "finalZoom=$endZ lens=${ultraWideLens.lensType} (fallback)")
                    onComplete(endZ, ultraWideLens)
                    return@launch
                }
            }

            // Reuse the actual zoom slider logarithmic mapping to anchor start and target positions
            val startNorm = zoomToNormalizedLog(startZ, minZoom, maxZoom)
            val targetNorm = zoomToNormalizedLog(endZ, minZoom, maxZoom)

            val startTime = SystemClock.uptimeMillis()
            var lastDispatchedZoom = startZ
            var lastDispatchedTime = 0L

            // Move the slider in the background from startNorm to targetNorm over exactly 300ms (0.30s)
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val elapsed = now - startTime
                if (elapsed >= transitionDurationMs) break

                // Sinusoidal ease-in-out profile matching smooth finger slider glide
                val linearProgress = (elapsed.toDouble() / transitionDurationMs.toDouble()).coerceIn(0.0, 1.0)
                val smoothProgress = (0.5 * (1.0 - cos(PI * linearProgress))).coerceIn(0.0, 1.0)
                val currentNorm = (startNorm + (targetNorm - startNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)

                // Calculate actual zoom level using the exact zoom slider logic
                val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                val currentZ = ((rawZoom * 100f).roundToInt() / 100f).coerceIn(minZoom, maxZoom)

                // Apply zoom through the camera controller slider handler (at ~60fps pacing)
                if (abs(currentZ - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= 16L) {
                    lastDispatchedZoom = currentZ
                    lastDispatchedTime = now
                    _currentInterpolatedZoom.value = currentZ
                    onZoomUpdate(currentZ)
                    onApplyZoomToEngine(currentZ, true)
                }

                val nextTick = minOf(startTime + transitionDurationMs, now + 16L)
                val waitMs = nextTick - SystemClock.uptimeMillis()
                if (waitMs > 0) {
                    delay(waitMs)
                }
            }

            // Guarantee exact duration of 300ms (0.30s), never less
            val totalElapsed = SystemClock.uptimeMillis() - startTime
            if (totalElapsed < transitionDurationMs) {
                delay(transitionDurationMs - totalElapsed)
            }

            // Apply final target zoom value
            _currentInterpolatedZoom.value = endZ
            onZoomUpdate(endZ)
            onApplyZoomToEngine(endZ, false)
            onAwaitPreviewFrame?.invoke(endZ)

            setSteadyStatePreview(!isFrontCamera && endZ < switchZoom)
            updateState(ZoomTransitionState.IDLE)
            _isTransitionActive.value = false
            activeTransitionJob = null

            logDiag("TRANSITION_COMPLETE", "finalZoom=$endZ targetLens=${targetLens?.lensType ?: currentLens?.lensType}")
            onComplete(endZ, targetLens)
        }

        activeTransitionJob = job
        return job
    }

    /**
     * Cancels any running background slider transition immediately.
     */
    fun cancelTransition() {
        logDiag("TRANSITION_CANCELLED", "activeJob=${activeTransitionJob != null}")
        transitionGeneration.incrementAndGet()
        activeTransitionJob?.cancel()
        activeTransitionJob = null
        _isTransitionActive.value = false
        updateState(ZoomTransitionState.IDLE)
    }
}
