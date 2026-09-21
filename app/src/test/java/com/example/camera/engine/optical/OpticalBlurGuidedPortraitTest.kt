package com.example.camera.engine.optical

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.camera.model.BokehStyle
import com.example.camera.model.PortraitConfig
import com.example.camera.model.PortraitStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpticalBlurGuidedPortraitTest {

    @Test
    fun testPortraitConfigOpticalBlurGuidedDefault() {
        val config = PortraitConfig()
        assertTrue("opticalBlurGuided should be enabled by default", config.opticalBlurGuided)

        val disabled = config.copy(opticalBlurGuided = false)
        assertFalse("opticalBlurGuided should be toggleable to false", disabled.opticalBlurGuided)
    }

    @Test
    fun testOpticalDefocusEstimatorGeneratesContinuousDefocusAndConfidence() = runBlocking {
        val width = 200
        val height = 200
        val testBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if (x in 50..150 && y in 50..150) Color.BLACK else Color.WHITE
        }
        testBmp.setPixels(pixels, 0, width, 0, 0, width, height)

        val estimator = OpticalDefocusEstimator()
        val result = estimator.estimateOpticalDefocus(testBmp, analysisScale = 1.0f)

        assertEquals(width, result.width)
        assertEquals(height, result.height)
        assertEquals(width * height, result.defocusMap.size)
        assertEquals(width * height, result.confidenceMap.size)

        // Validate values are continuous in [0.0, 1.0]
        for (i in 0 until (width * height)) {
            val d = result.defocusMap[i]
            val c = result.confidenceMap[i]
            assertTrue("Defocus must be in [0, 1], was $d", d in 0.0f..1.0f)
            assertTrue("Confidence must be in [0, 1], was $c", c in 0.0f..1.0f)
        }

        // Sharp edges around rectangle (e.g. x=50, y=100) should have high confidence
        val edgeIdx = 100 * width + 50
        assertTrue("Edge region should have measurable confidence", result.confidenceMap[edgeIdx] > 0.05f)

        testBmp.recycle()
    }

    @Test
    fun testOpticalDepthFusionEngineComputesContinuousDepthAndMatte() {
        val width = 160
        val height = 160
        val fusionEngine = OpticalDepthFusionEngine()

        // Create synthetic subject mask (circle in center)
        val initialMask = FloatArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            val dx = (x - 80) / 40f
            val dy = (y - 80) / 40f
            if (dx * dx + dy * dy <= 1.0f) 1.0f else 0.0f
        }

        val lumaGuide = FloatArray(width * height) { 0.5f }

        // Test Continuous Depth Map
        val depthMap = fusionEngine.estimateContinuousDepthMap(initialMask, lumaGuide, width, height)
        assertEquals(width * height, depthMap.size)

        val centerIdx = 80 * width + 80
        val cornerIdx = 5 * width + 5
        assertTrue("Center subject should be near focal plane depth", depthMap[centerIdx] < 0.15f)
        assertTrue("Distant background should have higher depth", depthMap[cornerIdx] > 0.25f)

        // Test High-Resolution Hair Matte
        val sourceBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val hairMatte = fusionEngine.computeHighResolutionHairMatte(sourceBmp, initialMask, width, height)
        assertEquals(width * height, hairMatte.size)
        assertTrue("Subject interior should have high alpha in hair matte", hairMatte[centerIdx] > 0.85f)
        assertTrue("Background corner should have low alpha in hair matte", hairMatte[cornerIdx] < 0.15f)

        // Test Fusion: R_synthetic calculation and foreground protection
        val defocusMap = FloatArray(width * height) { 0.6f }
        val confidenceMap = FloatArray(width * height) { 0.8f }

        val fusionResult = fusionEngine.fuseOpticalAndDepth(
            defocusMap = defocusMap,
            confidenceMap = confidenceMap,
            depthMap = depthMap,
            alphaMatte = hairMatte,
            width = width,
            height = height,
            simulatedAperture = "f/1.4",
            blurStrength = 60f
        )

        // Foreground should receive zero synthetic blur
        assertEquals(0.0f, fusionResult.syntheticBlurRadius[centerIdx], 0.001f)
        // Background should receive non-zero synthetic blur
        assertTrue("Background should receive synthetic blur", fusionResult.syntheticBlurRadius[cornerIdx] > 0.0f)

        sourceBmp.recycle()
    }

    @Test
    fun testOpticalBokehRendererAllStyles() = runBlocking {
        val width = 80
        val height = 80
        val renderer = OpticalBokehRenderer()

        val sourceBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sourceBmp)
        canvas.drawColor(Color.BLUE)

        val syntheticRadii = FloatArray(width * height) { 10f }
        val depthMap = FloatArray(width * height) { 0.8f }
        val alphaMatte = FloatArray(width * height) { 0.0f }

        for (style in BokehStyle.values()) {
            val bokehBmp = renderer.renderVariableRadiusBokeh(
                source = sourceBmp,
                syntheticRadii = syntheticRadii,
                depthMap = depthMap,
                maxRadius = 12f,
                bokehStyle = style
            )

            assertNotNull("Bokeh bitmap should not be null for style $style", bokehBmp)
            assertEquals(width, bokehBmp.width)
            assertEquals(height, bokehBmp.height)

            val compositeBmp = renderer.compositeSharpSubjectWithHairMatte(
                original = sourceBmp,
                blurredBackground = bokehBmp,
                alphaMatte = alphaMatte,
                skinToneCorrection = false,
                faceEnhancement = false,
                style = PortraitStyle.NATURAL
            )

            assertNotNull("Composite bitmap should not be null for style $style", compositeBmp)
            compositeBmp.recycle()
            bokehBmp.recycle()
        }

        sourceBmp.recycle()
    }
}
