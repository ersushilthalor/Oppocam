package com.example.camera.engine

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.camera.model.CameraMode
import com.example.camera.model.HybridStabilizationConfig
import com.example.camera.model.MainCameraStabilizationMode
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
    fun testHybridStabilizationConfigModeMapping() {
        val hybridConfig = HybridStabilizationConfig(
            isHybridEnabled = true,
            isOisPreferred = true,
            isEisPreferred = true,
            isUltraStabilizationEnabled = false
        )
        assertEquals(MainCameraStabilizationMode.HYBRID_OIS_EIS, hybridConfig.stabilizationMode)

        val oisOnlyConfig = hybridConfig.copy(
            isHybridEnabled = false,
            isOisPreferred = true,
            isEisPreferred = false
        )
        assertEquals(MainCameraStabilizationMode.OIS_ONLY, oisOnlyConfig.stabilizationMode)

        val eisOnlyConfig = hybridConfig.copy(
            isHybridEnabled = false,
            isOisPreferred = false,
            isEisPreferred = true,
            isEisOnly = true
        )
        assertEquals(MainCameraStabilizationMode.EIS_ONLY, eisOnlyConfig.stabilizationMode)

        val ultraConfig = hybridConfig.copy(
            isUltraStabilizationEnabled = true
        )
        assertEquals(MainCameraStabilizationMode.ULTRA, ultraConfig.stabilizationMode)
    }

    @Test
    fun testStabilizationStateInVideoAndCinemaModes() {
        // Video mode initialization
        viewModel.setCameraMode(CameraMode.VIDEO)
        assertTrue("Video mode should default to video stabilization enabled", viewModel.isVideoStabilizationEnabled.value)

        // Switch to Cinema Mode: stabilization remains properly active
        viewModel.setCameraMode(CameraMode.CINEMA)
        assertTrue("Cinema mode should keep stabilization active", viewModel.isVideoStabilizationEnabled.value)

        // Set Main Camera Stabilization to OIS Only
        viewModel.setMainCameraStabilizationMode(MainCameraStabilizationMode.OIS_ONLY)
        assertTrue(viewModel.isVideoStabilizationEnabled.value)
        assertTrue(viewModel.hybridStabilizationConfig.value.isOisPreferred)
        assertFalse(viewModel.hybridStabilizationConfig.value.isEisPreferred)

        // Set Main Camera Stabilization to Hybrid
        viewModel.setMainCameraStabilizationMode(MainCameraStabilizationMode.HYBRID_OIS_EIS)
        assertTrue(viewModel.isVideoStabilizationEnabled.value)
        assertTrue(viewModel.hybridStabilizationConfig.value.isOisPreferred)
        assertTrue(viewModel.hybridStabilizationConfig.value.isEisPreferred)

        // Toggle Ultra Action Steady
        viewModel.toggleUltraStabilization()
        assertTrue(viewModel.hybridStabilizationConfig.value.isUltraStabilizationEnabled)

        // Disable Ultra Action Steady
        viewModel.toggleUltraStabilization()
        assertFalse(viewModel.hybridStabilizationConfig.value.isUltraStabilizationEnabled)

        // Set to OFF
        viewModel.setMainCameraStabilizationMode(MainCameraStabilizationMode.OFF)
        assertFalse(viewModel.isVideoStabilizationEnabled.value)
    }

    @Test
    fun testOisToggleDirectlyOnAndOff() {
        // Default: OIS ON
        assertTrue("Default OIS should be ON", viewModel.isOisEnabled.value)
        assertTrue(viewModel.engine.isOisEnabled)

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
    fun testWhenOisOffEisDoesNotReenableOis() {
        // Forcefully disable OIS
        viewModel.setOisEnabled(false)
        assertFalse(viewModel.isOisEnabled.value)

        // Select normal EIS mode
        viewModel.setVideoStabilizationMode(com.example.camera.model.VideoStabilizationMode.EIS)
        assertEquals(com.example.camera.model.VideoStabilizationMode.EIS, viewModel.videoStabilizationMode.value)
        assertTrue("Video stabilization should be active for EIS", viewModel.isVideoStabilizationEnabled.value)
        assertFalse("OIS must NOT be re-enabled by selecting EIS", viewModel.isOisEnabled.value)
        assertFalse("Engine OIS must stay false in EIS", viewModel.engine.isOisEnabled)
        assertFalse("Engine isOisAllowed must stay false in EIS", viewModel.engine.isOisAllowed)
        assertFalse("Hybrid config OIS must stay false in EIS", viewModel.hybridStabilizationConfig.value.isOisEnabled)
        assertFalse("Hybrid config isOisPreferred must stay false in EIS", viewModel.hybridStabilizationConfig.value.isOisPreferred)

        // Toggle Video Stabilization directly (OFF then ON)
        viewModel.setVideoStabilization(false)
        assertFalse("Video stabilization should be OFF", viewModel.isVideoStabilizationEnabled.value)
        assertFalse("OIS must stay false", viewModel.isOisEnabled.value)

        viewModel.setVideoStabilization(true)
        assertTrue("Video stabilization should be ON (normal EIS)", viewModel.isVideoStabilizationEnabled.value)
        assertFalse("OIS must still be forcefully disabled when normal EIS is enabled", viewModel.isOisEnabled.value)
        assertFalse("Engine OIS must remain disabled", viewModel.engine.isOisEnabled)

        // Toggle Ultra Action Steady
        viewModel.toggleUltraStabilization()
        assertFalse("Ultra Action must NOT re-enable OIS when OIS is OFF", viewModel.isOisEnabled.value)
        assertFalse(viewModel.hybridStabilizationConfig.value.isOisPreferred)
    }

    @Test
    fun testMainCameraStabilizationModeFallbackWhenOisOff() {
        // With OIS OFF, selecting Hybrid results in EIS_ONLY operation
        viewModel.setOisEnabled(false)
        viewModel.setMainCameraStabilizationMode(MainCameraStabilizationMode.HYBRID_OIS_EIS)

        val config = viewModel.hybridStabilizationConfig.value
        assertFalse("OIS must remain false", config.isOisEnabled)
        assertFalse("OIS preferred must remain false", config.isOisPreferred)
        assertTrue("EIS remains preferred", config.isEisPreferred)
        assertEquals("When OIS is OFF, hybrid mode gracefully operates as EIS_ONLY", MainCameraStabilizationMode.EIS_ONLY, config.stabilizationMode)
    }
}
