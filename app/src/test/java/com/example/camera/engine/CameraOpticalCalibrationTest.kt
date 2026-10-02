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
        // Ultra-wide native FOV is preserved at 0.5x
        val uwRatio = CameraOpticalCalibration.calculateCalibratedOpticalRatio(
            lensFocalLengthMm = 1.8f,
            lensSensorWidthMm = 4.0f,
            mainFocalLengthMm = 5.5f,
            mainSensorWidthMm = 7.0f,
            lensType = LensType.ULTRAWIDE
        )

        assertEquals("Ultra-wide native FOV must be preserved at 0.5x", 0.5f, uwRatio, 0.001f)
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
    fun testUltraWideCropLimitCalculation() {
        // Ultra-Wide is ~16mm equivalent, Main 1x lens is ~23mm equivalent.
        // Required crop factor is 23 / 16 ≈ 1.4375x (≈ 1.44x)
        val limit = CameraOpticalCalibration.calculateUltraWideCropLimit(16.0f, 23.0f)
        assertEquals(1.44f, limit, 0.01f)
    }

    @Test
    fun testUltraWideDigitalCropMappingAvoidsDoubleCropping() {
        val baseUwRatio = 0.5f

        // At 0.5x UI zoom -> full uncropped Ultra-wide sensor (1.0x digital crop)
        val cropAt05 = CameraOpticalCalibration.calculateRequiredDigitalCrop(0.5f, baseUwRatio, LensType.ULTRAWIDE)
        assertEquals(1.0f, cropAt05, 0.001f)

        // At 0.7x UI zoom -> intermediate crop smoothly interpolated between 1.0x and 1.44x
        val cropAt07 = CameraOpticalCalibration.calculateRequiredDigitalCrop(0.7f, baseUwRatio, LensType.ULTRAWIDE)
        assertTrue("Crop at 0.7x must be > 1.0x and < 1.44x", cropAt07 in 1.15f..1.22f)

        // At 1.0x UI zoom -> approximately 1.44x digital crop (matches 1.0x Main FOV)
        val cropAt10 = CameraOpticalCalibration.calculateRequiredDigitalCrop(1.0f, baseUwRatio, LensType.ULTRAWIDE)
        assertEquals(1.44f, cropAt10, 0.01f)

        // Do NOT crop the Ultra-Wide up to 2x: at 2.0x UI zoom, Ultra-Wide digital crop remains capped at ~1.44x
        val cropAt20 = CameraOpticalCalibration.calculateRequiredDigitalCrop(2.0f, baseUwRatio, LensType.ULTRAWIDE)
        assertEquals(1.44f, cropAt20, 0.01f)

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
