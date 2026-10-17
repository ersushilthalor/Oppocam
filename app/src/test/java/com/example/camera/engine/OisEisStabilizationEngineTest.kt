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
    private lateinit var gyroEngine: GyroStabilizationEngine

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext<Application>()
        viewModel = CameraViewModel(app)
        gyroEngine = GyroStabilizationEngine(app)
    }

    @Test
    fun testGyroEngineLifecycleAndTelemetry() {
        gyroEngine.start()
        assertEquals(0f, gyroEngine.latestPitchSpeed, 0.001f)
        assertEquals(0f, gyroEngine.latestYawSpeed, 0.001f)
        assertEquals(0f, gyroEngine.latestRollSpeed, 0.001f)
        assertEquals(0f, gyroEngine.getRecentRmsMotion(), 0.001f)
        gyroEngine.stop()
    }

    @Test
    fun testEisCannotBeReEnabledInVideoOrCinemaModes() {
        // Legacy callers and stale saved preferences must never re-enable EIS.
        viewModel.setCameraMode(CameraMode.VIDEO)
        viewModel.setVideoStabilization(true)
        assertFalse("Video mode EIS must remain disabled", viewModel.isVideoStabilizationEnabled.value)
        assertEquals(VideoStabilizationMode.OFF, viewModel.videoStabilizationMode.value)
        assertFalse("Camera2 engine EIS must remain disabled", viewModel.engine.isVideoStabilizationEnabled)
        assertEquals(VideoStabilizationMode.OFF, viewModel.engine.videoStabilizationMode)

        viewModel.setCameraMode(CameraMode.CINEMA)
        viewModel.setVideoStabilization(true)
        assertFalse("Pro Video EIS must remain disabled", viewModel.isVideoStabilizationEnabled.value)
        assertEquals(VideoStabilizationMode.OFF, viewModel.videoStabilizationMode.value)
        assertFalse("Pro Video Camera2 EIS must remain disabled", viewModel.engine.isVideoStabilizationEnabled)
        assertEquals(VideoStabilizationMode.OFF, viewModel.engine.videoStabilizationMode)

        // OIS remains independently switchable while EIS is hard-disabled.
        viewModel.setOisEnabled(true)
        assertTrue("OIS must remain enabled independently", viewModel.isOisEnabled.value)
        assertFalse("Enabling OIS must not enable EIS", viewModel.isVideoStabilizationEnabled.value)
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

        // Selecting OFF disables OIS
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
}
