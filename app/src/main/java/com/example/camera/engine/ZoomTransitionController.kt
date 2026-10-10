package com.example.camera.engine

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

            val minZoom = if (ultraWideLens != null) 0.5f else 1.0f
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
                setSteadyStatePreview(endZ < switchZoom)
                _isTransitionActive.value = false
                onComplete(endZ, targetLens)
                return@launch
            }

            _isTransitionActive.value = true

            val isHalfXToOneX = (currentLens?.lensType == LensType.ULTRAWIDE || startZ <= 0.65f) &&
                endZ in 0.95f..1.05f &&
                (targetLens?.lensType == LensType.WIDE || targetLens?.isPrimaryMain == true || targetLens == null) &&
                ultraWideLens != null && mainWideLens != null

            if (isHalfXToOneX) {
                // 1. Keep view on Ultra-Wide lens; do NOT switch to Main lens immediately
                setSteadyStatePreview(true)
                val startNorm = zoomToNormalizedLog(startZ, minZoom, maxZoom)
                val targetNorm = zoomToNormalizedLog(1.0f, minZoom, maxZoom)

                val startTime = SystemClock.uptimeMillis()
                var lastDispatchedZoom = startZ
                var lastDispatchedTime = 0L

                // 2. Smoothly apply digital cropping on Ultra-Wide lens from 0.5x to 1x
                while (isActive) {
                    val now = SystemClock.uptimeMillis()
                    val elapsed = now - startTime
                    if (elapsed >= transitionDurationMs) break

                    val linearProgress = (elapsed.toDouble() / transitionDurationMs.toDouble()).coerceIn(0.0, 1.0)
                    val smoothProgress = (0.5 * (1.0 - cos(PI * linearProgress))).coerceIn(0.0, 1.0)
                    val currentNorm = (startNorm + (targetNorm - startNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)

                    val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                    val currentZ = ((rawZoom * 100f).roundToInt() / 100f).coerceIn(minZoom, 1.0f)

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

                val totalElapsed = SystemClock.uptimeMillis() - startTime
                if (totalElapsed < transitionDurationMs) {
                    delay(transitionDurationMs - totalElapsed)
                }

                // Ensure Ultra-Wide reaches the exact 1x field of view
                _currentInterpolatedZoom.value = 1.0f
                onZoomUpdate(1.0f)
                onApplyZoomToEngine(1.0f, true)

                // 3. Once Ultra-Wide reaches exact 1x field of view, hold that view for the selected hold duration
                val holdDuration = holdAtOneXDurationMs.coerceIn(MIN_HOLD_AT_ONE_X_DURATION_MS, MAX_HOLD_AT_ONE_X_DURATION_MS)
                delay(holdDuration)

                // 4. After the 100 ms hold, switch instantly to physical 1x Main lens with zero intentional delay
                onSelectLensOnEngine(mainWideLens, 1.0f, false)
                setSteadyStatePreview(false)
                _isTransitionActive.value = false
                activeTransitionJob = null

                onComplete(1.0f, mainWideLens)
                return@launch
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

            val switchZoom = CameraOpticalCalibration.switchPointToZoom(switchPointMm)
            setSteadyStatePreview(endZ < switchZoom)
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
