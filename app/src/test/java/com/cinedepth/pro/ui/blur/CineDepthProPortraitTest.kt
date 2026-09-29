package com.cinedepth.pro.ui.blur

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.cinedepth.pro.ui.BlurPreviewParams
import com.cinedepth.pro.ui.BokehPreset
import com.cinedepth.pro.ui.LensEffect
import com.cinedepth.pro.ui.LensProfile
import com.example.camera.model.BokehStyle
import com.example.camera.model.PortraitConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CineDepthProPortraitTest {

    @Test
    fun testCineDepthLensProfilesAndPresetsMapping() {
        assertEquals(LensEffect.Classic, BokehPreset.Classic.effect)
        assertEquals(LensEffect.Creamy, BokehPreset.Creamy.effect)
        assertEquals(LensEffect.Bubble, BokehPreset.Bubble.effect)
        assertEquals(LensEffect.Bloom, BokehPreset.Bloom.effect)
        assertEquals(LensEffect.Star, BokehPreset.Star.effect)
        assertEquals(LensEffect.Hexagon, BokehPreset.Circular.effect)
        assertEquals(LensEffect.Anamorphic, BokehPreset.Anamorphic.effect)

        assertEquals("Noctilux", LensProfile.Noctilux.label)
        assertEquals("G-Master", LensProfile.GMaster.label)
        assertEquals("Helios 44-2", LensProfile.Helios.label)
        assertEquals("CinemaScope", LensProfile.CinemaScope.label)

        assertTrue(LensProfile.Noctilux.params.blurStrength > 0.40f)
        assertTrue(LensProfile.CinemaScope.params.flareStrength > 0.10f)
    }

    @Test
    fun testCineDepthBlurPreviewParamsCoercion() {
        val params = BlurPreviewParams(
            blurStrength = 0.55f,
            focusDepth = 80f,
            lensEffect = LensEffect.Creamy,
            edgeSoftness = 0.30f,
            edgeExpand = 0.20f,
            edgeRefine = 0.60f,
            vignetteStrength = 0.18f,
            flareStrength = 0.10f
        )
        assertEquals(0.55f, params.blurStrength, 0.001f)
        assertEquals(80f, params.focusDepth, 0.001f)
        assertEquals(LensEffect.Creamy, params.lensEffect)
    }

    @Test
    fun testApertureParsingAndSupportedList() {
        assertEquals(0.95f, PortraitConfig.parseFNumber("f/0.95"), 0.01f)
        assertEquals(1.4f, PortraitConfig.parseFNumber("f/1.4"), 0.01f)
        assertEquals(2.8f, PortraitConfig.parseFNumber("f/2.8"), 0.01f)
        assertEquals(16.0f, PortraitConfig.parseFNumber("f/16"), 0.01f)

        assertTrue(PortraitConfig.SUPPORTED_APERTURES.any { it.first == "f/0.95" })
        assertTrue(PortraitConfig.SUPPORTED_APERTURES.any { it.first == "f/1.4" })
        assertTrue(PortraitConfig.SUPPORTED_APERTURES.any { it.first == "f/16" })
    }

    @Test
    fun testDepthBlurEngineEstimatorAccess() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val estimator = DepthBlurEngine.getEstimator(context)
        assertNotNull(estimator)
        try {
            val segmenter = DepthBlurEngine.getSegmenter()
            assertNotNull(segmenter)
        } catch (_: Throwable) {
            // ML Kit Native C++ binary not loaded in headless Robolectric JVM
        }
    }
}
