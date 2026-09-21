package com.example.camera.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Rec2020AutoToneEngineTest {

    private lateinit var engine: Rec2020AutoToneEngine

    @Before
    fun setUp() {
        engine = Rec2020AutoToneEngine()
    }

    @Test
    fun testInitialParamsAreValid() {
        val params = engine.currentParams.value
        assertEquals(0.0f, params.exposure, 0.01f)
        assertEquals(0.45f, params.highlights, 0.01f)
        assertEquals(0.30f, params.shadows, 0.01f)
        assertEquals(0.0f, params.contrast, 0.01f)
        assertEquals(0.55f, params.fadeout, 0.01f)
    }

    @Test
    fun testBrightSkyAndDarkForegroundSceneBalancing() {
        // Simulate high dynamic range daylight scene with bright sky
        // ev100 = 14.5f represents intense sunny outdoor daylight
        for (i in 0 until 60) {
            engine.processSceneIllumination(
                ev100 = 14.5f,
                hasFace = true,
                maxFaceArea = 120_000
            )
        }

        val params = engine.currentParams.value

        // Scene should protect highlights smoothly
        assertTrue("Highlights should provide strong roll-off shoulder for bright sky", params.highlights > 0.45f)

        // Scene should lift shadows to preserve foreground subject detail
        assertTrue("Shadows should be lifted to protect foreground detail", params.shadows > 0.35f)

        // Scene should NOT aggressively darken the whole scene to save the sky (exposure should stay healthy)
        assertTrue("Exposure must not be crushed to save sky; midtones remain balanced", params.exposure >= 0.0f)

        // Fadeout should remain active to avoid milky shadow pedestal
        assertTrue("Fadeout should keep inky blacks", params.fadeout >= 0.40f)
    }

    @Test
    fun testSmoothTemporalSmoothingPreventsFlicker() {
        val initialExposure = engine.currentParams.value.exposure

        // Single sudden frame spike in brightness
        engine.processSceneIllumination(ev100 = 15.0f)

        val immediateExposure = engine.currentParams.value.exposure
        val diff = kotlin.math.abs(immediateExposure - initialExposure)

        // Due to smoothing (IIR alpha), frame-to-frame change should be smooth and gradual, not jumping instantly
        assertTrue("Frame-to-frame jump should be limited by temporal smoothing", diff < 0.10f)
    }

    @Test
    fun testTransferFunctionMonotonicityAndPeakWhite() {
        val params = Rec2020AutoToneParams(
            exposure = 0.05f,
            highlights = 0.65f,
            shadows = 0.45f,
            contrast = -0.05f,
            fadeout = 0.55f
        )

        // Black floor must be inky black (zero)
        val yBlack = engine.evaluateTransferFunction(0.0f, params)
        assertEquals("Black floor at x=0 must be 0.0", 0.0f, yBlack, 0.001f)

        // Peak white must be preserved at 1.0 (never clamped to dull gray)
        val yWhite = engine.evaluateTransferFunction(1.0f, params)
        assertEquals("Peak white at x=1.0 must be 1.0", 1.0f, yWhite, 0.001f)

        // Verify monotonic non-decreasing across whole dynamic range
        var prevY = 0.0f
        for (i in 1..100) {
            val x = i / 100.0f
            val y = engine.evaluateTransferFunction(x, params)
            assertTrue("Curve must be monotonically non-decreasing at x=$x", y >= prevY - 0.0001f)
            prevY = y
        }
    }
}
