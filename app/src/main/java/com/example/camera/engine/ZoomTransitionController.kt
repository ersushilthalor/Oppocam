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
 * Preview layer overlapping state for crossfading between Main (1x) and Ultra-Wide (0.5x)
 * TextureViews on the actual viewfinder rendering layers without session restarts.
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
 * 1. Fixed duration of EXACTLY 300ms (0.30 seconds) for continuous digital zoom slider transitions.
 * 2. Dedicated user-configurable "Lens Switch Overlap Animation Duration" setting (0.1s to 1.0s, default 0.3s)
 *    applied specifically to physical lens-switching preview overlap crossfades (Ultra-Wide ↔ 1x Main).
 * 3. Continuous, seamless preview handoff with smooth sinusoidal ease-in-out motion:
 *    no sudden jumps, pauses, intermediate stops, dark flickers, or visible lens-switch stutters.
 * 4. Preserves every intermediate zoom value and progresses continuously to the exact target value.
 * 5. Seamless preview handoff for Ultra-Wide <-> 1x switching without waiting for animation to finish.
 * 6. Preserves instant-switch capability (0ms delay to switch hardware lens) and standby-camera operation.
 * 7. Eliminates redundant camera-engine calls with frame-rate pacing (~15ms / 60fps), preventing pipeline flooding.
 * 8. Zero delays on slider-based zoom or manual pinch gestures.
 */
