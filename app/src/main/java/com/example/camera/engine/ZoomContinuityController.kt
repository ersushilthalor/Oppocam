package com.example.camera.engine

import android.util.Log
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Zoom Continuity Controller.
 *
 * Ensures seamless, jump-free zoom transitions across camera lens switches:
 * - Continuously tracks the user's latest target zoom and swipe velocity/direction during slow lens switches.
 * - When a new lens becomes ready, initializes it at the exact FOV-equivalent zoom of the current view.
 * - Smoothly interpolates zoom on the new lens toward the user's latest requested target,
 *   preserving gesture velocity and direction without visual snapping or framing jumps.
 * - Supports Main <-> UltraWide <-> Telephoto transitions in Photo, Portrait, Video, and Cinema modes.
 * - Never restarts camera capture sessions or encoders for zoom adjustments.
 */
class ZoomContinuityController(
    private val coroutineScope: CoroutineScope,
    private val onApplyZoom: (zoom: Float) -> Unit,
    private val onZoomUpdated: (zoom: Float) -> Unit
) {
    companion object {
        private const val TAG = "ZoomContinuity"
        const val MIN_SLEW_SPEED = 2.5f // zoom units per second
        const val MAX_SLEW_SPEED = 14.0f // zoom units per second
        const val FRAME_INTERVAL_MS = 16L // ~60fps smooth interpolation cadence
    }

    /**
     * The user's latest requested target zoom (e.g. 8.0x), updated continuously during gestures.
     */
    @Volatile
    var userTargetZoom: Float = 1.0f
        private set

    /**
     * The apparent FOV zoom currently applied to the active display viewfinder (1.0x = Main 1x FOV).
     */
    @Volatile
    var currentDisplayedZoom: Float = 1.0f
        private set

    /**
     * The lens currently rendering to the viewfinder display.
     */
    @Volatile
    var activeLens: LensInfo? = null

    /**
     * The target lens currently being switched to (opening / configuring / warming up).
     */
    @Volatile
    var pendingLens: LensInfo? = null
        private set

    val isSwitching: Boolean
        get() = pendingLens != null || isSwitchingFlag.get()

    private val isSwitchingFlag = AtomicBoolean(false)

    /**
     * Estimated zoom velocity in zoom units per second (dZ/dt).
     * Positive = zooming in, Negative = zooming out.
     */
    @Volatile
    var zoomVelocity: Float = 0f
        private set

    private var lastUserZoomTimeNs: Long = 0L
    private var lastUserTargetZoom: Float = 1.0f

    private var interpolationJob: Job? = null

    /**
     * Initializes state at camera startup.
     */
    fun initialize(initialLens: LensInfo?, initialZoom: Float) {
        interpolationJob?.cancel()
        activeLens = initialLens
        pendingLens = null
        isSwitchingFlag.set(false)
        val z = initialZoom.coerceIn(0.35f, 10.0f)
        currentDisplayedZoom = z
        userTargetZoom = z
        lastUserTargetZoom = z
        lastUserZoomTimeNs = System.nanoTime()
        zoomVelocity = 0f
    }

    /**
     * Called on user touch/drag/pinch/preset events to update the desired zoom level.
     * Continuously tracks velocity and direction.
     */
    fun onUserZoomInput(zoom: Float, isPresetTap: Boolean = false) {
        val nowNs = System.nanoTime()
        if (lastUserZoomTimeNs > 0L) {
            val dtSec = (nowNs - lastUserZoomTimeNs) / 1_000_000_000f
            if (dtSec in 0.002f..0.25f) {
                val instantaneousVelocity = (zoom - lastUserTargetZoom) / dtSec
                zoomVelocity = if (isPresetTap) {
                    val delta = zoom - currentDisplayedZoom
                    sign(delta) * 8.0f
                } else {
                    (zoomVelocity * 0.35f) + (instantaneousVelocity * 0.65f)
                }
            }
        }
        lastUserZoomTimeNs = nowNs
        lastUserTargetZoom = zoom
        userTargetZoom = zoom

        if (!isSwitching) {
            // No switch in progress:
            // Cancel any prior settling interpolation if user took manual control
            if (interpolationJob?.isActive == true && !isPresetTap) {
                interpolationJob?.cancel()
            }
            currentDisplayedZoom = zoom
            onApplyZoom(zoom)
            onZoomUpdated(zoom)
        } else {
            // Lens switch is currently loading (old camera still streaming frames):
            // Keep updating the old camera's digital zoom smoothly within its safe physical FOV range.
            val oldLens = activeLens
            if (oldLens != null) {
                val clampedOldZoom = when (oldLens.lensType) {
                    LensType.ULTRAWIDE -> zoom.coerceIn(oldLens.baseZoomRatio, 1.25f)
                    LensType.WIDE -> zoom.coerceIn(1.0f, 3.8f) // Main can digitally zoom up to ~3.8x while Telephoto opens
                    LensType.TELEPHOTO, LensType.TELEPHOTO_3X -> zoom.coerceAtLeast(oldLens.baseZoomRatio)
                    else -> zoom.coerceAtLeast(1.0f)
                }
                currentDisplayedZoom = clampedOldZoom
                onApplyZoom(clampedOldZoom)
                onZoomUpdated(clampedOldZoom)
            }
            Log.d(TAG, "Zoom updated during lens switch: userTarget=$zoom, oldLensDisplayed=$currentDisplayedZoom, velocity=$zoomVelocity")
        }
    }

    /**
     * Called when a lens switch operation begins.
     */
    fun onLensSwitchStarted(targetLens: LensInfo) {
        pendingLens = targetLens
        isSwitchingFlag.set(true)
        interpolationJob?.cancel()
        Log.i(TAG, "Lens switch started: ${activeLens?.lensType} -> ${targetLens.lensType}, displayedZoom=$currentDisplayedZoom, userTarget=$userTargetZoom")
    }

    /**
     * Calculates the FOV-equivalent zoom on [toLens] that matches the apparent FOV of [sourceFovZoom].
     *
     * Invariants:
     * - 1.0x is Main 1x FOV.
     * - UltraWide (e.g. 0.5x base) can display 1.0x FOV via 2.0x digital crop (1.0 / 0.5 = 2.0).
     * - Main (1.0x base) can display 3.0x FOV via 3.0x digital crop (3.0 / 1.0 = 3.0).
     * - Telephoto 3x (3.0x base) minimum optical FOV is 3.0x (1.0x crop).
     */
    fun calculateFovEquivalentZoom(
        sourceFovZoom: Float,
        fromLens: LensInfo?,
        toLens: LensInfo
    ): Float {
        val toBase = if (toLens.baseZoomRatio > 0.1f) toLens.baseZoomRatio else 1.0f
        return when (toLens.lensType) {
            LensType.ULTRAWIDE -> {
                // UltraWide base is ~0.5x. Can match any FOV from toBase upwards with digital crop.
                // If coming from Main at 1.0x, 1.0x FOV on UltraWide is 1.0x (crop = 1.0 / 0.5 = 2.0).
                sourceFovZoom.coerceIn(toBase, 10.0f)
            }
            LensType.TELEPHOTO, LensType.TELEPHOTO_3X -> {
                // Telephoto cannot zoom wider than its physical optical baseline (e.g. 3.0x).
                // If source was at 3.2x, starts at 3.2x. If source was at 2.5x, starts at toBase (3.0x).
                sourceFovZoom.coerceAtLeast(toBase).coerceIn(toBase, 10.0f)
            }
            LensType.WIDE -> {
                // Main lens: base is 1.0x.
                // If coming from UltraWide at 0.9x or 1.0x: starts at 1.0x.
                // If coming from Telephoto at 3.0x: starts at 3.0x (digital crop 3.0x on Main).
                sourceFovZoom.coerceAtLeast(1.0f).coerceIn(1.0f, 10.0f)
            }
            else -> sourceFovZoom.coerceAtLeast(1.0f)
        }
    }

    /**
     * Called when the target lens session/hardware is configured and ready to stream.
     *
     * 1. Applies the FOV-equivalent zoom on the new lens to match the current view.
     * 2. If the user's latest target zoom differs from the FOV-equivalent zoom,
     *    smoothly interpolates to the target zoom, preserving user velocity and direction.
     */
    fun onNewLensReady(newLens: LensInfo) {
        val oldLens = activeLens
        pendingLens = null
        activeLens = newLens
        isSwitchingFlag.set(false)

        val startZoom = calculateFovEquivalentZoom(currentDisplayedZoom, oldLens, newLens)
        currentDisplayedZoom = startZoom

        Log.i(TAG, "New lens ${newLens.lensType} ready. Starting at FOV-equivalent zoom: $startZoom (userTarget: $userTargetZoom, velocity: $zoomVelocity)")

        // Instantly apply FOV-equivalent zoom to the newly active camera session
        onApplyZoom(startZoom)
        onZoomUpdated(startZoom)

        // Interpolate smoothly toward the latest user target zoom if needed
        val target = userTargetZoom
        if (abs(target - startZoom) > 0.03f) {
            startSmoothInterpolation(startZoom, target, zoomVelocity)
        }
    }

    /**
     * Cancels any pending switch flag if camera opening or session configuration failed.
     */
    fun onSwitchFailed() {
        pendingLens = null
        isSwitchingFlag.set(false)
        interpolationJob?.cancel()
    }

    /**
     * Smoothly interpolates from [fromZoom] toward [targetZoom] at ~60fps,
     * honoring [initialVelocity] and dynamically tracking updates to [userTargetZoom].
     */
    private fun startSmoothInterpolation(
        fromZoom: Float,
        targetZoom: Float,
        initialVelocity: Float
    ) {
        interpolationJob?.cancel()
        interpolationJob = coroutineScope.launch {
            var current = fromZoom
            val initialDelta = targetZoom - fromZoom
            val initialDir = sign(initialDelta)

            // Determine interpolation speed in zoom units per second
            val userSpeed = abs(initialVelocity)
            val speed = if (userSpeed > MIN_SLEW_SPEED && (sign(initialVelocity) == initialDir || initialVelocity == 0f)) {
                userSpeed.coerceIn(MIN_SLEW_SPEED, MAX_SLEW_SPEED)
            } else {
                // Smooth ease over ~350-450ms
                (abs(initialDelta) / 0.4f).coerceIn(MIN_SLEW_SPEED, MAX_SLEW_SPEED)
            }

            val dt = FRAME_INTERVAL_MS / 1000f

            while (isActive) {
                delay(FRAME_INTERVAL_MS)

                // Dynamic target tracking: user may continue swiping during the interpolation!
                val currentTarget = userTargetZoom
                val remaining = currentTarget - current
                if (abs(remaining) <= 0.025f) {
                    current = currentTarget
                    currentDisplayedZoom = current
                    onApplyZoom(current)
                    onZoomUpdated(current)
                    Log.d(TAG, "Zoom continuity interpolation settled at target: $current")
                    break
                }

                val dir = sign(remaining)
                val maxStep = speed * dt
                val step = dir * minOf(abs(remaining), maxStep)
                current += step
                currentDisplayedZoom = current
                onApplyZoom(current)
                onZoomUpdated(current)
            }
        }
    }

    fun cancelInterpolation() {
        interpolationJob?.cancel()
    }
}
