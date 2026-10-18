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
 * Preview layer state for legacy overlap and frame-based lens handoff.
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
 * 1. Fixed duration of EXACTLY 400ms (0.40 seconds) for all continuous background slider movements.
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
        const val TOTAL_TRANSITION_DURATION_MS = 400L // Fixed duration of exactly 400ms (0.40s), never less
        const val DEFAULT_HOLD_AT_ONE_X_DURATION_MS = 200L // Default hold at exact 1x FOV before physical switch
        const val HOLD_AT_ONE_X_DURATION_MS = 20L // Preserved for backwards compatibility
        const val MIN_HOLD_AT_ONE_X_DURATION_MS = 0L
        const val MAX_HOLD_AT_ONE_X_DURATION_MS = 300L
        const val DEFAULT_OVERLAP_DURATION_MS = 400L
        const val PREVIEW_OVERLAP_DURATION_MS = 100L
        const val MIN_OVERLAP_DURATION_MS = 100L
        const val MAX_OVERLAP_DURATION_MS = 1000L
        private const val FRAME_HANDOFF_WARMUP_FRAMES = 2
        private const val FRAME_HANDOFF_REQUIRED_FRAMES = 16
        private const val FRAME_HANDOFF_TIMEOUT_MS = 900L
        // Once the incoming lens moves away from the shared ~1x field of view, shorten the
        // overlap so differently cropped frames do not remain visibly doubled.
        private const val MAIN_FOV_DIVERGENCE_FADE_RANGE = 0.20f
        private const val MAX_HANDOFF_ALPHA_STEP = 0.30f
        // Pre-warmed frames are reusable only if the target stream is still actively updating.
        private const val FRAME_HANDOFF_PREWARM_MAX_AGE_MS = 120L

        private fun isHalfXZoom(zoom: Float): Boolean = abs(zoom - 0.5f) < 0.025f

        private fun isTelephotoPresetZoom(zoom: Float): Boolean =
            abs(zoom - 2.0f) < 0.05f || abs(zoom - 3.0f) < 0.05f

        /** True only for direct 0.5x -> 2x/3x preset-endpoint transitions. */
        fun isHalfXToTelephotoPresetTransition(fromZoom: Float, targetZoom: Float): Boolean =
            isHalfXZoom(fromZoom) && isTelephotoPresetZoom(targetZoom)

        /** True only for direct 2x/3x -> 0.5x preset-endpoint transitions. */
        fun isTelephotoPresetToHalfXTransition(fromZoom: Float, targetZoom: Float): Boolean =
            isTelephotoPresetZoom(fromZoom) && isHalfXZoom(targetZoom)

        /**
         * Calculates alpha progress from real incoming frames and the shared-FOV window.
         * A pure 16-frame blend leaves differently cropped views visible as double edges.
         * In either direction, fade faster once zoom moves away from the ~1x handoff FOV;
         * incoming frames still gate every progress update, so we never fade to a stale stream.
         */
        internal fun calculateFrameHandoffProgress(
            incomingFrameCount: Int,
            targetSource: PreviewStreamSource,
            currentZoom: Float,
            previousProgress: Float = 0f
        ): Float {
            val frameProgress = incomingFrameCount.coerceIn(0, FRAME_HANDOFF_REQUIRED_FRAMES).toFloat() /
                FRAME_HANDOFF_REQUIRED_FRAMES.toFloat()
            val frameEased = (0.5 * (1.0 - cos(PI * frameProgress.toDouble()))).toFloat()
            val zoomEased = if (targetSource == PreviewStreamSource.ULTRAWIDE) {
                // Reverse handoff: below 1x, Main cannot widen enough to match Ultra-Wide.
                val zoomProgress = ((1.0f - currentZoom) / (1.0f - 0.5f)).coerceIn(0f, 1f)
                val acceleratedZoomProgress = 1f - (1f - zoomProgress) * (1f - zoomProgress) * (1f - zoomProgress)
                (0.5 * (1.0 - cos(PI * acceleratedZoomProgress.toDouble()))).toFloat()
            } else {
                // Forward handoff: the outgoing Ultra-Wide frame is effectively parked near 1x,
                // while Main's FOV keeps tightening for 2x/3x. Fade along this shared-FOV window.
                val zoomProgress = ((currentZoom - 1.0f) / MAIN_FOV_DIVERGENCE_FADE_RANGE).coerceIn(0f, 1f)
                val acceleratedZoomProgress = 1f - (1f - zoomProgress) * (1f - zoomProgress) * (1f - zoomProgress)
                (0.5 * (1.0 - cos(PI * acceleratedZoomProgress.toDouble()))).toFloat()
            }
            val previous = previousProgress.coerceIn(0f, 1f)
            val desired = maxOf(frameEased, zoomEased)
            // Cap per-frame opacity changes to keep the fade smooth even if zoom/FOV jumps a tick.
            return minOf(desired, previous + MAX_HANDOFF_ALPHA_STEP).coerceIn(previous, 1f)
        }

        /**
         * Frame-based handoff is reserved for direct Ultra-Wide <-> 2x/3x preset taps.
         * A direct 0.5x <-> 1x tap must use the normal hold-time behavior instead.
         */
        fun isFrameBasedHandoffEligible(fromZoom: Float, targetZoom: Float): Boolean =
            isHalfXToTelephotoPresetTransition(fromZoom, targetZoom) ||
                isTelephotoPresetToHalfXTransition(fromZoom, targetZoom)
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

    // Keep the outgoing live preview visible until the incoming TextureView produces real frames.
    // This handoff is independent of zoom interpolation, so crossing 1x never pauses zoom.
    private var pendingFrameHandoffTarget: PreviewStreamSource? = null
    private var frameHandoffFromSource: PreviewStreamSource? = null
    private var frameHandoffFrameCount: Int = 0
    private var frameHandoffTimeoutJob: Job? = null

    // The standby camera can already be producing valid frames before the 1x switch point.
    // Count them silently so the visible crossfade does not waste two more warm-up frames.
    private var preparedFrameHandoffFromSource: PreviewStreamSource? = null
    private var preparedFrameHandoffTargetSource: PreviewStreamSource? = null
    private var preparedTargetFrameCount: Int = 0
    private var preparedTargetLastFrameAtMs: Long = 0L
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

    /**
     * Keeps the outgoing live source visible while the target stream warms up, then blends
     * when the target TextureView reports actual new buffers. Progress is frame-count based,
     * rather than a timer that can fade to a camera which has not produced an image yet.
     *
     * The timeout is a fail-safe for devices that cannot produce a target frame callback.
     */
    /**
     * Pre-warms the target stream without changing preview alpha. For a .5x -> 2x/3x move,
     * the standby Main preview is parked at the calibrated switch FOV while idle, so fresh
     * frames can be counted before the switch point is crossed.
     */
    fun prepareFrameBasedHandoff(
        fromSource: PreviewStreamSource,
        targetSource: PreviewStreamSource
    ) {
        if (fromSource == targetSource) {
            clearPreparedFrameHandoff()
            return
        }
        preparedFrameHandoffFromSource = fromSource
        preparedFrameHandoffTargetSource = targetSource
        preparedTargetFrameCount = 0
        preparedTargetLastFrameAtMs = 0L
    }

    private fun clearPreparedFrameHandoff() {
        preparedFrameHandoffFromSource = null
        preparedFrameHandoffTargetSource = null
        preparedTargetFrameCount = 0
        preparedTargetLastFrameAtMs = 0L
    }

    fun beginFrameBasedHandoff(
        fromSource: PreviewStreamSource,
        targetSource: PreviewStreamSource,
        scope: CoroutineScope
    ) {
        frameHandoffTimeoutJob?.cancel()
        val nowMs = SystemClock.uptimeMillis()
        val hasFreshPreparedTargetFrames =
            preparedFrameHandoffFromSource == fromSource &&
                preparedFrameHandoffTargetSource == targetSource &&
                preparedTargetFrameCount >= FRAME_HANDOFF_WARMUP_FRAMES &&
                preparedTargetLastFrameAtMs > 0L &&
                nowMs - preparedTargetLastFrameAtMs <= FRAME_HANDOFF_PREWARM_MAX_AGE_MS
        clearPreparedFrameHandoff()

        if (fromSource == targetSource) {
            pendingFrameHandoffTarget = null
            frameHandoffFromSource = null
            setSteadyStatePreview(targetSource == PreviewStreamSource.ULTRAWIDE)
            return
        }

        frameHandoffFromSource = fromSource
        pendingFrameHandoffTarget = targetSource
        // Fresh pre-warmed buffers already satisfied camera/crop warm-up. If not, use the
        // normal two-frame warm-up path after switching; never blend stale target frames.
        frameHandoffFrameCount = if (hasFreshPreparedTargetFrames) FRAME_HANDOFF_WARMUP_FRAMES else 0
        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = true,
            mainAlpha = if (fromSource == PreviewStreamSource.MAIN) 1f else 0f,
            ultraWideAlpha = if (fromSource == PreviewStreamSource.ULTRAWIDE) 1f else 0f,
            activeSource = targetSource,
            overlapProgress = 0f,
            topSource = fromSource
        )

        frameHandoffTimeoutJob = scope.launch {
            delay(FRAME_HANDOFF_TIMEOUT_MS)
            if (pendingFrameHandoffTarget == targetSource) {
                // Prevent a permanently stale outgoing frame if the target stream fails.
                pendingFrameHandoffTarget = null
                frameHandoffFromSource = null
                frameHandoffTimeoutJob = null
                setSteadyStatePreview(targetSource == PreviewStreamSource.ULTRAWIDE)
            }
        }
    }

    /** Called when a preview TextureView receives a new buffer. */
    fun onPreviewFrameAvailable(source: PreviewStreamSource) {
        val targetSource = pendingFrameHandoffTarget
        if (targetSource == null) {
            // While pre-warming, observe only the target stream. This never changes visible
            // alpha and cannot complete the handoff before the 1x-equivalent switch point.
            if (source == preparedFrameHandoffTargetSource) {
                preparedTargetFrameCount =
                    (preparedTargetFrameCount + 1).coerceAtMost(FRAME_HANDOFF_WARMUP_FRAMES)
                preparedTargetLastFrameAtMs = SystemClock.uptimeMillis()
            }
            return
        }
        if (source != targetSource) return

        frameHandoffFrameCount = (frameHandoffFrameCount + 1).coerceAtMost(
            FRAME_HANDOFF_WARMUP_FRAMES + FRAME_HANDOFF_REQUIRED_FRAMES
        )
        val fromSource = frameHandoffFromSource ?: return

        // Let two real target frames arrive before blending. Camera2 applies crop/zoom changes
        // asynchronously; these warm-up frames let the new stream catch up to the continuously
        // moving zoom value while the outgoing stream remains fully visible.
        if (frameHandoffFrameCount <= FRAME_HANDOFF_WARMUP_FRAMES) {
            _previewOverlapState.value = PreviewOverlapState(
                isOverlapping = true,
                mainAlpha = if (fromSource == PreviewStreamSource.MAIN) 1f else 0f,
                ultraWideAlpha = if (fromSource == PreviewStreamSource.ULTRAWIDE) 1f else 0f,
                activeSource = targetSource,
                overlapProgress = 0f,
                topSource = fromSource
            )
            return
        }

        val blendFrameCount = frameHandoffFrameCount - FRAME_HANDOFF_WARMUP_FRAMES
        // Progress is tied to real target buffers and zoom trajectory. For reverse handoff,
        // retire the Main layer quickly once the target Ultra-Wide FOV becomes wider than Main.
        val previousProgress = _previewOverlapState.value.overlapProgress
        val progress = calculateFrameHandoffProgress(
            incomingFrameCount = blendFrameCount,
            targetSource = targetSource,
            currentZoom = _currentInterpolatedZoom.value,
            previousProgress = previousProgress
        )
        // Keep the outgoing layer fully opaque underneath. Only the incoming top layer
        // animates its alpha, producing a true old*(1-p) + new*p visual blend without dark gaps.
        val mainAlpha = if (targetSource == PreviewStreamSource.MAIN) progress else 1f
        val ultraWideAlpha = if (targetSource == PreviewStreamSource.ULTRAWIDE) progress else 1f

        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = true,
            mainAlpha = mainAlpha,
            ultraWideAlpha = ultraWideAlpha,
            activeSource = targetSource,
            overlapProgress = progress,
            topSource = targetSource
        )

        if (blendFrameCount >= FRAME_HANDOFF_REQUIRED_FRAMES) {
            frameHandoffTimeoutJob?.cancel()
            frameHandoffTimeoutJob = null
            pendingFrameHandoffTarget = null
            frameHandoffFromSource = null
            setSteadyStatePreview(targetSource == PreviewStreamSource.ULTRAWIDE)
        }
    }

    private fun cancelFrameBasedHandoff() {
        val state = _previewOverlapState.value
        val keepUltraWide = if (state.isOverlapping) {
            state.ultraWideAlpha >= state.mainAlpha
        } else {
            state.activeSource == PreviewStreamSource.ULTRAWIDE
        }
        frameHandoffTimeoutJob?.cancel()
        frameHandoffTimeoutJob = null
        pendingFrameHandoffTarget = null
        frameHandoffFromSource = null
        frameHandoffFrameCount = 0
        clearPreparedFrameHandoff()
        setSteadyStatePreview(keepUltraWide)
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
     * - Runs over exactly 0.4 seconds (400ms), never less.
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

        // Fixed duration: must take exactly 400ms (0.40 seconds), never less
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
            val effectiveSwitchZoom = if ((isTappingOneX && switchZoom > 1.0f) || isHalfXToTelephotoPresetTransition(startZ, endZ)) 1.0f else switchZoom
            val isUltraWideToMainSwitch = !isFrontCamera && isStartingOnUltraWide &&
                isCrossingOrAtSwitchPoint &&
                ultraWideLens != null && mainWideLens != null &&
                (targetLens == null || targetLens.isPrimaryMain || targetLens.lensType == LensType.WIDE || targetLens.lensType == LensType.TELEPHOTO || targetLens.lensType == LensType.TELEPHOTO_3X)
            val isDirectHalfXToTelephotoPreset = isHalfXToTelephotoPresetTransition(startZ, endZ)

            if (isUltraWideToMainSwitch) {
                // 1. Keep view on Ultra-Wide lens; digitally crop Ultra-Wide to the selected focal length (effectiveSwitchZoom)
                setSteadyStatePreview(true)
                val startNorm = zoomToNormalizedLog(startZ, minZoom, maxZoom)
                val switchNorm = zoomToNormalizedLog(effectiveSwitchZoom, minZoom, maxZoom)
                val endNorm = zoomToNormalizedLog(endZ, minZoom, maxZoom)

                val isTargetNearSwitch = abs(endZ - effectiveSwitchZoom) <= 0.08f || endZ <= effectiveSwitchZoom + 0.05f

                if (!isTargetNearSwitch && (skipHoldAtSwitchPoint || isDirectHalfXToTelephotoPreset)) {
                    // Keep a single easing curve across the physical lens boundary. The previous
                    // two-phase path eased to zero velocity at 1x, then restarted easing on Main,
                    // which looked like a zoom pause even when the preview crossfade was working.
                    val useFrameBasedHandoff = isOverlapAnimationEnabled &&
                        isFrameBasedHandoffEligible(startZ, endZ)
                    if (useFrameBasedHandoff) {
                        // Start observing standby Main frames now. Visible blending remains
                        // gated to the 1x-equivalent switch point, but does not spend two extra
                        // frames waiting for a stream that was already ready.
                        prepareFrameBasedHandoff(
                            fromSource = PreviewStreamSource.ULTRAWIDE,
                            targetSource = PreviewStreamSource.MAIN
                        )
                    }
                    val startTime = SystemClock.uptimeMillis()
                    var mainLensSelected = false

                    if (isDirectHalfXToTelephotoPreset) {
                        // Emit every 0.05x value in order. The 0.5x -> 2x path has
                        // 30 equal intervals, so each interval is 400/30 ms (~13.33 ms).
                        // Use absolute deadlines so callback/camera latency does not accumulate
                        // into the total animation duration. The physical handoff happens exactly
                        // at 1.00x, without restarting the timeline or inserting a hold.
                        val stepCount = ((endZ - startZ) / 0.05f).roundToInt().coerceAtLeast(1)
                        for (stepIndex in 0..stepCount) {
                            if (!isActive) break
                            val deadline = startTime +
                                (stepIndex.toDouble() * transitionDurationMs.toDouble() / stepCount.toDouble()).roundToInt()
                            val waitMs = deadline - SystemClock.uptimeMillis()
                            if (waitMs > 0L) delay(waitMs)

                            val zoomValue = if (stepIndex == stepCount) {
                                endZ
                            } else {
                                (startZ + stepIndex * 0.05f).coerceIn(startZ, endZ)
                            }.let { (it * 100f).roundToInt() / 100f }

                            if (!mainLensSelected && zoomValue >= effectiveSwitchZoom - 0.0001f) {
                                _currentInterpolatedZoom.value = effectiveSwitchZoom
                                onZoomUpdate(effectiveSwitchZoom)
                                onApplyZoomToEngine(effectiveSwitchZoom, true)
                                if (useFrameBasedHandoff) {
                                    beginFrameBasedHandoff(
                                        fromSource = PreviewStreamSource.ULTRAWIDE,
                                        targetSource = PreviewStreamSource.MAIN,
                                        scope = scope
                                    )
                                }
                                onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                                if (!useFrameBasedHandoff) setSteadyStatePreview(false)
                                mainLensSelected = true
                            }

                            _currentInterpolatedZoom.value = zoomValue
                            onZoomUpdate(zoomValue)
                            onApplyZoomToEngine(zoomValue, true)
                        }
                    } else {
                        // Preserve the existing continuous curve for all other zoom transitions.
                        var lastDispatchedZoom = startZ
                        var lastDispatchedTime = 0L
                        while (isActive) {
                            val now = SystemClock.uptimeMillis()
                            val elapsed = now - startTime
                            if (elapsed >= transitionDurationMs) break

                            val linearProgress = (elapsed.toDouble() / transitionDurationMs.toDouble()).coerceIn(0.0, 1.0)
                            val smoothProgress = (0.5 * (1.0 - cos(PI * linearProgress))).coerceIn(0.0, 1.0)
                            val currentNorm = (startNorm + (endNorm - startNorm) * smoothProgress).toFloat().coerceIn(0f, 1f)
                            val rawZoom = normalizedToZoomLog(currentNorm, minZoom, maxZoom)
                            val currentZ = rawZoom.coerceIn(minZoom, maxZoom)
                            val reachedSwitchPoint = currentNorm >= switchNorm || currentZ >= effectiveSwitchZoom

                            if (!mainLensSelected && reachedSwitchPoint) {
                                _currentInterpolatedZoom.value = effectiveSwitchZoom
                                if (abs(lastDispatchedZoom - effectiveSwitchZoom) >= 0.005f) {
                                    onZoomUpdate(effectiveSwitchZoom)
                                }
                                onApplyZoomToEngine(effectiveSwitchZoom, true)
                                if (useFrameBasedHandoff) {
                                    beginFrameBasedHandoff(
                                        fromSource = PreviewStreamSource.ULTRAWIDE,
                                        targetSource = PreviewStreamSource.MAIN,
                                        scope = scope
                                    )
                                }
                                onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                                if (!useFrameBasedHandoff) setSteadyStatePreview(false)
                                mainLensSelected = true
                                lastDispatchedZoom = effectiveSwitchZoom
                                lastDispatchedTime = now
                                if (currentZ > effectiveSwitchZoom + 0.005f) {
                                    _currentInterpolatedZoom.value = currentZ
                                    onZoomUpdate(currentZ)
                                    onApplyZoomToEngine(currentZ, true)
                                    lastDispatchedZoom = currentZ
                                    lastDispatchedTime = now
                                }
                            } else {
                                val zoomToApply = if (mainLensSelected) currentZ else currentZ.coerceAtMost(effectiveSwitchZoom)
                                if (abs(zoomToApply - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= 16L) {
                                    lastDispatchedZoom = zoomToApply
                                    lastDispatchedTime = now
                                    _currentInterpolatedZoom.value = zoomToApply
                                    onZoomUpdate(zoomToApply)
                                    onApplyZoomToEngine(zoomToApply, true)
                                }
                            }

                            val nextTick = minOf(startTime + transitionDurationMs, now + 16L)
                            val waitMs = nextTick - SystemClock.uptimeMillis()
                            if (waitMs > 0) delay(waitMs)
                        }
                    }

                    val totalElapsed = SystemClock.uptimeMillis() - startTime
                    if (totalElapsed < transitionDurationMs) {
                        delay(transitionDurationMs - totalElapsed)
                    }

                    // Fail safe: a very unusual zoom mapping must not leave the physical lens unchanged.
                    if (!mainLensSelected) {
                        onApplyZoomToEngine(effectiveSwitchZoom, true)
                        if (useFrameBasedHandoff) {
                            beginFrameBasedHandoff(
                                fromSource = PreviewStreamSource.ULTRAWIDE,
                                targetSource = PreviewStreamSource.MAIN,
                                scope = scope
                            )
                        }
                        onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                        if (!useFrameBasedHandoff) setSteadyStatePreview(false)
                    }

                    _currentInterpolatedZoom.value = endZ
                    onZoomUpdate(endZ)
                    onApplyZoomToEngine(endZ, false)
                    if (pendingFrameHandoffTarget == null) setSteadyStatePreview(false)
                    _isTransitionActive.value = false
                    activeTransitionJob = null
                    onComplete(endZ, targetLens ?: mainWideLens)
                    return@launch
                }

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
                    val currentZ = rawZoom.coerceIn(minZoom, effectiveSwitchZoom)

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
                val isDirectTelephotoTarget = isDirectHalfXToTelephotoPreset
                val holdDuration = if (skipHoldAtSwitchPoint || isDirectHalfXToTelephotoPreset) {
                    0L
                } else {
                    holdAtOneXDurationMs.coerceIn(MIN_HOLD_AT_ONE_X_DURATION_MS, MAX_HOLD_AT_ONE_X_DURATION_MS)
                }

                val useFrameBasedHandoff = isOverlapAnimationEnabled &&
                    isFrameBasedHandoffEligible(startZ, endZ)
                val mainLensPreparedInOverlap = isOverlapAnimationEnabled &&
                    (holdDuration > 0L || isDirectTelephotoTarget || useFrameBasedHandoff)
                var frameBasedHandoffStarted = false
                if (mainLensPreparedInOverlap) {
                    if (isDirectTelephotoTarget || useFrameBasedHandoff) {
                        // Start handoff at the Ultra-Wide/Main boundary. The outgoing frame
                        // stays visible until fresh Main buffers arrive; zoom keeps moving.
                        beginFrameBasedHandoff(
                            fromSource = PreviewStreamSource.ULTRAWIDE,
                            targetSource = PreviewStreamSource.MAIN,
                            scope = scope
                        )
                        frameBasedHandoffStarted = true
                        onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, true)
                    } else {
                        // Keep the deliberately configured hold behavior for an explicit 1x tap.
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
                                if (waitMs > 0) delay(waitMs)
                            }
                        }
                    }
                } else {
                    // Exact hold duration applied at selected switch point FOV.
                    delay(holdDuration)
                }

                // Avoid resetting the frame-driven overlap before target frames arrive.
                if (!mainLensPreparedInOverlap) {
                    onSelectLensOnEngine(mainWideLens, effectiveSwitchZoom, false)
                }
                if (!frameBasedHandoffStarted) setSteadyStatePreview(false)

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
                        val currentZ = rawZoom.coerceIn(effectiveSwitchZoom, maxZoom)

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
            val useReverseFrameBasedHandoff = isOverlapAnimationEnabled &&
                isFrameBasedHandoffEligible(startZ, endZ)
            var reverseFrameBasedHandoffStarted = false

            // Move the slider in the background from startNorm to targetNorm over exactly 400ms (0.40s)
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
                val currentZ = rawZoom.coerceIn(minZoom, maxZoom)

                // Arm the reverse handoff before the zoom update triggers the physical switch to Ultra-Wide.
                // Only direct 2x/3x -> 0.5x transitions use frame-driven blending; 1x -> 0.5x stays untouched.
                if (useReverseFrameBasedHandoff && !reverseFrameBasedHandoffStarted && currentZ < switchZoom - 0.005f) {
                    beginFrameBasedHandoff(
                        fromSource = PreviewStreamSource.MAIN,
                        targetSource = PreviewStreamSource.ULTRAWIDE,
                        scope = scope
                    )
                    reverseFrameBasedHandoffStarted = true
                }

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

            // Guarantee exact duration of 400ms (0.40s), never less
            val totalElapsed = SystemClock.uptimeMillis() - startTime
            if (totalElapsed < transitionDurationMs) {
                delay(transitionDurationMs - totalElapsed)
            }

            // Apply final target zoom value maintaining continuous transition
            _currentInterpolatedZoom.value = endZ
            onZoomUpdate(endZ)
            onApplyZoomToEngine(endZ, true)

            if (pendingFrameHandoffTarget == null) {
                setSteadyStatePreview(!isFrontCamera && endZ < switchZoom)
            }
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
        if (pendingFrameHandoffTarget != null || preparedFrameHandoffTargetSource != null) {
            cancelFrameBasedHandoff()
        }
    }
}
