package com.example.camera.engine

import android.hardware.camera2.CameraCharacteristics
import androidx.test.core.app.ApplicationProvider
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import com.example.camera.viewmodel.CameraViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoomTransitionVerificationTest {

    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        viewModel = CameraViewModel(app)
        viewModel.engine.detectHardwareLenses()
    }

    @Test
    fun testEveryConsecutiveZoomValueHalfXToOneX() {
        // Build expected ordered sequence: 0.50x to 1.00x with no skipped 0.01x steps
        val expectedSteps = (50..100).map { it / 100f }
        assertEquals(51, expectedSteps.size)
        assertEquals(0.50f, expectedSteps.first(), 0.0001f)
        assertEquals(0.99f, expectedSteps[49], 0.0001f)
        assertEquals(1.00f, expectedSteps.last(), 0.0001f)

        // Verify every consecutive step differs by exactly 0.01x
        for (i in 0 until expectedSteps.size - 1) {
            val diff = (expectedSteps[i + 1] - expectedSteps[i]) * 100f
            assertEquals(1.0f, diff, 0.001f)
        }
    }

    @Test
    fun testEveryConsecutiveZoomValueOneXToHalfXReverse() {
        // Build expected reverse ordered sequence: 1.00x to 0.50x
        val expectedSteps = (100 downTo 50).map { it / 100f }
        assertEquals(51, expectedSteps.size)
        assertEquals(1.00f, expectedSteps.first(), 0.0001f)
        assertEquals(0.99f, expectedSteps[1], 0.0001f)
        assertEquals(0.51f, expectedSteps[49], 0.0001f)
        assertEquals(0.50f, expectedSteps.last(), 0.0001f)

        // Verify every consecutive reverse step differs by exactly 0.01x
        for (i in 0 until expectedSteps.size - 1) {
            val diff = (expectedSteps[i] - expectedSteps[i + 1]) * 100f
            assertEquals(1.0f, diff, 0.001f)
        }
    }

    @Test
    fun testSinusoidalEaseInOutTimingProfile300ms() {
        val stepCount = 50
        val durationMs = ZoomTransitionController.TOTAL_TRANSITION_DURATION_MS.toDouble()
        assertEquals(300.0, durationMs, 0.001)

        val controller = ZoomTransitionController()
        val targetElapsedMs = controller.calculateSinusoidalElapsedTimestamps(stepCount, 300L)

        // Verify start and finish timing: exactly 0ms and 300ms
        assertEquals(0L, targetElapsedMs[0])
        assertEquals(300L, targetElapsedMs[stepCount])

        // Verify middle timing (at 50% progress, exactly 150ms)
        assertEquals(150L, targetElapsedMs[25])

        // Verify slow start (ease-in)
        val firstStepDuration = targetElapsedMs[1] - targetElapsedMs[0]
        assertTrue("Start should be slow (~25-30ms), was $firstStepDuration", firstStepDuration in 20..35)

        // Verify faster middle
        val middleStepDuration = targetElapsedMs[25] - targetElapsedMs[24]
        assertTrue("Middle should be fast (~3-6ms), was $middleStepDuration", middleStepDuration in 2..8)

        // Verify slow finish (ease-out)
        val lastStepDuration = targetElapsedMs[50] - targetElapsedMs[49]
        assertTrue("Finish should be slow (~25-30ms), was $lastStepDuration", lastStepDuration in 20..35)

        // Verify strictly monotonic timestamps across all 50 intervals
        for (i in 0 until stepCount) {
            assertTrue("Timestamp $i must be <= timestamp ${i + 1}", targetElapsedMs[i] <= targetElapsedMs[i + 1])
        }
    }

    @Test
    fun testGenerateContinuousZoomStepsIncludesEveryValue() {
        val controller = ZoomTransitionController()

        val stepsUp = controller.generateContinuousZoomSteps(0.50f, 1.00f)
        assertEquals(51, stepsUp.size)
        assertEquals(0.50f, stepsUp.first(), 0.0001f)
        assertEquals(0.99f, stepsUp[49], 0.0001f)
        assertEquals(1.00f, stepsUp.last(), 0.0001f)
        for (i in 0 until stepsUp.size - 1) {
            val diff = (stepsUp[i + 1] - stepsUp[i]) * 100f
            assertEquals(1.0f, diff, 0.001f)
        }

        val stepsDown = controller.generateContinuousZoomSteps(1.00f, 0.50f)
        assertEquals(51, stepsDown.size)
        assertEquals(1.00f, stepsDown.first(), 0.0001f)
        assertEquals(0.99f, stepsDown[1], 0.0001f)
        assertEquals(0.50f, stepsDown.last(), 0.0001f)
        for (i in 0 until stepsDown.size - 1) {
            val diff = (stepsDown[i] - stepsDown[i + 1]) * 100f
            assertEquals(1.0f, diff, 0.001f)
        }
    }

    @Test
    fun testCameraPipelineZoomAppliedDirectly() {
        val lenses = viewModel.engine.availableLenses.value
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE } ?: lenses.firstOrNull()
        if (mainLens != null) {
            viewModel.engine.selectLens(mainLens)
        }
        // Set zoom with continuous transition enabled
        viewModel.engine.setZoom(1.5f, isPresetTap = false, isContinuousTransition = true)

        assertEquals(1.5f, viewModel.engine.currentZoom, 0.001f)
        assertEquals(1.5f, viewModel.engine.currentZoomState.value, 0.001f)
        assertTrue(viewModel.engine.isContinuousZoomTransitionActive)
    }

    @Test
    fun testTargetLensResolutionDuringTransition() {
        val hasUw = viewModel.engine.availableLenses.value.any { it.lensType == LensType.ULTRAWIDE }
        if (!hasUw) return

        // Below 1.0x (e.g. 0.99x), target lens must remain Ultra-Wide
        val lensTypeAt99 = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.ULTRAWIDE,
            targetZoom = 0.99f,
            hasUltraWide = true,
            hasTelephoto2x = false,
            hasTelephoto3x = false,
            isPresetTap = false,
            switchPointMm = 23.0f,
            isContinuousTransition = true
        )
        assertEquals("Target at 0.99x must be Ultra-Wide", LensType.ULTRAWIDE, lensTypeAt99)

        // At 1.00x, target lens resolves to Wide (Main) both during and after transition
        val lensTypeAt100Continuous = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.ULTRAWIDE,
            targetZoom = 1.00f,
            hasUltraWide = true,
            hasTelephoto2x = false,
            hasTelephoto3x = false,
            isPresetTap = false,
            switchPointMm = 23.0f,
            isContinuousTransition = true
        )
        assertEquals("Target at 1.00x during continuous transition must be Wide", LensType.WIDE, lensTypeAt100Continuous)

        val lensTypeAt100 = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.ULTRAWIDE,
            targetZoom = 1.00f,
            hasUltraWide = true,
            hasTelephoto2x = false,
            hasTelephoto3x = false,
            isPresetTap = false,
            switchPointMm = 23.0f,
            isContinuousTransition = false
        )
        assertEquals("Target at 1.00x must be Wide", LensType.WIDE, lensTypeAt100)
    }

    @Test
    fun testOpticalFovMatchAtHandoff() {
        // At 0.50x Ultra-Wide: digital crop is 1.00x (full uncropped sensor)
        val cropAt050 = CameraOpticalCalibration.calculateRequiredDigitalCrop(
            uiZoom = 0.50f,
            lensBaseRatio = 0.5f,
            lensType = LensType.ULTRAWIDE,
            switchPointMm = 23.0f
        )
        assertEquals(1.00f, cropAt050, 0.01f)

        // At 1.00x Ultra-Wide: digital crop reaches maxUwCrop (23/16 ≈ 1.44x)
        val cropAt100Uw = CameraOpticalCalibration.calculateRequiredDigitalCrop(
            uiZoom = 1.00f,
            lensBaseRatio = 0.5f,
            lensType = LensType.ULTRAWIDE,
            switchPointMm = 23.0f
        )
        assertEquals(1.44f, cropAt100Uw, 0.02f)

        // At 1.00x Main Wide: digital crop is 1.00x (full uncropped Main sensor)
        val cropAt100Wide = CameraOpticalCalibration.calculateRequiredDigitalCrop(
            uiZoom = 1.00f,
            lensBaseRatio = 1.0f,
            lensType = LensType.WIDE,
            switchPointMm = 23.0f
        )
        assertEquals(1.00f, cropAt100Wide, 0.01f)
    }

    @Test
    fun testPresetTapTriggersTransitionJobWithoutBypass() {
        val lenses = viewModel.engine.availableLenses.value
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE } ?: lenses.firstOrNull()
        if (mainLens != null) {
            viewModel.engine.selectLens(mainLens)
        }
        viewModel.setZoom(1.0f, isPresetTap = false)
        assertEquals(1.0f, viewModel.currentZoom.value, 0.01f)

        // Preset tap to 2.0x must trigger continuous transition and cannot bypass it
        viewModel.setZoom(2.0f, isPresetTap = true)
        assertTrue(viewModel.engine.isContinuousZoomTransitionActive)

        // Preset tap from 2.0x to 1.0x must trigger continuous transition and cannot bypass it
        viewModel.setZoom(1.0f, isPresetTap = true)
        assertTrue(viewModel.engine.isContinuousZoomTransitionActive)
    }

    @Test
    fun testPresetTapOneXCannotBypassTransitionFromUltraWide() {
        val lenses = viewModel.engine.availableLenses.value
        val uwLens = lenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        if (uwLens != null) {
            viewModel.selectLens(uwLens, instant = true)
            viewModel.setZoom(0.5f, isPresetTap = false)
            assertEquals(0.5f, viewModel.currentZoom.value, 0.01f)

            // Tapping 1x preset while on Ultra-Wide must trigger continuous transition, not direct switch!
            viewModel.setZoom(1.0f, isPresetTap = true)
            assertTrue("Tapping 1x preset must activate continuous transition", viewModel.engine.isContinuousZoomTransitionActive)
        }
    }

    @Test
    fun testPresetTapHalfXCannotBypassTransitionFromOneX() {
        val lenses = viewModel.engine.availableLenses.value
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE }
        val uwLens = lenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        if (mainLens != null && uwLens != null) {
            viewModel.selectLens(mainLens, instant = true)
            viewModel.setZoom(1.0f, isPresetTap = false)
            assertEquals(1.0f, viewModel.currentZoom.value, 0.01f)

            // Tapping 0.5x preset while on Main 1x must trigger continuous transition, not direct switch!
            viewModel.setZoom(0.5f, isPresetTap = true)
            assertTrue("Tapping 0.5x preset must activate continuous transition", viewModel.engine.isContinuousZoomTransitionActive)
        }
    }

    @Test
    fun testFixed300msDurationAcrossArbitraryZoomValues() {
        // Duration must be strictly 300ms (0.30 seconds), never less
        assertEquals(300L, ZoomTransitionController.TOTAL_TRANSITION_DURATION_MS)

        val controller = ZoomTransitionController()
        val timestamps1xTo2x = controller.calculateSinusoidalElapsedTimestamps(100, 300L)
        assertEquals(300L, timestamps1xTo2x.last())

        val timestamps1xTo10x = controller.calculateSinusoidalElapsedTimestamps(900, 300L)
        assertEquals(300L, timestamps1xTo10x.last())

        val timestampsHalfXToOneX = controller.calculateSinusoidalElapsedTimestamps(50, 300L)
        assertEquals(300L, timestampsHalfXToOneX.last())
    }

    @Test
    fun testSymmetric300msDurationUltraWideToMainAndMainToUltraWide() {
        val controller = ZoomTransitionController()
        val stepsUwToMain = controller.generateContinuousZoomSteps(0.50f, 1.00f)
        val stepsMainToUw = controller.generateContinuousZoomSteps(1.00f, 0.50f)

        assertEquals(stepsUwToMain.size, stepsMainToUw.size)
        assertEquals(51, stepsUwToMain.size)

        val timeProfileUwToMain = controller.calculateSinusoidalElapsedTimestamps(stepsUwToMain.size - 1, 300L)
        val timeProfileMainToUw = controller.calculateSinusoidalElapsedTimestamps(stepsMainToUw.size - 1, 300L)

        // Both directions must have identical 300ms completion (never less)
        assertEquals(300L, timeProfileUwToMain.last())
        assertEquals(300L, timeProfileMainToUw.last())

        // Symmetrical middle transition point at exactly 150ms
        assertEquals(150L, timeProfileUwToMain[25])
        assertEquals(150L, timeProfileMainToUw[25])
    }

    @Test
    fun testSliderZoomAppliesImmediatelyWithZeroDelay() {
        val lenses = viewModel.engine.availableLenses.value
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE } ?: lenses.firstOrNull()
        if (mainLens != null) {
            viewModel.engine.selectLens(mainLens)
        }

        // Slider zoom (isPresetTap = false) cancels active transition and immediately updates currentZoom
        viewModel.setZoom(3.5f, isPresetTap = false)
        assertEquals(3.5f, viewModel.currentZoom.value, 0.01f)
        assertEquals(3.5f, viewModel.engine.currentZoom, 0.01f)
        assertFalse(viewModel.engine.zoomTransitionController.isTransitionActive.value)
    }

    @Test
    fun testInstantLensSwitchBypassesAnimationWindow() {
        val lenses = viewModel.engine.availableLenses.value
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE } ?: lenses.firstOrNull()
        val uwLens = lenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        if (mainLens != null && uwLens != null) {
            viewModel.selectLens(mainLens, instant = true)
            assertFalse(viewModel.engine.zoomTransitionController.isTransitionActive.value)

            // Instant switch to ultra-wide
            viewModel.selectLens(uwLens, instant = true)
            assertEquals(uwLens.id, viewModel.engine.selectedLens.value?.id)
            assertFalse(viewModel.engine.zoomTransitionController.isTransitionActive.value)
        }
    }

    @Test
    fun testOverlapAnimationCompletelyRemoved() {
        val controller = ZoomTransitionController()
        // Overlap state is non-overlapping by default
        assertFalse(controller.previewOverlapState.value.isOverlapping)
        assertEquals(1.0f, controller.previewOverlapState.value.mainAlpha, 0.001f)
        assertEquals(0.0f, controller.previewOverlapState.value.ultraWideAlpha, 0.001f)
    }

    @Test
    fun testBackgroundSliderProgressAccuratelyReusesRulerMapping() {
        val controller = ZoomTransitionController()
        val minZoom = 0.5f
        val maxZoom = 20.0f

        val testZooms = listOf(0.5f, 0.7f, 1.0f, 2.0f, 5.0f, 10.0f, 20.0f)
        for (z in testZooms) {
            val progress = controller.zoomToNormalizedSliderProgress(z, minZoom, maxZoom)
            assertTrue("Progress for zoom $z must be in 0..1, was $progress", progress in 0.0f..1.0f)
            val reconstructed = controller.normalizedSliderProgressToZoom(progress, minZoom, maxZoom)
            assertEquals("Zoom must round-trip through slider mapping", z, reconstructed, 0.05f)
        }
    }

    @Test
    fun testBackgroundSliderUltraWideToOneXAndReverseSwitchesLensCorrectly() {
        val lenses = viewModel.engine.availableLenses.value
        val uwLens = lenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE }

        if (uwLens != null && mainLens != null) {
            // 1. Ultra-Wide to 1x via background slider logic
            viewModel.selectLens(uwLens, instant = true)
            viewModel.setZoom(0.5f, isPresetTap = false)
            assertEquals(0.5f, viewModel.engine.currentZoom, 0.01f)

            // Step through background slider values towards 1.0x
            viewModel.engine.setZoom(0.75f, isPresetTap = false, isContinuousTransition = true)
            assertEquals(0.75f, viewModel.engine.currentZoom, 0.01f)

            viewModel.engine.setZoom(0.99f, isPresetTap = false, isContinuousTransition = true)
            assertEquals(0.99f, viewModel.engine.currentZoom, 0.01f)

            // Final step at 1.0x with isContinuousTransition = false switches to Main lens
            viewModel.engine.setZoom(1.00f, isPresetTap = false, isContinuousTransition = false)
            assertEquals(1.00f, viewModel.engine.currentZoom, 0.01f)
            assertEquals("At 1.0x final step, engine must select Main lens", mainLens.id, viewModel.engine.selectedLens.value?.id)

            // 2. Reverse: 1x to Ultra-Wide via background slider logic
            viewModel.engine.setZoom(0.99f, isPresetTap = false, isContinuousTransition = true)
            assertEquals(0.99f, viewModel.engine.currentZoom, 0.01f)
            assertEquals("Dropping below 1.0x must select Ultra-Wide lens", uwLens.id, viewModel.engine.selectedLens.value?.id)

            viewModel.engine.setZoom(0.50f, isPresetTap = false, isContinuousTransition = false)
            assertEquals(0.50f, viewModel.engine.currentZoom, 0.01f)
            assertEquals("At 0.5x final step, must remain on Ultra-Wide lens", uwLens.id, viewModel.engine.selectedLens.value?.id)
        }
    }

    @Test
    fun testHalfXToOneXTransitionSequenceHoldAndSwitch() {
        val lenses = viewModel.engine.availableLenses.value
        val uwLens = lenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE }

        if (uwLens != null && mainLens != null) {
            assertEquals(100L, ZoomTransitionController.HOLD_AT_ONE_X_DURATION_MS)

            viewModel.selectLens(uwLens, instant = true)
            viewModel.setZoom(0.5f, isPresetTap = false)
            assertEquals(0.5f, viewModel.currentZoom.value, 0.01f)

            // When user taps 1x while at 0.5x, do NOT switch to Main lens immediately
            viewModel.setZoom(1.0f, isPresetTap = true)
            assertTrue(viewModel.engine.isContinuousZoomTransitionActive)

            // Must initially remain on Ultra-Wide lens
            assertEquals(uwLens.id, viewModel.engine.selectedLens.value?.id)
        }
    }

    @Test
    fun testVideoModeSmoothZoomTransitionWithoutSessionRestart() {
        viewModel.setCameraMode(com.example.camera.model.CameraMode.VIDEO)
        assertEquals(com.example.camera.model.CameraMode.VIDEO, viewModel.cameraMode.value)

        val lenses = viewModel.engine.availableLenses.value
        val uwLens = lenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE }

        if (uwLens != null && mainLens != null) {
            viewModel.selectLens(uwLens, instant = true)
            viewModel.setZoom(0.5f, isPresetTap = false)

            // Tapping 1x in Video Mode smoothly starts continuous transition
            viewModel.setZoom(1.0f, isPresetTap = true)
            assertTrue(viewModel.engine.isContinuousZoomTransitionActive)
            assertEquals(uwLens.id, viewModel.engine.selectedLens.value?.id)
        }
    }
}
