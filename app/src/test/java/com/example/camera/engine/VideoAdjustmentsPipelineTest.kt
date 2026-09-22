package com.example.camera.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.camera.data.CameraPreferences
import com.example.camera.model.VideoAdjustments
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoAdjustmentsPipelineTest {

    private lateinit var context: Context
    private lateinit var preferences: CameraPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = CameraPreferences(context)
    }

    @Test
    fun testDefaultVideoAdjustments() {
        val adjustments = VideoAdjustments()
        assertTrue("Default adjustments should report isDefault = true", adjustments.isDefault)
        assertFalse("Default adjustments should not have spatial effects", VideoAdjustmentsPipeline.hasSpatialEffects(adjustments))
        val matrix = VideoAdjustmentsPipeline.computeColorMatrix(adjustments)
        assertNull("Default adjustments should return null ColorMatrix to skip redundant grading", matrix)
    }

    @Test
    fun testColorMatrixComputationOnTonalityChange() {
        val adjustments = VideoAdjustments(tonality = 20f)
        assertFalse(adjustments.isDefault)
        val matrix = VideoAdjustmentsPipeline.computeColorMatrix(adjustments)
        assertNotNull("Non-default tonality must produce a ColorMatrix", matrix)
        val arr = matrix!!.array
        assertEquals(20, arr.size)
        // Offset for RGB should be positive (scaled by 255f * 0.4f * (20/100))
        assertTrue("Red offset should be positive for positive tonality", arr[4] > 0f)
        assertTrue("Green offset should be positive for positive tonality", arr[9] > 0f)
        assertTrue("Blue offset should be positive for positive tonality", arr[14] > 0f)
    }

    @Test
    fun testColorMatrixComputationOnContrastAndSaturation() {
        val adjustments = VideoAdjustments(contrast = 30f, saturation = -20f)
        val matrix = VideoAdjustmentsPipeline.computeColorMatrix(adjustments)
        assertNotNull(matrix)
        val arr = matrix!!.array
        // Alpha row must be unchanged identity
        assertEquals(0f, arr[15], 0.001f)
        assertEquals(0f, arr[16], 0.001f)
        assertEquals(0f, arr[17], 0.001f)
        assertEquals(1f, arr[18], 0.001f)
        assertEquals(0f, arr[19], 0.001f)
    }

    @Test
    fun testSpatialEffectsDetection() {
        val noSpatial = VideoAdjustments(contrast = 10f)
        assertFalse(VideoAdjustmentsPipeline.hasSpatialEffects(noSpatial))

        val withVignette = VideoAdjustments(vignette = 15f)
        assertTrue(VideoAdjustmentsPipeline.hasSpatialEffects(withVignette))

        val withGrain = VideoAdjustments(grain = 10f)
        assertTrue(VideoAdjustmentsPipeline.hasSpatialEffects(withGrain))

        val withTexture = VideoAdjustments(textureFilmGrain = 25f)
        assertTrue(VideoAdjustmentsPipeline.hasSpatialEffects(withTexture))

        val withLightFx = VideoAdjustments(lightFxSoftLight = 30f)
        assertTrue(VideoAdjustmentsPipeline.hasSpatialEffects(withLightFx))
    }

    @Test
    fun testPreferencesSaveAndRestore() {
        val custom = VideoAdjustments(
            exposure = 1.2f,
            tonality = 15f,
            contrast = -10f,
            highlights = 25f,
            shadows = -15f,
            saturation = 18f,
            temperature = 22f,
            tint = -8f,
            sharpness = 12f,
            vignette = 30f,
            grain = 15f,
            lightFxSoftLight = 40f
        )
        preferences.saveVideoAdjustments(custom)

        val restored = preferences.getVideoAdjustments()
        assertEquals(custom.exposure, restored.exposure, 0.001f)
        assertEquals(custom.tonality, restored.tonality, 0.001f)
        assertEquals(custom.contrast, restored.contrast, 0.001f)
        assertEquals(custom.highlights, restored.highlights, 0.001f)
        assertEquals(custom.shadows, restored.shadows, 0.001f)
        assertEquals(custom.saturation, restored.saturation, 0.001f)
        assertEquals(custom.temperature, restored.temperature, 0.001f)
        assertEquals(custom.tint, restored.tint, 0.001f)
        assertEquals(custom.sharpness, restored.sharpness, 0.001f)
        assertEquals(custom.vignette, restored.vignette, 0.001f)
        assertEquals(custom.grain, restored.grain, 0.001f)
        assertEquals(custom.lightFxSoftLight, restored.lightFxSoftLight, 0.001f)
    }
}
