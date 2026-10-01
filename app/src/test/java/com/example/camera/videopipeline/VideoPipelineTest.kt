package com.example.camera.videopipeline

import android.content.Context
import android.view.TextureView
import androidx.test.core.app.ApplicationProvider
import com.example.camera.data.CameraPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoPipelineTest {

    private lateinit var context: Context
    private lateinit var preferences: CameraPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = CameraPreferences(context)
    }

    @Test
    fun testPipelineTypesAndLookup() {
        assertEquals(VideoPipelineType.NORMAL, VideoPipelineType.fromId("normal"))
        assertEquals(VideoPipelineType.CUSTOM, VideoPipelineType.fromId("custom"))
        assertEquals(VideoPipelineType.NORMAL, VideoPipelineType.fromId("unknown_id"))
        assertEquals(2, VideoPipelineType.entries.size)
    }

    @Test
    fun testPipelineManagerResolution() {
        val normal = VideoPipelineManager.getPipeline(VideoPipelineType.NORMAL)
        val custom = VideoPipelineManager.getPipeline(VideoPipelineType.CUSTOM)

        assertTrue(normal is NormalVideoPipeline)
        assertTrue(custom is CustomVideoPipeline)

        assertFalse(normal.isCustomPipeline)
        assertTrue(custom.isCustomPipeline)

        assertFalse(VideoPipelineManager.isCustomPipeline(VideoPipelineType.NORMAL))
        assertTrue(VideoPipelineManager.isCustomPipeline(VideoPipelineType.CUSTOM))

        assertEquals("Normal", normal.displayName)
        assertEquals("Custom Pipeline", custom.displayName)
    }

    @Test
    fun testIndependentStageParamsAndTonemapCurves() {
        val normalParams = VideoPipelineManager.getPipeline(VideoPipelineType.NORMAL).stageParams
        val customParams = VideoPipelineManager.getPipeline(VideoPipelineType.CUSTOM).stageParams

        assertNotEquals(normalParams, customParams)

        val customCurve = customParams.buildCustomIspTonemapCurve()
        assertNotNull(customCurve)
    }

    @Test
    fun testNormalVideoPipelinePassthrough() {
        val pipeline = VideoPipelineManager.getPipeline(VideoPipelineType.NORMAL)
        assertEquals("", pipeline.getAgslShaderCode())
        assertEquals("", pipeline.getGlFragmentShaderCode())
    }

    @Test
    fun testPreferencesPersistence() {
        preferences.videoPipeline = VideoPipelineType.CUSTOM
        assertEquals(VideoPipelineType.CUSTOM, preferences.videoPipeline)

        preferences.videoPipeline = VideoPipelineType.NORMAL
        assertEquals(VideoPipelineType.NORMAL, preferences.videoPipeline)
    }

    @Test
    fun testViewApplicationAndClearing() {
        val textureView = TextureView(context)

        // Applying custom pipeline
        VideoPipelineManager.applyPipelineToView(textureView, VideoPipelineType.CUSTOM)

        // Clearing
        VideoPipelineManager.clearPipelineFromView(textureView)

        // Applying Normal pipeline
        VideoPipelineManager.applyPipelineToView(textureView, VideoPipelineType.NORMAL)
    }
}
