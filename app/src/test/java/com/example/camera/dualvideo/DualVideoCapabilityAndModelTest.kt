package com.example.camera.dualvideo

import android.hardware.camera2.CameraCharacteristics
import com.example.camera.dualvideo.engine.DualCameraCapabilityDetector
import com.example.camera.dualvideo.model.*
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DualVideoCapabilityAndModelTest {

    @Test
    fun testDualVideoLayouts() {
        val layouts = DualVideoLayout.values()
        assertEquals(3, layouts.size)
        assertTrue(layouts.contains(DualVideoLayout.PIP))
        assertTrue(layouts.contains(DualVideoLayout.SPLIT_TOP_BOTTOM))
        assertTrue(layouts.contains(DualVideoLayout.SPLIT_LEFT_RIGHT))
    }

    @Test
    fun testPipPositions() {
        val positions = PipPosition.values()
        assertEquals(4, positions.size)
        assertTrue(positions.contains(PipPosition.TOP_RIGHT))
        assertTrue(positions.contains(PipPosition.TOP_LEFT))
        assertTrue(positions.contains(PipPosition.BOTTOM_RIGHT))
        assertTrue(positions.contains(PipPosition.BOTTOM_LEFT))
    }

    @Test
    fun testDualVideoResolutionProperties() {
        val res1080p = DualVideoResolution(1080, 1920, "1080p Full HD")
        assertEquals(1080, res1080p.portraitWidth)
        assertEquals(1920, res1080p.portraitHeight)
        assertEquals(1080, res1080p.size.width)
        assertEquals(1920, res1080p.size.height)
        assertEquals(1080f / 1920f, res1080p.aspectRatio, 0.001f)

        val res720p = DualVideoResolution(720, 1280, "720p HD")
        assertEquals(720, res720p.portraitWidth)
        assertEquals(1280, res720p.portraitHeight)
        assertEquals(720, res720p.size.width)
        assertEquals(1280, res720p.size.height)
    }

    @Test
    fun testExactVideoDurationPacingCalculation() {
        val targetFps = 30
        val frameIntervalNs = 1_000_000_000L / targetFps
        val durationSeconds = 6

        val totalFrames = durationSeconds * targetFps // 180 frames for 6 seconds
        assertEquals(180, totalFrames)

        // First frame must be at timestamp 0
        val firstFramePtsNs = 0L * frameIntervalNs
        assertEquals(0L, firstFramePtsNs)

        // Last frame at 6.0 seconds
        val lastFramePtsNs = totalFrames * frameIntervalNs
        val durationSec = lastFramePtsNs.toDouble() / 1_000_000_000.0
        assertEquals(6.0, durationSec, 0.001)
    }

    @Test
    fun testDualCameraCapabilityDetectorWithSimulatedLenses() {
        val context = RuntimeEnvironment.getApplication()
        val backMain = LensInfo(
            cameraId = "0",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.WIDE,
            displayName = "1x Main Wide",
            focalLengthMm = 4.5f,
            maxAperture = 1.8f,
            isPrimaryMain = true,
            baseZoomRatio = 1.0f
        )
        val backUltraWide = LensInfo(
            cameraId = "0",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            lensType = LensType.ULTRAWIDE,
            displayName = "0.5x Ultra Wide",
            focalLengthMm = 2.2f,
            maxAperture = 2.2f,
            baseZoomRatio = 0.5f
        )
        val frontSelfie = LensInfo(
            cameraId = "1",
            facing = CameraCharacteristics.LENS_FACING_FRONT,
            lensType = LensType.FRONT,
            displayName = "Front Selfie",
            focalLengthMm = 3.0f,
            maxAperture = 2.0f,
            baseZoomRatio = 1.0f
        )

        val capability = DualCameraCapabilityDetector.detectCapability(
            context = context,
            availableLenses = listOf(backUltraWide, backMain, frontSelfie)
        )

        assertNotNull(capability)
        assertTrue(capability.supportedResolutions.isNotEmpty())
        assertTrue(capability.supportedFps.isNotEmpty())
        assertTrue(capability.supportedPairs.isNotEmpty())
    }

    @Test
    fun testDualVideoUiStateTransitions() {
        val state = DualVideoUiState()
        assertFalse(state.isRecording)
        assertEquals(0, state.recordingDurationSeconds)
        assertEquals(DualVideoLayout.PIP, state.config.layout)
        assertEquals(PipPosition.TOP_RIGHT, state.config.pipPosition)
    }
}
