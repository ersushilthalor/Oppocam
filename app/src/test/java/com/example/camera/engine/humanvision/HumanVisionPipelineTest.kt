package com.example.camera.engine.humanvision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanVisionPipelineTest {

    @Test
    fun testHumanVisionConfigDefaults() {
        val config = HumanVisionConfig()
        assertTrue("Perspective acuity should be between 0.10 and 0.35", config.perspectiveAcuity in 0.10f..0.35f)
        assertTrue("Tile overlap ratio must provide sufficient margin for stitching", config.tileOverlapRatio in 0.30f..0.45f)
        assertTrue("Moving object protection must be enabled", config.movingObjectProtection)
        assertTrue("HDR burst denoise must be enabled", config.hdrDenoiseBurst)
        assertTrue("Distant detail boost should be positive", config.distantDetailBoost > 0.5f)
        assertEquals("Max distant tiles should default to 3", 3, config.maxDistantTiles)
    }

    @Test
    fun testDepthZoneClassification() {
        // Foreground: [0.0, 0.25)
        assertEquals(SceneDepthZone.NEAR, SceneDepthZone.fromDepth(0.10f))
        assertEquals(SceneDepthZone.NEAR, SceneDepthZone.fromDepth(0.0f))

        // Midground: [0.25, 0.55)
        assertEquals(SceneDepthZone.MID, SceneDepthZone.fromDepth(0.25f))
        assertEquals(SceneDepthZone.MID, SceneDepthZone.fromDepth(0.40f))

        // Far: [0.55, 0.80)
        assertEquals(SceneDepthZone.FAR, SceneDepthZone.fromDepth(0.55f))
        assertEquals(SceneDepthZone.FAR, SceneDepthZone.fromDepth(0.70f))

        // Very Far: [0.80, 1.0]
        assertEquals(SceneDepthZone.VERY_FAR, SceneDepthZone.fromDepth(0.80f))
        assertEquals(SceneDepthZone.VERY_FAR, SceneDepthZone.fromDepth(1.0f))

        // Clamping check for out-of-bounds
        assertEquals(SceneDepthZone.NEAR, SceneDepthZone.fromDepth(-0.5f))
        assertEquals(SceneDepthZone.VERY_FAR, SceneDepthZone.fromDepth(1.5f))
    }

    @Test
    fun testDepthFieldZoneLookup() {
        val width = 4
        val height = 4
        val depthMap = FloatArray(16) { i ->
            // Top row: 0.9 (VERY_FAR), Bottom row: 0.1 (NEAR)
            val row = i / 4
            when (row) {
                0 -> 0.90f
                1 -> 0.65f
                2 -> 0.35f
                else -> 0.10f
            }
        }
        val emptyWeights = FloatArray(16)
        val depthField = HumanVisionDepthEngine.DepthField(
            width = width,
            height = height,
            depthMap = depthMap,
            zoneIndices = ByteArray(16),
            nearWeights = emptyWeights,
            midWeights = emptyWeights,
            farWeights = emptyWeights,
            veryFarWeights = emptyWeights
        )

        // Top should be VERY_FAR
        assertEquals(SceneDepthZone.VERY_FAR, depthField.getZoneAt(0.5f, 0.05f))
        // Bottom should be NEAR
        assertEquals(SceneDepthZone.NEAR, depthField.getZoneAt(0.5f, 0.95f))
    }
}

