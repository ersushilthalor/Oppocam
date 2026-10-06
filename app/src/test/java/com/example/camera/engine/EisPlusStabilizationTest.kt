package com.example.camera.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.camera.engine.eisplus.EisPlusStabilizationEngine
import com.example.camera.engine.eisplus.EisPlusTrajectoryPoint
import com.example.camera.model.CameraMode
import com.example.camera.model.CameraUiTemplates
import com.example.camera.model.ModeLayoutConfig
import com.example.camera.model.TopControlItem
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
class EisPlusStabilizationTest {

    private lateinit var app: Application
    private lateinit var viewModel: CameraViewModel
    private lateinit var eisPlusEngine: EisPlusStabilizationEngine

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext<Application>()
        viewModel = CameraViewModel(app)
        eisPlusEngine = EisPlusStabilizationEngine(app)
    }

    @Test
    fun testVideoStabilizationModeProperties() {
        // 3 Options: OFF, EIS, EIS+
        val off = VideoStabilizationMode.OFF
        val eis = VideoStabilizationMode.EIS
        val eisPlus = VideoStabilizationMode.EIS_PLUS

        assertEquals("OFF", off.badgeLabel)
        assertEquals("EIS", eis.badgeLabel)
        assertEquals("EIS+", eisPlus.badgeLabel)

        assertFalse(off.isEnabled)
        assertTrue(eis.isEnabled)
        assertTrue(eisPlus.isEnabled)

        assertFalse(off.isEisPlus)
        assertFalse(eis.isEisPlus)
        assertTrue(eisPlus.isEisPlus)
    }

    @Test
    fun testTopControlLayoutIncludesStabilizationInBothVideoAndCinemaModes() {
        val baseConfig = ModeLayoutConfig()

        val videoConfig = CameraUiTemplates.getDefaultConfigForMode(CameraMode.VIDEO, baseConfig)
        assertTrue(
            "Video mode must include STABILIZATION in top controls",
            videoConfig.topControlsOrder.contains(TopControlItem.STABILIZATION)
        )

        val cinemaConfig = CameraUiTemplates.getDefaultConfigForMode(CameraMode.CINEMA, baseConfig)
        assertTrue(
            "Cinema (Pro Video) mode must include STABILIZATION in top controls",
            cinemaConfig.topControlsOrder.contains(TopControlItem.STABILIZATION)
        )
    }

    @Test
    fun testStabilizationModeSwitchingInNormalVideo() {
        viewModel.setCameraMode(CameraMode.VIDEO)

        // Select EIS
        viewModel.setVideoStabilizationMode(VideoStabilizationMode.EIS)
        assertEquals(VideoStabilizationMode.EIS, viewModel.videoStabilizationMode.value)
        assertEquals(VideoStabilizationMode.EIS, viewModel.engine.videoStabilizationMode)
        assertTrue(viewModel.isVideoStabilizationEnabled.value)

        // Select EIS+
        viewModel.setVideoStabilizationMode(VideoStabilizationMode.EIS_PLUS)
        assertEquals(VideoStabilizationMode.EIS_PLUS, viewModel.videoStabilizationMode.value)
        assertEquals(VideoStabilizationMode.EIS_PLUS, viewModel.engine.videoStabilizationMode)
        assertTrue(viewModel.isVideoStabilizationEnabled.value)

        // Select OFF
        viewModel.setVideoStabilizationMode(VideoStabilizationMode.OFF)
        assertEquals(VideoStabilizationMode.OFF, viewModel.videoStabilizationMode.value)
        assertEquals(VideoStabilizationMode.OFF, viewModel.engine.videoStabilizationMode)
        assertFalse(viewModel.isVideoStabilizationEnabled.value)
    }

    @Test
    fun testStabilizationModeSwitchingInCinemaProVideo() {
        viewModel.setCameraMode(CameraMode.CINEMA)

        // Pro Video starts with EIS / preference
        viewModel.setVideoStabilizationMode(VideoStabilizationMode.EIS_PLUS)
        assertEquals(VideoStabilizationMode.EIS_PLUS, viewModel.videoStabilizationMode.value)
        assertEquals(VideoStabilizationMode.EIS_PLUS, viewModel.engine.videoStabilizationMode)

        // Window open/close toggle
        assertFalse(viewModel.isStabilizationWindowOpen.value)
        viewModel.toggleStabilizationWindow()
        assertTrue(viewModel.isStabilizationWindowOpen.value)

        viewModel.setStabilizationWindowOpen(false)
        assertFalse(viewModel.isStabilizationWindowOpen.value)
    }

    @Test
    fun testEisPlusEngineTrajectoryRecordingLifecycle() {
        eisPlusEngine.start()
        eisPlusEngine.startRecordingTrajectory()

        val emptyTrajectory = eisPlusEngine.stopRecordingTrajectory()
        assertNotNull(emptyTrajectory)
        assertTrue(emptyTrajectory.isEmpty())

        eisPlusEngine.stop()
    }

    @Test
    fun testWhenOisOffEisPlusDoesNotUseOisAndForcesOisOff() {
        // Turn OIS OFF
        viewModel.setOisEnabled(false)
        assertFalse(viewModel.isOisEnabled.value)
        assertFalse(viewModel.engine.isOisAllowed)
        assertFalse(viewModel.engine.eisPlusStabilizationEngine.isOisEnabled)

        // Turn on EIS+
        viewModel.setVideoStabilizationMode(VideoStabilizationMode.EIS_PLUS)
        assertTrue(viewModel.videoStabilizationMode.value.isEisPlus)
        // Ensure OIS is still strictly forbidden
        assertFalse("OIS must never be re-enabled when EIS+ is turned on", viewModel.engine.isOisAllowed)
        assertFalse(viewModel.engine.eisPlusStabilizationEngine.isOisEnabled)

        // Switch to Video Mode
        viewModel.setCameraMode(CameraMode.VIDEO)
        assertFalse(viewModel.engine.isOisAllowed)
        assertFalse(viewModel.engine.eisPlusStabilizationEngine.isOisEnabled)

        // Switch to Cinema Mode
        viewModel.setCameraMode(CameraMode.CINEMA)
        assertFalse(viewModel.engine.isOisAllowed)
        assertFalse(viewModel.engine.eisPlusStabilizationEngine.isOisEnabled)
    }

    @Test
    fun testActualHardwareStabilizationStatesExposed() {
        assertNotNull(viewModel.actualOisHardwareActive)
        assertNotNull(viewModel.actualEisHardwareActive)
        assertNotNull(viewModel.engine.actualOisHardwareActive)
        assertNotNull(viewModel.engine.actualEisHardwareActive)

        // Default state when camera not started
        assertFalse(viewModel.actualOisHardwareActive.value)
        assertFalse(viewModel.actualEisHardwareActive.value)
    }
}
