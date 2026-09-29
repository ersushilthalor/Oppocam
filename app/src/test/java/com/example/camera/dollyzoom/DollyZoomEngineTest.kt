package com.example.camera.dollyzoom

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DollyZoomEngineTest {

    private lateinit var engine: DollyZoomEngine

    @Before
    fun setUp() {
        engine = DollyZoomEngine()
    }

    @Test
    fun testLerpFormula() {
        // Frame.py: def lerp(a, b, c): return int((c * a) + ((1 - c) * b))
        val a = 100f
        val b = 200f
        val c = 0.4f
        val expected = (0.4f * 100f) + (0.6f * 200f) // 40 + 120 = 160
        assertEquals(expected, DollyBoundingBox.lerp(a, b, c), 0.001f)
    }

    @Test
    fun testLargestBoxSelection() {
        // Frame.py: largestBox selects the box with maximum width (box[2])
        val boxes = listOf(
            DollyBoundingBox(0.1f, 0.1f, 0.2f, 0.25f),
            DollyBoundingBox(0.3f, 0.3f, 0.45f, 0.50f),
            DollyBoundingBox(0.5f, 0.5f, 0.35f, 0.40f)
        )
        val largest = DollyBoundingBox.largestBox(boxes)
        assertNotNull(largest)
        assertEquals(0.45f, largest!!.w, 0.001f)
    }

    @Test
    fun testLerpShapeUpdatesDimensions() {
        // Frame.py: lerpShape updates pos with c=0.4, size with c=0.7
        val current = DollyBoundingBox(100f, 100f, 50f, 50f)
        val target = DollyBoundingBox(200f, 200f, 100f, 100f)

        current.lerpShape(target)

        // pos = 0.4 * 100 + 0.6 * 200 = 160
        assertEquals(160f, current.x, 0.001f)
        assertEquals(160f, current.y, 0.001f)

        // size = 0.7 * 50 + 0.3 * 100 = 35 + 30 = 65
        assertEquals(65f, current.w, 0.001f)
        assertEquals(65f, current.h, 0.001f)
    }

    @Test
    fun testGeometricDollyZoomHoldsSubjectSizeConstant() {
        engine.start()
        engine.setZoom(0.25f) // Reference repo ZOOM = 0.25

        // Simulate subject far away (small face box: 15% of screen width)
        engine.lockSubjectAt(0.5f, 0.5f)
        val farState = engine.cropStateFlow.value
        assertTrue("Far subject triggers zoom-in scale", farState.scaleFactor > 1.0f)

        // Post-filter box width should approach constant ZOOM = 0.25 of screen width
        val postFilterW = farState.postFilterBoxNorm.width()
        assertEquals(0.25f, postFilterW, 0.05f)

        engine.stop()
    }

    @Test
    fun testTrajectoryRecordingDuringVideo() {
        engine.start()
        engine.startRecordingTrajectory()

        engine.lockSubjectAt(0.5f, 0.5f)
        engine.updateCalculations(1080f, 1920f)

        val trajectory = engine.stopRecordingTrajectory()
        assertTrue("Trajectory points must be captured during recording", trajectory.isNotEmpty())
        assertTrue("Scale factor must be >= 1.0", trajectory.first().scaleFactor >= 1.0f)
        assertTrue("Crop width must be > 0", trajectory.first().cropWidth > 0f)

        engine.stop()
    }
}
