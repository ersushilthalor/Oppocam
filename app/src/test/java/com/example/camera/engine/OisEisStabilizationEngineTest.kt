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
}
