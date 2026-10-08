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

        // At 0.999x UI zoom -> maximum allowed Ultra-wide crop strictly below 1.000x
        val cropAt0999 = CameraOpticalCalibration.calculateRequiredDigitalCrop(0.999f, baseUwRatio, LensType.ULTRAWIDE)
        assertEquals(1.44f, cropAt0999, 0.02f)

        // At 1.000x UI zoom -> camera switches to Main Wide where crop is 1.0x (native 1x FOV)
        val mainCropAt10 = CameraOpticalCalibration.calculateRequiredDigitalCrop(1.0f, 1.0f, LensType.WIDE)
        assertEquals(1.0f, mainCropAt10, 0.001f)
    }

    @Test
    fun testHysteresisPreventsOscillationDuringZoomDrag() {
        // At 0.999x Ultra-wide is allowed; at exactly 1.000x Main Wide is strictly required
        assertEquals(
            LensType.ULTRAWIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.ULTRAWIDE,
                targetZoom = 0.999f,
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
                targetZoom = 1.000f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
        )
        assertEquals(
            LensType.WIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.WIDE,
                targetZoom = 1.000f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
        )
        // Hysteresis buffer holds WIDE during downward drag from WIDE above (1.0 - 0.04) = 0.96x
        assertEquals(
            LensType.WIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.WIDE,
                targetZoom = 0.999f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
        )
        // Preset tap at 0.999x immediately selects ULTRAWIDE without drag hysteresis
        assertEquals(
            LensType.ULTRAWIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.WIDE,
                targetZoom = 0.999f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = true
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
        // Above 0.96x (1.0x - 0.04x buffer), dragging down stays on Wide due to hysteresis
        assertEquals(
            LensType.WIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.WIDE,
                targetZoom = 0.98f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = false
            )
        )
        // Preset tap at 0.98x immediately selects Ultra-Wide without hysteresis
        assertEquals(
            LensType.ULTRAWIDE,
            CameraOpticalCalibration.resolveTargetLensType(
                currentLensType = LensType.WIDE,
                targetZoom = 0.98f,
                hasUltraWide = true,
                hasTelephoto2x = false,
                hasTelephoto3x = false,
                isPresetTap = true
            )
        )
        // Below 0.96x (e.g. 0.95x), drag transitions to Ultra-Wide
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

    @Test
    fun testSwitchPointCropCalculationsMatchUserSpecifications() {
        // Ultra-wide crop = Switch Point ÷ 16mm
        // Main crop = Switch Point ÷ 23mm

        // 32mm: UW 2.00x, Main 1.39x
        assertEquals(2.00f, CameraOpticalCalibration.calculateUltraWideCropForSwitchPoint(32.0f), 0.01f)
        assertEquals(1.39f, CameraOpticalCalibration.calculateMainCropForSwitchPoint(32.0f), 0.01f)

        // 40mm: UW 2.50x, Main 1.74x
        assertEquals(2.50f, CameraOpticalCalibration.calculateUltraWideCropForSwitchPoint(40.0f), 0.01f)
        assertEquals(1.74f, CameraOpticalCalibration.calculateMainCropForSwitchPoint(40.0f), 0.01f)

        // 50mm: UW 3.13x, Main 2.17x
        assertEquals(3.13f, CameraOpticalCalibration.calculateUltraWideCropForSwitchPoint(50.0f), 0.01f)
        assertEquals(2.17f, CameraOpticalCalibration.calculateMainCropForSwitchPoint(50.0f), 0.01f)

        // 85mm: UW 5.31x, Main 3.70x
        assertEquals(5.31f, CameraOpticalCalibration.calculateUltraWideCropForSwitchPoint(85.0f), 0.01f)
        assertEquals(3.70f, CameraOpticalCalibration.calculateMainCropForSwitchPoint(85.0f), 0.01f)
    }

    @Test
    fun testFocalLengthAndZoomConversions() {
        assertEquals(16.0f, CameraOpticalCalibration.zoomToFocalLengthMm(0.5f), 0.1f)
        assertEquals(23.0f, CameraOpticalCalibration.zoomToFocalLengthMm(1.0f), 0.1f)
        assertEquals(32.0f, CameraOpticalCalibration.zoomToFocalLengthMm(32.0f / 23.0f), 0.1f)
        assertEquals(50.0f, CameraOpticalCalibration.zoomToFocalLengthMm(50.0f / 23.0f), 0.1f)
        assertEquals(85.0f, CameraOpticalCalibration.zoomToFocalLengthMm(85.0f / 23.0f), 0.1f)

        assertEquals(0.5f, CameraOpticalCalibration.focalLengthMmToZoom(16.0f), 0.01f)
        assertEquals(1.0f, CameraOpticalCalibration.focalLengthMmToZoom(23.0f), 0.01f)
        assertEquals(1.39f, CameraOpticalCalibration.focalLengthMmToZoom(32.0f), 0.01f)
        assertEquals(2.17f, CameraOpticalCalibration.focalLengthMmToZoom(50.0f), 0.01f)
        assertEquals(3.70f, CameraOpticalCalibration.focalLengthMmToZoom(85.0f), 0.01f)
    }

    @Test
    fun testCustomSwitchPointDeterminesLensSwitchPoint() {
        // At user-selected 32mm (~1.39x switch point):
        val switch32mm = 32.0f
        val switchZoom32 = 32.0f / 23.0f // ~1.3913x

        // Below switch point (e.g. 1.2x) -> Ultra-wide
        val lensAt12 = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.ULTRAWIDE,
            targetZoom = 1.20f,
            hasUltraWide = true,
            hasTelephoto2x = false,
            hasTelephoto3x = false,
            isPresetTap = true,
            switchPointMm = switch32mm
        )
        assertEquals(LensType.ULTRAWIDE, lensAt12)

        // At or above switch point (e.g. 1.40x) -> Main Wide
        val lensAt14 = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.ULTRAWIDE,
            targetZoom = 1.40f,
            hasUltraWide = true,
            hasTelephoto2x = false,
            hasTelephoto3x = false,
            isPresetTap = true,
            switchPointMm = switch32mm
        )
        assertEquals(LensType.WIDE, lensAt14)

        // At user-selected 50mm (~2.17x switch point):
        val switch50mm = 50.0f
        val lensAt20Under50 = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.ULTRAWIDE,
            targetZoom = 2.00f,
            hasUltraWide = true,
            hasTelephoto2x = false,
            hasTelephoto3x = false,
            isPresetTap = true,
            switchPointMm = switch50mm
        )
        assertEquals(LensType.ULTRAWIDE, lensAt20Under50)

        val lensAt23Under50 = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = LensType.ULTRAWIDE,
            targetZoom = 2.30f,
            hasUltraWide = true,
            hasTelephoto2x = false,
            hasTelephoto3x = false,
            isPresetTap = true,
            switchPointMm = switch50mm
        )
        assertEquals(LensType.WIDE, lensAt23Under50)
    }
}
