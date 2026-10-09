package com.example.camera.engine

import android.os.SystemClock
import android.util.Log
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
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
 * Unified Zoom Transition Controller.
 *
 * Implements:
 * 1. Fixed duration of EXACTLY 300ms (0.30 seconds), never less, for all continuous zoom transitions.
 * 2. Complete zoom transition:
 *    - From 0.5x to 1x: smoothly covers the entire range from 0.5x to 0.99x on Ultra-Wide, then switches to the 1x lens.
 *      Does not stop at 0.9x and switch directly to 1x.
 *    - In reverse (1x to 0.5x): applies the same continuous transition in reverse from 1x down to 0.5x (covering 0.99x to 0.50x).
 * 3. Overlap animation completely removed:
 *    No crossfading alpha between preview layers; transitions render continuously through the existing preview pipeline.
 * 4. Preset buttons cannot bypass the transition:
 *    Tapping 1x or 0.5x executes the smooth 300ms transition instead of jumping or direct lens switching.
 * 5. Preserves Keep Ultra Wide Lens Ready and instant-switch readiness:
 *    Zero black frames, zero freezing, and 60fps frame-rate pacing.
 */
class ZoomTransitionController(
    private val onApplyZoomToEngine: (zoom: Float, isContinuous: Boolean) -> Unit = { _, _ -> },
    private val onSelectLensOnEngine: (lens: LensInfo, targetZoom: Float, isContinuous: Boolean) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val TAG = "ZoomTransitionCtrl"
        const val TOTAL_TRANSITION_DURATION_MS = 300L // Fixed duration of exactly 300ms (0.30s), never less
        const val DEFAULT_OVERLAP_DURATION_MS = 300L
        const val PREVIEW_OVERLAP_DURATION_MS = 100L
        const val MIN_OVERLAP_DURATION_MS = 100L
        const val MAX_OVERLAP_DURATION_MS = 1000L
    }

    var overlapDurationMs: Long = DEFAULT_OVERLAP_DURATION_MS

    private val _isTransitionActive = MutableStateFlow(false)
    val isTransitionActive: StateFlow<Boolean> = _isTransitionActive.asStateFlow()

    private val _currentInterpolatedZoom = MutableStateFlow(1.0f)
    val currentInterpolatedZoom: StateFlow<Float> = _currentInterpolatedZoom.asStateFlow()

    private val _previewOverlapState = MutableStateFlow(PreviewOverlapState())
    val previewOverlapState: StateFlow<PreviewOverlapState> = _previewOverlapState.asStateFlow()

    private var activeTransitionJob: Job? = null

    /**
     * Generates an ordered list of all consecutive 0.01x zoom steps from [startZoom] to [endZoom].
     * Guarantees that every intermediate 0.01x value is visited in strict order without skipping.
     * E.g. 0.50x to 1.00x -> [0.50, 0.51, 0.52, ..., 0.98, 0.99, 1.00] (51 steps).
     * E.g. 1.00x to 0.50x -> [1.00, 0.99, 0.98, ..., 0.51, 0.50] (51 steps).
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
     * Calculates the target elapsed timestamps (in milliseconds) for each step index using
     * a continuous sinusoidal ease-in-out curve over [durationMs].
     *
     * Curve: progress = (1 - cos(PI * t / T)) / 2 => t = (T / PI) * acos(1 - 2 * progress)
     * Characteristics:
     * - Slow start (ease-in)
     * - Faster middle movement
     * - Slow finish (ease-out)
     * - Monotonic timestamps from 0ms to exactly durationMs
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

    /**
     * Preserves steady-state preview state without overlapping animation.
     */
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

    /**
     * Legacy no-op stub: overlap animation is completely removed.
     */
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
     * Executes the unified 300ms continuous zoom transition through the existing preview pipeline.
     *
     * Invariants:
     * 1. Fixed duration: exactly 300ms (0.30 seconds), never less.
     * 2. From 0.5x to 1x: smoothly covers the entire range from 0.5x to 0.99x on Ultra-Wide,
     *    then switches to the 1x lens at 1.00x. Does NOT stop at 0.9x and switch directly to 1x.
     * 3. From 1x to 0.5x: switches to Ultra-Wide at start (where 1.44x crop matches 1.0x Main FOV),
     *    then smoothly covers the continuous range from 0.99x down to 0.50x.
     * 4. Overlap animation completely removed: no crossfade alpha blending between TextureViews.
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
            val tele2xLens = lensesForFacing.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical }
            val tele3xLens = lensesForFacing.firstOrNull { it.lensType == LensType.TELEPHOTO_3X && it.isPhysical }

            val minZoom = if (ultraWideLens != null) 0.5f else 1.0f
            val maxLensZoom = lensesForFacing.maxOfOrNull { it.maxZoomRatio } ?: 20.0f
            val maxZoom = maxOf(maxLensZoom, 20.0f)

            val startZ = ((fromZoom * 100f).roundToInt() / 100f).coerceIn(minZoom, maxZoom)
            val endZ = ((targetZoom * 100f).roundToInt() / 100f).coerceIn(minZoom, maxZoom)

            val switchZoom = CameraOpticalCalibration.switchPointToZoom(switchPointMm)

            val resolvedDestinationLens = targetLens ?: when {
                endZ < switchZoom -> ultraWideLens
                endZ >= 2.8f && tele3xLens != null -> tele3xLens
                endZ >= 1.8f && tele2xLens != null -> tele2xLens
                else -> mainWideLens
            }

            // If start and target are practically identical, finish immediately without delay
            if (abs(endZ - startZ) < 0.005f) {
                _currentInterpolatedZoom.value = endZ
                onZoomUpdate(endZ)
                if (resolvedDestinationLens != null && resolvedDestinationLens.id != currentLens?.id) {
                    onSelectLensOnEngine(resolvedDestinationLens, endZ, false)
                } else {
                    onApplyZoomToEngine(endZ, false)
                }
                setSteadyStatePreview(resolvedDestinationLens?.lensType == LensType.ULTRAWIDE)
                _isTransitionActive.value = false
                onComplete(endZ, resolvedDestinationLens)
                return@launch
            }

            _isTransitionActive.value = true

            // Generate every consecutive 0.01x zoom step in strict order without skipping
            val steps = generateContinuousZoomSteps(startZ, endZ)
            val stepCount = steps.size - 1

            // Direction 1: Downward switch crossing boundary into Ultra-Wide (e.g. 1.0x -> 0.5x)
            // Immediately select Ultra-Wide at startZ (at 1.00x, Ultra-Wide digital crop of 1.44x matches 1.0x Main FOV perfectly)
            // Then the transition smoothly uncrops through the entire range (0.99x down to 0.50x)
            val isCrossingDownToUw = startZ >= switchZoom && endZ < switchZoom && ultraWideLens != null
            if (isCrossingDownToUw) {
                onSelectLensOnEngine(ultraWideLens!!, startZ, true)
            }

            // Direction 2: Upward switch from Ultra-Wide to Main (e.g. 0.5x -> 1.0x)
            // Ensure Ultra-Wide lens is active at startZ so it can smoothly cover 0.50x to 0.99x
            val isCrossingUpToMain = startZ < switchZoom && endZ >= switchZoom && mainWideLens != null
            if (isCrossingUpToMain) {
                if (currentLens?.lensType != LensType.ULTRAWIDE && ultraWideLens != null) {
                    onSelectLensOnEngine(ultraWideLens, startZ, true)
                }
            }

            val startTime = SystemClock.uptimeMillis()
            var lastReportedStepIndex = 0
            var lastDispatchedZoom = startZ
            var lastDispatchedTime = startTime

            // Step through intermediate values using continuous sinusoidal ease-in-out motion
            // Duration is fixed at exactly 300ms (never less)
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val elapsed = now - startTime
                if (elapsed >= transitionDurationMs) break

                // Sinusoidal ease-in-out progress: (1 - cos(PI * progress)) / 2
                val linearProgress = (elapsed.toDouble() / transitionDurationMs.toDouble()).coerceIn(0.0, 1.0)
                val smoothProgress = (0.5 * (1.0 - cos(PI * linearProgress))).coerceIn(0.0, 1.0)

                // Advance step index and report all intermediate values in sequence
                val targetStepIndex = (smoothProgress * stepCount).roundToInt().coerceIn(0, stepCount)
                if (targetStepIndex > lastReportedStepIndex) {
                    for (s in (lastReportedStepIndex + 1)..targetStepIndex) {
                        val z = steps[s]
                        // For 0.5x -> 1.0x upward transition, hold the switch to 1x until transition completes;
                        // smoothly cover 0.50x to 0.99x while in-flight on Ultra-Wide
                        if (isCrossingUpToMain && s == stepCount) {
                            continue
                        }
                        _currentInterpolatedZoom.value = z
                        onZoomUpdate(z)
                    }
                    lastReportedStepIndex = targetStepIndex
                }

                val currentZ = if (isCrossingUpToMain && targetStepIndex == stepCount) {
                    steps[stepCount - 1] // 0.99x on Ultra-Wide
                } else {
                    steps[targetStepIndex]
                }

                // Paced camera-engine updates (60fps pacing, avoid redundant calls)
                if (abs(currentZ - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= 15L) {
                    lastDispatchedZoom = currentZ
                    lastDispatchedTime = now
                    onApplyZoomToEngine(currentZ, true)
                }

                val nextTick = minOf(startTime + transitionDurationMs, now + 8L)
                val waitMs = nextTick - SystemClock.uptimeMillis()
                if (waitMs > 0) {
                    delay(waitMs)
                }
            }

            // Fixed duration: both 0.5x -> 1x and 1x -> 0.5x transitions must take exactly 300ms, never less
            val totalElapsed = SystemClock.uptimeMillis() - startTime
            if (totalElapsed < transitionDurationMs) {
                delay(transitionDurationMs - totalElapsed)
            }

            // Report any remaining intermediate steps
            val intermediateLimit = if (isCrossingUpToMain) stepCount - 1 else stepCount
            if (lastReportedStepIndex < intermediateLimit) {
                for (s in (lastReportedStepIndex + 1)..intermediateLimit) {
                    val z = steps[s]
                    _currentInterpolatedZoom.value = z
                    onZoomUpdate(z)
                }
            }

            // Lens switch at the end of the transition
            if (isCrossingUpToMain) {
                onSelectLensOnEngine(mainWideLens!!, endZ, false)
            } else if (resolvedDestinationLens != null && resolvedDestinationLens.id != currentLens?.id && !isCrossingDownToUw) {
                onSelectLensOnEngine(resolvedDestinationLens, endZ, false)
            }

            _currentInterpolatedZoom.value = endZ
            onZoomUpdate(endZ)
            onApplyZoomToEngine(endZ, false)
            setSteadyStatePreview(resolvedDestinationLens?.lensType == LensType.ULTRAWIDE || endZ < switchZoom)
            _isTransitionActive.value = false
            activeTransitionJob = null

            onComplete(endZ, resolvedDestinationLens)
        }

        activeTransitionJob = job
        return job
    }

    /**
     * Cancels any running transition immediately.
     */
    fun cancelTransition() {
        activeTransitionJob?.cancel()
        activeTransitionJob = null
        _isTransitionActive.value = false
    }
}
