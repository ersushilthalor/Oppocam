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
    val overlapProgress: Float = 0.0f
)

/**
 * Unified Zoom Transition Controller.
 *
 * Implements:
 * 1. Ultra-smooth continuous zoom transition across all lenses and zoom values with
 *    a total transition duration of EXACTLY 400ms (0.40 seconds).
 * 2. Sinusoidal continuous interpolation (smooth ease-in, faster middle movement, smooth ease-out)
 *    traversing EVERY consecutive 0.01x intermediate zoom value without skipping or jumping.
 * 3. Redundant camera-engine call elimination using delta/frame-rate pacing.
 * 4. iPhone-style overlapping preview animation on the actual preview layers for both
 *    Ultra-Wide -> 1x and 1x -> Ultra-Wide switches lasting EXACTLY 100ms (0.10 seconds)
 *    in both directions.
 * 5. Synchronized 100ms preview overlap with the physical camera handoff without restarting
 *    camera sessions or waiting for new camera initialization.
 * 6. Preservation of instant lens readiness, background standby-camera operation, and responsive preset taps.
 */
class ZoomTransitionController(
    private val onApplyZoomToEngine: (zoom: Float, isContinuous: Boolean) -> Unit = { _, _ -> },
    private val onSelectLensOnEngine: (lens: LensInfo, targetZoom: Float, isContinuous: Boolean) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val TAG = "ZoomTransitionCtrl"
        const val TOTAL_TRANSITION_DURATION_MS = 400L // Exactly 400ms (0.40 seconds)
        const val PREVIEW_OVERLAP_DURATION_MS = 100L  // Exactly 100ms (0.10 seconds)
    }

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
     * Overlap duration is exactly [PREVIEW_OVERLAP_DURATION_MS] (100ms).
     */
    fun calculateOverlapProgress(
        elapsedMs: Long,
        overlapStartMs: Long,
        overlapDurationMs: Long = PREVIEW_OVERLAP_DURATION_MS
    ): Float {
        if (elapsedMs <= overlapStartMs) return 0f
        if (elapsedMs >= overlapStartMs + overlapDurationMs) return 1f
        val fraction = ((elapsedMs - overlapStartMs).toFloat() / overlapDurationMs.toFloat()).coerceIn(0f, 1f)
        // Smooth sinusoidal ease curve for the 100ms crossfade
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
            overlapProgress = if (isUltraWide) 1.0f else 0.0f
        )
    }

    /**
     * Starts an iPhone-style overlapping preview animation on the actual preview layers
     * for Ultra-Wide <-> Main handoff lasting EXACTLY 100ms (0.10 seconds) in both directions.
     *
     * Crossfades the actual TextureView preview layers without restarting camera sessions
     * or waiting for camera initialization.
     */
    fun startPreviewOverlap(
        fromLensType: LensType,
        toLensType: LensType,
        scope: CoroutineScope,
        durationMs: Long = PREVIEW_OVERLAP_DURATION_MS,
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

        val job = scope.launch(Dispatchers.Main.immediate) {
            val startTime = SystemClock.uptimeMillis()
            val stepIntervalMs = 10L // ~100fps silky smooth preview crossfade updates
            val steps = (durationMs / stepIntervalMs).toInt().coerceAtLeast(10)

            for (i in 0..steps) {
                if (!isActive) break
                val now = SystemClock.uptimeMillis()
                val elapsed = (now - startTime).coerceAtLeast(0L)
                val linearProgress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val smoothProgress = (0.5f * (1.0f - cos(linearProgress * PI.toFloat()))).coerceIn(0f, 1f)

                val mainAlpha = if (isUwToMain) smoothProgress else (1.0f - smoothProgress)
                val uwAlpha = if (isUwToMain) (1.0f - smoothProgress) else smoothProgress

                _previewOverlapState.value = PreviewOverlapState(
                    isOverlapping = true,
                    mainAlpha = mainAlpha,
                    ultraWideAlpha = uwAlpha,
                    activeSource = if (smoothProgress >= 0.5f) {
                        if (isUwToMain) PreviewStreamSource.MAIN else PreviewStreamSource.ULTRAWIDE
                    } else {
                        if (isUwToMain) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
                    },
                    overlapProgress = smoothProgress
                )

                val targetTime = startTime + ((i + 1) * durationMs / steps)
                val waitMs = targetTime - SystemClock.uptimeMillis()
                if (waitMs > 0) {
                    delay(waitMs)
                }
            }

            // Exactly at 100ms mark: finalize preview state
            val finalIsUw = (toLensType == LensType.ULTRAWIDE)
            setSteadyStatePreview(finalIsUw)
            onComplete?.invoke()
        }
        activeOverlapJob = job
        return job
    }

    /**
     * Executes the unified 400ms continuous zoom transition with synchronized 100ms preview overlap.
     *
     * @param fromZoom Starting zoom value
     * @param targetZoom Target zoom value
     * @param availableLenses List of available hardware lenses
     * @param currentLens Active lens
     * @param switchPointMm Dynamic lens switch point in mm
     * @param scope CoroutineScope to launch within (usually viewModelScope)
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

            // If start and target are practically identical, finish immediately without 400ms delay
            if (abs(endZ - startZ) < 0.005f) {
                _currentInterpolatedZoom.value = endZ
                onZoomUpdate(endZ)
                if (resolvedDestinationLens != null && resolvedDestinationLens.id != currentLens?.id) {
                    onSelectLensOnEngine(resolvedDestinationLens, endZ, false)
                    startPreviewOverlap(
                        currentLens?.lensType ?: LensType.WIDE,
                        resolvedDestinationLens.lensType,
                        scope,
                        PREVIEW_OVERLAP_DURATION_MS
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

            // Pre-calculate ease-in / fast middle / ease-out timestamps over EXACTLY durationMs (400ms)
            val targetElapsedMs = calculateSinusoidalElapsedTimestamps(stepCount, durationMs)

            // Overlap timing window: exactly 100ms
            // Direction 1: Downward switch crossing boundary into Ultra-Wide (e.g. 1.0x -> 0.5x)
            // Perform synchronized FOV-matched handoff at startZ/boundary, running 100ms preview overlap from t=0 to t=100ms
            val isCrossingDownToUw = startZ >= switchZoom && endZ < switchZoom && ultraWideLens != null
            if (isCrossingDownToUw) {
                // Handoff to Ultra-Wide with matched FOV crop
                onSelectLensOnEngine(ultraWideLens!!, startZ, true)
                startPreviewOverlap(
                    LensType.WIDE,
                    LensType.ULTRAWIDE,
                    scope,
                    PREVIEW_OVERLAP_DURATION_MS
                )
            }

            // Direction 2: Upward switch crossing boundary from Ultra-Wide to Main (e.g. 0.5x -> 1.0x)
            // Starts on Ultra-Wide; 100ms preview overlap runs during the final 100ms (from t = durationMs - 100ms to durationMs)
            val isCrossingUpToMain = startZ < switchZoom && endZ >= switchZoom && mainWideLens != null
            if (isCrossingUpToMain) {
                if (currentLens?.lensType != LensType.ULTRAWIDE && ultraWideLens != null) {
                    onSelectLensOnEngine(ultraWideLens, startZ, true)
                }
            }

            val startTime = SystemClock.uptimeMillis()
            var lastDispatchedZoom = startZ
            var lastDispatchedTime = startTime
            var hasTriggeredUpwardOverlap = false
            val upwardOverlapStartMs = max(0L, durationMs - PREVIEW_OVERLAP_DURATION_MS)

            // Step through every intermediate zoom value in strict sequential order
            for (stepIndex in 0 until stepCount) {
                if (!isActive) break

                val targetTime = startTime + targetElapsedMs[stepIndex]
                val now = SystemClock.uptimeMillis()
                val waitMs = targetTime - now
                if (waitMs > 0) {
                    delay(waitMs)
                }

                val currentZ = steps[stepIndex]
                _currentInterpolatedZoom.value = currentZ
                onZoomUpdate(currentZ)

                val elapsedNow = SystemClock.uptimeMillis() - startTime

                // Trigger 100ms preview overlap for upward switch when entering the 100ms window
                if (isCrossingUpToMain && !hasTriggeredUpwardOverlap && elapsedNow >= upwardOverlapStartMs) {
                    hasTriggeredUpwardOverlap = true
                    startPreviewOverlap(
                        LensType.ULTRAWIDE,
                        LensType.WIDE,
                        scope,
                        PREVIEW_OVERLAP_DURATION_MS
                    )
                }

                // Avoid redundant camera-engine calls while preserving every intermediate visual step:
                // Only dispatch to camera engine if zoom delta >= 0.01x or at least 15ms elapsed
                val currentTime = SystemClock.uptimeMillis()
                if (abs(currentZ - lastDispatchedZoom) >= 0.01f || (currentTime - lastDispatchedTime) >= 15L) {
                    lastDispatchedZoom = currentZ
                    lastDispatchedTime = currentTime
                    onApplyZoomToEngine(currentZ, true)
                }
            }

            // Final step reaching exactly durationMs (400ms)
            val finalTargetTime = startTime + targetElapsedMs[stepCount]
            val finalWaitMs = finalTargetTime - SystemClock.uptimeMillis()
            if (finalWaitMs > 0) {
                delay(finalWaitMs)
            }

            // Final boundary handoff for upward switch
            if (isCrossingUpToMain) {
                onSelectLensOnEngine(mainWideLens!!, endZ, true)
                if (!hasTriggeredUpwardOverlap) {
                    startPreviewOverlap(
                        LensType.ULTRAWIDE,
                        LensType.WIDE,
                        scope,
                        PREVIEW_OVERLAP_DURATION_MS
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

            // Ensure steady state preview is clean
            val finalIsUw = (resolvedDestinationLens?.lensType == LensType.ULTRAWIDE || endZ < switchZoom)
            setSteadyStatePreview(finalIsUw)

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
