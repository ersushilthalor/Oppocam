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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Android implementation of the Stable Action Horizontal Lock engine
 * directly ported from iOS Stable Action (scienceLabwork/Stable-Action).
 *
 * Implements:
 * 1. Continuous 360° roll tracking with boundary unwrapping across ±π (±180°),
 *    eliminating orientation flips and sudden jumps.
 * 2. High-precision gravity sensor reference and complementary gyroscope Z-axis roll
 *    rate integration with correct Android sensor coordinates and sign conventions.
 * 3. Exact exponential moving average smoothing filter (rollSmoothingAlpha = 0.25)
 *    from Stable Action CameraManager.swift to eliminate jitter while maintaining real-time responsiveness.
 * 4. User acceleration translation tracking (linear acceleration) with velocity decay (0.82),
 *    position decay (0.992), and translation smoothing (0.10) for gimbal-like lateral stabilization.
 * 5. Safe crop geometry calculation (cropFraction = 3/5 * 0.90 = 0.54) ensuring zero black borders
 *    at any 360-degree rotation angle without stretching or distortion.
 * 6. High-precision synchronized recording trajectory buffer for final video post-processing.
 */
class StableActionHorizonEngine(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "StableActionEngine"

        // Exact smoothing factor from Stable Action CameraManager.swift
        const val ROLL_SMOOTHING_ALPHA = 0.25f
        const val TRANSLATION_SMOOTHING_ALPHA = 0.10f

        // Geometry constants from Stable Action CameraManager.swift:
        // cropFraction = 3.0 / 5.0 * 0.90 = 0.54
        const val CROP_FRACTION = (3.0f / 5.0f) * 0.90f

        // Physics constants from Stable Action HorizonRectangleView.swift
        const val DEFAULT_DT = 1.0 / 120.0
        const val VELOCITY_DECAY = 0.82
        const val POSITION_DECAY = 0.992
        const val SENSITIVITY = 0.035
        const val ACCEL_DEAD_ZONE = 0.02
    }

    data class MotionSnapshot(
        val rawRoll: Float,
        val unwrappedRoll: Float,
        val smoothedRoll: Float,
        val smoothedRollDegrees: Float,
        val normX: Float,
        val normY: Float,
        val timestampNanos: Long
    )

    data class TrajectoryPoint(
        val timestampUs: Long,
        val smoothedRollRad: Float,
        val normX: Float = 0f,
        val normY: Float = 0f
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    // Hardware sensors
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linearAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)

    private val isRunning = AtomicBoolean(false)

    // Unwrapped roll tracking state (matching HorizonRectangleView.swift)
    private var previousRawRoll: Double = 0.0
    private var rollUnwrapped: Double = 0.0
    private var hasFirstSample = false

    // Lateral translation accumulators (matching HorizonRectangleView.swift)
    private var velX: Double = 0.0
    private var velY: Double = 0.0
    private var offsetX: Double = 0.0
    private var offsetY: Double = 0.0

    // Exponential smoothing state (matching CameraManager.swift)
    @Volatile
    var smoothedRoll: Float = 0f
        private set

    @Volatile
    var smoothedRollDegrees: Float = 0f
        private set

    @Volatile
    var smoothedNormX: Float = 0f
        private set

    @Volatile
    var smoothedNormY: Float = 0f
        private set

    private val _rollDegreesFlow = MutableStateFlow(0f)
    val rollDegreesFlow: StateFlow<Float> = _rollDegreesFlow.asStateFlow()

    private val _motionOffsetFlow = MutableStateFlow(Pair(0f, 0f))
    val motionOffsetFlow: StateFlow<Pair<Float, Float>> = _motionOffsetFlow.asStateFlow()

    // Gyroscope tracking
    private var lastGyroTimestampNanos: Long = 0L
    private var lastAccelTimestampNanos: Long = 0L

    // Trajectory recording for video capture
    private val isRecordingTrajectory = AtomicBoolean(false)
    private var recordingStartNanos = 0L
    private val recordedTrajectory = ConcurrentLinkedDeque<TrajectoryPoint>()

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            reset()
            try {
                gravitySensor?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
                linearAccelSensor?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
                gyroSensor?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
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
        velX = 0.0
        velY = 0.0
        offsetX = 0.0
        offsetY = 0.0
        smoothedRoll = 0f
        smoothedRollDegrees = 0f
        smoothedNormX = 0f
        smoothedNormY = 0f
        _rollDegreesFlow.value = 0f
        _motionOffsetFlow.value = Pair(0f, 0f)
        hasFirstSample = false
        lastGyroTimestampNanos = 0L
        lastAccelTimestampNanos = 0L
    }

    /**
     * Start recording trajectory timestamps for video recording synchronization.
     */
    fun startRecordingTrajectory() {
        recordedTrajectory.clear()
        recordingStartNanos = SystemClock.elapsedRealtimeNanos()
        isRecordingTrajectory.set(true)
        // Record initial anchor point at 0 us
        recordedTrajectory.add(TrajectoryPoint(0L, smoothedRoll, smoothedNormX, smoothedNormY))
        Log.i(TAG, "Started recording horizon lock trajectory for video")
    }

    /**
     * Stops trajectory recording and returns an immutable list of timestamped roll and translation values.
     */
    fun stopRecordingTrajectory(): List<TrajectoryPoint> {
        isRecordingTrajectory.set(false)
        val list = recordedTrajectory.toList()
        Log.i(TAG, "Stopped recording horizon lock trajectory. Samples collected: ${list.size}")
        return list
    }

    /**
     * Computes continuous unwrapped roll angle and exponential smoothing given a raw gravity measurement.
     * In Android sensor coordinates:
     * - When device is upright in portrait: gx ~= 0, gy ~= 9.8.
     * - When device tilts clockwise (right): gx increases (>0), gy decreases.
     * - atan2(gx, gy) computes device roll angle in (-π, π].
     */
    fun processGravitySample(gx: Float, gy: Float, timestampNanos: Long = System.nanoTime()) {
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

        // Exact 360° unwrap logic from Stable Action HorizonRectangleView.swift
        var delta = rawRoll - previousRawRoll
        if (delta > PI) delta -= 2.0 * PI
        if (delta < -PI) delta += 2.0 * PI
        previousRawRoll = rawRoll
        rollUnwrapped += delta

        val targetRoll = rollUnwrapped.toFloat()

        // Exact exponential smoothing from Stable Action CameraManager.swift
        smoothedRoll += ROLL_SMOOTHING_ALPHA * (targetRoll - smoothedRoll)
        smoothedRollDegrees = Math.toDegrees(smoothedRoll.toDouble()).toFloat()
        _rollDegreesFlow.value = smoothedRollDegrees

        recordCurrentTrajectorySample(timestampNanos)
    }

    /**
     * Updates lateral translation tracking using user linear acceleration (in m/s²).
     * Implements Stable Action translation stabilization (velocity & position decay).
     */
    fun processLinearAcceleration(axIn: Float, ayIn: Float, dtSeconds: Double = DEFAULT_DT, timestampNanos: Long = System.nanoTime()) {
        var ax = axIn.toDouble()
        var ay = ayIn.toDouble()

        // Dead-zone: eliminate micro-vibrations
        if (abs(ax) < ACCEL_DEAD_ZONE) ax = 0.0
        if (abs(ay) < ACCEL_DEAD_ZONE) ay = 0.0

        // Integrate acceleration -> velocity, then decay
        velX = (velX + ax * dtSeconds) * VELOCITY_DECAY
        velY = (velY + ay * dtSeconds) * VELOCITY_DECAY

        // Integrate velocity -> offset, then decay toward centre
        // Negate: if device moves right we shift crop left to compensate
        val newOffX = (offsetX - velX * SENSITIVITY) * POSITION_DECAY
        val newOffY = (offsetY - velY * SENSITIVITY) * POSITION_DECAY

        offsetX = newOffX.coerceIn(-1.0, 1.0)
        offsetY = newOffY.coerceIn(-1.0, 1.0)

        // Exponential smoothing
        smoothedNormX += TRANSLATION_SMOOTHING_ALPHA * (offsetX.toFloat() - smoothedNormX)
        smoothedNormY += TRANSLATION_SMOOTHING_ALPHA * (offsetY.toFloat() - smoothedNormY)
        _motionOffsetFlow.value = Pair(smoothedNormX, smoothedNormY)

        recordCurrentTrajectorySample(timestampNanos)
    }

    /**
     * High-frequency gyroscope Z-axis roll assistance.
     * In Android sensor coordinate system:
     * - +Z axis points out of screen towards user.
     * - Counter-clockwise rotation has wz > 0.
     * - Clockwise rotation (which increases roll angle) has wz < 0.
     * Hence, d(roll)/dt = -wz.
     */
    fun processGyroSample(wz: Float, timestampNanos: Long) {
        if (!hasFirstSample) return
        if (lastGyroTimestampNanos != 0L && timestampNanos > lastGyroTimestampNanos) {
            val dt = ((timestampNanos - lastGyroTimestampNanos) * 1e-9).coerceIn(0.0005, 0.05)
            // wz in rad/s, negate to match roll convention
            val gyroDelta = (-wz.toDouble() * dt)
            rollUnwrapped += gyroDelta * 0.15 // Fused complementary assist
            val targetRoll = rollUnwrapped.toFloat()
            smoothedRoll += (ROLL_SMOOTHING_ALPHA * 0.35f) * (targetRoll - smoothedRoll)
            smoothedRollDegrees = Math.toDegrees(smoothedRoll.toDouble()).toFloat()
            _rollDegreesFlow.value = smoothedRollDegrees

            recordCurrentTrajectorySample(timestampNanos)
        }
        lastGyroTimestampNanos = timestampNanos
    }

    private fun recordCurrentTrajectorySample(timestampNanos: Long) {
        if (isRecordingTrajectory.get()) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val relTimeUs = ((nowNs - recordingStartNanos) / 1000L).coerceAtLeast(0L)
            recordedTrajectory.add(TrajectoryPoint(relTimeUs, smoothedRoll, smoothedNormX, smoothedNormY))
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning.get() || event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                if (event.values.size >= 2) {
                    processGravitySample(event.values[0], event.values[1], event.timestamp)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (gravitySensor?.type == Sensor.TYPE_ACCELEROMETER && event.values.size >= 2) {
                    processGravitySample(event.values[0], event.values[1], event.timestamp)
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (event.values.size >= 2) {
                    val dt = if (lastAccelTimestampNanos != 0L && event.timestamp > lastAccelTimestampNanos) {
                        ((event.timestamp - lastAccelTimestampNanos) * 1e-9).coerceIn(0.001, 0.05)
                    } else DEFAULT_DT
                    lastAccelTimestampNanos = event.timestamp
                    processLinearAcceleration(event.values[0], event.values[1], dt, event.timestamp)
                }
            }
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                if (event.values.size >= 3) {
                    // Only Z-axis (roll) is used for horizon lock
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
            normX = smoothedNormX,
            normY = smoothedNormY,
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
        val minScale = sqrt(1f + aspect * aspect) / 0.90f
        return max(minScale, 1.8518f)
    }
}
