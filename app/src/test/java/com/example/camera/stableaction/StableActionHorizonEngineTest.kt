package com.example.camera.stableaction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StableActionHorizonEngineTest {

    private lateinit var context: Context
    private lateinit var engine: StableActionHorizonEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = StableActionHorizonEngine(context)
        engine.reset()
    }

    @Test
    fun testStableActionConstants() {
        // Strict adherence to Stable Action parameters
        assertEquals(0.25f, StableActionHorizonEngine.ROLL_SMOOTHING_ALPHA, 0.001f)
        assertEquals(0.54f, StableActionHorizonEngine.CROP_FRACTION, 0.001f)
    }

    @Test
    fun testUprightPortraitRollCalculation() {
        // Upright in portrait mode: gx = 0, gy = 9.8f
        engine.processGravitySample(0f, 9.8f, 1000L)
        val snapshot = engine.snapshot()
        assertEquals(0f, snapshot.smoothedRoll, 0.01f)
        assertEquals(0f, snapshot.smoothedRollDegrees, 0.1f)
    }

    @Test
    fun testClockwiseTiltRollCalculation() {
        // Device tilted 45° clockwise: gx = 6.93f, gy = 6.93f
        engine.processGravitySample(6.93f, 6.93f, 1000L)
        val snapshot = engine.snapshot()
        assertEquals(PI.toFloat() / 4f, snapshot.smoothedRoll, 0.05f)
        assertEquals(45f, snapshot.smoothedRollDegrees, 2.5f)
    }

    @Test
    fun testContinuous360UnwrappingAcrossBoundary() {
        // Start near +179°
        val rad179 = Math.toRadians(179.0).toFloat()
        val gx1 = kotlin.math.sin(rad179) * 9.8f
        val gy1 = kotlin.math.cos(rad179) * 9.8f
        engine.processGravitySample(gx1, gy1, 1000L)

        // Cross boundary to -179° (+181°)
        val rad181 = Math.toRadians(-179.0).toFloat()
        val gx2 = kotlin.math.sin(rad181) * 9.8f
        val gy2 = kotlin.math.cos(rad181) * 9.8f
        engine.processGravitySample(gx2, gy2, 2000L)

        val snapshot = engine.snapshot()
        // Unwrapped roll should continuously advance to ~180°+ without flipping by -360°
        val deg = snapshot.unwrappedRoll * 180f / PI.toFloat()
        assertTrue("Unwrapped roll should not flip sign at 180° boundary, was: $deg", deg > 150f)
    }

    @Test
    fun testGyroZAxisIntegration() {
        // Initialize upright
        engine.processGravitySample(0f, 9.8f, 1000L)
        assertEquals(0f, engine.smoothedRoll, 0.01f)

        // Process gyro Z-axis angular velocity (roll rate = 1.0 rad/s over 20ms)
        engine.processGyroSample(1.0f, 1000L)
        engine.processGyroSample(1.0f, 1000L + 20_000_000L) // 20ms later

        val snapshot = engine.snapshot()
        assertTrue("Gyro Z-axis roll should update roll angle", snapshot.smoothedRoll > 0f)
    }

    @Test
    fun testSafeCropScaleGeometry() {
        // 16:9 aspect ratio
        val scale16_9 = engine.computeSafeCropScale(1080f, 1920f)
        assertTrue("Crop scale for 16:9 must be at least 1.85x to prevent black borders", scale16_9 >= 1.85f)

        // 4:3 aspect ratio
        val scale4_3 = engine.computeSafeCropScale(1200f, 1600f)
        assertTrue("Crop scale for 4:3 must be at least 1.85x", scale4_3 >= 1.85f)
    }

    @Test
    fun testTrajectoryRecording() {
        engine.startRecordingTrajectory()
        engine.processGravitySample(0f, 9.8f, 1000L)
        engine.processGravitySample(2f, 9.5f, 2000L)
        val trajectory = engine.stopRecordingTrajectory()

        assertTrue("Trajectory points should be recorded", trajectory.isNotEmpty())
        assertEquals(0L, trajectory.first().timestampUs)
        assertTrue("Trajectory should contain at least 2 points (initial and terminal anchor)", trajectory.size >= 2)
    }

    @Test
    fun testIsTrajectoryRecordingFlag() {
        assertFalse(engine.isTrajectoryRecording())
        engine.startRecordingTrajectory()
        assertTrue(engine.isTrajectoryRecording())
        engine.stopRecordingTrajectory()
        assertFalse(engine.isTrajectoryRecording())
    }

    @Test
    fun testLinearAccelerationTranslationTracking() {
        // Lateral jerk to the right (ax = 1.0 m/s^2)
        engine.processLinearAcceleration(1.0f, 0.0f, 1.0 / 120.0, 1000L)
        val snapshot = engine.snapshot()
        // Shifting right moves offset in compensation direction
        assertNotEquals(0f, snapshot.normX, 0.0001f)
    }
}
