package com.example.camera.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.camera.model.CameraMode
import com.example.camera.model.MainCameraStabilizationMode
import com.example.camera.model.VideoStabilizationMode
import com.example.camera.viewmodel.CameraViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OisEisStabilizationEngineTest {

    private lateinit var app: Application
    private lateinit var viewModel: CameraViewModel

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext<Application>()
        viewModel = CameraViewModel(app)
    }

    @Test
    fun testEisFunctionalToggleAndPersistenceInVideoAndCinemaModes() {
        // Test enabling EIS
        viewModel.setVideoStabilization(true)
        assertTrue("EIS must be enabled", viewModel.isVideoStabilizationEnabled.value)
        assertTrue("Engine EIS must be true", viewModel.engine.isVideoStabilizationEnabled)
        assertEquals(VideoStabilizationMode.EIS, viewModel.videoStabilizationMode.value)

        // Switching to Video Mode preserves EIS
        viewModel.setCameraMode(CameraMode.VIDEO)
        assertTrue("Video mode must preserve enabled EIS", viewModel.isVideoStabilizationEnabled.value)

        // Switching to Cinema Mode preserves EIS
        viewModel.setCameraMode(CameraMode.CINEMA)
        assertTrue("Cinema mode must preserve enabled EIS", viewModel.isVideoStabilizationEnabled.value)

        // Toggling EIS OFF
        viewModel.setVideoStabilization(false)
        assertFalse("EIS must be disabled after toggle", viewModel.isVideoStabilizationEnabled.value)
        assertFalse("Engine EIS must be false", viewModel.engine.isVideoStabilizationEnabled)
        assertEquals(VideoStabilizationMode.OFF, viewModel.videoStabilizationMode.value)

        // Toggling EIS back ON
        viewModel.toggleVideoStabilization()
        assertTrue("EIS must be re-enabled after toggle", viewModel.isVideoStabilizationEnabled.value)
        assertTrue("Engine EIS must be true after toggle", viewModel.engine.isVideoStabilizationEnabled)
    }

    @Test
    fun testOisToggleDirectlyOnAndOff() {
        // Default: Hardware OIS ON
        assertTrue("Default OIS should be ON", viewModel.isOisEnabled.value)
        assertTrue("Engine OIS should be ON", viewModel.engine.isOisEnabled)

        // Turn OIS OFF
        viewModel.setOisEnabled(false)
        assertFalse("OIS should be OFF after toggle", viewModel.isOisEnabled.value)
        assertFalse("Engine OIS should be OFF", viewModel.engine.isOisEnabled)
        assertFalse("Hybrid config isOisEnabled should be false", viewModel.hybridStabilizationConfig.value.isOisEnabled)
        assertFalse("Hybrid config isOisPreferred should be false", viewModel.hybridStabilizationConfig.value.isOisPreferred)

        // Turn OIS back ON
        viewModel.setOisEnabled(true)
        assertTrue("OIS should be ON after toggle", viewModel.isOisEnabled.value)
        assertTrue("Engine OIS should be ON", viewModel.engine.isOisEnabled)
        assertTrue("Hybrid config isOisEnabled should be true", viewModel.hybridStabilizationConfig.value.isOisEnabled)
        assertTrue("Hybrid config isOisPreferred should be true", viewModel.hybridStabilizationConfig.value.isOisPreferred)
    }

    @Test
    fun testMainCameraStabilizationPreservesOisWhileEisIsOff() {
        // Selecting OIS_ONLY enables OIS and leaves EIS strictly OFF
        viewModel.setMainCameraStabilizationMode(MainCameraStabilizationMode.OIS_ONLY)
        assertTrue("OIS must be enabled", viewModel.isOisEnabled.value)
        assertTrue("Engine OIS must be enabled", viewModel.engine.isOisEnabled)
        assertFalse("EIS must be strictly OFF", viewModel.isVideoStabilizationEnabled.value)

        // Selecting HYBRID_OIS_EIS enables both OIS and EIS
        viewModel.setMainCameraStabilizationMode(MainCameraStabilizationMode.HYBRID_OIS_EIS)
        assertTrue("OIS must be enabled in hybrid mode", viewModel.isOisEnabled.value)
        assertTrue("EIS must be enabled in hybrid mode", viewModel.isVideoStabilizationEnabled.value)

        // Selecting OFF disables OIS and EIS
        viewModel.setMainCameraStabilizationMode(MainCameraStabilizationMode.OFF)
        assertFalse("OIS must be disabled", viewModel.isOisEnabled.value)
        assertFalse("Engine OIS must be disabled", viewModel.engine.isOisEnabled)
        assertFalse("EIS must remain OFF", viewModel.isVideoStabilizationEnabled.value)
    }

    @Test
    fun testFrontCameraNativeFieldOfViewAndNoLensSelector() {
        viewModel.engine.detectHardwareLenses()
        val frontLens = viewModel.engine.availableLenses.value.firstOrNull {
            it.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        }

        if (frontLens != null) {
            viewModel.selectLens(frontLens)

            // Front camera presets must be empty (0.8x and 1x removed)
            val presets = viewModel.activeZoomPresets.value
            assertTrue("Front camera must not display 0.8x or 1x presets", presets.isEmpty())

            // Front camera displayedLenses must be empty (no lens selector / controls)
            val displayed = viewModel.displayedLenses.value
            assertTrue("Front camera must not display lens selector", displayed.isEmpty())

            // Front camera must use native uncropped 1.0x sensor FOV by default
            val crop = CameraOpticalCalibration.calculateRequiredDigitalCrop(1.0f, 1.0f, com.example.camera.model.LensType.FRONT)
            assertEquals("Front camera must use native uncropped 1.0x sensor FOV", 1.0f, crop, 0.001f)
            assertEquals(1.0f, viewModel.currentZoom.value, 0.001f)
        }
    }

    @Test
    fun testFrontCameraPreservesFacingAcrossModeChanges() {
        viewModel.engine.detectHardwareLenses()
        val frontLens = viewModel.engine.availableLenses.value.firstOrNull {
            it.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        }

        if (frontLens != null) {
            viewModel.selectLens(frontLens)
            assertEquals(android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT, viewModel.engine.selectedLens.value?.facing)

            // Switching to VIDEO mode must preserve FRONT camera
            viewModel.setCameraMode(CameraMode.VIDEO)
            assertEquals(android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT, viewModel.engine.selectedLens.value?.facing)

            // Switching to CINEMA (Pro Video) mode must preserve FRONT camera
            viewModel.setCameraMode(CameraMode.CINEMA)
            assertEquals(android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT, viewModel.engine.selectedLens.value?.facing)

            // Switching back to PHOTO mode must preserve FRONT camera
            viewModel.setCameraMode(CameraMode.PHOTO)
            assertEquals(android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT, viewModel.engine.selectedLens.value?.facing)
        }
    }

    @Test
    fun testPhotoModeAlwaysMaintains2KViewfinderResolution() {
        viewModel.engine.detectHardwareLenses()
        val engine = viewModel.engine
        engine.setMode(CameraMode.PHOTO)

        val targetRatio = 16f / 9f
        val optimalSizePhoto = engine.getOptimalPreviewSize(targetRatio = targetRatio)
        val maxDimPhoto = maxOf(optimalSizePhoto.width, optimalSizePhoto.height)

        val chars = engine.getCharacteristics(engine.selectedLens.value?.cameraId ?: "0")
        val map = chars?.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()
        val matchingRatioSizes = supportedSizes.filter {
            val r = maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height).toFloat()
            kotlin.math.abs(r - targetRatio) < 0.08f
        }
        val has2K = matchingRatioSizes.any { maxOf(it.width, it.height) >= 2560 }
        if (has2K) {
            assertTrue("Photo mode viewfinder must maintain 2K resolution (got $maxDimPhoto)", maxDimPhoto >= 2560)
        } else if (matchingRatioSizes.isNotEmpty()) {
            val closest = matchingRatioSizes.minByOrNull { kotlin.math.abs(maxOf(it.width, it.height) - 2560) }!!
            assertEquals(maxOf(closest.width, closest.height), maxDimPhoto)
        }
    }

    @Test
    fun testGenuineDeviceEisAndNoPhotonCameraGyroSimulation() {
        viewModel.engine.detectHardwareLenses()
        val engine = viewModel.engine

        // Stabilization offset flow must remain 1.0f identity (no fake gyro software crop simulation)
        val offset = engine.stabilizationOffsetFlow.value
        assertEquals(0f, offset.first, 0.001f)
        assertEquals(0f, offset.second, 0.001f)
        assertEquals(1.0f, offset.third, 0.001f)

        // Realtime EIS state must accurately track actual hardware EIS activity
        assertEquals(engine.actualEisHardwareActive.value, engine.isRealtimeEisActive.value)
    }
}
