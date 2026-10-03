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
    fun testProcessedJpegColorProfileSmartphonePhotoRendering() {
        val jpegConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.PROCESSED_JPEG,
            washedOut = 0f
        )
        cinemaEngine.updateConfig(jpegConfig)
        val curve = cinemaEngine.getTonemapCurve()
        val count = curve.getPointCount(TonemapCurve.CHANNEL_RED)

        // PROCESSED_JPEG inky black anchoring: y must be strictly 0.0 at x = 0.0
        val blackPoint = curve.getPoint(TonemapCurve.CHANNEL_RED, 0)
        assertEquals(0.0f, blackPoint.y, 0.001f)

        // Midtone separation check around 50%
        val midPoint = curve.getPoint(TonemapCurve.CHANNEL_RED, count / 2)
        assertTrue("Midtone should have punchy contrast", midPoint.y in 0.55f..0.85f)

        // Highlight roll-off check near 100%
        val highPoint = curve.getPoint(TonemapCurve.CHANNEL_RED, count - 1)
        assertTrue("Highlight shoulder should smoothly roll off without clipping", highPoint.y in 0.95f..1.0f)
    }

    @Test
    fun testProResRejectedWithoutFakeFallbackWhenUnsupported() {
        val recorder = CinemaSoftwareRecordingEngine(context)
        val tempDest = File(context.cacheDir, "test_prores_unsupported.mov")

        try {
            // Attempting to record ProRes on a device without a ProRes encoder must throw
            // rather than secretly falling back to HEVC/AVC!
            var thrown = false
            try {
                recorder.startRecording(
                    destFile = tempDest,
                    width = 1920,
                    height = 1080,
                    fps = 24,
                    bitrate = 90_000_000,
                    codec = CinemaCodec.PRORES,
                    bitDepth = LogBitDepth.BIT_10,
                    isAudioEnabled = false
                )
            } catch (e: IllegalStateException) {
                thrown = true
                assertTrue("Exception must state ProRes is unavailable", e.message?.contains("ProRes") == true)
            }
            assertTrue("ProRes recording without actual encoder must fail honestly without fake fallback", thrown)
        } finally {
            try { tempDest.delete() } catch (ignored: Exception) {}
        }
    }

    @Test
    fun testCodecAndBitDepthCapabilitiesHonesty() {
        val detection = CinemaEngine.detectEncoders()
        assertNotNull(detection)
        assertTrue("Must support at least one standard video codec", detection.supportedCodecs.isNotEmpty())

        // ProRes must not be reported as supported unless an actual encoder exists
        if (!detection.proresSupported) {
            assertFalse("ProRes must be hidden from supportedCodecs when no encoder exists", detection.supportedCodecs.contains(CinemaCodec.PRORES))
        }

        // Capabilities must only expose supported bit depths
        val caps = cinemaEngine.capabilities
        if (!caps.supportsEndToEnd10Bit) {
            assertFalse("10-bit option must be completely hidden if not end-to-end supported", caps.supportedBitDepths.contains(LogBitDepth.BIT_10))
            assertFalse("HLG10 HDR must be hidden if 10-bit is not end-to-end supported", caps.supportedColorProfiles.contains(CinemaColorProfile.HLG10))
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

    @Test
    fun testHlg10ProfileTonemapAndRec2020Gamut() {
        val config = CinemaConfig(
            colorProfile = CinemaColorProfile.HLG10,
            colorSpace = CinemaColorSpace.REC_2020,
            logBitDepth = LogBitDepth.BIT_10,
            codec = CinemaCodec.H265
        )
        cinemaEngine.updateConfig(config)
        val curve = cinemaEngine.getTonemapCurve()
        assertNotNull(curve)

        // Anchors inky black at 0 (<= 0.01)
        val blackPoint = curve.getPoint(TonemapCurve.CHANNEL_GREEN, 0)
        assertEquals(0.0f, blackPoint.x, 0.001f)
        assertTrue("HLG10 black point must be true deep black (<=0.01f), got ${blackPoint.y}", blackPoint.y <= 0.01f)

        // Middle gray and highlight range
        val count = curve.getPointCount(TonemapCurve.CHANNEL_GREEN)
        val midPoint = curve.getPoint(TonemapCurve.CHANNEL_GREEN, count / 2)
        assertTrue("HLG10 mid-tone should have natural non-flat curve, got ${midPoint.y}", midPoint.y in 0.50f..0.95f)
    }

    @Test
    fun testHlg10ColorPipelineViewfinderMatrix() {
        val config = CinemaConfig(
            colorProfile = CinemaColorProfile.HLG10,
            colorSpace = CinemaColorSpace.REC_2020,
            logBitDepth = LogBitDepth.BIT_10,
            codec = CinemaCodec.H265
        )
        val matrix = CinemaColorPipeline.computeCinemaColorMatrix(config)
        assertNotNull("HLG10 must produce a non-null ColorMatrix for the live viewfinder and export", matrix)
        val array = matrix!!.array
        assertEquals(20, array.size)
        // Verify non-zero contrast and saturation transform
        assertTrue("Contrast diagonal should be non-zero", array[0] > 1.0f)
        // Verify zero pedestal offset so blacks are strictly uncrushed
        assertEquals(0.0f, array[4], 0.001f)
        assertEquals(0.0f, array[9], 0.001f)
        assertEquals(0.0f, array[14], 0.001f)
    }

    @Test
    fun testHlg10AribOetfContinuousAndMonotonic() {
        // Test values across low toe, split point, and log shoulder
        val testInputs = floatArrayOf(0.0f, 0.01f, 0.04f, 0.083333f, 0.18f, 0.5f, 1.0f)
        var prevOutput = -1.0f
        for (x in testInputs) {
            val y = Hlg10AutoExposureEngine.evaluateAribOetf(x)
            assertTrue("OETF output must be in [0, 1], got $y for x=$x", y in 0.0f..1.0f)
            assertTrue("OETF must be strictly monotonic, got $y <= $prevOutput for x=$x", y >= prevOutput)
            prevOutput = y
        }
        // At 0, output must be 0
        assertEquals(0.0f, Hlg10AutoExposureEngine.evaluateAribOetf(0.0f), 0.0001f)
        // At 1/12 (0.083333f), output should be 0.5
        assertEquals(0.5f, Hlg10AutoExposureEngine.evaluateAribOetf(1.0f / 12.0f), 0.01f)
    }

    @Test
    fun testHlg10AutoExposureEngineReset() {
        val hlgEngine = Hlg10AutoExposureEngine()
        hlgEngine.reset()
        val params = hlgEngine.currentParams.value
        assertEquals(0.0f, params.exposureComp, 0.001f)
        assertEquals(0.5f, params.sceneLuxIndex, 0.001f)
    }

    @Test
    fun testHlg10SoftwareRecordingEngineConfiguration() {
        val recorder = CinemaSoftwareRecordingEngine(context)
        val tempDest = File(context.cacheDir, "test_hlg10_rec.mp4")

        try {
            val surface = recorder.startRecording(
                destFile = tempDest,
                width = 1920,
                height = 1080,
                fps = 24,
                bitrate = 60_000_000,
                codec = CinemaCodec.H265,
                bitDepth = LogBitDepth.BIT_10,
                isAudioEnabled = false,
                colorProfile = CinemaColorProfile.HLG10,
                colorSpace = CinemaColorSpace.REC_2020
            )
            assertNotNull("Surface should be generated for HLG10 10-bit recording", surface)
            recorder.stopRecording()
        } finally {
            try { tempDest.delete() } catch (ignored: Exception) {}
        }
    }

    @Test
    fun testVibrantGreenLutSelectiveFoliageBoostAndSkinToneProtection() {
        assertTrue(
            "Vibrant Green / Punchy Green LUT must be in displayPresets",
            CinematicLut.displayPresets.contains(CinematicLut.VIBRANT_GREEN)
        )
        assertEquals("Vibrant Green / Punchy Green", CinematicLut.VIBRANT_GREEN.label)

        val baseConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.NATIVE,
            selectedLut = CinematicLut.NONE
        )
        val vibrantGreenConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.NATIVE,
            selectedLut = CinematicLut.VIBRANT_GREEN,
            lutIntensity = 1.0f
        )

        // 1. Test Foliage / Green pixel (e.g. natural leaf green)
        val foliageR = 0.24f
        val foliageG = 0.56f
        val foliageB = 0.20f
        val baseFoliage = CinemaColorPipeline.evaluatePixel(foliageR, foliageG, foliageB, baseConfig)
        val gradedFoliage = CinemaColorPipeline.evaluatePixel(foliageR, foliageG, foliageB, vibrantGreenConfig)

        val baseFoliageSat = maxOf(baseFoliage[0], baseFoliage[1], baseFoliage[2]) -
                minOf(baseFoliage[0], baseFoliage[1], baseFoliage[2])
        val gradedFoliageSat = maxOf(gradedFoliage[0], gradedFoliage[1], gradedFoliage[2]) -
                minOf(gradedFoliage[0], gradedFoliage[1], gradedFoliage[2])

        assertTrue(
            "Foliage green saturation must be noticeably boosted ($gradedFoliageSat > ${baseFoliageSat * 1.25f})",
            gradedFoliageSat > baseFoliageSat * 1.25f
        )
        assertTrue(
            "Green channel dominance over red/blue must increase for punchy greens",
            (gradedFoliage[1] - gradedFoliage[0]) > (baseFoliage[1] - baseFoliage[0]) + 0.10f
        )

        // 2. Test Human Skin Tone pixel (natural warm skin: R=0.76, G=0.58, B=0.48)
        val skinR = 0.76f
        val skinG = 0.58f
        val skinB = 0.48f
        val baseSkin = CinemaColorPipeline.evaluatePixel(skinR, skinG, skinB, baseConfig)
        val gradedSkin = CinemaColorPipeline.evaluatePixel(skinR, skinG, skinB, vibrantGreenConfig)

        val baseSkinSat = maxOf(baseSkin[0], baseSkin[1], baseSkin[2]) -
                minOf(baseSkin[0], baseSkin[1], baseSkin[2])
        val gradedSkinSat = maxOf(gradedSkin[0], gradedSkin[1], gradedSkin[2]) -
                minOf(gradedSkin[0], gradedSkin[1], gradedSkin[2])

        // MOST IMPORTANT: Do not increase skin saturation or make skin unnaturally orange/red
        assertTrue(
            "Skin saturation must NOT increase ($gradedSkinSat <= $baseSkinSat + 0.005f)",
            gradedSkinSat <= baseSkinSat + 0.005f
        )
        val baseSkinRedOrangeSpread = baseSkin[0] - baseSkin[1]
        val gradedSkinRedOrangeSpread = gradedSkin[0] - gradedSkin[1]
        assertTrue(
            "Skin must not become more orange/red ($gradedSkinRedOrangeSpread <= $baseSkinRedOrangeSpread + 0.002f)",
            gradedSkinRedOrangeSpread <= baseSkinRedOrangeSpread + 0.002f
        )

        // Keep skin tones natural, clean and slightly bright/fair-looking
        val baseSkinLuma = 0.2126f * baseSkin[0] + 0.7152f * baseSkin[1] + 0.0722f * baseSkin[2]
        val gradedSkinLuma = 0.2126f * gradedSkin[0] + 0.7152f * gradedSkin[1] + 0.0722f * gradedSkin[2]
        assertTrue(
            "Skin tone should remain clean and slightly bright/fair-looking ($gradedSkinLuma >= $baseSkinLuma)",
            gradedSkinLuma >= baseSkinLuma
        )
    }

    @Test
    fun testIndependentShadowsHighlightsAndSkinProtectedVibrance() {
        val neutralConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.NATIVE,
            selectedLut = CinematicLut.NONE,
            shadows = 0f,
            highlights = 0f,
            vibrance = 0f
        )

        // 1. Shadows independence: affects dark regions much more than highlights
        val shadowsLiftConfig = neutralConfig.copy(shadows = 0.8f)
        val darkModifiedByShadows = CinemaColorPipeline.evaluatePixel(0.15f, 0.15f, 0.15f, shadowsLiftConfig)
        val brightModifiedByShadows = CinemaColorPipeline.evaluatePixel(0.85f, 0.85f, 0.85f, shadowsLiftConfig)
        val shadowLiftOnDark = darkModifiedByShadows[0] - 0.15f
        val shadowLiftOnBright = abs(brightModifiedByShadows[0] - 0.85f)
        assertTrue("Shadows control must lift dark pixels ($shadowLiftOnDark > 0.05f)", shadowLiftOnDark > 0.05f)
        assertEquals("Shadows control must leave bright highlights untouched", 0.0f, shadowLiftOnBright, 0.001f)

        // 2. Highlights independence: affects bright regions while leaving deep shadows untouched
        val highlightsConfig = neutralConfig.copy(highlights = -0.8f)
        val darkModifiedByHighlights = CinemaColorPipeline.evaluatePixel(0.15f, 0.15f, 0.15f, highlightsConfig)
        val brightModifiedByHighlights = CinemaColorPipeline.evaluatePixel(0.85f, 0.85f, 0.85f, highlightsConfig)
        val highlightChangeOnDark = abs(darkModifiedByHighlights[0] - 0.15f)
        val highlightPullOnBright = 0.85f - brightModifiedByHighlights[0]
        assertEquals("Highlights control must leave dark shadows untouched", 0.0f, highlightChangeOnDark, 0.001f)
        assertTrue("Highlights control must adjust bright pixels ($highlightPullOnBright > 0.05f)", highlightPullOnBright > 0.05f)

        // 3. Vibrance independence and skin-tone protection
        val vibranceConfig = neutralConfig.copy(vibrance = 0.9f)
        val mutedFoliage = CinemaColorPipeline.evaluatePixel(0.32f, 0.48f, 0.34f, vibranceConfig)
        val mutedFoliageBaseSat = 0.48f - 0.32f
        val mutedFoliageVibSat = maxOf(mutedFoliage[0], mutedFoliage[1], mutedFoliage[2]) -
                minOf(mutedFoliage[0], mutedFoliage[1], mutedFoliage[2])
        assertTrue(
            "Vibrance must noticeably increase saturation of muted non-skin colors ($mutedFoliageVibSat > $mutedFoliageBaseSat)",
            mutedFoliageVibSat > mutedFoliageBaseSat * 1.25f
        )

        val skinPixel = CinemaColorPipeline.evaluatePixel(0.76f, 0.58f, 0.48f, vibranceConfig)
        val skinBaseSat = 0.76f - 0.48f
        val skinVibSat = maxOf(skinPixel[0], skinPixel[1], skinPixel[2]) -
                minOf(skinPixel[0], skinPixel[1], skinPixel[2])
        val skinSatIncreaseRatio = skinVibSat / skinBaseSat
        val nonSkinSatIncreaseRatio = mutedFoliageVibSat / mutedFoliageBaseSat
        assertTrue(
            "Vibrance must protect skin tones from oversaturation ($skinSatIncreaseRatio << $nonSkinSatIncreaseRatio)",
            skinSatIncreaseRatio < 1.10f && nonSkinSatIncreaseRatio > skinSatIncreaseRatio + 0.20f
        )
    }
}