class ZoomTransitionController(
    private val onApplyZoomToEngine: (zoom: Float, isContinuous: Boolean) -> Unit = { _, _ -> },
    private val onSelectLensOnEngine: (lens: LensInfo, targetZoom: Float, isContinuous: Boolean) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val TAG = "ZoomTransitionCtrl"
        const val TOTAL_TRANSITION_DURATION_MS = 300L // 300ms (0.30s) for continuous digital zoom
        const val DEFAULT_OVERLAP_DURATION_MS = 300L  // 300ms (0.30s) default for lens-switch overlap
        const val PREVIEW_OVERLAP_DURATION_MS = 100L  // Backwards compatibility default
        const val MIN_OVERLAP_DURATION_MS = 100L      // 100ms (0.10s)
        const val MAX_OVERLAP_DURATION_MS = 1000L     // 1000ms (1.00s)
    }

    var overlapDurationMs: Long = DEFAULT_OVERLAP_DURATION_MS

    private val _isTransitionActive = MutableStateFlow(false)
    val isTransitionActive: StateFlow<Boolean> = _isTransitionActive.asStateFlow()

    private val _currentInterpolatedZoom = MutableStateFlow(1.0f)
    val currentInterpolatedZoom: StateFlow<Float> = _currentInterpolatedZoom.asStateFlow()

    private val _previewOverlapState = MutableStateFlow(PreviewOverlapState())
    val previewOverlapState: StateFlow<PreviewOverlapState> = _previewOverlapState.asStateFlow()

    private var activeTransitionJob: Job? = null
    private var activeOverlapJob: Job? = null

    /**
     * Generates an ordered list of all consecutive 0.01x zoom steps from [startZoom] to [endZoom].
     * Guarantees that every intermediate 0.01x value is visited in strict order without skipping.
     * E.g. 0.50x to 1.00x -> [0.50, 0.51, 0.52, ..., 0.99, 1.00] (51 steps).
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
     * Calculates the normalized progress (0.0 to 1.0) of preview overlap crossfading.
     * Overlap duration is by default [overlapDurationMs].
     */
    fun calculateOverlapProgress(
        elapsedMs: Long,
        overlapStartMs: Long,
        overlapDurationMs: Long = this.overlapDurationMs
    ): Float {
        if (elapsedMs <= overlapStartMs) return 0f
        if (elapsedMs >= overlapStartMs + overlapDurationMs) return 1f
        val fraction = ((elapsedMs - overlapStartMs).toFloat() / overlapDurationMs.toFloat()).coerceIn(0f, 1f)
        // Smooth sinusoidal ease curve for the crossfade
        return (0.5f * (1.0f - cos(fraction * PI.toFloat()))).coerceIn(0f, 1f)
    }

    /**
     * Sets steady-state preview state for a given lens without overlapping animation.
     */
    fun setSteadyStatePreview(isUltraWide: Boolean) {
        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = false,
            mainAlpha = if (isUltraWide) 0.0f else 1.0f,
            ultraWideAlpha = if (isUltraWide) 1.0f else 0.0f,
            activeSource = if (isUltraWide) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN,
            overlapProgress = if (isUltraWide) 1.0f else 0.0f,
            topSource = if (isUltraWide) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
        )
    }

    /**
     * Starts an iPhone-style overlapping preview animation on the actual preview layers
     * for Ultra-Wide <-> Main handoff lasting user-configured [durationMs] in both directions.
     *
     * Crossfades the actual TextureView preview layers without restarting camera sessions
     * or waiting for camera initialization. Outgoing preview remains solid underneath while
     * incoming preview fades in on top, guaranteeing zero black flicker, jump, or dimming.
     */
    fun startPreviewOverlap(
        fromLensType: LensType,
        toLensType: LensType,
        scope: CoroutineScope,
        durationMs: Long = overlapDurationMs,
        onComplete: (() -> Unit)? = null
    ): Job {
        activeOverlapJob?.cancel()
        val isUwToMain = fromLensType == LensType.ULTRAWIDE && toLensType != LensType.ULTRAWIDE
        val isMainToUw = fromLensType != LensType.ULTRAWIDE && toLensType == LensType.ULTRAWIDE

        if (!isUwToMain && !isMainToUw) {
            val isUw = toLensType == LensType.ULTRAWIDE
            setSteadyStatePreview(isUw)
            onComplete?.invoke()
            val dummyJob = scope.launch(Dispatchers.Main.immediate) {}
            activeOverlapJob = dummyJob
            return dummyJob
        }

        val topSource = if (isUwToMain) PreviewStreamSource.MAIN else PreviewStreamSource.ULTRAWIDE
        val effectiveDuration = durationMs.coerceIn(MIN_OVERLAP_DURATION_MS, MAX_OVERLAP_DURATION_MS)

        // Immediately enter active overlapping state synchronously so there is zero gap, flicker or jump
        val initialMainAlpha = if (isUwToMain) 0.0f else 1.0f
        val initialUwAlpha = if (isUwToMain) 1.0f else 0.0f
        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = true,
            mainAlpha = initialMainAlpha,
            ultraWideAlpha = initialUwAlpha,
            activeSource = if (isUwToMain) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN,
            overlapProgress = 0.0f,
            topSource = topSource
        )

        val job = scope.launch(Dispatchers.Main.immediate) {
            val startTime = SystemClock.uptimeMillis()

            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val elapsed = now - startTime
                if (elapsed >= effectiveDuration) break

                val linearProgress = (elapsed.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
                val smoothProgress = (0.5f * (1.0f - cos(linearProgress * PI.toFloat()))).coerceIn(0f, 1f)

                // Perfect optical crossfade:
                // The incoming layer is ON TOP (zIndex = 2f) and its opacity fades from 0.0f to 1.0f.
                // The outgoing layer is UNDERNEATH (zIndex = 1f) and remains solid 1.0f throughout.
                // Hardware alpha blending (topAlpha * Top + (1 - topAlpha) * Bottom) produces
                // a mathematically pure, seamless crossfade with 0% black background leakage,
                // no dips in luminance, no flickering, and no pauses.
                val mainAlpha = if (isUwToMain) smoothProgress else 1.0f
                val uwAlpha = if (isMainToUw) smoothProgress else 1.0f

                _previewOverlapState.value = PreviewOverlapState(
                    isOverlapping = true,
                    mainAlpha = mainAlpha,
                    ultraWideAlpha = uwAlpha,
                    activeSource = if (smoothProgress >= 0.5f) {
                        if (isUwToMain) PreviewStreamSource.MAIN else PreviewStreamSource.ULTRAWIDE
                    } else {
                        if (isUwToMain) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                    },
                    overlapProgress = smoothProgress,
                    topSource = topSource
                )

                delay(8L) // ~120fps ultra-fluid preview crossfade updates
            }

            // Exactly at effectiveDuration mark: finalize steady state preview
            val finalIsUw = (toLensType == LensType.ULTRAWIDE)
            setSteadyStatePreview(finalIsUw)
            onComplete?.invoke()
        }
        activeOverlapJob = job
        return job
    }

    /**
     * Executes the unified 300ms continuous zoom transition with synchronized seamless preview handoff.
     *
     * @param fromZoom Starting zoom value
     * @param targetZoom Target zoom value
     * @param availableLenses List of available hardware lenses
     * @param currentLens Active lens
     * @param switchPointMm Dynamic lens switch point in mm
     * @param scope CoroutineScope to launch within (usually viewModelScope)
     * @param durationMs Fixed duration of exactly 300ms
     * @param onZoomUpdate Callback on each continuous zoom step
     * @param onComplete Callback when transition completes
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
                    startPreviewOverlap(
                        currentLens?.lensType ?: LensType.WIDE,
                        resolvedDestinationLens.lensType,
                        scope,
                        overlapDurationMs
                    )
                } else {
                    onApplyZoomToEngine(endZ, false)
                }
                _isTransitionActive.value = false
                onComplete(endZ, resolvedDestinationLens)
                return@launch
            }

            _isTransitionActive.value = true

            // Generate every consecutive 0.01x zoom step in strict order without skipping
            val steps = generateContinuousZoomSteps(startZ, endZ)
            val stepCount = steps.size - 1

            // Direction 1: Downward switch crossing boundary into Ultra-Wide (e.g. 1.0x -> 0.5x)
            // Perform synchronized FOV-matched handoff at startZ/boundary, running seamless preview handoff without waiting for animation to finish
            val isCrossingDownToUw = startZ >= switchZoom && endZ < switchZoom && ultraWideLens != null
            if (isCrossingDownToUw) {
                // Immediate optical FOV-matched handoff to Ultra-Wide
                onSelectLensOnEngine(ultraWideLens!!, startZ, true)
                startPreviewOverlap(
                    LensType.WIDE,
                    LensType.ULTRAWIDE,
                    scope,
                    overlapDurationMs
                )
            }

            // Direction 2: Upward switch crossing boundary from Ultra-Wide to Main (e.g. 0.5x -> 1.0x)
            // Starts on Ultra-Wide; preview crossfade runs during final handoff window approaching 1.0x
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
            var hasTriggeredUpwardOverlap = false
            val upwardOverlapStartMs = max(0L, durationMs - overlapDurationMs)

            // Step through intermediate values using continuous sinusoidal ease-in-out motion
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val elapsed = now - startTime
                if (elapsed >= durationMs) break

                // Sinusoidal ease-in-out progress: (1 - cos(PI * progress)) / 2
                val linearProgress = (elapsed.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
                val smoothProgress = (0.5 * (1.0 - cos(PI * linearProgress))).coerceIn(0.0, 1.0)

                // Advance step index and report all intermediate values in sequence
                val targetStepIndex = (smoothProgress * stepCount).roundToInt().coerceIn(0, stepCount)
                if (targetStepIndex > lastReportedStepIndex) {
                    for (s in (lastReportedStepIndex + 1)..targetStepIndex) {
                        val z = steps[s]
                        _currentInterpolatedZoom.value = z
                        onZoomUpdate(z)
                    }
                    lastReportedStepIndex = targetStepIndex
                }

                val currentZ = steps[targetStepIndex]

                // Trigger upward preview overlap when entering the handoff window
                if (isCrossingUpToMain && !hasTriggeredUpwardOverlap && elapsed >= upwardOverlapStartMs) {
                    hasTriggeredUpwardOverlap = true
                    onSelectLensOnEngine(mainWideLens!!, currentZ, true)
                    startPreviewOverlap(
                        LensType.ULTRAWIDE,
                        LensType.WIDE,
                        scope,
                        overlapDurationMs
                    )
                }

                // Paced camera-engine updates (60fps pacing, avoid redundant calls)
                if (abs(currentZ - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= 15L) {
                    lastDispatchedZoom = currentZ
                    lastDispatchedTime = now
                    onApplyZoomToEngine(currentZ, true)
                }

                val nextTick = minOf(startTime + durationMs, now + 8L)
                val waitMs = nextTick - SystemClock.uptimeMillis()
                if (waitMs > 0) {
                    delay(waitMs)
                }
            }

            // Report any remaining intermediate steps up to stepCount
            if (lastReportedStepIndex < stepCount) {
                for (s in (lastReportedStepIndex + 1)..stepCount) {
                    val z = steps[s]
                    _currentInterpolatedZoom.value = z
                    onZoomUpdate(z)
                }
            }

            // Final boundary handoff for upward switch
            if (isCrossingUpToMain) {
                onSelectLensOnEngine(mainWideLens!!, endZ, true)
                if (!hasTriggeredUpwardOverlap) {
                    startPreviewOverlap(
                        LensType.ULTRAWIDE,
                        LensType.WIDE,
                        scope,
                        overlapDurationMs
                    )
                }
            } else if (resolvedDestinationLens != null && resolvedDestinationLens.id != currentLens?.id && !isCrossingDownToUw) {
                onSelectLensOnEngine(resolvedDestinationLens, endZ, true)
            }

            _currentInterpolatedZoom.value = endZ
            onZoomUpdate(endZ)
            onApplyZoomToEngine(endZ, false)
            _isTransitionActive.value = false
            activeTransitionJob = null

            // Ensure steady state preview is cleanly set if no overlap is actively running
            if (activeOverlapJob?.isActive != true) {
                val finalIsUw = (resolvedDestinationLens?.lensType == LensType.ULTRAWIDE || endZ < switchZoom)
                setSteadyStatePreview(finalIsUw)
            }

            onComplete(endZ, resolvedDestinationLens)
        }

        activeTransitionJob = job
        return job
    }

    /**
     * Cancels any running transition or overlap immediately.
     */
    fun cancelTransition() {
        activeTransitionJob?.cancel()
        activeTransitionJob = null
        activeOverlapJob?.cancel()
        activeOverlapJob = null
        _isTransitionActive.value = false
    }
}
