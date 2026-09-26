package com.example.camera.engine

import com.example.camera.model.LensType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraOpticalCalibrationTest {

    @Test
    fun testHorizontalFovCalculation() {
        // Typical main camera: 7.0mm sensor width, 5.5mm focal length
        // hFOV = 2 * atan(7.0 / 11.0) * 180 / PI ~ 64.93 degrees
        val hFov = CameraOpticalCalibration.calculateHorizontalFovDegrees(7.0f, 5.5f)
        assertTrue("FOV should be ~64.9 degrees", hFov in 64.0f..66.0f)

        // Invalid inputs
        assertEquals(0f, CameraOpticalCalibration.calculateHorizontalFovDegrees(0f, 5.5f), 0.001f)
        assertEquals(0f, CameraOpticalCalibration.calculateHorizontalFovDegrees(7.0f, 0f), 0.001f)
    }

    @Test
    fun testUltraWideCalibratedOpticalRatioAccountsForSensorSizes() {
        // Main: 7.0mm width, 5.5mm focal length (tan(theta/2) = 7.0 / 11.0 = 0.6364)
        // Ultra-Wide: 4.0mm width, 1.8mm focal length (tan(theta/2) = 4.0 / 3.6 = 1.1111)
        // Optical Ratio = 0.6364 / 1.1111 = ~0.57x
        val uwRatio = CameraOpticalCalibration.calculateCalibratedOpticalRatio(
            lensFocalLengthMm = 1.8f,
            lensSensorWidthMm = 4.0f,
            mainFocalLengthMm = 5.5f,
            mainSensorWidthMm = 7.0f,
            lensType = LensType.ULTRAWIDE
        )

        assertTrue("Ultra-wide ratio must be strictly wider than 1.0x Main", uwRatio < 1.0f)
        assertTrue("Ultra-wide ratio should be ~0.57x", uwRatio in 0.55f..0.60f)
    }

    @Test
    fun testMainCameraAlwaysCalibratesToOnePointZero() {
        val mainRatio = CameraOpticalCalibration.calculateCalibratedOpticalRatio(
            lensFocalLengthMm = 5.5f,
            lensSensorWidthMm = 7.0f,
            mainFocalLengthMm = 5.5f,
            mainSensorWidthMm = 7.0f,
            lensType = LensType.WIDE
        )
        assertEquals(1.0f, mainRatio, 0.001f)
    }

    @Test
    fun testUltraWideDigitalCropMappingAvoidsDoubleCropping() {
        val baseUwRatio = 0.5f

        // At 0.5x UI zoom -> full uncropped Ultra-wide sensor (1.0x digital crop)
        val cropAt05 = CameraOpticalCalibration.calculateRequiredDigitalCrop(0.5f, baseUwRatio, LensType.ULTRAWIDE)
        assertEquals(1.0f, cropAt05, 0.001f)

        // At 0.7x UI zoom -> 1.4x digital crop
        val cropAt07 = CameraOpticalCalibration.calculateRequiredDigitalCrop(0.7f, baseUwRatio, LensType.ULTRAWIDE)
        assertEquals(1.4f, cropAt07, 0.001f)

        // At 0.9x UI zoom -> 1.8x digital crop (< 2.0x crop, so strictly wider than 1.0x Main)
        val cropAt09 = CameraOpticalCalibration.calculateRequiredDigitalCrop(0.9f, baseUwRatio, LensType.ULTRAWIDE)
        assertEquals(1.8f, cropAt09, 0.001f)

        // At 1.0x UI zoom -> 2.0x digital crop (matches 1.0x Main FOV)
        val cropAt10 = CameraOpticalCalibration.calculateRequiredDigitalCrop(1.0f, baseUwRatio, LensType.ULTRAWIDE)
        assertEquals(2.0f, cropAt10, 0.001f)

        // Main camera at 1.0x -> 1.0x digital crop
        val mainCropAt10 = CameraOpticalCalibration.calculateRequiredDigitalCrop(1.0f, 1.0f, LensType.WIDE)
        assertEquals(1.0f, mainCropAt10, 0.001f)
    }

    @Test
    fun testHysteresisPreventsOscillationDuringZoomDrag() {
        // When currently on Ultra-Wide:
        // Dragging below 1.0x stays on Ultra-Wide; at >= 1.0x transitions cleanly to Main Wide
        assertEquals(
            LensType.ULTRAWIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.ULTRAWIDE,
                targetZoom = 0.95f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
        )
        // At 1.0x and above, transitions to Wide
        assertEquals(
            LensType.WIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.ULTRAWIDE,
                targetZoom = 1.00f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
        )
        assertEquals(
            LensType.WIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.ULTRAWIDE,
                targetZoom = 1.03f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
        )

        // When currently on Wide (Main):
        // Above 1.0x stays on Wide, transitions to Ultra-Wide below 1.0x
        assertEquals(
            LensType.ULTRAWIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.WIDE,
                targetZoom = 0.98f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
        )
        assertEquals(
            LensType.ULTRAWIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.WIDE,
                targetZoom = 0.95f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
        )
        // Switches to Ultra-Wide below 1.0x
        assertEquals(
            LensType.ULTRAWIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.WIDE,
                targetZoom = 0.90f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
        )
    }

    @Test
    fun testPresetTapAlwaysSelectsCalibratedPhysicalLens() {
        // 1.0x preset tap MUST ALWAYS select Main Wide
        val lensFor1x = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.ULTRAWIDE,
            targetZoom = 1.0f,
            hasUltraWide = true,
            hasTelephoto2x = true,
            hasTelephoto3x = true,
            isPresetTap = true
        )
        assertEquals(LensType.WIDE, lensFor1x)

        // 0.5x preset tap MUST ALWAYS select Ultra-Wide
        val lensFor05x = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.WIDE,
            targetZoom = 0.5f,
            hasUltraWide = true,
            hasTelephoto2x = true,
            hasTelephoto3x = true,
            isPresetTap = true
        )
        assertEquals(LensType.ULTRAWIDE, lensFor05x)
    }
}
