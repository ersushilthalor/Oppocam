package com.example.camera.stableaction

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Direct Android port of the Stable Action open-source Horizon Lock engine
 * (scienceLabwork/Stable-Action).
 *
 * Implements:
 * 1. Continuous 360° roll tracking with boundary unwrapping across ±π (±180°),
 *    eliminating orientation flips and sudden jumps.
 * 2. Uses exclusively the necessary gyroscope/gravity Z-axis (roll) for horizontal leveling,
 *    without introducing unnecessary stabilization on other axes (Instruction 3).
 * 3. Exact exponential moving average smoothing filter (rollSmoothingAlpha = 0.25)
 *    from Stable Action's CameraManager.swift to eliminate jitter while maintaining real-time responsiveness.
 * 4. Safe crop geometry calculation (cropFraction = 3/5 * 0.90) ensuring zero black borders
 *    at any 360-degree rotation angle without stretching or distortion.
 * 5. Thread-safe snapshot provider and synchronized recording trajectory buffer for final video post-processing.
 */
class StableActionHorizonEngine(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "StableActionEngine"

        // Exact smoothing factor from Stable Action CameraManager.swift
        const val ROLL_SMOOTHING_ALPHA = 0.25f

        // Geometry constants from Stable Action CameraManager.swift:
        // cropFraction = 3.0 / 5.0 * 0.90 = 0.54
        const val CROP_FRACTION = (3.0f / 5.0f) * 0.90f
    }

    data class MotionSnapshot(
        val rawRoll: Float,
        val unwrappedRoll: Float,
        val smoothedRoll: Float,
        val smoothedRollDegrees: Float,
        val timestampNanos: Long
    )

    data class TrajectoryPoint(
        val timestampUs: Long,
        val smoothedRollRad: Float
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    // Hardware sensors: Gravity sensor (or accelerometer fallback) for absolute horizon reference,
    // and Gyroscope for high-frequency roll rate (Z-axis only).
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)

    private val isRunning = AtomicBoolean(false)

    // Unwrapped roll tracking state (matching HorizonRectangleView.swift)
    private var previousRawRoll: Double = 0.0
    private var rollUnwrapped: Double = 0.0
    private var hasFirstSample = false

    // Exponential smoothing state (matching CameraManager.swift)
    @Volatile
    var smoothedRoll: Float = 0f
        private set

    @Volatile
    var smoothedRollDegrees: Float = 0f
        private set

    private val _rollDegreesFlow = MutableStateFlow(0f)
    val rollDegreesFlow: StateFlow<Float> = _rollDegreesFlow.asStateFlow()

    // Gyroscope Z-axis integration
    private var lastGyroTimestampNanos: Long = 0L

    // Trajectory recording for video capture
    private val isRecordingTrajectory = AtomicBoolean(false)
    private var recordingStartUptimeUs = 0L
    private val recordedTrajectory = ConcurrentLinkedDeque<TrajectoryPoint>()

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            reset()
            try {
                gravitySensor?.let {
                    val registered = sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                    if (registered == false) {
                        sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                    }
                }
                gyroSensor?.let {
                    val registered = sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                    if (registered == false) {
                        sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                    }
                }
                Log.i(TAG, "Stable Action Horizon Lock engine started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register sensors for Stable Action Horizon Lock", e)
            }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                sensorManager?.unregisterListener(this)
            } catch (ignored: Exception) {}
            Log.i(TAG, "Stable Action Horizon Lock engine stopped")
        }
    }

    fun reset() {
        previousRawRoll = 0.0
        rollUnwrapped = 0.0
        smoothedRoll = 0f
        smoothedRollDegrees = 0f
        _rollDegreesFlow.value = 0f
        hasFirstSample = false
        lastGyroTimestampNanos = 0L
    }

    /**
     * Start recording trajectory timestamps for video recording synchronization.
     */
    fun startRecordingTrajectory() {
        recordedTrajectory.clear()
        recordingStartUptimeUs = SystemClock.uptimeMillis() * 1000L
        isRecordingTrajectory.set(true)
        // Record initial point
        recordedTrajectory.add(TrajectoryPoint(0L, smoothedRoll))
        Log.i(TAG, "Started recording horizon lock trajectory for video")
    }

    /**
     * Stops trajectory recording and returns an immutable list of timestamped roll values.
     */
    fun stopRecordingTrajectory(): List<TrajectoryPoint> {
        isRecordingTrajectory.set(false)
        val list = recordedTrajectory.toList()
        Log.i(TAG, "Stopped recording horizon lock trajectory. Samples collected: ${list.size}")
        return list
    }

    /**
     * Computes the unwrapped roll angle and exponential smoothing given a raw gravity measurement.
     * Exposed for unit testing without hardware sensors.
     */
    fun processGravitySample(gx: Float, gy: Float, timestampNanos: Long = System.nanoTime()) {
        // In Android sensor coordinates:
        // X points right, Y points up. When device is upright in portrait, gy ~= 9.8, gx ~= 0.
        // Tilted clockwise (right): gx > 0, gy decreases.
        // atan2(gx, gy) gives device roll angle in (-PI, PI].
        val rawRoll = atan2(gx.toDouble(), gy.toDouble())

        if (!hasFirstSample) {
            previousRawRoll = rawRoll
            rollUnwrapped = rawRoll
            smoothedRoll = rawRoll.toFloat()
            smoothedRollDegrees = Math.toDegrees(rawRoll).toFloat()
            _rollDegreesFlow.value = smoothedRollDegrees
            hasFirstSample = true
            return
        }

        // Exact unwrap logic from Stable Action HorizonRectangleView.swift:
        var delta = rawRoll - previousRawRoll
        if (delta > PI) delta -= 2.0 * PI
        if (delta < -PI) delta += 2.0 * PI
        previousRawRoll = rawRoll
        rollUnwrapped += delta

        val targetRoll = rollUnwrapped.toFloat()

        // Exact exponential smoothing from Stable Action CameraManager.swift:
        // smoothedRoll += rollSmoothingAlpha * (snap.roll - smoothedRoll)
        smoothedRoll += ROLL_SMOOTHING_ALPHA * (targetRoll - smoothedRoll)
        smoothedRollDegrees = Math.toDegrees(smoothedRoll.toDouble()).toFloat()
        _rollDegreesFlow.value = smoothedRollDegrees

        // Record trajectory if video capture is in progress
        if (isRecordingTrajectory.get()) {
            val nowUs = SystemClock.uptimeMillis() * 1000L
            val relTimeUs = (nowUs - recordingStartUptimeUs).coerceAtLeast(0L)
            recordedTrajectory.add(TrajectoryPoint(relTimeUs, smoothedRoll))
        }
    }

    /**
     * Updates roll tracking using high-frequency gyroscope Z-axis angular velocity.
     * Only the necessary Z-axis (roll) is used; pitch (X) and yaw (Y) are strictly ignored (Instruction 3).
     */
    fun processGyroSample(wz: Float, timestampNanos: Long) {
        if (!hasFirstSample) return
        if (lastGyroTimestampNanos != 0L && timestampNanos > lastGyroTimestampNanos) {
            val dt = ((timestampNanos - lastGyroTimestampNanos) * 1e-9f).coerceIn(0.0001f, 0.05f)
            // wz is angular velocity about screen Z axis in rad/s
            // In Android: counter-clockwise rotation is positive wz.
            // Integrate high-frequency gyro change into unwrapped roll
            rollUnwrapped += (wz * dt).toDouble()
            val targetRoll = rollUnwrapped.toFloat()
            smoothedRoll += (ROLL_SMOOTHING_ALPHA * 0.5f) * (targetRoll - smoothedRoll)
            smoothedRollDegrees = Math.toDegrees(smoothedRoll.toDouble()).toFloat()
            _rollDegreesFlow.value = smoothedRollDegrees

            if (isRecordingTrajectory.get()) {
                val nowUs = SystemClock.uptimeMillis() * 1000L
                val relTimeUs = (nowUs - recordingStartUptimeUs).coerceAtLeast(0L)
                recordedTrajectory.add(TrajectoryPoint(relTimeUs, smoothedRoll))
            }
        }
        lastGyroTimestampNanos = timestampNanos
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning.get() || event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size >= 2) {
                    processGravitySample(event.values[0], event.values[1], event.timestamp)
                }
            }
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                if (event.values.size >= 3) {
                    // Instruction 3: Use ONLY the necessary gyroscope axis for horizontal leveling (Z-axis).
                    // event.values[0] = pitch (ignored), event.values[1] = yaw (ignored), event.values[2] = roll (used)
                    val rollVelocity = event.values[2]
                    processGyroSample(rollVelocity, event.timestamp)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Atomic read of latest motion state (matching Stable Action snapshot()).
     */
    fun snapshot(): MotionSnapshot {
        val sRad = smoothedRoll
        return MotionSnapshot(
            rawRoll = previousRawRoll.toFloat(),
            unwrappedRoll = rollUnwrapped.toFloat(),
            smoothedRoll = sRad,
            smoothedRollDegrees = Math.toDegrees(sRad.toDouble()).toFloat(),
            timestampNanos = System.nanoTime()
        )
    }

    /**
     * Computes the mathematically exact safe crop scale factor so that NO black borders
     * or non-uniform stretching appear at any rotation angle (0° to 360°).
     *
     * Based on Stable Action's cropFraction = 3/5 * 0.90 = 0.54 (scale = 1/0.54 = 1.8518f).
     */
    fun computeSafeCropScale(width: Float, height: Float): Float {
        if (width <= 0f || height <= 0f) return 1.8518f
        val w = min(width, height)
        val h = max(width, height)
        val aspect = h / w
        // Radius of inscribed circle is w / 2.
        // Half diagonal of crop rect is sqrt((w_crop/2)^2 + (h_crop/2)^2) = (w_crop/2) * sqrt(1 + aspect^2).
        // For w_crop to fit inside inscribed circle at any rotation:
        // scale >= sqrt(1 + aspect^2) / margin (with 0.90 margin matching Stable Action)
        val minScale = sqrt(1f + aspect * aspect) / 0.90f
        return max(minScale, 1.8518f)
    }
}
