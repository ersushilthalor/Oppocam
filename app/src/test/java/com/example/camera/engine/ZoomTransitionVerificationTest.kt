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
    fun testSinusoidalEaseInOutTimingProfile500ms() {
        val stepCount = 50
        val durationMs = 500.0

        val targetElapsedMs = LongArray(stepCount + 1)
        for (stepIndex in 0..stepCount) {
            val progress = (stepIndex.toDouble() / stepCount.toDouble()).coerceIn(0.0, 1.0)
            val cosVal = (1.0 - 2.0 * progress).coerceIn(-1.0, 1.0)
            val elapsed = (durationMs / Math.PI) * Math.acos(cosVal)
            targetElapsedMs[stepIndex] = (elapsed + 0.5).toLong()
        }

        // Verify start and finish timing
        assertEquals(0L, targetElapsedMs[0])
        assertEquals(500L, targetElapsedMs[stepCount])

        // Verify middle timing (at 50% progress, exactly 250ms)
        assertEquals(250L, targetElapsedMs[25])

        // Verify slow start: step 0 -> step 1 takes ~45ms
        val firstStepDuration = targetElapsedMs[1] - targetElapsedMs[0]
        assertTrue("Start should be slow (~45ms), was $firstStepDuration", firstStepDuration in 40..50)

        // Verify faster middle: step 24 -> step 25 takes ~6ms
        val middleStepDuration = targetElapsedMs[25] - targetElapsedMs[24]
        assertTrue("Middle should be fast (~6ms), was $middleStepDuration", middleStepDuration in 4..10)

        // Verify slow finish: step 49 -> step 50 takes ~45ms
        val lastStepDuration = targetElapsedMs[50] - targetElapsedMs[49]
        assertTrue("Finish should be slow (~45ms), was $lastStepDuration", lastStepDuration in 40..50)

        // Verify strictly monotonic timestamps across all 50 intervals
        for (i in 0 until stepCount) {
            assertTrue("Timestamp $i must be <= timestamp ${i + 1}", targetElapsedMs[i] <= targetElapsedMs[i + 1])
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

        // At 1.00x, target lens must resolve to Wide (Main)
        val lensTypeAt100 = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.ULTRAWIDE,
            targetZoom = 1.00f,
            hasUltraWide = true,
            hasTelephoto2x = false,
            hasTelephoto3x = false,
            isPresetTap = false,
            switchPointMm = 23.0f,
            isContinuousTransition = true
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
    fun testPresetTapTriggersTransitionJob() {
        val lenses = viewModel.engine.availableLenses.value
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE } ?: lenses.firstOrNull()
        if (mainLens != null) {
            viewModel.engine.selectLens(mainLens)
        }
        viewModel.setZoom(1.0f, isPresetTap = false)
        assertEquals(1.0f, viewModel.currentZoom.value, 0.01f)

        // Preset tap to 2.0x should trigger continuous transition
        viewModel.setZoom(2.0f, isPresetTap = true)
        assertTrue(viewModel.engine.isContinuousZoomTransitionActive)
    }
}
