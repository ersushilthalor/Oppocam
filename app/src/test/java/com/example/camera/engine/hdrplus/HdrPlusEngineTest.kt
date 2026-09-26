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
    fun testPredictorTwoFrameMode() {
        val predictor = HdrPlusPredictor()
        val baseExpNs = 30_000_000L
        val baseIso = 100

        predictor.updatePrediction(
            stats = FrameLuminanceStats(p95 = 0.85f, p99 = 0.92f, dynamicRange = 0.70f),
            lastResult = null,
            caps = defaultCaps,
            frameCount = HdrPlusFrameCount.TWO_FRAMES,
            userSelectedIso = baseIso,
            userSelectedExposureTimeNs = baseExpNs
        )

        val prediction = predictor.getLatestPrediction(HdrPlusFrameCount.TWO_FRAMES)
        assertEquals(2, prediction.specs.size)

        val frame1 = prediction.specs[0]
        assertEquals(HdrPlusRole.BASE_PRIMARY, frame1.role)
        assertEquals(baseExpNs, frame1.exposureTimeNs)
        assertEquals(baseIso, frame1.iso)
        assertEquals(0.0f, frame1.evDelta, 0.001f)

        val frame2 = prediction.specs[1]
        assertEquals(HdrPlusRole.SECONDARY_MODERATE_HIGHLIGHT, frame2.role)
        assertTrue("Frame 2 must be darker than Frame 1", (frame2.exposureTimeNs * frame2.iso) < (frame1.exposureTimeNs * frame1.iso))
        assertTrue("Frame 2 EV offset must be negative", frame2.evDelta < 0f)
    }

    @Test
    fun testPredictorThreeFrameMode() {
        val predictor = HdrPlusPredictor()
        val baseExpNs = 40_000_000L
        val baseIso = 200

        predictor.updatePrediction(
            stats = FrameLuminanceStats(p95 = 0.90f, p99 = 0.98f, isOutdoorSkyWithDarkForeground = true),
            lastResult = null,
            caps = defaultCaps,
            frameCount = HdrPlusFrameCount.THREE_FRAMES,
            userSelectedIso = baseIso,
            userSelectedExposureTimeNs = baseExpNs
        )

        val prediction = predictor.getLatestPrediction(HdrPlusFrameCount.THREE_FRAMES)
        assertEquals(3, prediction.specs.size)

        val frame1 = prediction.specs[0]
        val frame2 = prediction.specs[1]
        val frame3 = prediction.specs[2]

        assertEquals(HdrPlusRole.BASE_PRIMARY, frame1.role)
        assertEquals(HdrPlusRole.SECONDARY_MODERATE_HIGHLIGHT, frame2.role)
        assertEquals(HdrPlusRole.SECONDARY_EXTREME_HIGHLIGHT, frame3.role)

        val p1 = frame1.exposureTimeNs.toDouble() * frame1.iso.toDouble()
        val p2 = frame2.exposureTimeNs.toDouble() * frame2.iso.toDouble()
        val p3 = frame3.exposureTimeNs.toDouble() * frame3.iso.toDouble()

        assertTrue("Frame 2 must be darker than Frame 1", p2 < p1)
        assertTrue("Frame 3 must be strictly darker than Frame 2", p3 < p2)
        assertTrue("Frame 3 EV delta must be more negative than Frame 2", frame3.evDelta < frame2.evDelta)
    }

    @Test
    fun testOutdoorSkyAndHighlightAdaptation() {
        val predictor = HdrPlusPredictor()

        // Normal scene
        predictor.updatePrediction(
            stats = FrameLuminanceStats(p95 = 0.60f, p99 = 0.75f, isOutdoorSkyWithDarkForeground = false),
            lastResult = null,
            caps = defaultCaps,
            frameCount = HdrPlusFrameCount.TWO_FRAMES
        )
        val normalPred = predictor.getLatestPrediction(HdrPlusFrameCount.TWO_FRAMES)

        // Outdoor sky high contrast scene
        predictor.updatePrediction(
            stats = FrameLuminanceStats(p95 = 0.96f, p99 = 0.99f, isOutdoorSkyWithDarkForeground = true),
            lastResult = null,
            caps = defaultCaps,
            frameCount = HdrPlusFrameCount.TWO_FRAMES
        )
        val skyPred = predictor.getLatestPrediction(HdrPlusFrameCount.TWO_FRAMES)

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
}
