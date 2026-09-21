package com.example.camera.engine

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import androidx.test.core.app.ApplicationProvider
import com.example.camera.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CinemaPipelineVerificationTest {

    private lateinit var context: Context
    private lateinit var cinemaEngine: CinemaEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        cinemaEngine = CinemaEngine(context)
    }

    @Test
    fun testRec2020NaturalContrastNoWashedOutPedestal() {
        val config = CinemaConfig(
            colorProfile = CinemaColorProfile.REC_2020,
            shadows = 0f,
            highlights = 0f,
            contrast = 0f,
            exposure = 0f
        )
        cinemaEngine.updateConfig(config)
        val curve = cinemaEngine.getTonemapCurve()
        val count = curve.getPointCount(TonemapCurve.CHANNEL_RED)

        // Verify black level: input 0.0f should map to 0.0f without artificial milky pedestal (>0.05f)
        val blackPoint = curve.getPoint(TonemapCurve.CHANNEL_RED, 0)
        assertEquals(0.0f, blackPoint.x, 0.001f)
        assertTrue("Rec.2020 black point must be true deep black (<=0.01f), got ${blackPoint.y}", blackPoint.y <= 0.01f)

        // Verify middle-grey (x=0.5, y ≈ 0.71 on BT.2020 0.45 power law) is in natural photographic range
        val midPoint = curve.getPoint(TonemapCurve.CHANNEL_RED, count / 2)
        assertTrue("Rec.2020 mid-tone should have natural filmic gamma, got ${midPoint.y}", midPoint.y in 0.50f..0.80f)

        // Verify highlights reach full range
        val whitePoint = curve.getPoint(TonemapCurve.CHANNEL_RED, count - 1)
        assertEquals(1.0f, whitePoint.x, 0.001f)
        assertEquals(1.0f, whitePoint.y, 0.01f)
    }

    @Test
    fun testRec2020AutoTonePrioritiesNeverDarkensSceneForSky() {
        val engine = Rec2020AutoToneEngine()

        // 1. Simulate intense sunny outdoor daylight (EV100 = 15.0)
        for (i in 0 until 40) {
            engine.processSceneIllumination(ev100 = 15.0f, hasFace = false)
        }
        val outdoorParams = engine.currentParams.value

        // Priority 1: Overall scene/subject exposure - NEVER darken scene just to save sky!
        assertTrue("Outdoor exposure must never be negative, got ${outdoorParams.exposure}", outdoorParams.exposure >= 0.0f)
        // Priority 2: Shadow detail must be lifted in high-contrast outdoor light
        assertTrue("Shadows should be lifted in daylight, got ${outdoorParams.shadows}", outdoorParams.shadows >= 0.40f)
        // Priority 4 & 5: Highlight roll-off shoulder protects clouds smoothly
        assertTrue("Highlight shoulder should be active, got ${outdoorParams.highlights}", outdoorParams.highlights >= 0.60f)
        assertTrue("Sky protection should be active", outdoorParams.skyProtectionActive)

        // 2. Simulate indoor room lighting (EV100 = 7.0)
        for (i in 0 until 40) {
            engine.processSceneIllumination(ev100 = 7.0f, hasFace = false)
        }
        val indoorParams = engine.currentParams.value

        // Indoor exposure remains positive and balanced
        assertTrue("Indoor exposure should be natural, got ${indoorParams.exposure}", indoorParams.exposure in 0.0f..0.20f)
        // Indoor shadows need less aggressive lift
        assertTrue("Indoor shadows should be natural, got ${indoorParams.shadows}", indoorParams.shadows < outdoorParams.shadows)
        assertFalse("Sky protection should be inactive indoors", indoorParams.skyProtectionActive)
    }

    @Test
    fun testRec2020NoRedPinkArtifactsAndMonotonicCurves() {
        val engine = Rec2020AutoToneEngine()
        // Run with aggressive daylight highlights
        for (i in 0 until 30) {
            engine.processSceneIllumination(ev100 = 14.5f, hasFace = true, maxFaceArea = 150_000)
        }

        val curve = engine.getTonemapCurve(64)
        val count = curve.getPointCount(TonemapCurve.CHANNEL_RED)
        assertEquals(64, count)

        var prevY = -0.001f
        for (i in 0 until count) {
            val ptR = curve.getPoint(TonemapCurve.CHANNEL_RED, i)
            val ptG = curve.getPoint(TonemapCurve.CHANNEL_GREEN, i)
            val ptB = curve.getPoint(TonemapCurve.CHANNEL_BLUE, i)

            // Red, Green, Blue MUST be strictly identical across all 64 points (eliminates false color & pink/red tint)
            assertEquals("Red and Green tonemap must match at point $i", ptR.y, ptG.y, 0.0001f)
            assertEquals("Red and Blue tonemap must match at point $i", ptR.y, ptB.y, 0.0001f)

            // Transfer curve MUST be monotonically non-decreasing (no dips or kinks in highlight shoulder)
            assertTrue("Curve must be monotonic at point $i: ${ptR.y} >= $prevY", ptR.y >= prevY - 0.0001f)
            prevY = ptR.y
        }

        // Peak white strictly reaches 1.0 (no dingy gray clamping)
        val peakPoint = curve.getPoint(TonemapCurve.CHANNEL_RED, count - 1)
        assertEquals(1.0f, peakPoint.y, 0.001f)

        // Inky black strictly at 0.0
        val blackPoint = curve.getPoint(TonemapCurve.CHANNEL_RED, 0)
        assertEquals(0.0f, blackPoint.y, 0.0001f)
    }

    @Test
    fun testRec2020PreviewColorMatrixNeutralWhitePreservation() {
        val params = Rec2020AutoToneParams(
            exposure = 0.15f,
            highlights = 0.75f,
            shadows = 0.50f,
            contrast = 0.05f,
            fadeout = 0.60f
        )
        val matrix = Rec2020AutoToneEngine.computePreviewColorMatrix(params)
        val arr = matrix.array

        // Row 0: Red output, Row 1: Green output, Row 2: Blue output
        val row0Sum = arr[0] + arr[1] + arr[2]
        val row1Sum = arr[5] + arr[6] + arr[7]
        val row2Sum = arr[10] + arr[11] + arr[12]

        // Rows must sum to identical luminance scale (neutral white preservation, zero color shift on white clouds)
        assertEquals("Row 0 and Row 1 luminance weight sum must match", row0Sum, row1Sum, 0.001f)
        assertEquals("Row 0 and Row 2 luminance weight sum must match", row0Sum, row2Sum, 0.001f)

        // Offsets on Red, Green, Blue channels must be strictly identical (zero DC color tint)
        val offR = arr[4]
        val offG = arr[9]
        val offB = arr[14]
        assertEquals("Channel offsets must be identical across R, G, B", offR, offG, 0.001f)
        assertEquals("Channel offsets must be identical across R, G, B", offR, offB, 0.001f)
    }

    @Test
    fun testExposureControlsInCinemaPipeline() {
        val baseConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.NATIVE,
            exposure = 0.0f
        )
        cinemaEngine.updateConfig(baseConfig)
        val baseCurve = cinemaEngine.getTonemapCurve()
        val count = baseCurve.getPointCount(TonemapCurve.CHANNEL_RED)
        val baseMid = baseCurve.getPoint(TonemapCurve.CHANNEL_RED, count / 2).y

        // Positive exposure should lift the mid-tones
        cinemaEngine.updateConfig(baseConfig.copy(exposure = 0.5f))
        val positiveExpCurve = cinemaEngine.getTonemapCurve()
        val positiveMid = positiveExpCurve.getPoint(TonemapCurve.CHANNEL_RED, count / 2).y
        assertTrue("Positive exposure must increase mid-tones ($positiveMid > $baseMid)", positiveMid > baseMid)

        // Negative exposure should lower the mid-tones
        cinemaEngine.updateConfig(baseConfig.copy(exposure = -0.5f))
        val negativeExpCurve = cinemaEngine.getTonemapCurve()
        val negativeMid = negativeExpCurve.getPoint(TonemapCurve.CHANNEL_RED, count / 2).y
        assertTrue("Negative exposure must decrease mid-tones ($negativeMid < $baseMid)", negativeMid < baseMid)
    }

    @Test
    fun testNoiseReductionLevelsInCaptureRequest() {
        val constructor = CaptureRequest.Builder::class.java.getDeclaredConstructor()
        constructor.isAccessible = true

        // CinemaNoiseReduction.OFF -> NOISE_REDUCTION_MODE_OFF
        val builderOff = constructor.newInstance()
        cinemaEngine.updateConfig(CinemaConfig(noiseReduction = CinemaNoiseReduction.OFF))
        cinemaEngine.applyToCaptureRequest(builderOff)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, builderOff.get(CaptureRequest.NOISE_REDUCTION_MODE))

        // CinemaNoiseReduction.LOW -> NOISE_REDUCTION_MODE_MINIMAL (or FAST if minimal unavailable)
        val builderLow = constructor.newInstance()
        cinemaEngine.updateConfig(CinemaConfig(noiseReduction = CinemaNoiseReduction.LOW))
        cinemaEngine.applyToCaptureRequest(builderLow)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL, builderLow.get(CaptureRequest.NOISE_REDUCTION_MODE))

        // CinemaNoiseReduction.HIGH -> NOISE_REDUCTION_MODE_HIGH_QUALITY
        val builderHigh = constructor.newInstance()
        cinemaEngine.updateConfig(CinemaConfig(noiseReduction = CinemaNoiseReduction.HIGH))
        cinemaEngine.applyToCaptureRequest(builderHigh)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, builderHigh.get(CaptureRequest.NOISE_REDUCTION_MODE))
    }

    @Test
    fun testCinematicLutsBakeAndColorSeparation() {
        val configUnbaked = CinemaConfig(
            colorProfile = CinemaColorProfile.FLAT_LOG,
            selectedLut = CinematicLut.TEAL_ORANGE,
            isBakeLutToOutput = false
        )
        assertFalse("LUT should not be baked into file if isBakeLutToOutput is false", configUnbaked.shouldBakeLut)

        val configBaked = CinemaConfig(
            colorProfile = CinemaColorProfile.FLAT_LOG,
            selectedLut = CinematicLut.TEAL_ORANGE,
            isBakeLutToOutput = true
        )
        assertTrue("LUT must be baked when selectedLut != NONE and isBakeLutToOutput is true", configBaked.shouldBakeLut)

        // Verify Hollywood cinematic LUT values are properly calibrated
        val tealOrange = CinematicLut.TEAL_ORANGE
        assertTrue(tealOrange.contrast > 1.0f)
        assertTrue(tealOrange.saturation > 1.0f)

        val warmCinema = CinematicLut.WARM_CINEMA
        assertTrue("Warm Cinema has filmic contrast", warmCinema.contrast > 1.0f)
        assertTrue("Warm Cinema has amber warmth offset", warmCinema.warmCoolOffset > 0f)

        val mutedFilm = CinematicLut.MUTED_FILM
        assertTrue("Muted Film has lifted shadow toe", mutedFilm.shadowToe > 0f)
        assertTrue("Muted Film has subdued saturation", mutedFilm.saturation < 1.0f)
    }

    @Test
    fun testWashedOutSliderReducesFlatPedestalAndRestoresContrast() {
        val flatConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.FLAT_LOG,
            washedOut = 0.0f
        )
        cinemaEngine.updateConfig(flatConfig)
        val flatCurve = cinemaEngine.getTonemapCurve()
        val flatBlack = flatCurve.getPoint(TonemapCurve.CHANNEL_RED, 0).y

        // Washed out slider at 1.0 should significantly lower black level / pedestal to eliminate hazy look
        val punchyConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.FLAT_LOG,
            washedOut = 1.0f
        )
        cinemaEngine.updateConfig(punchyConfig)
        val punchyCurve = cinemaEngine.getTonemapCurve()
        val punchyBlack = punchyCurve.getPoint(TonemapCurve.CHANNEL_RED, 0).y

        assertTrue("Washed-out slider must reduce shadow pedestal ($punchyBlack < $flatBlack)", punchyBlack < flatBlack)
    }

    @Test
    fun testNativeColorProfileUsesNaturalOetf() {
        val nativeConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.NATIVE,
            washedOut = 0f
        )
        cinemaEngine.updateConfig(nativeConfig)
        val curve = cinemaEngine.getTonemapCurve()
        val count = curve.getPointCount(TonemapCurve.CHANNEL_RED)

        // Native black point must be 0.0 without any lifted pedestal
        val blackPoint = curve.getPoint(TonemapCurve.CHANNEL_RED, 0)
        assertEquals(0.0f, blackPoint.y, 0.005f)

        // Native middle-grey should be standard Rec.709 OETF (~0.73)
        val midPoint = curve.getPoint(TonemapCurve.CHANNEL_RED, count / 2)
        assertTrue("Native mid-tone should match standard photographic gamma", midPoint.y in 0.65f..0.80f)
    }

    @Test
    fun testProRes10BitSoftwareRecorderPipeline() {
        val recorder = CinemaSoftwareRecordingEngine(context)
        val tempDest = File(context.cacheDir, "test_prores_10bit.mp4")

        try {
            val surface = recorder.startRecording(
                destFile = tempDest,
                width = 1920,
                height = 1080,
                fps = 24,
                bitrate = 90_000_000,
                codec = CinemaCodec.PRORES,
                bitDepth = LogBitDepth.BIT_10,
                isAudioEnabled = false
            )
            assertNotNull("Surface should be generated for ProRes 10-bit recording", surface)

            // Stopping recording should finalize the MP4 container
            val outFile = recorder.stopRecording()
            assertNotNull("Output file should be returned after stopping", outFile)
            assertTrue("Destination file should exist", outFile?.exists() == true)
        } finally {
            try { tempDest.delete() } catch (ignored: Exception) {}
        }
    }

    @Test
    fun test8BitStandardRecordingPipeline() {
        val recorder = CinemaSoftwareRecordingEngine(context)
        val tempDest = File(context.cacheDir, "test_standard_8bit.mp4")

        try {
            val surface = recorder.startRecording(
                destFile = tempDest,
                width = 1280,
                height = 720,
                fps = 30,
                bitrate = 20_000_000,
                codec = CinemaCodec.H264,
                bitDepth = LogBitDepth.BIT_8,
                isAudioEnabled = false
            )
            assertNotNull("Surface should be created for 8-bit standard recording", surface)
            val outFile = recorder.stopRecording()
            assertNotNull(outFile)
        } finally {
            try { tempDest.delete() } catch (ignored: Exception) {}
        }
    }
}
