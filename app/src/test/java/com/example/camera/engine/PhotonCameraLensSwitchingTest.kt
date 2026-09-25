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
}
