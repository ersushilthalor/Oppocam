package com.example.camera.engine

import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
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
        const val DEFAULT_HOLD_AT_ONE_X_DURATION_MS = 100L // 0.10 second default hold at exact 1x FOV before physical switch
        const val HOLD_AT_ONE_X_DURATION_MS = 100L // Preserved for backwards compatibility
        const val MIN_HOLD_AT_ONE_X_DURATION_MS = 50L
        const val MAX_HOLD_AT_ONE_X_DURATION_MS = 300L
        const val DEFAULT_OVERLAP_DURATION_MS = 300L
        const val PREVIEW_OVERLAP_DURATION_MS = 100L
        const val MIN_OVERLAP_DURATION_MS = 100L
        const val MAX_OVERLAP_DURATION_MS = 1000L
    }

    var overlapDurationMs: Long = DEFAULT_OVERLAP_DURATION_MS
    var holdAtOneXDurationMs: Long = DEFAULT_HOLD_AT_ONE_X_DURATION_MS
    var isOverlapAnimationEnabled: Boolean = false

    private val _isTransitionActive = MutableStateFlow(false)
    val isTransitionActive: StateFlow<Boolean> = _isTransitionActive.asStateFlow()

    private val _currentInterpolatedZoom = MutableStateFlow(1.0f)
    val currentInterpolatedZoom: StateFlow<Float> = _currentInterpolatedZoom.asStateFlow()

    private val _previewOverlapState = MutableStateFlow(PreviewOverlapState())
    val previewOverlapState: StateFlow<PreviewOverlapState> = _previewOverlapState.asStateFlow()

    private var activeTransitionJob: Job? = null

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
        skipHoldAtSwitchPoint: Boolean = false,
        onZoomUpdate: (Float) -> Unit,
        onComplete: (Float, LensInfo?) -> Unit
    ): Job {
        cancelTransition()

        // Fixed duration: must take exactly 300ms (0.30 seconds), never less
        val transitionDurationMs = maxOf(TOTAL_TRANSITION_DURATION_MS, durationMs)

        val job = scope.launch(Dispatchers.Main.immediate) {
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

            if (isUltraWideToMainSwitch) {
                // 1. Keep view on Ultra-Wide lens; digitally crop Ultra-Wide to the selected focal length (effectiveSwitchZoom)
                setSteadyStatePreview(true)
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

                // Smoothly apply digital cropping on Ultra-Wide lens up to the exact switch point
                while (isActive) {
                    val now = SystemClock.uptimeMillis()
                    val elapsed = now - startTime
                    if (elapsed >= uwPhaseDurationMs) break

                    val linearProgress = (elapsed.toDouble() / uwPhaseDurationMs.toDouble()).coerceIn(0.0, 1.0)
                    val smoothProgress = (0.5 * (1.0 - cos(PI * linearProgress))).coerceIn(0.0, 1.0)
                    val currentNorm = (startNorm + (switchNorm - startNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)

                    val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                    val currentZ = ((rawZoom * 100f).roundToInt() / 100f).coerceIn(minZoom, effectiveSwitchZoom)

                    if (abs(currentZ - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= 16L) {
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

                val totalElapsedUw = SystemClock.uptimeMillis() - startTime
                if (totalElapsedUw < uwPhaseDurationMs) {
                    delay(uwPhaseDurationMs - totalElapsedUw)
                }

                // Ensure Ultra-Wide reaches the exact selected focal length field of view
                _currentInterpolatedZoom.value = effectiveSwitchZoom
                onZoomUpdate(effectiveSwitchZoom)
                onApplyZoomToEngine(effectiveSwitchZoom, true)

                // 2. Hold that exact FOV for the same duration as the existing 1x switch hold time
                // Direct 2x/3x preset taps should cross the lens switch without pausing at 1x.
                // Keep the configured hold unchanged for 1x taps and all other transitions.
                // Direct 2x/3x telephoto preset taps must never pause at the lens handoff,
                // even if the caller forgets the skip flag. A deliberate 1x tap keeps its hold.
                val isDirectTelephotoTarget = endZ >= 1.5f
                val holdDuration = if (skipHoldAtSwitchPoint || isDirectTelephotoTarget) {
                    0L
                } else {
                    holdAtOneXDurationMs.coerceIn(MIN_HOLD_AT_ONE_X_DURATION_MS, MAX_HOLD_AT_ONE_X_DURATION_MS)
                }

                val mainLensPreparedInOverlap = isOverlapAnimationEnabled && (holdDuration > 0L || isDirectTelephotoTarget)
                if (mainLensPreparedInOverlap) {
                    // Prepare the main lens while the Ultra-Wide layer remains visible. For direct
                    // telephoto taps use a short crossfade instead of a dead stop at the handoff.
                    onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)

                    if (holdDuration > 0L) {
                        val blendDurationMs = holdDuration
                        val blendStartTime = SystemClock.uptimeMillis()
                        while (isActive) {
                            val now = SystemClock.uptimeMillis()
                            val blendElapsed = now - blendStartTime
                            if (blendElapsed >= blendDurationMs) break
    
                            val progress = (blendElapsed.toFloat() / blendDurationMs.toFloat()).coerceIn(0f, 1f)
                            val smoothProgress = (0.5f * (1.0f - cos(PI * progress.toDouble()))).toFloat().coerceIn(0f, 1f)
                            val uwAlpha = (1.0f - smoothProgress).coerceIn(0f, 1f)
                            val mainAlpha = 1.0f
    
                            _previewOverlapState.value = PreviewOverlapState(
                                isOverlapping = true,
                                mainAlpha = mainAlpha,
                                ultraWideAlpha = uwAlpha,
                                activeSource = PreviewStreamSource.MAIN,
                                overlapProgress = progress,
                                topSource = PreviewStreamSource.ULTRAWIDE
                            )
    
                            val nextTick = minOf(blendStartTime + blendDurationMs, now + 16L)
                            val waitMs = nextTick - SystemClock.uptimeMillis()
                            if (waitMs > 0) {
                                delay(waitMs)
                            }
                        }
                    }
                } else {
                    // Exact hold duration applied at selected switch point FOV
                    delay(holdDuration)
                }

                // 3. Finish the handoff. If overlap already activated the main lens, do not
                // activate it a second time (that duplicate request causes the brief freeze).
                if (!mainLensPreparedInOverlap) {
                    onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, false)
                }
                setSteadyStatePreview(false)

                if (isTargetNearSwitch) {
                    _currentInterpolatedZoom.value = endZ
                    onZoomUpdate(endZ)
                    onApplyZoomToEngine(endZ, false)
                    _isTransitionActive.value = false
                    activeTransitionJob = null

                    onComplete(endZ, mainWideLens)
                    return@launch
                } else {
                    // Target is beyond switch point: smoothly continue on Main lens from effectiveSwitchZoom to endZ
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

                        if (abs(currentZ - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= 16L) {
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
                    _isTransitionActive.value = false
                    activeTransitionJob = null

                    onComplete(endZ, targetLens ?: mainWideLens)
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
                    // The camera slider handler automatically executes physical lens switching when crossing switch points
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

            // Apply final target zoom value maintaining continuous transition
            _currentInterpolatedZoom.value = endZ
            onZoomUpdate(endZ)
            onApplyZoomToEngine(endZ, true)

            setSteadyStatePreview(!isFrontCamera && endZ < switchZoom)
            _isTransitionActive.value = false
            activeTransitionJob = null

            onComplete(endZ, targetLens)
        }

        activeTransitionJob = job
        return job
    }

    /**
     * Cancels any running background slider transition immediately.
     */
    fun cancelTransition() {
        activeTransitionJob?.cancel()
        activeTransitionJob = null
        _isTransitionActive.value = false
    }
}
