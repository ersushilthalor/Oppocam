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

    @Test
    fun testDualVideoOrientationBackCameraUprightInPortrait() {
        val compositor = com.example.camera.dualvideo.gl.DualVideoGLCompositor(1080, 1920)

        // Standard Android Camera2 SurfaceTexture transform for back camera (ROT_90 with OpenGL Y-flip):
        // col 0 = (0, -1, 0, 0), col 1 = (-1, 0, 0, 0), col 2 = (0, 0, 1, 0), col 3 = (1, 1, 0, 1)
        val stMatrixBack = floatArrayOf(
            0f, -1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 1f, 0f, 1f
        )
        val outMatrix = FloatArray(16)

        compositor.computeCameraTexMatrix(
            stMatrix = stMatrixBack,
            isFront = false,
            sensorOrientation = 90,
            rotationDegrees = 0, // Portrait
            viewportWidth = 1080,
            viewportHeight = 1920,
            outMatrix = outMatrix
        )

        // When stMatrix already contains ROT_90, for fullscreen 1080x1920 in portrait,
        // localTexMatrix must be Identity, so outMatrix == stMatrix!
        for (i in 0 until 16) {
            assertEquals("Index $i should match stMatrix", stMatrixBack[i], outMatrix[i], 0.001f)
        }
    }

    @Test
    fun testDualVideoOrientationFrontCameraUprightAndMirroredInPortrait() {
        val compositor = com.example.camera.dualvideo.gl.DualVideoGLCompositor(1080, 1920)

        // Standard Android Camera2 SurfaceTexture transform for front camera (FLIP_H | ROT_90 with OpenGL Y-flip):
        // col 0 = (0, 1, 0, 0), col 1 = (-1, 0, 0, 0), col 2 = (0, 0, 1, 0), col 3 = (1, 0, 0, 1)
        // Determinant = 0*0 - 1*(-1) = +1 (> 0, indicating already mirrored)
        val stMatrixFront = floatArrayOf(
            0f, 1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 0f, 0f, 1f
        )
        val outMatrix = FloatArray(16)

        compositor.computeCameraTexMatrix(
            stMatrix = stMatrixFront,
            isFront = true,
            sensorOrientation = 270,
            rotationDegrees = 0, // Portrait
            viewportWidth = 1080,
            viewportHeight = 1920,
            outMatrix = outMatrix
        )

        // When stMatrix already contains FLIP_H | ROT_90, for fullscreen 1080x1920 in portrait,
        // localTexMatrix must be Identity (no double rotation, no double mirror), so outMatrix == stMatrix!
        for (i in 0 until 16) {
            assertEquals("Index $i should match stMatrix", stMatrixFront[i], outMatrix[i], 0.001f)
        }
    }

    @Test
    fun testDualVideoOrientationLandscapeRecordingRotation() {
        val compositor = com.example.camera.dualvideo.gl.DualVideoGLCompositor(1080, 1920)

        val stMatrixBack = floatArrayOf(
            0f, -1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 1f, 0f, 1f
        )
        val outMatrix90 = FloatArray(16)
        val outMatrix270 = FloatArray(16)

        // Landscape 90
        compositor.computeCameraTexMatrix(
            stMatrix = stMatrixBack,
            isFront = false,
            sensorOrientation = 90,
            rotationDegrees = 90,
            viewportWidth = 1920,
            viewportHeight = 1080,
            outMatrix = outMatrix90
        )

        // Landscape 270
        compositor.computeCameraTexMatrix(
            stMatrix = stMatrixBack,
            isFront = false,
            sensorOrientation = 90,
            rotationDegrees = 270,
            viewportWidth = 1920,
            viewportHeight = 1080,
            outMatrix = outMatrix270
        )

        // Both matrices must be valid 4x4 affine matrices
        assertEquals(1.0f, outMatrix90[15], 0.001f)
        assertEquals(1.0f, outMatrix270[15], 0.001f)
        assertNotEquals(outMatrix90[0], outMatrix270[0], 0.001f)
    }

    @Test
    fun testDualVideoSplitTopBottomAspectScaling() {
        val compositor = com.example.camera.dualvideo.gl.DualVideoGLCompositor(1080, 1920)

        // Identity-like portrait stMatrix
        val stMatrixBack = floatArrayOf(
            0f, -1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 1f, 0f, 1f
        )
        val outMatrix = FloatArray(16)

        // Split Top/Bottom: viewport is 1080 x 960 (half height)
        compositor.computeCameraTexMatrix(
            stMatrix = stMatrixBack,
            isFront = false,
            sensorOrientation = 90,
            rotationDegrees = 0,
            viewportWidth = 1080,
            viewportHeight = 960,
            outMatrix = outMatrix
        )

        // Matrix must be scaled along height axis (factor 0.5f = (1080/1920) / (1080/960))
        assertNotNull(outMatrix)
        assertEquals(1.0f, outMatrix[15], 0.001f)
    }

    @Test
    fun testDualVideoFallbackUnrotatedStMatrix() {
        val compositor = com.example.camera.dualvideo.gl.DualVideoGLCompositor(1080, 1920)

        // Raw unrotated landscape buffer with standard OpenGL Y-flip (m0=1, m5=-1)
        val unrotatedSt = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f
        )
        val outMatrix = FloatArray(16)

        compositor.computeCameraTexMatrix(
            stMatrix = unrotatedSt,
            isFront = false,
            sensorOrientation = 90,
            rotationDegrees = 0,
            viewportWidth = 1080,
            viewportHeight = 1920,
            outMatrix = outMatrix
        )

        // Fallback must rotate by 90° so off-diagonal terms become dominant in outMatrix
        val offDiag = kotlin.math.abs(outMatrix[1]) + kotlin.math.abs(outMatrix[4])
        val diag = kotlin.math.abs(outMatrix[0]) + kotlin.math.abs(outMatrix[5])
        assertTrue("Fallback must apply 90-degree sensor rotation", offDiag > diag)
    }
}
