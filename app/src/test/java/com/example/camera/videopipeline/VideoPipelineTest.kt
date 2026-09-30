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
        assertEquals(VideoPipelineType.IPHONE, VideoPipelineType.fromId("iphone"))
        assertEquals(VideoPipelineType.SAMSUNG, VideoPipelineType.fromId("samsung"))
        assertEquals(VideoPipelineType.VIVO, VideoPipelineType.fromId("vivo"))
        assertEquals(VideoPipelineType.NORMAL, VideoPipelineType.fromId("unknown_id"))
        assertEquals(4, VideoPipelineType.entries.size)
    }

    @Test
    fun testPipelineManagerResolution() {
        val normal = VideoPipelineManager.getPipeline(VideoPipelineType.NORMAL)
        val iphone = VideoPipelineManager.getPipeline(VideoPipelineType.IPHONE)
        val samsung = VideoPipelineManager.getPipeline(VideoPipelineType.SAMSUNG)
        val vivo = VideoPipelineManager.getPipeline(VideoPipelineType.VIVO)

        assertTrue(normal is NormalVideoPipeline)
        assertTrue(iphone is IPhoneVideoPipeline)
        assertTrue(samsung is SamsungVideoPipeline)
        assertTrue(vivo is VivoVideoPipeline)

        assertFalse(normal.isCustomPipeline)
        assertTrue(iphone.isCustomPipeline)
        assertTrue(samsung.isCustomPipeline)
        assertTrue(vivo.isCustomPipeline)

        assertFalse(VideoPipelineManager.isCustomPipeline(VideoPipelineType.NORMAL))
        assertTrue(VideoPipelineManager.isCustomPipeline(VideoPipelineType.IPHONE))
        assertTrue(VideoPipelineManager.isCustomPipeline(VideoPipelineType.SAMSUNG))
        assertTrue(VideoPipelineManager.isCustomPipeline(VideoPipelineType.VIVO))

        assertEquals("Normal", normal.displayName)
        assertEquals("iPhone", iphone.displayName)
        assertEquals("Samsung", samsung.displayName)
        assertEquals("Vivo", vivo.displayName)
    }

    @Test
    fun testIndependentStageParamsAndTonemapCurves() {
        val normalParams = VideoPipelineManager.getPipeline(VideoPipelineType.NORMAL).stageParams
        val iphoneParams = VideoPipelineManager.getPipeline(VideoPipelineType.IPHONE).stageParams
        val samsungParams = VideoPipelineManager.getPipeline(VideoPipelineType.SAMSUNG).stageParams
        val vivoParams = VideoPipelineManager.getPipeline(VideoPipelineType.VIVO).stageParams

        assertNotEquals(normalParams, iphoneParams)
        assertNotEquals(iphoneParams, samsungParams)
        assertNotEquals(samsungParams, vivoParams)

        // Verify independent Stage 0 ISP tone curves are generated accurately
        val iphoneCurve = iphoneParams.buildCustomIspTonemapCurve()
        val samsungCurve = samsungParams.buildCustomIspTonemapCurve()
        val vivoCurve = vivoParams.buildCustomIspTonemapCurve()

        assertNotNull(iphoneCurve)
        assertNotNull(samsungCurve)
        assertNotNull(vivoCurve)
    }

    @Test
    fun testIPhoneVideoPipelineCharacteristics() {
        val pipeline = VideoPipelineManager.getPipeline(VideoPipelineType.IPHONE)
        val agsl = pipeline.getAgslShaderCode()
        val glsl = pipeline.getGlFragmentShaderCode()

        assertTrue("AGSL shader must not be empty", agsl.isNotEmpty())
        assertTrue("GLSL shader must not be empty", glsl.isNotEmpty())
        assertTrue("AGSL should contain 5-tap spatial convolution", agsl.contains("highFreqDetail"))
        assertTrue("GLSL should contain 5-tap spatial convolution", glsl.contains("highFreqDetail"))
        assertTrue("AGSL should contain Smart HDR highlight retention", agsl.contains("hlCompression"))
        assertTrue("GLSL should contain Smart HDR highlight retention", glsl.contains("hlCompression"))
        assertTrue("AGSL should contain Subject Separation bell curve", agsl.contains("subjectDist"))
        assertTrue("AGSL should contain Skin tone protection", agsl.contains("isSkin"))

        val matrix = pipeline.computeColorMatrix()
        assertNotNull(matrix)
        val arr = matrix.array
        assertEquals(20, arr.size)
        // Red channel offset should have slight warm daylight boost
        assertTrue("Red offset should be positive for Apple warm daylight", arr[4] > 0f)
    }

    @Test
    fun testSamsungVideoPipelineCharacteristics() {
        val pipeline = VideoPipelineManager.getPipeline(VideoPipelineType.SAMSUNG)
        val agsl = pipeline.getAgslShaderCode()
        val glsl = pipeline.getGlFragmentShaderCode()

        assertTrue(agsl.isNotEmpty())
        assertTrue(glsl.isNotEmpty())
        assertTrue("AGSL should contain 5-tap spatial convolution", agsl.contains("edgeDetail"))
        assertTrue("GLSL should contain 5-tap spatial convolution", glsl.contains("edgeDetail"))
        assertTrue("AGSL should contain lifted shadows toe curve", agsl.contains("shadowToe"))
        assertTrue("AGSL should contain bright midtone gain", agsl.contains("midtoneGain"))
        assertTrue("AGSL should contain rich highlight knee protection", agsl.contains("hlThreshold"))

        val matrix = pipeline.computeColorMatrix()
        assertNotNull(matrix)
        val arr = matrix.array
        assertEquals(20, arr.size)
        // High saturation boost
        assertTrue("Samsung should have high red gain", arr[0] > 1.05f)
    }

    @Test
    fun testVivoVideoPipelineCharacteristics() {
        val pipeline = VideoPipelineManager.getPipeline(VideoPipelineType.VIVO)
        val agsl = pipeline.getAgslShaderCode()
        val glsl = pipeline.getGlFragmentShaderCode()

        assertTrue(agsl.isNotEmpty())
        assertTrue(glsl.isNotEmpty())
        assertTrue("AGSL should contain 5-tap spatial convolution", agsl.contains("microDetail"))
        assertTrue("GLSL should contain 5-tap spatial convolution", glsl.contains("microDetail"))
        assertTrue("AGSL should contain controlled knee highlight compression", agsl.contains("knee"))
        assertTrue("AGSL should contain clean shadow detail lift", agsl.contains("shadowT"))
        assertTrue("AGSL should contain micro-contrast clarity", agsl.contains("clarityMask"))

        val matrix = pipeline.computeColorMatrix()
        assertNotNull(matrix)
        val arr = matrix.array
        assertEquals(20, arr.size)
    }

    @Test
    fun testNormalVideoPipelinePassthrough() {
        val pipeline = VideoPipelineManager.getPipeline(VideoPipelineType.NORMAL)
        assertEquals("", pipeline.getAgslShaderCode())
        assertEquals("", pipeline.getGlFragmentShaderCode())
    }

    @Test
    fun testPreferencesPersistence() {
        preferences.videoPipeline = VideoPipelineType.IPHONE
        assertEquals(VideoPipelineType.IPHONE, preferences.videoPipeline)

        preferences.videoPipeline = VideoPipelineType.SAMSUNG
        assertEquals(VideoPipelineType.SAMSUNG, preferences.videoPipeline)

        preferences.videoPipeline = VideoPipelineType.VIVO
        assertEquals(VideoPipelineType.VIVO, preferences.videoPipeline)

        preferences.videoPipeline = VideoPipelineType.NORMAL
        assertEquals(VideoPipelineType.NORMAL, preferences.videoPipeline)
    }

    @Test
    fun testViewApplicationAndClearing() {
        val textureView = TextureView(context)

        // Applying iPhone pipeline
        VideoPipelineManager.applyPipelineToView(textureView, VideoPipelineType.IPHONE)

        // Switching to Samsung pipeline
        VideoPipelineManager.applyPipelineToView(textureView, VideoPipelineType.SAMSUNG)

        // Switching to Vivo pipeline
        VideoPipelineManager.applyPipelineToView(textureView, VideoPipelineType.VIVO)

        // Clearing
        VideoPipelineManager.clearPipelineFromView(textureView)

        // Applying Normal pipeline
        VideoPipelineManager.applyPipelineToView(textureView, VideoPipelineType.NORMAL)
    }
}
