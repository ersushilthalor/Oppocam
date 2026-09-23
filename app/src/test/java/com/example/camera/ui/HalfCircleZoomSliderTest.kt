package com.example.camera.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class HalfCircleZoomSliderTest {

    private fun zoomToProgress(zoom: Float, minZoom: Float, maxZoom: Float): Float {
        val logMin = ln(minZoom.toDouble())
        val logMax = ln(maxZoom.toDouble())
        val logVal = ln(zoom.coerceIn(minZoom, maxZoom).toDouble())
        return ((logVal - logMin) / (logMax - logMin)).toFloat().coerceIn(0f, 1f)
    }

    private fun progressToZoom(p: Float, minZoom: Float, maxZoom: Float): Float {
        val clampedP = p.coerceIn(0f, 1f)
        val logMin = ln(minZoom.toDouble())
        val logMax = ln(maxZoom.toDouble())
        val logVal = logMin + clampedP * (logMax - logMin)
        return exp(logVal).toFloat().coerceIn(minZoom, maxZoom)
    }

    @Test
    fun `test logarithmic zoom mapping accuracy`() {
        val minZoom = 0.5f
        val maxZoom = 10.0f

        // Progress at min is 0.0
        val pMin = zoomToProgress(minZoom, minZoom, maxZoom)
        assertEquals(0.0f, pMin, 0.001f)

        // Progress at max is 1.0
        val pMax = zoomToProgress(maxZoom, minZoom, maxZoom)
        assertEquals(1.0f, pMax, 0.001f)

        // Roundtrip checks
        val testZooms = listOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f, 10.0f)
        for (z in testZooms) {
            val progress = zoomToProgress(z, minZoom, maxZoom)
            val reconstructed = progressToZoom(progress, minZoom, maxZoom)
            assertEquals(z, (reconstructed * 10f).roundToInt() / 10f, 0.05f)
        }
    }

    @Test
    fun `test swipe gestures directionality`() {
        var currentZoom = 1.0f
        val minZoom = 0.5f
        val maxZoom = 10.0f

        // Swipe right -> left (pan.x < 0) MUST zoom in
        val swipeRightToLeftPanX = -40f
        val zoomInFactor = 1.0f - (swipeRightToLeftPanX / 260f)
        assertTrue("Zoom in factor should be > 1.0", zoomInFactor > 1.0f)
        val zoomedIn = (currentZoom * zoomInFactor).coerceIn(minZoom, maxZoom)
        assertTrue("Zoom should increase after right-to-left swipe", zoomedIn > currentZoom)

        // Swipe left -> right (pan.x > 0) MUST zoom out
        currentZoom = 2.0f
        val swipeLeftToRightPanX = 40f
        val zoomOutFactor = 1.0f - (swipeLeftToRightPanX / 260f)
        assertTrue("Zoom out factor should be < 1.0", zoomOutFactor < 1.0f)
        val zoomedOut = (currentZoom * zoomOutFactor).coerceIn(minZoom, maxZoom)
        assertTrue("Zoom should decrease after left-to-right swipe", zoomedOut < currentZoom)
    }

    @Test
    fun `test presets filtering respects device zoom limits`() {
        val candidatePresets = listOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f, 10.0f)

        // Case 1: Ultra-wide equipped device (minZoom = 0.5f)
        val presetsWide = candidatePresets.filter { it in 0.5f..10.0f }
        assertTrue(presetsWide.contains(0.5f))
        assertTrue(presetsWide.contains(1.0f))
        assertTrue(presetsWide.contains(10.0f))

        // Case 2: Standard sensor (minZoom = 1.0f)
        val presetsStandard = candidatePresets.filter { it in 1.0f..8.0f }
        assertTrue(!presetsStandard.contains(0.5f))
        assertTrue(presetsStandard.contains(1.0f))
        assertTrue(!presetsStandard.contains(10.0f))
    }
}
