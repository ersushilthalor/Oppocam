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
            selectedLut = CinematicLut.BLOCKBUSTER,
            isBakeLutToOutput = false
        )
        assertFalse("LUT should not be baked into file if isBakeLutToOutput is false", configUnbaked.shouldBakeLut)

        val configBaked = CinemaConfig(
            colorProfile = CinemaColorProfile.FLAT_LOG,
            selectedLut = CinematicLut.BLOCKBUSTER,
            isBakeLutToOutput = true
        )
        assertTrue("LUT must be baked when selectedLut is not OFF and isBakeLutToOutput is true", configBaked.shouldBakeLut)

        // Verify the 5 required presets are present in displayPresets
        val presetIds = CinematicLut.displayPresets.map { it.id }
        assertTrue("Must contain OFF", presetIds.contains("off"))
        assertTrue("Must contain STANDARD", presetIds.contains("standard"))
        assertTrue("Must contain BLOCKBUSTER", presetIds.contains("blockbuster"))
        assertTrue("Must contain THRILLER", presetIds.contains("thriller"))
        assertTrue("Must contain WEDDING", presetIds.contains("wedding"))
        assertTrue("Must contain CUSTOM", presetIds.contains("custom_cube"))

        // Verify distinct cinematic tonal character for each preset
        val off = CinematicLut.OFF
        assertEquals(1.0f, off.contrast, 0.001f)
        assertEquals(1.0f, off.saturation, 0.001f)
        assertEquals(0.0f, off.blacksToe, 0.001f)
        assertEquals(0.0f, off.shadowToe, 0.001f)

        val standard = CinematicLut.STANDARD
        assertTrue("Standard has refined film contrast", standard.contrast > 1.05f)
        assertTrue("Standard has inky anchored blacks", standard.blacksToe < 0f)
        assertTrue("Standard has open organic shadows", standard.shadowToe > 0f)
        assertTrue("Standard has organic highlight roll-off", standard.highlightRollOff > 0.60f)

        val blockbuster = CinematicLut.BLOCKBUSTER
        assertTrue("Blockbuster has bold S-curve contrast", blockbuster.contrast > 1.20f)
        assertTrue("Blockbuster has rich saturation", blockbuster.saturation > 1.10f)
        assertTrue("Blockbuster has deep inky blacks", blockbuster.blacksToe < -0.04f)
        assertTrue("Blockbuster has dense shadows", blockbuster.shadowToe < 0f)
        assertTrue("Blockbuster has warm/amber midtone offset", blockbuster.warmCoolOffset > 0.04f)

        val thriller = CinematicLut.THRILLER
        assertTrue("Thriller has high micro-contrast", thriller.contrast > 1.25f)
        assertTrue("Thriller has subdued cold saturation", thriller.saturation < 0.90f)
        assertTrue("Thriller has cold slate-blue color offset", thriller.warmCoolOffset < -0.15f)
        assertTrue("Thriller has deep darks", thriller.blacksToe < -0.05f)

        val wedding = CinematicLut.WEDDING
        assertTrue("Wedding has lifted matte velvety blacks", wedding.blacksToe > 0.03f)
        assertTrue("Wedding has luminous lifted shadow toe", wedding.shadowToe > 0.05f)
        assertTrue("Wedding has radiant midtones", wedding.midtonesGain > 1.05f)
        assertTrue("Wedding has ultra-soft highlight shoulder", wedding.highlightRollOff > 0.80f)
        assertTrue("Wedding has warm champagne tint", wedding.warmCoolOffset > 0.10f)
    }

    @Test
    fun testAllLutsProduceDistinctTonalCurvesAndManualControlsWorkAfterLut() {
        // Test pixel values sampled through all LUTs
        val testR = 0.5f
        val testG = 0.45f
        val testB = 0.4f

        val offPixel = CinemaColorPipeline.samplePresetLut(CinematicLut.OFF, testR, testG, testB)
        val standardPixel = CinemaColorPipeline.samplePresetLut(CinematicLut.STANDARD, testR, testG, testB)
        val blockbusterPixel = CinemaColorPipeline.samplePresetLut(CinematicLut.BLOCKBUSTER, testR, testG, testB)
        val thrillerPixel = CinemaColorPipeline.samplePresetLut(CinematicLut.THRILLER, testR, testG, testB)
        val weddingPixel = CinemaColorPipeline.samplePresetLut(CinematicLut.WEDDING, testR, testG, testB)

        // OFF must equal input
        assertEquals(testR, offPixel[0], 0.01f)
        assertEquals(testG, offPixel[1], 0.01f)
        assertEquals(testB, offPixel[2], 0.01f)

        // Every LUT must produce distinct chromatic and tonal changes
        assertFalse("Blockbuster must differ from Standard", blockbusterPixel.contentEquals(standardPixel))
        assertFalse("Thriller must differ from Standard", thrillerPixel.contentEquals(standardPixel))
        assertFalse("Wedding must differ from Standard", weddingPixel.contentEquals(standardPixel))
        assertFalse("Thriller must differ from Blockbuster", thrillerPixel.contentEquals(blockbusterPixel))

        // Verify that manual controls (Exposure, Contrast, Blacks, Shadows, Midtones, Highlights, Whites, Saturation, Vibrance)
        // apply cleanly AFTER the LUT:
        val lutOnlyConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.NATIVE,
            selectedLut = CinematicLut.BLOCKBUSTER,
            lutIntensity = 1.0f
        )
        val lutOnlyPixel = CinemaColorPipeline.evaluatePixel(0.5f, 0.45f, 0.4f, lutOnlyConfig)

        // 1. Exposure manual control after LUT
        val expConfig = lutOnlyConfig.copy(exposure = 0.5f)
        val expPixel = CinemaColorPipeline.evaluatePixel(0.5f, 0.45f, 0.4f, expConfig)
        assertTrue("Exposure after LUT must brighten pixel", expPixel[0] > lutOnlyPixel[0])

        // 2. Shadows manual control after LUT (tested on shadow pixel 0.2)
        val lutShadowPixel = CinemaColorPipeline.evaluatePixel(0.2f, 0.2f, 0.2f, lutOnlyConfig)
        val shadowsConfig = lutOnlyConfig.copy(shadows = 0.6f)
        val shadowsLiftedPixel = CinemaColorPipeline.evaluatePixel(0.2f, 0.2f, 0.2f, shadowsConfig)
        assertTrue("Shadows after LUT must lift shadow pixel", shadowsLiftedPixel[0] > lutShadowPixel[0])

        // 3. Highlights manual control after LUT (tested on highlight pixel 0.65)
        val lutHighlightPixel = CinemaColorPipeline.evaluatePixel(0.65f, 0.65f, 0.65f, lutOnlyConfig)
        val highlightsConfig = lutOnlyConfig.copy(highlights = 0.6f)
        val highlightsBoostedPixel = CinemaColorPipeline.evaluatePixel(0.65f, 0.65f, 0.65f, highlightsConfig)
        assertTrue("Highlights after LUT must boost highlight pixel", highlightsBoostedPixel[0] > lutHighlightPixel[0])

        // 4. Contrast manual control after LUT
        val contrastConfig = lutOnlyConfig.copy(contrast = 0.5f)
        val contrastPixel = CinemaColorPipeline.evaluatePixel(0.65f, 0.65f, 0.65f, contrastConfig)
        assertTrue("Contrast after LUT affects highlights", contrastPixel[0] > lutHighlightPixel[0])

        // 5. Saturation manual control after LUT
        val satDesatConfig = lutOnlyConfig.copy(saturation = 0.0f)
        val desatPixel = CinemaColorPipeline.evaluatePixel(0.6f, 0.4f, 0.3f, satDesatConfig)
        assertEquals("Saturation 0 after LUT makes RGB equal", desatPixel[0], desatPixel[1], 0.015f)
        assertEquals("Saturation 0 after LUT makes RGB equal", desatPixel[1], desatPixel[2], 0.015f)
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
    fun testRealProResSoftwareRecordingPipeline() {
        val tempDest = File(context.cacheDir, "test_prores_real.mov")
        try {
            val width = 320
            val height = 240
            val session = com.example.camera.engine.prores.ProResSoftwareRecordingSession(
                context = context,
                destFile = tempDest,
                width = width,
                height = height,
                fps = 24,
                isAudioEnabled = true,
                colorProfile = CinemaColorProfile.REC_2020,
                colorSpace = CinemaColorSpace.REC_2020
            )
            session.start()

            // Generate genuine YUV_420_888 frame data:
            // Y plane: 320x240, U/V planes: 160x120
            val yBytes = ByteArray(width * height) { (it % 256).toByte() }
            val halfW = (width + 1) / 2
            val halfH = (height + 1) / 2
            val uBytes = ByteArray(halfW * halfH) { 128.toByte() }
            val vBytes = ByteArray(halfW * halfH) { 128.toByte() }

            // Encode 3 real video frames
            for (i in 0 until 3) {
                session.encodeManualFrame(
                    yBytes = yBytes,
                    uBytes = uBytes,
                    vBytes = vBytes,
                    yRowStride = width,
                    uRowStride = halfW,
                    vRowStride = halfW,
                    uPixelStride = 1,
                    vPixelStride = 1
                )
            }

            val outFile = session.stop()
            assertNotNull("Recorded file should exist and finalize successfully", outFile)
            assertTrue("Output file must exist", outFile!!.exists())
            assertTrue("Output file must have .mov extension", outFile.name.endsWith(".mov", ignoreCase = true))
            assertTrue("Output file must be non-empty (>1024 bytes)", outFile.length() > 1024L)

            // Deep MOV container verification: check ftyp, mdat, and moov atoms
            val bytes = outFile.readBytes()
            assertTrue("File must be at least 64 bytes", bytes.size >= 64)

            // Check ftyp atom
            val ftypType = String(bytes, 4, 4, Charsets.US_ASCII)
            val brand = String(bytes, 8, 4, Charsets.US_ASCII)
            assertEquals("ftyp", ftypType)
            assertEquals("qt  ", brand)

            // Search for mdat and moov atoms
            val fileContentStr = String(bytes, Charsets.ISO_8859_1)
            assertTrue("Output MOV must contain 'mdat' atom", fileContentStr.contains("mdat"))
            assertTrue("Output MOV must contain 'moov' atom", fileContentStr.contains("moov"))
            assertTrue("Output MOV must contain 'mvhd' movie header atom", fileContentStr.contains("mvhd"))
            assertTrue("Output MOV must contain 'trak' track atom", fileContentStr.contains("trak"))
            assertTrue("Output MOV must contain 'apcn' ProRes 422 sample entry", fileContentStr.contains("apcn"))
            assertTrue("Output MOV must contain 'Apple ProRes 422' compressor name", fileContentStr.contains("Apple ProRes 422"))
        } finally {
            try { tempDest.delete() } catch (ignored: Exception) {}
        }
    }

    @Test
    fun testProResZeroFramesAbortsWithoutCorruptOutput() {
        val tempDest = File(context.cacheDir, "test_prores_zero_frames.mov")
        try {
            val session = com.example.camera.engine.prores.ProResSoftwareRecordingSession(
                context = context,
                destFile = tempDest,
                width = 640,
                height = 480,
                fps = 24,
                isAudioEnabled = false,
                colorProfile = CinemaColorProfile.NATIVE,
                colorSpace = CinemaColorSpace.REC_709
            )
            session.start()
            // Do NOT encode any frames
            val outFile = session.stop()
            assertNull("Session with 0 encoded frames must return null and abort cleanly", outFile)
            assertFalse("Corrupted/incomplete temp file must be cleaned up", tempDest.exists())
        } finally {
            try { tempDest.delete() } catch (ignored: Exception) {}
        }
    }

    @Test
    fun testQuickTimeProResMuxerDynamicAtomSizes() {
        val tempDest = File(context.cacheDir, "test_muxer_dynamic_atoms.mov")
        try {
            val muxer = com.example.camera.engine.prores.QuickTimeProResMuxer(
                outputFile = tempDest,
                width = 1920,
                height = 1080,
                fps = 24,
                isAudioEnabled = true
            )
            muxer.start()

            // Write 2 dummy ProRes frames (using valid ProRes magic header)
            val dummyFrame = ByteArray(4096)
            java.nio.ByteBuffer.wrap(dummyFrame).apply {
                putInt(4096)
                putInt(com.example.camera.engine.prores.ProResEncoder.MAGIC_ICPF)
            }
            muxer.writeVideoFrame(dummyFrame)
            muxer.writeVideoFrame(dummyFrame)

            // Write uncompressed stereo PCM audio chunk (48kHz * 2 channels * 2 bytes = 4 bytes per frame)
            val audioPcm = ByteArray(3840) // 960 frames
            muxer.writeAudioChunk(audioPcm, audioPcm.size)

            val success = muxer.finish()
            assertTrue("Muxer finish should succeed with video & audio", success)
            assertTrue("File must exist", tempDest.exists())
            assertTrue("File length must be > 8KB", tempDest.length() > 8192L)

            val contentStr = String(tempDest.readBytes(), Charsets.ISO_8859_1)
            assertTrue("Must contain 'stsd'", contentStr.contains("stsd"))
            assertTrue("Must contain 'apcn'", contentStr.contains("apcn"))
            assertTrue("Must contain 'sowt' for PCM audio", contentStr.contains("sowt"))
            assertTrue("Must contain 'smhd' sound media header", contentStr.contains("smhd"))
        } finally {
            try { tempDest.delete() } catch (ignored: Exception) {}
        }
    }

    @Test
    fun testCodecAndBitDepthCapabilitiesHonesty() {
        val detection = CinemaEngine.detectEncoders()
        assertNotNull(detection)
        assertTrue("Must support at least one standard video codec", detection.supportedCodecs.isNotEmpty())

        // ProRes is supported via real ProRes 422 10-bit software encoder or hardware
        assertTrue("ProRes must be supported", detection.proresSupported)
        assertTrue("ProRes must be in supportedCodecs", detection.supportedCodecs.contains(CinemaCodec.PRORES))

        val caps = cinemaEngine.capabilities
        // HLG10 and HDR Log profiles must always be available
        assertTrue("HLG10 must always be available in supportedColorProfiles", caps.supportedColorProfiles.contains(CinemaColorProfile.HLG10))
        assertTrue("HDR Log must always be available in supportedColorProfiles", caps.supportedColorProfiles.contains(CinemaColorProfile.HDR_LOG))

        // On devices without hardware 10-bit, standard codecs (H.264/H.265) hide 10-bit
        if (!caps.supportsEndToEnd10Bit) {
            val h264Depths = caps.getSupportedBitDepthsForCodec(CinemaCodec.H264)
            assertFalse("10-bit option must be hidden for H.264", h264Depths.contains(LogBitDepth.BIT_10))
            val h265Depths = caps.getSupportedBitDepthsForCodec(CinemaCodec.H265)
            assertFalse("10-bit option must be hidden for H.265 on devices without 10-bit hardware", h265Depths.contains(LogBitDepth.BIT_10))
        }
        // ProRes always supports 10-bit
        val proresDepths = caps.getSupportedBitDepthsForCodec(CinemaCodec.PRORES)
        assertTrue("ProRes must support 10-bit", proresDepths.contains(LogBitDepth.BIT_10))
    }

    @Test
    fun testHdrLogProfileNaturalContrastAndHighlightLatitude() {
        val config = CinemaConfig(
            colorProfile = CinemaColorProfile.HDR_LOG,
            colorSpace = CinemaColorSpace.REC_709,
            shadows = 0f,
            highlights = 0f,
            contrast = 0f,
            exposure = 0f
        )
        cinemaEngine.updateConfig(config)
        val curve = cinemaEngine.getTonemapCurve()
        assertNotNull(curve)

        // 1. Inky black point: x=0.0 maps strictly to 0.0 (no milky pedestal lift)
        val blackPoint = curve.getPoint(TonemapCurve.CHANNEL_GREEN, 0)
        assertEquals(0.0f, blackPoint.x, 0.001f)
        assertEquals("HDR Log must anchor strictly at 0.0f black level", 0.0f, blackPoint.y, 0.001f)

        // 2. Middle gray (x=0.18): natural contrast, not flat/washed-out
        val count = curve.getPointCount(TonemapCurve.CHANNEL_GREEN)
        val midIdx = (count * 0.18f).toInt()
        val midPoint = curve.getPoint(TonemapCurve.CHANNEL_GREEN, midIdx)
        assertTrue("HDR Log midtone should have natural non-flat contrast, got ${midPoint.y}", midPoint.y in 0.15f..0.40f)

        // 3. Highlight shoulder (x > 0.5): rolls off smoothly up to 1.0 without hard clipping
        val highPoint = curve.getPoint(TonemapCurve.CHANNEL_GREEN, count - 1)
        assertEquals(1.0f, highPoint.x, 0.001f)
        assertEquals(1.0f, highPoint.y, 0.01f)

        // 4. Viewfinder ColorMatrix
        val matrix = CinemaColorPipeline.computeCinemaColorMatrix(config)
        assertNotNull("HDR Log must produce a non-null ColorMatrix for live viewfinder", matrix)
        val arr = matrix!!.array
        assertTrue("Contrast diagonal must be >= 1.0", arr[0] >= 1.0f)
        // Zero pedestal offset
        assertEquals(0.0f, arr[4], 0.001f)
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
    fun testBlockbusterLutColorSeparationAndSkinTones() {
        assertTrue(
            "Blockbuster LUT must be in displayPresets",
            CinematicLut.displayPresets.contains(CinematicLut.BLOCKBUSTER)
        )
        assertEquals("Blockbuster", CinematicLut.BLOCKBUSTER.label)

        val baseConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.NATIVE,
            selectedLut = CinematicLut.OFF
        )
        val blockbusterConfig = CinemaConfig(
            colorProfile = CinemaColorProfile.NATIVE,
            selectedLut = CinematicLut.BLOCKBUSTER,
            lutIntensity = 1.0f
        )

        // 1. Test Cool Shadow / Cyan region (e.g. R=0.15, G=0.30, B=0.35)
        val shadowR = 0.15f
        val shadowG = 0.30f
        val shadowB = 0.35f
        val gradedShadow = CinemaColorPipeline.evaluatePixel(shadowR, shadowG, shadowB, blockbusterConfig)
        assertTrue("Blockbuster must push blue/cyan in shadow/cool regions", gradedShadow[2] >= gradedShadow[0])

        // 2. Test Human Skin Tone pixel (natural warm skin: R=0.76, G=0.58, B=0.48)
        val skinR = 0.76f
        val skinG = 0.58f
        val skinB = 0.48f
        val baseSkin = CinemaColorPipeline.evaluatePixel(skinR, skinG, skinB, baseConfig)
        val gradedSkin = CinemaColorPipeline.evaluatePixel(skinR, skinG, skinB, blockbusterConfig)

        val gradedSkinLuma = 0.2126f * gradedSkin[0] + 0.7152f * gradedSkin[1] + 0.0722f * gradedSkin[2]
        assertTrue("Blockbuster skin tones should remain vibrant and well separated", gradedSkinLuma > 0.4f)
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

    @Test
    fun testProResEncoderChromaSubsamplingWithStrides() {
        val width = 128
        val height = 96
        val encoder = com.example.camera.engine.prores.ProResEncoder(width, height)

        // Realistic Camera2 YUV_420_888 layout:
        // Y: rowStride = 160 (with 32 padding bytes), pixelStride = 1
        // UV: interleaved or semi-planar, rowStride = 160, pixelStride = 2
        val yRowStride = 160
        val uvRowStride = 160
        val uPixelStride = 2
        val vPixelStride = 2

        val yBytes = ByteArray(yRowStride * height) { (it % 256).toByte() }
        val uvHeight = (height + 1) / 2
        val uBytes = ByteArray(uvRowStride * uvHeight) { 110.toByte() }
        val vBytes = ByteArray(uvRowStride * uvHeight) { 140.toByte() }

        val encoded = encoder.encodeFrame(
            yPlane = yBytes,
            uPlane = uBytes,
            vPlane = vBytes,
            yRowStride = yRowStride,
            uRowStride = uvRowStride,
            vRowStride = uvRowStride,
            uPixelStride = uPixelStride,
            vPixelStride = vPixelStride
        )

        assertNotNull("Encoded frame should not be null", encoded)
        assertTrue("Encoded frame size must be positive (> 256 bytes)", encoded.size > 256)

        // Verify ProRes container magic 'icpf' (0x69637066)
        val bb = java.nio.ByteBuffer.wrap(encoded)
        val frameSize = bb.getInt()
        val magic = bb.getInt()
        assertEquals("Encoded frame size field must match byte array length", encoded.size, frameSize)
        assertEquals("ProRes magic must be 'icpf'", com.example.camera.engine.prores.ProResEncoder.MAGIC_ICPF, magic)
    }

    @Test
    fun testProResBitstreamPictureHeaderAndSlices() {
        val width = 64
        val height = 64
        val encoder = com.example.camera.engine.prores.ProResEncoder(width, height)

        val yBytes = ByteArray(width * height) { 128.toByte() }
        val uvHeight = (height + 1) / 2
        val uvWidth = (width + 1) / 2
        val uBytes = ByteArray(uvWidth * uvHeight) { 128.toByte() }
        val vBytes = ByteArray(uvWidth * uvHeight) { 128.toByte() }

        val encoded = encoder.encodeFrame(
            yPlane = yBytes,
            uPlane = uBytes,
            vPlane = vBytes,
            yRowStride = width,
            uRowStride = uvWidth,
            vRowStride = uvWidth,
            uPixelStride = 1,
            vPixelStride = 1
        )

        val bb = java.nio.ByteBuffer.wrap(encoded)
        val frameSize = bb.getInt()
        val magic = bb.getInt()
        assertEquals(encoded.size, frameSize)
        assertEquals(com.example.camera.engine.prores.ProResEncoder.MAGIC_ICPF, magic)

        val hdrSize = bb.getShort().toInt() and 0xFFFF
        assertEquals(148, hdrSize)

        // Read up to picture header (offset = 8 + 148 = 156)
        bb.position(8 + hdrSize)
        val picHdrByte0 = bb.get().toInt() and 0xFF
        assertEquals("ProRes picture header byte 0 must be 0x40 (8 << 3 bits)", 0x40, picHdrByte0)

        val picDataSize = bb.getInt()
        assertTrue("Picture data size must be positive", picDataSize > 0)

        val totalSlices = bb.getShort().toInt() and 0xFFFF
        assertTrue("Total slices must be > 0", totalSlices > 0)

        val sliceFactor = bb.get().toInt() and 0xFF
        assertEquals("Slice factor for 8 MBs per slice must be 0x30", 0x30, sliceFactor)

        // Read first slice size from slice table
        val firstSliceSize = bb.getShort().toInt() and 0xFFFF
        assertTrue("Slice size must be > 6 bytes", firstSliceSize > 6)

        // Skip remaining slice table entries
        bb.position(8 + hdrSize + 8 + totalSlices * 2)
        val sliceHdrByte0 = bb.get().toInt() and 0xFF
        assertEquals("Slice header byte 0 must be 0x30 (6 << 3 bits)", 0x30, sliceHdrByte0)
        val qscale = bb.get().toInt() and 0xFF
        assertEquals(6, qscale)
    }

    @Test
    fun testProResQuickTimeMovMuxerIntegrity() {
        val tempDest = File(context.cacheDir, "test_prores_mux.mov")
        try {
            val muxer = com.example.camera.engine.prores.QuickTimeProResMuxer(
                outputFile = tempDest,
                width = 64,
                height = 64,
                fps = 24,
                isAudioEnabled = false
            )
            muxer.start()

            val encoder = com.example.camera.engine.prores.ProResEncoder(64, 64)
            val dummyY = ByteArray(64 * 64) { 128.toByte() }
            val dummyUV = ByteArray(32 * 32) { 128.toByte() }
            val frame = encoder.encodeFrame(dummyY, dummyUV, dummyUV, 64, 32, 32, 1, 1)

            muxer.writeVideoFrame(frame)
            muxer.writeVideoFrame(frame)

            val finished = muxer.finish()
            assertTrue("Muxer finish must return true", finished)
            assertTrue("MOV file must exist", tempDest.exists())
            assertTrue("MOV file size must be > 512 bytes", tempDest.length() > 512)

            // Validate atoms: ftyp -> mdat -> moov
            java.io.RandomAccessFile(tempDest, "r").use { raf ->
                val ftypSize = raf.readInt()
                val ftypType = raf.readInt()
                assertEquals("Must start with ftyp", 0x66747970, ftypType)

                // Seek past ftyp
                raf.seek(ftypSize.toLong())
                val mdatRaw = raf.readInt().toLong() and 0xFFFFFFFFL
                val mdatType = raf.readInt()
                assertEquals("Second atom must be mdat", 0x6D646174, mdatType)
            }
        } finally {
            try { tempDest.delete() } catch (_: Exception) {}
        }
    }

    @Test
    fun testVp9EncoderCapabilitySurfaceRequirement() {
        // Calling isVp9EncodingSupported with requireSurface=true must be robust and safe
        val supported = DeviceCompatibilityManager.isVp9EncodingSupported(
            width = 1920,
            height = 1080,
            fps = 30,
            requireSurface = true
        )
        // Check that hasEncoderForMime in CinemaSoftwareRecordingEngine accurately rejects when unsupported
        val engine = CinemaSoftwareRecordingEngine(context)
        // If device has no VP9 surface encoder, startRecording with VP9 must throw IllegalStateException
        if (!supported) {
            val tempDest = File(context.cacheDir, "test_vp9_surface.webm")
            try {
                var caught = false
                try {
                    engine.startRecording(
                        destFile = tempDest,
                        width = 1920,
                        height = 1080,
                        fps = 30,
                        bitrate = 10_000_000,
                        codec = CinemaCodec.VP9,
                        bitDepth = LogBitDepth.BIT_8,
                        isAudioEnabled = false
                    )
                } catch (e: IllegalStateException) {
                    caught = true
                    assertTrue("Exception message should clearly indicate missing surface encoder", e.message?.contains("VP9") == true)
                }
                assertTrue("VP9 startRecording must fail when genuine surface encoder is absent", caught)
            } finally {
                try { tempDest.delete() } catch (_: Exception) {}
            }
        }
    }

    @Test
    fun testCinemaConfigFineTuningDefaultsNeutral() {
        val defaultConfig = CinemaConfig()
        assertFalse("Default CinemaConfig must not trigger fine tuning", defaultConfig.hasColorFineTuning)
        assertEquals(0.0f, defaultConfig.temperature, 0.0001f)
        assertEquals(0.0f, defaultConfig.tint, 0.0001f)
        assertEquals(0.0f, defaultConfig.whites, 0.0001f)
        assertEquals(0.0f, defaultConfig.blacks, 0.0001f)
        assertEquals(0.0f, defaultConfig.midtones, 0.0001f)
        assertEquals(0.0f, defaultConfig.blackLevel, 0.0001f)
        assertEquals(0.0f, defaultConfig.highlightRolloff, 0.0001f)
        assertEquals(0.0f, defaultConfig.shadowRolloff, 0.0001f)
        assertEquals(0.0f, defaultConfig.localContrast, 0.0001f)
        assertEquals(0.0f, defaultConfig.lumaCurve, 0.0001f)
        assertEquals(0.0f, defaultConfig.colorTransform, 0.0001f)
        assertEquals(1.0f, defaultConfig.chromaStrength, 0.0001f)
        assertEquals(0.0f, defaultConfig.toneMappingStrength, 0.0001f)
        assertEquals(0.0f, defaultConfig.lumaNoiseReduction, 0.0001f)
        assertEquals(0.0f, defaultConfig.chromaNoiseReduction, 0.0001f)
        assertEquals(0.0f, defaultConfig.fineSharpening, 0.0001f)
        assertEquals(0.0f, defaultConfig.microContrast, 0.0001f)
        assertEquals(1.0f, defaultConfig.outputGamma, 0.0001f)
    }

    @Test
    fun testCinemaColorPipelineFineTuningActiveTriggers() {
        val baseConfig = CinemaConfig()
        assertFalse(CinemaColorPipeline.requiresSelectiveShader(baseConfig))

        val warmConfig = baseConfig.copy(temperature = 0.5f)
        assertTrue(warmConfig.hasColorFineTuning)
        assertTrue(CinemaColorPipeline.hasActiveTransform(warmConfig))
        assertTrue(CinemaColorPipeline.requiresSelectiveShader(warmConfig))

        val neutralPixel = floatArrayOf(0.5f, 0.5f, 0.5f)
        val warmResult = CinemaColorPipeline.evaluatePixel(neutralPixel[0], neutralPixel[1], neutralPixel[2], warmConfig)
        assertTrue("Warm temperature must boost red relative to blue", warmResult[0] > warmResult[2])

        val coolConfig = baseConfig.copy(temperature = -0.5f)
        val coolResult = CinemaColorPipeline.evaluatePixel(neutralPixel[0], neutralPixel[1], neutralPixel[2], coolConfig)
        assertTrue("Cool temperature must boost blue relative to red", coolResult[2] > coolResult[0])
    }

    @Test
    fun testCinemaColorPipelineTonalAndLumaAdjustments() {
        val baseConfig = CinemaConfig()
        // Test Black Level
        val liftedBlack = CinemaColorPipeline.evaluatePixel(0.0f, 0.0f, 0.0f, baseConfig.copy(blackLevel = 0.5f))
        assertTrue("Black level lift must brighten deep black", liftedBlack[0] > 0.0f)

        // Test Midtones
        val midResult = CinemaColorPipeline.evaluatePixel(0.5f, 0.5f, 0.5f, baseConfig.copy(midtones = 0.8f))
        assertTrue("Positive midtones must brighten middle-gray", midResult[0] > 0.5f)

        // Test Whites
        val whiteResult = CinemaColorPipeline.evaluatePixel(0.85f, 0.85f, 0.85f, baseConfig.copy(whites = 0.8f))
        assertTrue("Positive whites must brighten upper highlights", whiteResult[0] > 0.85f)

        // Test Output Gamma
        val gammaBright = CinemaColorPipeline.evaluatePixel(0.25f, 0.25f, 0.25f, baseConfig.copy(outputGamma = 0.7f))
        assertTrue("Gamma < 1.0 must lift midtone luminance", gammaBright[0] > 0.25f)
    }

    @Test
    fun testCinemaColorPipelineNoClippingOrNaNOnExtremeValues() {
        val extremeConfig = CinemaConfig(
            temperature = 1.0f,
            tint = 1.0f,
            whites = 1.0f,
            blacks = 1.0f,
            midtones = 1.0f,
            blackLevel = 1.0f,
            highlightRolloff = 1.0f,
            shadowRolloff = 1.0f,
            localContrast = 1.0f,
            lumaCurve = 1.0f,
            colorTransform = 1.0f,
            chromaStrength = 2.0f,
            toneMappingStrength = 1.0f,
            lumaNoiseReduction = 1.0f,
            chromaNoiseReduction = 1.0f,
            fineSharpening = 1.0f,
            microContrast = 1.0f,
            outputGamma = 0.5f
        )
        val testInputs = listOf(
            floatArrayOf(0.0f, 0.0f, 0.0f),
            floatArrayOf(0.1f, 0.2f, 0.3f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
            floatArrayOf(0.9f, 0.8f, 0.7f),
            floatArrayOf(1.0f, 1.0f, 1.0f)
        )
        for (input in testInputs) {
            val result = CinemaColorPipeline.evaluatePixel(input[0], input[1], input[2], extremeConfig)
            for (c in result) {
                assertFalse("Result must not be NaN", c.isNaN())
                assertFalse("Result must not be Infinite", c.isInfinite())
                assertTrue("Result must be clamped in [0, 1], got $c", c in 0.0f..1.0f)
            }
        }
    }

    @Test
    fun testPreferencesSaveAndRestoreFineTuningControls() {
        val prefs = com.example.camera.data.CameraPreferences(context)
        val customConfig = CinemaConfig(
            temperature = 0.35f,
            tint = -0.25f,
            whites = 0.40f,
            blacks = -0.15f,
            midtones = 0.20f,
            blackLevel = 0.05f,
            highlightRolloff = 0.30f,
            shadowRolloff = -0.10f,
            localContrast = 0.50f,
            lumaCurve = 0.22f,
            colorTransform = -0.45f,
            chromaStrength = 1.35f,
            toneMappingStrength = 0.80f,
            lumaNoiseReduction = 0.60f,
            chromaNoiseReduction = 0.75f,
            fineSharpening = 0.45f,
            microContrast = 0.30f,
            outputGamma = 1.15f
        )
        prefs.saveCinemaConfig(customConfig)
        val loaded = prefs.getCinemaConfig()

        assertEquals(0.35f, loaded.temperature, 0.001f)
        assertEquals(-0.25f, loaded.tint, 0.001f)
        assertEquals(0.40f, loaded.whites, 0.001f)
        assertEquals(-0.15f, loaded.blacks, 0.001f)
        assertEquals(0.20f, loaded.midtones, 0.001f)
        assertEquals(0.05f, loaded.blackLevel, 0.001f)
        assertEquals(0.30f, loaded.highlightRolloff, 0.001f)
        assertEquals(-0.10f, loaded.shadowRolloff, 0.001f)
        assertEquals(0.50f, loaded.localContrast, 0.001f)
        assertEquals(0.22f, loaded.lumaCurve, 0.001f)
        assertEquals(-0.45f, loaded.colorTransform, 0.001f)
        assertEquals(1.35f, loaded.chromaStrength, 0.001f)
        assertEquals(0.80f, loaded.toneMappingStrength, 0.001f)
        assertEquals(0.60f, loaded.lumaNoiseReduction, 0.001f)
        assertEquals(0.75f, loaded.chromaNoiseReduction, 0.001f)
        assertEquals(0.45f, loaded.fineSharpening, 0.001f)
        assertEquals(0.30f, loaded.microContrast, 0.001f)
        assertEquals(1.15f, loaded.outputGamma, 0.001f)
        assertTrue(loaded.hasColorFineTuning)
    }

    @Test
    fun testProRes10BitSoftwareDoesNotRequireHardwareEncoder() {
        val capsNoHw = CinemaHardwareCapabilities(
            supports10BitRecording = false,
            supportsHevc10Bit = false,
            supportsVp910Bit = false,
            is10BitAvailableOnHAL = false,
            supportsEndToEnd10Bit = false,
            supportsSoftwareProRes = true,
            isSoftware10BitSupported = true
        )

        // 1. ProRes MUST offer 10-bit even when hardware encoder supports only 8-bit
        val proresDepths = capsNoHw.getSupportedBitDepthsForCodec(CinemaCodec.PRORES)
        assertTrue("ProRes must offer 10-bit even when hardware 10-bit is absent", proresDepths.contains(LogBitDepth.BIT_10))

        // 2. VP9 MUST NOT offer 10-bit when hardware VP9 10-bit is absent
        val vp9Depths = capsNoHw.getSupportedBitDepthsForCodec(CinemaCodec.VP9)
        assertFalse("VP9 must NOT offer 10-bit when hardware VP9 10-bit is absent", vp9Depths.contains(LogBitDepth.BIT_10))

        // 3. Disabling VP9 10-bit must NOT disable ProRes software 10-bit
        assertTrue("Disabling VP9 10-bit must not disable ProRes software 10-bit", proresDepths.contains(LogBitDepth.BIT_10))
    }

    @Test
    fun testProResEncoder8BitTo10BitConversionAnd10BitNativeSupport() {
        val width = 64
        val height = 64

        // Case A: 8-bit source -> Software ProRes converts to 10-bit
        val encoder8b = com.example.camera.engine.prores.ProResEncoder(
            width = width,
            height = height,
            isSource10Bit = false
        )
        assertFalse("isSource10Bit must be false for 8-bit camera source", encoder8b.isSource10Bit)

        val yBytes8b = ByteArray(width * height) { 200.toByte() }
        val uvBytes8b = ByteArray((width / 2) * (height / 2)) { 128.toByte() }

        val frame8b = encoder8b.encodeFrame(
            yPlane = yBytes8b,
            uPlane = uvBytes8b,
            vPlane = uvBytes8b,
            yRowStride = width,
            uRowStride = width / 2,
            vRowStride = width / 2,
            uPixelStride = 1,
            vPixelStride = 1,
            isSource10Bit = false
        )
        assertNotNull(frame8b)
        assertTrue("Frame size must be positive", frame8b.size > 180)
        val bb8b = java.nio.ByteBuffer.wrap(frame8b)
        val frameSize8b = bb8b.getInt()
        val magic8b = bb8b.getInt()
        assertEquals("Encoded frame size field must match byte array length", frame8b.size, frameSize8b)
        assertEquals("Must produce valid ProRes icpf frame", com.example.camera.engine.prores.ProResEncoder.MAGIC_ICPF, magic8b)

        // Case B: Genuine 10-bit camera source (P010: 2 bytes per sample)
        val encoder10b = com.example.camera.engine.prores.ProResEncoder(
            width = width,
            height = height,
            isSource10Bit = true
        )
        assertTrue("isSource10Bit must be true for 10-bit camera source", encoder10b.isSource10Bit)

        // 10-bit samples in little-endian 16-bit words (e.g. 800 in 10-bit: 800 shl 6 = 51200)
        val yBytes10b = ByteArray(width * height * 2) { idx ->
            if (idx % 2 == 0) 0x20.toByte() else 0x03.toByte() // 800 in 10-bit
        }
        val uvBytes10b = ByteArray((width / 2) * (height / 2) * 2) { idx ->
            if (idx % 2 == 0) 0x00.toByte() else 0x02.toByte() // 512 midpoint
        }

        val frame10b = encoder10b.encodeFrame(
            yPlane = yBytes10b,
            uPlane = uvBytes10b,
            vPlane = uvBytes10b,
            yRowStride = width * 2,
            uRowStride = (width / 2) * 2,
            vRowStride = (width / 2) * 2,
            uPixelStride = 2,
            vPixelStride = 2,
            isSource10Bit = true
        )
        assertNotNull(frame10b)
        assertTrue("10-bit native encoded frame size must be positive", frame10b.size > 180)
        val bb10b = java.nio.ByteBuffer.wrap(frame10b)
        val frameSize10b = bb10b.getInt()
        val magic10b = bb10b.getInt()
        assertEquals("Encoded frame size field must match byte array length", frame10b.size, frameSize10b)
        assertEquals("Must produce valid ProRes icpf frame from 10-bit source", com.example.camera.engine.prores.ProResEncoder.MAGIC_ICPF, magic10b)
    }

    @Test
    fun testVp9NoSilentFallbackTo8Bit() {
        // When 10-bit VP9 is requested on a system without VP9 Profile 2 hardware encoder,
        // CinemaSoftwareRecordingEngine must fail with an explicit exception rather than silently recording 8-bit.
        val isProfile2Supported = DeviceCompatibilityManager.isVp9Profile2Supported()
        if (!isProfile2Supported) {
            val engine = CinemaSoftwareRecordingEngine(context)
            val tempDest = File(context.cacheDir, "test_vp9_10bit_honesty.webm")
            try {
                var caught = false
                try {
                    engine.startRecording(
                        destFile = tempDest,
                        width = 1920,
                        height = 1080,
                        fps = 30,
                        bitrate = 15_000_000,
                        codec = CinemaCodec.VP9,
                        bitDepth = LogBitDepth.BIT_10,
                        isAudioEnabled = false
                    )
                } catch (e: IllegalStateException) {
                    caught = true
                    assertTrue("Exception must state VP9 10-bit hardware recording is not supported",
                        e.message?.contains("VP9") == true)
                }
                assertTrue("Must reject 10-bit VP9 request without silent 8-bit fallback", caught)
            } finally {
                try { tempDest.delete() } catch (_: Exception) {}
            }
        }
    }

    @Test
    fun testCubeLutParserMatrix4x5BlueRowFix() {
        // Requirement 10: Fix the "matrix4x5" blue-row bug in "CubeLutParser" where "bG" was used incorrectly.
        val cubeContent = """
            TITLE "BlueRowTest"
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.1 0.2 0.9
            1.0 0.2 0.9
            0.1 1.0 0.9
            1.0 1.0 1.0
        """.trimIndent()

        val stream = java.io.ByteArrayInputStream(cubeContent.toByteArray(Charsets.UTF_8))
        val parsed = com.example.camera.engine.CubeLutParser.parse(stream, "BlueRowTest")
        assertNotNull(parsed)
        val m = parsed!!.matrix4x5
        // Row 2 is the Blue output row: indices 10, 11, 12, 13, 14
        // Must be rB, gB, bB, 0f, boff
        // greenCorner is at (0, 1, 0) -> R=0.0, G=1.0, B=0.0
        // gB is (greenCorner[2] - blackCorner[2]) = 0.0
        // In the buggy version, bG was used which was (blueCorner[1] - blackCorner[1]) = 0.2
        assertEquals(0.0f, m[11], 0.001f) // gB must be in row 2 column 1 (index 11)
        assertEquals(0.9f, m[12], 0.001f) // bB must be in row 2 column 2 (index 12)
    }

    @Test
    fun test3DLutSamplingPreservesNonlinearTonalCurves() {
        // Requirements 1, 2, 4, 12: Real 3D LUT sampling preserves nonlinear S-curves and tonal information
        // Build a 3D LUT with a distinct non-linear S-curve on midtones and shadow toe
        val sb = StringBuilder()
        sb.appendLine("TITLE \"NonlinearTonalLut\"")
        sb.appendLine("LUT_3D_SIZE 5")
        // Size 5: indices 0..4 for R, G, B
        for (b in 0..4) {
            for (g in 0..4) {
                for (r in 0..4) {
                    val rNorm = r / 4.0f
                    val gNorm = g / 4.0f
                    val bNorm = b / 4.0f
                    // Non-linear S-curve and black toe
                    val outR = if (rNorm <= 0.5f) {
                        0.5f * Math.pow((2.0 * rNorm), 2.2).toFloat()
                    } else {
                        1.0f - 0.5f * Math.pow((2.0 * (1.0 - rNorm)), 2.2).toFloat()
                    }
                    val outG = gNorm * 0.9f + 0.05f
                    val outB = bNorm * 1.1f - 0.02f
                    sb.appendLine("$outR $outG $outB")
                }
            }
        }

        val stream = java.io.ByteArrayInputStream(sb.toString().toByteArray(Charsets.UTF_8))
        val parsed = com.example.camera.data.CubeLutParser.parseStream(stream, "NonlinearTonalLut")
        assertNotNull(parsed)

        // Sample at middle grey (0.5, 0.5, 0.5)
        val sampled = parsed!!.sample3D(0.5f, 0.5f, 0.5f)
        assertEquals(0.5f, sampled[0], 0.02f)
        assertEquals(0.5f, sampled[1], 0.02f)

        // Sample at quarter tone (0.25, 0.25, 0.25)
        val quarterTone = parsed.sample3D(0.25f, 0.25f, 0.25f)
        // With gamma 2.2 toe, 0.5 * (0.5)^2.2 = ~0.109, far below linear 0.25
        assertTrue("3D LUT sampling must preserve nonlinear shadow compression", quarterTone[0] < 0.20f)
    }

    @Test
    fun testPipelinePreservesOrderAndStageSeparation() {
        // Requirements 5, 6, 7, 8:
        // Log/CST -> 3D LUT -> tonal/color grading controls -> output transform/gamma
        val config = CinemaConfig(
            colorProfile = CinemaColorProfile.FLAT_LOG,
            selectedLut = CinematicLut.BLOCKBUSTER,
            lutIntensity = 0.75f,
            exposure = 0.5f,
            contrast = 0.2f,
            shadows = -0.1f,
            highlights = 0.1f,
            saturation = 1.1f,
            outputGamma = 1.05f
        )

        // Evaluate pixel through pipeline
        val outRgb = CinemaColorPipeline.evaluatePixel(0.4f, 0.4f, 0.4f, config)
        assertNotNull(outRgb)
        assertEquals(3, outRgb.size)
        for (v in outRgb) {
            assertTrue("Pixel values must remain bounded in [0, 1]", v in 0.0f..1.0f)
        }

        // For GPU shader, computeCinemaColorMatrix should NOT bake the creative LUT
        val gpuMatrix = CinemaColorPipeline.computeCinemaColorMatrix(config, forGpuShader = true)
        val fallbackMatrix = CinemaColorPipeline.computeCinemaColorMatrix(config, forGpuShader = false)
        assertNotNull(gpuMatrix)
        assertNotNull(fallbackMatrix)
        // Fallback matrix includes LUT and primary grade, whereas gpuMatrix contains only CST technical transform
        assertFalse("GPU matrix must differ from CPU fallback matrix", gpuMatrix!!.array.contentEquals(fallbackMatrix!!.array))
    }
}

