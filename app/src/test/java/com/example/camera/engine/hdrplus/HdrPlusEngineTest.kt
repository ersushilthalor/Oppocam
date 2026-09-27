package com.example.camera.engine.hdrplus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.camera.engine.FrameLuminanceStats
import com.example.camera.model.HardwareCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HdrPlusEngineTest {

    private val defaultCaps = HardwareCapabilities(
        supportsRaw = true,
        minIso = 50,
        maxIso = 3200,
        minExposureTimeNs = 100_000L,
        maxExposureTimeNs = 1_000_000_000L,
        minExposureCompensation = -4,
        maxExposureCompensation = 4,
        exposureCompensationStep = 0.333f
    )

    @Test
    fun testPredictorThreeFrameExposureFusion() {
        val predictor = HdrPlusPredictor()
        val baseExpNs = 30_000_000L
        val baseIso = 100

        predictor.updatePrediction(
            stats = FrameLuminanceStats(p95 = 0.85f, p99 = 0.92f, dynamicRange = 0.70f),
            lastResult = null,
            caps = defaultCaps,
            frameCount = HdrPlusFrameCount.THREE_FRAMES,
            userSelectedIso = baseIso,
            userSelectedExposureTimeNs = baseExpNs
        )

        val prediction = predictor.getLatestPrediction(HdrPlusFrameCount.THREE_FRAMES)
        assertEquals(3, prediction.specs.size)

        val frameNormal = prediction.specs[0]
        assertEquals(HdrPlusRole.NORMAL_EXPOSURE, frameNormal.role)
        assertEquals(baseExpNs, frameNormal.exposureTimeNs)
        assertEquals(baseIso, frameNormal.iso)
        assertEquals(0.0f, frameNormal.evDelta, 0.001f)

        val frameUnder = prediction.specs[1]
        assertEquals(HdrPlusRole.UNDER_EXPOSED, frameUnder.role)
        assertTrue("Under-exposed frame must be darker than Normal frame", (frameUnder.exposureTimeNs * frameUnder.iso) < (frameNormal.exposureTimeNs * frameNormal.iso))
        assertTrue("Under-exposed frame EV offset must be negative", frameUnder.evDelta < 0f)

        val frameOver = prediction.specs[2]
        assertEquals(HdrPlusRole.OVER_EXPOSED, frameOver.role)
        assertTrue("Over-exposed frame must be brighter than Normal frame", (frameOver.exposureTimeNs * frameOver.iso) > (frameNormal.exposureTimeNs * frameNormal.iso))
        assertTrue("Over-exposed frame EV offset must be positive", frameOver.evDelta > 0f)
    }

    @Test
    fun testOutdoorSkyAndHighlightAdaptation() {
        val predictor = HdrPlusPredictor()

        // Normal scene
        predictor.updatePrediction(
            stats = FrameLuminanceStats(p95 = 0.60f, p99 = 0.75f, isOutdoorSkyWithDarkForeground = false),
            lastResult = null,
            caps = defaultCaps,
            frameCount = HdrPlusFrameCount.THREE_FRAMES
        )
        val normalPred = predictor.getLatestPrediction(HdrPlusFrameCount.THREE_FRAMES)

        // Outdoor sky high contrast scene
        predictor.updatePrediction(
            stats = FrameLuminanceStats(p95 = 0.96f, p99 = 0.99f, isOutdoorSkyWithDarkForeground = true),
            lastResult = null,
            caps = defaultCaps,
            frameCount = HdrPlusFrameCount.THREE_FRAMES
        )
        val skyPred = predictor.getLatestPrediction(HdrPlusFrameCount.THREE_FRAMES)

        assertTrue(
            "Outdoor sky should demand greater EV underexposure than normal scene",
            skyPred.specs[1].evDelta <= normalPred.specs[1].evDelta
        )
        assertTrue(skyPred.isOutdoorSkyDetected)
    }

    @Test
    fun testHardwareExposureClamping() {
        val predictor = HdrPlusPredictor()
        val strictCaps = defaultCaps.copy(
            minIso = 100,
            maxIso = 800,
            minExposureTimeNs = 1_000_000L, // 1ms min
            maxExposureTimeNs = 50_000_000L
        )

        predictor.updatePrediction(
            stats = FrameLuminanceStats(),
            lastResult = null,
            caps = strictCaps,
            frameCount = HdrPlusFrameCount.THREE_FRAMES,
            userSelectedIso = 100,
            userSelectedExposureTimeNs = 2_000_000L
        )

        val prediction = predictor.getLatestPrediction(HdrPlusFrameCount.THREE_FRAMES)
        for (spec in prediction.specs) {
            assertTrue("Exposure time must be >= minExpNs", spec.exposureTimeNs >= strictCaps.minExposureTimeNs)
            assertTrue("Exposure time must be <= maxExpNs", spec.exposureTimeNs <= strictCaps.maxExposureTimeNs)
            assertTrue("ISO must be >= minIso", spec.iso >= strictCaps.minIso)
            assertTrue("ISO must be <= maxIso", spec.iso <= strictCaps.maxIso)
        }
    }

    @Test
    fun testHighlightOnlyMergeOutputsValidJpeg() = runBlocking {
        val merger = HdrPlusMerger()
        val width = 64
        val height = 48

        // Base frame with a gradient (dark bottom, bright highlights at top)
        val baseRgb = FloatArray(width * height * 3)
        for (y in 0 until height) {
            val yNorm = y.toFloat() / height.toFloat() // 0.0 at top (highlight), 1.0 at bottom (shadow)
            for (x in 0 until width) {
                val idx = (y * width + x) * 3
                val luma = 1.0f - yNorm
                baseRgb[idx] = luma
                baseRgb[idx + 1] = luma
                baseRgb[idx + 2] = luma
            }
        }

        val baseFrame = HdrPlusDevelopedImage(
            rgbLinear = baseRgb,
            width = width,
            height = height,
            exposureTimeNs = 33_333_333L,
            iso = 100,
            role = HdrPlusRole.BASE_PRIMARY,
            evDelta = 0f
        )

        // Secondary frame with 0.25x exposure
        val secRgb = FloatArray(width * height * 3)
        for (i in secRgb.indices) {
            secRgb[i] = (baseRgb[i] * 0.25f)
        }

        val secFrame = HdrPlusDevelopedImage(
            rgbLinear = secRgb,
            width = width,
            height = height,
            exposureTimeNs = 8_333_333L,
            iso = 100,
            role = HdrPlusRole.SECONDARY_MODERATE_HIGHLIGHT,
            evDelta = -2.0f
        )

        val alignment = HdrPlusAlignmentResult(shiftX = 0, shiftY = 0, confidence = 0.95f)

        val jpegBytes = merger.mergeFrames(
            baseFrame = baseFrame,
            secondaryFrames = listOf(Pair(secFrame, alignment)),
            jpegQuality = 95
        )

        assertNotNull("Merged JPEG bytes must not be null", jpegBytes)
        assertTrue("Merged JPEG bytes must be non-empty", jpegBytes.isNotEmpty())

        val decodedBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        assertNotNull("Merged JPEG must decode to a valid Bitmap", decodedBitmap)
        assertEquals(width, decodedBitmap.width)
        assertEquals(height, decodedBitmap.height)
        decodedBitmap.recycle()
    }

    @Test
    fun testMotionSuppressionSafety() = runBlocking {
        val merger = HdrPlusMerger()
        val width = 32
        val height = 32

        // Base frame is bright white (highlight)
        val baseRgb = FloatArray(width * height * 3) { 0.95f }
        val baseFrame = HdrPlusDevelopedImage(
            rgbLinear = baseRgb,
            width = width,
            height = height,
            exposureTimeNs = 33_333_333L,
            iso = 100,
            role = HdrPlusRole.BASE_PRIMARY,
            evDelta = 0f
        )

        // Secondary frame has severe motion mismatch (e.g. completely black object moved in)
        val secRgb = FloatArray(width * height * 3) { 0.0f }
        val secFrame = HdrPlusDevelopedImage(
            rgbLinear = secRgb,
            width = width,
            height = height,
            exposureTimeNs = 8_333_333L,
            iso = 100,
            role = HdrPlusRole.SECONDARY_MODERATE_HIGHLIGHT,
            evDelta = -2.0f
        )

        val alignment = HdrPlusAlignmentResult(shiftX = 0, shiftY = 0, confidence = 0.5f)

        // Should not crash and should produce valid output
        val jpegBytes = merger.mergeFrames(
            baseFrame = baseFrame,
            secondaryFrames = listOf(Pair(secFrame, alignment)),
            jpegQuality = 90
        )
        assertTrue(jpegBytes.isNotEmpty())
    }

    @Test
    fun testRawExtractionRowStrideAndYellowColorAccuracy() = runBlocking {
        val developer = HdrPlusRawDeveloper()
        val width = 32
        val height = 32
        val pixelStride = 2
        val rowStride = width * pixelStride + 12 // padded row stride (76 bytes = 38 shorts)
        val strideShorts = rowStride shr 1
        val rawShorts = ShortArray(strideShorts * height)

        // Simulate RGGB Bayer sensor viewing a yellow notebook (high R, high G, low B)
        val blackLevel = 64f
        val whiteLevel = 1023
        for (y in 0 until height) {
            val evenY = (y and 1) == 0
            for (x in 0 until width) {
                val evenX = (x and 1) == 0
                val sample = when {
                    evenY && evenX -> 820   // R (high)
                    evenY && !evenX -> 800  // Gr (high)
                    !evenY && evenX -> 800  // Gb (high)
                    else -> 150             // B (low)
                }
                rawShorts[y * strideShorts + x] = sample.toShort()
            }
        }

        val rawFrame = HdrPlusRawFrame(
            rawData = rawShorts,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            blackLevel = blackLevel.toInt(),
            blackLevelPattern = floatArrayOf(blackLevel, blackLevel, blackLevel, blackLevel),
            whiteLevel = whiteLevel,
            cfaPattern = 0, // RGGB
            rGain = 1.0f,
            gGain = 1.0f,
            bGain = 1.0f,
            colorCorrectionMatrix = floatArrayOf(
                1.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 1.0f
            ),
            exposureTimeNs = 16_666_666L,
            iso = 100,
            timestampNs = 1_000_000L,
            role = HdrPlusRole.BASE_PRIMARY,
            evDelta = 0f
        )

        val developed = developer.developRawToLinearRgb(
            frame = rawFrame
        )
        val merger = HdrPlusMerger()
        val jpegBytes = merger.mergeFrames(
            baseFrame = developed,
            secondaryFrames = emptyList(),
            jpegQuality = 95
        )
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        assertNotNull(bitmap)
        val centerPixel = bitmap.getPixel(width / 2, height / 2)
        val r = android.graphics.Color.red(centerPixel)
        val g = android.graphics.Color.green(centerPixel)
        val b = android.graphics.Color.blue(centerPixel)
        bitmap.recycle()

        assertTrue(
            "Expected yellow surface to remain yellow (R > B + 60 and G > B + 60), got R=$r, G=$g, B=$b",
            r > b + 60 && g > b + 60
        )
    }

    @Test
    fun testThreeFrameExposureFusionFullPipelineAndRotation() = runBlocking {
        val merger = HdrPlusMerger()
        val width = 64
        val height = 48
        val totalPixels = width * height

        // 1. Normal Frame (0 EV): High contrast scene with bright sky at top (luma ~1.0) and dark shadow at bottom (luma ~0.05)
        val normalRgb = FloatArray(totalPixels * 3)
        for (y in 0 until height) {
            val yNorm = y.toFloat() / (height - 1).toFloat()
            val luma = (1.0f - yNorm).coerceIn(0.05f, 1.0f)
            for (x in 0 until width) {
                val idx = (y * width + x) * 3
                normalRgb[idx] = luma
                normalRgb[idx + 1] = luma
                normalRgb[idx + 2] = luma
            }
        }
        val normalFrame = HdrPlusDevelopedImage(
            rgbLinear = normalRgb,
            width = width,
            height = height,
            exposureTimeNs = 33_333_333L,
            iso = 100,
            role = HdrPlusRole.NORMAL_EXPOSURE,
            evDelta = 0.0f
        )

        // 2. Underexposed Frame (-2 EV): Highlights in the sky are preserved without sensor saturation
        val underRgb = FloatArray(totalPixels * 3) { idx ->
            (normalRgb[idx] * 0.25f).coerceIn(0.0f, 1.0f)
        }
        val underFrame = HdrPlusDevelopedImage(
            rgbLinear = underRgb,
            width = width,
            height = height,
            exposureTimeNs = 8_333_333L,
            iso = 100,
            role = HdrPlusRole.UNDER_EXPOSED,
            evDelta = -2.0f
        )

        // 3. Overexposed Frame (+2 EV): Shadows at the bottom are boosted and clear
        val overRgb = FloatArray(totalPixels * 3) { idx ->
            (normalRgb[idx] * 4.0f).coerceIn(0.0f, 2.0f)
        }
        val overFrame = HdrPlusDevelopedImage(
            rgbLinear = overRgb,
            width = width,
            height = height,
            exposureTimeNs = 133_333_333L,
            iso = 100,
            role = HdrPlusRole.OVER_EXPOSED,
            evDelta = 2.0f
        )

        // Merge with 90° rotation (Portrait capture on standard 90° landscape sensor)
        val mergedJpeg = merger.merge3FramesExposureFusion(
            underFrame = underFrame,
            normalFrame = normalFrame,
            overFrame = overFrame,
            jpegQuality = 98,
            orientationDegrees = 90
        )

        assertNotNull(mergedJpeg)
        assertTrue(mergedJpeg.isNotEmpty())

        val bitmap = BitmapFactory.decodeByteArray(mergedJpeg, 0, mergedJpeg.size)
        assertNotNull(bitmap)
        // With 90° rotation, width and height must swap correctly: original 64x48 -> 48x64 upright
        assertEquals("Width must be rotated from 64 to 48", height, bitmap.width)
        assertEquals("Height must be rotated from 48 to 64", width, bitmap.height)
        bitmap.recycle()
    }
}
