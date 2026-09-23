package com.example.camera.engine

import android.hardware.camera2.CameraCharacteristics
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoomContinuityControllerTest {

    private val mainLens = LensInfo(
        id = "0",
        displayName = "Wide",
        cameraId = "0",
        lensType = LensType.WIDE,
        facing = CameraCharacteristics.LENS_FACING_BACK,
        baseZoomRatio = 1.0f,
        isPrimaryMain = true,
        focalLengthMm = 5.5f,
        maxAperture = 1.8f
    )

    private val teleLens = LensInfo(
        id = "2",
        displayName = "Telephoto 3x",
        cameraId = "2",
        lensType = LensType.TELEPHOTO_3X,
        facing = CameraCharacteristics.LENS_FACING_BACK,
        baseZoomRatio = 3.0f,
        isPhysical = true,
        focalLengthMm = 16.5f,
        maxAperture = 2.4f
    )

    private val ultraWideLens = LensInfo(
        id = "1",
        displayName = "Ultra Wide",
        cameraId = "1",
        lensType = LensType.ULTRAWIDE,
        facing = CameraCharacteristics.LENS_FACING_BACK,
        baseZoomRatio = 0.5f,
        isPhysical = true,
        focalLengthMm = 1.8f,
        maxAperture = 2.2f
    )

    @Test
    fun testRapidSwipeDuringLensSwitchPreservesContinuityAndFinishesAtTarget() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val appliedZooms = mutableListOf<Float>()
        val updatedZooms = mutableListOf<Float>()

        val controller = ZoomContinuityController(
            coroutineScope = testScope,
            onApplyZoom = { appliedZooms.add(it) },
            onZoomUpdated = { updatedZooms.add(it) }
        )

        // Initial setup at 1.0x on Main lens
        controller.initialize(mainLens, 1.0f)
        assertEquals(1.0f, controller.currentDisplayedZoom, 0.001f)
        assertEquals(1.0f, controller.userTargetZoom, 0.001f)

        // User starts rapid swipe to 8.0x
        controller.onUserZoomInput(1.5f)
        controller.onLensSwitchStarted(teleLens)
        assertTrue("Controller must be in switching state", controller.isSwitching)
        assertEquals(teleLens, controller.pendingLens)

        // While lens switch is loading (~500-1000ms), user rapidly swipes up to 8.0x
        controller.onUserZoomInput(2.5f)
        controller.onUserZoomInput(4.0f)
        controller.onUserZoomInput(6.0f)
        controller.onUserZoomInput(8.0f)

        // Target must be updated to 8.0x, but display zoom must NOT jump directly to 8.0x
        assertEquals(8.0f, controller.userTargetZoom, 0.001f)
        assertTrue("Display zoom must not jump directly to 8.0x while switching", controller.currentDisplayedZoom < 8.0f)

        // Check FOV-equivalent zoom calculation for the new lens
        // The new lens should start at equivalent framing of current view
        val fovEq = controller.calculateFovEquivalentZoom(
            sourceFovZoom = controller.currentDisplayedZoom,
            fromLens = mainLens,
            toLens = teleLens
        )
        assertTrue("FOV equivalent zoom must be positive and reasonable", fovEq > 0f)

        // Simulate 700ms elapsed: new lens session becomes ready
        testScope.advanceTimeBy(700)
        controller.onNewLensReady(teleLens)

        assertFalse("Controller must no longer be in switching state", controller.isSwitching)
        assertEquals(teleLens, controller.activeLens)

        // Advance time for the smooth interpolation to run toward 8.0x
        testScope.advanceTimeBy(600)

        // Must finish smoothly at the latest user requested zoom (8.0x)
        assertEquals(8.0f, controller.currentDisplayedZoom, 0.05f)
        assertTrue("Applied zooms list must not be empty", appliedZooms.isNotEmpty())

        // Verify that consecutive zoom steps did not have any sudden gigantic jump
        for (i in 1 until appliedZooms.size) {
            val stepDelta = Math.abs(appliedZooms[i] - appliedZooms[i - 1])
            assertTrue("Step delta between consecutive frames should be smooth (was $stepDelta)", stepDelta < 2.0f)
        }
    }

    @Test
    fun testFovEquivalentZoomMatchesCorrectFovRatio() {
        val testScope = TestScope()
        val controller = ZoomContinuityController(
            coroutineScope = testScope,
            onApplyZoom = {},
            onZoomUpdated = {}
        )

        // Switching from Main (1x) to Telephoto (3x) at 3.5x current zoom: FOV matches 3.5x
        val fovEqTele35 = controller.calculateFovEquivalentZoom(
            sourceFovZoom = 3.5f,
            fromLens = mainLens,
            toLens = teleLens
        )
        assertEquals(3.5f, fovEqTele35, 0.001f)

        // Switching from Main (1x) to Telephoto (3x) at 2.5x: cannot exceed physical 3x base sensor FOV, so clamps to 3.0x
        val fovEqTele25 = controller.calculateFovEquivalentZoom(
            sourceFovZoom = 2.5f,
            fromLens = mainLens,
            toLens = teleLens
        )
        assertEquals(3.0f, fovEqTele25, 0.001f)

        // Switching from Ultra-Wide (0.5x) to Main (1.0x) at 0.8x current zoom:
        // Main sensor cannot physically capture wider than 1.0x, so clamps to 1.0x
        val fovEqMain08 = controller.calculateFovEquivalentZoom(
            sourceFovZoom = 0.8f,
            fromLens = ultraWideLens,
            toLens = mainLens
        )
        assertEquals(1.0f, fovEqMain08, 0.001f)

        // Switching from Ultra-Wide to Main at 1.4x: matches 1.4x
        val fovEqMain14 = controller.calculateFovEquivalentZoom(
            sourceFovZoom = 1.4f,
            fromLens = ultraWideLens,
            toLens = mainLens
        )
        assertEquals(1.4f, fovEqMain14, 0.001f)
    }

    @Test
    fun testSwitchFailedGracefullyCancelsAndRecovers() {
        val testScope = TestScope()
        val controller = ZoomContinuityController(
            coroutineScope = testScope,
            onApplyZoom = {},
            onZoomUpdated = {}
        )

        controller.initialize(mainLens, 1.0f)
        controller.onLensSwitchStarted(teleLens)
        assertTrue(controller.isSwitching)

        controller.onSwitchFailed()
        assertFalse(controller.isSwitching)
        assertEquals(null, controller.pendingLens)
        assertEquals(mainLens, controller.activeLens)
    }
}
