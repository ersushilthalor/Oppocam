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
    fun testCustomPipelineControlsAndShaderGeneration() {
        val customPipeline = VideoPipelineManager.getCustomPipeline()
        val customConfig = CustomVideoPipelineConfig(
            exposure = 0.5f,
            highlightRecovery = 0.75f,
            shadowRecovery = 0.60f,
            blackLevel = 0.01f,
            midtoneControl = 0.2f,
            contrast = 0.3f,
            localContrast = 0.25f,
            dynamicRangeToneMapping = 0.50f,
            highlightRollOff = 0.60f,
            shadowRollOff = 0.40f,
            temperature = 0.1f,
            tint = -0.05f,
            saturation = 1.1f,
            vibrance = 0.15f,
            colorMatrixPreset = 1,
            redGain = 1.05f,
            greenGain = 1.0f,
            blueGain = 0.95f,
            lumaCurvePreset = 2,
            redCurveStrength = 0.1f,
            greenCurveStrength = 0.0f,
            blueCurveStrength = -0.1f,
            chromaStrength = 1.15f,
            chromaNoiseReduction = 0.40f,
            lumaNoiseReduction = 0.35f,
            temporalNoiseReduction = 0.60f,
            spatialNoiseReduction = 0.45f,
            sharpening = 0.30f,
            microContrast = 0.20f,
            textureDetail = 0.25f,
            debanding = 0.30f,
            demosaicDetailProcessing = 0.35f,
            lensShadingCorrection = 0.50f,
            distortionCorrection = 0.30f,
            blackClippingControl = 0.20f,
            highlightClippingProtection = 0.50f,
            hdrToneMappingStrength = 0.45f,
            localToneMapping = 0.30f,
            colorHighlightShadowSeparation = 0.25f,
            outputGamma = 2.4f,
            logToDisplayTransformStrength = 0.90f
        )
        customPipeline.updateConfig(customConfig)

        val agsl = customPipeline.getAgslShaderCode()
        val glsl = customPipeline.getGlFragmentShaderCode()

        assertTrue("AGSL shader must not be empty", agsl.isNotEmpty())
        assertTrue("GLSL shader must not be empty", glsl.isNotEmpty())

        assertTrue("AGSL must contain Rec.2020 linear conversion", agsl.contains("toLinearRec2020"))
        assertTrue("GLSL must contain Rec.2020 linear conversion", glsl.contains("toLinearRec2020"))

        assertTrue("AGSL must contain lens shading & distortion", agsl.contains("distortedCoord"))
        assertTrue("GLSL must contain lens shading & distortion", glsl.contains("distortedUv"))

        assertTrue("AGSL must contain highlight recovery", agsl.contains("hlKnee"))
        assertTrue("GLSL must contain highlight recovery", glsl.contains("hlKnee"))

        assertTrue("AGSL must contain shadow recovery", agsl.contains("shadowLift"))
        assertTrue("GLSL must contain shadow recovery", glsl.contains("shadowLift"))

        assertTrue("AGSL must contain RGB curves", agsl.contains("fRedCurve"))
        assertTrue("GLSL must contain RGB curves", glsl.contains("fRedCurve"))
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
        assertTrue(preferences.isCustomVideoPipelineEnabled)

        preferences.videoPipeline = VideoPipelineType.NORMAL
        assertEquals(VideoPipelineType.NORMAL, preferences.videoPipeline)
        assertFalse(preferences.isCustomVideoPipelineEnabled)

        val cfg = preferences.getCustomVideoPipelineConfig()
        assertNotNull(cfg)
        preferences.saveCustomVideoPipelineConfig(cfg.copy(exposure = 0.35f, sharpening = 0.40f))
        val updated = preferences.getCustomVideoPipelineConfig()
        assertEquals(0.35f, updated.exposure, 0.001f)
        assertEquals(0.40f, updated.sharpening, 0.001f)
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
