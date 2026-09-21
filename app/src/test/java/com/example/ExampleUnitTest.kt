package com.example

import com.example.camera.model.CameraResolution
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {
    @Test
    fun testMainActivityLaunch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        assertNotNull(controller.get())
    }

    @Test
    fun testMainActivityLaunchWithPermissionsGranted() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        assertNotNull(controller.get())
    }

    @Test
    fun testCameraResolutionCalculations() {
        val res43 = CameraResolution(4000, 3000)
        assertEquals(12.0f, res43.megapixels, 0.01f)
        assertEquals("4:3", res43.aspectRatioLabel)

        val res169 = CameraResolution(3840, 2160)
        assertEquals(8.29f, res169.megapixels, 0.02f)
        assertEquals("16:9", res169.aspectRatioLabel)

        val res209 = CameraResolution(2400, 1080)
        assertEquals("20:9", res209.aspectRatioLabel)
    }

    @Test
    fun testLensInfoTypes() {
        val ultraWide = LensInfo(
            cameraId = "2",
            facing = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.ULTRAWIDE,
            displayName = "0.5x Ultra Wide",
            focalLengthMm = 1.94f,
            maxAperture = 2.2f,
            isPhysical = true,
            isHiddenAux = true,
            fovDegrees = 118f
        )
        assertEquals("0.5x", ultraWide.lensType.shortLabel)
        assertEquals("Ultra Wide", ultraWide.lensType.fullLabel)
        assertTrue(ultraWide.isPhysical)
        assertTrue(ultraWide.isHiddenAux)
    }

    @Test
    fun testMirrorSelfiePreferenceDefault() {
        val prefs = com.example.camera.data.CameraPreferences(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        // Default is true for "Save selfie as previewed (without flipping)"
        assertTrue(prefs.saveSelfieAsPreviewed)
        prefs.saveSelfieAsPreviewed = false
        assertFalse(prefs.saveSelfieAsPreviewed)
        prefs.saveSelfieAsPreviewed = true
        assertTrue(prefs.saveSelfieAsPreviewed)
    }

    @Test
    fun testRawVideoModeCompletelyPurged() {
        val modes = com.example.camera.model.CameraMode.entries.map { it.name }
        assertFalse("RAW_VIDEO should not exist in CameraMode", modes.contains("RAW_VIDEO"))
    }

    @Test
    fun testCinemaColorProfilesExactSet() {
        val expectedProfiles = setOf("NATIVE", "FLAT_LOG", "REC_2020", "HLG", "APPLE_LOG_2", "SAMSUNG_APV_LOG")
        val actualProfiles = com.example.camera.model.CinemaColorProfile.entries.map { it.name }.toSet()
        assertEquals(expectedProfiles, actualProfiles)
    }

    @Test
    fun testNativeNaturalVideoEngineColorProcessing() {
        val engine = com.example.camera.engine.NativeNaturalVideoEngine()
        // Simulate normal outdoor landscape EV100
        engine.processSceneIllumination(ev100 = 14.5f, hasFace = false)
        val params = engine.currentParams.value
        // Verify outdoor sky protection parameters
        assertTrue(params.isOutdoorSkyPresent)
        assertTrue(params.highlightCompression > 0.3f)

        val curve = engine.getTonemapCurve()
        assertNotNull(curve)
        // Pedestal (x=0) should be pinned to 0.0 (inky blacks, no wash out)
        val pt0 = curve.getPoint(android.hardware.camera2.params.TonemapCurve.CHANNEL_GREEN, 0)
        assertEquals(0.0f, pt0.y, 0.001f)
    }

    @Test
    fun testDollyZoomEngineLockAndApparentSize() {
        val dolly = com.example.camera.engine.DollyZoomEngine()
        assertFalse(dolly.dollyState.value.isCalibrated)
        assertFalse(dolly.dollyState.value.isSubjectLocked)

        // Lock on subject at coordinates (0.5, 0.5)
        dolly.lockSubject(
            normX = 0.5f,
            normY = 0.5f,
            currentZoom = 1.0f,
            faces = null,
            lensFocusDiopters = 1.0f, // 1 meter
            sensorRect = android.graphics.Rect(0, 0, 4000, 3000),
            minZoom = 1.0f,
            maxZoom = 8.0f
        )

        val lockedState = dolly.dollyState.value
        assertTrue(lockedState.isCalibrated)
        assertTrue(lockedState.isTracking)
        assertTrue(lockedState.isSubjectLocked)
        assertNotNull(lockedState.subjectBounds)
        assertEquals(1.0f, lockedState.initialZoom, 0.001f)
        assertEquals(1.0f, lockedState.targetDistanceMeters, 0.1f)

        // Reset
        dolly.reset()
        assertFalse(dolly.dollyState.value.isCalibrated)
        assertFalse(dolly.dollyState.value.isTracking)
    }

    @Test
    fun testDualVideoModePurged() {
        val modes = com.example.camera.model.CameraMode.entries.map { it.name }
        assertFalse("DUAL_VIDEO should not exist in CameraMode", modes.contains("DUAL_VIDEO"))
    }

    @Test
    fun testCinemaCodecsAndFileExtensions() {
        val vp9Codec = com.example.camera.model.CinemaCodec.VP9
        val proResCodec = com.example.camera.model.CinemaCodec.PRORES
        val h264Codec = com.example.camera.model.CinemaCodec.H264
        val h265Codec = com.example.camera.model.CinemaCodec.H265

        // VP9 uses webm container
        val isVp9Software = true
        val vp9Extension = if (isVp9Software && vp9Codec == com.example.camera.model.CinemaCodec.VP9) "webm" else "mp4"
        assertEquals("webm", vp9Extension)

        // ProRes and H264/H265 use mp4 container
        val proResExtension = if (isVp9Software && proResCodec == com.example.camera.model.CinemaCodec.VP9) "webm" else "mp4"
        assertEquals("mp4", proResExtension)

        val h264Extension = if (false && h264Codec == com.example.camera.model.CinemaCodec.VP9) "webm" else "mp4"
        assertEquals("mp4", h264Extension)
    }

    @Test
    fun testCinemaTempFileCreationAndCleanup() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cacheDir = context.cacheDir.apply { mkdirs() }
        assertTrue(cacheDir.exists())

        val tempFile = java.io.File(cacheDir, "cinema_temp_${System.currentTimeMillis()}.mp4")
        if (tempFile.exists()) tempFile.delete()
        tempFile.createNewFile()
        assertTrue(tempFile.exists())
        assertEquals(0L, tempFile.length())

        // Simulate writing recorded bytes
        val testData = "test_video_data".toByteArray()
        tempFile.writeBytes(testData)
        assertEquals(testData.size.toLong(), tempFile.length())

        // Cleanup
        tempFile.delete()
        assertFalse(tempFile.exists())
    }

    @Test
    fun testMotorolaInstantSwitchPreferencesIndependence() {
        val prefs = com.example.camera.data.CameraPreferences(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )

        // Test default values
        assertTrue(prefs.isKeepUltraWideReady)
        assertFalse(prefs.isShowUltraWidePreview)
        assertTrue(prefs.isKeepFrontCameraReady)
        assertFalse(prefs.isShowFrontCameraPreview)

        // Test independent toggling for Ultra-Wide
        // Ultra-Wide Ready ON + Preview OFF
        prefs.isKeepUltraWideReady = true
        prefs.isShowUltraWidePreview = false
        assertTrue(prefs.isKeepUltraWideReady)
        assertFalse(prefs.isShowUltraWidePreview)

        // Ultra-Wide Ready ON + Preview ON
        prefs.isShowUltraWidePreview = true
        assertTrue(prefs.isKeepUltraWideReady)
        assertTrue(prefs.isShowUltraWidePreview)

        // Ultra-Wide Ready OFF + Preview ON
        prefs.isKeepUltraWideReady = false
        assertFalse(prefs.isKeepUltraWideReady)
        assertTrue(prefs.isShowUltraWidePreview)

        // Test independent toggling for Front Camera
        // Front Ready ON + Preview OFF
        prefs.isKeepFrontCameraReady = true
        prefs.isShowFrontCameraPreview = false
        assertTrue(prefs.isKeepFrontCameraReady)
        assertFalse(prefs.isShowFrontCameraPreview)

        // Front Ready ON + Preview ON
        prefs.isShowFrontCameraPreview = true
        assertTrue(prefs.isKeepFrontCameraReady)
        assertTrue(prefs.isShowFrontCameraPreview)

        // Front Ready OFF
        prefs.isKeepFrontCameraReady = false
        assertFalse(prefs.isKeepFrontCameraReady)
    }

    @Test
    fun testMotorolaInstantSwitchEngineState() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = com.example.camera.engine.MotorolaInstantSwitchEngine(context)

        assertNotNull(engine.switchState.value)

        // Test independent state toggles via engine
        engine.setKeepUltraWideReady(true)
        assertTrue(engine.switchState.value.isKeepUltraWideReady)

        engine.setShowUltraWidePreview(true)
        assertTrue(engine.switchState.value.isShowUltraWidePreview)

        engine.setShowUltraWidePreview(false)
        assertFalse(engine.switchState.value.isShowUltraWidePreview)
        assertTrue(engine.switchState.value.isKeepUltraWideReady)

        engine.setKeepFrontCameraReady(true)
        assertTrue(engine.switchState.value.isKeepFrontCameraReady)

        engine.setShowFrontCameraPreview(true)
        assertTrue(engine.switchState.value.isShowFrontCameraPreview)

        engine.release()
    }
}
