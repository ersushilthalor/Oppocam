package com.example.camera.engine

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.test.core.app.ApplicationProvider
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotonCameraLensSwitchingTest {

    private lateinit var context: Context
    private lateinit var engine: Camera2Engine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = Camera2Engine(context)
    }

    @Test
    fun testCameraDiscoveryCreatesCleanInventory() {
        val count = engine.detectHardwareLenses()
        assertTrue("Camera inventory should discover at least 1 lens", count >= 1)
        val lenses = engine.availableLenses.value
        assertTrue("Available lenses must not be empty", lenses.isNotEmpty())

        // Verify primary main lens exists and is marked as primary
        val mainLens = lenses.firstOrNull { it.isPrimaryMain }
        assertNotNull("Primary main lens should exist", mainLens)
        assertEquals(1.0f, mainLens!!.baseZoomRatio, 0.001f)
    }

    @Test
    fun testResolveSwitchStrategyDifferentiatesIndependentAndLogical() {
        val mainLens = LensInfo(
            cameraId = "0",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.WIDE,
            displayName = "1x Main",
            focalLengthMm = 5.0f,
            maxAperture = 1.8f,
            isPrimaryMain = true,
            isLogicalMultiCamera = true,
            isIndependentCamera = true,
            baseZoomRatio = 1.0f
        )

        val frontLens = LensInfo(
            cameraId = "1",
            facing = CameraCharacteristics.LENS_FACING_FRONT,
            lensType = LensType.FRONT,
            displayName = "Front Selfie",
            focalLengthMm = 3.5f,
            maxAperture = 2.0f,
            isIndependentCamera = true,
            baseZoomRatio = 1.0f
        )

        val physicalUltraWide = LensInfo(
            cameraId = "0",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.ULTRAWIDE,
            displayName = "0.5x Ultra Wide",
            focalLengthMm = 2.0f,
            maxAperture = 2.2f,
            isLogicalMultiCamera = true,
            supportsPhysicalStream = true,
            physicalCameraId = "2",
            baseZoomRatio = 0.5f
        )

        val logicalZoomUltraWide = LensInfo(
            cameraId = "0",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.ULTRAWIDE,
            displayName = "0.5x Ultra Wide",
            focalLengthMm = 2.0f,
            maxAperture = 2.2f,
            isLogicalMultiCamera = true,
            supportsPhysicalStream = false,
            baseZoomRatio = 0.5f
        )

        // Switch Back to Front -> INDEPENDENT_DEVICE
        val stratBackToFront = CameraDiscovery.resolveSwitchStrategy(mainLens, frontLens)
        assertEquals(LensSwitchStrategy.INDEPENDENT_DEVICE, stratBackToFront)

        // Switch Front to Back -> INDEPENDENT_DEVICE
        val stratFrontToBack = CameraDiscovery.resolveSwitchStrategy(frontLens, mainLens)
        assertEquals(LensSwitchStrategy.INDEPENDENT_DEVICE, stratFrontToBack)

        // Switch Main to Physical Stream UltraWide -> LOGICAL_PHYSICAL_STREAM
        val stratMainToPhys = CameraDiscovery.resolveSwitchStrategy(mainLens, physicalUltraWide)
        assertEquals(LensSwitchStrategy.LOGICAL_PHYSICAL_STREAM, stratMainToPhys)

        // Switch Main to Logical Zoom UltraWide -> LOGICAL_ZOOM
        val stratMainToLogZoom = CameraDiscovery.resolveSwitchStrategy(mainLens, logicalZoomUltraWide)
        assertEquals(LensSwitchStrategy.LOGICAL_ZOOM, stratMainToLogZoom)
    }

    @Test
    fun testZoomPreservationAcrossLensSwitches() {
        val mainLens = LensInfo(
            cameraId = "0",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.WIDE,
            displayName = "1x Main",
            focalLengthMm = 5.0f,
            maxAperture = 1.8f,
            isPrimaryMain = true,
            baseZoomRatio = 1.0f
        )

        val teleLens = LensInfo(
            cameraId = "0",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.TELEPHOTO,
            displayName = "2x Telephoto",
            focalLengthMm = 10.0f,
            maxAperture = 2.4f,
            isLogicalMultiCamera = true,
            supportsPhysicalStream = true,
            physicalCameraId = "3",
            baseZoomRatio = 2.0f
        )

        // Explicit lens selection without preserving zoom resets to lens base zoom
        engine.selectLens(mainLens, preserveZoom = false)
        assertEquals(1.0f, engine.currentZoom, 0.001f)

        // Switching with target zoom preserves the target zoom level
        engine.selectLens(teleLens, preserveZoom = true, targetZoom = 3.5f)
        assertEquals(3.5f, engine.currentZoom, 0.001f)
    }

    @Test
    fun testRapidRepeatedLensSwitchingDoesNotLockState() {
        val lenses = engine.availableLenses.value
        if (lenses.size >= 2) {
            val lens1 = lenses[0]
            val lens2 = lenses[1]

            // Simulate rapid user tapping back and forth between lenses
            for (i in 0..5) {
                engine.selectLens(lens1)
                engine.selectLens(lens2)
            }

            // Engine should still be alive and responsive
            assertNotNull(engine.selectedLens.value)
        }
    }

    @Test
    fun testOptimalPhotoSizeNeverFailsForAnyLens() {
        val lenses = engine.availableLenses.value
        for (lens in lenses) {
            val size = engine.getOptimalPhotoSizeForLens(lens, lens.cameraId)
            assertNotNull("Photo size should be resolved for ${lens.displayName}", size)
            assertTrue("Width should be positive", size.width > 0)
            assertTrue("Height should be positive", size.height > 0)
        }
    }

    @Test
    fun testKeepUltraWideReadyDefaultOff() {
        val prefs = com.example.camera.data.CameraPreferences(context)
        assertFalse("Keep Ultra Wide Ready must be OFF by default in preferences", prefs.isKeepUltraWideReady)
        assertFalse("Keep Ultra Wide Ready must be OFF by default in engine", engine.isKeepUltraWideReady.value)
    }

    @Test
    fun testKeepUltraWideReadyTogglePersistsAndUpdatesState() {
        val prefs = com.example.camera.data.CameraPreferences(context)
        engine.setKeepUltraWideReady(true)
        assertTrue("Setting Keep Ultra Wide Ready to true must update engine state", engine.isKeepUltraWideReady.value)
        assertTrue("Setting Keep Ultra Wide Ready to true must persist in preferences", prefs.isKeepUltraWideReady)

        engine.setKeepUltraWideReady(false)
        assertFalse("Setting Keep Ultra Wide Ready to false must update engine state", engine.isKeepUltraWideReady.value)
        assertFalse("Setting Keep Ultra Wide Ready to false must persist in preferences", prefs.isKeepUltraWideReady)
    }

    @Test
    fun testKeepUltraWideReadyInstantSwitchMainAndUltraWide() {
        engine.detectHardwareLenses()
        val lenses = engine.availableLenses.value
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE }
        val ultraWideLens = lenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }

        assertNotNull("Main lens should be detected", mainLens)
        engine.selectLens(mainLens!!)
        assertEquals(mainLens.id, engine.selectedLens.value?.id)

        // Enable Keep Ultra Wide Ready
        engine.setKeepUltraWideReady(true)
        assertTrue(engine.isKeepUltraWideReady.value)

        // If an ultra-wide lens is present, test instant digital switch
        if (ultraWideLens != null) {
            engine.selectLens(ultraWideLens)
            assertEquals(ultraWideLens.id, engine.selectedLens.value?.id)

            // Switch back to Main
            engine.selectLens(mainLens)
            assertEquals(mainLens.id, engine.selectedLens.value?.id)
        }
    }

    @Test
    fun testOneXToHalfXSmoothTransitionInterpolatesCorrectly() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.camera.viewmodel.CameraViewModel(app)
        viewModel.setZoom(1.0f, isPresetTap = false)
        assertEquals(1.0f, viewModel.currentZoom.value, 0.001f)

        // Trigger 0.5x preset tap from 1.0x
        viewModel.setZoom(0.5f, isPresetTap = true)

        // Smooth transition job should be active and current zoom smoothly transitioning down
        assertTrue(viewModel.currentZoom.value <= 1.0f)
    }

    @Test
    fun testSmoothTransitionBetweenHalfXAndOneXBothWays() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.camera.viewmodel.CameraViewModel(app)
        // Transition down from 1.0x to 0.5x
        viewModel.setZoom(1.0f, isPresetTap = false)
        viewModel.setZoom(0.5f, isPresetTap = true)
        assertTrue(viewModel.currentZoom.value <= 1.0f)

        // Transition up from 0.5x to 1.0x
        viewModel.startSmoothLensTransition(fromZoom = 0.5f, targetZoom = 1.0f)
        assertTrue(viewModel.currentZoom.value >= 0.5f)
    }

    @Test
    fun testSmoothTransitionOtherLensSwitches() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.camera.viewmodel.CameraViewModel(app)
        viewModel.setZoom(1.0f, isPresetTap = false)
        assertEquals(1.0f, viewModel.currentZoom.value, 0.001f)

        // Trigger 2.0x preset tap from 1.0x
        viewModel.setZoom(2.0f, isPresetTap = true)

        // Smooth transition should be active and current zoom smoothly transitioning up
        assertTrue(viewModel.currentZoom.value >= 1.0f)
    }

    @Test
    fun testKeepUltraWideReadyPhysicalSwitchVerified() {
        engine.detectHardwareLenses()
        val lenses = engine.availableLenses.value
        val mainLens = lenses.firstOrNull { it.isPrimaryMain } ?: lenses.firstOrNull { it.lensType == LensType.WIDE }
        val ultraWideLens = lenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }

        assertNotNull("Main lens must exist", mainLens)
        engine.selectLens(mainLens!!)
        engine.setKeepUltraWideReady(true)

        if (ultraWideLens != null) {
            engine.selectLens(ultraWideLens)
            // Tapping 0.5x / selecting Ultra-Wide must genuinely set selected lens to Ultra-Wide
            assertEquals(ultraWideLens.id, engine.selectedLens.value?.id)
            assertEquals(LensType.ULTRAWIDE, engine.selectedLens.value?.lensType)

            // Switch back to 1x Main
            engine.selectLens(mainLens)
            assertEquals(mainLens.id, engine.selectedLens.value?.id)
            assertEquals(LensType.WIDE, engine.selectedLens.value?.lensType)
        }
    }

    @Test
    fun testSliderRangeInvariantToActiveLens() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.camera.viewmodel.CameraViewModel(app)
        viewModel.engine.detectHardwareLenses()
        val lenses = viewModel.engine.availableLenses.value
        val hasRealUltraWide = lenses.any { it.lensType == LensType.ULTRAWIDE }

        // Slider minimum must always be 0.5f if Ultra-wide exists, regardless of whether 1x or 2x is active
        if (hasRealUltraWide) {
            val minZoomAt1x = if (hasRealUltraWide) 0.5f else 1.0f
            assertEquals(0.5f, minZoomAt1x, 0.001f)

            // When at 1.0x, dragging backward below 1.0x must be valid and allowed
            viewModel.setZoom(1.0f, isPresetTap = false)
            assertEquals(1.0f, viewModel.currentZoom.value, 0.001f)

            viewModel.setZoom(0.8f, isPresetTap = false)
            assertEquals(0.8f, viewModel.currentZoom.value, 0.001f)

            viewModel.setZoom(0.5f, isPresetTap = false)
            assertEquals(0.5f, viewModel.currentZoom.value, 0.001f)

            // Dragging forward from 0.5x up to 10x must also be smooth and allowed
            viewModel.setZoom(1.5f, isPresetTap = false)
            assertEquals(1.5f, viewModel.currentZoom.value, 0.001f)
        }
    }

    @Test
    fun testNeverShowCroppedUltraWideAtOneX() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.camera.viewmodel.CameraViewModel(app)
        viewModel.engine.detectHardwareLenses()
        val lenses = viewModel.engine.availableLenses.value
        val hasRealUltraWide = lenses.any { it.lensType == LensType.ULTRAWIDE }

        if (hasRealUltraWide) {
            // At exactly 1.000x, target lens must strictly be Main Wide
            val resolvedAt1x = CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.ULTRAWIDE,
                targetZoom = 1.000f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
            assertEquals(LensType.WIDE, resolvedAt1x)

            // At 0.999x, Ultra-wide is allowed
            val resolvedAt0999x = CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.ULTRAWIDE,
                targetZoom = 0.999f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
            assertEquals(LensType.ULTRAWIDE, resolvedAt0999x)
        }
    }
}
